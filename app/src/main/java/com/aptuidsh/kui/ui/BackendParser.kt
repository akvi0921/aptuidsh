package com.aptuidsh.kui.ui

import org.json.JSONArray
import org.json.JSONObject

/**
 * 后端 JSON 解析器:把 dsh 官方 API(session.list / session.history / agentPreset.list)
 * 的返回解析为 UI 模型。
 *
 * <p>协议信封:value 为 result.value。解析均为纯函数,不触碰线程。
 */

/** 解析 agentPreset.list → (id → 显示名) 映射。 */
fun parsePresetNameMap(value: JSONObject?): Map<String, String> {
    val map = HashMap<String, String>()
    val presets = value?.optJSONArray("presets") ?: return map
    for (i in 0 until presets.length()) {
        val p = presets.optJSONObject(i) ?: continue
        val id = p.optString("id")
        if (id.isNotEmpty()) {
            map[id] = p.optString("name", id)
        }
    }
    return map
}

/**
 * 解析 session.list → 会话列表项 SessionState。
 * title 取自 projections.values.title;agentPreset 为会话真实预设 id。
 * blank 会话无消息历史(loading 标记由上层处理)。
 * origin/parentSessionId/cwd/running 供会话列表分组(官方 workspace 树语义)。
 */
fun parseSessionList(value: JSONObject?): List<SessionState> {
    val items = value?.optJSONArray("items") ?: return emptyList()
    val list = ArrayList<SessionState>()
    for (i in 0 until items.length()) {
        val it = items.optJSONObject(i) ?: continue
        val id = it.optString("sessionId")
        if (id.isEmpty()) continue
        val pj = it.optJSONObject("projections")
        val pv = pj?.optJSONObject("values")
        val title = pv?.optString("title") ?: ""
        val blank = it.optBoolean("blank", false)
        val origin = it.optString("origin", "")
        list.add(
            SessionState(
                id = id,
                title = title,
                showWelcome = blank, // 空会话 → 引导层形态(无消息)
                updatedAt = it.optLong("updatedAt", System.currentTimeMillis()),
                agentPresetId = it.optString("agentPreset", ""),
                loading = !blank,
                origin = origin,
                parentSessionId = it.optString("parentSessionId", ""),
                running = it.optBoolean("running", false),
                cwd = it.optString("cwd", ""),
                blank = blank,
            ),
        )
    }
    return list
}

/** 一个 Host 工作区(来自 workspace.list,members 按存储顺序)。 */
data class WorkspaceEntry(
    val workspaceId: String,
    val path: String,
    val title: String,
    val sessionIds: List<String>,
)

/**
 * 解析 workspace.list → 工作区列表(顺序为 Host 稳定顺序;members=sessionIds 存储顺序)。
 */
fun parseWorkspaceList(value: JSONObject?): List<WorkspaceEntry> {
    val items = value?.optJSONArray("items") ?: return emptyList()
    val list = ArrayList<WorkspaceEntry>()
    for (i in 0 until items.length()) {
        val it = items.optJSONObject(i) ?: continue
        val id = it.optString("workspaceId")
        if (id.isEmpty()) continue
        val clean = ArrayList<String>()
        it.optJSONArray("sessionIds")?.let { arr ->
            for (j in 0 until arr.length()) {
                val s = arr.optString(j)
                if (s.isNotEmpty()) clean.add(s)
            }
        }
        list.add(
            WorkspaceEntry(
                workspaceId = id,
                path = it.optString("path", ""),
                title = it.optString("title", ""),
                sessionIds = clean,
            ),
        )
    }
    return list
}

