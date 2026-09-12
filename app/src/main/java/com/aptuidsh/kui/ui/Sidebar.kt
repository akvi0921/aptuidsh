package com.aptuidsh.kui.ui

import android.content.res.Configuration
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.aptuidsh.kui.ui.theme.LocalDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.aptuidsh.kui.R
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// ==================== 侧边栏颜色(亮/暗双主题) ====================
/** 侧边栏背景(收纳/展开统一);各弹窗/菜单背景与之一致。 */
internal val SidebarBgLight = Color(0xFFF4FBF9)
internal val SidebarBgDark = Color(0xFF1B221F)
private val SidebarDividerLight = Color(0xFFD9E7E0)
private val SidebarDividerDark = Color(0xFF2B3630)
private val LabelSecondaryLight = Color(0xFF5B6478)
private val LabelSecondaryDark = Color(0xFFA3B5AC)
private val PlaceholderTextLight = Color(0xFF9AA3B2)
private val PlaceholderTextDark = Color(0xFF7A8881)
private val TextPrimaryLight = Color(0xFF1A1D26)
private val TextPrimaryDark = Color(0xFFE6F0EC)
/** 深色模式下 PNG/矢量图标的统一染色(浅色),浅色模式不染色保留原色。 */
private val IconTintDark = Color(0xFFDCE8E3)

/** 过渡动画:250ms 快速慢出缓动(收纳↔展开)。 */
private val SideAnim = tween<Dp>(durationMillis = 250, easing = FastOutSlowInEasing)

/** 会话列表行高(绝对高度计算用)。 */
private val SessionRowHeight = 38.dp

/** 侧边栏目标宽度(自适应):竖屏收纳 屏宽/6、展开 屏宽×3/5;横屏收纳 屏宽/15、展开 屏宽/7。 */
internal fun sidebarTargetWidth(screenW: Dp, portrait: Boolean, expanded: Boolean): Dp =
    if (expanded) {
        if (portrait) screenW * 3f / 5f else screenW / 7f
    } else {
        if (portrait) screenW / 6f else screenW / 15f
    }

/**
 * 对话主界面侧边栏(支持系统深色模式)。
 *
 * <p>收纳态:窄栏,上半屏 4 图标(3 菜单/4 新建/5 工作区/6 搜索),下半屏 2 图标
 * (7 插件默认隐藏/8 设置);展开态:品牌横幅 + 9 收纳图标(右上,高度与品牌横幅一致) +
 * 10 新建横幅(4 图标移动到横幅居中) + 搜索框(默认隐藏,点 6 唤醒,下移展开动画) +
 * "会话列表"标题行(5/6 移为小图标靠右) + 底部设置行;7 插件行两态默认隐藏。
 *
 * <p>宽度自适应:竖屏收纳 屏宽/6、展开 屏宽×3/5;横屏收纳 屏宽/15、展开 屏宽/7。
 * 所有图标 x/y/尺寸/透明度均由 250ms 动画驱动,收纳↔展开连续过渡。
 * 底部元素一律用 {@code align(BottomStart)} 锚定真实底部边界,不依赖高度数值。
 *
 * <p>亮/暗配色:浅色 侧边栏 #F4FBF9、深色 #1B221F;右缘 1dp 浅色分隔线;
 * 深色模式下 4-9 PNG 与 10 横幅染色为浅色,3.svg 同样染色。
 */
