package com.aptuidsh.kui.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.aptuidsh.kui.ui.theme.LocalDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aptuidsh.kui.file.FileOpenKit
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PrimaryBlue = Color(0xFF4D6BFE)
private val TextPrimaryLight = Color(0xFF1A1D26)
private val TextPrimaryDark = Color(0xFFE6F0EC)
private val TextSecondaryLight = Color(0xFF5B6478)
private val TextSecondaryDark = Color(0xFFA3B5AC)
private val ExpandBgLight = Color(0xFFE9EFEC)
private val ExpandBgDark = Color(0xFF222A26)
private val CodeBgLight = Color(0xFFE2E7E4)
private val CodeBgDark = Color(0xFF1B221F)
private val InlineCodeBgLight = Color(0xFFE4E9E6)
private val InlineCodeBgDark = Color(0xFF2A332E)
private val BorderLight = Color(0xFFB8C4C0)
private val BorderDark = Color(0xFF3A4740)
// 可点击文件路径样式:灰色背景 + 红色文字(替代蓝色下划线)
private val PathBgLight = Color(0xFFE3E3E3)
private val PathBgDark = Color(0xFF3A403C)
private val PathFgLight = Color(0xFFC62828)
private val PathFgDark = Color(0xFFFF8A80)

/** 复制文本到剪贴板。 */
private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("dsh-message", text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}

/**
 * 会话消息列表(对话选项卡)。
 *
 * <p>对齐官方 dsh web UI 的滚动机制:
 * - atBottom 追踪:用户滚动到底部时标记 atBottom=true,内容变化时自动跟随
 * - 用户上滑:atBottom=false,停止自动滚动,显示"回到底部"按钮
 * - 加载更多:保存锚点位置,加载后恢复
 * - 流式输出:TurnStatus 显示"Deep diving..."+ 计时器
 */
