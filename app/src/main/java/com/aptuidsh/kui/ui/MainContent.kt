package com.aptuidsh.kui.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.aptuidsh.kui.ui.theme.LocalDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aptuidsh.kui.R

// ==================== 内容区颜色(亮/暗双主题) ====================
private val PrimaryBlue = Color(0xFF4D6BFE)
private val TextPrimaryLight = Color(0xFF1A1D26)
private val TextPrimaryDark = Color(0xFFE6F0EC)
private val TextSecondaryLight = Color(0xFF5B6478)
private val TextSecondaryDark = Color(0xFFA3B5AC)
private val BorderLight = Color(0xFFB8C4C0)
private val BorderDark = Color(0xFF3A4740)
private val DividerLight = Color(0xFFD8DEE4)
private val DividerDark = Color(0xFF2A322E)
private val PlaceholderLight = Color(0xFF9AA3B2)
private val PlaceholderDark = Color(0xFF8A93A5)
private val IconTintDark = Color(0xFFDCE8E3)

/** 顶部区域高度:对齐收纳态侧边栏第二个图标底部(14dp + 48dp×2)。 */
private val TopBarHeight = 110.dp

/** 左对齐边距(与侧边栏边线 25dp)/ 右侧距屏幕边缘(2 个中文空格)。 */
private val LeftInset = 25.dp
private val RightInset = 28.dp

/**
 * 内容区(未来对话区)UI 布局,暂不实现功能,仅 UI 占位与交互。
 *
 * <p>顶部区域(110dp,分隔线上方):
 * <ul>
 *   <li>行1: 预设模式「Android APP 开发(AG 经验)」(11.png,不可点击) + 右侧 Session log
 *       直角边框按钮(12.png,靠屏幕边缘 4 字间距)</li>
 *   <li>行2: 空</li>
 *   <li>行3: 后台任务「20 个后台任务」(13.png,点击 180° 翻转 + 弹出圆角菜单容器)</li>
 *   <li>行4: 选项卡「对话 | 轨迹」(平分宽度,选中主色+下划线)</li>
 * </ul>
 * 分隔线(1dp 浅灰)在 110dp 处;分隔线下方为内容主体占位
 * (对话:暂无消息 / 轨迹:暂无工具调用)。
 */
