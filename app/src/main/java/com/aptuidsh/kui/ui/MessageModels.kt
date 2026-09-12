package com.aptuidsh.kui.ui

import com.aptuidsh.kui.R

/**
 * 会话消息块模型(对齐 dsh 协议 content[] 块类型)。
 *
 * <p>type:
 * <ul>
 *   <li>context-injection — 上下文注入行(默认收起,展开浅灰容器显示注入内容)</li>
 *   <li>reasoning — 推理行(默认收起,展开完整推理文本)</li>
 *   <li>text — 正文(markdown-lite 渲染)</li>
 *   <li>tool-call — 工具调用行(默认收起,展开参数+结果)</li>
 *   <li>pending — 生成中占位</li>
 * </ul>
 */
data class MessageBlock(
    val type: String,
    val label: String = "",     // 标题行文本(注入/推理/工具名)
    val extra: String = "",     // 展开内容(注入全文/推理全文/工具参数)
    val result: String = "",    // 工具返回结果(可选)
    val toolKind: String = "",  // 工具行图标符号:bash/read/think/other
    val toolCallId: String = "", // 工具调用 id(实时 tool/result 回填匹配)
)

/** 图片附件(消息流渲染用;来自 user/message 的 image 块 attachment 引用)。 */
data class ImageAttachment(
    val attachmentId: String,   // 后端持久化引用 id
    val mediaType: String = "", // image/png|jpeg|webp|gif
    val name: String = "",      // 原始文件名
    val width: Int = 0,
    val height: Int = 0,
    val data: String = "",      // base64(渲染缩略图用;历史消息经 session.attachment 下载后填充)
)

/** 文件附件(非图片;通过 text 块告知助手的「文件名+路径」)。 */
data class FileAttachment(
    val name: String,           // 文件名
    val path: String,           // 复制到应用存储文件夹后的绝对路径
)

/** 草稿附件(发送前预览;本地选择后、尚未发送)。 */
data class DraftAttachment(
    val id: String,             // 本地唯一 id
    val name: String,           // 文件名
    val mimeType: String,       // MIME 类型
    val isImage: Boolean,       // 是否图片
    val data: String = "",      // base64(图片用于预览+上传)
    val size: Long = 0L,        // 字节数
)

/** 会话消息(用户/助手)。 */
data class ChatMessage(
    val role: String,           // user | assistant
    val blocks: List<MessageBlock>,
    val time: Long = System.currentTimeMillis(),
    val model: String = "DeepSeek-V4-Flash",
    val streaming: Boolean = false, // 是否流式输出中(流式中不打印时间行,结束后显示)
    // ---- 实时流式定位(官方 turn/step 坐标;历史与 mux chunk 均携带) ----
    val turn: Int = -1,         // 轮次号(assistant step 归属; -1=无)
    val step: Int = -1,         // step 号(assistant/message 落定粒度; -1=无)
    // ---- 计时信息(对齐官方 timing;用于底部操作栏显示运行时长/TTFT) ----
    val turnStartTime: Long = 0L,     // turn/start 时间
    val firstTokenTime: Long = 0L,    // 首个 token 时间
    val completedTime: Long = 0L,     // 落定时间(turn/end 或 assistant/message)
    val lastSeq: Long = 0L,           // 最后一个事件的 seq(用于 session.fork 的 atSeq 参数)
    // ---- 附件(用户消息:图片附件 + 文件附件) ----
    val images: List<ImageAttachment> = emptyList(),  // 图片附件(缩略图容器)
    val files: List<FileAttachment> = emptyList(),     // 文件附件(文件名容器)
    // ---- 错误(助手流式输出中断错误;非空表示渲染错误横幅) ----
    val error: String = "",        // 错误中文描述(turn/end reason.error 映射)
)