/**
 * 解析 session.history → 消息列表(已填充工具结果)。
 *
 * <p>策略:按 seq 升序遍历 events,完全对齐官方 Web UI messageDefinition 语义:
 * <ul>
 *   <li>user/message 且 source.kind=="user" → 用户气泡(普通输入与 steering 插话共用;
 *       位置 = 事件在日志里的 seq,即真实注入上下文的位置)</li>
 *   <li>user/message 且 source.kind!="user" → 上下文注入折叠行(插件/子代理/引用/指令等)</li>
 *   <li>agent/inbox/spliced → 不渲染气泡(只维护 inbox 排队投影,由 session/queue 帧驱动排队列表)</li>
 *   <li>assistant/message → 助手消息;若上一条仍是助手(同 turn 连续 step)则合并 blocks</li>
 * </ul>
 * 块映射:reasoning → 💭 Think、text → 正文、tool-call → 工具卡(结果按 callId 回填)。
 *
 * <p>注意:用户气泡必须从 user/message 渲染而非 agent/inbox/spliced——后者记录的是
 * 消息「入队」时刻(发送时),其 seq 在 assistant 输出中间;前者记录的是消息被 agent
 * claim 后真正注入上下文的时刻(step 边界),seq 才是正确渲染位置。
 */
fun parseSessionHistory(value: JSONObject?): List<ChatMessage> {
    val events = value?.optJSONArray("events") ?: return emptyList()
    val envelopes = ArrayList<JSONObject>(events.length())
    for (i in 0 until events.length()) {
        events.optJSONObject(i)?.let { envelopes.add(it) }
    }
    // 统一经 SessionLog 折叠:历史回放与实时增量共用同一套折叠规则,
    // 保证消息字段(turn/step/time/lastSeq/计时/错误/附件)完全一致,消除重拉 key 漂移。
    return SessionLog.fromHistory(envelopes).snapshot()
}

/**
 * assistant/message 的 content[] → UI 块列表(供历史解析与实时流式共用)。
 * 块映射:reasoning → 💭 Think、text → 正文、tool-call → 工具卡(结果按 callId 回填)。
 */
fun assistantContentToBlocks(content: JSONArray?, toolResults: Map<String, String>): List<MessageBlock> {
    val blocks = ArrayList<MessageBlock>()
    content ?: return blocks
    for (j in 0 until content.length()) {
        val block = content.optJSONObject(j) ?: continue
        when (block.optString("type")) {
            "reasoning" -> {
                val text = block.optString("text")
                blocks.add(
                    MessageBlock(
                        type = "reasoning",
                        label = "💭 Think · " + firstLine(text, 36),
                        extra = text,
                    ),
                )
            }
            "text" -> {
                val text = block.optString("text")
                if (text.isNotBlank()) {
                    blocks.add(MessageBlock(type = "text", label = text))
                }
            }
            "tool-call" -> {
                val name = block.optString("name")
                val callId = block.optString("id")
                blocks.add(
                    MessageBlock(
                        type = "tool-call",
                        label = "$name ·  ",
                        toolKind = name,
                        extra = block.optString("arguments", "{}"),
                        result = toolResults[callId] ?: "",
                        toolCallId = callId,
                    ),
                )
            }
            else -> {
                // 其他块(未知):正文兜底
                val text = block.optString("text")
                if (text.isNotBlank()) {
                    blocks.add(MessageBlock(type = "text", label = text))
                }
            }
        }
    }
    return blocks
}

/** 取首行前 n 字(推理/标题截断用)。 */
private fun firstLine(text: String, max: Int): String {
    val first = text.substringBefore("\n").trim()
    return if (first.length <= max) first else first.take(max) + "…"
}

/** 模型目录项:一组模型(官方 session.models → groups[])。 */
data class ModelEntry(
    val providerId: String,    // 组 id(如 deepseek-official)
    val providerName: String,  // 组显示名(如 DeepSeek)
    val modelId: String,       // 模型 id(如 deepseek-v4-flash)
    val modelName: String,     // 模型显示名(如 DeepSeek-V4-Flash)
    val efforts: List<Pair<String, String>>, // 推理档位 (id→名称)
)

/**
 * 解析 session.models → 模型目录(仅含支持档位的模型)。
 */
