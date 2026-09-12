package com.aptuidsh.kui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aptuidsh.kui.ui.theme.LocalDarkTheme

private val PrimaryBlue = Color(0xFF4D6BFE)
private val DangerRed = Color(0xFFE5484D)
private val TextSecondaryLight = Color(0xFF5B6478)
private val TextSecondaryDark = Color(0xFFA3B5AC)
private val DividerLight = Color(0xFFD8DEE4)
private val DividerDark = Color(0xFF2A322E)

/** 设置弹窗左侧设置项(从上到下)。 */
private val SETTING_SECTIONS = listOf("Agent 预设", "权限", "插话", "语言", "外观")

/** 弹窗固定宽高(内容超出内部滚动)。 */
private val DIALOG_WIDTH = 340.dp
private val DIALOG_HEIGHT = 460.dp

/**
 * 设置弹窗:固定宽高容器;顶部标题「设置」→ 分割线 → 左侧设置菜单 + 右侧选项卡内容(内部滚动)。
 * 左侧菜单缩小,设置项浅灰分割线分隔、小字灰;描述行靠右显示且可横向滚动;
 * 右侧选项分割线分隔、小字灰,过长不换行支持横向滚动;
 * Agent 预设为「标题+°内置/自定义标志+描述+底部小字名字」卡片结构。
 */