/** 工具行图标资源 id(对齐 Web UI VARIANT_ICONS,全部使用 PNG drawable)。 */
fun toolIconRes(kind: String): Int = when (kind) {
    "bash", "pwsh" -> R.drawable.ic_tool_bash
    "read" -> R.drawable.ic_tool_read
    "write" -> R.drawable.ic_tool_write
    "edit" -> R.drawable.ic_tool_edit
    "str-replace-editor" -> R.drawable.ic_tool_str_replace
    "grep" -> R.drawable.ic_tool_grep
    "glob" -> R.drawable.ic_tool_glob
    "web_search" -> R.drawable.ic_tool_web_search
    "web_fetch" -> R.drawable.ic_tool_web_fetch
    "todo_write" -> R.drawable.ic_tool_todo_write
    "ask_user" -> R.drawable.ic_tool_ask_user
    "skill" -> R.drawable.ic_tool_skill
    "goal" -> R.drawable.ic_tool_goal
    "subagent", "subagent_fork" -> R.drawable.ic_tool_subagent
    "workflow" -> R.drawable.ic_tool_workflow
    "command" -> R.drawable.ic_tool_command
    "subagent-control" -> R.drawable.ic_tool_subagent_control
    "subagent-report" -> R.drawable.ic_tool_subagent_report
    "jobs" -> R.drawable.ic_tool_jobs
    "ralph" -> R.drawable.ic_tool_ralph
    "cordis" -> R.drawable.ic_tool_cordis
    "fs" -> R.drawable.ic_tool_fs
    "fs-search" -> R.drawable.ic_tool_fs_search
    else -> R.drawable.ic_tool_default
}

/**
 * think 工具图标:根据推理是否结束选择不同图标。
 * @param streaming 父消息是否仍在流式输出中
 */
fun toolIconResThink(streaming: Boolean): Int =
    if (streaming) R.drawable.ic_tool_think_running else R.drawable.ic_tool_think_done

/** 工具行显示标题(对齐 Web UI VARIANT_TITLES)。 */
fun toolTitle(kind: String): String = when (kind) {
    "bash" -> "Bash"
    "pwsh" -> "Pwsh"
    "read" -> "Read"
    "grep", "glob" -> "Search"
    "write" -> "Write"
    "edit" -> "Edit"
    "todo_write" -> "任务"       // 中文:更新任务清单
    "web_search" -> "Search"
    "web_fetch" -> "Fetch"
    "command" -> "Command"
    else -> kind.ifEmpty { "Tool" }
}

/**
 * 会话窗口状态(每个会话独立,切换会话=保存当前+加载目标,浏览器标签页语义)。
 *
 * <p>包含该会话的全部 UI 状态:消息/引导层标志/编辑器未发送文本/模型/推理/权限/卡片收纳,
 * 相互隔离,切换会话不丢失。
 */