@Composable
fun Sidebar(
    expanded: Boolean,
    onToggle: () -> Unit,
    onNewSession: () -> Unit,
    onWorkspace: () -> Unit,
    onSearchClick: () -> Unit,
    onSettings: () -> Unit,
    onMaterialClick: () -> Unit = {},
    /** 消费统计按钮(素材/模型图标正下方);点击弹出消费统计弹窗。 */
    onUsageClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    // 会话列表(展开态显示;点击切换会话)
    currentSessionId: String = "",
    onSessionClick: (String) -> Unit = {},
    // ---- 会话列表分组(官方 workspace 树:按工作区 / 单列表) ----
    groupBy: SessionGroupBy = SessionGroupBy.WORKSPACE,
    onGroupByChange: (SessionGroupBy) -> Unit = {},
    workspaceGroups: List<SessionGroup> = emptyList(),
    flatSessions: List<SessionState> = emptyList(),
    onToggleGroup: (String) -> Unit = {},
    runningSubCounts: Map<String, Int> = emptyMap(),
    // ---- 搜索功能(对齐官方 deriveSearchResults) ----
    searchText: String = "",
    onSearchTextChange: (String) -> Unit = {},
    searchResults: List<SessionState> = emptyList(),
) {
    val dark = LocalDarkTheme.current
    val sidebarBg = if (dark) SidebarBgDark else SidebarBgLight
    val dividerColor = if (dark) SidebarDividerDark else SidebarDividerLight
    val labelSecondary = if (dark) LabelSecondaryDark else LabelSecondaryLight
    val placeholder = if (dark) PlaceholderTextDark else PlaceholderTextLight
    val textPrimary = if (dark) TextPrimaryDark else TextPrimaryLight
    val iconTint: Color? = if (dark) IconTintDark else null

    val configuration = LocalConfiguration.current
    val screenW = configuration.screenWidthDp.dp
    val portrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    // ---- 目标宽度(自适应,不强制参数) ----
    val collapsedW = sidebarTargetWidth(screenW, portrait, false)
    val expandedW = sidebarTargetWidth(screenW, portrait, true)
    val width by animateDpAsState(
        targetValue = sidebarTargetWidth(screenW, portrait, expanded),
        animationSpec = SideAnim,
        label = "sidebarWidth",
    )

    val pad = 12.dp
    val touch = 48.dp
    val topPad = 14.dp
    val bottomPad = 12.dp

    BoxWithConstraints(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .background(sidebarBg),
    ) {
        val H = constraints.maxHeight.toFloat().dp // 侧边栏实际高度(会话列表高度参考)

        // ---- 展开态尺寸(目标宽度派生,全部自适应) ----
        val brandW = minOf(125.dp, expandedW - pad * 2f)        // 品牌横幅宽(1.png 313×80)
        val brandH = brandW * 80f / 313f
        val newBtnW = expandedW - pad * 2f                      // 新建横幅容器宽(比侧边栏小左右各 pad)
        // 会话列表行高(新建横幅/搜索框统一):保持上轮 20dp
        val titleH = 20.dp
        val newBtnH = titleH                                    // 新建横幅高度:与会话列表行相同
        val newBtnY = pad + brandH + 12.dp
        val searchH = titleH                                    // 搜索框高度:与会话列表行相同
        val searchY = newBtnY + newBtnH + 12.dp
        val bottomRowH = newBtnH                                // 底部行宽同新建横幅
        val bottomGap = 8.dp

        // ---- 收纳态位置(上半屏竖排,自顶部向下) ----
        val collX = (collapsedW - touch) / 2f
        val collIcon3Y = topPad
        val collIcon4Y = topPad + touch
        val collIcon5Y = topPad + touch * 2f
        val collIcon6Y = topPad + touch * 3f
        val collIcon10Y = topPad + touch * 4f  // 素材图标在搜索图标下方
        val collIcon11Y = topPad + touch * 5f  // 消费统计图标在素材(模型)图标正下方

        // ---- 底部元素:自底部向上的偏移量(align(BottomStart) + 负偏移) ----
        // 收纳态:8 贴底,7 在其上;展开态:8 在设置行内,7 在插件行内
        val icon8YBottom = if (expanded) bottomPad + (bottomRowH - touch) / 2f else bottomPad
        val icon7YBottom = if (expanded) {
            bottomPad + bottomRowH + bottomGap + (bottomRowH - touch) / 2f
        } else {
            bottomPad + touch
        }

        // ---- 展开态位置 ----
        // 收纳按钮:竖屏在侧边栏内部右上角;横屏展开时移到侧边栏外部贴右缘
        val expIcon9X = if (portrait) expandedW - touch - 8.dp else expandedW + 6.dp
        val expIcon9Y = pad
        val expIcon4X = pad + (newBtnW - touch) / 2f            // 4 图标 → 新建横幅居中
        val expIcon4Y = newBtnY + (newBtnH - touch) / 2f
        val smallTouch = 32.dp
        val expIcon5X = expandedW - pad - smallTouch * 2f       // 5/6 小图标靠右
        val expIcon6X = expandedW - pad - smallTouch
        val expIcon8X = pad + 8.dp                              // 8 图标 → 底部设置横幅左侧
        val expIcon7X = pad + 8.dp
        val expIcon10X = pad + 8.dp                             // 10 素材图标 → 展开态在设置行上方
        val expIcon11X = pad + 8.dp                             // 11 消费统计 → 展开态紧随 10 下方

        // 底部文字与图标的间距:竖屏三个中文文字宽,横屏一个文字空格
        val textGap = if (portrait) 42.dp else 14.dp

        // 4 图标展开态尺寸:高度 = 行高 - 2 物理像素,宽按 4.png(88×94)比例
        val densityPx = LocalDensity.current.density // px/dp
        val twoPx = (2f / densityPx).dp

        // ---- 搜索框状态(默认隐藏,点 6 唤醒/关闭) ----
        var searchVisible by remember { mutableStateOf(false) }
        // searchText 由外部参数控制(非内部状态)
        val focusRequester = remember { FocusRequester() }
        var focusRequested by remember { mutableStateOf(false) }

        // 搜索框展开高度动画(0→searchH 下移展开,反向即上移消失)
        val searchBoxH by animateDpAsState(
            targetValue = if (searchVisible) searchH else 0.dp,
            animationSpec = tween(250, easing = FastOutSlowInEasing),
            label = "searchBoxH",
        )
        val searchAlpha by animateFloatAsState(if (searchVisible) 1f else 0f, tween(250))

        // 标题行随搜索框展开被推下(或收回上移)
        val titleY = searchY + searchBoxH + 8.dp
        val expIcon56Y = titleY + (titleH - smallTouch) / 2f
        val expIcon10Y = titleY + titleH + 4.dp + SessionRowHeight * 18f + 8.dp  // 展开态在会话列表下方
        // 11 消费统计:展开态紧贴 10 下方一行(与 10 同列,间距 4dp)
        // 展开态 10 的显示高度固定 24dp,故此处直接用常量(icon10Show 的动画值在下方才声明)
        val expIcon11Y = expIcon10Y + 24.dp + 4.dp

        // 收纳时关闭搜索框;展开+可见+待聚焦 → 延迟聚焦
        LaunchedEffect(expanded) {
            if (!expanded) searchVisible = false
        }
        LaunchedEffect(expanded, searchVisible, focusRequested) {
            if (expanded && searchVisible && focusRequested) {
                delay(300) // 等宽度/展开动画完成后聚焦
                focusRequester.requestFocus()
                focusRequested = false
            }
        }
        val onSearchIcon = {
            if (!searchVisible) {
                searchVisible = true
                focusRequested = true
                onSearchClick() // 外部: expanded = true
            } else {
                searchVisible = false
            }
        }

        // ---- 动画值(250ms 连续) ----
        val icon3X by animateDpAsState(collX, SideAnim, label = "i3x")
        val icon3Y by animateDpAsState(collIcon3Y, SideAnim, label = "i3y")
        val icon3Alpha by animateFloatAsState(if (expanded) 0f else 1f, tween(250))
        val icon9X by animateDpAsState(expIcon9X, SideAnim, label = "i9x")
        val icon9Y by animateDpAsState(expIcon9Y, SideAnim, label = "i9y")
        val icon9Alpha by animateFloatAsState(if (expanded) 1f else 0f, tween(250))
        val icon4X by animateDpAsState(if (expanded) expIcon4X else collX, SideAnim, label = "i4x")
        val icon4Y by animateDpAsState(if (expanded) expIcon4Y else collIcon4Y, SideAnim, label = "i4y")
        val icon5X by animateDpAsState(if (expanded) expIcon5X else collX, SideAnim, label = "i5x")
        val icon5Y by animateDpAsState(if (expanded) expIcon56Y else collIcon5Y, SideAnim, label = "i5y")
        val icon6X by animateDpAsState(if (expanded) expIcon6X else collX, SideAnim, label = "i6x")
        val icon6Y by animateDpAsState(if (expanded) expIcon56Y else collIcon6Y, SideAnim, label = "i6y")
        val icon8X by animateDpAsState(if (expanded) expIcon8X else collX, SideAnim, label = "i8x")
        val icon8Yb by animateDpAsState(icon8YBottom, SideAnim, label = "i8yb")
        val icon7X by animateDpAsState(if (expanded) expIcon7X else collX, SideAnim, label = "i7x")
        val icon7Yb by animateDpAsState(icon7YBottom, SideAnim, label = "i7yb")
        val icon10X by animateDpAsState(if (expanded) expIcon10X else collX, SideAnim, label = "i10x")
        val icon10Y by animateDpAsState(if (expanded) expIcon10Y else collIcon10Y, SideAnim, label = "i10y")
        val icon11X by animateDpAsState(if (expanded) expIcon11X else collX, SideAnim, label = "i11x")
        val icon11Y by animateDpAsState(if (expanded) expIcon11Y else collIcon11Y, SideAnim, label = "i11y")
        val icon11Show by animateDpAsState(if (expanded) 24.dp else 40.dp, SideAnim, label = "i11s")
        val icon11Touch by animateDpAsState(if (expanded) 32.dp else 48.dp, SideAnim, label = "i11t")
        val icon10Show by animateDpAsState(if (expanded) 24.dp else 40.dp, SideAnim, label = "i10s")
        val icon10Touch by animateDpAsState(if (expanded) 32.dp else 48.dp, SideAnim, label = "i10t")
        // 4 图标:收纳 40dp 方形 → 展开高度=行高-2px、宽按 4.png 比例
        val icon4ShowH by animateDpAsState(
            if (expanded) titleH - twoPx else 40.dp,
            SideAnim,
            label = "i4h",
        )
        val icon4ShowW by animateDpAsState(
            if (expanded) (titleH - twoPx) * 88f / 94f else 40.dp,
            SideAnim,
            label = "i4w",
        )
        val icon5Show by animateDpAsState(if (expanded) 16.dp else 40.dp, SideAnim, label = "i5s")
        val icon6Show by animateDpAsState(if (expanded) 16.dp else 40.dp, SideAnim, label = "i6s")
        val icon7Show by animateDpAsState(if (expanded) 24.dp else 40.dp, SideAnim, label = "i7s")
        val icon8Show by animateDpAsState(if (expanded) 24.dp else 40.dp, SideAnim, label = "i8s")
        val icon5Touch by animateDpAsState(if (expanded) 32.dp else 48.dp, SideAnim, label = "i5t")
        val icon6Touch by animateDpAsState(if (expanded) 32.dp else 48.dp, SideAnim, label = "i6t")
        // ==================== 图标层 ====================
        // 3 菜单(仅收纳态,展开淡出;3.svg VectorDrawable)
        SideIcon(R.drawable.ic_side_menu, icon3X, icon3Y, null, 24.dp, 48.dp, icon3Alpha, iconTint, onClick = onToggle)
        // 5 工作区(收纳竖排 → 展开小图标移到"会话列表"行右侧;收纳态放大显示)
        SideIcon(R.drawable.ic_side_workspace, icon5X, icon5Y, null, icon5Show, icon5Touch, 1f, iconTint, onClick = onWorkspace)
        // 6 搜索(收纳竖排 → 展开小图标移到"会话列表"行右侧;点击唤醒/关闭搜索框并展开;收纳态放大显示)
        SideIcon(R.drawable.ic_side_search, icon6X, icon6Y, null, icon6Show, icon6Touch, 1f, iconTint, onClick = onSearchIcon)
        // 10 素材图标(收纳竖排 → 展开移到会话列表下方;点击弹出模型配置弹窗)
        SideIcon(R.drawable.ic_side_material, icon10X, icon10Y, null, icon10Show, icon10Touch, 1f, iconTint, onClick = onMaterialClick)
        // 11 消费统计(收纳在 10 正下方;展开在 10 下方一行;点击弹出消费统计弹窗)
        SideIcon(R.drawable.ic_side_usage, icon11X, icon11Y, null, icon11Show, icon11Touch, 1f, iconTint, onClick = onUsageClick)
        // 7 动态插件(默认隐藏;底部锚定,收纳在 8 上方,展开在插件行内)
        SideIcon(R.drawable.ic_side_plugin, icon7X, 0.dp, icon7Yb, icon7Show, 48.dp, 0f, iconTint, onClick = {})
        // 8 设置(底部锚定:收纳贴底,展开在设置行横幅左侧;收纳态放大显示)
        SideIcon(R.drawable.ic_side_settings, icon8X, 0.dp, icon8Yb, icon8Show, 48.dp, 1f, iconTint, onClick = onSettings)
        // 4 新建会话(收纳竖排 40dp → 展开移动到新建横幅居中,高度=行高-2px)
        Box(
            modifier = Modifier
                .offset(x = icon4X, y = icon4Y)
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onNewSession),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_side_new),
                contentDescription = null,
                modifier = Modifier.width(icon4ShowW).height(icon4ShowH),
                contentScale = ContentScale.Fit,
                colorFilter = iconTint?.let { ColorFilter.tint(it) },
            )
        }
        // 9 收纳(仅展开态,右上角,淡入;显示高度与品牌横幅一致)
        Box(
            modifier = Modifier
                .offset(x = icon9X, y = icon9Y)
                .size(48.dp)
                .alpha(icon9Alpha)
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = icon9Alpha > 0.05f, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_side_collapse),
                contentDescription = null,
                modifier = Modifier
                    .height(brandH)
                    .aspectRatio(178f / 135f),
                contentScale = ContentScale.Fit,
                colorFilter = iconTint?.let { ColorFilter.tint(it) },
            )
        }

        // ==================== 展开态内容层(AnimatedVisibility:收纳态移出组合树,不参与命中测试) ====================
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(250)),
            modifier = Modifier.matchParentSize(), // 确保覆盖整个 BoxWithConstraints
        ) {
        // 内层 Box 提供 BoxScope(align 用于底部锚定元素)
        Box(Modifier.fillMaxSize()) {
        // 品牌横幅 1.png(左上;品牌色,不染色)
        Box(Modifier.offset(pad, pad)) {
            Image(
                painter = painterResource(R.drawable.title_logo),
                contentDescription = null,
                modifier = Modifier.width(brandW).aspectRatio(313f / 80f),
                contentScale = ContentScale.Fit,
            )
        }
        // 新建会话横幅 10.png(4 图标移动至此居中;整幅可点击新建会话,收纳态隐藏不可点)
        Box(
            Modifier
                .offset(pad, newBtnY)
                .width(newBtnW)
                .height(newBtnH)
                
                .clickable(onClick = onNewSession),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_side_new_banner),
                contentDescription = "新建会话",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
                colorFilter = iconTint?.let { ColorFilter.tint(it) },
            )
        }
        // 会话搜索框(默认隐藏;点 6 唤醒,下移展开动画;无背景下划线编辑框)
        Box(Modifier.offset(pad, searchY).width(newBtnW).height(searchBoxH).alpha(searchAlpha)) {
            BasicTextField(
                value = searchText,
                onValueChange = onSearchTextChange,
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester),
                textStyle = TextStyle(fontSize = 14.sp, color = textPrimary),
                singleLine = true,
                cursorBrush = SolidColor(labelSecondary),
                decorationBox = { innerTextField ->
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            if (searchText.isEmpty()) {
                                Text("搜索会话…", fontSize = 14.sp, color = placeholder)
                            }
                            innerTextField()
                        }
                        // 清除按钮(有内容时显示)
                        if (searchText.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onSearchTextChange("") },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("✕", fontSize = 12.sp, color = placeholder)
                            }
                        }
                    }
                },
            )
            // 下划线(无背景,底部 1dp 线)
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(labelSecondary),
            )
        }
        // 标题行(5/6 小图标靠右;随搜索框展开下移):分组名可点弹「分组方式」菜单
        SessionTitleRow(
            groupBy = groupBy,
            onGroupByChange = onGroupByChange,
            titleY = titleY,
            titleH = titleH,
            pad = pad,
            labelSecondary = labelSecondary,
            textPrimary = textPrimary,
            surface = if (dark) Color(0xFF252E2A) else Color.White,
            divider = dividerColor,
            shadowColor = if (dark) Color.Black else Color(0x22000000),
        )
        // 会话列表(标题行下方;分组树/单列表/搜索结果,点击切换会话)
        SessionList(
            groupBy = groupBy,
            workspaceGroups = workspaceGroups,
            flatSessions = flatSessions,
            currentId = currentSessionId,
            onSessionClick = onSessionClick,
            onToggleGroup = onToggleGroup,
            runningSubCounts = runningSubCounts,
            searchText = searchText,
            searchResults = searchResults,
            modifier = Modifier
                .offset(x = pad, y = titleY + titleH + 4.dp)
                .width(newBtnW)
                // 绝对高度:竖屏 18 行 / 横屏 5 行,超出内部滚动
                .height(SessionRowHeight * if (portrait) 18f else 5f),
        )
        // 底部:设置行横幅(10.png 复用,宽度=搜索框宽;锚定底部)+ 渐显文字"设置"
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .offset(x = pad, y = -bottomPad)
                .width(newBtnW)
                .height(bottomRowH)
                ,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_side_new_banner),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
                colorFilter = iconTint?.let { ColorFilter.tint(it) },
            )
        }
        Text(
            text = "设置",
            fontSize = 13.sp,
            color = textPrimary,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(
                    x = pad + 8.dp + 24.dp + textGap, // 图标 + 间距(竖屏三字宽/横屏一字)
                    y = -(bottomPad + (bottomRowH - 18.dp) / 2f),
                )
                ,
        )
        // 底部:插件行(默认隐藏;锚定底部,位于设置行上方)
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .offset(x = pad, y = -(bottomPad + bottomRowH + bottomGap))
                .width(newBtnW)
                .height(bottomRowH)
                .alpha(0f),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_side_new_banner),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
                colorFilter = iconTint?.let { ColorFilter.tint(it) },
            )
        }
        Text(
            text = "Cordis Plugin",
            fontSize = 13.sp,
            color = textPrimary,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(
                    x = pad + 8.dp + 24.dp + textGap, // 图标 + 间距(竖屏三字宽/横屏一字)
                    y = -(bottomPad + bottomRowH + bottomGap + (bottomRowH - 18.dp) / 2f),
                )
                .alpha(0f),
        )

        }
        }

        // ==================== 右缘分隔线 ====================
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(1.dp)
                .fillMaxHeight()
                .background(dividerColor),
        )
    }
}