@Composable
fun MainContent(
    modifier: Modifier = Modifier,
    onOutsideClick: () -> Unit,
    messages: List<ChatMessage> = emptyList(),
    agentPresetName: String = "",
    todos: List<TodoItem> = emptyList(),
    hasMoreHistory: Boolean = false,
    onLoadMore: () -> Unit = {},
    running: Boolean = false,
    turnCompleted: Boolean = false,
    loadAttachmentImage: suspend (String) -> String? = { null },
    onFork: ((Int) -> Unit)? = null,
    onFilesClick: () -> Unit = {},
    // ---- 顶部请求卡片(答题 / 权限审批;渲染在 TodoPanel 正下方,二者不同时出现) ----
    question: QuestionRequest? = null,
    approval: ApprovalRequest? = null,
    onQuestionAnswered: (List<QuestionAnswer>) -> Unit = {},
    onQuestionCancelled: () -> Unit = {},
    onApprovalDecided: (String) -> Unit = {},
) {
    val dark = LocalDarkTheme.current
    val textPrimary = if (dark) TextPrimaryDark else TextPrimaryLight
    val textSecondary = if (dark) TextSecondaryDark else TextSecondaryLight
    val borderColor = if (dark) BorderDark else BorderLight
    val dividerColor = if (dark) DividerDark else DividerLight
    val placeholder = if (dark) PlaceholderDark else PlaceholderLight
    val iconTint: Color? = if (dark) IconTintDark else null

    var selectedTab by remember { mutableStateOf(0) } // 0=对话 1=轨迹
    var jobsVisible by remember { mutableStateOf(false) }
    var jobsFlipped by remember { mutableStateOf(false) }
    val jobsFlipAnim by animateFloatAsState(
        targetValue = if (jobsFlipped) 180f else 0f,
        animationSpec = tween(250),
        label = "jobsFlip",
    )

    Column(
        modifier = modifier.clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null,
        ) {
            onOutsideClick() // 空白区域点击(竖屏展开时收纳侧边栏)
        },
    ) {
        // ===== 顶部区域(110dp) =====
        Box(
            Modifier
                .fillMaxWidth()
                .height(TopBarHeight),
        ) {
            // 行1: 预设模式(不可点击,占位)
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = LeftInset, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_mode_ag),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    contentScale = ContentScale.Fit,
                    colorFilter = iconTint?.let { ColorFilter.tint(it) },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = agentPresetName.ifBlank { "Android APP 开发(AG 经验)" },
                    fontSize = 14.sp,
                    color = textPrimary,
                )
            }

            // 行1 右侧: Session log 下载按钮(直角边框,高度同行高,图标在文字右侧,占位)
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = RightInset, top = 11.dp)
                    .border(1.dp, borderColor)
                    .padding(horizontal = 10.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(0.dp))
                    .clickable { /* 占位:下载当前会话日志,暂不实现 */ },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Session log",
                    fontSize = 12.sp,
                    color = textPrimary,
                )
                Spacer(Modifier.width(4.dp))
                Image(
                    painter = painterResource(R.drawable.ic_session_log),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    contentScale = ContentScale.Fit,
                    colorFilter = iconTint?.let { ColorFilter.tint(it) },
                )
            }

            // 行2 右侧: 「🗂 文件」文件浏览按钮(Session log 正下方;打开内置文件浏览器)
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = RightInset, top = 37.dp)
                    .border(1.dp, borderColor)
                    .padding(horizontal = 10.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(0.dp))
                    .clickable(onClick = onFilesClick),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "🗂 文件",
                    fontSize = 12.sp,
                    color = textPrimary,
                )
            }

            // 行3: 后台任务(图标在文字右侧,点击 180° 翻转 + 弹出菜单容器)
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = LeftInset, top = 46.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        jobsFlipped = !jobsFlipped
                        jobsVisible = !jobsVisible
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "20 个后台任务",
                    fontSize = 13.sp,
                    color = textPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Image(
                    painter = painterResource(R.drawable.ic_jobs),
                    contentDescription = null,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer { rotationZ = jobsFlipAnim },
                    contentScale = ContentScale.Fit,
                    colorFilter = iconTint?.let { ColorFilter.tint(it) },
                )
            }

            // 行4: 选项卡 对话 | 轨迹(平分整行宽度,选中主色+下划线)
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(32.dp),
            ) {
                TabItem("对话", selected = selectedTab == 0, modifier = Modifier.weight(1f)) {
                    selectedTab = 0
                }
                TabItem("轨迹", selected = selectedTab == 1, modifier = Modifier.weight(1f)) {
                    selectedTab = 1
                }
            }
        }

        // ===== 分隔线(轻微灰色,决定顶部高度 110dp) =====
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(dividerColor),
        )

        // ===== 内容主体(对话:消息流 / 轨迹:占位) =====
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (selectedTab == 1) {
                // 轨迹选项卡:占位
                Text(
                    text = "暂无工具调用",
                    fontSize = 14.sp,
                    color = placeholder,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                // 对话选项卡:消息流(用户气泡 + 助手块)
                ConversationView(
                    messages = messages,
                    modifier = Modifier.fillMaxSize(),
                    hasMoreHistory = hasMoreHistory,
                    onLoadMore = onLoadMore,
                    running = running,
                    turnCompleted = turnCompleted,
                    loadAttachmentImage = loadAttachmentImage,
                    onFork = onFork,
                )
            }
            // ===== 顶部悬浮条区(对话选项卡):任务进度横条 + 请求卡片(答题/审批)。
            // 不随消息滚动;请求卡片紧跟任务横条正下方,高度自适应内容。
            if (selectedTab == 0) {
                val q = question
                val a = approval
                if (todos.isNotEmpty() || q != null || a != null) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        if (todos.isNotEmpty()) {
                            TodoPanel(todos = todos)
                            if (q != null || a != null) {
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                        when {
                            q != null -> QuestionCard(
                                request = q,
                                onAnswered = onQuestionAnswered,
                                onCancelled = onQuestionCancelled,
                            )
                            a != null -> ApprovalCard(
                                approval = a,
                                onDecide = onApprovalDecided,
                            )
                        }
                    }
                }
            }
        }
    }

    // ===== 后台任务菜单容器(圆角卡片,列表占位) =====
    if (jobsVisible) {
        Dialog(onDismissRequest = { jobsVisible = false }) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (dark) SidebarBgDark else SidebarBgLight,
                ),
            ) {
                Column(
                    Modifier
                        .width(280.dp)
                        .padding(16.dp),
                ) {
                    Text(
                        text = "后台任务",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = textPrimary,
                    )
                    Spacer(Modifier.height(8.dp))
                    // 占位任务列表(功能开发中)
                    listOf("任务 #1 · 运行中", "任务 #2 · 排队中", "任务 #3 · 已完成").forEach { item ->
                        Text(
                            text = "· $item",
                            fontSize = 13.sp,
                            color = textSecondary,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "(功能开发中,占位)",
                        fontSize = 11.sp,
                        color = placeholder,
                    )
                }
            }
        }
    }
}

/** 选项卡项:无容器包裹,选中主色文字 + 底部下划线。 */
@Composable
private fun TabItem(
    text: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val dark = LocalDarkTheme.current
    Box(
        modifier = modifier.clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = text,
                fontSize = 14.sp,
                color = if (selected) PrimaryBlue else if (dark) TextSecondaryDark else TextSecondaryLight,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
            Spacer(Modifier.height(3.dp))
            Box(
                Modifier
                    .width(28.dp)
                    .height(2.dp)
                    .background(if (selected) PrimaryBlue else Color.Transparent),
            )
        }
    }
}