data class SessionState(
    val id: String,
    val title: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val showWelcome: Boolean = true,   // true=引导层(无会话记录), false=会话层
    val composerText: String = "",     // 编辑器内未发送文本
    val drafts: List<DraftAttachment> = emptyList(), // 附件草稿(选择后未发送)
    val collapsed: Boolean = false,    // 悬浮卡片收纳状态
    val model: String = "DeepSeek-V4-Flash",
    val reasoning: String = "High",
    val permission: String = "Full access",
    val updatedAt: Long = System.currentTimeMillis(),
    // ---- 后端真实会话字段 ----
    val agentPresetId: String = "",    // 会话使用的 Agent 预设 id(如 android-dev)
    val agentPresetName: String = "",  // 预设显示名(如 Android APP 开发(AG 经验))
    val loading: Boolean = false,      // 消息是否加载中
    // ---- 会话列表分组字段(官方 workspace 树语义) ----
    val origin: String = "",           // ''=主会话 | 'subagent'=子代理会话
    val parentSessionId: String = "",  // 子代理会话的父会话 id(主会话为空)
    val running: Boolean = false,      // 会话是否运行中(行首状态点; turn/start → true, turn/end → false)
    val turnCompleted: Boolean = false, // 当前 turn 是否完成(turn/end 到达后为 true; turn/start → false)
    val cwd: String = "",              // 会话工作目录(未入工作区时用于未分组)
    val blank: Boolean = false,        // 后端 session.list 的 blank 标志(空会话;列表仅当前显示)
    // ---- 会话真实配置(官方 session.models / permissions 投影) ----
    val modelId: String = "",          // 当前模型 id(如 deepseek-v4-flash)
    val provider: String = "",         // 模型提供方 id(如 deepseek-official)
    val reasoningEffort: String = "",  // 推理档位 id(off/low/high/max)
    val permissionValue: String = "",  // 权限值 id(read-only/workspace-write/danger-full-access)
    val permissionOptions: List<Pair<String, String>> = emptyList(), // 权限选项(value→名称)
    // ---- 会话实时统计(官方 projections:sessionStats/contextPressure/contextBreakdown) ----
    val statsText: String = "",        // 实时统计行(轮次/步数/耗时/输出)
    val contextPercent: Float = -1f,   // 上下文占用 %(-1=未知)
    val contextText: String = "",      // 上下文说明「~463K / 1M」
    // 消费统计弹窗用的原始投影(逐字段展示,不做字符串化——避免二次解析丢失精度/分项)
    val tokenUsage: TokenUsage = TokenUsage(),   // 官方 tokenUsage 投影(累计)
    val sessionStats: SessionStats = SessionStats(), // 官方 sessionStats 投影(轮次/步数/耗时)
    // 首次输入样本(用于估算「系统提示词消耗」:首轮未命中缓存的输入 token)
    val firstInputTokens: Long = -1L,
    // ---- 审批(官方 approval/asked 事件;Tool 需要升级沙箱时弹出) ----
    val pendingApproval: ApprovalRequest? = null,
    // ---- 提问(官方 question/requested 独立 mux 帧;助手用提问工具等待用户回答) ----
    val pendingQuestion: QuestionRequest? = null,
    // ---- 历史分页(官方 session.history 分页语义:beforeSeq + maxMessages) ----
    val hasMoreHistory: Boolean = false,  // 是否还有更早的历史(后端 pageOf.hasMore)
    val oldestSeq: Long = Long.MAX_VALUE, // 当前消息中最小的 seq(用于 beforeSeq 翻页)
    // ---- 排队消息(官方 session/queue 事件) ----
    val queueItems: List<QueueItem> = emptyList(), // 排队中的用户消息
    // ---- 输入错误(引导层/会话层横幅提示;发送失败时设置,发送成功清除) ----
    val promptError: String = "",  // 输入消息发送失败的错误中文描述
)

/** 排队消息条目(官方 session/queue 事件)。 */
data class QueueItem(
    val id: String,              // 消息 id
    val placement: String,       // "queued"(排队) / "steering"(立即插入)
    val text: String,            // 消息文本
    val source: String = "",     // 消息来源
)

/** 审批请求(官方 approval/asked 事件)。Tool 请求升级沙箱权限时触发。 */
data class ApprovalRequest(
    val sessionId: String,    // 发起审批的会话 id(mux payload.sessionId;respond 时必须用此 id)
    val muxRpcId: String,     // mux 帧 envelope 的 rpcId(PendingWait 用它匹配后端等待)
    val id: String,           // 审批 id(approvalId)
    val toolName: String,     // 工具名(如 write/bash)
    val callId: String = "",  // 工具调用 id
    val reason: String = "",  // 升级原因
)

/** 提问选项(官方 askUserQuestionItem.options[])。 */
data class QuestionOption(
    val label: String,
    val description: String = "",
)

