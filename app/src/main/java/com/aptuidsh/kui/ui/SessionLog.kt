package com.aptuidsh.kui.ui

import org.json.JSONArray
import org.json.JSONObject

/**
 * 会话事件 → 消息面(surface)的**唯一折叠器**(C 档重构核心)。
 *
 * <p>同一份实现同时服务:
 * <ul>
 *   <li>实时增量:{@link #append(JSONObject)} 逐个事件折叠(等价旧 LiveFold);</li>
 *   <li>历史/校准回放:{@link #fromHistory}、{@link #prepend} 全量重折叠。</li>
 * </ul>
 *
 * <p>关键一致性:历史与实时都产出**同一套字段**——turn/step/time/lastSeq/turnStartTime/
 * firstTokenTime/completedTime/error/附件。这是消除"每次重拉整表 key 漂移"的根因修复。
 * 折叠结果用 {@link #snapshot()} 导出为不可变列表供 Compose 使用。
 *
 * <p>原始事件窗口被保留,以便 loadMore(前插更早事件)与 gap 修复(整窗重建)复用同一折叠。
 */
class SessionLog {

    private val messages = ArrayList<ChatMessage>()
    private val toolResults = HashMap<String, String>()
    private val rawEvents = ArrayList<JSONObject>()
    private var turnStartTime = 0L

    /** 已折叠消息的不可变快照。 */
    fun snapshot(): List<ChatMessage> = ArrayList(messages)

    /** 工具结果缓存(实时路径外部共享;历史回放内部已维护)。 */
    fun toolResultCache(): Map<String, String> = HashMap(toolResults)

    /** 折叠一个原始 session 事件(type/data/time/seq 顶层)。 */
    fun append(event: JSONObject) {
        rawEvents.add(event)
        foldEvent(event)
    }

    /** 历史回放入口:解壳信封 → 按 seq 排序全量折叠 → 孤立用户消息裁剪。 */
    companion object {
        fun fromHistory(events: List<JSONObject>): SessionLog {
            val log = SessionLog()
            val raw = events.mapNotNull { it.optJSONObject("event") }
                .sortedBy { it.optLong("seq", 0L) }
            log.rawEvents.addAll(raw)
            log.refold(prune = true)
            return log
        }
    }

    /**
     * 前插更早的历史事件(loadMore):合并去重后整窗重折叠并裁剪孤立用户消息。
     * 更早事件传信封数组(带 event 外壳),与 history 接口一致。
     */
    fun prepend(envelopes: List<JSONObject>) {
        val older = envelopes.mapNotNull { it.optJSONObject("event") }
            .sortedBy { it.optLong("seq", 0L) }
        if (older.isEmpty()) return
        val seen = HashSet<Long>()
        val merged = ArrayList<JSONObject>(older.size + rawEvents.size)
        fun push(e: JSONObject) {
            val s = e.optLong("seq", 0L)
            if (s > 0L && seen.add(s)) merged.add(e)
        }
        older.forEach(::push)
        rawEvents.forEach(::push)
        rawEvents.clear()
        rawEvents.addAll(merged)
        refold(prune = true)
    }

    private fun refold(prune: Boolean) {
        messages.clear()
        toolResults.clear()
        turnStartTime = 0L
        for (e in rawEvents) {
            foldEvent(e)
        }
        if (prune) pruneOrphans()
    }

    // ==================== 事件分派 ====================

    private fun foldEvent(event: JSONObject) {
        val type = event.optString("type")
        val data = event.optJSONObject("data") ?: return
        val time = event.optLong("time", System.currentTimeMillis())
        val seq = event.optLong("seq", 0L)
        when (type) {
            "assistant/chunk" -> applyChunk(data, time, seq)
            "assistant/message" -> applyAssistantMessage(data, time, seq)
            "tool/result" -> applyToolResult(data)
            "user/message" -> applyUserMessage(data, time, seq)
            "command/run" -> applyCommandRun(data, time)
            "command/done" -> applyCommandDone(data, time)
            "turn/start" -> applyTurnStart(time)
            "turn/end" -> applyTurnEnd(data, time, seq)
            "step/end" -> sealStreaming(time)
            else -> { /* 忽略(含 agent/inbox/spliced、投影类等) */ }
        }
    }

    // ==================== 轮次开始 ====================

    private fun applyTurnStart(time: Long) {
        turnStartTime = time
        val last = messages.lastOrNull()
        if (last != null && last.role == "assistant" && last.streaming && last.turnStartTime == 0L) {
            messages[messages.size - 1] = last.copy(turnStartTime = time)
        }
    }

    // ==================== 流式 chunk ====================