@Composable
fun ConversationView(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
    hasMoreHistory: Boolean = false,
    onLoadMore: () -> Unit = {},
    running: Boolean = false,
    turnCompleted: Boolean = false,
    loadAttachmentImage: suspend (String) -> String? = { null },
    onFork: ((Int) -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val bottomAnchorKey = "bottom-anchor"
    val ctx = LocalContext.current

    // atBottom:用户是否位于底部(只描述"用户粘在最新一行"这一状态,
    // 判定只看“最后一项/锚点是否进入可视区”,与具体像素/动画解耦)
    var atBottom by remember { mutableStateOf(true) }
    val atBottomState = rememberUpdatedState(atBottom)

    // 底部状态追踪:滚动位置变化 → 是否贴底。不用任何动画期标志,直接跟随用户滚动结果
    LaunchedEffect(listState) {
        snapshotFlow { atBottomOf(listState) }
            .distinctUntilChanged()
            .collect { near -> atBottom = near }
    }

    // 真实“末项(底部锚点)”索引 = 顶部"加载更多"(如有) + 消息数 + 底部"TurnStatus"(流式时)
    val topExtra = if (hasMoreHistory) 1 else 0
    val runningTail = if (running && messages.isNotEmpty()) 1 else 0
    val bottomIndex = topExtra + messages.size + runningTail
    val tailBlockSize = messages.lastOrNull()?.blocks?.size ?: 0

    // ── 跟随闸门控制器(中间程序,全程生效,与是否流式无关) ───────────
    // 职责:唯一开关「自动滚动 autoFollow」。强信号规则:
    //   ① 用户把手从「底部可见」向上翻,使底部锚点从视口内变为视口外
    //      (正在滚动 && 底部不可见 && 不是引擎自身动画)→ 立即 autoFollow=false;
    //   ② 底部锚点重新出现在屏幕视口内 → 立即 autoFollow=true。
    // 内容增长顶出锚点时用户是静止的(moving=false),不会误关;引擎自己的追底动画
    // 由 engineBusy 隔离,也不会把自己判成“用户上滑”。
    var autoFollow by remember { mutableStateOf(true) }
    val autoFollowState = rememberUpdatedState(autoFollow)
    // 引擎动画进行中标记(隔离程序滚动,避免控制器把引擎动画当成用户手势)
    var engineBusy by remember { mutableStateOf(false) }
    val engineBusyState = rememberUpdatedState(engineBusy)
    LaunchedEffect(listState, bottomIndex) {
        snapshotFlow {
            val info = listState.layoutInfo
            ScrollSig(
                moving = listState.isScrollInProgress,
                bottomVisible = info.totalItemsCount > 0 &&
                    info.visibleItemsInfo.any { it.index == bottomIndex },
            )
        }.collect { sig ->
            // 规则②(优先级最高):底部回到视口 → 立刻恢复自动滚动
            val was = autoFollow
            if (sig.bottomVisible) {
                autoFollow = true
            } else if (sig.moving && !engineBusyState.value && autoFollowState.value) {
                // 规则①:用户正把底部滑出视口(翻历史)→ 立刻关闭自动滚动
                autoFollow = false
            }
            if (autoFollow != was) {
            }
        }
    }

    // ── 跟随引擎(仅 autoFollow=true 时工作;false 时整体退出,
    //    重新开启时该 Effect 因 key 变化重启,天然取消残留动画) ──
    val streamingTail = messages.lastOrNull()?.streaming == true
    LaunchedEffect(listState, messages.size, bottomIndex, tailBlockSize, streamingTail, autoFollow) {
        if (messages.isEmpty() || !autoFollow) return@LaunchedEffect
        var positioned = false
        while (true) {
            val info = listState.layoutInfo
            if (info.totalItemsCount == 0) {
                kotlinx.coroutines.delay(50)
                continue
            }
            val anchor = bottomIndex.coerceAtLeast(0)
            val anchorSeen = info.visibleItemsInfo.any { it.index == anchor }
            if (!positioned) {
                if (!anchorSeen) {
                    // 初次/回到底部后被重新启用时若还不在底,先快速定位到最新内容
                    engineBusy = true
                    try {
                        listState.scrollToItem(anchor)
                    } catch (_: Exception) {
                    } finally {
                        engineBusy = false
                    }
                    positioned = true
                    kotlinx.coroutines.delay(40)
                    continue
                }
                positioned = true
            }
            if (!anchorSeen && !listState.isScrollInProgress) {
                // 内容增长把锚点顶出视口底部(用户未在操作):做一次小距离动画回底,
                // 检查间隔极短,每次只需移动刚增长的部分,视觉为连续平滑上推。
                engineBusy = true
                try {
                    listState.animateScrollToItem(anchor)
                } catch (_: Exception) {
                } finally {
                    engineBusy = false
                }
                kotlinx.coroutines.delay(8)
            } else {
                kotlinx.coroutines.delay(if (streamingTail) 24 else 120)
            }
        }
    }

    val dark = LocalDarkTheme.current
    val textPrimary = if (dark) TextPrimaryDark else TextPrimaryLight
    val textSecondary = if (dark) TextSecondaryDark else TextSecondaryLight
    val linkBlue = PrimaryBlue

    BoxWithConstraints(modifier = modifier) {
        // 回到底部按钮纵向位置:内容区底部向上 1/3 高度处
        val bottomInset = if (constraints.maxHeight > 0 &&
            constraints.maxHeight != Constraints.Infinity
        ) {
            with(LocalDensity.current) { (constraints.maxHeight / 3f).toDp() }
        } else 120.dp
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = AnchorBottom,
            ),
        ) {
            // "加载更多"按钮(列表顶部)
            if (hasMoreHistory) {
                item {
                    Text(
                        text = "加载更多历史…",
                        fontSize = 13.sp,
                        color = linkBlue,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onLoadMore)
                            .padding(vertical = 12.dp),
                    )
                }
            }
            items(messages.size, key = { i -> messageKey(messages[i]) }) { index ->
                val msg = messages[index]
                if (msg.error.isNotEmpty()) {
                    // 助手输出中断错误:渲染错误横幅(无圆角边框透明背景)
                    ErrorBanner(msg.error)
                } else if (msg.role == "user") {
                    UserBubble(msg, loadAttachmentImage)
                } else {
                    // 紧凑模式:本条是流式中消息,或下一条是同一轮(turn 相同)的流式续接消息
                    // → 去掉 16dp 底距,避免推理行独占一条消息时上下出现大段空白。
                    val next = messages.getOrNull(index + 1)
                    val compact = msg.streaming
                        || (next != null && next.role == "assistant" && next.streaming
                            && msg.turn >= 0 && next.turn == msg.turn)
                    AssistantMessage(msg, compact)
                    // 工具组按 turn 号区分(修复"工具间隙短暂闪现又消失"):
                    //  - 整轮结束(!running || turnCompleted):所有已落定助手消息都显示;
                    //  - 流式中(running=true):只显示「更早 turn」的历史助手消息;
                    //    当前 running turn 的正文/工具中间消息(同 turn 或 turn 未知)不显示。
                    val currentTurn = messages.lastOrNull { it.role == "assistant" && it.turn >= 0 }?.turn ?: -1
                    val turnEnded = !running || turnCompleted
                    val showTail = when {
                        msg.blocks.isEmpty() || msg.streaming -> false
                        turnEnded -> true
                        msg.turn < 0 -> false
                        msg.turn == currentTurn -> false
                        else -> true
                    }
                    if (showTail) {
                        TurnTail(
                            msg = msg,
                            onFork = onFork,
                        )
                    }
                }
            }
            // 流式输出中:TurnStatus 状态指示器
            if (running && messages.isNotEmpty()) {
                item {
                    TurnStatus()
                }
            }
            // 底部锚点(2dp:仅作跟随检测用,尽量缩小"最新行下方的空白死区",
            // 避免新行先填进死区、再被整体推上去的观感)
            item(key = bottomAnchorKey) {
                Spacer(Modifier.height(2.dp))
            }
        }

        // "回到底部"按钮(非底部时显示;透明无底/无圆角,位于内容区底部向上 1/3 高度)
        AnimatedVisibility(
            visible = !atBottom,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = bottomInset),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable {
                        scope.launch {
                            if (messages.isNotEmpty()) {
                                try {
                                    listState.animateScrollToItem(bottomIndex.coerceAtLeast(0))
                                } catch (_: Exception) {}
                                atBottom = true
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "↓",
                    fontSize = 22.sp,
                    color = textPrimary,
                    style = TextStyle(
                        shadow = Shadow(
                            color = Color(0x80000000),
                            offset = Offset(0f, 2f),
                            blurRadius = 8f,
                        ),
                    ),
                )
            }
        }
    }
}

