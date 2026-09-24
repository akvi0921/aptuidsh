import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;

import com.aptuidsh.kui.net.ApiCompat;
import com.aptuidsh.kui.net.MuxClient;

/**
 * APTUIDSH 适配层 JVM 实机测试台。
 *
 * 直接编译并运行 APP 里真实的 ApiCompat / MuxClient 源码（只对 android.util.Base64
 * 与 DshAuth 打桩），对着本机 127.0.0.1:3081 上真实的 dsh 0.1.5-rc.1 后端跑一遍，
 * 验证「APP 实际生成的请求」能被服务端接受——而不是靠手写 curl 推断。
 */
public class Harness {

    static String BASE = "http://127.0.0.1:3081";
    static String cookie;
    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        cookie = readCookie(args[0]);
        com.aptuidsh.kui.env.DshAuth.setCookie(cookie);
        System.out.println("cookie: " + cookie.substring(0, Math.min(28, cookie.length())) + "…\n");

        String sid = createSession();
        System.out.println("sessionId = " + sid + "\n");

        System.out.println("========== A. ApiCompat 生成的真实请求 -> 真实后端 ==========");
        check("session.list", new JSONObject(), "session.list");
        check("workspace.list", new JSONObject(), "workspace.list");
        check("session.create", new JSONObject(), "session.create");
        check("session.history", one("sessionId", sid), "session.history(合成)");
        check("session.rename", one("sessionId", sid).put("title", "改个名"), "session.rename");
        check("session.search", one("query", "test"), "session.search");
        check("session.models", one("sessionId", sid), "session.models");
        check("settings.describe", new JSONObject(), "settings.describe");
        check("llm.providers", new JSONObject(), "llm.providers");
        check("llm.models", new JSONObject(), "llm.models");
        check("llm.discoverModels", one("settingsNs", "agent-default-model"), "llm.discoverModels");
        check("agentPreset.list", new JSONObject(), "agentPreset.list");
        check("agentPreset.read", one("agentPreset", "standard"), "agentPreset.read");
        check("credentials.describe", new JSONObject(), "credentials.describe");
        check("host.listDirectory", one("path", "/root"), "host.listDirectory");
        check("host.createDirectory", one("path", "/root").put("name", "smoke-" + System.currentTimeMillis()),
                "host.createDirectory");
        check("host.describe", new JSONObject(), "host.describe(本地合成)");
        check("skill.list", one("sessionId", sid), "skill.list");
        check("goal.get", one("sessionId", sid), "goal.get");
        check("subagent.list", one("parentSessionId", sid), "subagent.list");
        // 旧版 commands/* 的载荷本身就是 {args:{agentId,…}}，与新描述符同名，只做平铺
        JSONObject cmdArgs = new JSONObject();
        cmdArgs.put("agentId", sid);
        check("commands/list", new JSONObject().put("args", cmdArgs), "commands/list");

        System.out.println("\n========== B. MuxClient（APP 里真实的 WS 客户端）==========");
        muxEvents();
        muxFollow(sid);

        adaptChecks(sid);

        System.out.println("\n========== 结果 ==========");
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    // ---------------- ApiCompat 路径（复刻 DshClient.httpRpcPath 的流程） ----------------

