package com.aptuidsh.kui.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.aptuidsh.kui.ui.theme.LocalDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.aptuidsh.kui.R
import kotlin.math.abs
import kotlinx.coroutines.delay

private val PrimaryBlue = Color(0xFF4D6BFE)
private val TextPrimaryLight = Color(0xFF1A1D26)
private val TextPrimaryDark = Color(0xFFE6F0EC)
private val TextSecondaryLight = Color(0xFF5B6478)
private val TextSecondaryDark = Color(0xFFA3B5AC)
private val DividerLight = Color(0xFFD8DEE4)
private val DividerDark = Color(0xFF2A322E)
private val IconTintDark = Color(0xFFDCE8E3)

/**
 * 统一输入卡片区域(引导层与会话层共用同一 Composer)。
 *
 * <p>架构:引导层前两行(标题+工作区/预设)与输入卡片为<b>两个独立容器</b>,
 * 互不包含——前两行显隐不影响卡片布局;各自用 graphicsLayer 做纯绘制位移动画,
 * 位置目标基于切换后采样高度(动画期间固定,无循环驱动),显著降低切换卡顿。
 *
 * <p>位置:引导层 前两行+卡片 视觉相连居中;会话层 前两行移出屏幕上方淡出,
 * 卡片贴底(距底 25dp+导航栏),高度内容自适应(编辑器多行向上扩展)。
 * 切换时:卡片位移动画主导,16 图标/统计/前两行淡入淡出并行。
 */