/**
 * 侧边栏图标:顶部坐标系(y 正向下)或底部锚定(yFromBottom 自底向上)定位 + 触控区 + 涟漪。
 * tint 非空时对图标染色(深色模式浅色图标)。
 */
@Composable
private fun BoxScope.SideIcon(
    res: Int,
    x: Dp,
    y: Dp,
    yFromBottom: Dp?,
    showSize: Dp,
    touchSize: Dp,
    alpha: Float,
    tint: Color?,
    onClick: () -> Unit,
) {
    val position = if (yFromBottom != null) {
        Modifier.align(Alignment.BottomStart).offset(x = x, y = -yFromBottom)
    } else {
        Modifier.offset(x = x, y = y)
    }
    Box(
        modifier = position
            .size(touchSize)
            .alpha(alpha)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = alpha > 0.05f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(res),
            contentDescription = null,
            modifier = Modifier.size(showSize),
            contentScale = ContentScale.Fit,
            colorFilter = tint?.let { ColorFilter.tint(it) },
        )
    }
}

/**
 * 会话列表标题行:「工作区」/「单列表」标题 + 右侧分组方式切换小按钮。
 * 点击分组名弹出 Popup(两档单选:按工作区 / 单列表)。
 */
@Composable
private fun BoxScope.SessionTitleRow(
    groupBy: SessionGroupBy,
    onGroupByChange: (SessionGroupBy) -> Unit,
    titleY: Dp,
    titleH: Dp,
    pad: Dp,
    labelSecondary: Color,
    textPrimary: Color,
    surface: Color,
    divider: Color,
    shadowColor: Color,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(IntOffset.Zero) }
    val density = LocalDensity.current
    // 标题文本:按当前分组方式显示「工作区」或「会话」
    val sectionLabel = if (groupBy == SessionGroupBy.WORKSPACE) "工作区" else "会话"
    Row(
        modifier = Modifier
            .offset(pad, titleY)
            .height(titleH)
            .onGloballyPositioned { coords ->
                // 记录锚点(Popup 用屏幕坐标,直接取 positionInWindow)
                val win = coords.positionInWindow()
                val dy = with(density) { (titleH + 8.dp).toPx() }
                anchor = IntOffset(win.x.roundToInt(), (win.y + dy).roundToInt())
            }
            .clickable { menuOpen = !menuOpen },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = sectionLabel,
            fontSize = 13.sp,
            color = labelSecondary,
        )
        Spacer(Modifier.width(4.dp))
        // 分组切换指示(下拉箭头:当前方式 + ▾)
        Text(
            text = "▾",
            fontSize = 10.sp,
            color = labelSecondary,
        )
    }
    if (menuOpen) {
        Popup(
            alignment = Alignment.TopStart,
            offset = anchor,
            onDismissRequest = { menuOpen = false },
            properties = PopupProperties(focusable = true),
        ) {
            // 菜单面板:两档单选(官方 ViewOptionsMenu:分组方式)
            Column(
                Modifier
                    .shadow(8.dp, RoundedCornerShape(10.dp), clip = false)
                    .background(surface, RoundedCornerShape(10.dp))
                    .padding(vertical = 6.dp)
                    .width(148.dp),
            ) {
                Text(
                    text = "分组方式",
                    fontSize = 11.sp,
                    color = labelSecondary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                GroupOptionRow("按工作区", groupBy == SessionGroupBy.WORKSPACE, textPrimary, divider) {
                    onGroupByChange(SessionGroupBy.WORKSPACE); menuOpen = false
                }
                GroupOptionRow("单列表", groupBy == SessionGroupBy.FLAT, textPrimary, divider) {
                    onGroupByChange(SessionGroupBy.FLAT); menuOpen = false
                }
            }
        }
    }
}