    private fun applyChunk(data: JSONObject, time: Long, seq: Long) {
        val chunk = data.optJSONObject("chunk") ?: return
        val kind = chunk.optString("type")
        if (kind == "usage" || kind == "finish") return
        val turn = data.optInt("turn", -1)
        val step = data.optInt("step", -1)

        val last = messages.lastOrNull()
        val activeIndex = if (last != null && last.role == "assistant" && last.streaming) {
            messages.size - 1
        } else {
            messages.add(
                ChatMessage(
                    role = "assistant",
                    blocks = emptyList(),
                    time = time,
                    turn = turn,
                    step = step,
                    turnStartTime = turnStartTime,
                    streaming = true,
                ),
            )
            messages.size - 1
        }
        val active = messages[activeIndex]
        val blocks = active.blocks.toMutableList()
        var firstTokenStamped = false

        when (kind) {
            "block-start" -> {
                val bt = chunk.optString("blockType", "text")
                when (bt) {
                    "reasoning" -> blocks.add(MessageBlock(type = "reasoning", label = "💭 Think · ", extra = ""))
                    "tool-call" -> blocks.add(MessageBlock(type = "tool-call", label = "", toolKind = "", extra = ""))
                    else -> blocks.add(MessageBlock(type = "text", label = ""))
                }
            }
            "text-delta" -> {
                val delta = chunk.optString("text")
                if (delta.isNotEmpty()) {
                    val lastText = blocks.indexOfLast { it.type == "text" }
                    if (lastText >= 0) {
                        blocks[lastText] = blocks[lastText].copy(label = blocks[lastText].label + delta)
                    } else {
                        blocks.add(MessageBlock(type = "text", label = delta))
                    }
                    if (active.firstTokenTime == 0L) firstTokenStamped = true
                }
            }
            "reasoning-delta" -> {
                val delta = chunk.optString("text")
                if (delta.isNotEmpty()) {
                    val lastR = blocks.indexOfLast { it.type == "reasoning" }
                    if (lastR >= 0) {
                        val b = blocks[lastR]
                        blocks[lastR] = b.copy(extra = b.extra + delta)
                    } else {
                        blocks.add(MessageBlock(type = "reasoning", label = "💭 Think · ", extra = delta))
                    }
                }
            }
            // 对齐官方 BlockAssembler tool-call-delta 处理:
            // 累积 id → toolCallId, name → toolKind+label, argumentsDelta → extra(追加)
            "tool-call-delta" -> {
                val callId = chunk.optString("id", "")
                val name = chunk.optString("name", "")
                val argsDelta = chunk.optString("argumentsDelta", "")
                if (callId.isNotEmpty() || name.isNotEmpty() || argsDelta.isNotEmpty()) {
                    // 定位最后一个 tool-call block（与 text-delta/reasoning-delta 同模式）
                    var lastTool = blocks.indexOfLast { it.type == "tool-call" }
                    // 官方 ensure() 模式:如果没有前置 block-start,自动创建 tool-call block
                    if (lastTool < 0) {
                        blocks.add(
                            MessageBlock(
                                type = "tool-call",
                                label = "",
                                toolKind = "",
                                extra = "",
                                toolCallId = "",
                            ),
                        )
                        lastTool = blocks.size - 1
                    }
                    val b = blocks[lastTool]
                    blocks[lastTool] = b.copy(
                        // id 幂等赋值（每个 delta 都带 id,直接覆盖）
                        toolCallId = if (callId.isNotEmpty()) callId else b.toolCallId,
                        // name 可选,仅首个 delta 出现;同时更新 toolKind 和 label
                        toolKind = if (name.isNotEmpty()) name else b.toolKind,
                        label = if (name.isNotEmpty()) "$name · " else b.label,
                        // argumentsDelta 追加到 extra（JSON 参数流式拼接）
                        extra = b.extra + argsDelta,
                    )
                    if (active.firstTokenTime == 0L) firstTokenStamped = true
                }
            }
            "block-end" -> {
                // 对齐官方 BlockAssembler:block-end 第一关闭优先,
                // 重复关闭(已有内容)被忽略。
                // 仅清理"全空"块(无 label/extra/result/toolCallId/toolKind),
                // 有内容的 tool-call 块保留(已被 tool-call-delta 填充)
                val lastB = blocks.lastOrNull()
                if (lastB != null && lastB.label.isEmpty() && lastB.extra.isEmpty()
                    && lastB.result.isEmpty() && lastB.toolCallId.isEmpty()
                    && lastB.toolKind.isEmpty()
                ) {
                    blocks.removeAt(blocks.size - 1)
                }
            }
        }
        messages[activeIndex] = active.copy(
            blocks = blocks,
            streaming = true,
            firstTokenTime = if (firstTokenStamped) time else active.firstTokenTime,
            lastSeq = if (seq > 0L) seq else active.lastSeq,
        )
    }