@Composable
fun SettingsDialog(
    selectedSection: Int,
    onSectionChange: (Int) -> Unit,
    agentPreset: String,
    onAgentPresetChange: (String) -> Unit,
    permission: String,
    onPermissionChange: (String) -> Unit,
    interjectEnabled: Boolean,
    onInterjectEnabledChange: (Boolean) -> Unit,
    interjectMode: String,  // "queue" or "steer"
    onInterjectModeChange: (String) -> Unit,
    interjectAutoClear: Boolean = true,
    onInterjectAutoClearChange: (Boolean) -> Unit = {},
    language: String,
    onLanguageChange: (String) -> Unit,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    onDismiss: () -> Unit,
    // ---- 后端真实数据 ----
    presetList: List<AgentPresetEntry> = emptyList(),
    permissionOptions: List<Pair<String, String>> = emptyList(),
) {
    val dark = LocalDarkTheme.current
    val bg = if (dark) SidebarBgDark else SidebarBgLight
    val textSecondary = if (dark) TextSecondaryDark else TextSecondaryLight
    val divider = if (dark) DividerDark else DividerLight

    // 当前选项卡描述(显示在顶部标题行右侧)
    val currentDescription = when (selectedSection) {
        0 -> "对此后新建的会话生效。运行中的会话保持它开始时的预设。"
        1 -> "选择新会话的默认权限模式"
        2 -> "助手输出时将消息插入上下文"
        3 -> "切换主题语言，选择立刻生效。"
        else -> "切换主题立刻生效。"
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = bg),
        ) {
            Column(
                Modifier
                    .width(DIALOG_WIDTH)
                    .height(DIALOG_HEIGHT),
            ) {
                // ===== 第一行:左侧「设置」标题 + 右侧靠右描述(随选项卡,可横向滚动) =====
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "设置",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = textSecondary,
                    )
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        Text(
                            text = currentDescription,
                            fontSize = 11.sp,
                            color = textSecondary,
                            maxLines = 1,
                        )
                    }
                }
                // ===== 第二行:上下区域分割线 =====
                HorizontalDivider(color = divider)

                // ===== 左侧设置菜单 + 右侧内容区(内部滚动) =====
                Row(Modifier.weight(1f)) {
                    // 左栏(缩小约 92dp;设置项分割线分隔,小字灰)
                    Column(
                        Modifier
                            .width(92.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                    ) {
                        SETTING_SECTIONS.forEachIndexed { i, name ->
                            Text(
                                text = name,
                                fontSize = 12.sp,
                                fontWeight = if (i == selectedSection) FontWeight.Bold else FontWeight.Normal,
                                color = if (i == selectedSection) PrimaryBlue else textSecondary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (i == selectedSection) PrimaryBlue.copy(alpha = 0.08f)
                                        else Color.Transparent,
                                    )
                                    .clickable { onSectionChange(i) }
                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                            )
                            if (i < SETTING_SECTIONS.size - 1) {
                                HorizontalDivider(color = divider)
                            }
                        }
                    }

                    // 右栏(内容区,内部滚动;选项分割线分隔,小字灰,过长横滚)
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        when (selectedSection) {
                            0 -> {
                                // Agent 预设选项:从后端 presetList 获取
                                val presetOptions = if (presetList.isNotEmpty()) {
                                    presetList
                                } else {
                                    // 默认值(后端未加载时)
                                    listOf(
                                        AgentPresetEntry("standard", "标准模式", "功能完整的编码 Agent"),
                                        AgentPresetEntry("ag", "AG 模式", "标准模式全部能力 + AG v2.0 手机自动化控制"),
                                    )
                                }
                                PresetList(
                                    options = presetOptions,
                                    selected = agentPreset,
                                    onSelect = onAgentPresetChange,
                                    divider = divider,
                                )
                            }
                            1 -> {
                                // 权限选项:从后端 permissionOptions 获取,或使用默认值
                                val permOptions = if (permissionOptions.isNotEmpty()) {
                                    permissionOptions.map { (value, name) -> name to value }
                                } else {
                                    listOf(
                                        "只读" to "read-only",
                                        "工作区写入" to "workspace-write",
                                        "完全访问" to "danger-full-access",
                                    )
                                }
                                // 将显示名映射为后端值,用于正确匹配选中状态
                                val permBackendValue = when (permission) {
                                    "Read Only" -> "read-only"
                                    "Workspace Write" -> "workspace-write"
                                    "Full access" -> "danger-full-access"
                                    else -> permission
                                }
                                OptionList(
                                    options = permOptions,
                                    selected = permBackendValue,
                                    onSelect = onPermissionChange,
                                    divider = divider,
                                )
                            }
                            2 -> {
                                // 插话设置
                                InterjectSettings(
                                    enabled = interjectEnabled,
                                    onEnabledChange = onInterjectEnabledChange,
                                    mode = interjectMode,
                                    onModeChange = onInterjectModeChange,
                                    autoClear = interjectAutoClear,
                                    onAutoClearChange = onInterjectAutoClearChange,
                                    textSecondary = textSecondary,
                                    divider = divider,
                                )
                            }
                            3 -> {
                                OptionList(
                                    options = listOf("中文" to "中文", "英文" to "英文"),
                                    selected = language,
                                    onSelect = onLanguageChange,
                                    divider = divider,
                                )
                            }
                            else -> {
                                OptionList(
                                    options = listOf(
                                        "浅色" to "light",
                                        "深色" to "dark",
                                        "跟随系统" to "system",
                                    ),
                                    selected = themeMode,
                                    onSelect = onThemeModeChange,
                                    divider = divider,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Agent 预设列表:每项「标题 + °内置/自定义标志 + 描述(小字浅灰) + 底部名字(很小)」;
 * 选项间分割线;选中项标题红色 + ✓;标题行过长可横向滚动。
 */
@Composable
private fun PresetList(
    options: List<AgentPresetEntry>,
    selected: String,
    onSelect: (String) -> Unit,
    divider: Color,
) {
    val dark = LocalDarkTheme.current
    val textSecondary = if (dark) TextSecondaryDark else TextSecondaryLight
    val dim = if (dark) TextSecondaryDark.copy(alpha = 0.75f) else TextSecondaryLight.copy(alpha = 0.75f)
    val faint = if (dark) TextSecondaryDark.copy(alpha = 0.55f) else TextSecondaryLight.copy(alpha = 0.55f)

    Column(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, p ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(p.id) }
                    .padding(top = 8.dp, bottom = 3.dp), // 底部收紧(名字行下方间距调小)
            ) {
                // 标题行:标题(选中红+✓) + °内置/自定义标志;过长横向滚动
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = p.name,
                        fontSize = 12.sp,
                        fontWeight = if (p.id == selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (p.id == selected) DangerRed else textSecondary,
                        maxLines = 1,
                    )
                    if (p.id == selected) {
                        Spacer(Modifier.width(4.dp))
                        Text("✓", fontSize = 12.sp, color = DangerRed)
                    }
                }
                if (p.description.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    // 描述(小字浅灰,后端拉取)
                    Text(
                        text = p.description,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        color = dim,
                    )
                }
                // 底部名字(很小,靠右;行高=字号 9sp,紧贴描述无间距)
                Text(
                    text = p.id,
                    fontSize = 9.sp,
                    lineHeight = 9.sp,
                    color = faint,
                    modifier = Modifier.align(Alignment.End),
                )
            }
            if (i < options.size - 1) {
                HorizontalDivider(color = divider)
            }
        }
    }
}