/** 单个问题(官方 question/requested 帧 questions[] 元素)。 */
data class QuestionItem(
    val id: String,                // 问题 id(作答时原样带回)
    val question: String,          // 问题正文
    val header: String = "",       // 可选的标题行
    val detail: String = "",       // 可选的补充说明
    val options: List<QuestionOption> = emptyList(), // 单选/多选选项
    val multiSelect: Boolean = false,                // 是否多选
    val approveIntent: String = "", // intent.kind==plan-review 时的按钮文案(如“同意”)
)

/** 单个问题的作答(提交给后端 answer.answers[])。 */
data class QuestionAnswer(
    val id: String,                    // 对应 QuestionItem.id
    val selected: List<String> = emptyList(), // 选中的选项 label
    val custom: String = "",           // 自定义文本(单选且填了自定义时 selected 清空)
)

/** 提问请求(官方 question/requested 独立 mux 帧)。助手用提问工具等待用户输入时触发。 */
data class QuestionRequest(
    val sessionId: String,    // 发起提问的会话 id
    val muxRpcId: String,     // mux 帧 envelope 的 rpcId(应答/取消必须复用)
    val items: List<QuestionItem>, // 一批问题(逐题作答,最后一题答完统一提交)
)

/** 从用户消息生成会话标题(截断 12 字 + …)。 */
fun titleFrom(text: String): String =
    if (text.length <= 12) text else text.take(12) + "…"

/** 会话相对时间:分钟显示「X分钟」,小时「X小时前」,天「X天前」,7天前显示日期。 */
fun relativeSessionTime(updatedAt: Long): String {
    val min = (System.currentTimeMillis() - updatedAt) / 60_000L
    return when {
        min < 60 -> "${min}分钟"
        min < 60 * 24 -> "${min / 60}小时前"
        min < 60 * 24 * 7 -> "${min / (60 * 24)}天前"
        else -> java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA)
            .format(java.util.Date(updatedAt))
    }
}

/** 模拟助手回复(与真实流式结构一致;需后端接口替换数据源)。 */
fun demoAssistantReply(prompt: String): ChatMessage = ChatMessage(
    role = "assistant",
    blocks = listOf(
        MessageBlock(
            type = "context-injection",
            label = "上下文注入 · @deepseek-ai/dsh-system-prompt",
            extra = "取代先前的快照\n\n" +
                "sandbox:policy\n" +
                "Current DSH file policy: danger-full-access. The DSH file sandbox does not " +
                "restrict file modifications by available operations.\n\n" +
                "approval:policy\n" +
                "Approval prompts are disabled in this session: actions that require approval " +
                "are rejected automatically — do not request sandbox escalation (do not set " +
                "`sandbox_permissions`).\n\n" +
                "file-output-format\n" +
                "【文件输出格式要求】\n" +
                "当你输出任何文件路径(生成/修改/参考的文件)时,必须执行以下规则:\n" +
                "1，必须把文件复制到手机系统的共享下载目录/sdcard/Download/下。\n" +
                "2，必须用「✅ 文件说明(路径)」的格式,例如:✅ 已就绪(Downloads/DSH客户端-v1.1.2.apk)。" +
                "多个文件时列表显示,路径用绝对路径或 Downloads/ 开头。\n" +
                "3，路径必须用单个反引号的行内代码容器包裹,要求:Downloads/xxx.apk这个文件路径" +
                "显示在灰色背景的行内代码容器中。",
        ),
        MessageBlock(
            type = "reasoning",
            label = "💭 Think · Let me understand the task:",
            extra = "用户要求开发会话层的对话选项卡页面,需要查看参考项目的消息渲染实现与协议格式," +
                "并在开发前提出 20 个问题。我需要先了解 dsh 协议的事件流块结构," +
                "然后设计消息气泡的渲染方案。\n\n" +
                "关键点:\n" +
                "1. 用户消息:右侧气泡+时间戳+复制\n" +
                "2. 助手消息:上下文注入/推理/正文/工具调用块\n" +
                "3. 块模型对齐协议 content[] 类型",
        ),
        MessageBlock(
            type = "text",
            label = "我先快速了解一下现有参考项目 dsh-android 的架构和构建方式，以便提出有针对性的问题。",
        ),
        MessageBlock(
            type = "tool-call",
            label = "Bash ·  ",
            toolKind = "bash",
            extra = "List workspace and dsh-android directories",
            result = "ls -la ... → instance/ projects/ storage/",
        ),
        MessageBlock(
            type = "tool-call",
            label = "Read ·  ",
            toolKind = "read",
            extra = ".devcrew/runs/devcrew-mthm5cmc-0/REPORT.md",
            result = "交付报告: 44 个 Java 源文件,协议全链路 25/25 PASS",
        ),
        MessageBlock(
            type = "tool-call",
            label = "Think ·  ",
            toolKind = "think",
            extra = "Now I understand the existing project well",
            result = "",
        ),
    ),
)