    // ==================== assistant/message 权威落定 ====================

    private fun applyAssistantMessage(data: JSONObject, time: Long, seq: Long) {
        val message = data.optJSONObject("message") ?: return
        val content = message.optJSONArray("content")
        val blocks = assistantContentToBlocks(content, toolResults)
        val turn = data.optInt("turn", -1)
        val step = data.optInt("step", -1)
        if (blocks.isEmpty()) {
            sealStreaming(time)
            return
        }
        val last = messages.lastOrNull()
        var preservedTurnStart = 0L
        var preservedFirstToken = 0L
        if (last != null && last.role == "assistant" && last.streaming) {
            preservedTurnStart = last.turnStartTime
            preservedFirstToken = last.firstTokenTime
            messages.removeAt(messages.size - 1)
        }
        val prev = messages.lastOrNull()
        if (prev != null && prev.role == "assistant") {
            messages[messages.size - 1] = prev.copy(
                blocks = prev.blocks + blocks,
                streaming = false,
                // 注意:不更新 time——time 参与 LazyColumn item key,更新会导致
                // 消息整体重建、折叠行展开状态丢失;落定时间用 completedTime 承载。
                turn = if (prev.turn >= 0) prev.turn else turn,
                step = step,
                turnStartTime = if (prev.turnStartTime > 0L) prev.turnStartTime else preservedTurnStart,
                firstTokenTime = if (prev.firstTokenTime > 0L) prev.firstTokenTime else preservedFirstToken,
                completedTime = time,
                lastSeq = if (seq > 0L) seq else prev.lastSeq,
            )
        } else {
            messages.add(
                ChatMessage(
                    role = "assistant",
                    blocks = blocks,
                    time = time,
                    turn = turn,
                    step = step,
                    turnStartTime = if (preservedTurnStart > 0L) preservedTurnStart else turnStartTime,
                    firstTokenTime = preservedFirstToken,
                    completedTime = time,
                    lastSeq = if (seq > 0L) seq else 0L,
                ),
            )
        }
    }

    // ==================== 工具结果回填 ====================

    private fun applyToolResult(data: JSONObject) {
        val message = data.optJSONObject("message") ?: return
        val content = message.optJSONArray("content") ?: return
        val resolved = HashMap<String, String>()
        for (j in 0 until content.length()) {
            val block = content.optJSONObject(j) ?: continue
            if (block.optString("type") != "tool-result") continue
            val callId = block.optString("toolCallId")
            val sb = StringBuilder()
            block.optJSONArray("content")?.let { arr ->
                for (k in 0 until arr.length()) {
                    val ib = arr.optJSONObject(k) ?: continue
                    if (ib.optString("type") == "text") sb.append(ib.optString("text"))
                }
            }
            if (callId.isNotEmpty()) {
                toolResults[callId] = sb.toString()
                resolved[callId] = sb.toString()
            }
        }
        if (resolved.isEmpty()) return
        for (i in messages.indices) {
            val m = messages[i]
            if (m.role != "assistant") continue
            var dirty = false
            val nb = m.blocks.map { b ->
                if (b.type == "tool-call" && b.toolCallId.isNotEmpty()) {
                    val r = resolved[b.toolCallId]
                    if (r != null && b.result.isEmpty()) {
                        dirty = true
                        b.copy(result = r)
                    } else b
                } else b
            }
            if (dirty) messages[i] = m.copy(blocks = nb)
        }
    }

    // ==================== 用户输入 / 上下文注入 ====================

    private fun applyUserMessage(data: JSONObject, time: Long, seq: Long) {
        val source = data.optJSONObject("source")
        val sourceKind = source?.optString("kind", "") ?: ""
        val content = data.optJSONArray("content")

        if (sourceKind.isNotEmpty() && sourceKind != "user") {
            val label = buildInjectionLabel(sourceKind, source)
            val body = extractContentText(content)
            cacheToolResults(content)
            if (body.isNotEmpty()) {
                sealStreaming(time)
                messages.add(
                    findInsertIndex(seq),
                    ChatMessage(
                        role = "assistant",
                        blocks = listOf(MessageBlock(type = "context-injection", label = label, extra = body)),
                        time = time,
                        lastSeq = seq,
                    ),
                )
            }
            return
        }
        val (textParts, images, files) = parseUserMessageContent(content, toolResults)
        if (textParts.isEmpty() && images.isEmpty() && files.isEmpty()) return
        sealStreaming(time)
        messages.add(
            findInsertIndex(seq),
            ChatMessage(
                role = "user",
                blocks = if (textParts.isEmpty()) emptyList()
                else listOf(MessageBlock(type = "text", label = textParts.joinToString("\n"))),
                time = time,
                lastSeq = seq,
                images = images,
                files = files,
            ),
        )
    }