/**
 * 内容区底部留白:尽量收小,避免"最新行下方死区"先露出新行再被整推的观感。
 */
private val AnchorBottom = 6.dp

/** 消息稳定 key:同一条消息在重拉/就地更新时保持同一 key,滚动与项复用才稳定。 */
private fun messageKey(m: ChatMessage): String =
    m.role + "|" + m.turn + "|" + m.time

/** 跟随闸门控制器的滚动采样(是否滚动中 + 底部是否在视口)。 */
private data class ScrollSig(
    val moving: Boolean,        // 是否正在滚动(触摸/惯性/程序动画)
    val bottomVisible: Boolean, // 底部锚点是否已在屏幕视口内
)

/**
 * 是否位于列表底部(内容不满一屏也算底部):
 * 最后可见项是「末尾锚点」,或已是倒数第二项(最后一条消息)且其下缘接近视口底。
 */
private fun atBottomOf(state: androidx.compose.foundation.lazy.LazyListState): Boolean {
    val info = state.layoutInfo
    if (info.totalItemsCount == 0) return true
    val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return true
    if (lastVisible.index >= info.totalItemsCount - 1) return true
    val viewportEnd = info.viewportEndOffset
    val itemBottom = lastVisible.offset + lastVisible.size
    return lastVisible.index >= info.totalItemsCount - 2 && itemBottom >= viewportEnd - 100
}

/**
 * 助手输出中断错误横幅:无圆角边框透明背景。
 * 渲染在流式输出中断的下一行,持久化(历史与实时均解析 turn/end reason.error)。
 */
@Composable
private fun ErrorBanner(error: String) {
    val dark = LocalDarkTheme.current
    val dangerRed = Color(0xFFE5484D)
    val borderColor = if (dark) Color(0xFF5A3A3A) else Color(0xFFE0C0C0)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .border(1.dp, borderColor, RectangleShape)
            .background(Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⚠️", fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = error,
            fontSize = 13.sp,
            color = dangerRed,
        )
    }
}

