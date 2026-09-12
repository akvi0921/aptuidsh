package com.aptuidsh.kui.net;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DeepSeek 账户余额查询与密钥管理。
 *
 * <p><b>官方接口(实测)</b>：{@code GET https://api.deepseek.com/user/balance}，
 * 头 {@code Authorization: Bearer <key>}，返回
 * <pre>{"is_available":true,
 *  "balance_infos":[{"currency":"CNY","total_balance":"6.92",
 *                    "granted_balance":"0.00","topped_up_balance":"6.92"}]}</pre>
 *
 * <p><b>为什么不能直接读 DSH 的凭据文件(踩坑记录)</b>：APP 与 Termux 是
 * <b>不同的 Android 沙箱</b>(实测 Termux UID 10199、APP UID 10328)，
 * 而 {@code ~/.dsh/.credentials.yaml} 权限 0600、其上级目录 0700 且属于 Termux UID。
 * APP 连该目录都无法进入，**无论代码怎么写都读不到**——这是操作系统级隔离，
 * 不是代码缺陷。早期实现试图直接读该文件，因此必然报「未找到密钥」。
 *
 * <p><b>正确路径(本类实现)</b>：DSH 后端自身有 {@code credentials.*} RPC 面，
 * APP 经 HTTP 调本地后端即可，无需任何文件权限：
 * <ul>
 *   <li>{@code credentials.describe(refs)} → {@code {configured, source, writable}}</li>
 *   <li>{@code credentials.set(ref, value)} → 把密钥写进后端凭据存储</li>
 * </ul>
 * 密钥从不由 APP 落盘(除非用户选择"仅本机保存"的本地兜底)。
 *
 * <p><b>余额是"提供方级"而非"模型级"</b>：余额挂在账户(API Key)上，
 * 同一提供方下所有模型共享同一份余额。因此按提供方判定：
 * 只有 {@link #BALANCE_CAPABLE_PROVIDERS} 中登记的提供方才会真正请求，
 * 其余(如 qwen-token-plan/MiniMax)返回"该提供方不支持获取余额"。
 *
 * <p>线程模型：单线程 daemon 执行器；回调在工作线程触发，调用方自行切主线程。
 */
public final class BalanceClient {

    /** DeepSeek 开放平台 base(与 dsh-llm-deepseek 适配器默认一致)。 */
    private static final String DEEPSEEK_BASE = "https://api.deepseek.com";
    /** 余额端点。 */
    private static final String BALANCE_PATH = "/user/balance";
    /** 后端凭据引用名(与 dsh-llm-deepseek 一致)。 */
    public static final String KEY_REF = "DEEPSEEK_API_KEY";

    /** 已知可查余额的提供方 id 集合(按提供方判定,非按模型判定)。 */
    private static final String[] BALANCE_CAPABLE_PROVIDERS = {
        "deepseek-official",
    };

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    /** 密钥来源(决定提示文案与后续动作)。 */
    public enum KeySource {
        /** 后端已配置(credentials.describe → configured=true)。 */
        BACKEND,
        /** APP 本地兜底保存(后端不可用或未配置时用户手填)。 */
        LOCAL,
        /** 都没有 → 需要用户输入。 */
        NONE,
    }

    /** 查询结果：成功时 amount 非空；失败/不支持时 error 说明原因。 */
    public static final class Result {
        public final String amount;
        public final String currency;
        public final String toppedUp;
        public final String granted;
        public final boolean available;
        public final String error;
        /** 是否属于"该提供方不支持余额查询"(区别于网络/密钥失败)。 */
        public final boolean unsupported;
        /** 是否缺少密钥(UI 据此弹输入框)。 */
        public final boolean needsKey;

        private Result(String amount, String currency, String toppedUp, String granted,
                       boolean available, String error, boolean unsupported, boolean needsKey) {
            this.amount = amount;
            this.currency = currency;
            this.toppedUp = toppedUp;
            this.granted = granted;
            this.available = available;
            this.error = error;
            this.unsupported = unsupported;
            this.needsKey = needsKey;
        }

        static Result ok(String amount, String currency, String toppedUp, String granted, boolean available) {
            return new Result(amount, currency, toppedUp, granted, available, "", false, false);
        }

        /** 构造失败结果(供 UI 层在后端不可用等场景直接构造)。 */
        public static Result fail(String error) {
            return new Result("", "", "", "", false, error, false, false);
        }

        static Result unsupported() {
            return new Result("", "", "", "", false, "当前提供方不支持获取余额", true, false);
        }

        static Result needKey(String error) {
            return new Result("", "", "", "", false, error, false, true);
        }

        public boolean isOk() {
            return error.isEmpty();
        }

        /**
         * 展示用文本。
         *
         * <p>按 UI 草图：余额只显示数字(如 {@code 6.92})，不带 ¥ 符号——
         * 币种信息由旁边的「余额:」标签隐含，多语言下也更干净。
         */
        public String display() {
            if (!isOk()) return error;
            return amount;
        }
    }

    /** 异步回调(工作线程触发)。 */
    public interface Callback {
        void onResult(Result result);
    }

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dsh-balance");
        t.setDaemon(true);
        return t;
    });

    private BalanceClient() {
    }

    /** 该提供方是否已知可查余额(按提供方判定)。 */
    public static boolean isBalanceCapable(String providerId) {
        if (providerId == null) return false;
        for (String p : BALANCE_CAPABLE_PROVIDERS) {
            if (p.equalsIgnoreCase(providerId.trim())) return true;
        }
        return false;
    }

    /**
     * 查询某提供方余额(异步)。
     *
     * <p>密钥解析顺序(对齐用户要求「每次打开检查,后端找不到则用本地,都没有则弹输入框」)：
     * <ol>
     *   <li>后端凭据({@code credentials.describe} 报告 configured=true)——
     *       但后端只报告"有没有",不返回明文，所以实际请求仍需要一份可用 key；
     *       若 APP 本地也存过同一把 key 则直接用它，否则用本地兜底 key。</li>
     *   <li>APP 本地兜底存储。</li>
     *   <li>都没有 → 返回 {@code needsKey=true}，UI 弹输入框。</li>
     * </ol>
     *
     * @param providerId 当前会话选中的提供方 id。
     * @param localKey APP 本地保存的 key(可为 null)。
     * @param backendConfigured 后端是否已配置该凭据(由调用方先查 credentials.describe)。
     * @param cb 结果回调(工作线程)。
     */
    public static void fetch(final String providerId, final String localKey,
                             final boolean backendConfigured, final Callback cb) {
        POOL.execute(() -> {
            Result r = fetchBlocking(providerId, localKey, backendConfigured);
            if (cb != null) cb.onResult(r);
        });
    }

    /**
     * 同步查询(内部/测试用)。
     *
     * @param providerId 提供方 id。
     * @param localKey 本地保存的 key(可空)。
     * @param backendConfigured 后端是否已配置凭据。
     */
    public static Result fetchBlocking(String providerId, String localKey, boolean backendConfigured) {
        String pid = providerId == null ? "" : providerId.trim();
        if (pid.isEmpty() || !isBalanceCapable(pid)) {
            return Result.unsupported();
        }
        String key = localKey == null ? "" : localKey.trim();
        if (key.isEmpty() && backendConfigured) {
            // 后端有凭据但 APP 拿不到明文(后端 RPC 只暴露"是否配置",不返回密钥)。
            // 这是设计上的安全边界:提示用户在本机补一次 key,而不是假装能读到。
            return Result.needKey("后端已配置密钥,但 APP 无法读取;请在此输入一次以便本机查询");
        }
        if (key.isEmpty()) {
            return Result.needKey("未配置密钥,请点击提供方输入 API Key");
        }
        return queryBalance(DEEPSEEK_BASE + BALANCE_PATH, key);
    }

    /** 把密钥写入 DSH 后端(credentials.set)。 */
    public static void saveToBackend(DshGateway gateway, String key, DshClient.DshCallback cb) {
        if (gateway == null) return;
        gateway.client().credentialsSet(KEY_REF, key, cb);
    }

    /** 查询后端凭据是否已配置。 */
    public static void describeBackend(DshGateway gateway, DshClient.DshCallback cb) {
        if (gateway == null) return;
        JSONArray arr = new JSONArray();
        arr.put(KEY_REF);
        gateway.client().credentialsDescribe(arr, cb);
    }

    /** 解析 credentials.describe 响应 → configured 布尔。 */
    public static boolean parseConfigured(JSONObject value) {
        if (value == null) return false;
        JSONObject creds = value.optJSONObject("credentials");
        if (creds == null) return false;
        JSONObject c = creds.optJSONObject(KEY_REF);
        return c != null && c.optBoolean("configured", false);
    }

    /** 执行一次余额请求并解析。 */
    private static Result queryBalance(String url, String key) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Authorization", "Bearer " + key);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Connection", "close");

            int status = conn.getResponseCode();
            InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = is != null ? readAll(is) : "";
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
            }
            if (status == 401 || status == 403) {
                return Result.fail("密钥无效或无权查询余额");
            }
            if (status != 200) {
                return Result.fail("余额查询失败(HTTP " + status + ")");
            }
            return parseBalance(body);
        } catch (IOException e) {
            return Result.fail("余额查询网络错误");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 解析 /user/balance 响应体。 */
    static Result parseBalance(String body) {
        try {
            JSONObject o = new JSONObject(body);
            boolean available = o.optBoolean("is_available", false);
            JSONArray infos = o.optJSONArray("balance_infos");
            if (infos == null || infos.length() == 0) {
                return Result.fail("余额响应缺少 balance_infos");
            }
            JSONObject pick = infos.optJSONObject(0);
            for (int i = 0; i < infos.length(); i++) {
                JSONObject it = infos.optJSONObject(i);
                if (it != null && "CNY".equalsIgnoreCase(it.optString("currency", ""))) {
                    pick = it;
                    break;
                }
            }
            if (pick == null) return Result.fail("余额响应格式异常");
            return Result.ok(
                pick.optString("total_balance", "0"),
                pick.optString("currency", "CNY"),
                pick.optString("topped_up_balance", ""),
                pick.optString("granted_balance", ""),
                available
            );
        } catch (JSONException e) {
            return Result.fail("余额响应解析失败");
        }
    }

    // ==================== APP 本地 key 存储 ====================
    // 说明:APP 无法读 DSH 的文件(沙箱隔离),因此当后端未配置或用户希望
    // 本机独立查询时,把 key 存进 APP 自己的 SharedPreferences(仅本应用可读)。
    // 这是"本地兜底"路径,不是主路径(主路径是 credentials.set 写回后端)。

    private static final String PREFS = "aptuidsh_secrets";
    private static final String PREF_KEY = "deepseek_api_key";

    /** 读取本地保存的 key(无则返回空串)。 */
    public static String loadLocalKey(android.content.Context ctx) {
        if (ctx == null) return "";
        try {
            return ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getString(PREF_KEY, "");
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** 保存 key 到本地(传空串=清除)。 */
    public static void saveLocalKey(android.content.Context ctx, String key) {
        if (ctx == null) return;
        try {
            android.content.SharedPreferences.Editor e =
                ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit();
            if (key == null || key.trim().isEmpty()) {
                e.remove(PREF_KEY);
            } else {
                e.putString(PREF_KEY, key.trim());
            }
            e.apply();
        } catch (RuntimeException ignored) {
        }
    }

    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