    private fun findInsertIndex(seq: Long): Int {
        if (seq <= 0L || messages.isEmpty()) return messages.size
        for (i in messages.indices) {
            val m = messages[i]
            if (m.lastSeq > 0L && m.lastSeq >= seq) return i
        }
        return messages.size
    }

    // ==================== 命令执行 ====================

    private fun applyCommandRun(data: JSONObject, time: Long) {
        val commandId = data.optString("commandId", "")
        val name = data.optString("name", "")
        val args = data.optString("args", "").trim()
        if (commandId.isEmpty() || name.isEmpty()) return
        sealStreaming(time)
        val label = if (args.isNotEmpty()) "$name · $args" else name
        messages.add(
            ChatMessage(
                role = "assistant",
                blocks = listOf(
                    MessageBlock(
                        type = "tool-call",
                        label = label,
                        toolKind = "command",
                        toolCallId = "cmd-$commandId",
                    ),
                ),
                time = time,
            ),
        )
    }

    private fun applyCommandDone(data: JSONObject, time: Long) {
        val commandId = data.optString("commandId", "")
        val kind = data.optString("kind", "")
        val text = data.optString("text", "")
        if (commandId.isEmpty()) return
        val key = "cmd-$commandId"
        for (i in messages.indices) {
            val m = messages[i]
            if (m.role != "assistant") continue
            var dirty = false
            val nb = m.blocks.map { b ->
                if (b.type == "tool-call" && b.toolCallId == key && b.result.isEmpty()) {
                    dirty = true
                    val resultText = if (kind == "error") "❌ $text" else if (text.isNotEmpty()) text else "✅"
                    b.copy(result = resultText)
                } else b
            }
            if (dirty) messages[i] = m.copy(blocks = nb)
        }
    }

    // ==================== 封口与错误横幅 ====================

    private fun sealStreaming(time: Long) {
        val last = messages.lastOrNull() ?: return
        if (last.role == "assistant" && last.streaming) {
            messages[messages.size - 1] = last.copy(streaming = false, completedTime = time)
        }
    }

    private fun applyTurnEnd(data: JSONObject, time: Long, seq: Long) {
        sealStreaming(time)
        val reason = data.optJSONObject("reason") ?: return
        if (reason.optString("kind", "") != "error") return
        val errorObj = reason.optJSONObject("error")
        val code = errorObj?.optString("code", "") ?: ""
        val msg = errorObj?.optString("message", "") ?: ""
        val errText = turnErrorText(code.ifEmpty { null }, msg.ifEmpty { null })
        if (errText.isEmpty()) return
        messages.add(
            ChatMessage(
                role = "assistant",
                blocks = emptyList(),
                time = time,
                lastSeq = seq,
                error = errText,
            ),
        )
    }

    // ==================== 孤立用户消息裁剪(仅历史/校准回放) ====================

    private fun pruneOrphans() {
        var i = 0
        while (i < messages.size) {
            val m = messages[i]
            if (m.role == "user") {
                val next = messages.getOrNull(i + 1)
                if (next == null || next.role != "assistant") {
                    messages.removeAt(i)
                    continue
                }
            }
            i++
        }
    }

    // ==================== 小型共用辅助 ====================

    private fun buildInjectionLabel(sourceKind: String, source: JSONObject?): String {
        val base = "上下文注入"
        val label = when (sourceKind) {
            "plugin" -> source?.optString("plugin", "") ?: ""
            "subagent-settled" -> "subagent-settled"
            "subagent-report" -> "subagent-report"
            "session-reference" -> "session-reference"
            "agent-instructions" -> "agent-instructions"
            "skill-invocation" -> source?.optString("name", "") ?: ""
            else -> sourceKind
        }
        return if (label.isNotEmpty()) "$base · $label" else base
    }

    private fun extractContentText(content: JSONArray?): String {
        if (content == null) return ""
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") {
                val t = block.optString("text", "")
                if (t.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append(t)
                }
            }
        }
        return sb.toString()
    }

    private fun cacheToolResults(content: JSONArray?) {
        if (content == null) return
        for (j in 0 until content.length()) {
            val b = content.optJSONObject(j) ?: continue
            if (b.optString("type") != "tool-result") continue
            val callId = b.optString("toolCallId")
            if (callId.isEmpty()) continue
            val sb = StringBuilder()
            b.optJSONArray("content")?.let { arr ->
                for (k in 0 until arr.length()) {
                    val ib = arr.optJSONObject(k) ?: continue
                    if (ib.optString("type") == "text") sb.append(ib.optString("text"))
                }
            }
            toolResults[callId] = sb.toString()
        }
    }
}