/** 用户消息:右侧直角气泡 + 附件容器 + 复制图标(无时间戳,避免干扰流式)。 */
@Composable
private fun UserBubble(
    msg: ChatMessage,
    loadAttachmentImage: suspend (String) -> String?,
) {
    val dark = LocalDarkTheme.current
    val textPrimary = if (dark) TextPrimaryDark else TextPrimaryLight
    val textSecondary = if (dark) TextSecondaryDark else TextSecondaryLight
    val context = LocalContext.current

    val fullText = msg.blocks.joinToString("\n") { it.label }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
        horizontalAlignment = Alignment.End,
    ) {
        // 附件容器(图片缩略图 + 文件列表),附在气泡上方紧贴
        if (msg.images.isNotEmpty() || msg.files.isNotEmpty()) {
            AttachmentContainers(images = msg.images, files = msg.files, loadAttachmentImage = loadAttachmentImage)
            Spacer(Modifier.height(4.dp))
        }
        // 直角气泡(无圆角,背景不变);仅正文非空时显示
        if (fullText.isNotEmpty()) {
            Box(
                Modifier
                    .background(
                        PrimaryBlue.copy(alpha = if (dark) 0.35f else 0.15f),
                        RectangleShape,
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                LinkifyAnnotated(
                    annotated = AnnotatedString(fullText),
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    baseColor = textPrimary,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        // 复制图标(无时间戳)
        Text(
            text = "⧉",
            fontSize = 12.sp,
            color = textSecondary,
            modifier = Modifier
                .padding(2.dp)
                .clickable { copyToClipboard(context, fullText) },
        )
    }
}

/**
 * 附件容器:文件容器(上) + 图片缩略图容器(下),按列上下排放。
 * 横幅条状无圆角边框透明背景。
 */
@Composable
private fun AttachmentContainers(
    images: List<ImageAttachment>,
    files: List<FileAttachment>,
    loadAttachmentImage: suspend (String) -> String?,
) {
    Column(horizontalAlignment = Alignment.End) {
        // 文件容器(上方)
        if (files.isNotEmpty()) {
            FileContainer(files)
        }
        // 图片缩略图容器(下方,横排,横向滚动)
        if (images.isNotEmpty()) {
            if (files.isNotEmpty()) Spacer(Modifier.height(4.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
            ) {
                images.forEach { img ->
                    MessageImageThumb(img, loadAttachmentImage)
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
    }
}

/**
 * 消息流中的图片缩略图(64dp),组件自己异步加载图片数据。
 *
 * 对齐官方 MessageImage 组件设计:用 LaunchedEffect 自己加载图片,
 * 结果存组件本地 state,不写回消息模型。
 * 组件重新挂载时自动从缓存秒加载(缓存命中)或重新下载(缓存未命中/失败)。
 */
@Composable
private fun MessageImageThumb(
    img: ImageAttachment,
    loadAttachmentImage: suspend (String) -> String?,
) {
    val dark = LocalDarkTheme.current
    // 组件本地 state:不依赖消息模型的 data 字段
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loading by remember(img.attachmentId) { mutableStateOf(true) }
    var failed by remember(img.attachmentId) { mutableStateOf(false) }

    // 独立异步加载:每次组件挂载/attachmentId 变化时触发
    LaunchedEffect(img.attachmentId) {
        if (img.attachmentId.isEmpty()) {
            loading = false
            failed = true
            return@LaunchedEffect
        }
        // 先检查消息模型里的 data(实时流发送后可能有)
        val data = img.data.ifEmpty { loadAttachmentImage(img.attachmentId) }
        if (data != null && data.isNotEmpty()) {
            try {
                val raw = if (data.startsWith("data:")) data.substringAfter("base64,") else data
                val bytes = android.util.Base64.decode(raw, android.util.Base64.NO_WRAP)
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                }
                bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                if (bitmap == null) failed = true
            } catch (e: Exception) {
                failed = true
            }
        } else {
            failed = true
        }
        loading = false
    }

    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(0.dp))
            .background(if (dark) Color(0xFF1B221F) else Color(0xFFE2E9E6)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = img.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            failed -> Text("⚠️", fontSize = 18.sp)
            loading -> Text("⏳", fontSize = 18.sp)
        }
    }
}

/**
 * 文件容器:横幅条状无圆角边框透明背景。
 * 单文件直接显示文件名;多文件默认收起为「已上传 N 个文件」,点击展开,列表内部上下滚动。
 */
@Composable
private fun FileContainer(files: List<FileAttachment>) {
    val dark = LocalDarkTheme.current
    val textSecondary = if (dark) TextSecondaryDark else TextSecondaryLight
    val borderColor = if (dark) BorderDark else BorderLight
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .border(1.dp, borderColor, RectangleShape)
            .background(Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.End,
    ) {
        if (files.size == 1) {
            // 单文件:直接显示文件名
            FileRow(files[0], textSecondary)
        } else {
            // 多文件:展开时显示列表(内部上下滚动)
            if (expanded) {
                Column(
                    Modifier
                        .heightIn(max = 160.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    files.forEach { f -> FileRow(f, textSecondary) }
                }
            }
            Text(
                text = if (expanded) "收起 ▴" else "已上传 ${files.size} 个文件 ▾",
                fontSize = 11.sp,
                color = textSecondary,
                modifier = Modifier
                    .padding(vertical = 2.dp)
                    .clickable { expanded = !expanded },
            )
        }
    }
}

/** 单个文件名行。 */
@Composable
private fun FileRow(f: FileAttachment, textSecondary: Color) {
    Row(
        Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("📄", fontSize = 13.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            text = f.name,
            fontSize = 12.sp,
            color = textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 流式状态指示器(对齐官方 TurnStatus)。
 * 显示"Deep diving..."动画 + 计时器。
 */
@Composable
private fun TurnStatus() {
    val dark = LocalDarkTheme.current
    val textSecondary = if (dark) TextSecondaryDark else TextSecondaryLight
    val startTime = remember { mutableStateOf(System.currentTimeMillis()) }
    var elapsed by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            elapsed = System.currentTimeMillis() - startTime.value
            kotlinx.coroutines.delay(1000)
        }
    }

    val showClock = elapsed >= 15_000
    val durationText = if (showClock) {
        val secs = elapsed / 1000
        val mins = secs / 60
        val remainSecs = secs % 60
        if (mins > 0) "${mins}m ${remainSecs}s" else "${remainSecs}s"
    } else ""

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 流式动画圆点
        val infiniteTransition = rememberInfiniteTransition(label = "turnStatus")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "dotAlpha",
        )
        Box(
            Modifier
                .size(8.dp)
                .background(PrimaryBlue.copy(alpha = alpha), CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Deep diving…",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = textSecondary,
        )
        if (showClock) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = durationText,
                fontSize = 12.sp,
                color = textSecondary.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * 轮次尾部操作栏(对齐官方 TurnTailNodeView + MessageIconActions)。
 * 助手消息落定后显示:时间 + 运行时长 + 复制 + 分支。
 */
@Composable
private fun TurnTail(
    msg: ChatMessage,
    onFork: ((Int) -> Unit)?,
) {
    val dark = LocalDarkTheme.current
    val textSecondary = if (dark) TextSecondaryDark else TextSecondaryLight
    val context = LocalContext.current

    val runMs = if (msg.turnStartTime > 0L && msg.completedTime > 0L) {
        maxOf(0L, msg.completedTime - msg.turnStartTime)
    } else 0L
    val ttftMs = if (msg.turnStartTime > 0L && msg.firstTokenTime > 0L) {
        maxOf(0L, msg.firstTokenTime - msg.turnStartTime)
    } else 0L

    var copied by remember { mutableStateOf(false) }
    val timeText = formatMessageTime(msg.time)
    val runText = if (runMs > 0) formatDuration(runMs) else ""
    val ttftText = if (ttftMs > 0) "${ttftMs / 1000.0}s" else ""

    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 时间 + 运行时长 + TTFT
        Text(text = timeText, fontSize = 11.sp, color = textSecondary.copy(alpha = 0.7f))
        if (runText.isNotEmpty()) {
            Text(text = " · $runText", fontSize = 11.sp, color = textSecondary.copy(alpha = 0.7f))
        }
        if (ttftText.isNotEmpty()) {
            Text(text = " · TTFT $ttftText", fontSize = 11.sp, color = textSecondary.copy(alpha = 0.7f))
        }
        // 分支按钮(紧跟时间后面)
        if (onFork != null && msg.blocks.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp))
                    .clickable { onFork(msg.lastSeq.toInt()) },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "⑂", fontSize = 14.sp, color = textSecondary)
            }
        }
        // 复制按钮(最后)
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp))
                .clickable {
                    if (!copied) {
                        copyToClipboard(context, msg.blocks.joinToString("\n") { it.label })
                        copied = true
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(text = if (copied) "✓" else "⧉", fontSize = 14.sp, color = if (copied) PrimaryBlue else textSecondary)
        }
    }
}

/** 格式化消息时间戳。 */
private fun formatMessageTime(timeMs: Long): String {
    val cal = java.util.Calendar.getInstance()
    val msgCal = java.util.Calendar.getInstance().apply { timeInMillis = timeMs }
    return if (cal.get(java.util.Calendar.YEAR) == msgCal.get(java.util.Calendar.YEAR) &&
        cal.get(java.util.Calendar.DAY_OF_YEAR) == msgCal.get(java.util.Calendar.DAY_OF_YEAR)) {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timeMs))
    } else {
        java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timeMs))
    }
}