fun parseModels(value: JSONObject?): List<ModelEntry> {
    val out = ArrayList<ModelEntry>()
    val groups = value?.optJSONArray("groups") ?: return out
    for (i in 0 until groups.length()) {
        val g = groups.optJSONObject(i) ?: continue
        val gid = g.optString("id")
        if (gid.isEmpty()) continue
        val models = g.optJSONArray("models") ?: continue
        for (j in 0 until models.length()) {
            val m = models.optJSONObject(j) ?: continue
            val mid = m.optString("id")
            if (mid.isEmpty()) continue
            val efforts = ArrayList<Pair<String, String>>()
            val reasoning = m.optJSONObject("reasoning")
            reasoning?.optJSONArray("efforts")?.let { arr ->
                for (k in 0 until arr.length()) {
                    val e = arr.optJSONObject(k) ?: continue
                    val eid = e.optString("id")
                    if (eid.isNotEmpty()) efforts.add(eid to e.optString("name", eid))
                }
            }
            if (efforts.isEmpty()) continue // 无可调档位的模型不入列(本端仅支持 effort 模型)
            out.add(
                ModelEntry(
                    providerId = gid,
                    providerName = g.optString("name", gid),
                    modelId = mid,
                    modelName = m.optString("name", mid),
                    efforts = efforts,
                ),
            )
        }
    }
    return out
}

/** 权限选项解析:projections.permissions {options:[{value,name}], currentValue}。 */
fun parsePermissionOptions(permissions: JSONObject?): Pair<String, List<Pair<String, String>>> {
    val current = permissions?.optString("currentValue", "") ?: ""
    val list = ArrayList<Pair<String, String>>()
    permissions?.optJSONArray("options")?.let { arr ->
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val v = o.optString("value")
            if (v.isNotEmpty()) list.add(v to o.optString("name", v))
        }
    }
    return current to list
}

/**
 * 官方 tokenUsage 投影(实测线上形状):
 * {@code {uncachedInputTokens, outputTokens, cacheReadTokens, cacheWriteTokens}}。
 *
 * 注意:输入侧用的是 uncachedInputTokens(未命中缓存部分),不是 inputTokens。
 * 这四项正好是 DeepSeek 的计费口径,可直接用于费用估算。
 */
data class TokenUsage(
    val uncachedInputTokens: Long = 0L,  // 输入·缓存未命中(贵)
    val cacheReadTokens: Long = 0L,      // 输入·缓存命中(便宜)
    val cacheWriteTokens: Long = 0L,     // 输入·缓存写入
    val outputTokens: Long = 0L,         // 输出
) {
    /** 总输入 = 未命中 + 命中 + 缓存写(官方 prompt_tokens 语义)。 */
    val totalInputTokens: Long get() = uncachedInputTokens + cacheReadTokens + cacheWriteTokens

    /** 是否已有任何用量(全 0 = 尚未产生,而非"用量为零")。 */
    val hasData: Boolean get() = totalInputTokens > 0L || outputTokens > 0L

    companion object {
        /** 从投影 JSON 解析(缺字段安全落 0)。 */
        fun parse(o: JSONObject?): TokenUsage {
            if (o == null) return TokenUsage()
            return TokenUsage(
                uncachedInputTokens = o.optLong("uncachedInputTokens", 0L),
                cacheReadTokens = o.optLong("cacheReadTokens", 0L),
                cacheWriteTokens = o.optLong("cacheWriteTokens", 0L),
                outputTokens = o.optLong("outputTokens", 0L),
            )
        }
    }
}

/**
 * 官方 sessionStats 投影(实测线上形状):
 * {@code {turns, steps, llmMs, toolMs, ttftMs, ttftSteps, decodeMs, decodeTokens}}。
 */
data class SessionStats(
    val turns: Long = 0L,        // 轮次
    val steps: Long = 0L,        // 步数
    val llmMs: Long = 0L,        // LLM 耗时
    val toolMs: Long = 0L,       // 工具耗时
    val decodeMs: Long = 0L,     // 纯解码耗时(不含首 token 等待),用于更贴近"生成速度"的分母
    val decodeTokens: Long = 0L, // 解码输出 token
) {
    val hasData: Boolean get() = turns > 0L || steps > 0L

    companion object {
        fun parse(o: JSONObject?): SessionStats {
            if (o == null) return SessionStats()
            return SessionStats(
                turns = o.optLong("turns", 0L),
                steps = o.optLong("steps", 0L),
                llmMs = o.optLong("llmMs", 0L),
                toolMs = o.optLong("toolMs", 0L),
                decodeMs = o.optLong("decodeMs", 0L),
                decodeTokens = o.optLong("decodeTokens", 0L),
            )
        }
    }
}

