package com.aptuidsh.kui.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.aptuidsh.kui.ui.theme.LocalDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import android.graphics.BitmapFactory
import android.util.Base64
import com.aptuidsh.kui.R

// ==================== 输入卡片颜色(亮/暗双主题) ====================
/** 卡片背景:比内容区(#ECF2F0)稍微暗一点。 */
private val CardBgLight = Color(0xFFE2E9E6)
private val CardBgDark = Color(0xFF0C0F12)
private val TextPrimaryLight = Color(0xFF1A1D26)
private val TextPrimaryDark = Color(0xFFE6F0EC)
private val TextSecondaryLight = Color(0xFF5B6478)
private val TextSecondaryDark = Color(0xFFA3B5AC)
private val PlaceholderLight = Color(0xFF9AA3B2)
private val PlaceholderDark = Color(0xFF7A8881)
private val BorderLight = Color(0xFFB8C4C0)
private val BorderDark = Color(0xFF3A4740)
private val PrimaryBlue = Color(0xFF4D6BFE)
private val IconTintDark = Color(0xFFDCE8E3)
private val CtxBlockGray = Color(0xFF9AA3B2)
private val CtxBlockPurple = Color(0xFF9B6DFF)
private val CtxBlockBlue = Color(0xFF4D6BFE)

/** 编辑器两行基线高度(20sp 行高≈28dp × 2,默认显示两行)。 */
private val EditorMinHeight = 56.dp
/** 工具区固定高度:图标行 36 + 模型行 24 + 统计行(至多2行) 22 + 底部 2。 */
private val ToolAreaHeight = 84.dp

/** 统计占位文本(后端实时数据,暂占位)。 */
private const val STATS_TEXT =
    "16轮．200步|LLM 42m42s．工具调用45m11s|首token平均2s 127 tok/s|缓存命中 99%|输入 32.1M tok．输出 250K tok"

/**
 * 底部提示词输入容器(悬浮卡片)。
 *
 * <p>无圆角长方形卡片,背景比内容区稍暗,带轻微上投影;层级:内容区之上、侧边栏之下
 * (由 ChatScreen 的 zIndex 控制,侧边栏展开时遮挡卡片左侧)。
 *
 * <p>上半:文本编辑器(默认 3 行,输入增多自适应增高,最高 屏高/2,超出后内部滚动);
 * 下半:工具区(固定高)——
 * <ul>
 *   <li>行1: 命令菜单(14.png)/权限菜单(15.png)/上下文用量(16.png) + 右侧发送按钮</li>
 *   <li>行3: 模型「DeepSeek-V4-Flash (High)」+13.png,点击弹模型/推理一级菜单,二级子菜单叠加</li>
 *   <li>行4: 统计文本(真实 sessionStats 投影)</li>
 * </ul>
 * 全部真实接后端:发送 = session.prompt(queue);权限 = /permission 命令;
 * 模型/推理 = session.selectModel;统计/上下文 = 会话 projections。
 */