/**
 * 选项列表:每项分割线分隔;文字缩小加灰;被选项红色 + ✓;
 * 过长选项不换行(maxLines=1),支持横向滚动查看完整文本。
 */
@Composable
private fun OptionList(
    options: List<Pair<String, String>>, // (显示 label, 实际值)
    selected: String,
    onSelect: (String) -> Unit,
    divider: Color,
) {
    val dark = LocalDarkTheme.current
    val textSecondary = if (dark) TextSecondaryDark else TextSecondaryLight

    Column(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (label, value) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(value) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 右侧内容(短文本靠右;超长时内部横向滚动,不换行)
                Spacer(Modifier.weight(1f))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = if (value == selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (value == selected) DangerRed else textSecondary,
                        maxLines = 1,
                    )
                    if (value == selected) {
                        Spacer(Modifier.width(5.dp))
                        Text("✓", fontSize = 12.sp, color = DangerRed)
                    }
                }
            }
            if (i < options.size - 1) {
                HorizontalDivider(color = divider)
            }
        }
    }
}

/**
 * 方形无圆角开关：关闭时灰色边框，开启时红色边框+红色填充，透明背景。
 */
@Composable
private fun SquareSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val borderColor = if (checked) DangerRed else TextSecondaryLight.copy(alpha = 0.5f)
    val bgColor = if (checked) DangerRed.copy(alpha = 0.15f) else Color.Transparent
    val textColor = if (checked) DangerRed else TextSecondaryLight
    Box(
        modifier = Modifier
            .padding(2.dp)
            .border(1.dp, borderColor, RoundedCornerShape(0.dp))
            .background(bgColor, RoundedCornerShape(0.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (checked) "ON" else "OFF",
            fontSize = 10.sp,
            color = textColor,
        )
    }
}

/**
 * 插话设置：开关 + 自动清空 + 模式选择（排队等待/立即插入上下文）
 * 布局：启用插话 → 自动清空 → 分割线 → 排队等待 → 分割线 → 立即插入
 */
@Composable
private fun InterjectSettings(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    mode: String,
    onModeChange: (String) -> Unit,
    autoClear: Boolean,
    onAutoClearChange: (Boolean) -> Unit,
    textSecondary: Color,
    divider: Color,
) {
    Column(Modifier.fillMaxWidth()) {
        // 第一行：启用插话开关
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "启用插话功能",
                fontSize = 12.sp,
                color = textSecondary,
                modifier = Modifier.weight(1f),
            )
            SquareSwitch(checked = enabled, onCheckedChange = onEnabledChange)
        }

        // 第二行：输出流终止时清空队列（始终可见，不受 enabled 控制）
        HorizontalDivider(color = divider)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "输出流终止时清空队列",
                fontSize = 12.sp,
                color = textSecondary,
                modifier = Modifier.weight(1f),
            )
            SquareSwitch(checked = autoClear, onCheckedChange = onAutoClearChange)
        }
        
        // 启用后显示模式选择
        if (enabled) {
            HorizontalDivider(color = divider)
            
            // 排队等待模式
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onModeChange("queue") }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "排队等待",
                    fontSize = 12.sp,
                    color = textSecondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (mode == "queue") "✓" else "",
                    fontSize = 12.sp,
                    color = DangerRed,
                )
            }
            
            HorizontalDivider(color = divider)
            
            // 立即插入上下文模式
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onModeChange("steer") }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "立即插入上下文",
                    fontSize = 12.sp,
                    color = textSecondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (mode == "steer") "✓" else "",
                    fontSize = 12.sp,
                    color = DangerRed,
                )
            }
        }
    }
}