/** 分组方式单选行(左侧小圆点选中标记)。 */
@Composable
private fun GroupOptionRow(
    label: String,
    selected: Boolean,
    textPrimary: Color,
    divider: Color,
    onClick: () -> Unit,
) {
    val accent = Color(0xFF4D6BFE)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (selected) accent else Color.Transparent)
                .padding(if (selected) 3.dp else 0.dp),
        ) {
            if (!selected) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(divider.copy(alpha = 0.6f)),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            fontSize = 14.sp,
            color = if (selected) accent else textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 会话列表(标题行下方):
 * <ul>
 *   <li>搜索模式:搜索框有内容时显示搜索结果列表</li>
 *   <li>WORKSPACE — 官方工作区树:组头行(折叠/展开)+ 组内会话行;展开组才渲染成员。</li>
 *   <li>FLAT — 官方单列表:全部可见会话最新优先,无分组。</li>
 * </ul>
 * 组内会话行:运行中点 + 标题 + (父会话)运行子代理徽标 + 相对时间;点击切换会话。
 */
@Composable
private fun SessionList(
    groupBy: SessionGroupBy,
    workspaceGroups: List<SessionGroup>,
    flatSessions: List<SessionState>,
    currentId: String,
    onSessionClick: (String) -> Unit,
    onToggleGroup: (String) -> Unit,
    runningSubCounts: Map<String, Int>,
    modifier: Modifier = Modifier,
    searchText: String = "",
    searchResults: List<SessionState> = emptyList(),
) {
    val dark = LocalDarkTheme.current
    val textPrimary = if (dark) TextPrimaryDark else TextPrimaryLight
    val textSecondary = if (dark) LabelSecondaryDark else LabelSecondaryLight
    val primaryBlue = Color(0xFF4D6BFE)
    val groupBg = if (dark) Color(0xFF202923) else Color(0xFFEAF5F0)

    LazyColumn(modifier = modifier) {
        // 搜索模式:显示搜索结果
        if (searchText.isNotEmpty()) {
            if (searchResults.isEmpty()) {
                item {
                    Text(
                        text = "未找到匹配的会话",
                        fontSize = 13.sp,
                        color = textSecondary,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            } else {
                items(searchResults.size, key = { searchResults[it].id }) { index ->
                    SearchResultRow(
                        session = searchResults[index],
                        currentId = currentId,
                        searchText = searchText,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        primaryBlue = primaryBlue,
                        onClick = { onSessionClick(searchResults[index].id) },
                    )
                }
            }
            return@LazyColumn
        }

        if (groupBy == SessionGroupBy.FLAT) {
            items(flatSessions.size, key = { flatSessions[it].id }) { index ->
                SessionRow(
                    session = flatSessions[index],
                    currentId = currentId,
                    subCount = runningSubCounts[flatSessions[index].id] ?: 0,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    primaryBlue = primaryBlue,
                    onClick = { onSessionClick(flatSessions[index].id) },
                )
            }
            return@LazyColumn
        }
        // WORKSPACE 分组树
        workspaceGroups.forEach { group ->
            // 组头行(点击折叠/展开)
            item(key = "hdr-${group.key}") {
                val accent = primaryBlue
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(SessionRowHeight)
                        .background(groupBg)
                        .clickable { onToggleGroup(group.key) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (group.expanded) "▾" else "▸",
                        fontSize = 11.sp,
                        color = if (group.containsCurrent) accent else textSecondary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = group.label.ifBlank { UNGROUPED_LABEL },
                        fontSize = 13.sp,
                        color = if (group.containsCurrent) accent else textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${group.sessionCount} 会话",
                        fontSize = 11.sp,
                        color = textSecondary,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
            // 展开组才渲染成员行
            if (group.expanded) {
                items(group.sessions.size, key = { group.sessions[it].id }) { index ->
                    val session = group.sessions[index]
                    SessionRow(
                        session = session,
                        currentId = currentId,
                        subCount = runningSubCounts[session.id] ?: 0,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        primaryBlue = primaryBlue,
                        onClick = { onSessionClick(session.id) },
                        indent = true,
                    )
                }
            }
        }
    }
}

/** 一行会话:运行中点/标题/子代理徽标/相对时间。indent=组内成员缩进。 */
@Composable
private fun SessionRow(
    session: SessionState,
    currentId: String,
    subCount: Int,
    textPrimary: Color,
    textSecondary: Color,
    primaryBlue: Color,
    onClick: () -> Unit,
    indent: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(SessionRowHeight)
            .background(
                if (session.id == currentId) primaryBlue.copy(alpha = 0.08f)
                else Color.Transparent,
            )
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (indent) Spacer(Modifier.width(14.dp))
        // 运行中点
        Box(
            Modifier
                .padding(start = if (indent) 0.dp else 4.dp, end = 6.dp)
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (session.running) primaryBlue else Color.Transparent),
        )
        Text(
            text = session.title.ifBlank { "新会话" },
            fontSize = 13.sp,
            color = if (session.id == currentId) primaryBlue else textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (subCount > 0) {
            Text(
                text = "· $subCount 子代理",
                fontSize = 10.sp,
                color = textSecondary,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = relativeSessionTime(session.updatedAt),
            fontSize = 11.sp,
            color = textSecondary,
            modifier = Modifier.padding(end = 4.dp),
        )
    }
}

/**
 * 搜索结果行(对齐官方 SearchResultItem)。
 * 显示:标题 + 工作区名 + 相对时间;标题中匹配的文字高亮。
 */
@Composable
private fun SearchResultRow(
    session: SessionState,
    currentId: String,
    searchText: String,
    textPrimary: Color,
    textSecondary: Color,
    primaryBlue: Color,
    onClick: () -> Unit,
) {
    val title = session.title.ifBlank { "新会话" }
    val query = searchText.trim().lowercase()

    // 高亮匹配文字
    val highlightedTitle = buildAnnotatedString {
        val lowerTitle = title.lowercase()
        var start = 0
        var idx = lowerTitle.indexOf(query, start)
        while (idx >= 0) {
            append(title.substring(start, idx))
            withStyle(SpanStyle(color = primaryBlue, fontWeight = FontWeight.Bold)) {
                append(title.substring(idx, idx + query.length))
            }
            start = idx + query.length
            idx = lowerTitle.indexOf(query, start)
        }
        append(title.substring(start))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(SessionRowHeight + 8.dp)
            .background(
                if (session.id == currentId) primaryBlue.copy(alpha = 0.08f)
                else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        // 标题行:运行中点 + 标题(高亮匹配)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (session.running) primaryBlue else Color.Transparent),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = highlightedTitle,
                fontSize = 13.sp,
                color = textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        // 元信息行:工作区名 + 相对时间
        Row(
            modifier = Modifier.padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = relativeSessionTime(session.updatedAt),
                fontSize = 11.sp,
                color = textSecondary,
            )
        }
    }
}