@Composable
fun Composer(
    modifier: Modifier = Modifier,
    maxHeight: Dp,
    compact: Boolean = false,
    onSend: (String) -> Unit = {},
    text: String = "",
    onTextChange: (String) -> Unit = {},
    model: String = "DeepSeek-V4-Flash",
    onModelChange: (String) -> Unit = {},
    reasoning: String = "High",
    onReasoningChange: (String) -> Unit = {},
    permission: String = "Full access",
    onPermissionChange: (String) -> Unit = {},
    // ---- 后端真实数据(官方输入条语义) ----
    models: List<ModelEntry> = emptyList(),          // session.models 目录
    onModelsReload: () -> Unit = {},                 // 打开模型菜单时重拉目录(官方 load-on-open)
    modelsLoading: Boolean = false,                  // 目录重拉中(菜单内提示「正在刷新模型列表…」)
    modelsError: String = "",                        // 目录加载/切换失败提示(菜单内错误条 + 重试)
    permissionOptions: List<Pair<String, String>> = emptyList(), // 权限选项(value→名称)
    statsText: String = "",                          // 实时统计行
    contextPercent: Float = -1f,                     // 上下文占用
    contextText: String = "",                        // 上下文说明
    sending: Boolean = false,                        // 发送中(running)→ 按钮变停止
    onStop: () -> Unit = {},                         // 停止当前运行
    onCommandPick: (String) -> Unit = {},            // 命令菜单选择(填入编辑器)
    commandCatalog: List<SlashCommandEntry> = emptyList(), // commands.list 命令目录
    onAttachmentClick: () -> Unit = {},              // 附件按钮点击 → 打开文件选择器
    drafts: List<DraftAttachment> = emptyList(),     // 附件草稿(发送前预览)
    onRemoveDraft: (String) -> Unit = {},            // 移除单个草稿附件
    promptError: String = "",                        // 输入错误横幅(发送失败时显示)
) {
    val dark = LocalDarkTheme.current
    val cardBg = if (dark) CardBgDark else CardBgLight
    val textPrimary = if (dark) TextPrimaryDark else TextPrimaryLight
    val textSecondary = if (dark) TextSecondaryDark else TextSecondaryLight
    val placeholder = if (dark) PlaceholderDark else PlaceholderLight
    val borderColor = if (dark) BorderDark else BorderLight
    val iconTint: Color? = if (dark) IconTintDark else null
    // 弹窗/菜单背景统一为侧边栏背景色
    val dialogBg = if (dark) SidebarBgDark else SidebarBgLight

    // 菜单显隐为组件内部状态;文本/模型/推理/权限由外部(会话状态)控制
    var commandMenu by remember { mutableStateOf(false) }
    var permMenu by remember { mutableStateOf(false) }
    var ctxDialog by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var modelSubMenu by remember { mutableStateOf(false) }
    var reasonSubMenu by remember { mutableStateOf(false) }

    // ---- 真实模型/推理展示(id ↔ 名称解析;无目录时回退占位列表) ----
    // 外部传入 model/reasoning 可能为名称(历史默认)或 id(后端),统一解析到 id/名称
    val effectiveModels = if (models.isNotEmpty()) models else emptyList()
    val modelId: String = effectiveModels.firstOrNull { it.modelName == model || it.modelId == model }?.modelId
        ?: model
    val modelDisplay: String = effectiveModels.firstOrNull { it.modelId == modelId }?.modelName
        ?: (model.ifBlank { "DeepSeek-V4-Flash" })
    val reasoningEffort: String = effectiveModels.firstOrNull { it.modelId == modelId }
        ?.efforts?.firstOrNull { it.first.equals(reasoning, ignoreCase = true) || it.second.equals(reasoning, ignoreCase = true) }?.first
        ?: reasoning
    val effortDisplay: String = effectiveModels.firstOrNull { it.modelId == modelId }
        ?.efforts?.firstOrNull { it.first.equals(reasoningEffort, ignoreCase = true) }?.second
        ?: (reasoning.ifBlank { "High" })

    // compact(引导层)无统计行,工具区收缩为 图标行36 + 模型行24 + 底部2;会话层展开 84
    // 工具区高度瞬间切换(避免布局动画与位移动画并发卡顿);16 图标与统计行淡入淡出
    val toolAreaH = if (compact) 62.dp else ToolAreaHeight
    val editorMax = maxHeight // maxHeight = screenH/3, editor expands up to 1/3 of screen
    // 编辑器滚动状态:达到最大高度后内部滚动(输入时滚动到底,最新内容可见)
    val editorScroll = rememberScrollState()
    LaunchedEffect(text) {
        if (editorScroll.maxValue > 0) {
            editorScroll.scrollTo(editorScroll.maxValue)
        }
    }

    Column(
        modifier = modifier
            .shadow(
                elevation = 8.dp,
                shape = RectangleShape,
                spotColor = Color.Black.copy(alpha = 0.12f),
            )
            .background(cardBg),
    ) {
        // ===== 附件草稿预览(编辑器上方;图片缩略图横排 / 文件列表) =====
        if (drafts.isNotEmpty()) {
            DraftAttachmentPreview(
                drafts = drafts,
                onRemoveDraft = onRemoveDraft,
                textSecondary = textSecondary,
                divider = borderColor,
            )
        }

        // ===== 输入错误横幅(发送失败时显示,无圆角边框透明背景) =====
        if (promptError.isNotEmpty()) {
            ComposerErrorBanner(promptError, textSecondary)
        }

        // ===== 上半: 文本编辑器(自适应增高向扩展,最多10行,超出内部滚动) =====
        // Box 负责高度约束+滚动,内部 BasicTextField 无约束自由撑高
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = EditorMinHeight, max = editorMax)
                .verticalScroll(editorScroll)
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        ) {
            // 占位文本(无输入时显示)
            if (text.isEmpty()) {
                Text("输入消息…", fontSize = 20.sp, color = placeholder)
            }
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, color = textPrimary),
                cursorBrush = SolidColor(PrimaryBlue),
            )
        }

        // ===== 下半: 工具区(固定高,发送按钮独立不占行) =====
        Box(
            Modifier
                .fillMaxWidth()
                .height(toolAreaH),
        ) {
            // 行1: 工具图标(左上,放大并上移,减少与编辑器间距)
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolIcon(R.drawable.ic_com_cmd, iconTint) { commandMenu = true }
                Spacer(Modifier.width(6.dp))
                ToolIcon(R.drawable.ic_com_perm, iconTint) { permMenu = true }
                // 16 上下文图标:仅会话层显示,切换时淡入淡出
                AnimatedVisibility(
                    visible = !compact,
                    enter = fadeIn(tween(250)),
                    exit = fadeOut(tween(250)),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(6.dp))
                        ToolIcon(R.drawable.ic_com_ctx, iconTint) { ctxDialog = true }
                    }
                }
                // 附件按钮:三个图标后面,打开文件选择器(引导层/会话层都显示)
                Spacer(Modifier.width(6.dp))
                ToolIcon(R.drawable.ic_com_attach, iconTint) { onAttachmentClick() }
            }
            // 发送按钮(独立容器,不占用行数;右上角;空文本禁用;运行中变「停止」)
            val canSend = text.isNotBlank()
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 16.dp, top = 0.dp)
                    .width(64.dp)
                    .height(48.dp)
                    .border(1.dp, if (sending) Color(0xFFE5533D) else borderColor)
                    .background(
                        if (sending) Color(0x14E5533D)
                        else if (canSend) PrimaryBlue.copy(alpha = 0.12f)
                        else Color.Transparent,
                    )
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = sending || canSend) {
                        if (sending) onStop() else onSend(text)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (sending) "停止" else "发送",
                    fontSize = 14.sp,
                    color = if (sending) Color(0xFFE5533D)
                    else if (canSend) PrimaryBlue else placeholder,
                )
            }
            // 模型行 + 统计行(底部起排,左对齐,统计为最后一行并随上面对齐截断)
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 6.dp),
            ) {
                // 模型选择(图标正下方第一列,靠左对齐)
                Row(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            modelMenu = true
                            // 官方语义:每次打开菜单都重新 load() 一次目录
                            // (ModelSelect.show() → reload()),而不是只吃挂载时的快照。
                            onModelsReload()
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "$modelDisplay ($effortDisplay)",
                        fontSize = 12.sp,
                        color = textPrimary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Image(
                        painter = painterResource(R.drawable.ic_jobs),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        contentScale = ContentScale.Fit,
                        colorFilter = iconTint?.let { ColorFilter.tint(it) },
                    )
                }
                Spacer(Modifier.height(2.dp))
                // 统计行(会话层显示,自动换行至多 2 行,仍超出截断;切换时淡入淡出)
                AnimatedVisibility(
                    visible = !compact,
                    enter = fadeIn(tween(250)),
                    exit = fadeOut(tween(250)),
                ) {
                    Text(
                        text = statsText.ifBlank { STATS_TEXT },
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = textSecondary,
                        maxLines = 2,
                        modifier = Modifier.clipToBounds(),
                    )
                }
            }
        }
    }

    // ==================== 菜单/弹窗(独立窗口,层级在卡片之上) ====================
    // 命令菜单(14.png):真实命令目录(commands.list);选择 → 填入编辑器(官方 popup 语义)
    if (commandMenu) {
        Dialog(onDismissRequest = { commandMenu = false }) {
            MenuCard(dialogBg, width = 300.dp) {
                Text("命令", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                Spacer(Modifier.height(8.dp))
                val commands = if (commandCatalog.isNotEmpty()) commandCatalog else emptyList()
                if (commands.isEmpty()) {
                    Text("命令目录加载中…", fontSize = 12.sp, color = textSecondary)
                } else {
                    commands.forEach { cmd ->
                        val token = "/" + cmd.name + (if (cmd.hint.isNotEmpty()) " " else "")
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    commandMenu = false
                                    onCommandPick(token)
                                }
                                .padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("/" + cmd.name, fontSize = 13.sp, color = PrimaryBlue)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (cmd.hint.isNotEmpty()) cmd.description + "  " + cmd.hint else cmd.description,
                                fontSize = 11.sp,
                                color = textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
    // 权限菜单(15.png):真实 options(后端 projections.permissions)
    if (permMenu) {
        Dialog(onDismissRequest = { permMenu = false }) {
            MenuCard(dialogBg, width = 260.dp) {
                Text("权限", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                Spacer(Modifier.height(8.dp))
                val options = if (permissionOptions.isNotEmpty()) permissionOptions else listOf(
                    "read-only" to "Read Only",
                    "workspace-write" to "Workspace Write",
                    "danger-full-access" to "Full access",
                )
                options.forEach { (value, name) ->
                    val currentId = permissionToId(permission)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onPermissionChange(value)
                                permMenu = false
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, fontSize = 13.sp, color = textPrimary, modifier = Modifier.weight(1f))
                        if (currentId == value) {
                            Text("✓", fontSize = 14.sp, color = PrimaryBlue)
                        }
                    }
                }
            }
        }
    }
    // 上下文用量描述框(16.png):真实 contextPressure/contextBreakdown 投影
    if (ctxDialog) {
        Dialog(onDismissRequest = { ctxDialog = false }) {
            MenuCard(dialogBg, width = 300.dp) {
                val pct = if (contextPercent >= 0f) contextPercent else 0f
                val pctInt = pct.toInt().coerceIn(0, 100)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("上下文已用 $pctInt%", fontSize = 13.sp, color = textPrimary, modifier = Modifier.weight(1f))
                    Text(contextText.ifBlank { "—" }, fontSize = 13.sp, color = textSecondary)
                }
                Spacer(Modifier.height(8.dp))
                // 进度条(主色细条 4dp)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (dark) Color(0xFF2A332E) else Color(0xFFD8DEE4)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(pct / 100f)
                            .fillMaxHeight()
                            .background(PrimaryBlue),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "(明细以服务端 projections 为准;上下文压力为实时估计)",
                    fontSize = 11.sp,
                    color = textSecondary,
                )
            }
        }
    }
    // 模型菜单(点击模型行弹出;一级: 模型/推理等级 + 二级子菜单叠加,数据来自 session.models)
    if (modelMenu) {
        Dialog(onDismissRequest = { modelMenu = false }) {
            MenuCard(dialogBg, width = 300.dp) {
                // 第一列: 模型
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { modelSubMenu = true }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("模型", fontSize = 13.sp, color = textSecondary, modifier = Modifier.weight(1f))
                    Text(modelDisplay, fontSize = 13.sp, color = textPrimary)
                    Spacer(Modifier.width(6.dp))
                    Image(
                        painter = painterResource(R.drawable.ic_com_model),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        contentScale = ContentScale.Fit,
                        colorFilter = iconTint?.let { ColorFilter.tint(it) },
                    )
                }
                // 第二列: 推理等级
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { reasonSubMenu = true }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("推理等级", fontSize = 13.sp, color = textSecondary, modifier = Modifier.weight(1f))
                    Text(effortDisplay, fontSize = 13.sp, color = textPrimary)
                    Spacer(Modifier.width(6.dp))
                    Image(
                        painter = painterResource(R.drawable.ic_com_model),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        contentScale = ContentScale.Fit,
                        colorFilter = iconTint?.let { ColorFilter.tint(it) },
                    )
                }
            }
        }
    }
    // 二级: 模型子菜单(真实目录;按 provider 分组展示)
    if (modelSubMenu) {
        Dialog(onDismissRequest = { modelSubMenu = false }) {
            MenuCard(dialogBg, width = 300.dp) {
                // 加载态与错误条(官方 ModelSelect 的 status.loading / error+Retry 语义):
                // 目录只信后端,不再回退到内置占位列表——占位列表会把已下线的旧模型
                // (deepseek-v4-flash 等)显示成可选,是"模型列表不实时"的可见症状之一。
                if (modelsLoading) {
                    Text("正在刷新模型列表…", fontSize = 12.sp, color = textSecondary)
                    Spacer(Modifier.height(6.dp))
                }
                if (modelsError.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = modelsError,
                            fontSize = 12.sp,
                            color = Color(0xFFE5533D),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "重试",
                            fontSize = 12.sp,
                            color = PrimaryBlue,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onModelsReload() }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
                val catalog = models
                if (catalog.isEmpty()) {
                    Text(
                        text = if (modelsLoading) "正在加载…" else "没有可用的模型。",
                        fontSize = 12.sp,
                        color = textSecondary,
                    )
                }
                val grouped = catalog.groupBy { it.providerName }
                grouped.forEach { (groupName, entries) ->
                    Text(groupName, fontSize = 12.sp, color = textSecondary)
                    entries.forEach { e ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    onModelChange(e.modelId)
                                    modelSubMenu = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(e.modelName, fontSize = 13.sp, color = textPrimary, modifier = Modifier.weight(1f))
                            if (modelId == e.modelId || model == e.modelName) {
                                Text("✓", fontSize = 14.sp, color = PrimaryBlue)
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
    // 二级: 推理等级子菜单(真实 efforts;随所选模型变化)
    if (reasonSubMenu) {
        Dialog(onDismissRequest = { reasonSubMenu = false }) {
            MenuCard(dialogBg, width = 220.dp) {
                val efforts = currentEfforts(models, modelId)
                if (efforts.isEmpty()) {
                    Text("当前模型未提供推理等级。", fontSize = 12.sp, color = textSecondary)
                }
                efforts.forEach { (id, name) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onReasoningChange(id)
                                reasonSubMenu = false
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, fontSize = 13.sp, color = textPrimary, modifier = Modifier.weight(1f))
                        if (reasoningEffort == id || reasoning.equals(name, ignoreCase = true)) {
                            Text("✓", fontSize = 14.sp, color = PrimaryBlue)
                        }
                    }
                }
            }
        }
    }
}

/** 工具区图标(36dp 触控,24dp 显示)。 */
@Composable
private fun ToolIcon(res: Int, tint: Color?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(res),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            contentScale = ContentScale.Fit,
            colorFilter = tint?.let { ColorFilter.tint(it) },
        )
    }
}

/** 圆角菜单卡片容器。 */
@Composable
private fun MenuCard(
    bg: Color,
    width: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bg),
    ) {
        Column(
            Modifier
                .width(width)
                .padding(16.dp),
        ) {
            content()
        }
    }
}

