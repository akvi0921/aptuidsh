package com.aptuidsh.kui

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import com.aptuidsh.kui.ui.theme.LocalDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.aptuidsh.kui.AppRuntime
import com.aptuidsh.kui.net.BalanceClient
import com.aptuidsh.kui.net.DshClient
import com.aptuidsh.kui.net.DshGateway
import com.aptuidsh.kui.net.EventStream
import com.aptuidsh.kui.net.ModelCatalogSignals
import com.aptuidsh.kui.ui.ChatMessage
import com.aptuidsh.kui.ui.ComposerArea
import com.aptuidsh.kui.ui.DraftAttachment
import com.aptuidsh.kui.ui.MainContent
import com.aptuidsh.kui.ui.InterjectFloatingBall
import com.aptuidsh.kui.ui.MessageBlock
import com.aptuidsh.kui.ui.ApiKeyDialog
import com.aptuidsh.kui.ui.ConsumptionStatsDialog
import com.aptuidsh.kui.ui.ModelEntry
import com.aptuidsh.kui.ui.SessionGroup
import com.aptuidsh.kui.ui.SessionGroupBy
import com.aptuidsh.kui.ui.SessionState
import com.aptuidsh.kui.ui.SessionStats
import com.aptuidsh.kui.ui.TokenUsage
import com.aptuidsh.kui.ui.SessionLog
import com.aptuidsh.kui.ui.QueueItem
import com.aptuidsh.kui.ui.SlashCommandEntry
import com.aptuidsh.kui.ui.UNGROUPED_KEY
import com.aptuidsh.kui.ui.WorkspaceEntry
import com.aptuidsh.kui.ui.AgentPresetEntry
import com.aptuidsh.kui.ui.buildContext
import com.aptuidsh.kui.ui.buildStatsText
import com.aptuidsh.kui.ui.deriveFlatSessions
import com.aptuidsh.kui.ui.deriveWorkspaceGroups
import com.aptuidsh.kui.ui.parseCommandList
import com.aptuidsh.kui.ui.parseCurrentModel
import com.aptuidsh.kui.ui.parseModels
import com.aptuidsh.kui.ui.parsePermissionOptions
import com.aptuidsh.kui.ui.parsePresetNameMap
import com.aptuidsh.kui.ui.parsePresetList
import com.aptuidsh.kui.ui.parseSessionHistory
import com.aptuidsh.kui.ui.parseSessionList
import com.aptuidsh.kui.ui.promptErrorText
import com.aptuidsh.kui.ui.turnErrorText
import com.aptuidsh.kui.ui.ApprovalRequest
import com.aptuidsh.kui.ui.QuestionAnswer
import com.aptuidsh.kui.ui.QuestionItem
import com.aptuidsh.kui.ui.QuestionOption
import com.aptuidsh.kui.ui.QuestionRequest
import com.aptuidsh.kui.ui.extractTodosFromMessages
import com.aptuidsh.kui.ui.parseWorkspaceList
import com.aptuidsh.kui.ui.permValueToDisplay
import com.aptuidsh.kui.ui.runningSubagentCounts
import com.aptuidsh.kui.ui.SettingsDialog
import com.aptuidsh.kui.ui.Sidebar
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.width
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.sp
import com.aptuidsh.kui.ui.WorkspacePickerDialog
import com.aptuidsh.kui.ui.MaterialDialog
import com.aptuidsh.kui.ui.ProviderEntry
import com.aptuidsh.kui.ui.CredentialStatus
import com.aptuidsh.kui.ui.CandidateModel
import com.aptuidsh.kui.ui.ConfiguredModel
import com.aptuidsh.kui.ui.demoAssistantReply
import com.aptuidsh.kui.ui.theme.APTUIDSHTheme
import com.aptuidsh.kui.ui.sidebarTargetWidth
import com.aptuidsh.kui.ui.titleFrom
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.json.JSONArray
import org.json.JSONObject

/** 主内容区背景:浅色 #ECF2F0(需求指定)/ 深色适配。 */
private val MainBgLight = Color(0xFFECF2F0)
private val MainBgDark = Color(0xFF101218)

/**
 * 对话主界面。
 *
 * <p>多会话窗口(浏览器标签页语义):每个会话独立持有全部 UI 状态
 * (消息/引导层标志/编辑器未发送文本/模型/推理/权限/悬浮卡片收纳),
 * 切换会话=保存当前+加载目标,状态互不污染。
 *
 * <p>预置 3 个演示会话:会话1(展开+空白)、会话2(收纳+未发送文本)、会话3(引导层+未发送文本)。
 * 层级(zIndex):内容层(1) < 输入卡片/悬浮层(2) < 竖屏展开遮罩(2.5) < 侧边栏(3)。
 */
class ChatActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChatScreen()
        }
    }
}

@Composable
fun ChatScreen() {
    // 设置状态(外观/语言/默认权限/预设;主题由 themeMode 驱动,真正切换暗色)
    var settingsVisible by remember { mutableStateOf(false) }
    var settingsSection by remember { mutableStateOf(0) }
    var settingsAgentPreset by remember { mutableStateOf("android-dev") }
    var settingsPermission by remember { mutableStateOf("Full access") }
    var settingsLanguage by remember { mutableStateOf("中文") }
    var themeMode by remember { mutableStateOf("system") }
    // 插话设置(本地化存储)
    var interjectEnabled by remember { mutableStateOf(false) }
    var interjectMode by remember { mutableStateOf("queue") }
    var interjectAutoClear by remember { mutableStateOf(true) }


    APTUIDSHTheme(
        darkTheme = when (themeMode) {
            "light" -> false
            "dark" -> true
            else -> isSystemInDarkTheme() // 跟随系统
        },
    ) {
        ChatScreenContent(
            settingsVisible = settingsVisible,
            onSettingsVisibleChange = { settingsVisible = it },
            settingsSection = settingsSection,
            onSettingsSectionChange = { settingsSection = it },
            settingsAgentPreset = settingsAgentPreset,
            onSettingsAgentPresetChange = { settingsAgentPreset = it },
            settingsPermission = settingsPermission,
            onSettingsPermissionChange = { settingsPermission = it },
            interjectEnabled = interjectEnabled,
            onInterjectEnabledChange = { interjectEnabled = it },
            interjectMode = interjectMode,
            onInterjectModeChange = { interjectMode = it },
            interjectAutoClear = interjectAutoClear,
            onInterjectAutoClearChange = { interjectAutoClear = it },
            settingsLanguage = settingsLanguage,
            onSettingsLanguageChange = { settingsLanguage = it },
            themeMode = themeMode,
            onThemeModeChange = { themeMode = it },
        )
    }
}

