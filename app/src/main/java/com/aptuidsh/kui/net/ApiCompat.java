package com.aptuidsh.kui.net;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * dsh-aui 前端协议（面向 dsh ≤ 0.1.1）到 dsh ≥ 0.1.5 的兼容适配层。
 *
 * <h3>背景</h3>
 * APTUIDSH 内置的是 dsh 最新版（0.1.5-rc.1），而前端 UI 的三处契约在这一版发生了破坏性变更，
 * 本类集中收敛这些差异，让上层 {@link DshClient} 的调用点保持原样：
 *
 * <ol>
 *   <li><b>方法名空间化</b>：{@code host.listDirectory} → {@code directoryPicker/list}、
 *       {@code session.history} → {@code session/page}、{@code agentPreset.list} →
 *       {@code agentPresets/list}、{@code goal.*} → {@code goals/*} 等。</li>
 *   <li><b>载荷信封 args 化</b>：新网关要求 {@code payload} 里<b>有且只有一个</b> {@code args}
 *       对象，且 {@code args} 的键名必须与 typert 描述符的形参名一致
 *       （{@code request} / {@code refs} / {@code path} / {@code ns} / {@code _request} …）。
 *       旧的平铺载荷因此必须重排。</li>
 *   <li><b>会话模型重构</b>：历史由 {@code session/page} 返回
 *       {@code {records,hasMore}}；实时流改走 {@code /api/remote.mux} WebSocket 的
 *       {@code session/follow} 逻辑流（见 {@link EventStream}）。</li>
 * </ol>
 *
 * <p>另外 {@code host.describe} 在新版被移除，本类用 {@code settings/describe} +
 * {@code llm/listProviders} 合成等价的描述对象，并把 dsh 版本从镜像标记文件补齐。
 */
public final class ApiCompat {

    private ApiCompat() {
    }

    /** 内置镜像里的 dsh 版本（由 {@link #setDshVersion} 注入，取自镜像标记文件）。 */
    private static volatile String dshVersion = "0.1.5+";

    public static void setDshVersion(String v) {
        if (v != null && !v.isEmpty()) {
            dshVersion = v;
        }
    }

    public static String dshVersion() {
        return dshVersion;
    }

    // ------------------------------------------------------------------ 方法名映射

    /** 合成方法标记（不属于服务端，由 {@link DshClient} 本地组装）。 */
    public static final String SYNTHETIC_HOST_DESCRIBE = "$synthetic/host.describe";

    /** 合成方法标记：会话历史改由一次性 follow 快照提供（见 {@link DshClient}）。 */
    public static final String SYNTHETIC_SESSION_HISTORY = "$synthetic/session.history";

    private static final Map<String, String> METHOD = new HashMap<>();

    static {
        // session
        METHOD.put("session.create", "session/create");
        METHOD.put("session.prompt", "session/prompt");
        // session/page 要求 throughSeq 不得超过会话游标，无法从平铺载荷直接构造，
        // 因此标记为合成方法，由 DshClient 用一次性 follow 快照取历史。
        METHOD.put("session.history", SYNTHETIC_SESSION_HISTORY);
        METHOD.put("session.list", "session/list");
        METHOD.put("session.rename", "session/rename");
        METHOD.put("session.fork", "session/fork");
        METHOD.put("session.cancel", "session/cancel");
        METHOD.put("session.attachment", "session/attachment");
        METHOD.put("session.search", "session/search");
        METHOD.put("session.selectModel", "session/selectModel");
        METHOD.put("session.updateQueue", "session/updateQueue");
        METHOD.put("session.models", "session/modelCatalog");
        // workspace
        METHOD.put("workspace.create", "workspace/create");
        METHOD.put("workspace.rename", "workspace/rename");
        METHOD.put("workspace.delete", "workspace/delete");
        METHOD.put("workspace.insertBefore", "workspace/insertBefore");
        METHOD.put("workspace.insertSessionBefore", "workspace/insertSessionBefore");
        METHOD.put("workspace.archiveSession", "workspace/archiveSession");
        METHOD.put("workspace.list", "session/list");
        // subagent —— 注意：dsh 0.1.7-rc.1 起旧的 subagents/list 已被【移除】，
        // 再调它就是 HTTP 404「not found」。子代理清单改由 session/list 的
        // projections.values.subagentCatalog 提供（官方 Web UI 也是这么取的），
        // 因此这里改走 session/list，再由 adaptResult 还原成旧形状。
        METHOD.put("subagent.list", "session/list");
        METHOD.put("subagent.prompt", "subagents/prompt");
        METHOD.put("subagent.history", "session/list");
        METHOD.put("subagent.interrupt", "subagents/interruptByParent");
        // goal（单数 → 复数）
        METHOD.put("goal.get", "goals/get");
        METHOD.put("goal.create", "goals/create");
        METHOD.put("goal.edit", "goals/edit");
        METHOD.put("goal.pause", "goals/pause");
        METHOD.put("goal.resume", "goals/resume");
        METHOD.put("goal.complete", "goals/complete");
        METHOD.put("goal.clear", "goals/clear");
        // settings
        METHOD.put("settings.describe", "settings/describe");
        METHOD.put("settings.update", "settings/update");
        METHOD.put("settings.mutate", "settings/mutate");
        METHOD.put("settings.replace", "settings/replace");
        METHOD.put("settings.openDocument", "settings/openSettingsDocument");
        // llm
        METHOD.put("llm.models", "session/modelCatalog");
        METHOD.put("llm.providers", "llm/listProviders");
        METHOD.put("llm.discoverModels", "llm/discoverModels");
        // agentPreset（单数 → 复数）
        METHOD.put("agentPreset.list", "agentPresets/list");
        METHOD.put("agentPreset.read", "agentPresets/read");
        METHOD.put("agentPreset.select", "agentPresets/select");
        METHOD.put("agentPreset.copy", "agentPresets/copy");
        METHOD.put("agentPreset.remove", "agentPresets/deletePreset");
        METHOD.put("agentPreset.openDocument", "settings/openAgentPresetDirectory");
        // credentials
        METHOD.put("credentials.describe", "credentials/describe");
        METHOD.put("credentials.set", "credentials/set");
        METHOD.put("credentials.unset", "credentials/unset");
        // host.*（新 API 拆到 directoryPicker / session）
        METHOD.put("host.listDirectory", "directoryPicker/list");
        METHOD.put("host.createDirectory", "directoryPicker/createDirectory");
        METHOD.put("host.pickDirectory", "directoryPicker/pick");
        METHOD.put("host.openPath", "session/openWorkspacePath");
        METHOD.put("host.describe", SYNTHETIC_HOST_DESCRIBE);
        // skill
        METHOD.put("skill.list", "skills/list");
        // 其余同名
        METHOD.put("commands/list", "commands/list");
        METHOD.put("commands/execute", "commands/execute");
    }

    /** 旧方法名 → 新方法名（未知的按原样透传）。 */
    public static String mapMethod(String oldPath) {
        String mapped = METHOD.get(oldPath);
        return mapped == null ? oldPath : mapped;
    }

    /** 是否为本地合成方法（不发给服务端）。 */
    public static boolean isSynthetic(String mapped) {
        return mapped != null && mapped.startsWith("$synthetic/");
    }

    // ------------------------------------------------------------------ 载荷重排

    /**
     * 把旧版平铺载荷重排为新网关要求的 {@code {args:{…}}}。
     *
     * @param oldPath 旧方法名（点分或斜杠）
     * @param payload 旧载荷（平铺）
     * @return 新载荷；无法识别时退化为 {@code {args: payload}}
     */
    public static JSONObject buildArgs(String oldPath, JSONObject payload) {
        JSONObject p = payload == null ? new JSONObject() : payload;
        JSONObject args = new JSONObject();
        try {
            switch (oldPath) {
                case "session.list":
                case "workspace.list":
                    // 描述符形参名为 _request（SessionListRequest，可为空对象）
                    args.put("_request", new JSONObject());
                    break;

                case "session.create": {
                    JSONObject req = new JSONObject();
                    copyIfPresent(p, req, "cwd");
                    copyIfPresent(p, req, "workspaceId");
                    copyIfPresent(p, req, "sessionId");
                    copyIfPresent(p, req, "agentPreset");
                    args.put("request", req);
                    break;
                }

                case "session.prompt": {
                    JSONObject req = new JSONObject();
                    req.put("requestId", UUID.randomUUID().toString());
                    req.put("sessionId", p.optString("sessionId", ""));
                    req.put("mode", p.optString("mode", "queue"));
                    JSONArray content = p.optJSONArray("content");
                    req.put("content", content == null ? new JSONArray() : content);
                    args.put("request", req);
                    break;
                }

                case "session.history": {
                    JSONObject req = new JSONObject();
                    JSONObject address = new JSONObject();
                    address.put("kind", "session");
                    address.put("sessionId", p.optString("sessionId", ""));
                    req.put("address", address);
                    // throughSeq 是跟随流的游标；直接取页时用一个足够大的值表示"到最新"
                    req.put("throughSeq", Integer.MAX_VALUE);
                    req.put("maxMessages", 400);
                    args.put("request", req);
                    break;
                }

                case "session.rename": {
                    JSONObject req = new JSONObject();
                    req.put("sessionId", p.optString("sessionId", ""));
                    req.put("title", p.optString("title", ""));
                    args.put("request", req);
                    break;
                }

                case "session.cancel": {
                    JSONObject req = new JSONObject();
                    req.put("sessionId", p.optString("sessionId", ""));
                    args.put("request", req);
                    break;
                }

                case "session.fork": {
                    JSONObject req = new JSONObject();
                    req.put("sessionId", p.optString("sessionId", ""));
                    args.put("request", req);
                    break;
                }

                case "session.attachment": {
                    JSONObject req = new JSONObject();
                    req.put("sessionId", p.optString("sessionId", ""));
                    copyIfPresent(p, req, "attachmentId");
                    args.put("request", req);
                    break;
                }

                case "session.search": {
                    JSONObject req = new JSONObject();
                    req.put("query", p.optString("query", ""));
                    args.put("request", req);
                    break;
                }

                case "session.selectModel": {
                    JSONObject req = new JSONObject();
                    req.put("sessionId", p.optString("sessionId", ""));
                    copyIfPresent(p, req, "provider");
                    copyIfPresent(p, req, "model");
                    copyIfPresent(p, req, "reasoningEffort");
                    args.put("request", req);
                    break;
                }

                case "session.updateQueue": {
                    JSONObject req = new JSONObject();
                    req.put("sessionId", p.optString("sessionId", ""));
                    copyIfPresent(p, req, "itemId");
                    copyIfPresent(p, req, "action");
                    args.put("request", req);
                    break;
                }

                case "session.models":
                    // modelCatalog 无入参
                    break;

                case "workspace.create":
                case "workspace.rename":
                case "workspace.delete":
                case "workspace.insertBefore":
                case "workspace.insertSessionBefore":
                case "workspace.archiveSession": {
                    JSONObject req = new JSONObject();
                    for (String k : new String[]{"path", "name", "workspaceId", "sessionId",
                            "beforeSessionId", "targetSessionId", "title"}) {
                        copyIfPresent(p, req, k);
                    }
                    args.put("request", req);
                    break;
                }

                case "settings.update":
                case "settings.replace":
                case "settings.mutate":
                    args.put("ns", p.optString("ns", p.optString("namespace", "")));
                    break;

                case "settings.describe":
                case "settings.openDocument":
                case "llm.models":
                case "llm.providers":
                case "agentPreset.list":
                case "agentPresets/list":
                case "host.pickDirectory":
                case "directoryPicker/pick":
                    break;

                case "llm.discoverModels":
                    // 描述符: llm/discoverModels(settingsNs: string, request: LlmModelDiscoveryRequest)
                    if (p.has("settingsNs")) args.put("settingsNs", p.optString("settingsNs", ""));
                    args.put("request", p.optJSONObject("request") == null
                            ? new JSONObject() : p.optJSONObject("request"));
                    break;

                case "agentPreset.read":
                    // 描述符: agentPresets/read(agentPreset: string)
                    args.put("agentPreset", p.optString("agentPreset", ""));
                    break;

                case "agentPreset.select":
                    // 描述符: agentPresets/select(agentId: SessionId, agentPreset: string)
                    args.put("agentId", p.optString("agentId", p.optString("sessionId", "")));
                    args.put("agentPreset", p.optString("agentPreset", ""));
                    break;

                case "agentPreset.copy":
                    copyIfPresent(p, args, "from");
                    copyIfPresent(p, args, "id");
                    break;

                case "agentPreset.remove":
                    args.put("id", p.optString("agentPreset", p.optString("id", "")));
                    break;

                case "agentPreset.openDocument":
                    args.put("agentPreset", p.optString("agentPreset", ""));
                    break;

                case "credentials.describe": {
                    JSONArray refs = p.optJSONArray("refs");
                    args.put("refs", refs == null ? new JSONArray() : refs);
                    break;
                }

                case "credentials.set": {
                    copyIfPresent(p, args, "ref");
                    copyIfPresent(p, args, "value");
                    // 旧版可能用 key/secret 命名
                    if (!args.has("ref") && p.has("key")) args.put("ref", p.optString("key", ""));
                    if (!args.has("value") && p.has("secret")) args.put("value", p.optString("secret", ""));
                    break;
                }

                case "credentials.unset": {
                    JSONArray refs = new JSONArray();
                    if (p.has("ref")) {
                        refs.put(p.optString("ref", ""));
                    } else {
                        JSONArray old = p.optJSONArray("refs");
                        if (old != null) refs = old;
                    }
                    args.put("refs", refs);
                    break;
                }

                case "host.listDirectory":
                case "directoryPicker/list":
                    if (p.has("path")) args.put("path", p.optString("path", ""));
                    break;

                case "host.createDirectory":
                case "directoryPicker/createDirectory":
                    args.put("path", p.optString("path", ""));
                    args.put("name", p.optString("name", ""));
                    break;

                case "host.openPath":
                case "session/openWorkspacePath": {
                    JSONObject req = new JSONObject();
                    req.put("sessionId", p.optString("sessionId", ""));
                    req.put("path", p.optString("path", ""));
                    args.put("request", req);
                    break;
                }

                case "skill.list":
                case "skills/list": {
                    JSONObject req = new JSONObject();
                    req.put("sessionId", p.optString("sessionId", ""));
                    args.put("request", req);
                    break;
                }

                case "subagent.list":
                case "subagents/list":
                case "subagent.history": {
                    // 0.1.7-rc.1 起没有 subagents/list 了，改打 session/list；
                    // 它只要一个可空的 _request，parentSessionId 只在结果侧用来定位父会话。
                    args.put("_request", new JSONObject());
                    break;
                }

                case "subagent.prompt":
                case "subagents/prompt": {
                    // 描述符: subagents/prompt(request: SubagentPromptRequest)
                    JSONObject req = new JSONObject();
                    if (p.has("request")) {
                        req = p.optJSONObject("request");
                        if (req == null) req = new JSONObject();
                    } else {
                        for (String k : new String[]{"parentSessionId", "prompt", "text",
                                "content", "mode", "agentPreset", "cwd"}) {
                            copyIfPresent(p, req, k);
                        }
                        if (p.has("sessionId") && !req.has("parentSessionId")) {
                            req.put("parentSessionId", p.optString("sessionId", ""));
                        }
                    }
                    args.put("request", req);
                    break;
                }

                case "subagent.interrupt":
                case "subagents/interruptByParent": {
                    // 描述符: subagents/interruptByParent(childSessionId, parentSessionId, mode)
                    args.put("childSessionId", p.optString("childSessionId", p.optString("sessionId", "")));
                    args.put("parentSessionId", p.optString("parentSessionId", ""));
                    args.put("mode", p.optString("mode", "continuable"));
                    break;
                }

                // goals/* 一律以 agentId 寻址（旧版用 sessionId）
                case "goal.get":
                case "goal.create":
                case "goal.edit":
                case "goal.pause":
                case "goal.resume":
                case "goal.complete":
                case "goal.clear": {
                    args.put("agentId", p.optString("agentId", p.optString("sessionId", "")));
                    for (String k : new String[]{"ref", "maxGoalRounds", "action", "blockedReason"}) {
                        copyIfPresent(p, args, k);
                    }
                    if (p.has("request")) {
                        args.put("request", p.optJSONObject("request"));
                    } else if (p.has("objective")) {
                        JSONObject req = new JSONObject();
                        copyIfPresent(p, req, "objective");
                        copyIfPresent(p, req, "maxGoalRounds");
                        args.put("request", req);
                    }
                    break;
                }

                case "commands/list":
                case "commands/execute": {
                    // 旧版已是 {args:{agentId,…}}，而新描述符的形参名恰好也是 agentId/line，
                    // 只需把内层对象平铺上来，并把旧键 images 换成 submittedAttachments。
                    JSONObject inner = p.optJSONObject("args");
                    if (inner == null) inner = new JSONObject();
                    for (java.util.Iterator<String> it = inner.keys(); it.hasNext(); ) {
                        String k = it.next();
                        if ("images".equals(k)) {
                            args.put("submittedAttachments", inner.get(k));
                        } else {
                            args.put(k, inner.get(k));
                        }
                    }
                    break;
                }

                default:
                    // 未知方法：整体塞进 args，避免丢参数
                    for (java.util.Iterator<String> it = p.keys(); it.hasNext(); ) {
                        String k = it.next();
                        args.put(k, p.get(k));
                    }
                    break;
            }
        } catch (JSONException e) {
            throw new IllegalStateException("ApiCompat.buildArgs failed for " + oldPath, e);
        }
        JSONObject wrapped = new JSONObject();
        try {
            wrapped.put("args", args);
        } catch (JSONException e) {
            throw new IllegalStateException("ApiCompat: wrap args failed", e);
        }
        return wrapped;
    }

    private static void copyIfPresent(JSONObject from, JSONObject to, String key) throws JSONException {
        if (from.has(key) && !from.isNull(key)) {
            to.put(key, from.get(key));
        }
    }

    // ------------------------------------------------------------------ 结果适配

    /**
     * 把新版结果对象适配回旧版形状；无需适配时返回原对象。
     *
     * @param oldPath 旧方法名
     * @param value   新版 {@code result.value}
     */
    public static JSONObject adaptResult(String oldPath, JSONObject value) {
        return adaptResult(oldPath, null, value);
    }

    /**
     * 带请求上下文的版本：少数新方法要靠**原始请求参数**才能还原旧形状
     * （例如 subagent.list 现在借用 session/list，需要 parentSessionId 定位父会话）。
     *
     * @param oldPath 旧方法名
     * @param request 旧版平铺请求载荷（可能为 null）
     * @param value   新版 {@code result.value}
     */
    public static JSONObject adaptResult(String oldPath, JSONObject request, JSONObject value) {
        if (value == null) return null;
        try {
            switch (oldPath) {
                case "session.history": {
                    // {records,hasMore} → 旧版事件账本 {events:[{type,seq,time,data},…]}
                    JSONObject out = new JSONObject();
                    JSONArray events = new JSONArray();
                    JSONArray records = value.optJSONArray("records");
                    if (records != null) {
                        for (int i = 0; i < records.length(); i++) {
                            JSONObject rec = records.optJSONObject(i);
                            if (rec == null) continue;
                            JSONObject ev = rec.optJSONObject("event");
                            if (ev != null) events.put(ev);
                        }
                    }
                    out.put("events", events);
                    out.put("hasMore", value.optBoolean("hasMore", false));
                    return out;
                }
                case "subagent.list":
                case "subagents/list":
                case "subagent.history": {
                    // 新形状（session/list）：{items:[{sessionId, projections:{values:{subagentCatalog:[…]}}}]}
                    // 旧形状（subagents/list）：{entries:[{kind:"child", id, activity, hasChildren, mode, label?}], parentAvailable}
                    // 两者字段名不同，这里按父会话取出 catalog 再还原。
                    String parentId = request == null ? ""
                            : request.optString("parentSessionId", request.optString("sessionId", ""));
                    JSONObject out = new JSONObject();
                    JSONArray entries = new JSONArray();
                    boolean parentAvailable = false;
                    JSONArray items = value.optJSONArray("items");
                    if (items != null && !parentId.isEmpty()) {
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject it = items.optJSONObject(i);
                            if (it == null || !parentId.equals(it.optString("sessionId", ""))) continue;
                            parentAvailable = true;
                            JSONObject proj = it.optJSONObject("projections");
                            JSONObject values = proj == null ? null : proj.optJSONObject("values");
                            JSONArray catalog = values == null ? null : values.optJSONArray("subagentCatalog");
                            if (catalog != null) {
                                for (int j = 0; j < catalog.length(); j++) {
                                    JSONObject e = catalog.optJSONObject(j);
                                    if (e == null) continue;
                                    JSONObject entry = new JSONObject();
                                    entry.put("kind", "child");
                                    entry.put("id", e.optString("id", ""));
                                    entry.put("activity", e.optString("activity", "inactive"));
                                    // 新 catalog 不含这个字段（官方 UI 也不读），保守取 false
                                    entry.put("hasChildren", e.optBoolean("hasChildren", false));
                                    entry.put("mode", e.optString("mode", "one-shot"));
                                    String label = e.optString("label", "");
                                    if (!label.isEmpty()) entry.put("label", label);
                                    entries.put(entry);
                                }
                            }
                            break;
                        }
                    }
                    out.put("entries", entries);
                    out.put("parentAvailable", parentAvailable);
                    return out;
                }
                case "session.list":
                case "workspace.list": {
                    // 新版摘要没有 title，标题在 projections 里；这里把 projections.title 提升为 title
                    JSONArray items = value.optJSONArray("items");
                    if (items == null) return value;
                    JSONArray out = new JSONArray();
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject it = items.optJSONObject(i);
                        if (it == null) continue;
                        JSONObject copy = new JSONObject(it.toString());
                        JSONObject proj = it.optJSONObject("projections");
                        if (proj != null && !copy.has("title")) {
                            String t = proj.optString("title", "");
                            if (!t.isEmpty()) copy.put("title", t);
                        }
                        out.put(copy);
                    }
                    JSONObject res = new JSONObject(value.toString());
                    res.put("items", out);
                    return res;
                }
                default:
                    return value;
            }
        } catch (JSONException e) {
            return value;
        }
    }
}