/** 上下文用量行:彩色块 + 标签 + 用量。 */
@Composable
private fun CtxRow(
    blockColor: Color,
    label: String,
    usage: String,
    textPrimary: Color,
    textSecondary: Color,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(blockColor),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 13.sp, color = textPrimary, modifier = Modifier.weight(1f))
        Text(usage, fontSize = 13.sp, color = textSecondary)
    }
}

/** 权限显示名 → 后端值 id(兼容旧显示名;未知原样返回)。 */
private fun permissionToId(display: String): String = when (display.trim()) {
    "Read Only" -> "read-only"
    "Workspace Write" -> "workspace-write"
    "Full access", "Full Access" -> "danger-full-access"
    else -> display.trim()
}

/** 当前模型可用的推理档位(模型 id 匹配;找不到回退空)。 */
private fun currentEfforts(models: List<ModelEntry>, modelId: String): List<Pair<String, String>> {
    val entry = models.firstOrNull { it.modelId == modelId } ?: models.firstOrNull()
    return entry?.efforts ?: emptyList()
}

/**
 * 附件草稿预览(编辑器上方)：提示行 + 图片缩略图横排(可横向滚动) + 文件列表。
 * 横幅条状无圆角透明背景。
 */
@Composable
private fun DraftAttachmentPreview(
    drafts: List<DraftAttachment>,
    onRemoveDraft: (String) -> Unit,
    textSecondary: Color,
    divider: Color,
) {
    val images = drafts.filter { it.isImage }
    val files = drafts.filter { !it.isImage }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        // 提示行
        Text(
            text = "📎 已预选 ${drafts.size} 个文件，点击发送可上传",
            fontSize = 11.sp,
            color = textSecondary,
        )
        Spacer(Modifier.height(4.dp))

        // 图片缩略图横排(横向滚动)
        if (images.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                images.forEach { img ->
                    DraftImageThumb(img, onRemoveDraft)
                    Spacer(Modifier.width(6.dp))
                }
            }
        }

        // 文件列表(纵向)
        if (files.isNotEmpty()) {
            if (images.isNotEmpty()) Spacer(Modifier.height(4.dp))
            files.forEach { f ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("📄", fontSize = 13.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = f.name,
                        fontSize = 12.sp,
                        color = textSecondary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "✕",
                        fontSize = 12.sp,
                        color = textSecondary,
                        modifier = Modifier
                            .padding(horizontal = 6.dp)
                            .clickable { onRemoveDraft(f.id) },
                    )
                }
            }
        }
    }
}

/** 单个图片草稿缩略图(56dp) + 右上角移除按钮。 */
@Composable
private fun DraftImageThumb(img: DraftAttachment, onRemove: (String) -> Unit) {
    val bitmap = remember(img.data) {
        try {
            val bytes = Base64.decode(img.data, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }
    Box(
        modifier = Modifier.size(56.dp),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = img.name,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(4.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Gray.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("📷", fontSize = 20.sp)
            }
        }
        // 右上角移除按钮
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                .clickable { onRemove(img.id) }
                .padding(2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", fontSize = 9.sp, color = Color.White)
        }
    }
}

/**
 * 输入错误横幅(发送失败时显示在编辑器上方):无圆角边框透明背景。
 */
@Composable
private fun ComposerErrorBanner(error: String, textSecondary: Color) {
    val dangerRed = Color(0xFFE5484D)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .border(1.dp, dangerRed.copy(alpha = 0.4f), RectangleShape)
            .background(Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⚠️", fontSize = 13.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            text = error,
            fontSize = 12.sp,
            color = dangerRed,
        )
    }
}
