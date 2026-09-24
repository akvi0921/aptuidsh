package com.aptuidsh.kui.net;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * DSH 后端协议层客户端（devcrew 契约 1）。
 *
 * <p>传输：HTTP POST /api/&lt;method&gt;，请求信封
 * {@code {"type":"client-request","rpcId":"<uuid>","method":"<m>","payload":{...}}}，
 * 响应信封 {@code {"type":"server-response","rpcId":"...","result":{"ok":true,"value":...}}}
 * 或 {@code {"ok":false,"error":{"code","message","details"}}}。
 *
 * <p>要点（依据 docs/protocol-notes.md 实测）：
 * <ul>
 *   <li>所有 RPC 在后台线程执行（内部线程池），永不阻塞主线程；10s 连接/读取超时。</li>
 *   <li>业务错误也以 HTTP 200 返回，必须解析信封的 {@code result.ok} 判断成败。</li>
 *   <li>错误透传：{@code error.code} 为字符串（如 "bad-request"）。{@link DshCallback#onError}
 *       的 {@code code} 参数为 int——约定：code 可解析为整数则用之，否则用 {@link #ERR_SERVER}，
 *       并把原始字符串 code 放入 details.rawCode 供 UI 展示。</li>
 *   <li>value 恒为 JSONObject（所有方法实测均为对象形返回值）；防御性包裹非对象值。</li>
 * </ul>
 *
 * <p>零第三方依赖：仅使用 JDK 与 android.jar 内置 org.json。
 */
public class DshClient {

    /** RPC 回调（契约 1）：onResult 传 result.value；onError 透传错误。 */
    public interface DshCallback {
        void onResult(JSONObject value);

        void onError(int code, String message, JSONObject details);
    }

    // ---- 错误码约定（负数 = 客户端/传输层，非服务端 error.code） ----
    /** 网络/IO/超时等传输层错误（message 为异常描述，details 可能含 httpStatus）。 */
    public static final int ERR_NETWORK = -1;
    /** 非 JSON 响应（404 纯文本等），details.httpStatus 为状态码。 */
    public static final int ERR_HTTP = -2;
    /** 响应无法解析为合法信封。 */
    public static final int ERR_BAD_RESPONSE = -3;
    /** 服务端 result.ok=false 且 error.code 不是整数（details.rawCode 存原始字符串 code）。 */
    public static final int ERR_SERVER = -4;

    /**
     * 回调统一投递到主线程。
     *
     * <p><b>为什么必须这么做</b>：RPC 在 {@code dsh-rpc} 线程池执行，而绝大多数回调体都是
     * UI 代码——更新 Compose 状态、弹 Toast、收起弹窗。其中 {@code Toast} 尤其致命：
     * 在非主线程调用会抛
     * {@code RuntimeException: Can't toast on a thread that has not called Looper.prepare()}，
     * 直接把进程打死（真机实测：点「添加工作区 → 打开」即崩溃重启）。
     * 全项目曾有 9 处 Toast 位于 RPC 回调内，集中在一点修复比逐个改调用点可靠得多。
     *
     * <p>安全性：所有调用方要么是 Compose UI，要么是
     * {@code suspendCancellableCoroutine}（非阻塞等待），因此主线程投递不会造成死锁。
     */
    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /** 明细日志开关（默认开；排障期需要，稳定后可关）。 */
    private static final boolean VERBOSE = true;

    private static String truncate(String s) {
        if (s == null) return "null";
        return s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }

    private static void deliver(Runnable r) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            r.run();
        } else {
            MAIN.post(r);
        }
    }

    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:3081";
    private static final int DEFAULT_TIMEOUT_MS = 10_000;
    private static final int MAX_BODY_BYTES = 64 * 1024 * 1024; // 响应体读取上限，防 OOM

    private volatile String baseUrl;
    private final int timeoutMs;
    private final ExecutorService executor;

    public DshClient() {
        this(DEFAULT_BASE_URL, DEFAULT_TIMEOUT_MS);
    }

    public DshClient(String baseUrl, int timeoutMs) {
        this.baseUrl = normalizeBase(baseUrl);
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        this.executor = Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "dsh-rpc");
                t.setDaemon(true);
                return t;
            }
        });
    }

    // ==================== 配置 ====================

    public String getBaseUrl() {
        return baseUrl;
    }

    /** 修改基址（SettingsVm 修改基址后可调用；后续 RPC 立即生效）。 */
    public void setBaseUrl(String baseUrl) {
        String b = normalizeBase(baseUrl);
        if (b != null) this.baseUrl = b;
    }

    /** 释放线程池（应用退出/重建客户端时调用）。 */
    public void shutdown() {
        executor.shutdownNow();
    }

    // ==================== 核心 RPC ====================

    /**
     * 通用 RPC（契约 1 核心）：后台线程执行，自动包 client-request 信封与 rpcId，10s 超时。
     *
     * @param method  点分全名，如 "session.create"
     * @param payload 请求参数；null 视为 {}
     * @param cb      回调（可为 null，将静默丢弃结果）
     */
    public void rpc(final String method, final JSONObject payload, final DshCallback cb) {
        final String m = method == null ? "" : method.trim();
        final JSONObject p = payload == null ? new JSONObject() : payload;
        final DshCallback sink = cb != null ? cb : NOOP_CALLBACK;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                JSONObject value = null;
                String errMsg = null;
                int errCode = 0;
                JSONObject errDetails = null;
                long t0 = System.currentTimeMillis();

                try {
                    JSONObject result = httpRpc(m, p);
                    long ms = System.currentTimeMillis() - t0;
                    if (result == null) {
                        errCode = ERR_BAD_RESPONSE;
                        errMsg = "empty server response";
                        errDetails = new JSONObject();

                    } else if (result.optBoolean("ok", false)) {
                        value = coerceValue(result.opt("value"));

                    } else {
                        JSONObject err = result.optJSONObject("error");
                        if (err == null) {
                            errCode = ERR_BAD_RESPONSE;
                            errMsg = "server result.ok=false without error detail";
                            errDetails = new JSONObject();

                        } else {
                            errDetails = err.optJSONObject("details");
                            if (errDetails == null) errDetails = new JSONObject();
                            String rawCode = err.optString("code", "");
                            errCode = parseErrorCode(rawCode);
                            errMsg = err.optString("message", rawCode);
                            if (errCode == ERR_SERVER && rawCode.length() > 0) {
                                // 字符串错误码无法用 int 表达，塞入 details.rawCode 供 UI 展示
                                try {
                                    errDetails.put("rawCode", rawCode);
                                } catch (JSONException ignored) {
                                    // details 结构非法时忽略
                                }
                            }

                        }
                    }
                } catch (JsonHttpException e) {
                    errCode = ERR_HTTP;
                    errMsg = e.getMessage() != null ? e.getMessage() : "http error";
                    errDetails = new JSONObject();
                    try {
                        errDetails.put("httpStatus", e.status);
                        if (e.body != null) {
                            errDetails.put("body", e.body.length() > 256 ? e.body.substring(0, 256) : e.body);
                        }
                    } catch (JSONException ignored) {
                    }

                } catch (IOException e) {
                    errCode = ERR_NETWORK;
                    errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    errDetails = new JSONObject();

                } catch (JSONException e) {
                    errCode = ERR_BAD_RESPONSE;
                    errMsg = "malformed server response: " + (e.getMessage() != null ? e.getMessage() : "json");
                    errDetails = new JSONObject();

                } catch (Exception e) {
                    errCode = ERR_NETWORK;
                    errMsg = e.getClass().getSimpleName() + ": " + (e.getMessage() != null ? e.getMessage() : "");
                    errDetails = new JSONObject();

                }
                if (value != null) {
                    final JSONObject okValue = value;
                    deliver(new Runnable() {
                        @Override
                        public void run() {
                            sink.onResult(okValue);
                        }
                    });
                } else {
                    final int ec = errCode;
                    final String em = errMsg != null ? errMsg : "unknown error";
                    final JSONObject ed = errDetails;
                    deliver(new Runnable() {
                        @Override
                        public void run() {
                            sink.onError(ec, em, ed);
                        }
                    });
                }
            }
        });
    }

    // ==================== 方法面：session.*（12） ====================

    /** session.create：新建会话。 */
    public void sessionCreate(DshCallback cb) {
        rpc("session.create", new JSONObject(), cb);
    }

    /** session.create：新建会话（可附加额外参数，如 agentPreset）。 */
    public void sessionCreate(JSONObject extra, DshCallback cb) {
        rpc("session.create", extra == null ? new JSONObject() : extra, cb);
    }

    /** session.prompt：向会话发送纯文本（mode=queue）。 */
    public void sessionPrompt(String sessionId, String text, DshCallback cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("sessionId", sessionId);
            payload.put("mode", "queue");
            JSONArray content = new JSONArray();
            content.put(new JSONObject().put("type", "text").put("text", text == null ? "" : text));
            payload.put("content", content);
        } catch (JSONException e) {
            throw new IllegalArgumentException("sessionPrompt: build payload failed", e);
        }
        rpc("session.prompt", payload, cb);
    }

    /** session.prompt：完整控制（mode 可传 "queue"/"steer"，content 为消息内容块数组）。 */
    public void sessionPrompt(String sessionId, String mode, JSONArray content, DshCallback cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("sessionId", sessionId);
            payload.put("mode", mode == null || mode.length() == 0 ? "queue" : mode);
            payload.put("content", content == null ? new JSONArray() : content);
        } catch (JSONException e) {
            throw new IllegalArgumentException("sessionPrompt: build payload failed", e);
        }
        rpc("session.prompt", payload, cb);
    }

    /** session.history：会话事件账本（UI 渲染消息流用）。 */
    public void sessionHistory(String sessionId, DshCallback cb) {
        rpc("session.history", one("sessionId", sessionId), cb);
    }

    /** session.list：会话列表。 */
    public void sessionList(DshCallback cb) {
        rpc("session.list", new JSONObject(), cb);
    }

    /** session.list：会话列表（可附加额外参数）。 */
    public void sessionList(JSONObject extra, DshCallback cb) {
        rpc("session.list", extra == null ? new JSONObject() : extra, cb);
    }

    /** session.rename：重命名会话。 */
    public void sessionRename(String sessionId, String title, DshCallback cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("sessionId", sessionId);
            payload.put("title", title == null ? "" : title);
        } catch (JSONException e) {
            throw new IllegalArgumentException("sessionRename: build payload failed", e);
        }
        rpc("session.rename", payload, cb);
    }

    /** session.fork：派生新会话。 */
    public void sessionFork(String sessionId, DshCallback cb) {
        rpc("session.fork", one("sessionId", sessionId), cb);
    }

    /** session.cancel：停止当前流式生成。 */
    public void sessionCancel(String sessionId, DshCallback cb) {
        rpc("session.cancel", one("sessionId", sessionId), cb);
    }

    /** session.attachment：添加附件引用。 */
    public void sessionAttachment(String sessionId, String attachmentId, DshCallback cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("sessionId", sessionId);
            payload.put("attachmentId", attachmentId);
        } catch (JSONException e) {
            throw new IllegalArgumentException("sessionAttachment: build payload failed", e);
        }
        rpc("session.attachment", payload, cb);
    }

    /** session.search：会话搜索（本部署禁用，见 protocol-notes §2；保留方法）。 */
    public void sessionSearch(String query, DshCallback cb) {
        rpc("session.search", one("query", query), cb);
    }

    /** session.selectModel：为会话选择模型。 */
    public void sessionSelectModel(String sessionId, String provider, String model, DshCallback cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("sessionId", sessionId);
            payload.put("provider", provider);
            payload.put("model", model);
        } catch (JSONException e) {
            throw new IllegalArgumentException("sessionSelectModel: build payload failed", e);
        }
        rpc("session.selectModel", payload, cb);
    }

    /** session.selectModel：完整选择(provider/model/推理档位 reasoningEffort)。 */
    public void sessionSelectModel(String sessionId, String provider, String model, String reasoningEffort, DshCallback cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("sessionId", sessionId);
            payload.put("provider", provider);
            payload.put("model", model);
            if (reasoningEffort != null && reasoningEffort.length() > 0) {
                payload.put("reasoningEffort", reasoningEffort);
            }
        } catch (JSONException e) {
            throw new IllegalArgumentException("sessionSelectModel: build payload failed", e);
        }
        rpc("session.selectModel", payload, cb);
    }

    /** session.updateQueue：编辑/移除/抢占队列项（action.kind ∈ edit|remove|steer）。 */
    public void sessionUpdateQueue(String sessionId, String itemId, JSONObject action, DshCallback cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("sessionId", sessionId);
            payload.put("itemId", itemId);
            payload.put("action", action == null ? new JSONObject() : action);
        } catch (JSONException e) {
            throw new IllegalArgumentException("sessionUpdateQueue: build payload failed", e);
        }
        rpc("session.updateQueue", payload, cb);
    }

    /** session.models：会话可用模型列表。 */
    public void sessionModels(String sessionId, DshCallback cb) {
        rpc("session.models", one("sessionId", sessionId), cb);
    }

    // ==================== 方法面：workspace.*（7） ====================

    /** workspace.create：按路径创建工作区。 */
    public void workspaceCreate(String path, DshCallback cb) {
        rpc("workspace.create", one("path", path), cb);
    }

    /** workspace.list：工作区列表。 */
    public void workspaceList(DshCallback cb) {
        rpc("workspace.list", new JSONObject(), cb);
    }

    /** workspace.rename：重命名工作区。 */
    public void workspaceRename(String workspaceId, String title, DshCallback cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("workspaceId", workspaceId);
            payload.put("title", title == null ? "" : title);
        } catch (JSONException e) {
            throw new IllegalArgumentException("workspaceRename: build payload failed", e);
        }
        rpc("workspace.rename", payload, cb);
    }

    /** workspace.delete：删除工作区。 */
    public void workspaceDelete(String workspaceId, DshCallback cb) {
        rpc("workspace.delete", one("workspaceId", workspaceId), cb);
    }

    /** workspace.archiveSession：归档会话。 */
    public void workspaceArchiveSession(String sessionId, DshCallback cb) {
        rpc("workspace.archiveSession", one("sessionId", sessionId), cb);
    }

    /** workspace.insertBefore：把会话插入到工作区中某引用位置之前。 */
    public void workspaceInsertBefore(String workspaceId, String refId, String sessionId, DshCallback cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("workspaceId", workspaceId);
            if (refId != null && refId.length() > 0) payload.put("refId", refId);
            if (sessionId != null && sessionId.length() > 0) payload.put("sessionId", sessionId);
        } catch (JSONException e) {
            throw new IllegalArgumentException("workspaceInsertBefore: build payload failed", e);
        }
        rpc("workspace.insertBefore", payload, cb);
    }

    /** workspace.insertSessionBefore：把会话插入到工作区指定位置。 */
    public void workspaceInsertSessionBefore(String workspaceId, String sessionId, DshCallback cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("workspaceId", workspaceId);
            payload.put("sessionId", sessionId);
        } catch (JSONException e) {
            throw new IllegalArgumentException("workspaceInsertSessionBefore: build payload failed", e);
        }
        rpc("workspace.insertSessionBefore", payload, cb);
    }

    // ==================== 方法面：subagent.*（4） ====================

    /** subagent.list：子代理目录（按父会话）。 */
    public void subagentList(String parentSessionId, DshCallback cb) {
        rpc("subagent.list", one("parentSessionId", parentSessionId), cb);
    }

    /** subagent.prompt：续跑子代理（mode=continuable）。 */
    public void subagentPrompt(String parentSessionId, String childSessionId, String text, DshCallback cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("parentSessionId", parentSessionId);
            payload.put("childSessionId", childSessionId);
            payload.put("mode", "continuable");
            JSONArray content = new JSONArray();
            content.put(new JSONObject().put("type", "text").put("text", text == null ? "" : text));
            payload.put("content", content);
        } catch (JSONException e) {
            throw new IllegalArgumentException("subagentPrompt: build payload failed", e);
        }
        rpc("subagent.prompt", payload, cb);
    }

    /** subagent.history：子代理会话历史（mode ∈ one-shot|continuable）。 */
    public void subagentHistory(String parentSessionId, String childSessionId, String mode, DshCallback cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("parentSessionId", parentSessionId);
            payload.put("childSessionId", childSessionId);
            payload.put("mode", mode == null || mode.length() == 0 ? "continuable" : mode);
        } catch (JSONException e) {
            throw new IllegalArgumentException("subagentHistory: build payload failed", e);
        }
        rpc("subagent.history", payload, cb);
    }

    /** subagent.interrupt：中断子代理（mode=continuable）。 */
    public void subagentInterrupt(String parentSessionId, String childSessionId, DshCallback cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("parentSessionId", parentSessionId);
            payload.put("childSessionId", childSessionId);
            payload.put("mode", "continuable");
        } catch (JSONException e) {
            throw new IllegalArgumentException("subagentInterrupt: build payload failed", e);
        }
        rpc("subagent.interrupt", payload, cb);
    }

    // ==================== 方法面：goal.*（6） ====================

    /** goal.create：新建目标。 */
    public void goalCreate(String sessionId, String objective, DshCallback cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("sessionId", sessionId);
            payload.put("objective", objective == null ? "" : objective);
        } catch (JSONException e) {
            throw new IllegalArgumentException("goalCreate: build payload failed", e);
        }
        rpc("goal.create", payload, cb);
    }

    /** goal.edit：编辑目标（ref 形如 {"goalId":"...","revision":N}）。 */
    public void goalEdit(String sessionId, JSONObject ref, DshCallback cb) {
        rpc("goal.edit", withRef(sessionId, ref), cb);
    }

    /** goal.pause：暂停目标。 */
    public void goalPause(String sessionId, JSONObject ref, DshCallback cb) {
        rpc("goal.pause", withRef(sessionId, ref), cb);
    }

    /** goal.resume：恢复目标。 */
    public void goalResume(String sessionId, JSONObject ref, DshCallback cb) {
        rpc("goal.resume", withRef(sessionId, ref), cb);
    }

    /** goal.complete：完成目标。 */
    public void goalComplete(String sessionId, JSONObject ref, DshCallback cb) {
        rpc("goal.complete", withRef(sessionId, ref), cb);
    }

    /** goal.clear：清空目标。 */
    public void goalClear(String sessionId, JSONObject ref, DshCallback cb) {
        rpc("goal.clear", withRef(sessionId, ref), cb);
    }

    // ==================== 方法面：settings.*（5） ====================

    /** settings.describe：全部设置命名空间。 */
    public void settingsDescribe(DshCallback cb) {
        rpc("settings.describe", new JSONObject(), cb);
    }

    /** settings.update：按命名空间打补丁（patch 为记录对象）。 */
    public void settingsUpdate(String ns, JSONObject patch, DshCallback cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("ns", ns);
            payload.put("patch", patch == null ? new JSONObject() : patch);
        } catch (JSONException e) {
            throw new IllegalArgumentException("settingsUpdate: build payload failed", e);
        }
        rpc("settings.update", payload, cb);
    }

    /** settings.mutate：按命名空间应用 JSON Patch 操作数组。 */
    public void settingsMutate(String ns, JSONArray ops, DshCallback cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("ns", ns);
            payload.put("ops", ops == null ? new JSONArray() : ops);
        } catch (JSONException e) {
            throw new IllegalArgumentException("settingsMutate: build payload failed", e);
        }
        rpc("settings.mutate", payload, cb);
    }

    /** settings.replace：整段替换命名空间配置。 */
    public void settingsReplace(String ns, JSONObject section, DshCallback cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("ns", ns);
            payload.put("section", section == null ? new JSONObject() : section);
        } catch (JSONException e) {
            throw new IllegalArgumentException("settingsReplace: build payload failed", e);
        }
        rpc("settings.replace", payload, cb);
    }

    /** settings.openDocument：打开设置文档（本机原生 opener 不可用，见 protocol-notes）。 */
    public void settingsOpenDocument(DshCallback cb) {
        rpc("settings.openDocument", new JSONObject(), cb);
    }

    // ==================== 方法面：llm.*（3） ====================

    /** llm.models：模型分组列表（模型选择弹窗用）。 */
    public void llmModels(DshCallback cb) {
        rpc("llm.models", new JSONObject(), cb);
    }

    /** llm.providers：提供商列表。 */
    public void llmProviders(DshCallback cb) {
        rpc("llm.providers", new JSONObject(), cb);
    }

    /** llm.discoverModels：发现某设置命名空间的模型。 */
    public void llmDiscoverModels(String settingsNs, DshCallback cb) {
        rpc("llm.discoverModels", one("settingsNs", settingsNs), cb);
    }

    // ==================== 方法面：agentPreset.*（6） ====================

    /** agentPreset.list：预设列表。 */
    public void agentPresetList(DshCallback cb) {
        rpc("agentPreset.list", new JSONObject(), cb);
    }

    /** agentPreset.read：读取预设定义。 */
    public void agentPresetRead(String agentPreset, DshCallback cb) {
        rpc("agentPreset.read", one("agentPreset", agentPreset), cb);
    }

    /** agentPreset.select：为会话选择预设。 */
    public void agentPresetSelect(String sessionId, String agentPreset, DshCallback cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("sessionId", sessionId);
            payload.put("agentPreset", agentPreset);
        } catch (JSONException e) {
            throw new IllegalArgumentException("agentPresetSelect: build payload failed", e);
        }
        rpc("agentPreset.select", payload, cb);
    }

    /** agentPreset.copy：从已有预设复制创建新预设。 */
    public void agentPresetCopy(String from, String agentPreset, DshCallback cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("from", from);
            payload.put("agentPreset", agentPreset);
        } catch (JSONException e) {
            throw new IllegalArgumentException("agentPresetCopy: build payload failed", e);
        }
        rpc("agentPreset.copy", payload, cb);
    }

    /** agentPreset.remove：删除预设。 */
    public void agentPresetRemove(String agentPreset, DshCallback cb) {
        rpc("agentPreset.remove", one("agentPreset", agentPreset), cb);
    }

    /** agentPreset.openDocument：打开预设文档。 */
    public void agentPresetOpenDocument(String agentPreset, DshCallback cb) {
        rpc("agentPreset.openDocument", one("agentPreset", agentPreset), cb);
    }

    // ==================== 方法面：credentials.*（3） ====================

    /** credentials.describe：按引用数组查询凭据状态。 */
    public void credentialsDescribe(String[] refs, DshCallback cb) {
        JSONArray arr = new JSONArray();
        if (refs != null) {
            for (String r : refs) arr.put(r);
        }
        credentialsDescribe(arr, cb);
    }

    /** credentials.describe：按引用数组查询凭据状态（原始数组版）。 */
    public void credentialsDescribe(JSONArray refs, DshCallback cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("refs", refs == null ? new JSONArray() : refs);
        } catch (JSONException e) {
            throw new IllegalArgumentException("credentialsDescribe: build payload failed", e);
        }
        rpc("credentials.describe", payload, cb);
    }

    /** credentials.set：设置凭据。 */
    public void credentialsSet(String ref, String value, DshCallback cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("ref", ref);
            payload.put("value", value == null ? "" : value);
        } catch (JSONException e) {
            throw new IllegalArgumentException("credentialsSet: build payload failed", e);
        }
        rpc("credentials.set", payload, cb);
    }

    /** credentials.unset：清除凭据。 */
    public void credentialsUnset(String ref, DshCallback cb) {
        rpc("credentials.unset", one("ref", ref), cb);
    }

    // ==================== 方法面：host.*（5） ====================

    /** host.describe：主机信息（M1 连接状态页核心）。 */
    public void hostDescribe(DshCallback cb) {
        rpc("host.describe", new JSONObject(), cb);
    }

    /** host.listDirectory：目录浏览（默认 home 目录）。 */
    public void hostListDirectory(DshCallback cb) {
        rpc("host.listDirectory", new JSONObject(), cb);
    }

    /** host.listDirectory：目录浏览（指定路径）。 */
    public void hostListDirectory(String path, DshCallback cb) {
        rpc("host.listDirectory", one("path", path), cb);
    }

    /** host.createDirectory：创建子目录。 */
    public void hostCreateDirectory(String path, String name, DshCallback cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("path", path);
            payload.put("name", name == null ? "" : name);
        } catch (JSONException e) {
            throw new IllegalArgumentException("hostCreateDirectory: build payload failed", e);
        }
        rpc("host.createDirectory", payload, cb);
    }

    /** host.openPath：打开路径（本机 openPath 能力取决于后端）。 */
    public void hostOpenPath(String path, DshCallback cb) {
        rpc("host.openPath", one("path", path), cb);
    }

    /** host.pickDirectory：原生目录选择（本机不可用，见 protocol-notes）。 */
    public void hostPickDirectory(DshCallback cb) {
        rpc("host.pickDirectory", new JSONObject(), cb);
    }

    // ==================== 方法面：skill / powershell ====================

    /** skill.list：会话可用技能列表（需 sessionId）。 */
    public void skillList(String sessionId, DshCallback cb) {
        rpc("skill.list", one("sessionId", sessionId), cb);
    }

    /** powershell.exe：Windows 专用，本后端不存在（保留方法面，见 protocol-notes）。 */
    public void powershellExe(JSONObject payload, DshCallback cb) {
        rpc("powershell.exe", payload == null ? new JSONObject() : payload, cb);
    }

    // ==================== 方法面：commands.*（typert 网关斜杠格式，M4 实测） ====================
    //
    // 实测（docs/protocol-notes 追加）：本部署的「命令目录」走 typert 网关斜杠端点
    // POST /api/commands/list、/api/commands/execute（不是 session.prompt 的命令槽）。
    // 端点 URL 为 /api/<ns>/<method>，payload 为 {args:{...}}，方法字段用斜杠全名。

    /** commands.list：会话可用斜杠命令目录（agentId 实为 sessionId，实测）。 */
    public void commandsList(String sessionId, DshCallback cb) {
        JSONObject args = new JSONObject();
        try {
            args.put("agentId", sessionId == null ? "" : sessionId);
        } catch (JSONException e) {
            throw new IllegalArgumentException("commandsList: build args failed", e);
        }
        rpcTypert("commands", "list", args, cb);
    }

    /** commands.execute：执行斜杠命令（line 含前导斜杠，如 "/plan"；images 为图片块数组，可空）。 */
    public void commandsExecute(String sessionId, String line, JSONArray images, DshCallback cb) {
        JSONObject args = new JSONObject();
        try {
            args.put("agentId", sessionId == null ? "" : sessionId);
            args.put("line", line == null ? "" : line);
            args.put("images", images == null ? new JSONArray() : images);
        } catch (JSONException e) {
            throw new IllegalArgumentException("commandsExecute: build args failed", e);
        }
        rpcTypert("commands", "execute", args, cb);
    }

    // ==================== 方法面：messageFeedback.*（typert 网关，M4 实测） ====================
    //
    // 实测：POST /api/messageFeedback/list|put|delete，参数名为 request（对象），
    // result.value 为域结果 {ok:true, value:{items:[...]}}（外层信封再包一层）。

    /** messageFeedback.list：会话内全部消息反馈（messageId→{rating,version,...}）。 */
    public void messageFeedbackList(String sessionId, DshCallback cb) {
        JSONObject request = new JSONObject();
        try {
            request.put("sessionId", sessionId == null ? "" : sessionId);
        } catch (JSONException e) {
            throw new IllegalArgumentException("messageFeedbackList: build args failed", e);
        }
        rpcTypert("messageFeedback", "list", one("request", request), cb);
    }

    /** messageFeedback.put：写入/替换一条反馈。rating ∈ positive|negative；note 可空；ifVersion 可空。 */
    public void messageFeedbackPut(String sessionId, String messageId, String rating,
                                   String note, String ifVersion, DshCallback cb) {
        JSONObject request = new JSONObject();
        try {
            request.put("sessionId", sessionId == null ? "" : sessionId);
            request.put("messageId", messageId == null ? "" : messageId);
            request.put("rating", rating == null ? "positive" : rating);
            if (note != null && note.length() > 0) request.put("note", note);
            request.put("ifVersion", ifVersion == null || ifVersion.length() == 0 ? JSONObject.NULL : ifVersion);
        } catch (JSONException e) {
            throw new IllegalArgumentException("messageFeedbackPut: build args failed", e);
        }
        rpcTypert("messageFeedback", "put", one("request", request), cb);
    }

    /** messageFeedback.delete：删除一条反馈。ifVersion 为必填 string（实测 schema 不允许 null，
     *  空值时生成随机 UUID 占位——items 存在则业务层按版本比对，不存在则返回 absent）。 */
    public void messageFeedbackDelete(String sessionId, String messageId, String ifVersion, DshCallback cb) {
        JSONObject request = new JSONObject();
        try {
            request.put("sessionId", sessionId == null ? "" : sessionId);
            request.put("messageId", messageId == null ? "" : messageId);
            request.put("ifVersion", ifVersion == null || ifVersion.length() == 0
                    ? UUID.randomUUID().toString() : ifVersion);
        } catch (JSONException e) {
            throw new IllegalArgumentException("messageFeedbackDelete: build args failed", e);
        }
        rpcTypert("messageFeedback", "delete", one("request", request), cb);
    }

    // ==================== 方法面：/api/respond（M4 实测：审批与提问的应答端点） ====================
    //
    // 实测：POST /api/respond，体为 client-response 信封（非 client-request），
    // result.value 为应答载荷（审批 {sessionId,approvalId,outcome} / 提问
    // {sessionId,answer:{answers:[{id,selected:[],custom?}]}}）；
    // 响应为受理回执 {"accepted":true} 或 {"accepted":false,"reason":...}（非标准信封）。

    /** respond：向 /api/respond 提交应答载荷（审批/提问共用），回执 accepted 经 onResult(value) 返回。 */
    public void respond(final JSONObject value, final DshCallback cb) {
        respond(value, null, cb);
    }

    /**
     * respond：提交审批 / 提问的应答。
     *
     * <p><b>协议变更</b>：dsh ≤ 0.1.1 走 {@code POST /api/respond} 的 {@code client-response}
     * 信封；dsh ≥ 0.1.5 该端点已 404，回执改由事件通道完成——
     * {@code POST /api/$events/result}，体为
     * {@code {args:{clientId, eventId, outcome:{kind:"result", value:…}}}}。
     * 其中 {@code clientId} 来自 {@code $events} 流的 ready 帧（见
     * {@link WaterfallRegistry}），{@code eventId} 就是待应答 waterfall 帧的 eventId
     * ——也就是旧 UI 里那个「mux 帧的 rpcId」。
     *
     * @param rpcId 待应答 waterfall 帧的 eventId（旧称 rpcId）；为 null 时从 value 里取
     */
    public void respond(final JSONObject value, final String rpcId, final DshCallback cb) {
        final DshCallback sink = cb != null ? cb : NOOP_CALLBACK;
        final JSONObject v = value == null ? new JSONObject() : value;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String eventId = rpcId;
                    if (eventId == null || eventId.isEmpty()) {
                        eventId = v.optString("rpcId", "");
                    }
                    // 兜底：按会话找最近一条待应答的提问事件
                    if ((eventId == null || eventId.isEmpty()) && v.has("answers")) {
                        eventId = WaterfallRegistry.findEventId(
                                "user-questions/request", v.optString("sessionId", ""));
                    }
                    if (eventId == null || eventId.isEmpty()) {
                        sink.onError(ERR_SERVER, "找不到对应的待应答事件（eventId 缺失）", new JSONObject());
                        return;
                    }
                    String clientId = WaterfallRegistry.clientId();
                    if (clientId == null || clientId.isEmpty()) {
                        sink.onError(ERR_SERVER, "事件通道尚未就绪（缺 clientId），请稍后重试", new JSONObject());
                        return;
                    }

                    JSONObject outcome = new JSONObject();
                    outcome.put("kind", "result");
                    outcome.put("value", mapRespondValue(v));

                    JSONObject args = new JSONObject();
                    args.put("clientId", clientId);
                    args.put("eventId", eventId);
                    args.put("outcome", outcome);

                    JSONObject wrapped = new JSONObject();
                    wrapped.put("args", args);
                    JSONObject envelope = postApi("$events/result", wrapped, true);
                    if (envelope == null) {
                        if (com.aptuidsh.kui.env.DshAuth.reauth()) {
                            envelope = postApi("$events/result", wrapped, true);
                        }
                    }
                    if (envelope == null) {
                        sink.onError(ERR_NETWORK, "应答提交失败（鉴权或网络）", new JSONObject());
                        return;
                    }
                    if (envelope.optBoolean("ok", false)) {
                        WaterfallRegistry.resolve(eventId);
                        final JSONObject okEnv = envelope;
                        deliver(new Runnable() {
                            @Override
                            public void run() {
                                sink.onResult(okEnv);
                            }
                        });
                    } else {
                        JSONObject err = envelope.optJSONObject("error");
                        JSONObject details = new JSONObject();
                        String reason = err == null ? "unknown" : err.optString("message", "unknown");
                        try {
                            details.put("reason", reason);
                        } catch (JSONException ignored) {
                        }
                        sink.onError(ERR_SERVER, "应答被拒绝: " + reason, details);
                    }
                } catch (IOException e) {
                    sink.onError(ERR_NETWORK, e.getMessage() != null ? e.getMessage() : "respond io error",
                            new JSONObject());
                } catch (JSONException e) {
                    sink.onError(ERR_BAD_RESPONSE, "malformed respond receipt", new JSONObject());
                } catch (Exception e) {
                    sink.onError(ERR_NETWORK, e.getClass().getSimpleName() + ": " + e.getMessage(),
                            new JSONObject());
                }
            }
        });
    }

    /**
     * 把旧式应答载荷映射为新网关 outcome 的 value。
     *
     * <ul>
     *   <li>提问：旧 {@code {sessionId, answers:[{id,selected,custom?}]}} →
     *       新 value 直接就是 {@code {answers:[…]}}（<b>同一形状</b>，见
     *       {@code AskUserQuestionAnswer}）。</li>
     *   <li>审批：旧 {@code {sessionId, approvalId, outcome:"approved"|"rejected"}} →
     *       新 value 是闭集字符串 {@code allowed-once|rejected|cancelled}
     *       （见 {@code ApprovalOutcome}），这里做取值归一。</li>
     * </ul>
     */
    private static Object mapRespondValue(JSONObject value) throws JSONException {
        if (value.has("answers")) {
            JSONObject out = new JSONObject();
            out.put("answers", value.optJSONArray("answers") == null
                    ? new org.json.JSONArray() : value.optJSONArray("answers"));
            return out;
        }
        if (value.has("outcome")) {
            String raw = value.optString("outcome", "").toLowerCase(java.util.Locale.ROOT);
            String normalized;
            if (raw.startsWith("allow") || "approved".equals(raw) || "approve".equals(raw)
                    || "yes".equals(raw) || "once".equals(raw)) {
                normalized = "allowed-once";
            } else if (raw.startsWith("cancel")) {
                normalized = "cancelled";
            } else if (raw.isEmpty() || "unavailable".equals(raw)) {
                normalized = "unavailable";
            } else {
                normalized = "rejected";
            }
            return normalized;
        }
        return value;
    }

    /** 审批应答：允许一次 / 拒绝（outcome ∈ allowed-once|rejected）。
     *  @param muxRpcId mux 帧的 rpcId（后端 pendingApprovals 的 key，必须与 approval/asked 帧的 rpcId 一致）。 */
    public void respondApproval(String sessionId, String approvalId, String muxRpcId, String outcome, DshCallback cb) {
        JSONObject value = new JSONObject();
        try {
            value.put("sessionId", sessionId == null ? "" : sessionId);
            value.put("approvalId", approvalId == null ? "" : approvalId);
            value.put("outcome", outcome == null ? "rejected" : outcome);
        } catch (JSONException e) {
            throw new IllegalArgumentException("respondApproval: build payload failed", e);
        }
        respond(value, muxRpcId, cb);
    }

    /** 提问应答：answers 为 [{id,selected:[],custom?}] 数组（question/requested 帧的 questions[].id）。
     *  @param muxRpcId 必须复用 question/requested mux 帧 envelope 的 rpcId（后端按此 id 匹配等待），
     *                  否则返回 not-pending。 */
    public void respondQuestion(String sessionId, String muxRpcId, JSONArray answers, DshCallback cb) {
        JSONObject answer = new JSONObject();
        try {
            answer.put("answers", answers == null ? new JSONArray() : answers);
        } catch (JSONException e) {
            throw new IllegalArgumentException("respondQuestion: build payload failed", e);
        }
        JSONObject value = new JSONObject();
        try {
            value.put("sessionId", sessionId == null ? "" : sessionId);
            value.put("answer", answer);
        } catch (JSONException e) {
            throw new IllegalArgumentException("respondQuestion: build payload failed", e);
        }
        respond(value, muxRpcId, cb);
    }

    /** 取消提问/审批等待：以 ok:false + cancelled 错误应答（官方用户主动关闭提问卡片的语义）。 */
    public void respondCancel(String muxRpcId, DshCallback cb) {
        final JSONObject body = new JSONObject();
        try {
            JSONObject error = new JSONObject();
            error.put("code", "cancelled");
            error.put("message", "the user closed this request");
            error.put("details", new JSONObject());
            body.put("type", "client-response");
            body.put("rpcId", muxRpcId != null ? muxRpcId : UUID.randomUUID().toString());
            body.put("result", new JSONObject().put("ok", false).put("error", error));
        } catch (JSONException e) {
            throw new IllegalArgumentException("respondCancel: build envelope failed", e);
        }
        final DshCallback sink = cb != null ? cb : NOOP_CALLBACK;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject receipt = httpRespond(body);
                    if (receipt == null) {
                        sink.onError(ERR_BAD_RESPONSE, "empty respond receipt", new JSONObject());
                        return;
                    }
                    if (receipt.optBoolean("accepted", false)) {
                        sink.onResult(receipt);
                    } else {
                        JSONObject details = new JSONObject();
                        try {
                            details.put("reason", receipt.optString("reason", "not-pending"));
                        } catch (JSONException ignored) {
                        }
                        sink.onError(ERR_SERVER, "取消被拒绝: " + receipt.optString("reason", "not-pending"), details);
                    }
                } catch (IOException e) {
                    JSONObject details = new JSONObject();
                    sink.onError(ERR_NETWORK, e.getMessage() != null ? e.getMessage() : "respond io error", details);
                } catch (JSONException e) {
                    sink.onError(ERR_BAD_RESPONSE, "malformed respond receipt", new JSONObject());
                } catch (Exception e) {
                    sink.onError(ERR_NETWORK, e.getClass().getSimpleName() + ": " + e.getMessage(), new JSONObject());
                }
            }
        });
    }

    /** session.prompt 完整内容块版（M4 附件：content 可含 {type:"image",mediaType,data,name?} 图片块）。 */
    public void sessionPromptBlocks(String sessionId, String mode, JSONArray content, DshCallback cb) {
        sessionPrompt(sessionId, mode, content, cb);
    }

    // ==================== 内部实现 ====================

    private static final DshCallback NOOP_CALLBACK = new DshCallback() {
        @Override
        public void onResult(JSONObject value) {
        }

        @Override
        public void onError(int code, String message, JSONObject details) {
        }
    };

    /** 执行一次 HTTP RPC，返回 result 信封对象（或 null）。抛 IOException/JSONException 由上层归类。 */
    private JSONObject httpRpc(String method, JSONObject payload) throws IOException, JSONException {
        return httpRpcPath(method, payload);
    }

    /**
     * 执行一次 HTTP RPC：path 为点分方法名或斜杠 ns/method，均 POST /api/&lt;path&gt;。
     *
     * <p>本方法同时承担三件与内置 dsh 版本相关的事：
     * <ol>
     *   <li>经 {@link ApiCompat#mapMethod} 把 dsh-aui 的旧方法名映射到 dsh ≥ 0.1.5 的新命名空间；</li>
     *   <li>经 {@link ApiCompat#buildArgs} 把旧版平铺载荷重排为网关要求的 {@code {args:{…}}} 信封；</li>
     *   <li>携带 {@link com.aptuidsh.kui.env.DshAuth} 的签名 Cookie——新版 {@code /api/*} 强制鉴权，
     *       缺失会直接 401；收到 401 时自动重取 Cookie 并重试一次。</li>
     * </ol>
     *
     * <p>{@code host.describe} 在新版已被移除，由 {@link #syntheticHostDescribe()} 本地合成。
     */
    private JSONObject httpRpcPath(String path, JSONObject payload) throws IOException, JSONException {
        // session.history 在新版被 session/page 取代，而后者要求 throughSeq 不得超过会话游标，
        // 因此改为「开一条短命 follow 流取 opening snapshot」，见 syntheticSessionHistory。
        if ("session.history".equals(path)) {
            return syntheticSessionHistory(payload == null ? "" : payload.optString("sessionId", ""));
        }
        String mapped = ApiCompat.mapMethod(path);
        if (ApiCompat.isSynthetic(mapped)) {
            return syntheticHostDescribe();
        }
        JSONObject newPayload = ApiCompat.buildArgs(path, payload);
        // 明细日志：真机排障时，能直接看到"本地方法名 → 实际发出的方法 + 参数"，
        // 以及服务端原样返回的错误码/消息。
        if (VERBOSE) {
            com.aptuidsh.kui.env.EnvLog.i("RPC → " + path + " → " + mapped
                    + " args=" + truncate(newPayload.toString()));
        }

        JSONObject envelope = postApi(mapped, newPayload, true);
        if (envelope == null && com.aptuidsh.kui.env.DshAuth.reauth()) {
            // 401：Cookie 过期或 dsh 重启换了令牌 → 重新交换后重试一次
            envelope = postApi(mapped, newPayload, true);
        }
        if (envelope == null) {
            throw new JsonHttpException(401, "unauthorized",
                    "内置 dsh 鉴权失败：请在环境控制页重启后端");
        }
        if (VERBOSE) {
            if (envelope.optBoolean("ok", false)) {
                com.aptuidsh.kui.env.EnvLog.i("RPC ← " + mapped + " ok "
                        + truncate(String.valueOf(envelope.opt("value"))));
            } else {
                com.aptuidsh.kui.env.EnvLog.e("RPC ← " + mapped + " 失败 "
                        + truncate(String.valueOf(envelope.optJSONObject("error"))), null);
            }
        }
        JSONObject value = envelope.optJSONObject("value");
        if (value != null && envelope.optBoolean("ok", false)) {
            JSONObject adapted = ApiCompat.adaptResult(path, payload, value);
            if (adapted != value) {
                envelope.put("value", adapted);
            }
        }
        return envelope;
    }

    /** 一次 POST /api/&lt;mapped&gt;；返回 result.value；401 返回 null。 */
    private JSONObject postApi(String mapped, JSONObject newPayload, boolean withCookie)
            throws IOException, JSONException {
        URL url = new URL(baseUrl + "/api/" + mapped);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Connection", "close");
            if (withCookie) {
                String ck = com.aptuidsh.kui.env.DshAuth.cookieHeader();
                if (ck != null && !ck.isEmpty()) {
                    conn.setRequestProperty("Cookie", ck);
                }
            }

            byte[] body = buildRequest(mapped, newPayload).toString().getBytes(StandardCharsets.UTF_8);
            OutputStream os = conn.getOutputStream();
            try {
                os.write(body);
                os.flush();
            } finally {
                os.close();
            }

            int status = conn.getResponseCode();
            if (status == 401) {
                return null;
            }
            InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) {
                try {
                    is = conn.getInputStream();
                } catch (IOException e) {
                    is = null;
                }
            }
            String resp = is != null ? readAll(is) : "";
            try {
                if (is != null) is.close();
            } catch (IOException ignored) {
            }

            JSONObject envelope;
            try {
                envelope = new JSONObject(resp);
            } catch (JSONException e) {
                // 非 JSON 响应（如 404 "not found"）
                throw new JsonHttpException(status, resp, "non-JSON server response");
            }
            if (!envelope.has("result")) {
                throw new JsonHttpException(status, resp, "server response missing result field");
            }
            return envelope.getJSONObject("result");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 合成 {@code session.history}：用一次性的 {@code session/follow} 流取 opening snapshot。
     *
     * <p>新版 {@code session/page} 需要 {@code throughSeq} 且<b>不得超过会话当前游标</b>：
     * <pre>
     * {"code":"gateway/bad-request","message":"session page through seq 2147483647 is past cursor 2"}
     * </pre>
     * 而游标只出现在 follow 流的 opening snapshot 里。官方客户端同样是「先 follow 拿快照，
     * 再按 beforeSeq 往前翻页」。这里取快照的 records 直接还原成旧版事件账本形状
     * {@code {events:[…], hasMore}}，上层 UI 无需改动。
     */
    private JSONObject syntheticSessionHistory(String sessionId) throws IOException, JSONException {
        JSONObject envelope = new JSONObject();
        JSONObject out = new JSONObject();
        JSONArray events = new JSONArray();
        boolean hasMore = false;

        if (sessionId != null && !sessionId.isEmpty()) {
            JSONObject args = new JSONObject();
            JSONObject request = new JSONObject();
            JSONObject address = new JSONObject();
            address.put("kind", "session");
            address.put("sessionId", sessionId);
            request.put("address", address);
            request.put("maxMessages", 400);
            args.put("request", request);

            JSONObject snapshot = MuxClient.openAndFirstItem(
                    baseUrl, "session/follow", args, Math.max(timeoutMs, 15000));
            if (snapshot != null) {
                hasMore = snapshot.optBoolean("hasMore", false);
                JSONArray records = snapshot.optJSONArray("records");
                if (records != null) {
                    for (int i = 0; i < records.length(); i++) {
                        JSONObject rec = records.optJSONObject(i);
                        if (rec == null) continue;
                        // 必须原样保留记录外壳 {"type":"event","event":{…}}：
                        // 上层 SessionLog.fromHistory 是按 events[i].optJSONObject("event") 取事件的，
                        // 早先这里把外壳剥掉只放裸事件，导致历史解析全部落空、会话打开后是空的。
                        if (rec.optJSONObject("event") != null) {
                            events.put(rec);
                        }
                    }
                }
                // 会话实时配置/统计来自快照的 projections（sessionStats / contextPressure /
                // permissions / tokenUsage），上层是照 projections.values.* 读的，缺了就没有状态栏。
                JSONObject projections = snapshot.optJSONObject("projections");
                if (projections != null) {
                    out.put("projections", projections);
                }
                if (snapshot.has("cursor")) {
                    out.put("cursor", snapshot.opt("cursor"));
                }
                JSONObject header = snapshot.optJSONObject("header");
                if (header != null) {
                    out.put("header", header);
                    String sid = header.optString("id", "");
                    if (!sid.isEmpty()) out.put("sessionId", sid);
                }
            }
        }

        out.put("events", events);
        out.put("hasMore", hasMore);
        envelope.put("ok", true);
        envelope.put("value", out);
        return envelope;
    }

    /**
     * 合成 {@code host.describe} 结果（新版 API 已删除该方法）。
     *
     * <p>数据来源：{@code settings/describe}（默认模型、可写性）+ {@code llm/listProviders}
     * （服务提供方）+ 固定 guest 路径。版本号取自随包镜像的版本标记文件。
     */
    private JSONObject syntheticHostDescribe() {
        JSONObject out = new JSONObject();
        try {
            out.put("version", ApiCompat.dshVersion());
            out.put("provider", "deepseek-official");
            out.put("model", "—");
            out.put("cwd", com.aptuidsh.kui.env.ProrootEnv.GUEST_WORKSPACE);
            out.put("home", com.aptuidsh.kui.env.ProrootEnv.GUEST_HOME);
            out.put("attachedSessions", "—");
            out.put("canOpenPath", false);
        } catch (JSONException ignored) {
        }

        // 从 settings/describe 里读默认 provider/model
        try {
            JSONObject env = postApi("settings/describe",
                    ApiCompat.buildArgs("settings.describe", new JSONObject()), true);
            if (env != null && env.optBoolean("ok", false)) {
                JSONObject value = env.optJSONObject("value");
                JSONArray namespaces = value == null ? null : value.optJSONArray("namespaces");
                if (namespaces != null) {
                    for (int i = 0; i < namespaces.length(); i++) {
                        JSONObject ns = namespaces.optJSONObject(i);
                        if (ns == null || !"agent-default-model".equals(ns.optString("ns"))) continue;
                        JSONObject mv = ns.optJSONObject("value");
                        if (mv != null) {
                            if (mv.has("provider")) out.put("provider", mv.optString("provider"));
                            if (mv.has("model")) out.put("model", mv.optString("model"));
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 合成失败保留兜底值
        }

        JSONObject envelope = new JSONObject();
        try {
            envelope.put("ok", true);
            envelope.put("value", out);
        } catch (JSONException ignored) {
        }
        return envelope;
    }

    /** 执行一次 /api/respond（client-response 回执端点，M4）。返回回执对象或 null。 */
    private JSONObject httpRespond(JSONObject body) throws IOException, JSONException {
        URL url = new URL(baseUrl + "/api/respond");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Connection", "close");
            // 新版 /api/* 强制 Cookie 鉴权，回执端点同样需要
            String respondCookie = com.aptuidsh.kui.env.DshAuth.cookieHeader();
            if (respondCookie != null && !respondCookie.isEmpty()) {
                conn.setRequestProperty("Cookie", respondCookie);
            }

            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream os = conn.getOutputStream();
            try {
                os.write(bytes);
                os.flush();
            } finally {
                os.close();
            }

            int status = conn.getResponseCode();
            InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) {
                try {
                    is = conn.getInputStream();
                } catch (IOException e) {
                    is = null;
                }
            }
            String resp = is != null ? readAll(is) : "";
            try {
                if (is != null) is.close();
            } catch (IOException ignored) {
            }
            try {
                return new JSONObject(resp);
            } catch (JSONException e) {
                throw new JsonHttpException(status, resp, "non-JSON respond receipt");
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 组装 client-request 信封。 */
    private static JSONObject buildRequest(String method, JSONObject payload) {
        JSONObject req = new JSONObject();
        try {
            req.put("type", "client-request");
            req.put("rpcId", UUID.randomUUID().toString());
            req.put("method", method);
            req.put("payload", payload == null ? new JSONObject() : payload);
        } catch (JSONException e) {
            throw new IllegalStateException("build request envelope failed", e);
        }
        return req;
    }

    /** 读取响应体（限长防 OOM）。 */
    private static String readAll(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(1024);
        char[] buf = new char[8192];
        int n;
        int total = 0;
        while ((n = reader.read(buf)) != -1) {
            sb.append(buf, 0, n);
            total += n;
            if (total > MAX_BODY_BYTES) {
                throw new IOException("server response too large (>" + MAX_BODY_BYTES + " bytes)");
            }
        }
        return sb.toString();
    }

    /** value 恒为 JSONObject（契约）；非对象值防御性包裹。 */
    private static JSONObject coerceValue(Object raw) {
        if (raw instanceof JSONObject) return (JSONObject) raw;
        JSONObject wrap = new JSONObject();
        try {
            if (raw == null || raw == JSONObject.NULL) {
                return wrap;
            } else if (raw instanceof JSONArray) {
                wrap.put("items", raw);
            } else {
                wrap.put("value", raw);
            }
        } catch (JSONException ignored) {
            // 包装失败返回空对象
        }
        return wrap;
    }

    /**
     * typert 网关 RPC（M4 新增，契约外扩展）：POST /api/&lt;ns&gt;/&lt;method&gt;（斜杠路径），
     * payload 为 {@code {args:{...}}}，方法字段用斜杠全名。信封与响应解析与标准 rpc 相同。
     */
    public void rpcTypert(final String ns, final String method, final JSONObject args, final DshCallback cb) {
        final String path = (ns == null ? "" : ns.trim()) + "/" + (method == null ? "" : method.trim());
        final JSONObject p = new JSONObject();
        try {
            p.put("args", args == null ? new JSONObject() : args);
        } catch (JSONException e) {
            throw new IllegalArgumentException("rpcTypert: build payload failed", e);
        }
        final DshCallback sink = cb != null ? cb : NOOP_CALLBACK;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                JSONObject value = null;
                String errMsg = null;
                int errCode = 0;
                JSONObject errDetails = null;
                try {
                    JSONObject result = httpRpcPath(path, p);
                    if (result == null) {
                        errCode = ERR_BAD_RESPONSE;
                        errMsg = "empty server response";
                        errDetails = new JSONObject();
                    } else if (result.optBoolean("ok", false)) {
                        value = coerceValue(result.opt("value"));
                    } else {
                        JSONObject err = result.optJSONObject("error");
                        if (err == null) {
                            errCode = ERR_BAD_RESPONSE;
                            errMsg = "server result.ok=false without error detail";
                            errDetails = new JSONObject();
                        } else {
                            errDetails = err.optJSONObject("details");
                            if (errDetails == null) errDetails = new JSONObject();
                            String rawCode = err.optString("code", "");
                            errCode = parseErrorCode(rawCode);
                            errMsg = err.optString("message", rawCode);
                            if (errCode == ERR_SERVER && rawCode.length() > 0) {
                                try {
                                    errDetails.put("rawCode", rawCode);
                                } catch (JSONException ignored) {
                                }
                            }
                        }
                    }
                } catch (JsonHttpException e) {
                    errCode = ERR_HTTP;
                    errMsg = e.getMessage() != null ? e.getMessage() : "http error";
                    errDetails = new JSONObject();
                    try {
                        errDetails.put("httpStatus", e.status);
                    } catch (JSONException ignored) {
                    }
                } catch (IOException e) {
                    errCode = ERR_NETWORK;
                    errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    errDetails = new JSONObject();
                } catch (JSONException e) {
                    errCode = ERR_BAD_RESPONSE;
                    errMsg = "malformed server response: " + (e.getMessage() != null ? e.getMessage() : "json");
                    errDetails = new JSONObject();
                } catch (Exception e) {
                    errCode = ERR_NETWORK;
                    errMsg = e.getClass().getSimpleName() + ": " + (e.getMessage() != null ? e.getMessage() : "");
                    errDetails = new JSONObject();
                }
                if (value != null) {
                    final JSONObject okValue = value;
                    deliver(new Runnable() {
                        @Override
                        public void run() {
                            sink.onResult(okValue);
                        }
                    });
                } else {
                    final int ec = errCode;
                    final String em = errMsg != null ? errMsg : "unknown error";
                    final JSONObject ed = errDetails;
                    deliver(new Runnable() {
                        @Override
                        public void run() {
                            sink.onError(ec, em, ed);
                        }
                    });
                }
            }
        });
    }

    /** 构造单字段 payload。 */
    private static JSONObject one(String key, String value) {
        JSONObject payload = new JSONObject();
        try {
            payload.put(key, value == null ? "" : value);
        } catch (JSONException e) {
            throw new IllegalArgumentException("build payload failed", e);
        }
        return payload;
    }

    /**
     * commands.execute：向会话 agent 执行一条 slash 命令(官方 typert agent-scope direct)。
     * 信封:payload = {args:{agentId, line, images[]}}。
     * 命令执行产生 command/run→command/done 生命周期事件(如 /permission 切权限)。
     */
    public void commandExecute(String sessionId, String line, DshCallback cb) {
        JSONObject args = new JSONObject();
        try {
            args.put("agentId", sessionId);
            args.put("line", line == null ? "" : line);
            args.put("images", new JSONArray());
        } catch (JSONException e) {
            throw new IllegalArgumentException("commandExecute: build args failed", e);
        }
        rpc("commands/execute", one("args", args), cb);
    }

    /**
     * commands.list：会话 agent 可用命令目录(官方 typert agent-scope direct)。
     * 返回 [{name, description, input?}]。
     */
    public void commandList(String sessionId, DshCallback cb) {
        JSONObject args = new JSONObject();
        try {
            args.put("agentId", sessionId);
        } catch (JSONException e) {
            throw new IllegalArgumentException("commandList: build args failed", e);
        }
        rpc("commands/list", one("args", args), cb);
    }

    /** 构造单对象字段 payload（messageFeedback/typert 用）。 */
    private static JSONObject one(String key, JSONObject value) {
        JSONObject payload = new JSONObject();
        try {
            payload.put(key, value == null ? JSONObject.NULL : value);
        } catch (JSONException e) {
            throw new IllegalArgumentException("build payload failed", e);
        }
        return payload;
    }

    /** goal.* 共用：sessionId + ref 对象。 */
    private static JSONObject withRef(String sessionId, JSONObject ref) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("sessionId", sessionId);
            payload.put("ref", ref == null ? new JSONObject() : ref);
        } catch (JSONException e) {
            throw new IllegalArgumentException("build payload failed", e);
        }
        return payload;
    }

    private static String normalizeBase(String url) {
        if (url == null) return null;
        String b = url.trim();
        if (b.length() == 0) return null;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    /** error.code 字符串 → int；不可解析返回 ERR_SERVER。 */
    private static int parseErrorCode(String raw) {
        if (raw == null || raw.length() == 0) return ERR_SERVER;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return ERR_SERVER;
        }
    }

    /** 非 JSON / 缺 result 的响应异常（携带 HTTP 状态码与原始响应体）。 */
    private static final class JsonHttpException extends IOException {
        final int status;
        final String body;

        private JsonHttpException(int status, String body, String reason) {
            super(reason + ": " + (body != null && body.length() > 120 ? body.substring(0, 120) : body)
                    + " (HTTP " + status + ")");
            this.status = status;
            this.body = body;
        }
    }
}
