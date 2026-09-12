package com.aptuidsh.kui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aptuidsh.kui.ui.theme.LocalDarkTheme

// ==================== 交互卡片配色(无圆角、与侧边栏同背景) ====================
private val RCPrimary = Color(0xFF4D6BFE)
private val RCTextLight = Color(0xFF1A1D26)
private val RCTextDark = Color(0xFFE6F0EC)
private val RCSubLight = Color(0xFF5B6478)
private val RCSubDark = Color(0xFFA3B5AC)
private val RCBorderLight = Color(0xFFB8C4C0)
private val RCBorderDark = Color(0xFF3A4740)
private val RCError = Color(0xFFE5484D)
private val RCBtnHover = Color(0x334D6BFE)

/**
 * 答题/审批通用「请求卡片」:
 * 无圆角边框、背景与侧边栏一致(SidebarBg)、高度随内容自适应;
 * 由 MainContent 放置在内容区顶部、任务进度横条(TodoPanel)正下方,不随消息滚动。
 */

/** 提问卡片:支持一批问题逐题作答(选项单选/多选 + 自定义输入),最后一题答完统一提交。 */
@Composable
fun QuestionCard(
    request: QuestionRequest,
    onAnswered: (List<QuestionAnswer>) -> Unit,
    onCancelled: () -> Unit,
) {
    if (request.items.isEmpty()) return
    val dark = LocalDarkTheme.current
    val bg = if (dark) SidebarBgDark else SidebarBgLight
    val textPrimary = if (dark) RCTextDark else RCTextLight
    val textSecondary = if (dark) RCSubDark else RCSubLight
    val borderColor = if (dark) RCBorderDark else RCBorderLight

    var page by remember(request) { mutableStateOf(0) }
    val answers = remember(request) { mutableStateListOf<QuestionAnswer>() }
    val item = request.items[page]
    val multi = item.multiSelect
    // 选项:本身无 options 但有 plan-review 意图时,用其 approve 文案当作单选项
    val effectiveOptions = if (item.options.isEmpty() && item.approveIntent.isNotEmpty()) {
        listOf(QuestionOption(label = item.approveIntent))
    } else item.options
    var selected by remember(item.id, request.muxRpcId) { mutableStateOf(setOf<String>()) }
    var custom by remember(item.id, request.muxRpcId) { mutableStateOf("") }

    val isLast = page == request.items.size - 1
    val answerable = selected.isNotEmpty() || custom.trim().isNotEmpty()

    RequestCardRoot(bg = bg, borderColor = borderColor) {
        // ---- 标题区 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("❓", fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                "Agent 需要你回答",
                fontSize = 11.sp,
                color = textSecondary,
            )
            if (request.items.size > 1) {
                Spacer(Modifier.weight(1f))
                Text("${page + 1}/${request.items.size}", fontSize = 11.sp, color = textSecondary)
            }
        }
        if (item.header.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(item.header, fontSize = 12.sp, color = textSecondary)
        }
        Spacer(Modifier.height(6.dp))
        Text(item.question, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = textPrimary)
        if (item.detail.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(item.detail, fontSize = 12.sp, color = textSecondary)
        }

        // ---- 选项列表(自适应高度) ----
        if (effectiveOptions.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                effectiveOptions.forEach { opt ->
                    val checked = opt.label in selected
                    OptionRow(
                        label = opt.label,
                        description = opt.description,
                        checked = checked,
                        multi = multi,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        borderColor = borderColor,
                        onClick = {
                            selected = if (multi) {
                                if (checked) selected - opt.label else selected + opt.label
                            } else {
                                if (checked) emptySet() else setOf(opt.label)
                            }
                        },
                    )
                    Spacer(Modifier.height(2.dp))
                }
            }
        }

        // ---- 自定义输入(始终可用,与官方一致) ----
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .border(1.dp, borderColor, RectangleShape)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            if (custom.isEmpty()) {
                Text(
                    "输入自定义回答…",
                    fontSize = 13.sp,
                    color = textSecondary.copy(alpha = 0.7f),
                )
            }
            BasicTextField(
                value = custom,
                onValueChange = { custom = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(fontSize = 13.sp, color = textPrimary),
                cursorBrush = SolidColor(RCPrimary),
            )
        }

        // ---- 操作区 ----
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardTextAction(
                label = "取消",
                color = textSecondary,
                enabled = true,
                onClick = onCancelled,
            )
            Spacer(Modifier.width(12.dp))
            CardTextAction(
                label = if (!isLast) "下一题" else "回答",
                color = if (answerable) RCPrimary else textSecondary.copy(alpha = 0.5f),
                enabled = answerable,
                onClick = {
                    val customTrim = custom.trim()
                    val sel = if (customTrim.isNotEmpty() && !multi) emptyList() else selected.toList()
                    answers += QuestionAnswer(id = item.id, selected = sel, custom = customTrim)
                    if (isLast) {
                        onAnswered(answers.toList())
                    } else {
                        page += 1
                        selected = emptySet()
                        custom = ""
                    }
                },
            )
        }
    }
}