@Composable
private fun ChatScreenContent(
    settingsVisible: Boolean,
    onSettingsVisibleChange: (Boolean) -> Unit,
    settingsSection: Int,
    onSettingsSectionChange: (Int) -> Unit,
    settingsAgentPreset: String,
    onSettingsAgentPresetChange: (String) -> Unit,
    settingsPermission: String,
    onSettingsPermissionChange: (String) -> Unit,
    interjectEnabled: Boolean,
    onInterjectEnabledChange: (Boolean) -> Unit,
    interjectMode: String,
    onInterjectModeChange: (String) -> Unit,
    interjectAutoClear: Boolean,
    onInterjectAutoClearChange: (Boolean) -> Unit,
    settingsLanguage: String,
    onSettingsLanguageChange: (String) -> Unit,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var workspacePickerVisible by remember { mutableStateOf(false) }
    var materialDialogVisible by remember { mutableStateOf(false) }
    // 消费统计弹窗(侧边栏 11 图标):余额 + 会话真实 token/耗时
    var usageDialogVisible by remember { mutableStateOf(false) }
    var balanceResult by remember { mutableStateOf<BalanceClient.Result?>(null) }
    var balanceLoading by remember { mutableStateOf(false) }
    // 弹窗数据(投影快照)拉取中标志
    var usageLoading by remember { mutableStateOf(false) }
    // API Key 输入弹窗(点击消费统计弹窗里的「提供方」名称唤起)
    var apiKeyDialogVisible by remember { mutableStateOf(false) }
    var backendKeyConfigured by remember { mutableStateOf(false) }
    // 会话集合:初始为空,后端 session.list 加载后填充真实会话;无会话时自动创建
    var sessions by remember { mutableStateOf(mapOf<String, SessionState>()) }
    var currentId by remember { mutableStateOf("") }
    var presetNames by remember { mutableStateOf(mapOf<String, String>()) } // agentPreset id→name
    var presetList by remember { mutableStateOf(listOf<AgentPresetEntry>()) } // agentPreset.list 完整目录
    var selectedWorkspaceId by remember { mutableStateOf("") }  // 引导层选中的工作区
    var selectedPresetId by remember { mutableStateOf("") }     // 引导层选中的预设(staging)
    var userManuallySelectedPreset by remember { mutableStateOf(false) } // 用户在引导层手动选过预设
    // ---- 搜索状态(对齐官方 deriveSearchResults) ----
    var searchText by remember { mutableStateOf("") }
    // ---- 会话列表分组状态(官方 workspace 树:groupBy 两档 + 组展开集合) ----
    var groupBy by remember { mutableStateOf(SessionGroupBy.WORKSPACE) }
    var workspaces by remember { mutableStateOf(listOf<WorkspaceEntry>()) } // workspace.list
    var expandedGroupKeys by remember { mutableStateOf(setOf<String>()) }   // 已展开的组 key
    val configuration = LocalConfiguration.current
    val screenW = configuration.screenWidthDp.dp
    val screenH = configuration.screenHeightDp.dp
    val portrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    
    // 插话设置状态（从 SharedPreferences 加载）
    var localInterjectEnabled by remember { mutableStateOf(false) }
    var localInterjectMode by remember { mutableStateOf("queue") }
    var localInterjectAutoClear by remember { mutableStateOf(true) }
    
    // 加载本地化存储的插话设置
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("aptuidsh_settings", Context.MODE_PRIVATE)
        localInterjectEnabled = prefs.getBoolean("interject_enabled", false)
        localInterjectMode = prefs.getString("interject_mode", "queue") ?: "queue"
        localInterjectAutoClear = prefs.getBoolean("interject_auto_clear", true)
        // 同步到父组件
        onInterjectEnabledChange(localInterjectEnabled)
        onInterjectModeChange(localInterjectMode)
        onInterjectAutoClearChange(localInterjectAutoClear)
    }
    
    // 监听父组件设置变化，同步到本地状态
    LaunchedEffect(interjectEnabled, interjectMode, interjectAutoClear) {
        localInterjectEnabled = interjectEnabled
        localInterjectMode = interjectMode
        localInterjectAutoClear = interjectAutoClear
    }

    // 异步 RPC 封装:回调转挂起。
    // 关键:DshClient 回调跑在后台线程,这里统一通过 scope(主线程)恢复续体,
    // 使所有 await 之后的代码(含 sessions 状态的读改写)都在主线程串行执行,
    // 避免与 liveEvents/stateFrames 等主线程写入者发生跨线程覆盖(草稿残留/队列残影)。
    suspend fun suspendRpc(gateway: DshGateway, method: String, payload: JSONObject): JSONObject? =
        suspendCancellableCoroutine { cont ->
            gateway.client().rpc(
                method,
                payload,
                object : DshClient.DshCallback {
                    override fun onResult(value: JSONObject?) {
                        scope.launch { if (cont.isActive) cont.resume(value) }
                    }

                    override fun onError(code: Int, message: String?, details: JSONObject?) {
                        scope.launch { if (cont.isActive) cont.resume(null) }
                    }
                },
            )
        }

    // ---- 会话窗口(C 档:一次快照 + 纯增量折叠) ----
    // sessionSeq = 订阅基线(最近一次快照/修复后的尾部 seq;增量事件 seq>此才应用)
    var sessionSeq by remember { mutableStateOf(mapOf<String, Long>()) }
    // 每会话统一折叠器(历史回放与实时增量共用同一折叠核心)
    val sessionLogs = remember { HashMap<String, SessionLog>() }
    // 正在做 gap 修复的会话(期间实时事件入 liveBuffers 缓冲)
    var repairing by remember { mutableStateOf(setOf<String>()) }
    // 每会话工具结果缓存(兼容保留;折叠器内部已维护,不再作为实时折叠输入)
    var sessionToolResults by remember { mutableStateOf(mapOf<String, MutableMap<String, String>>()) }
    // 已打开过(加载过历史)的会话:仅这些接收 mux 实时增量(未打开的会话不累计渲染)
    var openedSessions by remember { mutableStateOf(setOf<String>()) }
    // 每会话模型目录(session.models;Composer 模型/推理菜单数据源)
    var sessionModelCatalog by remember { mutableStateOf(mapOf<String, List<ModelEntry>>()) }
    // 目录重拉在途/失败状态(Composer 菜单内提示与重试;对齐官方 status.loading / error+Retry)
    var modelsLoading by remember { mutableStateOf(false) }
    var modelsError by remember { mutableStateOf("") }
    // 目录加载代次守卫(官方 ModelDirectory.generation 语义:最新一次操作获胜,
    // 旧响应永不覆盖新响应——防"打开菜单触发重拉 x 切换会话触发重拉"交错时
    // 旧目录写回当前会话)。
    var modelLoadGen by remember { mutableStateOf(0L) }
    // 全局默认模型目录(从第一个真实会话加载,供引导层本地会话使用)
    var defaultModels by remember { mutableStateOf(listOf<ModelEntry>()) }
    var defaultModelId by remember { mutableStateOf("") }
    var defaultEffortId by remember { mutableStateOf("") }
    // 命令目录(会话 agent 可用命令;打开会话时经 commands/list 拉取)
    var commandCatalog by remember { mutableStateOf(listOf<SlashCommandEntry>()) }

    // 实时事件串行通道与快照在途缓冲(loadSessionHistory 完成时重放防丢帧)
    val liveEvents = remember { Channel<Triple<String, JSONObject, String>>(Channel.UNLIMITED) }
    val liveBuffers = remember { HashMap<String, MutableList<Triple<String, JSONObject, String>>>() }
    // 投影帧通道(permissions/modelSelection 等;值可为任意 JSON)
    val liveProjections = remember { Channel<Triple<String, String, Any?>>(Channel.UNLIMITED) }
    // 交互/排队帧通道:审批/提问请求与解决、session/queue 等会直接改 sessions 状态的帧,
    // 一律经此在主线程串行应用,避免与 liveEvents 折叠器(主线程)发生跨线程读改写竞态
    // (否则实时提问帧写下的 pendingQuestion 会被消息折叠的旧快照覆盖丢失)。
    val stateFrames = remember { Channel<JSONObject>(Channel.UNLIMITED) }

    // 在主线程应用一帧会改 sessions 状态的 mux 帧(审批/提问 requested/resolved、session/queue)。
    fun applyStateEnvelope(envelope: JSONObject) {
        val payload = envelope.optJSONObject("payload") ?: return
        val type = payload.optString("type")
        val sid = payload.optString("sessionId", "")
        when (type) {
            "approval/requested" -> {
                val approval = ApprovalRequest(
                    sessionId = sid,
                    muxRpcId = envelope.optString("rpcId", ""),
                    id = payload.optString("approvalId", ""),
                    toolName = payload.optString("toolName", ""),
                    callId = payload.optString("callId", ""),
                    reason = payload.optString("reason", ""),
                )
                sessions = sessions.toMutableMap().apply {
                    put(sid, (this[sid] ?: SessionState(id = sid)).copy(
                        pendingApproval = approval,
                        updatedAt = System.currentTimeMillis(),
                    ))
                }
            }
            "approval/resolved" -> {
                sessions = sessions.toMutableMap().apply {
                    put(sid, (this[sid] ?: SessionState(id = sid)).copy(
                        pendingApproval = null,
                        updatedAt = System.currentTimeMillis(),
                    ))
                }
            }
            "question/requested" -> {
                val qRpcId = envelope.optString("rpcId", "")
                val items = mutableListOf<QuestionItem>()
                val qs = payload.optJSONArray("questions")
                if (qs != null) {
                    for (i in 0 until qs.length()) {
                        val o = qs.optJSONObject(i) ?: continue
                        val options = mutableListOf<QuestionOption>()
                        o.optJSONArray("options")?.let { opts ->
                            for (j in 0 until opts.length()) {
                                val op = opts.optJSONObject(j) ?: continue
                                options.add(
                                    QuestionOption(
                                        label = op.optString("label", ""),
                                        description = op.optString("description", ""),
                                    )
                                )
                            }
                        }
                        val intent = o.optJSONObject("intent")
                        items.add(
                            QuestionItem(
                                id = o.optString("id", ""),
                                question = o.optString("question", ""),
                                header = o.optString("header", ""),
                                detail = o.optString("detail", ""),
                                options = options,
                                multiSelect = o.optBoolean("multiSelect", false),
                                approveIntent = if (intent?.optString("kind", "") == "plan-review")
                                    intent.optString("approve", "") else "",
                            )
                        )
                    }
                }
                if (items.isNotEmpty()) {
                    sessions = sessions.toMutableMap().apply {
                        put(sid, (this[sid] ?: SessionState(id = sid)).copy(
                            pendingQuestion = QuestionRequest(
                                sessionId = sid,
                                muxRpcId = qRpcId,
                                items = items,
                            ),
                            pendingApproval = null, // 两类请求不会同时出现
                            updatedAt = System.currentTimeMillis(),
                        ))
                    }
                }
            }
            "question/resolved" -> {
                val resolvedRpcId = payload.optString("questionRpcId", "")
                if (resolvedRpcId.isNotEmpty()) {
                    sessions = sessions.toMutableMap().apply {
                        val s = this[sid]
                        if (s != null && s.pendingQuestion?.muxRpcId == resolvedRpcId) {
                            put(sid, s.copy(pendingQuestion = null, updatedAt = System.currentTimeMillis()))
                        }
                    }
                }
            }
            "session/queue" -> {
                val items = payload.optJSONArray("items")
                if (items != null) {
                    val queueItems = mutableListOf<QueueItem>()
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        val placement = item.optString("placement", "")
                        val msg = item.optJSONObject("message") ?: continue
                        val content = msg.optJSONArray("content")
                        var text = ""
                        if (content != null) {
                            for (j in 0 until content.length()) {
                                val block = content.optJSONObject(j) ?: continue
                                if (block.optString("type") == "text") {
                                    text = block.optString("text", "")
                                    break
                                }
                            }
                        }
                        queueItems.add(
                            QueueItem(
                                id = msg.optString("id", ""),
                                placement = placement,
                                text = text,
                                source = msg.optJSONObject("source")?.optString("kind", "") ?: "",
                            )
                        )
                    }
                    sessions = sessions.toMutableMap().apply {
                        put(sid, (this[sid] ?: SessionState(id = sid)).copy(
                            queueItems = queueItems,
                            updatedAt = System.currentTimeMillis(),
                        ))
                    }
                }
            }
        }
    }


    /**
     * 重拉某会话的模型目录(对齐官方 ModelDirectory.load 语义)。
     *
     * 官方实现(packages/client/ui-model-selection/lib/types/client/directory.js)的三条不变量,
     * 本函数逐条对齐:
     *  1. 代次守卫:调用即 ++generation,"最新一次操作获胜",过期响应直接丢弃
     *     —— 防打开菜单与切换会话交错时旧目录覆盖新目录;
     *  2. 失败保留上一次成功值:只有成功才写 store,失败只置 error
     *     —— 网络抖动不得把用户已看到的模型列表清空;
     *  3. status 生命周期:loading → ready / error,供 UI 渲染状态行与重试。
     *
     * @param id 会话 id;为空或网关不可用时不动作。
     * @param showLoading 是否点亮菜单内「正在刷新模型列表…」(静默后台刷新传 false)。
     */
    fun reloadModelCatalog(id: String, showLoading: Boolean = true) {
        if (id.isEmpty()) return
        val gateway = AppRuntime.gateway() ?: return
        val gen = ++modelLoadGen
        if (showLoading) modelsLoading = true
        modelsError = ""
        scope.launch {
            val value = suspendRpc(gateway, "session.models", JSONObject().put("sessionId", id))
            if (gen != modelLoadGen) return@launch // 有过更新的请求在途:本次结果作废
            if (value == null) {
                modelsError = "模型目录加载失败"
                if (showLoading) modelsLoading = false
                return@launch
            }
            val models = parseModels(value)
            // 成功:更新目录(即使为空也如实反映后端状态,不回退占位列表)
            sessionModelCatalog = sessionModelCatalog.toMutableMap().apply { put(id, models) }
            // 会话层的当前选择以服务端 current 为准(官方 load() 同时刷新 current):
            // 若他处(Web UI/其他客户端)改了本会话模型,这里能立刻对齐显示。
            val (p, m, e) = parseCurrentModel(value)
            if (m.isNotEmpty()) {
                val modelName = models.firstOrNull { it.modelId == m }?.modelName ?: m
                val effortName = models.firstOrNull { it.modelId == m }
                    ?.efforts?.firstOrNull { it.first == e }?.second ?: e
                sessions = sessions.toMutableMap().apply {
                    val s = this[id]
                    if (s != null) {
                        put(id, s.copy(
                            provider = p,
                            modelId = m,
                            model = modelName,
                            reasoningEffort = e,
                            reasoning = effortName,
                        ))
                    }
                }
            }
            // 引导层默认目录同步(首次拿到或后端已变更时刷新)
            if (models.isNotEmpty()) {
                defaultModels = models
                if (m.isNotEmpty()) {
                    defaultModelId = m
                    defaultEffortId = e
                }
            }
            if (showLoading) modelsLoading = false
        }
    }

    /** 统一落盘余额结果,并在缺少密钥时主动弹输入框。 */
    fun applyBalanceResult(r: BalanceClient.Result, providerId: String) {
        balanceResult = r
        balanceLoading = false
        if (r.needsKey && !apiKeyDialogVisible && BalanceClient.isBalanceCapable(providerId)) {
            apiKeyDialogVisible = true
        }
    }

    /**
     * 拉取当前提供方的余额(每次打开消费统计弹窗都调用)。
     *
     * <p>三步链路:问后端凭据状态 → 用本地 key 查余额 → 都无则标记 needsKey
     * (由 UI 主动弹密钥输入框)。
     *
     * <p>定义在组合顶层(而非弹窗的 if 块内),避免局部函数捕获成分快照,
     * 也便于 LaunchedEffect 在条件之外安全调用。
     *
     * @param id 当前会话 id(用于取提供方)。
     */
    fun refreshBalanceSnapshot(id: String) {
        val providerId = sessions[id]?.provider ?: ""
        balanceLoading = true
        balanceResult = null
        val gw = AppRuntime.gateway()
        if (gw == null) {
            balanceResult = BalanceClient.Result.fail("后端未连接")
            balanceLoading = false
            return
        }
        val localKey = BalanceClient.loadLocalKey(context)
        // 步骤 1:查后端凭据状态(HTTP RPC,不依赖文件权限)
        BalanceClient.describeBackend(gw, object : DshClient.DshCallback {
            override fun onResult(value: JSONObject?) {
                val configured = BalanceClient.parseConfigured(value)
                scope.launch { backendKeyConfigured = configured }
                // 步骤 2:用本地 key 查余额
                BalanceClient.fetch(providerId, localKey, configured) { r ->
                    scope.launch { applyBalanceResult(r, providerId) }
                }
            }

            override fun onError(code: Int, message: String?, details: JSONObject?) {
                // 后端不可用:退化为纯本地 key 路径
                BalanceClient.fetch(providerId, localKey, false) { r ->
                    scope.launch { applyBalanceResult(r, providerId) }
                }
            }
        })
    }

    /**
     * 拉取消费统计弹窗的全部真实数据(每次打开弹窗都调用,不吃缓存)。
     *
     * <p><b>为什么要单独做这件事(缺陷复盘)</b>:弹窗此前直接读 {@code sessions[id]} 里缓存的
     * {@code tokenUsage/sessionStats} 字段,而这些字段只在两处写入:
     * (1) {@code loadSessionHistory}——打开会话时**仅一次**;
     * (2) 实时投影帧 {@code sessionStats}/{@code tokenUsage} 到达时。
     * 于是出现"底部统计行有数据、弹窗里全空"的割裂:统计行读的是历史快照里就算好的
     * 字符串({@code statsText}),而弹窗要的是结构化字段,后者在会话打开后若没有新的
     * 投影帧到达就一直是初始值 0。
     *
     * <p>修复策略(与需求一致:每次打开弹窗内部所有数据都真实读取一次):
     * 打开即现场 {@code session.history} 取一次 projections,并用返回的
     * {@code tokenUsage / sessionStats / contextPressure / permissions} 覆写会话状态,
     * 使弹窗与底部统计行同源同刻。
     *
     * @param id 会话 id;为空或网关不可用时不动作。
     */
    fun refreshUsageSnapshot(id: String) {
        if (id.isEmpty()) return
        val gateway = AppRuntime.gateway() ?: return
        scope.launch {
            usageLoading = true
            val v = suspendRpc(
                gateway, "session.history",
                JSONObject().put("sessionId", id).put("maxMessages", 1),
            )
            val values = v?.optJSONObject("projections")?.optJSONObject("values")
            if (values != null) {
                val u = TokenUsage.parse(values.optJSONObject("tokenUsage"))
                val st = SessionStats.parse(values.optJSONObject("sessionStats"))
                val (ctxPct, ctxText) = buildContext(values.optJSONObject("contextPressure"))
                val (permValue, permOpts) = parsePermissionOptions(values.optJSONObject("permissions"))
                sessions = sessions.toMutableMap().apply {
                    val s = this[id] ?: return@apply
                    // 首轮未命中缓存 = 系统提示词近似消耗(仅首次落库,不被后续轮次覆盖)
                    val firstIn = if (s.firstInputTokens > 0L) s.firstInputTokens
                        else if (u.uncachedInputTokens > 0L) u.uncachedInputTokens else -1L
                    put(
                        id,
                        s.copy(
                            tokenUsage = u,
                            sessionStats = st,
                            firstInputTokens = firstIn,
                            statsText = buildStatsText(
                                values.optJSONObject("sessionStats"),
                                values.optJSONObject("tokenUsage"),
                            ).ifEmpty { s.statsText },
                            contextPercent = if (ctxPct >= 0f) ctxPct else s.contextPercent,
                            contextText = ctxText.ifEmpty { s.contextText },
                            permissionValue = permValue.ifEmpty { s.permissionValue },
                            permissionOptions = if (permOpts.isNotEmpty()) permOpts else s.permissionOptions,
                            permission = permValueToDisplay(permValue, permOpts) ?: s.permission,
                        ),
                    )
                }
            }
            usageLoading = false
        }
    }

    // 加载指定会话的历史消息(真实会话流;首次加载用默认 50 条)
    fun loadSessionHistory(id: String) {
        val gateway = AppRuntime.gateway() ?: return
        scope.launch {
            openedSessions = openedSessions + id
            sessions = sessions.toMutableMap().apply {
                put(id, (this[id] ?: SessionState(id = id)).copy(loading = true))
            }
            val payload = JSONObject().put("sessionId", id)
            val v = suspendRpc(gateway, "session.history", payload)
            // C 档:历史仅作为"窗口快照"——经统一折叠器回放,实时路径共用同一折叠核心
            val envelopes = v?.optJSONArray("events")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            } ?: emptyList()
            val log = SessionLog.fromHistory(envelopes)
            sessionLogs[id] = log
            val msgs = log.snapshot()
            // 记录历史尾部 seq(实时增量以此为起点)
            var tail = 0L
            var minSeq = Long.MAX_VALUE
            v?.optJSONArray("events")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i)?.optJSONObject("event")?.optLong("seq", 0L) ?: 0L
                    if (s > tail) tail = s
                    if (s > 0L && s < minSeq) minSeq = s
                }
            }
            sessionSeq = sessionSeq.toMutableMap().apply { put(id, tail) }
            sessionToolResults = sessionToolResults.toMutableMap().apply {
                if (!containsKey(id)) put(id, HashMap())
            }
            val hasMore = v?.optBoolean("hasMore", false) ?: false
            val presetName = (sessions[id]?.agentPresetId ?: "").let { pid ->
                if (pid.isNotEmpty()) presetNames[pid] ?: pid else ""
            }
            // 会话实时配置/统计:来自 history 尾部 projections(sessionStats/contextPressure/permissions)
            val projValues = v?.optJSONObject("projections")?.optJSONObject("values")
            val stats = projValues?.optJSONObject("sessionStats")
            val ctxPressure = projValues?.optJSONObject("contextPressure")
            val permissions = projValues?.optJSONObject("permissions")
            val (permValue, permOpts) = parsePermissionOptions(permissions)
            val (ctxPct, ctxText) = buildContext(ctxPressure)
            val statsLine = buildStatsText(stats, projValues?.optJSONObject("tokenUsage"))
            // 消费统计弹窗的原始投影(逐字段保留,不做字符串化)
            val usageParsed = TokenUsage.parse(projValues?.optJSONObject("tokenUsage"))
            val statsParsed = SessionStats.parse(stats)
            // 首轮未命中缓存的输入 token = 系统提示词近似消耗(仅首次有数据时落库,后续轮次不覆盖)
            val prevFirst = sessions[id]?.firstInputTokens ?: -1L
            val firstInput = if (prevFirst > 0L) prevFirst
                else if (usageParsed.uncachedInputTokens > 0L) usageParsed.uncachedInputTokens else -1L
            // 并行拉取模型目录 + 当前选择(session.models)
            var mProvider = ""; var mModel = ""; var mEffort = ""; var models = emptyList<ModelEntry>()
            val modelsValue = suspendRpc(gateway, "session.models", JSONObject().put("sessionId", id))
            if (modelsValue != null) {
                models = parseModels(modelsValue)
                val cur = parseCurrentModel(modelsValue)
                mProvider = cur.first; mModel = cur.second; mEffort = cur.third
            }
            val modelName = models.firstOrNull { it.modelId == mModel }?.modelName ?: mModel
            val effortName = models.firstOrNull { it.modelId == mModel }
                ?.efforts?.firstOrNull { it.first == mEffort }?.second ?: mEffort
            // 命令目录(commands.list,agent 作用域 args 信封)
            val cmdsValue = suspendRpc(gateway, "commands/list", JSONObject().put("args", JSONObject().put("agentId", id)))
            if (cmdsValue != null) {
                commandCatalog = parseCommandList(cmdsValue)
            }
            sessions = sessions.toMutableMap().apply {
                put(
                    id,
                    (this[id] ?: SessionState(id = id)).copy(
                        messages = msgs,
                        loading = false,
                        showWelcome = msgs.isEmpty(),
                        agentPresetName = presetName,
                        updatedAt = System.currentTimeMillis(),
                        modelId = mModel,
                        provider = mProvider,
                        reasoningEffort = mEffort,
                        model = if (modelName.isEmpty()) this[id]?.model ?: "DeepSeek-V4-Flash" else modelName,
                        reasoning = if (effortName.isEmpty()) this[id]?.reasoning ?: "High" else effortName,
                        permissionValue = permValue,
                        permissionOptions = permOpts,
                        permission = permValueToDisplay(permValue, permOpts)
                            ?: this[id]?.permission ?: "Full access",
                        statsText = statsLine,
                        tokenUsage = usageParsed,
                        sessionStats = statsParsed,
                        firstInputTokens = firstInput,
                        contextPercent = ctxPct,
                        contextText = ctxText,
                        running = this[id]?.running ?: false,
                        hasMoreHistory = hasMore,
                        oldestSeq = if (minSeq < Long.MAX_VALUE) minSeq else 0L,
                        // 仅当会话不在运行(回合已完成)时标记 turnCompleted=true;
                        // 流式中途因掉帧/重连触发的快照重拉不得误标已结束(否则工具组会早显)
                        turnCompleted = msgs.isNotEmpty() && !(this[id]?.running ?: false),
                    ),
                )
            }
            sessionModelCatalog = sessionModelCatalog.toMutableMap().apply { put(id, models) }
            // 保存全局默认模型目录(供引导层本地会话使用)
            if (models.isNotEmpty() && defaultModels.isEmpty()) {
                defaultModels = models
                defaultModelId = mModel
                defaultEffortId = mEffort
            }
            // 快照在途时到达的实时事件挂起在此,快照完成后按序重放
            val buffered = liveBuffers.remove(id)
            if (buffered != null) {
                for (ev in buffered) liveEvents.trySend(ev)
            }
        }
    }

    // 加载更多历史消息(beforeSeq 分页;在列表顶部截断位置触发)
    fun loadMoreHistory(id: String) {
        val gateway = AppRuntime.gateway() ?: return
        val s = sessions[id] ?: return
        if (!s.hasMoreHistory || s.loading) return
        scope.launch {
            sessions = sessions.toMutableMap().apply {
                put(id, (this[id] ?: s).copy(loading = true))
            }
            val payload = JSONObject()
                .put("sessionId", id)
                .put("beforeSeq", s.oldestSeq)
                .put("maxMessages", 50)
            val v = suspendRpc(gateway, "session.history", payload)
            val envelopes = v?.optJSONArray("events")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            } ?: emptyList()
            var minSeq = Long.MAX_VALUE
            v?.optJSONArray("events")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val seq = arr.optJSONObject(i)?.optJSONObject("event")?.optLong("seq", 0L) ?: 0L
                    if (seq > 0L && seq < minSeq) minSeq = seq
                }
            }
            val hasMore = v?.optBoolean("hasMore", false) ?: false
            // C 档:前插更早事件到统一折叠器,整窗重折叠后取快照
            val log = sessionLogs[id]
            if (log != null) {
                log.prepend(envelopes)
            } else {
                sessionLogs[id] = SessionLog.fromHistory(envelopes)
            }
            val merged = sessionLogs[id]?.snapshot() ?: (parseSessionHistory(v) + s.messages)
            sessions = sessions.toMutableMap().apply {
                put(id, (this[id] ?: s).copy(
                    messages = merged,
                    loading = false,
                    hasMoreHistory = hasMore,
                    oldestSeq = if (minSeq < Long.MAX_VALUE) minSeq else s.oldestSeq,
                ))
            }
        }
    }

    // host 通道「模型目录已失效」信号消费(对齐官方 ctx.remote.$on(...) 的两条刷新触发点)。
    // 官方在客户端根上下文订阅 llm/adapters-updated 与 settings/document-updated,任一到达
    // 即对该会话目录调用 load()。这里以同样的方式重建本页目录:信号版本号变化 → 重拉当前会话,
    // 并清掉其他会话的缓存目录(切过去时会各自重拉),保证不会残留旧代模型列表。
    // 后端的这几条事件只是"提示可能有变",重拉仍然是权威来源,因此按版本号幂等消费。
    LaunchedEffect(Unit) {
        var consumed = ModelCatalogSignals.revision()
        while (true) {
            delay(300)
            val now = ModelCatalogSignals.revision()
            if (now != consumed) {
                consumed = now
                sessionModelCatalog = sessionModelCatalog.toMutableMap().apply { clear() }
                if (currentId.isNotEmpty()) reloadModelCatalog(currentId, showLoading = false)
            }
        }
    }

    // 实时事件回调(EventStream socket 线程 → 串行 Channel → 主线程按序应用,
    // 保证 chunk text-delta 等增量严格按帧序折叠,不乱序)
    DisposableEffect(Unit) {
        val gateway = AppRuntime.gateway()
        val listener = object : EventStream.DshEventListener {
            override fun onHostEvent(envelope: JSONObject) {}
            override fun onMuxEvent(envelope: JSONObject) {
                val payload = envelope.optJSONObject("payload") ?: return
                val type = payload.optString("type")
                val sid = payload.optString("sessionId", "")
                if (type == "session/projection") {
                    // 投影帧(permissions/modelSelection 等):同步会话实时状态
                    val key = payload.optString("key", "")
                    val value = payload.opt("value")
                    liveProjections.trySend(Triple(sid, key, value))
                    return
                }
                // 审批/提问请求与解决、排队消息等会改 sessions 状态的帧:统一投递到
                // stateFrames 通道,由主线程按序应用(避免与 liveEvents 折叠器跨线程竞态)。
                if (type == "approval/requested" || type == "approval/resolved"
                    || type == "question/requested" || type == "question/resolved"
                    || type == "session/queue"
                ) {
                    stateFrames.trySend(envelope)
                    return
                }
                if (type != "session/event") return
                val event = payload.optJSONObject("event") ?: return
                liveEvents.trySend(Triple(sid, event, envelope.optString("rpcId", "")))
            }

            override fun onTextDelta(sessionId: String, delta: String, meta: JSONObject?) {}
            override fun onStreamEnd(sessionId: String) {}
        }
        gateway?.setListener(listener)
        onDispose {
            gateway?.setListener(null)
        }
    }
    // 串行消费(严格帧序):官方 acceptLiveEvent 语义——
    // 打开在途/修复中 → 事件入 liveBuffers;seq<=基线 → 丢弃;seq 缺口 → 缓冲+repair;
    // 正常 → SessionLog.append 增量折叠。不再有"每轮结束自动重拉"。
    fun repairSessionGap(id: String) {
        if (id in repairing) return
        repairing = repairing + id
        val gateway = AppRuntime.gateway()
        scope.launch {
            try {
                val v = suspendRpc(
                    gateway, "session.history",
                    JSONObject().put("sessionId", id).put("maxMessages", 50),
                )
                val envelopes = v?.optJSONArray("events")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                } ?: emptyList()
                val log = SessionLog.fromHistory(envelopes)
                sessionLogs[id] = log
                var tail = 0L
                v?.optJSONArray("events")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val s = arr.optJSONObject(i)?.optJSONObject("event")?.optLong("seq", 0L) ?: 0L
                        if (s > tail) tail = s
                    }
                }
                sessionSeq = sessionSeq.toMutableMap().apply { put(id, tail) }
                sessions = sessions.toMutableMap().apply {
                    val s = this[id]
                    if (s != null) put(id, s.copy(messages = log.snapshot(), loading = false))
                }
            } finally {
                repairing = repairing - id
            }
            // 修复期间缓冲的事件按序重放(对齐官方 liveBuffer 拼接回放)
            val buffered = liveBuffers.remove(id)
            if (buffered != null) {
                for (ev in buffered) liveEvents.trySend(ev)
            }
        }
    }

    LaunchedEffect(Unit) {
        for ((sid, event, muxRpcId) in liveEvents) {
            // 仅已打开(加载过历史)的会话参与实时渲染
            if (!openedSessions.contains(sid)) continue
            val latest = sessions[sid] ?: continue
            if (latest.loading || sid in repairing) {
                // 快照/修复在途:暂存事件,完成后重放
                liveBuffers.getOrPut(sid) { mutableListOf() }.add(Triple(sid, event, muxRpcId))
                continue
            }
            val seq = event.optLong("seq", 0L)
            val prevSeq = sessionSeq[sid] ?: 0L
            if (seq <= prevSeq) continue // 重复/乱序帧丢弃
            // seq 缺口(越过订阅基线):缓冲该事件并触发一次 gap 修复(官方 repairGap 语义)
            if (prevSeq > 0L && seq > prevSeq + 1L) {
                liveBuffers.getOrPut(sid) { mutableListOf() }.add(Triple(sid, event, muxRpcId))
                repairSessionGap(sid)
                continue
            }
            sessionSeq = sessionSeq.toMutableMap().apply { put(sid, seq) }
            // 运行态翻转(幂等):turn/start → running;turn/end → idle
            val et = event.optString("type")
            if (et == "turn/start") {
                sessions = sessions.toMutableMap().apply { put(sid, latest.copy(running = true, turnCompleted = false)) }
            } else if (et == "turn/end") {
                sessions = sessions.toMutableMap().apply { put(sid, latest.copy(running = false, turnCompleted = true)) }
            }
            // 纯增量折叠(移除旧"turn/end 后 200ms 重拉收尾")
            val log = sessionLogs[sid] ?: continue
            log.append(event)
            val newMsgs = log.snapshot()
            sessions = sessions.toMutableMap().apply {
                val refreshed = this[sid] ?: latest
                put(
                    sid,
                    refreshed.copy(
                        messages = newMsgs,
                        showWelcome = false,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
            // 会话离开引导层时,重置手动选择标记(新会话将重新同步设置)
            if (sid == currentId) {
                userManuallySelectedPreset = false
            }
            // 审批事件:approval/decided → 清除审批弹窗(由 approval/requested 帧弹出的卡)
            if (et == "approval/decided") {
                sessions = sessions.toMutableMap().apply {
                    put(sid, (this[sid] ?: latest).copy(pendingApproval = null, updatedAt = System.currentTimeMillis()))
                }
            }
        }
    }

    // 投影帧消费:permissions 变更回写显示(官方 PermissionSelect 靠投影回显)
    LaunchedEffect(Unit) {
        for ((sid, key, value) in liveProjections) {
            val latest = sessions[sid] ?: continue
            if (!openedSessions.contains(sid)) continue
            when (key) {
                "permissions" -> {
                    val obj = value as? JSONObject ?: continue
                    val (permValue, permOpts) = parsePermissionOptions(obj)
                    if (permValue.isNotEmpty()) {
                        sessions = sessions.toMutableMap().apply {
                            put(
                                sid,
                                latest.copy(
                                    permissionValue = permValue,
                                    permissionOptions = permOpts,
                                    permission = permValueToDisplay(permValue, permOpts) ?: latest.permission,
                                ),
                            )
                        }
                    }
                }
                "sessionStats" -> {
                    val obj = value as? JSONObject ?: continue
                    // 修复:此前这里固定传 null,导致实时更新时 token 分项丢失
                    // (sessionStats 与 tokenUsage 是两条独立投影帧,各自到达时都要带上已有的另一半)
                    val parsed = SessionStats.parse(obj)
                    val line = buildStatsText(obj, null)
                    sessions = sessions.toMutableMap().apply {
                        put(
                            sid,
                            latest.copy(
                                statsText = line.ifEmpty { latest.statsText },
                                sessionStats = parsed,
                            ),
                        )
                    }
                }
                "tokenUsage" -> {
                    // token 用量投影(累计):驱动统计行与消费统计弹窗
                    val obj = value as? JSONObject ?: continue
                    val u = TokenUsage.parse(obj)
                    val line = buildStatsText(
                        JSONObject()
                            .put("turns", latest.sessionStats.turns)
                            .put("steps", latest.sessionStats.steps)
                            .put("llmMs", latest.sessionStats.llmMs)
                            .put("toolMs", latest.sessionStats.toolMs),
                        obj,
                    )
                    // 首轮未命中缓存 = 系统提示词近似消耗(仅首次落库)
                    val firstIn = if (latest.firstInputTokens > 0L) latest.firstInputTokens
                        else if (u.uncachedInputTokens > 0L) u.uncachedInputTokens else -1L
                    sessions = sessions.toMutableMap().apply {
                        put(
                            sid,
                            latest.copy(
                                tokenUsage = u,
                                statsText = line.ifEmpty { latest.statsText },
                                firstInputTokens = firstIn,
                            ),
                        )
                    }
                }
                "contextPressure" -> {
                    val obj = value as? JSONObject ?: continue
                    val (pct, txt) = buildContext(obj)
                    if (pct >= 0f) {
                        sessions = sessions.toMutableMap().apply {
                            put(sid, latest.copy(contextPercent = pct, contextText = txt))
                        }
                    }
                }
                "modelSelection" -> {
                    // 会话模型选择的权威投影帧(官方 load() 之外的第二条实时来源):
                    // 任何路径改模型(Web UI/命令/其他客户端)都会推此帧,回写显示并
                    // 触发一次目录重拉,保证"当前值"与"可选列表"同步收敛。
                    val obj = value as? JSONObject ?: continue
                    val p = obj.optString("provider", "")
                    val m = obj.optString("model", "")
                    if (m.isEmpty()) continue
                    val e = obj.optString("reasoningEffort", "")
                    val cat = sessionModelCatalog[sid] ?: emptyList()
                    val modelName = cat.firstOrNull { it.modelId == m }?.modelName ?: m
                    val effortName = cat.firstOrNull { it.modelId == m }
                        ?.efforts?.firstOrNull { it.first == e }?.second ?: e
                    if (m != latest.modelId || e != latest.reasoningEffort) {
                        sessions = sessions.toMutableMap().apply {
                            put(sid, latest.copy(
                                provider = p,
                                modelId = m,
                                model = modelName,
                                reasoningEffort = e,
                                reasoning = effortName,
                            ))
                        }
                    }
                    if (sid == currentId) reloadModelCatalog(sid, showLoading = false)
                }
            }
        }
    }

    // 交互/排队帧消费(主线程串行):审批/提问 requested/resolved、session/queue 统一在此应用,
    // 与 liveEvents 折叠器、投影消费器同在主线程顺序执行,消除跨线程读改写竞态。
    LaunchedEffect(Unit) {
        for (env in stateFrames) {
            applyStateEnvelope(env)
        }
    }

    // ==================== 自动化程序:新开会话后自动应用设置的预设和权限 ====================
    // 对齐官方逻辑:agentPreset.select + 权限变更
    // 1. 从后端重新拉取最新设置配置(确保配置是最新的)
    // 2. 应用 Agent 预设到新会话(真实后端会话,直接调用 agentPreset.select)
    // 3. 应用权限到新会话(更新本地状态)
    fun applySettingsToNewSession(sessionId: String) {
        val gateway = AppRuntime.gateway() ?: return
        scope.launch {
            // 步骤1:从后端重新拉取最新设置配置
            val settingsValue = suspendRpc(gateway, "settings.describe", JSONObject())
            var latestPresetId = settingsAgentPreset
            var latestPermission = settingsPermission
            if (settingsValue != null) {
                val namespaces = settingsValue.optJSONArray("namespaces")
                if (namespaces != null) {
                    for (i in 0 until namespaces.length()) {
                        val ns = namespaces.optJSONObject(i) ?: continue
                        val nsName = ns.optString("ns")
                        val value = ns.optJSONObject("value")
                        when (nsName) {
                            "agent-presets" -> {
                                val defaultPreset = value?.optString("default", "") ?: ""
                                if (defaultPreset.isNotEmpty()) {
                                    latestPresetId = defaultPreset
                                    // 更新本地设置状态(始终同步)
                                    onSettingsAgentPresetChange(defaultPreset)
                                    // 只有用户未手动选过预设时,才同步引导层显示状态
                                    if (!userManuallySelectedPreset) {
                                        selectedPresetId = defaultPreset
                                    }
                                }
                            }
                            "permission" -> {
                                val defaultPerm = value?.optString("defaultPreset", "") ?: ""
                                if (defaultPerm.isNotEmpty()) {
                                    latestPermission = when (defaultPerm) {
                                        "read-only" -> "Read Only"
                                        "workspace-write" -> "Workspace Write"
                                        "danger-full-access" -> "Full access"
                                        else -> defaultPerm
                                    }
                                    // 更新本地状态
                                    onSettingsPermissionChange(latestPermission)
                                }
                            }
                        }
                    }
                }
            }

            // 步骤2:应用 Agent 预设(对齐官方 agentPreset.select)
            // 如果用户在引导层手动选过预设,则跳过(用户选择优先于设置默认值)
            if (latestPresetId.isNotEmpty() && !userManuallySelectedPreset) {
                // 延迟 500ms 等待会话完全初始化
                kotlinx.coroutines.delay(500)
                suspendCancellableCoroutine<Unit> { cont ->
                    gateway.client().agentPresetSelect(sessionId, latestPresetId, object : DshClient.DshCallback {
                        override fun onResult(value: JSONObject?) {
                            // 更新会话状态中的预设信息(回调在后台线程,切回主线程写)
                            val presetName = presetNames[latestPresetId] ?: latestPresetId
                            scope.launch {
                                sessions = sessions.toMutableMap().apply {
                                    val s = this[sessionId] ?: return@apply
                                    put(sessionId, s.copy(
                                        agentPresetId = latestPresetId,
                                        agentPresetName = presetName,
                                    ))
                                }
                                if (cont.isActive) cont.resume(Unit)
                            }
                        }
                        override fun onError(code: Int, message: String?, details: JSONObject?) {
                            scope.launch { if (cont.isActive) cont.resume(Unit) }
                        }
                    })
                }
            }

            // 步骤3:应用权限(更新本地状态)
            sessions = sessions.toMutableMap().apply {
                val s = this[sessionId] ?: return@apply
                put(sessionId, s.copy(permission = latestPermission))
            }
        }
    }

    // 从进程级 pending 视图恢复提问/审批卡片(官方:进入会话时按 pendingInteractions 唤醒弹窗)。
    // EventStream 持续维护该视图(requested 记录/resolved 删除),与本方法解耦;因此无论是
    // 杀进程重启、还是按返回键切走会话页后再进入,都能在进入会话时恢复出仍待处理的卡片。
    fun applyPendingInteractionView() {
        val gw = AppRuntime.gateway() ?: return
        val frames = gw.events().snapshotPendingInteractions()
        if (frames.isEmpty()) return
        for (env in frames) {
            applyStateEnvelope(env)
        }
    }

    // 启动时加载:agentPreset.list + workspace.list + session.list → 真实会话列表,并自动打开最近会话
    LaunchedEffect(Unit) {
        val gateway = AppRuntime.gateway() ?: return@LaunchedEffect
        val presetsValue = suspendRpc(gateway, "agentPreset.list", JSONObject())
        val presetMap = parsePresetNameMap(presetsValue)
        presetNames = presetMap
        presetList = parsePresetList(presetsValue)
        // 设置默认预设(第一个)
        if (selectedPresetId.isEmpty() && presetList.isNotEmpty()) {
            selectedPresetId = presetList.first().id
        }
        val wsValue = suspendRpc(gateway, "workspace.list", JSONObject())
        val ws = parseWorkspaceList(wsValue)
        workspaces = ws
        // 设置默认工作区(第一个)
        if (selectedWorkspaceId.isEmpty() && ws.isNotEmpty()) {
            selectedWorkspaceId = ws.first().workspaceId
        }
        // 加载设置:agent-presets.default + permission.defaultPreset
        val settingsValue = suspendRpc(gateway, "settings.describe", JSONObject())
        if (settingsValue != null) {
            val namespaces = settingsValue.optJSONArray("namespaces")
            if (namespaces != null) {
                for (i in 0 until namespaces.length()) {
                    val ns = namespaces.optJSONObject(i) ?: continue
                    val nsName = ns.optString("ns")
                    val value = ns.optJSONObject("value")
                    when (nsName) {
                        "agent-presets" -> {
                            val defaultPreset = value?.optString("default", "") ?: ""
                            if (defaultPreset.isNotEmpty()) {
                                onSettingsAgentPresetChange(defaultPreset)
                                // 同步引导层显示状态
                                selectedPresetId = defaultPreset
                            }
                        }
                        "permission" -> {
                            val defaultPerm = value?.optString("defaultPreset", "") ?: ""
                            if (defaultPerm.isNotEmpty()) {
                                // 映射后端值到显示名
                                val displayName = when (defaultPerm) {
                                    "read-only" -> "Read Only"
                                    "workspace-write" -> "Workspace Write"
                                    "danger-full-access" -> "Full access"
                                    else -> defaultPerm
                                }
                                onSettingsPermissionChange(displayName)
                            }
                        }
                    }
                }
            }
        }
        val listValue = suspendRpc(gateway, "session.list", JSONObject())
        val parsed = parseSessionList(listValue)
        if (parsed.isEmpty()) {
            // 无会话 → 创建一个真实后端会话(对齐官方:用户点击新开会话后立即创建)
            // 先加载模型列表
            val modelsValue = suspendRpc(gateway, "llm.models", JSONObject())
            if (modelsValue != null) {
                val models = parseModels(modelsValue)
                if (models.isNotEmpty()) {
                    defaultModels = models
                    defaultModelId = models.first().modelId
                    defaultEffortId = models.first().efforts.firstOrNull()?.first ?: "high"
                }
            }
            // 创建真实后端会话
            val createValue = suspendRpc(gateway, "session.create", JSONObject())
            val newId = createValue?.optString("sessionId", "") ?: ""
            if (newId.isNotEmpty()) {
                val appliedPreset = createValue?.optString("agentPreset", "") ?: ""
                val presetName = if (appliedPreset.isNotEmpty()) presetMap[appliedPreset] ?: appliedPreset else ""
                val modelDisplay = defaultModels.firstOrNull { it.modelId == defaultModelId }?.modelName
                    ?: defaultModelId.ifEmpty { "DeepSeek-V4-Flash" }
                val effortDisplay = defaultModels.firstOrNull { it.modelId == defaultModelId }
                    ?.efforts?.firstOrNull { it.first == defaultEffortId }?.second
                    ?: defaultEffortId.ifEmpty { "High" }
                sessions = mapOf(newId to SessionState(
                    id = newId,
                    model = modelDisplay,
                    reasoning = effortDisplay,
                    agentPresetId = appliedPreset,
                    agentPresetName = presetName,
                    showWelcome = true,
                ))
                currentId = newId
                // 加载模型目录
                val sessionModelsValue = suspendRpc(gateway, "session.models", JSONObject().put("sessionId", newId))
                if (sessionModelsValue != null) {
                    val models = parseModels(sessionModelsValue)
                    if (models.isNotEmpty()) {
                        sessionModelCatalog = mapOf(newId to models)
                    }
                }
                // 应用设置的预设和权限
                applySettingsToNewSession(newId)
            }
            return@LaunchedEffect
        }
        // 用后端 session.list 重建会话表;重建前先取当前表中的"实时待处理"状态
        // (审批/提问补发帧可能在列表加载完成前就到达并写入 pending* 字段),
        // 重建时按会话 id 合并保留,避免整表替换把未处理的提问/审批卡片状态冲掉。
        val prevBySid = sessions
        val mapped = linkedMapOf<String, SessionState>()
        parsed.forEach { s ->
            val name = if (s.agentPresetId.isNotEmpty()) presetMap[s.agentPresetId] ?: s.agentPresetId else ""
            val prev = prevBySid[s.id]
            mapped[s.id] = if (prev != null) {
                s.copy(
                    agentPresetName = name,
                    pendingApproval = prev.pendingApproval,
                    pendingQuestion = prev.pendingQuestion,
                )
            } else {
                s.copy(agentPresetName = name)
            }
        }
        sessions = mapped
        // 关键:会话表重建完成后,从进程级 pending 视图恢复仍待处理的提问/审批卡片
        // (覆盖"杀进程重启"与"返回键切走会话页后再进入"两种场景)。
        applyPendingInteractionView()
        val target = parsed.firstOrNull { !it.showWelcome }?.id ?: parsed.first().id
        currentId = target
        loadSessionHistory(target)
        // 首次默认展开当前会话所在组(官方:current group 自动展开;其余折叠)
        val currentWs = ws.firstOrNull { it.sessionIds.contains(target) }?.workspaceId
        expandedGroupKeys = setOf(currentWs ?: UNGROUPED_KEY)
        // 加载全局默认模型目录(供引导层使用):用 llm.models(官方方案,无需 sessionId)
        if (defaultModels.isEmpty()) {
            val llmModelsValue = suspendRpc(gateway, "llm.models", JSONObject())
            if (llmModelsValue != null) {
                val models = parseModels(llmModelsValue)
                if (models.isNotEmpty()) {
                    defaultModels = models
                    defaultModelId = models.first().modelId
                    defaultEffortId = models.first().efforts.firstOrNull()?.first ?: "high"
                }
            }
        }
    }

    val current = sessions[currentId] ?: return
    val messages = current.messages
    val showWelcome = current.showWelcome

    // 更新当前会话状态(局部函数,闭包捕获 var)
    // 注意:必须用 map 中的最新值(latest),而非重组时的 current 快照——
    // 否则流式回调等多次更新会基于旧快照覆盖状态(如 showWelcome 被重置回引导层)
    fun updateCurrent(transform: (SessionState) -> SessionState) {
        val latest = sessions[currentId] ?: return
        sessions = sessions.toMutableMap().apply {
            put(currentId, transform(latest))
        }
    }

    // 读取 content URI → DraftAttachment(读 base64 + 判断图片 + 取文件名)
    // 失败时返回 Result.failure(无效文件/文件已删除/读取异常等)
    fun uriToDraftAttachment(uri: Uri): Result<DraftAttachment> {
        return try {
            val resolver = context.contentResolver
            val mimeType = resolver.getType(uri) ?: ""
            // 文件名
            var name = "file"
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = cursor.getString(idx)
                }
            }
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return Result.failure(java.io.FileNotFoundException("无法读取文件: $name"))
            val data = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val isImage = mimeType.startsWith("image/") &&
                (mimeType == "image/png" || mimeType == "image/jpeg" ||
                 mimeType == "image/webp" || mimeType == "image/gif")
            Result.success(
                DraftAttachment(
                    id = uri.toString(),
                    name = name,
                    mimeType = if (isImage) mimeType else mimeType.ifBlank { "application/octet-stream" },
                    isImage = isImage,
                    data = data,
                    size = bytes.size.toLong(),
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ===== 附件文件选择器(SAF 多选,支持任意类型;仅选择,发送时才上传) =====
    val attachmentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val succeeded = ArrayList<DraftAttachment>()
            var failedCount = 0
            for (uri in uris) {
                val result = uriToDraftAttachment(uri)
                if (result.isSuccess) succeeded.add(result.getOrThrow())
                else failedCount++
            }
            if (succeeded.isNotEmpty()) {
                updateCurrent { it.copy(drafts = it.drafts + succeeded) }
            }
            if (failedCount > 0) {
                Toast.makeText(
                    context,
                    "$failedCount 个文件读取失败（文件可能已被删除或路径无效）",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ===== 图片缩略图缓存服务(对齐官方 imageUrls;独立于消息模型,不受历史重拉影响) =====
    // key = sessionId:attachmentId, value = base64 字符串
    // 缓存是持久的(remember),turn/end 重拉历史不会丢失;组件重新挂载后从缓存秒加载
    val imageCache = remember { mutableMapOf<String, String>() }
    // 正在下载中的 key(防并发重复请求)
    val imageLoading = remember { mutableSetOf<String>() }

    /**
     * 加载图片缩略图数据:先查缓存,未命中才调 session.attachment 下载,成功写缓存。
     * 对齐官方 resolveImage 的缓存语义——失败不写缓存,下次组件挂载自动重试。
     */
    suspend fun loadAttachmentImage(attachmentId: String): String? {
        if (attachmentId.isEmpty()) return null
        val cacheKey = "${currentId}:$attachmentId"
        // 1. 缓存命中
        imageCache[cacheKey]?.let { return it }
        // 2. 防并发重复下载
        if (cacheKey in imageLoading) return null
        imageLoading.add(cacheKey)
        return try {
            val gateway = AppRuntime.gateway()
            if (gateway == null) { imageLoading.remove(cacheKey); return null }
            val payload = JSONObject().put("sessionId", currentId).put("attachmentId", attachmentId)
            val v = suspendRpc(gateway, "session.attachment", payload)
            val data = v?.optString("data", "") ?: ""
            if (data.isNotEmpty()) {
                imageCache[cacheKey] = data  // 写缓存
                data
            } else {
                null  // 失败不写缓存,下次可重试
            }
        } catch (e: Exception) {
            null
        } finally {
            imageLoading.remove(cacheKey)
        }
    }
    // 切换会话:保存当前 + 加载目标;真实会话历史未加载则拉取
    fun switchTo(id: String) {
        currentId = id
        val s = sessions[id]
        if (s != null && s.loading) {
            loadSessionHistory(id)
        }
    }

    // 新建会话窗口:向后端创建真实会话(对齐官方 session.create 语义)
    // 1. 调用 session.create 创建真实后端会话
    // 2. 获取真实 sessionId
    // 3. 创建 SessionState 并切换
    // 4. 加载模型目录
    // 5. 应用设置的预设和权限
    fun newSession() {
        val gateway = AppRuntime.gateway() ?: return
        userManuallySelectedPreset = false // 新会话重置手动选择标记
        scope.launch {
            // 步骤1:调用 session.create 创建真实后端会话
            val payload = JSONObject()
            // 如果有选中的工作区,传入 workspaceId
            if (selectedWorkspaceId.isNotEmpty()) {
                payload.put("workspaceId", selectedWorkspaceId)
            }
            val createValue = suspendRpc(gateway, "session.create", payload)
            val newId = createValue?.optString("sessionId", "") ?: ""
            if (newId.isEmpty()) {
                return@launch
            }

            // 步骤2:获取应用的预设(后端可能返回默认预设)
            val appliedPreset = createValue?.optString("agentPreset", "") ?: ""
            val presetName = if (appliedPreset.isNotEmpty()) presetNames[appliedPreset] ?: appliedPreset else ""

            // 步骤3:创建 SessionState 并切换
            val modelDisplay = defaultModels.firstOrNull { it.modelId == defaultModelId }?.modelName
                ?: defaultModelId.ifEmpty { "DeepSeek-V4-Flash" }
            val effortDisplay = defaultModels.firstOrNull { it.modelId == defaultModelId }
                ?.efforts?.firstOrNull { it.first == defaultEffortId }?.second
                ?: defaultEffortId.ifEmpty { "High" }
            sessions = sessions.toMutableMap().apply {
                put(newId, SessionState(
                    id = newId,
                    model = modelDisplay,
                    reasoning = effortDisplay,
                    agentPresetId = appliedPreset.ifEmpty { settingsAgentPreset },
                    agentPresetName = presetName.ifEmpty { settingsAgentPreset },
                    showWelcome = true,
                ))
            }
            currentId = newId
            expandedGroupKeys = expandedGroupKeys + UNGROUPED_KEY

            // 步骤4:加载模型目录
            val modelsValue = suspendRpc(gateway, "session.models", JSONObject().put("sessionId", newId))
            if (modelsValue != null) {
                val models = parseModels(modelsValue)
                if (models.isNotEmpty()) {
                    sessionModelCatalog = sessionModelCatalog.toMutableMap().apply { put(newId, models) }
                    val cur = parseCurrentModel(modelsValue)
                    updateCurrent { it.copy(model = cur.second, reasoning = cur.third) }
                }
            }

            // 步骤5:应用设置的预设和权限(真实后端会话,可直接调用 agentPreset.select)
            applySettingsToNewSession(newId)
        }
    }

    // 引导层:选择工作区 → 向后端创建真实会话(官方 connectWorkspace 语义)
    // 1. 查找可复用的空白会话(同 workspace、blank)
    // 2. 没有可复用的 → session.create({ workspaceId })
    // 3. 切换到该会话,并加载模型目录
    fun pickWorkspace(workspaceId: String) {
        selectedWorkspaceId = workspaceId
        val gateway = AppRuntime.gateway() ?: return
        scope.launch {
            // 查找可复用的空白会话
            val reusable = sessions.values.firstOrNull { s ->
                s.blank && workspaces.any { it.workspaceId == workspaceId && it.sessionIds.contains(s.id) }
            }
            if (reusable != null) {
                // 复用已有空白会话
                currentId = reusable.id
                // 加载模型目录(如果还没有)
                if (!sessionModelCatalog.containsKey(reusable.id)) {
                    val modelsValue = suspendRpc(gateway, "session.models", JSONObject().put("sessionId", reusable.id))
                    if (modelsValue != null) {
                        val models = parseModels(modelsValue)
                        sessionModelCatalog = sessionModelCatalog.toMutableMap().apply { put(reusable.id, models) }
                        val cur = parseCurrentModel(modelsValue)
                        updateCurrent { it.copy(model = cur.second, reasoning = cur.third) }
                    }
                }
                // 触发自动化程序
                applySettingsToNewSession(reusable.id)
                return@launch
            }
            // 创建新会话(不传入预设和权限)
            val payload = JSONObject()
            payload.put("workspaceId", workspaceId)
            val createValue = suspendRpc(gateway, "session.create", payload)
            val newId = createValue?.optString("sessionId", "") ?: ""
            if (newId.isNotEmpty()) {
                sessions = sessions.toMutableMap().apply {
                    put(newId, SessionState(
                        id = newId,
                        showWelcome = true,
                    ))
                }
                currentId = newId
                expandedGroupKeys = expandedGroupKeys + UNGROUPED_KEY
                // 加载模型目录
                val modelsValue = suspendRpc(gateway, "session.models", JSONObject().put("sessionId", newId))
                if (modelsValue != null) {
                    val models = parseModels(modelsValue)
                    sessionModelCatalog = sessionModelCatalog.toMutableMap().apply { put(newId, models) }
                    val cur = parseCurrentModel(modelsValue)
                    updateCurrent { it.copy(model = cur.second, reasoning = cur.third) }
                }
                // 触发自动化程序
                applySettingsToNewSession(newId)
            }
        }
    }

    // 引导层:选择 Agent 预设 → 暂存(staging),等会话创建时应用
    // 如果当前已有空白会话,立即应用(官方 apply 语义)
    fun pickPreset(presetId: String) {
        selectedPresetId = presetId
        userManuallySelectedPreset = true // 标记用户在引导层手动选过预设
        val gateway = AppRuntime.gateway() ?: return
        val current = sessions[currentId] ?: return
        // 如果当前是空白会话或引导页会话,立即应用预设到后端
        if (current.blank || current.showWelcome) {
            scope.launch {
                gateway.client().agentPresetSelect(currentId, presetId, object : DshClient.DshCallback {
                    override fun onResult(value: JSONObject?) {
                        val presetName = presetNames[presetId] ?: presetId
                        // 回调在后台线程:切回主线程写 sessions
                        scope.launch {
                            updateCurrent { it.copy(agentPresetId = presetId, agentPresetName = presetName) }
                        }
                    }
                    override fun onError(code: Int, message: String?, details: JSONObject?) {
                    }
                })
            }
        }
        // 否则仅暂存,等 session.create 时通过 payload.agentPreset 传入
    }

    // 执行一条 slash 命令:官方 commands.execute(agent 作用域,args 信封)。
    // 产生 command/run → 命令生命周期事件(如 /permission、/plan、/goal 等)。
    fun executeCommand(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        val gateway = AppRuntime.gateway() ?: return
        val sid = currentId
        scope.launch {
            gateway.client().commandExecute(sid, trimmed, object : DshClient.DshCallback {
                override fun onResult(value: JSONObject?) {
                    val r = value?.optJSONObject("result")
                    if (r != null && r.optString("kind") == "error") {
                    }
                }

                override fun onError(code: Int, message: String?, details: JSONObject?) {
                }
            })
        }
    }

    // 切换权限:真实执行 /permission 命令(官方 commands.execute,agent 作用域)。
    // 实测:commands/execute + payload={args:{agentId,line,images}} → command/run →
    // permission/preset+sandbox/mode(+approval/policy 联动) → command/done(success)。
    // 命令执行后 mux session/projection 回推 permissions → 本端投影消费同步显示。
    fun changePermission(valueId: String) {
        val gateway = AppRuntime.gateway() ?: return
        val sid = currentId
        updateCurrent { s ->
            s.copy(
                permissionValue = valueId,
                permission = permValueToDisplay(valueId, s.permissionOptions) ?: valueId,
                updatedAt = System.currentTimeMillis(),
            )
        }
        scope.launch {
            gateway.client().commandExecute(sid, "/permission $valueId", object : DshClient.DshCallback {
                override fun onResult(value: JSONObject?) {
                    val r = value?.optJSONObject("result")
                    if (r != null && r.optString("kind") == "error") {
                    }
                }

                override fun onError(code: Int, message: String?, details: JSONObject?) {
                }
            })
        }
    }

    // 切换模型/推理档:官方 session.selectModel(provider/model/reasoningEffort)
    fun changeModel(providerId: String, modelId: String, effortId: String) {
        val g = AppRuntime.gateway() ?: return
        val sid = currentId
        updateCurrent { s ->
            val cat = sessionModelCatalog[sid] ?: emptyList()
            val m = cat.firstOrNull { it.modelId == modelId }
            s.copy(
                provider = providerId,
                modelId = modelId,
                model = m?.modelName ?: modelId,
                reasoningEffort = effortId,
                reasoning = m?.efforts?.firstOrNull { it.first == effortId }?.second ?: effortId,
                updatedAt = System.currentTimeMillis(),
            )
        }
        scope.launch {
            g.client().sessionSelectModel(sid, providerId, modelId, effortId, object : DshClient.DshCallback {
                override fun onResult(value: JSONObject?) {
                    // 成功的权威回显:会话级 projections 随后会推 modelSelection 帧;
                    // 这里再拉一次目录,把 current 与可用列表对齐(他处改模型也能收敛)
                    reloadModelCatalog(sid, showLoading = false)
                }

                override fun onError(code: Int, message: String?, details: JSONObject?) {
                    // 官方 ModelSelect:选择失败经排错条 + Toast 报错,并保留原选择。
                    // 静默失败会让用户以为"选了但没生效",所以必须回显。
                    val text = "模型操作失败:" + (message ?: "code $code")
                    modelsError = text
                    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                    // 失败后重新对齐后端真实选择(撤销乐观更新)
                    reloadModelCatalog(sid, showLoading = false)
                }
            })
        }
    }

    // 停止当前运行(官方 primary=Stop;session.cancel)
    // 停止后触发自动化程序：根据 autoClear 设置决定是否清空排队插话
    fun stopRunning() {
        val g = AppRuntime.gateway() ?: return
        val sid = currentId
        scope.launch {
            g.client().sessionCancel(sid, object : DshClient.DshCallback {
                override fun onResult(value: JSONObject?) {}
                override fun onError(code: Int, message: String?, details: JSONObject?) {
                }
            })
            // 自动化程序：停止后检查排队插话列表
            val latest = sessions[sid] ?: return@launch
            val pendingQueue = latest.queueItems.filter { it.source == "user" && it.placement == "queued" }
            if (pendingQueue.isEmpty()) return@launch
            if (localInterjectAutoClear) {
                // 自动清空：逐个删除排队中的插话消息
                for (item in pendingQueue) {
                    val payload = JSONObject().apply {
                        put("sessionId", sid)
                        put("itemId", item.id)
                        put("action", JSONObject().apply { put("kind", "remove") })
                    }
                    suspendRpc(g, "session.updateQueue", payload)
                }
            }
            // autoClear 关闭时：不做任何清理，悬浮球已通过显示条件修改常态化显示
        }
    }

    // 审批应答:POST /api/respond(client-response 信封 + {sessionId,approvalId,outcome})。
    // 关键:rpcId 必须复用 mux 帧的 rpcId(后端 pendingApprovals 的 key),否则返回 not-pending。
    fun respondToApproval(approval: ApprovalRequest, outcome: String) {
        val gateway = AppRuntime.gateway()
        if (gateway == null) return
        val sid = approval.sessionId
        scope.launch {
            gateway.client().respondApproval(sid, approval.id, approval.muxRpcId, outcome, object : DshClient.DshCallback {
                override fun onResult(resp: JSONObject?) {
                    val accepted = resp?.optBoolean("accepted") ?: false
                    if (accepted) {
                        // 回调在后台线程:切回主线程写 sessions,避免竞态
                        scope.launch {
                            sessions = sessions.toMutableMap().apply {
                                val s = sessions[sid]
                                if (s != null && s.pendingApproval?.id == approval.id) {
                                    put(sid, s.copy(pendingApproval = null))
                                }
                            }
                        }
                    }
                }

                override fun onError(code: Int, message: String?, details: JSONObject?) {
                    // 失败无副作用;保留卡片供用户重试
                }
            })
        }
    }

    // 清除指定会话的待答题卡片(仅当 rpcId 一致,防覆盖新请求)
    fun clearPendingQuestion(sid: String, muxRpcId: String) {
        sessions = sessions.toMutableMap().apply {
            val s = sessions[sid]
            if (s != null && s.pendingQuestion?.muxRpcId == muxRpcId) {
                put(sid, s.copy(pendingQuestion = null))
            }
        }
    }

    // 提问应答:一批问题全部作答完成后提交(POST /api/respond,复用 question/requested 帧 rpcId)
    fun answerQuestionBatch(req: QuestionRequest, answers: List<QuestionAnswer>) {
        val gateway = AppRuntime.gateway() ?: return
        val sid = req.sessionId
        val arr = JSONArray()
        answers.forEach { a ->
            val item = JSONObject()
                .put("id", a.id)
                .put("selected", JSONArray().apply { a.selected.forEach { put(it) } })
            if (a.custom.isNotEmpty()) item.put("custom", a.custom)
            arr.put(item)
        }
        scope.launch {
            gateway.client().respondQuestion(sid, req.muxRpcId, arr, object : DshClient.DshCallback {
                override fun onResult(resp: JSONObject?) {
                    val accepted = resp?.optBoolean("accepted") ?: false
                    // 回调在后台线程:切回主线程写 sessions + 弹 Toast
                    scope.launch {
                        if (accepted) {
                            clearPendingQuestion(sid, req.muxRpcId)
                        } else {
                            Toast.makeText(context, "回答被拒绝(可能已超时)", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                override fun onError(code: Int, message: String?, details: JSONObject?) {
                    scope.launch {
                        Toast.makeText(context, "回答提交失败: ${message ?: code}", Toast.LENGTH_LONG).show()
                    }
                }
            })
        }
    }

    // 取消提问(用户点卡片取消):ok:false 应答让后端结束等待
    fun cancelQuestion(req: QuestionRequest) {
        val gateway = AppRuntime.gateway() ?: return
        val sid = req.sessionId
        scope.launch {
            gateway.client().respondCancel(req.muxRpcId, object : DshClient.DshCallback {
                override fun onResult(resp: JSONObject?) {
                    scope.launch { clearPendingQuestion(sid, req.muxRpcId) }
                }

                override fun onError(code: Int, message: String?, details: JSONObject?) {
                    scope.launch { clearPendingQuestion(sid, req.muxRpcId) }
                }
            })
        }
    }

    // 命令菜单选择:填入编辑器等待用户确认补参(官方 popup 选择后回车提交完整行)
    fun pickCommand(cmdWithSpace: String) {
        updateCurrent { it.copy(composerText = cmdWithSpace, updatedAt = System.currentTimeMillis()) }
    }

    // ---- 会话列表分组派生(官方 workspace 树语义,纯派生自当前状态) ----
    // 全部会话(含本地新建);workspace 树/单列表派生
    val allSessions = sessions.values.toList()
    val workspaceGroups: List<SessionGroup> =
        if (groupBy == SessionGroupBy.WORKSPACE) {
            deriveWorkspaceGroups(allSessions, workspaces, currentId, expandedGroupKeys)
        } else {
            emptyList()
        }
    val flatSessions: List<SessionState> =
        if (groupBy == SessionGroupBy.FLAT) deriveFlatSessions(allSessions, currentId) else emptyList()
    val subCounts: Map<String, Int> = runningSubagentCounts(allSessions)

    // 切换/新建会话后:确保当前会话所在组已展开(官方 current group 自动展开)
    LaunchedEffect(currentId, groupBy, workspaces) {
        if (groupBy != SessionGroupBy.WORKSPACE) return@LaunchedEffect
        val g = workspaces.firstOrNull { it.sessionIds.contains(currentId) }?.workspaceId
            ?: UNGROUPED_KEY
        if (g !in expandedGroupKeys) {
            expandedGroupKeys = expandedGroupKeys + g
        }
    }

    val dark = LocalDarkTheme.current
    val mainBg = if (dark) MainBgDark else MainBgLight

    // 侧边栏收纳态宽度(rail):内容区恒定从 rail 右侧开始布局——rail 占位不遮挡内容;
    // 展开态侧边栏(更宽)悬浮覆盖其上,内容区不随之挤压/重排
    val railWidth = sidebarTargetWidth(screenW, portrait, expanded = false)

    // 搜索逻辑(对齐官方 deriveSearchResults):客户端过滤会话标题
    val searchResults = remember(searchText, sessions, workspaces) {
        val query = searchText.trim().lowercase()
        if (query.isEmpty()) {
            emptyList()
        } else {
            // 构建 workspaceId → workspace title 映射
            val workspaceTitleMap = mutableMapOf<String, String>()
            workspaces.forEach { ws ->
                ws.sessionIds.forEach { sid ->
                    workspaceTitleMap[sid] = ws.title.ifEmpty { ws.workspaceId }
                }
            }
            sessions.values
                .filter { s ->
                    s.showWelcome == false &&
                    (s.title.lowercase().contains(query) ||
                     s.agentPresetName.lowercase().contains(query) ||
                     (workspaceTitleMap[s.id] ?: "").lowercase().contains(query))
                }
                .sortedByDescending { it.updatedAt }
                .take(20)
        }
    }

    // 发送处理:真实后端发送(官方 session.prompt, mode=queue);
    // 用户消息与助手回复经 mux 实时事件流回显(官方 inbox 入队 + assistant 流式)。
    // 所有会话都是真实后端会话(由 newSession/pickWorkspace 创建)。

    // 检查并引导开启「所有文件访问」权限(Android 11+ 写公共目录需要)
    fun ensureStoragePermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = android.net.Uri.parse("package:${context.packageName}")
                    context.startActivity(intent)
                } catch (e: Exception) {
                    try {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                }
                return false
            }
        }
        return true
    }

    // 应用附件存储文件夹: /storage/emulated/0/APTUIDSH/(安装时自动创建,已有则复用)
    fun attachmentDir(): java.io.File? {
        return try {
            val dir = java.io.File(android.os.Environment.getExternalStorageDirectory(), "APTUIDSH")
            if (!dir.exists()) dir.mkdirs()
            if (dir.exists() && dir.isDirectory) dir else null
        } catch (e: Exception) {
            null
        }
    }

    // 复制草稿附件到应用存储文件夹,返回绝对路径(助手经 Termux 可读)
    fun copyDraftToAttachments(draft: DraftAttachment): String? {
        return try {
            val dir = attachmentDir() ?: return null
            // 避免重名覆盖:同名追加时间戳
            val base = draft.name.substringBeforeLast(".", draft.name)
            val ext = draft.name.substringAfterLast(".", "")
            val fileName = if (ext.isNotEmpty() && ext != draft.name) {
                "$base-${System.currentTimeMillis()}.$ext"
            } else {
                "${draft.name}-${System.currentTimeMillis()}"
            }
            val target = java.io.File(dir, fileName)
            val bytes = android.util.Base64.decode(draft.data, android.util.Base64.NO_WRAP)
            target.writeBytes(bytes)
            target.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    // 从 DshClient 的 onError 参数提取中文错误描述(输入错误)
    fun extractPromptError(code: Int, message: String?, details: JSONObject?): String {
        return when (code) {
            // 服务端业务错误(ERR_SERVER):details.rawCode 是字符串错误码(如 attachment-error),details.reason 是详情
            DshClient.ERR_SERVER -> {
                val rawCode = details?.optString("rawCode", "") ?: ""
                val reason = details?.optString("reason", "") ?: ""
                promptErrorText(rawCode.ifEmpty { null }, message, reason.ifEmpty { null })
            }
            // 传输层错误
            DshClient.ERR_NETWORK -> "网络连接失败，请检查后端服务"
            DshClient.ERR_HTTP -> "服务响应异常（HTTP ${details?.optInt("httpStatus", -1) ?: ""}）"
            DshClient.ERR_BAD_RESPONSE -> "服务响应无法解析"
            else -> message?.let { "发送失败：$it" } ?: "发送失败"
        }
    }

    // 普通文本发送:真实后端发送(官方 session.prompt, mode=queue);用户消息与回复
    // 经 mux 实时事件流回显。所有会话都是真实后端会话。
    fun sendPromptToSession(trimmed: String, drafts: List<DraftAttachment>) {
        val gateway = AppRuntime.gateway() ?: return
        val sid = currentId
        // 非图片文件需写入 /storage/emulated/0/APTUIDSH/(Android 11+ 需「所有文件访问」权限)
        val fileDrafts = drafts.filter { !it.isImage }
        if (fileDrafts.isNotEmpty() && !ensureStoragePermission()) {
            Toast.makeText(context, "需开启「所有文件访问」权限以保存附件文件", Toast.LENGTH_LONG).show()
            return
        }
        scope.launch {
            if (!openedSessions.contains(sid)) loadSessionHistory(sid)
            // 设置运行态 + 清除旧错误(不立即清空草稿,等后端反馈)
            sessions = sessions.toMutableMap().apply {
                val s = this[sid] ?: return@apply
                put(sid, s.copy(running = true, promptError = ""))
            }
            // 构建 content:文本块 + 图片块(image) + 文件文本块(文件名+路径)
            val content = JSONArray()
            if (trimmed.isNotEmpty()) {
                content.put(JSONObject().put("type", "text").put("text", trimmed))
            }
            // 图片附件 → 官方 image 块(base64 + mediaType + name)
            for (img in drafts.filter { it.isImage }) {
                content.put(JSONObject().apply {
                    put("type", "image")
                    put("mediaType", img.mimeType)
                    put("data", img.data)
                    put("name", img.name)
                })
            }
            // 非图片文件 → 复制到应用存储文件夹,text 块告知文件名+路径
            if (fileDrafts.isNotEmpty()) {
                val copied = fileDrafts.mapNotNull { copyDraftToAttachments(it) }
                if (copied.isNotEmpty()) {
                    val fileText = copied.joinToString("\n") { "📎 附件文件: $it" }
                    content.put(JSONObject().put("type", "text").put("text", fileText))
                }
            }
            gateway.client().sessionPrompt(sid, "queue", content, object : DshClient.DshCallback {
                override fun onResult(value: JSONObject?) {
                    // 回调在后台线程:统一切回主线程写 sessions,避免与消息折叠竞态导致草稿残留
                    scope.launch {
                        sessions = sessions.toMutableMap().apply {
                            val s = this[sid] ?: return@apply
                            put(sid, s.copy(composerText = "", drafts = emptyList(), promptError = ""))
                        }
                    }
                }
                override fun onError(code: Int, message: String?, details: JSONObject?) {
                    // 发送失败:保留草稿,设置错误提示,恢复非运行态(同样主线程写)
                    val errText = extractPromptError(code, message, details)
                    scope.launch {
                        sessions = sessions.toMutableMap().apply {
                            val s = this[sid] ?: return@apply
                            put(sid, s.copy(running = false, promptError = errText))
                        }
                    }
                }
            })
        }
    }

    val handleSend: (String) -> Unit = { raw ->
        val trimmed = raw.trim()
        val gateway = AppRuntime.gateway()
        val drafts = sessions[currentId]?.drafts ?: emptyList()
        // 有文本或附件即可发送
        if ((trimmed.isNotEmpty() || drafts.isNotEmpty()) && gateway != null) {
            if (trimmed.startsWith("/") && drafts.isEmpty()) {
                // slash 命令行(/xxx …)→ commands.execute(立即清空)
                updateCurrent { it.copy(composerText = "", updatedAt = System.currentTimeMillis()) }
                executeCommand(trimmed)
            } else {
                // 普通发送:不立即清空草稿,等 sendPromptToSession 反馈
                sendPromptToSession(trimmed, drafts)
            }
        }
    }


    // 普通文本发送:真实后端发送(官方 session.prompt, mode=queue);用户消息与回复
    // 经 mux 实时事件流回显。本地会话(引导层新建)先向后端创建真实会话再发送。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(mainBg),
    ) {
        // ===== 会话层内容区(z1;列表滚动范围延伸到屏幕底部,文本可透过悬浮卡片下方空隙可见)
        // 覆盖模式:内容区恒定从收纳态 rail 右侧开始(rail 占位不遮内容);
        // 展开态侧边栏(z3)悬浮覆盖其上,不挤压内容 =====
        if (!showWelcome) {
            val todos = remember(messages) { extractTodosFromMessages(messages) }
            MainContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = railWidth)
                    .systemBarsPadding()
                    .zIndex(1f),
                messages = messages,
                agentPresetName = current.agentPresetName.ifEmpty { current.agentPresetId },
                todos = todos,
                hasMoreHistory = current.hasMoreHistory,
                onLoadMore = { loadMoreHistory(currentId) },
                running = current.running,
                turnCompleted = current.turnCompleted,
                loadAttachmentImage = { attachmentId -> loadAttachmentImage(attachmentId) },
                onFork = { seq ->
                    // 分支对话:session.fork → 打开子会话(对齐官方:fork后直接切换到子会话)
                    val gateway = AppRuntime.gateway() ?: return@MainContent
                    scope.launch {
                        val payload = JSONObject()
                            .put("sessionId", currentId)
                            .put("atSeq", seq)
                            .put("increaseTitle", true)
                        val result = suspendRpc(gateway, "session.fork", payload)
                        val childId = result?.optString("sessionId", "") ?: ""
                        if (childId.isNotEmpty()) {
                            // 对齐官方:fork后直接切换到子会话
                            switchTo(childId)
                            loadSessionHistory(childId)
                        }
                    }
                },
                onFilesClick = { FileBrowserActivity.start(context, "") },
                // 顶部请求卡片(答题 / 审批;渲染在任务进度横条正下方)
                question = sessions[currentId]?.pendingQuestion,
                approval = current.pendingApproval,
                onQuestionAnswered = { answers ->
                    sessions[currentId]?.pendingQuestion?.let { answerQuestionBatch(it, answers) }
                },
                onQuestionCancelled = {
                    sessions[currentId]?.pendingQuestion?.let { cancelQuestion(it) }
                },
                onApprovalDecided = { outcome ->
                    current.pendingApproval?.let { respondToApproval(it, outcome) }
                },
                onOutsideClick = {
                    // 竖屏展开时点击空白直接收纳;横屏展开时点击外部不收纳
                    if (expanded && portrait) {
                        expanded = false
                        focusManager.clearFocus()
                    }
                },
            )
        }
        
        // ===== 插话悬浮球(z2.5;设置开启 + 助手正在输出时显示) =====
        InterjectFloatingBall(
            enabled = localInterjectEnabled,
            isAssistantStreaming = current.running,
            queueItems = current.queueItems,
            onSend = { text, _ ->
                // 发送插话消息
                val gateway = AppRuntime.gateway() ?: return@InterjectFloatingBall
                scope.launch {
                    val contentArray = JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", text)
                        })
                    }
                    val payload = JSONObject().apply {
                        put("sessionId", currentId)
                        put("mode", localInterjectMode)
                        put("content", contentArray)
                    }
                    suspendRpc(gateway, "session.prompt", payload)
                }
            },
            onEditQueueItem = { item, newText ->
                // 编辑排队消息：用 session.updateQueue action.kind=edit 替换内容
                if (newText.isBlank()) return@InterjectFloatingBall
                val gateway = AppRuntime.gateway() ?: return@InterjectFloatingBall
                scope.launch {
                    val contentArray = JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", newText)
                        })
                    }
                    val payload = JSONObject().apply {
                        put("sessionId", currentId)
                        put("itemId", item.id)
                        put("action", JSONObject().apply {
                            put("kind", "edit")
                            put("content", contentArray)
                        })
                    }
                    suspendRpc(gateway, "session.updateQueue", payload)
                }
            },
            onDeleteQueueItem = { item ->
                // 删除排队消息：session.updateQueue action.kind=remove
                val gateway = AppRuntime.gateway() ?: return@InterjectFloatingBall
                scope.launch {
                    val payload = JSONObject().apply {
                        put("sessionId", currentId)
                        put("itemId", item.id)
                        put("action", JSONObject().apply {
                            put("kind", "remove")
                        })
                    }
                    suspendRpc(gateway, "session.updateQueue", payload)
                }
            },
            onSteerQueueItem = { item ->
                // 立即插入：session.updateQueue action.kind=steer（后端自动 remove + steer）
                val gateway = AppRuntime.gateway() ?: return@InterjectFloatingBall
                scope.launch {
                    val payload = JSONObject().apply {
                        put("sessionId", currentId)
                        put("itemId", item.id)
                        put("action", JSONObject().apply {
                            put("kind", "steer")
                        })
                    }
                    suspendRpc(gateway, "session.updateQueue", payload)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(25f),
        )

        // ===== 统一输入卡片容器(z2;状态来自当前会话,切换会话按目标状态显示) =====
        ComposerArea(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2f),
            showWelcome = showWelcome,
            railWidth = railWidth,
            screenW = screenW,
            screenH = screenH,
            onSend = handleSend,
            text = current.composerText,
            onTextChange = { t -> updateCurrent { it.copy(composerText = t, promptError = "") } },
            model = current.model,
            onModelChange = { m ->
                val cat = sessionModelCatalog[currentId] ?: emptyList()
                val e = cat.firstOrNull { it.modelName == m || it.modelId == m }
                if (e != null) {
                    val effort = e.efforts.firstOrNull { it.first == current.reasoningEffort }?.first
                        ?: e.efforts.firstOrNull()?.first ?: ""
                    changeModel(e.providerId, e.modelId, effort)
                }
            },
            reasoning = current.reasoning,
            onReasoningChange = { r ->
                // 推理档可能是显示名或 id,映射为 id 再切换
                val effortId = sessionModelCatalog[currentId]?.firstOrNull { it.modelId == current.modelId }
                    ?.efforts?.firstOrNull { it.first == r || it.second == r }?.first ?: r
                changeModel(current.provider, current.modelId, effortId)
            },
            permission = current.permission,
            onPermissionChange = { p -> changePermission(p) },
            collapsed = current.collapsed,
            onCollapsedChange = { c -> updateCurrent { it.copy(collapsed = c) } },
            // 后端真实数据/动作
            models = sessionModelCatalog[currentId] ?: emptyList(),
            // 打开模型菜单即重拉(官方 load-on-open):保证菜单里永远是后端最新目录
            onModelsReload = { reloadModelCatalog(currentId) },
            modelsLoading = modelsLoading,
            modelsError = modelsError,
            permissionOptions = current.permissionOptions,
            statsText = current.statsText,
            contextPercent = current.contextPercent,
            contextText = current.contextText,
            sending = current.running,
            onStop = { stopRunning() },
            onCommandPick = { cmd -> pickCommand(cmd) },
            commandCatalog = commandCatalog,
            // 引导层真实数据(工作区+Agent预设)
            workspaces = workspaces,
            presets = presetList,
            selectedWorkspaceId = selectedWorkspaceId,
            selectedPresetId = selectedPresetId,
            onWorkspacePick = { wsId -> pickWorkspace(wsId) },
            onPresetPick = { pid -> pickPreset(pid) },
            onWorkspaceCreated = { wsId ->
                // 工作区创建后,刷新工作区列表并选中新工作区
                scope.launch {
                    val gateway = AppRuntime.gateway() ?: return@launch
                    val wsValue = suspendRpc(gateway, "workspace.list", JSONObject())
                    workspaces = parseWorkspaceList(wsValue)
                    selectedWorkspaceId = wsId
                }
            },
            // 附件
            onAttachmentClick = { attachmentPicker.launch(arrayOf("*/*")) },
            drafts = current.drafts,
            onRemoveDraft = { draftId ->
                updateCurrent { it.copy(drafts = it.drafts.filterNot { d -> d.id == draftId }) }
            },
            // 输入错误横幅
            promptError = current.promptError,
        )

        // ===== 竖屏展开时的内容区阴影遮罩(z2.5;覆盖内容区侧,点击收纳;侧边栏 z3 在其上不受影响) =====
        AnimatedVisibility(
            visible = portrait && expanded,
            enter = fadeIn(tween(250)),
            modifier = Modifier
                .fillMaxSize()
                .padding(start = railWidth)
                .zIndex(2.5f),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        expanded = false
                        focusManager.clearFocus()
                    },
            )
        }

        // ===== 侧边栏(z3;新建=新会话窗口并切换;会话列表点击切换/长按拖动排序) =====
        Sidebar(
            expanded = expanded,
            onToggle = { expanded = !expanded },
            onNewSession = {
                // 新开会话 = 新建窗口并切换;竖屏展开态点击后自动收纳(横屏不变)
                newSession()
                if (portrait && expanded) expanded = false
            },
            onWorkspace = { workspacePickerVisible = true },
            onSearchClick = { expanded = true },
            onSettings = { onSettingsVisibleChange(true) },
            onMaterialClick = { materialDialogVisible = true },
            onUsageClick = { usageDialogVisible = true },
            modifier = Modifier
                .zIndex(3f)
                .systemBarsPadding(),
            currentSessionId = currentId,
            onSessionClick = {
                // 切换会话:竖屏自动收纳侧边栏,横屏保持展开
                switchTo(it)
                if (portrait) expanded = false
            },
            // 会话列表分组(官方 workspace 树:按工作区 / 单列表)
            groupBy = groupBy,
            onGroupByChange = { groupBy = it },
            workspaceGroups = workspaceGroups,
            flatSessions = flatSessions,
            onToggleGroup = { key ->
                expandedGroupKeys = if (key in expandedGroupKeys) {
                    expandedGroupKeys - key
                } else {
                    expandedGroupKeys + key
                }
            },
            runningSubCounts = subCounts,
            // 搜索功能
            searchText = searchText,
            onSearchTextChange = { searchText = it },
            searchResults = searchResults,
        )
    }

    // ===== 消费统计弹窗(侧边栏 11 图标) =====
    // 余额链路(修复:此前直接读 DSH 凭据文件必然失败——APP 与 Termux 是不同
    // Android 沙箱,UID 10199 vs 10328,且该文件 0600/目录 0700,APP 无法进入):
    //   1) 先问后端凭据状态(credentials.describe,HTTP RPC,无需文件权限);
    //   2) 用 APP 本地保存的 key 查余额;
    //   3) 本地无 key 且后端也未配置 → 主动弹密钥输入框。
    //
    // 数据链路:每次打开都现场 session.history 取 projections(不吃会话缓存),
    // 否则会因结构化字段只在"打开会话时"写过一次而显示为 0。
    //
    // 结构注意:触发用的 LaunchedEffect 放在 if 之外(只依赖开关与当前会话),
    // 避免副作用落在条件块内、开关翻转时协程被取消带来的边界行为。
    LaunchedEffect(usageDialogVisible, currentId) {
        if (!usageDialogVisible) return@LaunchedEffect
        refreshBalanceSnapshot(currentId)
        refreshUsageSnapshot(currentId)
    }
    if (usageDialogVisible) {
        val cur = sessions[currentId]
        ConsumptionStatsDialog(
            providerName = (cur?.provider ?: "").let { pid ->
                if (pid.isEmpty()) "" else sessionModelCatalog[currentId]
                    ?.firstOrNull { it.providerId == pid }?.providerName ?: pid
            },
            usage = cur?.tokenUsage ?: TokenUsage(),
            stats = cur?.sessionStats ?: SessionStats(),
            firstInputTokens = cur?.firstInputTokens ?: -1L,
            balance = balanceResult,
            balanceLoading = balanceLoading,
            usageLoading = usageLoading,
            onProviderClick = { apiKeyDialogVisible = true },
            onDismiss = { usageDialogVisible = false },
        )
    }

    // ===== API Key 输入弹窗(点击提供方名称唤起) =====
    if (apiKeyDialogVisible) {
        val cur = sessions[currentId]
        ApiKeyDialog(
            providerLabel = (cur?.provider ?: "").ifBlank { "DeepSeek" },
            initialKey = BalanceClient.loadLocalKey(context),
            backendConfigured = backendKeyConfigured,
            onSubmit = { key, saveToBackend ->
                apiKeyDialogVisible = false
                // 本地保存(用于 APP 直接查余额)
                BalanceClient.saveLocalKey(context, key)
                // 可选:写入 DSH 后端凭据(credentials.set,使后端也能用该 key)
                if (saveToBackend && key.isNotEmpty()) {
                    val gw = AppRuntime.gateway()
                    BalanceClient.saveToBackend(gw, key, object : DshClient.DshCallback {
                        override fun onResult(value: JSONObject?) {
                            scope.launch {
                                backendKeyConfigured = true
                                Toast.makeText(context, "已写入后端凭据", Toast.LENGTH_SHORT).show()
                            }
                        }

                        override fun onError(code: Int, message: String?, details: JSONObject?) {
                            scope.launch {
                                Toast.makeText(
                                    context,
                                    "写入后端失败:" + (message ?: "code $code"),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    })
                }
                // 保存后立即用新 key 重查余额
                scope.launch {
                    balanceLoading = true
                    balanceResult = null
                    val providerId = sessions[currentId]?.provider ?: ""
                    BalanceClient.fetch(providerId, key, backendKeyConfigured) { r ->
                        scope.launch { applyBalanceResult(r, providerId) }
                    }
                }
            },
            onDismiss = { apiKeyDialogVisible = false },
        )
    }

    // ===== 选择工作区目录弹窗(与引导层「添加工作区」一致) =====
    if (workspacePickerVisible) {
        WorkspacePickerDialog(
            onDismiss = { workspacePickerVisible = false },
            onWorkspaceCreated = { wsId ->
                // 工作区创建后,刷新工作区列表并选中新工作区
                scope.launch {
                    val gateway = AppRuntime.gateway() ?: return@launch
                    val wsValue = suspendRpc(gateway, "workspace.list", JSONObject())
                    workspaces = parseWorkspaceList(wsValue)
                    selectedWorkspaceId = wsId
                }
            },
        )
    }

    // ===== 模型配置弹窗 =====
    if (materialDialogVisible) {
        // 从后端加载提供方列表和凭据
        var materialProviders by remember { mutableStateOf(listOf<ProviderEntry>()) }
        var materialCredentials by remember { mutableStateOf(mapOf<String, CredentialStatus>()) }
        // 模型发现状态
        var materialDiscoveredModels by remember { mutableStateOf(listOf<CandidateModel>()) }
        var materialIsDiscovering by remember { mutableStateOf(false) }
        var materialDiscoverError by remember { mutableStateOf<String?>(null) }
        
        // 加载数据
        LaunchedEffect(materialDialogVisible) {
            if (materialDialogVisible) {
                val gateway = AppRuntime.gateway()
                if (gateway != null) {
                    // 1. 获取提供方列表
                    val providersValue = suspendRpc(gateway, "llm.providers", JSONObject())
                    if (providersValue != null) {
                        val providersArr = providersValue.optJSONArray("providers")
                        if (providersArr != null) {
                            val allEntries = mutableListOf<ProviderEntry>()
                            for (i in 0 until providersArr.length()) {
                                val p = providersArr.optJSONObject(i) ?: continue
                                val providerId = p.optString("provider", "")
                                val isActive = p.optBoolean("active", true)
                                allEntries.add(ProviderEntry(
                                    provider = providerId,
                                    displayName = p.optString("displayName", providerId),
                                    settingsNs = p.optString("settingsNs", ""),
                                    settingsPath = p.optJSONArray("settingsPath")?.let { arr ->
                                        (0 until arr.length()).map { arr.optString(it) }
                                    } ?: emptyList(),
                                    active = isActive,
                                    declared = p.optBoolean("declared", false),
                                ))
                            }
                            materialProviders = allEntries
                        }
                    }
                    
                    // 2. 从 settings.describe 获取完整配置（baseURL + models）
                    val settingsValue = suspendRpc(gateway, "settings.describe", JSONObject())
                    if (settingsValue != null) {
                        val namespaces = settingsValue.optJSONArray("namespaces")
                        if (namespaces != null) {
                            // 构建 settingsNs → value 映射
                            val nsMap = mutableMapOf<String, JSONObject>()
                            for (i in 0 until namespaces.length()) {
                                val ns = namespaces.optJSONObject(i) ?: continue
                                nsMap[ns.optString("ns")] = ns.optJSONObject("value") ?: JSONObject()
                            }
                            
                            // 为每个提供方提取 baseURL 和 models
                            materialProviders = materialProviders.map { provider ->
                                val nsValue = nsMap[provider.settingsNs] ?: return@map provider
                                
                                // 提取完整配置路径的值
                                var configValue: JSONObject? = nsValue
                                for (path in provider.settingsPath) {
                                    configValue = configValue?.optJSONObject(path)
                                }
                                
                                if (configValue != null) {
                                    // 提取 baseURL
                                    val baseURL = configValue.optString("baseURL", "")
                                    
                                    // 提取 models
                                    val modelsArr = configValue.optJSONArray("models")
                                    val models = if (modelsArr != null) {
                                        (0 until modelsArr.length()).mapNotNull { i ->
                                            val m = modelsArr.optJSONObject(i) ?: return@mapNotNull null
                                            ConfiguredModel(
                                                id = m.optString("id", ""),
                                                name = m.optString("name", m.optString("id", "")),
                                            )
                                        }
                                    } else emptyList()
                                    
                                    provider.copy(
                                        defaultBaseURL = baseURL,
                                        models = models,
                                    )
                                } else provider
                            }
                        }
                    }
                    
                    // 3. 获取凭据状态
                    val refs = materialProviders.mapNotNull { it.provider.uppercase().replace("[^A-Z0-9]+".toRegex(), "_") + "_API_KEY" }.distinct()
                    if (refs.isNotEmpty()) {
                        val refsArr = JSONArray()
                        refs.forEach { refsArr.put(it) }
                        val credsValue = suspendRpc(gateway, "credentials.describe", JSONObject().put("refs", refsArr))
                        if (credsValue != null) {
                            val credsObj = credsValue.optJSONObject("credentials")
                            val credMap = mutableMapOf<String, CredentialStatus>()
                            materialProviders.forEach { provider ->
                                val keyRef = provider.provider.uppercase().replace("[^A-Z0-9]+".toRegex(), "_") + "_API_KEY"
                                val credObj = credsObj?.optJSONObject(keyRef)
                                credMap[provider.provider] = CredentialStatus(
                                    configured = credObj?.optBoolean("configured", false) ?: false,
                                    writable = credObj?.optBoolean("writable", false) ?: false,
                                    value = credObj?.optString("value"),
                                )
                            }
                            materialCredentials = credMap
                        }
                    }
                }
            }
        }
        
        MaterialDialog(
            onDismiss = { materialDialogVisible = false },
            onProviderEdit = { provider, apiKey, baseURL ->
                scope.launch {
                    val gateway = AppRuntime.gateway() ?: return@launch
                    
                    // 1. 保存 API key 到凭据服务
                    if (apiKey.isNotEmpty()) {
                        val keyRef = provider.provider.uppercase().replace("[^A-Z0-9]+".toRegex(), "_") + "_API_KEY"
                        suspendRpc(gateway, "credentials.set", JSONObject().apply {
                            put("ref", keyRef)
                            put("value", apiKey)
                        })
                    }
                    
                    // 2. 更新 profile 中的 apiKeyEnv（对齐官方流程）
                    if (apiKey.isNotEmpty()) {
                        val keyRef = provider.provider.uppercase().replace("[^A-Z0-9]+".toRegex(), "_") + "_API_KEY"
                        val payload = JSONObject().apply {
                            put("ns", provider.settingsNs)
                            put("ops", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("op", "set")
                                    put("path", JSONArray().apply {
                                        provider.settingsPath.forEach { put(it) }
                                        put("apiKeyEnv")
                                    })
                                    put("value", keyRef)
                                })
                            })
                        }
                        suspendRpc(gateway, "settings.mutate", payload)
                    }
                    
                    // 3. 如果有自定义 baseURL，也保存
                    if (baseURL.isNotEmpty()) {
                        val payload = JSONObject().apply {
                            put("ns", provider.settingsNs)
                            put("ops", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("op", "set")
                                    put("path", JSONArray().apply {
                                        provider.settingsPath.forEach { put(it) }
                                        put("baseURL")
                                    })
                                    put("value", baseURL)
                                })
                            })
                        }
                        suspendRpc(gateway, "settings.mutate", payload)
                    }
                    
                    materialDialogVisible = false
                    materialDialogVisible = true
                }
            },
            onProviderDelete = { provider ->
                scope.launch {
                    val gateway = AppRuntime.gateway() ?: return@launch
                    val payload = JSONObject().apply {
                        put("ns", provider.settingsNs)
                        put("ops", JSONArray().apply {
                            put(JSONObject().apply {
                                put("op", "unset")
                                put("path", JSONArray().apply {
                                    provider.settingsPath.forEach { put(it) }
                                })
                            })
                        })
                    }
                    suspendRpc(gateway, "settings.mutate", payload)
                    val keyRef = provider.provider.uppercase().replace("[^A-Z0-9]+".toRegex(), "_") + "_API_KEY"
                    suspendRpc(gateway, "credentials.unset", JSONObject().apply {
                        put("ref", keyRef)
                    })
                    materialDialogVisible = false
                    materialDialogVisible = true
                }
            },
            onDiscoverModels = { provider, apiKey, baseURL ->
                // 获取可用模型
                scope.launch {
                    materialIsDiscovering = true
                    materialDiscoverError = null
                    materialDiscoveredModels = emptyList()
                    
                    val gateway = AppRuntime.gateway()
                    if (gateway == null) {
                        materialDiscoverError = "无法连接到后端"
                        materialIsDiscovering = false
                        return@launch
                    }
                    
                    val payload = JSONObject().apply {
                        put("settingsNs", provider.settingsNs)
                        if (provider.provider.isNotEmpty()) put("provider", provider.provider)
                        if (baseURL.isNotEmpty()) put("baseURL", baseURL)
                        if (apiKey.isNotEmpty()) put("apiKey", apiKey)
                    }
                    
                    val result = suspendRpc(gateway, "llm.discoverModels", payload)
                    if (result != null) {
                        val modelsArr = result.optJSONArray("models")
                        if (modelsArr != null) {
                            val models = mutableListOf<CandidateModel>()
                            for (i in 0 until modelsArr.length()) {
                                val m = modelsArr.optJSONObject(i) ?: continue
                                models.add(CandidateModel(
                                    id = m.optString("id", ""),
                                    name = m.optString("name", null),
                                ))
                            }
                            materialDiscoveredModels = models
                            if (models.isEmpty()) {
                                materialDiscoverError = "未找到可用模型"
                            }
                        } else {
                            materialDiscoverError = "返回数据格式错误"
                        }
                    } else {
                        materialDiscoverError = "获取模型列表失败"
                    }
                    materialIsDiscovering = false
                }
            },
            onSaveModels = { provider, selectedModels ->
                // 保存选中的模型到后端
                scope.launch {
                    val gateway = AppRuntime.gateway() ?: return@launch
                    
                    // 构建 models 数组
                    val modelsArr = JSONArray()
                    selectedModels.forEach { model ->
                        modelsArr.put(JSONObject().apply {
                            put("id", model.id)
                            if (model.name != null) put("name", model.name)
                        })
                    }
                    
                    // 构建 profile
                    val profile = JSONObject().apply {
                        put("models", modelsArr)
                    }
                    
                    // 通过 settings.mutate 保存
                    val payload = JSONObject().apply {
                        put("ns", provider.settingsNs)
                        put("ops", JSONArray().apply {
                            put(JSONObject().apply {
                                put("op", "set")
                                put("path", JSONArray().apply {
                                    provider.settingsPath.forEach { put(it) }
                                    put("models")
                                })
                                put("value", modelsArr)
                            })
                        })
                    }
                    suspendRpc(gateway, "settings.mutate", payload)
                    
                    // 刷新列表
                    materialDialogVisible = false
                    materialDialogVisible = true
                }
            },
            allProviders = materialProviders,
            credentials = materialCredentials,
            discoveredModels = materialDiscoveredModels,
            isDiscovering = materialIsDiscovering,
            discoverError = materialDiscoverError,
        )
    }

    // ===== 设置弹窗(左侧设置列表 + 右侧内容区) =====
    if (settingsVisible) {
        SettingsDialog(
            selectedSection = settingsSection,
            onSectionChange = onSettingsSectionChange,
            agentPreset = settingsAgentPreset,
            onAgentPresetChange = { preset ->
                onSettingsAgentPresetChange(preset)
                // 同步引导层显示状态
                selectedPresetId = preset
                // 持久化到后端:settings.update ns=agent-presets
                scope.launch {
                    val gateway = AppRuntime.gateway() ?: return@launch
                    val patch = JSONObject().put("default", preset)
                    val payload = JSONObject().put("ns", "agent-presets").put("patch", patch)
                    suspendRpc(gateway, "settings.update", payload)
                    // 重新读取一次配置,确认后端保存成功
                    val settingsValue = suspendRpc(gateway, "settings.describe", JSONObject())
                    if (settingsValue != null) {
                        val namespaces = settingsValue.optJSONArray("namespaces")
                        if (namespaces != null) {
                            for (i in 0 until namespaces.length()) {
                                val ns = namespaces.optJSONObject(i) ?: continue
                                if (ns.optString("ns") == "agent-presets") {
                                    val defaultPreset = ns.optJSONObject("value")?.optString("default", "") ?: ""
                                    if (defaultPreset.isNotEmpty()) {
                                        onSettingsAgentPresetChange(defaultPreset)
                                        selectedPresetId = defaultPreset
                                    }
                                }
                            }
                        }
                    }
                }
            },
            permission = settingsPermission,
            onPermissionChange = { perm ->
                onSettingsPermissionChange(perm)
                // 映射显示名到后端值
                val backendValue = when (perm) {
                    "Read Only" -> "read-only"
                    "Workspace Write" -> "workspace-write"
                    "Full access" -> "danger-full-access"
                    else -> perm
                }
                // 持久化到后端:settings.update ns=permission
                scope.launch {
                    val gateway = AppRuntime.gateway() ?: return@launch
                    val patch = JSONObject().put("defaultPreset", backendValue)
                    val payload = JSONObject().put("ns", "permission").put("patch", patch)
                    suspendRpc(gateway, "settings.update", payload)
                    // 重新读取一次配置,确认后端保存成功
                    val settingsValue = suspendRpc(gateway, "settings.describe", JSONObject())
                    if (settingsValue != null) {
                        val namespaces = settingsValue.optJSONArray("namespaces")
                        if (namespaces != null) {
                            for (i in 0 until namespaces.length()) {
                                val ns = namespaces.optJSONObject(i) ?: continue
                                if (ns.optString("ns") == "permission") {
                                    val defaultPerm = ns.optJSONObject("value")?.optString("defaultPreset", "") ?: ""
                                    if (defaultPerm.isNotEmpty()) {
                                        val displayName = when (defaultPerm) {
                                            "read-only" -> "Read Only"
                                            "workspace-write" -> "Workspace Write"
                                            "danger-full-access" -> "Full access"
                                            else -> defaultPerm
                                        }
                                        onSettingsPermissionChange(displayName)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            language = settingsLanguage,
            onLanguageChange = onSettingsLanguageChange,
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            interjectEnabled = interjectEnabled,
            onInterjectEnabledChange = { enabled ->
                onInterjectEnabledChange(enabled)
                // 本地化存储到 SharedPreferences
                scope.launch {
                    val prefs = context.getSharedPreferences("aptuidsh_settings", Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("interject_enabled", enabled).apply()
                }
            },
            interjectMode = interjectMode,
            onInterjectModeChange = { mode ->
                onInterjectModeChange(mode)
                // 本地化存储到 SharedPreferences
                scope.launch {
                    val prefs = context.getSharedPreferences("aptuidsh_settings", Context.MODE_PRIVATE)
                    prefs.edit().putString("interject_mode", mode).apply()
                }
            },
            interjectAutoClear = interjectAutoClear,
            onInterjectAutoClearChange = { autoClear ->
                onInterjectAutoClearChange(autoClear)
                scope.launch {
                    val prefs = context.getSharedPreferences("aptuidsh_settings", Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("interject_auto_clear", autoClear).apply()
                }
            },
            onDismiss = { onSettingsVisibleChange(false) },
            // 传递后端真实数据
            presetList = presetList,
            permissionOptions = listOf(
                "read-only" to "Read Only",
                "workspace-write" to "Workspace Write",
                "danger-full-access" to "Full access",
            ),
        )
    }

    // ===== 顶部请求卡片(答题/审批)已内联到 MainContent 的任务横条下方渲染,此处不再用 Dialog =====
}

/**
 * 从 settings.yaml 读取提供方的默认 baseURL
 */
private fun readDefaultBaseURL(providerId: String): String {
    return try {
        val file = java.io.File("/data/data/com.termux/files/home/.dsh/settings.yaml")
        if (!file.exists()) return ""
        val lines = file.readLines()
        var inProviders = false
        var inTargetProvider = false
        
        for (line in lines) {
            val trimmed = line.trimEnd()
            val currentIndent = line.length - line.trimStart().length
            
            // 检测 llm-pi-ai: (缩进 0)
            if (currentIndent == 0 && trimmed == "llm-pi-ai:") {
                inProviders = true
                continue
            }
            
            if (inProviders) {
                // 提供方 ID: 缩进 4 (如 "    xiaomi:")
                if (currentIndent == 4 && trimmed.endsWith(":") && !trimmed.startsWith("-")) {
                    inTargetProvider = trimmed.removeSuffix(":").trim() == providerId
                    continue
                }
                
                // baseURL: 缩进 6 (如 "      baseURL: xxx")
                if (inTargetProvider && currentIndent == 6 && trimmed.startsWith("baseURL:")) {
                    return trimmed.substringAfter("baseURL:").trim().removeSurrounding("\"")
                }
                
                // 离开目标提供方: 下一个缩进 <= 4 的行
                if (inTargetProvider && currentIndent <= 4 && trimmed.endsWith(":")) {
                    inTargetProvider = false
                }
            }
        }
        ""
    } catch (e: Exception) {
        ""
    }
}