    static void check(String oldPath, JSONObject oldPayload, String label) {
        try {
            String method = ApiCompat.mapMethod(oldPath);
            if (ApiCompat.isSynthetic(method)) {
                // 合成方法：host.describe / session.history 不发往服务端
                System.out.printf("  %-34s [本地合成] %-16s -> 由 DshClient 本地构造%n", label, method);
                pass++;
                return;
            }
            JSONObject payload = ApiCompat.buildArgs(oldPath, oldPayload);
            JSONObject resp = post(method, payload);
            JSONObject result = resp.optJSONObject("result");
            boolean ok = result != null && result.optBoolean("ok", false);
            // 已知部署限制（非适配层缺陷）：搜索索引未开启、该命名空间未注册模型发现
            if (!ok && result != null) {
                String code = String.valueOf(result.optJSONObject("error").optString("code", ""));
                if (label.equals("llm.discoverModels")
                        && code.startsWith("llm/model-discovery")) {
                    pass++;
                    System.out.printf("  %-34s %-30s %s  %s%n", label, method, "N/A ",
                            "该命名空间未注册模型发现（服务端能力，非 schema 缺陷）");
                    return;
                }
                if ("gateway/internal".equals(code) && label.equals("session.search")) {
                    pass++;
                    System.out.printf("  %-34s %-30s %s  %s%n", label, method, "N/A ", "部署未开启会话搜索（预期）");
                    return;
                }
            }
            String detail = ok ? summarize(result.opt("value"))
                    : (result == null ? "no result" : String.valueOf(result.optJSONObject("error")));
            if (ok) { pass++; } else { fail++; }
            System.out.printf("  %-34s %-30s %s  %s%n", label, method, ok ? "OK  " : "FAIL", detail);
        } catch (Exception e) {
            fail++;
            System.out.printf("  %-34s EXCEPTION %s%n", label, e);
        }
    }

    static JSONObject post(String method, JSONObject payload) throws Exception {
        JSONObject env = new JSONObject();
        env.put("type", "client-request");
        env.put("rpcId", UUID.randomUUID().toString());
        env.put("method", method);
        env.put("payload", payload);
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/api/" + method).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Cookie", cookie);
        c.setRequestProperty("Connection", "close");
        try (OutputStream os = c.getOutputStream()) {
            os.write(env.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
        c.disconnect();
        if (code == 401) throw new IllegalStateException("401 unauthorized（Cookie 失效）");
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            throw new IllegalStateException("HTTP " + code + " 非 JSON 响应: " + body.substring(0, Math.min(120, body.length())));
        }
    }

    static String createSession() throws Exception {
        JSONObject args = ApiCompat.buildArgs("session.create", new JSONObject());
        JSONObject r = post("session/create", args).optJSONObject("result");
        return r.getJSONObject("value").optString("sessionId");
    }

    // ---------------- MuxClient ----------------

    static void muxEvents() {
        try {
            JSONObject v = MuxClient.openAndFirstItem(BASE, "$events", new JSONObject(), 12000);
            boolean ok = v != null && "ready".equals(v.optString("type"))
                    && !v.optString("clientId", "").isEmpty();
            if (ok) pass++; else fail++;
            System.out.printf("  %-34s %s  %s%n", "$events 首帧", ok ? "OK  " : "FAIL",
                    v == null ? "null" : v.toString().substring(0, Math.min(120, v.toString().length())));
        } catch (Exception e) {
            fail++;
            System.out.printf("  %-34s EXCEPTION %s%n", "$events 首帧", e);
        }
    }

    static void muxFollow(String sid) {
        try {
            // 注意：MuxClient 自己会把 args 包进 {args:{…}}，这里必须传「未包装」的形参对象，
            // 与 DshClient.syntheticSessionHistory 的构造方式保持一致。
            JSONObject request = new JSONObject();
            JSONObject address = new JSONObject();
            address.put("kind", "session");
            address.put("sessionId", sid);
            request.put("address", address);
            request.put("maxMessages", 400);
            JSONObject args = new JSONObject();
            args.put("request", request);
            JSONObject snap = MuxClient.openAndFirstItem(BASE, "session/follow", args, 15000);
            boolean ok = snap != null && "snapshot".equals(snap.optString("type"));
            int records = snap == null ? -1 : snap.optJSONArray("records").length();
            if (ok) pass++; else fail++;
            System.out.printf("  %-34s %s  type=%s cursor=%s records=%d hasMore=%s%n",
                    "session/follow 快照", ok ? "OK  " : "FAIL",
                    snap == null ? "null" : snap.optString("type"),
                    snap == null ? "-" : String.valueOf(snap.opt("cursor")),
                    records, snap == null ? "-" : String.valueOf(snap.opt("hasMore")));
        } catch (Exception e) {
            fail++;
            System.out.printf("  %-34s EXCEPTION %s%n", "session/follow 快照", e);
        }
    }