/**
 * 会话统计行(官方 sessionStats + tokenUsage 投影)。
 *
 * 修复:此前本函数接收 tokenUsage 却完全没用,只读 sessionStats.decodeTokens(仅输出 token),
 * 导致真正计费的大头——缓存命中的输入(便宜)与未命中的输入(贵)——全被丢掉。
 * 现按官方计费口径展示三项:总输入 / 命中缓存 / 未命中缓存 / 总输出。
 */
fun buildStatsText(stats: JSONObject?, tokenUsage: JSONObject?): String {
    fun hms(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        return if (h > 0) "${h}h${m}m" else "${m}m${s % 60}s"
    }

    val sb = StringBuilder()
    val st = SessionStats.parse(stats)
    if (stats != null) {
        sb.append("${st.turns}轮．${st.steps}步|LLM ${hms(st.llmMs)}．工具${hms(st.toolMs)}")
    }

    // 真实 token 用量:优先 tokenUsage 投影(权威、含缓存分项);缺失时回退 sessionStats.decodeTokens
    val u = TokenUsage.parse(tokenUsage)
    val out = if (u.outputTokens > 0L) u.outputTokens else st.decodeTokens
    if (u.totalInputTokens > 0L || out > 0L) {
        if (sb.isNotEmpty()) sb.append("|")
        sb.append(
            "入 ${compactTokens(u.totalInputTokens)}" +
                "(缓存 ${compactTokens(u.cacheReadTokens)}．未命中 ${compactTokens(u.uncachedInputTokens)})" +
                "．出 ${compactTokens(out)} tok",
        )
    }
    return sb.toString()
}

/** token 数紧凑显示(1.2M / 12.3K / 456)。 */
fun compactTokens(n: Long): String = when {
    n >= 1_000_000L -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000L -> {
        val k = n / 1000.0
        if (k >= 100) Math.round(k).toString() else String.format("%.1f", k)
    }
    else -> n.toString()
}

/** 上下文占用(官方 contextPressure 投影)→ 百分比与说明文本。 */
fun buildContext(pressure: JSONObject?): Pair<Float, String> {
    if (pressure == null) return -1f to ""
    val used = pressure.optLong("pressureTokens", 0L)
    val window = pressure.optLong("contextWindow", 0L)
    if (window <= 0) return -1f to ""
    val pct = (used * 100f / window).toFloat().coerceIn(0f, 100f)
    fun compact(n: Long): String = when {
        n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000f)
        n >= 1_000 -> String.format("%.0fK", n / 1_000f)
        else -> n.toString()
    }
    return pct to "~${compact(used)} / ${compact(window)}"
}

/** 会话模型投影(session.models value.current)→ provider/model/effort。 */
fun parseCurrentModel(value: JSONObject?): Triple<String, String, String> {
    val cur = value?.optJSONObject("current") ?: return Triple("", "", "")
    return Triple(
        cur.optString("provider", ""),
        cur.optString("model", ""),
        cur.optString("reasoningEffort", ""),
    )
}

/** 权限值 id → 显示名(官方产品文案);未知名原样返回。 */
fun permValueToDisplay(valueId: String, options: List<Pair<String, String>>): String? {
    if (valueId.isEmpty()) return null
    options.firstOrNull { it.first == valueId }?.let { return it.second }
    return when (valueId) {
        "read-only" -> "Read Only"
        "workspace-write" -> "Workspace Write"
        "danger-full-access" -> "Full access"
        else -> null
    }
}

/** 一条 slash 命令(commands.list 项)。 */
data class SlashCommandEntry(
    val name: String,        // 命令名(无斜杠;如 permission)
    val description: String,
    val hint: String,        // input.hint(如 <preset>);无则为空
)

/** 解析 commands.list value → 命令目录。 */
fun parseCommandList(value: JSONObject?): List<SlashCommandEntry> {
    val out = ArrayList<SlashCommandEntry>()
    val arr = value?.optJSONArray("value") ?: value?.optJSONArray("items") ?: return out
    for (i in 0 until arr.length()) {
        val c = arr.optJSONObject(i) ?: continue
        val name = c.optString("name")
        if (name.isEmpty()) continue
        out.add(
            SlashCommandEntry(
                name = name,
                description = c.optString("description", ""),
                hint = c.optJSONObject("input")?.optString("hint", "") ?: "",
            ),
        )
    }
    return out
}