/** 权限审批卡片:与答题卡片同位置同样式(两者不会同时出现)。 */
@Composable
fun ApprovalCard(
    approval: ApprovalRequest,
    onDecide: (String) -> Unit,
) {
    val dark = LocalDarkTheme.current
    val bg = if (dark) SidebarBgDark else SidebarBgLight
    val textPrimary = if (dark) RCTextDark else RCTextLight
    val textSecondary = if (dark) RCSubDark else RCSubLight
    val borderColor = if (dark) RCBorderDark else RCBorderLight

    RequestCardRoot(bg = bg, borderColor = borderColor) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⚠️", fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            Text("权限审批请求", fontSize = 11.sp, color = textSecondary)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "工具「${approval.toolName}」请求执行",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = textPrimary,
        )
        if (approval.sessionId.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Text("会话: ${approval.sessionId.take(12)}…", fontSize = 11.sp, color = textSecondary)
        }
        if (approval.reason.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(approval.reason, fontSize = 12.sp, color = textSecondary)
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardTextAction(
                label = "拒绝",
                color = RCError,
                enabled = true,
                onClick = { onDecide("rejected") },
            )
            Spacer(Modifier.width(12.dp))
            CardTextAction(
                label = "允许一次",
                color = RCPrimary,
                enabled = true,
                onClick = { onDecide("allowed-once") },
            )
        }
    }
}

/** 卡片外壳:无圆角边框、背景与侧边栏一致,宽度铺满、高度随内容自适应。 */
@Composable
private fun RequestCardRoot(
    bg: Color,
    borderColor: Color,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RectangleShape)
            .background(bg, RectangleShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        content = content,
    )
}

/** 单个选项行(单选 ○/●、多选 ☐/☑)。 */
@Composable
private fun OptionRow(
    label: String,
    description: String,
    checked: Boolean,
    multi: Boolean,
    textPrimary: Color,
    textSecondary: Color,
    borderColor: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, if (checked) RCPrimary.copy(alpha = 0.8f) else borderColor, RectangleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when {
                multi && checked -> "☑"
                multi -> "☐"
                checked -> "●"
                else -> "○"
            },
            fontSize = 14.sp,
            color = if (checked) RCPrimary else textSecondary,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, color = textPrimary)
            if (description.isNotEmpty()) {
                Text(description, fontSize = 11.sp, color = textSecondary)
            }
        }
    }
}

/** 文本式操作按钮:矩形边框无圆角(与卡片风格一致),禁用时半透明不可点。 */
@Composable
private fun CardTextAction(
    label: String,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val dark = LocalDarkTheme.current
    Text(
        label,
        fontSize = 14.sp,
        color = color,
        modifier = Modifier
            .border(
                1.dp,
                if (dark) color.copy(alpha = if (enabled) 0.7f else 0.25f)
                else color.copy(alpha = if (enabled) 0.55f else 0.2f),
                RectangleShape,
            )
            .background(if (enabled) RCBtnHover.copy(alpha = if (enabled) 0.06f else 0f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}