@Composable
fun ComposerArea(
    showWelcome: Boolean,
    railWidth: Dp,          // 收纳态侧边栏宽度:内容恒从 rail 右侧开始(rail 占位不遮)
    screenW: Dp,
    screenH: Dp,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    // 会话内状态(由外部 SessionState 控制,切换会话时按目标状态显示)
    text: String = "",
    onTextChange: (String) -> Unit = {},
    model: String = "DeepSeek-V4-Flash",
    onModelChange: (String) -> Unit = {},
    reasoning: String = "High",
    onReasoningChange: (String) -> Unit = {},
    permission: String = "Full access",
    onPermissionChange: (String) -> Unit = {},
    collapsed: Boolean = false,
    onCollapsedChange: (Boolean) -> Unit = {},
    // ---- 后端真实数据(官方输入条语义,由 ChatScreen 提供) ----
    models: List<ModelEntry> = emptyList(),
    onModelsReload: () -> Unit = {},
    modelsLoading: Boolean = false,
    modelsError: String = "",
    permissionOptions: List<Pair<String, String>> = emptyList(),
    statsText: String = "",
    contextPercent: Float = -1f,
    contextText: String = "",
    sending: Boolean = false,
    onStop: () -> Unit = {},
    onCommandPick: (String) -> Unit = {},
    commandCatalog: List<SlashCommandEntry> = emptyList(),
    // ---- 引导层真实数据(工作区+Agent预设) ----
    workspaces: List<WorkspaceEntry> = emptyList(),
    presets: List<AgentPresetEntry> = emptyList(),
    selectedWorkspaceId: String = "",
    selectedPresetId: String = "",
    onWorkspacePick: (String) -> Unit = {},  // 选择工作区 → 创建/切换到该工作区的空白会话
    onPresetPick: (String) -> Unit = {},     // 选择预设 → 暂存/应用到当前会话
    onWorkspaceCreated: ((String) -> Unit)? = null, // 工作区创建后的回调
    // ---- 附件 ----
    onAttachmentClick: () -> Unit = {},      // 附件按钮点击 → 打开文件选择器
    drafts: List<DraftAttachment> = emptyList(), // 附件草稿
    onRemoveDraft: (String) -> Unit = {},    // 移除单个草稿附件
    // ---- 输入错误横幅 ----
    promptError: String = "",                // 发送失败错误描述(非空显示横幅)
) {
    val dark = LocalDarkTheme.current
    val textPrimary = if (dark) TextPrimaryDark else TextPrimaryLight
    val textSecondary = if (dark) TextSecondaryDark else TextSecondaryLight
    val dividerColor = if (dark) DividerDark else DividerLight
    val iconTint: Color? = if (dark) IconTintDark else null
    val menuBg = if (dark) SidebarBgDark else SidebarBgLight

    // 引导层菜单状态
    var workspaceMenu by remember { mutableStateOf(false) }
    var presetMenu by remember { mutableStateOf(false) }
    var addWorkspaceDialog by remember { mutableStateOf(false) }

    val leftInset = 25.dp
    val rightInset = 28.dp
    val bottomInset = 25.dp
    val gap = 12.dp // 前两行与卡片之间的视觉间距
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val density = LocalDensity.current
    val cardBg = if (dark) Color(0xFF0C0F12) else Color(0xFFE2E9E6) // 与 Composer 卡片一致

    // 收缩悬浮球状态:collapsed 由外部(会话状态)控制;savedCardH 为展开动画目标
    var savedCardH by remember { mutableStateOf(140.dp) }

    // 两个独立容器的高度(实时)与采样目标(切换后固定,动画期间不漂移)
    var headerH by remember { mutableStateOf(0.dp) }
    var cardH by remember { mutableStateOf(0.dp) }
    var targetHeaderH by remember { mutableStateOf(0.dp) }
    var targetCardH by remember { mutableStateOf(0.dp) }
    LaunchedEffect(showWelcome) {
        delay(300) // 等切换动画完成再采样,供下次切换使用
        targetHeaderH = headerH
        targetCardH = cardH
    }

    val hH = if (targetHeaderH > 0.dp) targetHeaderH else headerH
    val cH = if (targetCardH > 0.dp) targetCardH else cardH
    val totalH = hH + gap + cH
    val welcomeTop = ((screenH - totalH) / 2f).coerceAtLeast(0.dp)

    // 目标位置:引导层视觉相连居中;会话层 前两行移出上方、卡片贴底
    val headerTargetY = if (showWelcome) welcomeTop else -(hH + 80.dp)
    // 会话层卡片底部位置:按当前形态实时高度(球56 / 展开=onSizeChanged 实际高)计算,
    // 使卡片底部恒定于 距底25dp+导航栏,避免切换/收纳后使用过时采样高度导致贴到屏幕底
    val cardTargetY = if (showWelcome) {
        welcomeTop + hH + gap
    } else {
        val anchorH = if (collapsed) 56.dp else cardH
        (screenH - anchorH - bottomInset - navBar).coerceAtLeast(0.dp)
    }
    val headerAnimY by animateDpAsState(targetValue = headerTargetY, animationSpec = tween(250), label = "headerY")
    val cardAnimY by animateDpAsState(targetValue = cardTargetY, animationSpec = tween(250), label = "cardY")

    Box(modifier = modifier) {
        // ===== 容器1: 引导层前两行(独立;会话层淡出并移出屏幕上方) =====
        AnimatedVisibility(
            visible = showWelcome,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(250)),
            modifier = Modifier
                .width(screenW - railWidth - leftInset - rightInset)
                .graphicsLayer {
                    translationX = (railWidth + leftInset).toPx()
                    translationY = headerAnimY.toPx()
                }
                .onSizeChanged { size -> headerH = with(density) { size.height.toDp() } },
        ) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 行1 标题
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.ic_side_menu),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        contentScale = ContentScale.Fit,
                        colorFilter = iconTint?.let { ColorFilter.tint(it) },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "探索未至之境",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = textPrimary,
                    )
                }

                Spacer(Modifier.height(12.dp))

                // 行2 工作区选择 + Agent 预设选择(紧凑,灰色小字)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 工作区选择(左,下拉菜单;数据来自 workspace.list)
                    val selectedWsName = workspaces.firstOrNull { it.workspaceId == selectedWorkspaceId }?.title
                        ?: workspaces.firstOrNull { it.workspaceId == selectedWorkspaceId }?.workspaceId
                        ?: if (workspaces.isEmpty()) "选择工作区…" else workspaces.first().title.ifEmpty { workspaces.first().workspaceId }
                    Box {
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { workspaceMenu = true }
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_side_workspace),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                contentScale = ContentScale.Fit,
                                colorFilter = iconTint?.let { ColorFilter.tint(it) },
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(selectedWsName, fontSize = 12.sp, color = textSecondary)
                            Spacer(Modifier.width(3.dp))
                            Image(
                                painter = painterResource(R.drawable.ic_jobs),
                                contentDescription = null,
                                modifier = Modifier.size(11.dp),
                                contentScale = ContentScale.Fit,
                                colorFilter = iconTint?.let { ColorFilter.tint(it) },
                            )
                        }
                        if (workspaceMenu) {
                            Popup(
                                onDismissRequest = { workspaceMenu = false },
                                alignment = Alignment.TopStart,
                                offset = IntOffset(0, 40),
                            ) {
                                Column(
                                    Modifier
                                        .width(280.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(menuBg)
                                        .padding(vertical = 4.dp),
                                ) {
                                    if (workspaces.isEmpty()) {
                                        Text(
                                            "加载中…",
                                            fontSize = 13.sp,
                                            color = textSecondary,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        )
                                    } else {
                                        workspaces.forEach { ws ->
                                            Row(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable {
                                                        workspaceMenu = false
                                                        onWorkspacePick(ws.workspaceId)
                                                    }
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Image(
                                                    painter = painterResource(R.drawable.ic_ws_item),
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                    contentScale = ContentScale.Fit,
                                                    colorFilter = iconTint?.let { ColorFilter.tint(it) },
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text(
                                                        ws.title.ifEmpty { ws.workspaceId },
                                                        fontSize = 13.sp,
                                                        color = textPrimary,
                                                    )
                                                    if (ws.path.isNotEmpty()) {
                                                        Text(
                                                            ws.path,
                                                            fontSize = 10.sp,
                                                            color = textSecondary,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                    }
                                                }
                                                if (selectedWorkspaceId == ws.workspaceId) {
                                                    Text("✓", fontSize = 13.sp, color = PrimaryBlue)
                                                }
                                            }
                                        }
                                    }
                                    HorizontalDivider(color = dividerColor)
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                workspaceMenu = false
                                                addWorkspaceDialog = true
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Image(
                                            painter = painterResource(R.drawable.ic_ws_add),
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            contentScale = ContentScale.Fit,
                                            colorFilter = iconTint?.let { ColorFilter.tint(it) },
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("添加工作区…", fontSize = 13.sp, color = textSecondary)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.width(20.dp))

                    // Agent 预设选择(右,下拉菜单;数据来自 agentPreset.list)
                    val selectedPresetName = presets.firstOrNull { it.id == selectedPresetId }?.name
                        ?: if (presets.isEmpty()) "选择预设…" else presets.first().name
                    Box {
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { presetMenu = true }
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_mode_ag),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                contentScale = ContentScale.Fit,
                                colorFilter = iconTint?.let { ColorFilter.tint(it) },
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(selectedPresetName, fontSize = 12.sp, color = textSecondary)
                            Spacer(Modifier.width(3.dp))
                            Image(
                                painter = painterResource(R.drawable.ic_jobs),
                                contentDescription = null,
                                modifier = Modifier.size(11.dp),
                                contentScale = ContentScale.Fit,
                                colorFilter = iconTint?.let { ColorFilter.tint(it) },
                            )
                        }
                        if (presetMenu) {
                            Popup(
                                onDismissRequest = { presetMenu = false },
                                alignment = Alignment.TopStart,
                                offset = IntOffset(0, 40),
                            ) {
                                Column(
                                    Modifier
                                        .width(320.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(menuBg)
                                        .padding(vertical = 4.dp),
                                ) {
                                    if (presets.isEmpty()) {
                                        Text(
                                            "加载中…",
                                            fontSize = 13.sp,
                                            color = textSecondary,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        )
                                    } else {
                                        presets.forEach { preset ->
                                            Column(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable {
                                                        presetMenu = false
                                                        onPresetPick(preset.id)
                                                    }
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        preset.name,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = textPrimary,
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                    if (selectedPresetId == preset.id) {
                                                        Text("✓", fontSize = 13.sp, color = PrimaryBlue)
                                                    }
                                                }
                                                if (preset.description.isNotEmpty()) {
                                                    Spacer(Modifier.height(2.dp))
                                                    Text(
                                                        preset.description,
                                                        fontSize = 11.sp,
                                                        color = textSecondary,
                                                        maxLines = 3,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ===== 容器2: 输入卡片(右划收缩成圆形悬浮球,点击展开;位置动画,底部自适应) =====
        // 覆盖模式:卡片恒从收纳态 rail 右侧开始(rail 占位不遮),宽度 = 剩余可用宽;
        // 展开态侧边栏(z3)覆盖其上,卡片不随挤压
        val cardWidth = screenW - railWidth - leftInset - rightInset
        val animCardW by animateDpAsState(
            targetValue = if (collapsed) 56.dp else cardWidth,
            animationSpec = tween(300),
            label = "cardW",
        )
        val animCardH by animateDpAsState(
            targetValue = if (collapsed) 56.dp else savedCardH,
            animationSpec = tween(300),
            label = "cardH",
        )
        val cardContentAlpha by animateFloatAsState(
            targetValue = if (collapsed) 0f else 1f,
            animationSpec = tween(300),
            label = "cardAlpha",
        )
        val ballAlpha by animateFloatAsState(
            targetValue = if (collapsed) 1f else 0f,
            animationSpec = tween(300),
            label = "ballAlpha",
        )

        Box(
            Modifier
                .width(animCardW)
                .then(
                    if (collapsed) Modifier.height(animCardH)
                    else Modifier.heightIn(min = 140.dp, max = screenH * 0.5f)
                )
                .graphicsLayer {
                    // 右缘靠右对齐:左缘 + (宽度差) → 收缩时右缘不动,向左收缩
                    translationX = (railWidth + leftInset + (cardWidth - animCardW)).toPx()
                    translationY = cardAnimY.toPx()
                }
                .clip(RoundedCornerShape(percent = if (collapsed) 50 else 0))
                .background(cardBg)
                .pointerInput(collapsed, showWelcome) {
                    // 引导层不支持右滑收纳;仅会话层且未收缩时监听
                    if (!collapsed && !showWelcome) {
                        var dx = 0f
                        var dy = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dx = 0f; dy = 0f },
                            onHorizontalDrag = { change, amount ->
                                dx += amount
                                dy += change.positionChange().y
                                change.consume()
                            },
                            onDragEnd = {
                                // 手指右划(不限制速度)且水平倾斜角 <45° → 收缩
                                if (dx > 120f && abs(dy) < abs(dx)) {
                                    savedCardH = cardH
                                    onCollapsedChange(true)
                                }
                            },
                        )
                    }
                },
        ) {
            // Composer 内容(收缩时淡出但保持组合,未发送文本不丢失)
            Composer(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .alpha(cardContentAlpha)
                    .onSizeChanged { size -> cardH = with(density) { size.height.toDp() } },
                maxHeight = screenH / 3f,
                compact = showWelcome,
                onSend = onSend,
                text = text,
                onTextChange = onTextChange,
                model = model,
                onModelChange = onModelChange,
                reasoning = reasoning,
                onReasoningChange = onReasoningChange,
                permission = permission,
                onPermissionChange = onPermissionChange,
                models = models,
                onModelsReload = onModelsReload,
                modelsLoading = modelsLoading,
                modelsError = modelsError,
                permissionOptions = permissionOptions,
                statsText = statsText,
                contextPercent = contextPercent,
                contextText = contextText,
                sending = sending,
                onStop = onStop,
                onCommandPick = onCommandPick,
                commandCatalog = commandCatalog,
                onAttachmentClick = onAttachmentClick,
                drafts = drafts,
                onRemoveDraft = onRemoveDraft,
                promptError = promptError,
            )
            // 悬浮球图标(收缩时显示)
            Image(
                painter = painterResource(R.drawable.ic_jobs),
                contentDescription = null,
                modifier = Modifier
                    .size(24.dp)
                    .align(Alignment.Center)
                    .alpha(ballAlpha),
                contentScale = ContentScale.Fit,
                colorFilter = iconTint?.let { ColorFilter.tint(it) },
            )
            // 收缩时覆盖层:点击任意处展开恢复卡片
            if (collapsed) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onCollapsedChange(false) },
                )
            }
        }
    }

    // ===== 添加工作区弹窗(文件浏览器,层级为独立窗口) =====
    if (addWorkspaceDialog) {
        WorkspacePickerDialog(
            onDismiss = { addWorkspaceDialog = false },
            onWorkspaceCreated = onWorkspaceCreated,
        )
    }
}