// ==================== 错误码 → 中文描述映射 ====================

/**
 * 输入消息发送失败错误 → 中文描述(引导层/会话层横幅)。
 * @param code 后端错误码(如 attachment-error / agent-busy / model-unavailable)
 * @param message 后端原始错误消息(兜底)
 * @param reason 附件错误详情(如 MODEL_DOES_NOT_SUPPORT_IMAGES)
 */
fun promptErrorText(code: String?, message: String?, reason: String?): String {
    if (code == "attachment-error") {
        return when (reason) {
            "MODEL_DOES_NOT_SUPPORT_IMAGES", "SUBAGENT_IMAGE_UNSUPPORTED" -> "当前模型不支持图片输入"
            "UNSUPPORTED_IMAGE_TYPE", "IMAGE_TYPE_MISMATCH" -> "图片格式不支持"
            "TOO_MANY_IMAGES" -> "图片数量超过限制"
            "IMAGES_TOO_LARGE", "IMAGE_TOO_LARGE" -> "图片过大"
            "IMAGE_TOO_MANY_PIXELS", "IMAGE_DIMENSION_TOO_LARGE" -> "图片尺寸过大"
            "INVALID_IMAGE_BASE64", "INVALID_IMAGE" -> "图片数据无效"
            else -> "附件错误${reason?.let { "（$it）" } ?: ""}"
        }
    }
    return when (code) {
        "agent-busy" -> "代理忙，请稍后重试"
        "session-not-found" -> "会话不存在"
        "invalid-time-zone" -> "时区无效"
        "model-unavailable" -> "模型不可用"
        "steer-unavailable" -> "无法插话（当前回合已结束）"
        "queue-item-not-found" -> "队列项不存在"
        null -> message?.let { "发送失败：$it" } ?: "发送失败"
        else -> message?.let { "$it（$code）" } ?: "发送失败（$code）"
    }
}

/**
 * 助手流式输出中断错误 → 中文描述(turn/end reason.error)。
 * @param code LlmFailure.code(如 QUOTA / SERVER / TRANSPORT / AUTH)
 * @param message LlmFailure.message(兜底)
 */
fun turnErrorText(code: String?, message: String?): String {
    return when (code) {
        "AUTH" -> "API Key 无效，请检查配置"
        "QUOTA" -> "余额不足，请充值后重试"
        "RATE_LIMIT" -> "请求过于频繁（限流），请稍后重试"
        "CONTEXT_WINDOW_EXCEEDED" -> "上下文超出限制"
        "SERVER" -> "服务器内部错误"
        "TRANSPORT" -> "网络连接错误"
        "ABORTED" -> "请求已中止"
        "TIMEOUT", "LLM_STREAM_IDLE_TIMEOUT" -> "响应超时"
        "EMPTY_RESPONSE" -> "响应为空"
        "INVALID_REQUEST" -> "请求无效"
        null -> "输出中断"
        else -> if (code.startsWith("HTTP_")) "HTTP 错误（${code.removePrefix("HTTP_")}）"
            else message?.let { "$it" } ?: "输出中断（$code）"
    }
}