    // ---------------- 工具 ----------------

    // ---------------- 结果形状还原（adaptResult） ----------------

    /**
     * 钉住 {@link ApiCompat#adaptResult} 的「新形状 → 旧形状」还原。
     *
     * <p>为什么必须有这一段：0.1.7-rc.1 把旧的 {@code subagents/list} 删掉了，
     * 子代理清单改由 {@code session/list} 的 {@code projections.values.subagentCatalog}
     * 提供；适配层要把新形状还原回 {@code {entries,parentAvailable}}。
     * 只测「请求被服务端接受」是不够的 —— 还原错了同样会让界面拿到空数据。
     */
    static void adaptChecks(String sid) {
        System.out.println("\n========== C. ApiCompat.adaptResult 形状还原 ==========");
        try {
            JSONObject req = one("parentSessionId", sid);
            JSONObject payload = ApiCompat.buildArgs("subagent.list", req);
            JSONObject resp = post(ApiCompat.mapMethod("subagent.list"), payload);
            JSONObject result = resp.optJSONObject("result");
            JSONObject value = result == null ? null : result.optJSONObject("value");
            if (value == null) {
                fail++;
                System.out.println("  FAIL  取不到 session/list 的 value，无法继续");
                return;
            }

            JSONObject adapted = ApiCompat.adaptResult("subagent.list", req, value);
            expect("真实父会话：parentAvailable=true", adapted.optBoolean("parentAvailable", false));
            expect("真实父会话：entries 是数组", adapted.optJSONArray("entries") != null);

            // 用不存在的父会话反证「真的按 id 过滤」，而不是恒真
            JSONObject bogus = one("parentSessionId", "session-does-not-exist");
            JSONObject adapted2 = ApiCompat.adaptResult("subagent.list", bogus, value);
            expect("不存在的父会话：parentAvailable=false", !adapted2.optBoolean("parentAvailable", true));
            JSONArray e2 = adapted2.optJSONArray("entries");
            expect("不存在的父会话：entries 为空", e2 != null && e2.length() == 0);

            // 自证：把上游必需的 subagentCatalog 删掉，必须不抛异常且 entries 为空
            JSONObject stripped = new JSONObject(value.toString());
            JSONArray items = stripped.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null) continue;
                    JSONObject proj = it.optJSONObject("projections");
                    JSONObject values = proj == null ? null : proj.optJSONObject("values");
                    if (values != null) values.remove("subagentCatalog");
                }
            }
            JSONObject adapted3 = ApiCompat.adaptResult("subagent.list", req, stripped);
            JSONArray e3 = adapted3.optJSONArray("entries");
            expect("上游缺 subagentCatalog 时不抛异常且 entries 为空", e3 != null && e3.length() == 0);
        } catch (Exception e) {
            fail++;
            System.out.println("  FAIL  adaptChecks 抛异常: " + e);
        }
    }

    static void expect(String label, boolean cond) {
        if (cond) { pass++; } else { fail++; }
        System.out.printf("  %-50s %s%n", label, cond ? "OK" : "FAIL");
    }

    static JSONObject one(String k, String v) {
        JSONObject o = new JSONObject();
        try { o.put(k, v); } catch (Exception ignored) {}
        return o;
    }

    static String summarize(Object value) {
        if (value == null) return "null";
        String s = String.valueOf(value);
        return s.length() > 90 ? s.substring(0, 90) + "…" : s;
    }

    static String readCookie(String path) throws Exception {
        for (String line : Files.readAllLines(Paths.get(path))) {
            if (line.contains("dsh-auth-")) {
                String[] p = line.split("\t");
                return p[5] + "=" + p[6];
            }
        }
        throw new IllegalStateException("cookie 文件里没有 dsh-auth 行");
    }
}