/** Agent 预设目录项(agentPreset.list 项)。 */
data class AgentPresetEntry(
    val id: String,           // 预设 id(如 android-dev、standard)
    val name: String,         // 显示名(如 "Android APP 开发(AG 经验)")
    val description: String,  // 描述
)

/** 解析 agentPreset.list → 预设目录(完整信息,供引导层选择)。 */
fun parsePresetList(value: JSONObject?): List<AgentPresetEntry> {
    val out = ArrayList<AgentPresetEntry>()
    val presets = value?.optJSONArray("presets") ?: return out
    for (i in 0 until presets.length()) {
        val p = presets.optJSONObject(i) ?: continue
        val id = p.optString("id")
        if (id.isEmpty()) continue
        out.add(
            AgentPresetEntry(
                id = id,
                name = p.optString("name", id),
                description = p.optString("description", ""),
            ),
        )
    }
    return out
}

// ==================== 上下文注入辅助函数(对齐 Web UI ContextInjectionRow) ====================

/** 缓存一条 tool-result 块到 toolResults(供 assistant tool-call 卡回填)。 */
private fun cacheToolResult(block: JSONObject, toolResults: MutableMap<String, String>) {
    if (block.optString("type") != "tool-result") return
    val callId = block.optString("toolCallId")
    if (callId.isEmpty()) return
    val sb = StringBuilder()
    block.optJSONArray("content")?.let { arr ->
        for (k in 0 until arr.length()) {
            val innerBlock = arr.optJSONObject(k) ?: continue
            if (innerBlock.optString("type") == "text") sb.append(innerBlock.optString("text"))
        }
    }
    toolResults[callId] = sb.toString()
}

/** 附件文件 text 标记前缀(用于从 text 块中识别非图片文件附件)。 */
const val FILE_ATTACHMENT_PREFIX = "📎 附件文件: "

/**
 * 解析用户消息 content → (文本部分, 图片附件, 文件附件)。
 * 供实时流(LiveFold)与历史解析(BackendParser)共用,保证渲染规则一致。
 */
fun parseUserMessageContent(
    content: org.json.JSONArray?,
    toolResults: MutableMap<String, String>,
): Triple<List<String>, List<ImageAttachment>, List<FileAttachment>> {
    val textParts = ArrayList<String>()
    val images = ArrayList<ImageAttachment>()
    val files = ArrayList<FileAttachment>()
    if (content == null) return Triple(textParts, images, files)
    for (j in 0 until content.length()) {
        val block = content.optJSONObject(j) ?: continue
        when (block.optString("type")) {
            "text" -> {
                val txt = block.optString("text")
                if (txt.isNotBlank()) {
                    val (fileList, body) = extractFileAttachments(txt)
                    if (body.isNotBlank()) textParts.add(body)
                    files.addAll(fileList)
                }
            }
            "image" -> {
                val att = block.optJSONObject("attachment")
                if (att != null) {
                    images.add(
                        ImageAttachment(
                            attachmentId = att.optString("attachmentId", ""),
                            mediaType = att.optString("mediaType", ""),
                            name = att.optString("name", ""),
                            width = att.optInt("width", 0),
                            height = att.optInt("height", 0),
                        ),
                    )
                }
            }
            "tool-result" -> cacheToolResult(block, toolResults)
        }
    }
    return Triple(textParts, images, files)
}

/** 从 text 中拆出文件附件标记 → (文件列表, 剩余正文)。 */
fun extractFileAttachments(text: String): Pair<List<FileAttachment>, String> {
    val files = ArrayList<FileAttachment>()
    val body = StringBuilder()
    for (line in text.split("\n")) {
        val trimmed = line.trim()
        if (trimmed.startsWith(FILE_ATTACHMENT_PREFIX)) {
            val path = trimmed.removePrefix(FILE_ATTACHMENT_PREFIX).trim()
            if (path.isNotEmpty()) {
                val name = path.substringAfterLast("/")
                files.add(FileAttachment(name = name, path = path))
            }
        } else {
            if (body.isNotEmpty()) body.append("\n")
            body.append(line)
        }
    }
    return files to body.toString()
}