/** 格式化运行时长。 */
private fun formatDuration(ms: Long): String {
    val secs = ms / 1000
    val mins = secs / 60
    val remainSecs = secs % 60
    return if (mins > 0) "${mins}m ${remainSecs}s" else "${remainSecs}s"
}

/** 助手消息:全宽行 + 块列表。 */
@Composable
private fun AssistantMessage(msg: ChatMessage, compact: Boolean = false) {
    val dark = LocalDarkTheme.current
    val textPrimary = if (dark) TextPrimaryDark else TextPrimaryLight
    val textSecondary = if (dark) TextSecondaryDark else TextSecondaryLight

    Column(
        Modifier
            .fillMaxWidth()
            // 流式中的消息 / 后面还跟着同一轮后续消息时,不再留 16dp 底距,
            // 避免推理行独占一条消息时上下各出现一段空白(结束后合并才消失)。
            .padding(bottom = if (compact) 0.dp else 16.dp),
    ) {
        msg.blocks.forEach { block ->
            when (block.type) {
                "context-injection" -> InjectionRow(block)
                "reasoning" -> ReasoningRow(block, isStreaming = msg.streaming)
                "tool-call" -> ToolCallRow(block, isStreaming = msg.streaming)
                "pending" -> Text(
                    text = "生成中…",
                    fontSize = 12.sp,
                    color = textSecondary,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                else -> MarkdownBody(
                    text = block.label,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** 可折叠灰色小字行(注入/推理共用):标题行点击展开浅灰容器。 */
@Composable
private fun CollapsibleRow(
    label: String,
    body: String,
    bodyColor: Color,
    expandBg: Color,
    extraPadding: Boolean,
    iconRes: Int? = null,       // 可选:标题行左侧 PNG 图标
    statusDotColor: Color? = null, // 可选:标题行状态小点颜色
) {
    var open by remember { mutableStateOf(false) }
    val arrowRotate by animateFloatAsState(if (open) 180f else 0f, tween(250), label = "arrow")

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 可选图标
            if (iconRes != null) {
                Image(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            // 可选状态小点
            if (statusDotColor != null) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(statusDotColor, CircleShape),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = label,
                fontSize = 12.sp,
                color = bodyColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "▸",
                fontSize = 10.sp,
                color = bodyColor,
                modifier = Modifier.graphicsLayer { rotationZ = arrowRotate },
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(tween(250)) + fadeIn(tween(250)),
            exit = shrinkVertically(tween(250)) + fadeOut(tween(250)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(expandBg, RoundedCornerShape(8.dp))
                    .padding(if (extraPadding) 10.dp else 10.dp),
            ) {
                Text(
                    text = body,
                    fontSize = 10.sp,
                    color = bodyColor,
                )
            }
        }
    }
}

/** 上下文注入行(特小灰字展开容器)。 */
@Composable
private fun InjectionRow(block: MessageBlock) {
    val dark = LocalDarkTheme.current
    val textSecondary = if (dark) TextSecondaryDark else TextSecondaryLight
    val expandBg = if (dark) ExpandBgDark else ExpandBgLight
    CollapsibleRow(
        label = block.label,
        body = block.extra,
        bodyColor = textSecondary,
        expandBg = expandBg,
        extraPadding = true,
    )
}

/** 推理行:使用 think 图标,区分推理中/推理结束。 */
@Composable
private fun ReasoningRow(block: MessageBlock, isStreaming: Boolean = false) {
    val dark = LocalDarkTheme.current
    val textSecondary = if (dark) TextSecondaryDark else TextSecondaryLight
    val expandBg = if (dark) ExpandBgDark else ExpandBgLight
    // think 图标:推理中=24.png,推理结束=24-1.png
    val thinkIcon = toolIconResThink(isStreaming)
    // label 去掉旧的 emoji 前缀 "💭 Think · ",只保留摘要
    val cleanLabel = block.label
        .removePrefix("💭 Think · ")
        .removePrefix("💭 Think ·")
        .removePrefix("💭 Think")
        .trim()
        .ifEmpty { "Think" }
    // 状态点:推理中=黄色,推理结束=绿色
    val dotColor = if (isStreaming) Color(0xFFD4A017) else Color(0xFF43A047)
    CollapsibleRow(
        label = cleanLabel,
        body = block.extra,
        bodyColor = textSecondary,
        expandBg = expandBg,
        extraPadding = true,
        iconRes = thinkIcon,
        statusDotColor = dotColor,
    )
}

/**
 * 工具调用卡片(对齐 Web UI ToolRow)。
 *
 * 收起态: 图标 + 标题 + · + 摘要文本 + ▸
 * 展开态: IN(参数JSON) + OUT(结果文本)
 *
 * 特殊: todo_write 解析 todos 数组显示 "{done}/{total} 已完成 · {首个进行中}"
 */
@Composable
private fun ToolCallRow(block: MessageBlock, isStreaming: Boolean = false) {
    val dark = LocalDarkTheme.current
    val textPrimary = if (dark) TextPrimaryDark else TextPrimaryLight
    val textSecondary = if (dark) TextSecondaryDark else TextSecondaryLight
    val expandBg = if (dark) ExpandBgDark else ExpandBgLight
    val errorColor = Color(0xFFE53935)
    val successColor = Color(0xFF43A047)
    var open by remember { mutableStateOf(false) }
    val arrowRotate by animateFloatAsState(if (open) 180f else 0f, tween(250), label = "toolArrow")

    // 图标资源:think 根据流式状态选择不同图标
    val iconRes = if (block.toolKind == "think") {
        toolIconResThink(isStreaming)
    } else {
        toolIconRes(block.toolKind)
    }
    val title = toolTitle(block.toolKind)
    val summary = buildToolSummary(block)
    val expandable = block.extra.isNotEmpty() || block.result.isNotEmpty()

    // 工具状态:运行中=黄色,成功=绿色,失败=红色
    val toolStatus: String = when {
        block.result.isEmpty() -> "running"
        block.result.startsWith("❌") -> "error"
        else -> "success"
    }

    Column(Modifier.fillMaxWidth()) {
        // ---- 收起行: 图标 + 标题 + 状态点 + 摘要 + ▸ ----
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { if (expandable) open = !open }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 工具图标(PNG drawable)
            Image(
                painter = painterResource(id = iconRes),
                contentDescription = title,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = textPrimary,
            )
            // 状态小点:黄色=运行中,绿色=成功,红色=失败
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier
                    .size(6.dp)
                    .background(
                        when (toolStatus) {
                            "error" -> errorColor
                            "success" -> successColor
                            else -> Color(0xFFD4A017) // 运行中:黄色
                        },
                        CircleShape,
                    ),
            )
            // 摘要区
            if (summary.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = summary,
                    fontSize = 12.sp,
                    color = textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (expandable) {
                Text(
                    text = "▸",
                    fontSize = 10.sp,
                    color = textSecondary,
                    modifier = Modifier.graphicsLayer { rotationZ = arrowRotate },
                )
            }
        }
        // ---- 展开区: IN/OUT 卡片 ----
        AnimatedVisibility(
            visible = open && expandable,
            enter = expandVertically(tween(250)) + fadeIn(tween(250)),
            exit = shrinkVertically(tween(250)) + fadeOut(tween(250)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(expandBg, RoundedCornerShape(10.dp))
                    .padding(10.dp),
            ) {
                // IN 参数区
                if (block.extra.isNotEmpty()) {
                    InOutSection(label = "IN", text = block.extra, textSecondary = textSecondary)
                }
                // OUT 结果区
                if (block.result.isNotEmpty()) {
                    if (block.extra.isNotEmpty()) Spacer(Modifier.height(6.dp))
                    val isError = block.result.startsWith("❌")
                    val isOk = block.result == "✅"
                    InOutSection(
                        label = "OUT",
                        text = block.result,
                        textColor = when {
                            isError -> errorColor
                            isOk -> successColor
                            else -> textSecondary
                        },
                    )
                }
            }
        }
    }
}

/** IN/OUT 区域: 标签行 + 内容。 */
@Composable
private fun InOutSection(label: String, text: String, textColor: Color = Color.Unspecified, textSecondary: Color = textColor) {
    val actualColor = if (textColor == Color.Unspecified) textSecondary else textColor
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = textSecondary.copy(alpha = 0.6f),
            modifier = Modifier.width(28.dp),
        )
        Text(
            text = text,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = actualColor,
            maxLines = 12,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 构建工具行摘要文本(对齐 Web UI ToolRow collapsed summary)。 */
private fun buildToolSummary(block: MessageBlock): String {
    val args = block.extra
    if (args.isEmpty()) return ""
    // todo_write: 解析 {todos:[...]} 显示统计
    if (block.toolKind == "todo_write") {
        return try {
            val obj = org.json.JSONObject(args)
            val todos = obj.optJSONArray("todos") ?: return briefArgs(args)
            var done = 0; var active = 0; var firstActive = ""
            for (i in 0 until todos.length()) {
                val item = todos.optJSONObject(i) ?: continue
                val s = item.optString("status", "")
                if (s == "completed") done++
                else if (s == "in_progress") {
                    active++
                    if (firstActive.isEmpty()) firstActive = item.optString("content", "")
                }
            }
            val head = "$done/${todos.length()} 已完成"
            if (active > 0 && firstActive.isNotEmpty()) {
                val extra = if (active > 1) " +${active - 1}" else ""
                "$head · $firstActive$extra"
            } else head
        } catch (_: Exception) { briefArgs(args) }
    }
    // 按工具类型从参数中提取人类可读摘要
    return try {
        val obj = org.json.JSONObject(args)
        when (block.toolKind) {
            "bash", "pwsh" -> {
                // 命令行:取 command 字段,截断首行
                val cmd = obj.optString("command", "")
                if (cmd.isNotEmpty()) {
                    val first = cmd.lineSequence().firstOrNull()?.trim() ?: cmd
                    if (first.length <= 60) first else first.take(57) + "..."
                } else briefArgs(args)
            }
            "read" -> {
                // 文件路径:取 file_path 尾部
                val path = obj.optString("file_path", "")
                if (path.isNotEmpty()) path.substringAfterLast("/").ifEmpty { path }
                else briefArgs(args)
            }
            "write", "edit" -> {
                val path = obj.optString("file_path", "")
                if (path.isNotEmpty()) path.substringAfterLast("/").ifEmpty { path }
                else briefArgs(args)
            }
            "grep", "glob", "search" -> {
                val pattern = obj.optString("pattern", obj.optString("query", ""))
                if (pattern.isNotEmpty()) {
                    if (pattern.length <= 50) pattern else pattern.take(47) + "..."
                } else briefArgs(args)
            }
            "web_search" -> {
                val queries = obj.optJSONArray("queries")
                if (queries != null && queries.length() > 0) {
                    val q = queries.optString(0, "")
                    if (q.length <= 50) q else q.take(47) + "..."
                } else briefArgs(args)
            }
            "web_fetch" -> {
                val url = obj.optString("url", "")
                if (url.isNotEmpty()) {
                    // 去掉协议前缀,截断
                    val clean = url.removePrefix("https://").removePrefix("http://")
                    if (clean.length <= 50) clean else clean.take(47) + "..."
                } else briefArgs(args)
            }
            "subagent", "subagent_fork" -> {
                val prompt = obj.optString("prompt", obj.optString("description", ""))
                if (prompt.isNotEmpty()) {
                    val first = prompt.lineSequence().firstOrNull()?.trim() ?: prompt
                    if (first.length <= 50) first else first.take(47) + "..."
                } else briefArgs(args)
            }
            "think" -> "" // think 无参数
            "skill" -> {
                val name = obj.optString("name", "")
                if (name.isNotEmpty()) name else briefArgs(args)
            }
            else -> briefArgs(args)
        }
    } catch (_: Exception) { briefArgs(args) }
}

/** 参数 JSON 的简短摘要(兜底):去掉大括号/引号,截断 50 字符。 */
private fun briefArgs(args: String): String {
    if (args.isEmpty()) return ""
    val clean = args.replace(Regex("[{}\"\\[\\]]"), "").trim()
    if (clean.isEmpty()) return ""
    return if (clean.length <= 50) clean else clean.take(47) + "..."
}

/** markdown-lite 正文:代码围栏(等宽深底块)+ 行内 code/bold。 */
@Composable
private fun MarkdownBody(
    text: String,
    textPrimary: Color,
    textSecondary: Color,
) {
    val dark = LocalDarkTheme.current
    val codeBg = if (dark) CodeBgDark else CodeBgLight
    val inlineBg = if (dark) InlineCodeBgDark else InlineCodeBgLight
    val segments = splitCodeFences(text)

    Column(Modifier.fillMaxWidth()) {
        segments.forEach { (isFence, content) ->
            if (isFence) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(codeBg, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                ) {
                    LinkifyAnnotated(
                        annotated = AnnotatedString(content.trim('\n')),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        baseColor = textSecondary,
                        monospace = true,
                    )
                }
            } else if (content.isNotBlank()) {
                LinkifyAnnotated(
                    annotated = buildInline(content, inlineBg, textSecondary),
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    baseColor = textPrimary,
                )
            }
        }
    }
}

/** 拆分行内代码围栏(```...```),返回 (是否围栏, 内容) 序列。 */
private fun splitCodeFences(text: String): List<Pair<Boolean, String>> {
    val result = mutableListOf<Pair<Boolean, String>>()
    val fence = "```"
    var i = 0
    while (i < text.length) {
        val idx = text.indexOf(fence, i)
        if (idx < 0) {
            result.add(false to text.substring(i))
            break
        }
        if (idx > i) result.add(false to text.substring(i, idx))
        val end = text.indexOf(fence, idx + 3)
        if (end < 0) {
            result.add(false to text.substring(idx))
            break
        }
        result.add(true to text.substring(idx + 3, end))
        i = end + 3
    }
    return result
}

/** 行内 markdown:加粗 **x**、行内代码 `x`(灰底)。 */
private fun buildInline(text: String, inlineBg: Color, monoColor: Color): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            if (text.startsWith("**", i)) {
                val end = text.indexOf("**", i + 2)
                if (end > 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                    continue
                }
            }
            if (text[i] == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > 0) {
                    withStyle(SpanStyle(background = inlineBg, fontFamily = FontFamily.Monospace, color = monoColor)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                    continue
                }
            }
            append(text[i])
            i++
        }
    }

/**
 * 路径可点击富文本:在已带行内样式的 AnnotatedString 之上,把识别出的文件路径
 * 叠加成「蓝色 + 下划线」链接;点击命中路径 → FileOpenKit 统一分发
 * (目录探测 → 文件浏览器 / 文本·图片内置查看 / APK 安装 / 系统应用打开)。
 */
@Composable
private fun LinkifyAnnotated(
    annotated: AnnotatedString,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    baseColor: Color,
    monospace: Boolean = false,
) {
    val context = LocalContext.current
    val dark = LocalDarkTheme.current
    val pathBg = if (dark) PathBgDark else PathBgLight
    val pathFg = if (dark) PathFgDark else PathFgLight
    val text = annotated.text
    // 路径识别跑在渲染后的可见文本上(行内 code 反引号已剥除,偏移与 ClickableText 一致)
    val paths = remember(text) { FileOpenKit.findPaths(text) }
    val linked = remember(text, dark) {
        val builder = AnnotatedString.Builder()
        builder.append(annotated) // 保留 markdown 行内样式(bold/code 背景等)
        for (p in paths) {
            builder.addStyle(
                SpanStyle(background = pathBg, color = pathFg),
                p.start,
                p.end,
            )
        }
        builder.toAnnotatedString()
    }
    ClickableText(
        text = linked,
        style = TextStyle(
            fontSize = fontSize,
            lineHeight = lineHeight,
            color = baseColor,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        ),
        onClick = { offset ->
            paths.firstOrNull { offset >= it.start && offset < it.end }
                ?.let { FileOpenKit.openFromMessage(context, it.raw) }
        },
    )
}
