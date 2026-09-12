package com.aptuidsh.kui.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aptuidsh.kui.ui.theme.LocalDarkTheme
import org.json.JSONArray
import org.json.JSONObject

// ---- 颜色(对齐 Web UI TodoPanel) ----
private val SuccessGreen = Color(0xFF2E7D32)
private val SuccessGreenDark = Color(0xFF66BB6A)
private val ProgressBlue = Color(0xFF1976D2)
private val ProgressBlueDark = Color(0xFF42A5F5)
private val PendingGray = Color(0xFFBDBDBD)
private val PanelBgLight = Color(0xFFF5F8F6)
private val PanelBgDark = Color(0xFF1A2420)
private val PanelBorderLight = Color(0xFFD5DDD9)
private val PanelBorderDark = Color(0xFF2A3A32)
private val ItemTextLight = Color(0xFF3D4A42)
private val ItemTextDark = Color(0xFFB8C8BE)

// ---- 数据模型 ----

data class TodoItem(
    val content: String,
    val status: String,  // "completed" | "in_progress" | "pending"
)

/** 从 todo_write 工具的 args JSON 解析任务列表。 */
fun parseTodoItems(argsJson: String): List<TodoItem> {
    return try {
        val obj = JSONObject(argsJson)
        val arr = obj.optJSONArray("todos") ?: return emptyList()
        val items = ArrayList<TodoItem>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            items.add(
                TodoItem(
                    content = item.optString("content", ""),
                    status = item.optString("status", "pending"),
                ),
            )
        }
        items
    } catch (_: Exception) {
        emptyList()
    }
}

/** 从当前会话的消息列表中提取最新的 todo_write 参数并解析任务列表。 */
fun extractTodosFromMessages(messages: List<ChatMessage>): List<TodoItem> {
    // 从后往前找最后一个 todo_write 工具调用
    for (i in messages.indices.reversed()) {
        val msg = messages[i]
        for (j in msg.blocks.indices.reversed()) {
            val block = msg.blocks[j]
            if (block.type == "tool-call" && block.toolKind == "todo_write" && block.extra.isNotEmpty()) {
                val items = parseTodoItems(block.extra)
                if (items.isNotEmpty()) return items
            }
        }
    }
    return emptyList()
}

// ---- 组件 ----

/**
 * 任务清单面板(对齐 Web UI TodoPanel)。
 *
 * 贴在输入编辑器上方，显示 todo_write 的任务列表。
 * 收起态: ☑️ 图标 + "任务" + 统计摘要 + ▲/▼
 * 展开态: 任务列表(每项带状态图标)
 */
@Composable
fun TodoPanel(
    todos: List<TodoItem>,
    modifier: Modifier = Modifier,
) {
    if (todos.isEmpty()) return

    // 任务全部完成后 10 秒自动隐藏面板
    val allDone = todos.all { it.status == "completed" }
    var visible by remember { mutableStateOf(true) }
    // 用 todos 内容变化重置 visible(新任务列表到达时重新显示)
    LaunchedEffect(todos) {
        visible = true
    }
    LaunchedEffect(allDone) {
        if (allDone) {
            kotlinx.coroutines.delay(10_000L)
            visible = false
        } else {
            visible = true
        }
    }
    if (!visible) return

    val dark = LocalDarkTheme.current
    val panelBg = if (dark) PanelBgDark else PanelBgLight
    val borderColor = if (dark) PanelBorderDark else PanelBorderLight
    val textPrimary = if (dark) ItemTextDark else ItemTextLight
    val textSecondary = if (dark) ItemTextDark.copy(alpha = 0.7f) else ItemTextLight.copy(alpha = 0.6f)

    val done = todos.count { it.status == "completed" }
    val active = todos.count { it.status == "in_progress" }
    val pending = todos.size - done - active

    // 摘要文本
    val progressParts = mutableListOf<String>()
    if (done > 0) progressParts.add("$done 已完成")
    if (active > 0) progressParts.add("$active 进行中")
    if (pending > 0) progressParts.add("$pending 待处理")
    val progressText = progressParts.joinToString(" · ")

    var collapsed by remember { mutableStateOf(true) }
    val chevronRotation by animateFloatAsState(
        if (collapsed) 0f else 180f, tween(200), label = "todoChevron"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(12.dp), ambientColor = Color.Black.copy(alpha = 0.08f))
            .clip(RoundedCornerShape(12.dp))
            .background(panelBg)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp)),
    ) {
        // ---- 头部: 图标 + 标题 + 统计 + 箭头 ----
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { collapsed = !collapsed }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("☑️", fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "任务",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = textPrimary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = progressText,
                fontSize = 13.sp,
                color = textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "▲",
                fontSize = 10.sp,
                color = textSecondary,
                modifier = Modifier.graphicsLayer { rotationZ = chevronRotation },
            )
        }
        // ---- 展开态: 任务列表(可滚动) ----
        AnimatedVisibility(
            visible = !collapsed,
            enter = expandVertically(tween(200)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(200)) + fadeOut(tween(200)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                todos.forEach { item ->
                    TodoItemRow(item, textPrimary, textSecondary)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

/** 单条任务行: 状态图标 + 内容文本。 */
@Composable
private fun TodoItemRow(item: TodoItem, textPrimary: Color, textSecondary: Color) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusIcon(status = item.status, size = 16)
        Spacer(Modifier.width(10.dp))
        Text(
            text = item.content,
            fontSize = 13.sp,
            color = if (item.status == "completed") textSecondary else textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 状态图标(对齐 Web UI StatusGlyph)。 */
@Composable
private fun StatusIcon(status: String, size: Int = 14) {
    val dark = LocalDarkTheme.current
    when (status) {
        "completed" -> {
            // 绿色实心圆 + 白色勾
            val color = if (dark) SuccessGreenDark else SuccessGreen
            androidx.compose.foundation.Canvas(
                Modifier.size(size.dp),
            ) {
                drawCircle(color)
                // 简单勾: 两条线
                val cx = size * 0.48f
                val cy = size * 0.50f
                drawLine(
                    Color.White,
                    androidx.compose.ui.geometry.Offset(cx - size * 0.15f, cy),
                    androidx.compose.ui.geometry.Offset(cx - size * 0.02f, cy + size * 0.13f),
                    strokeWidth = size * 0.12f,
                )
                drawLine(
                    Color.White,
                    androidx.compose.ui.geometry.Offset(cx - size * 0.02f, cy + size * 0.13f),
                    androidx.compose.ui.geometry.Offset(cx + size * 0.18f, cy - size * 0.14f),
                    strokeWidth = size * 0.12f,
                )
            }
        }
        "in_progress" -> {
            // 蓝色旋转环(无限旋转动画)
            val color = if (dark) ProgressBlueDark else ProgressBlue
            val infiniteTransition = rememberInfiniteTransition(label = "progress")
            val angle by infiniteTransition.animateFloat(
                initialValue = 0f, targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
                label = "spin",
            )
            androidx.compose.foundation.Canvas(
                Modifier
                    .size(size.dp)
                    .rotate(angle),
            ) {
                drawArc(
                    color = color,
                    startAngle = 0f,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = size * 0.14f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    ),
                )
            }
        }
        else -> {
            // 灰色虚线圆(pending)
            val color = if (dark) PendingGray.copy(alpha = 0.6f) else PendingGray
            androidx.compose.foundation.Canvas(Modifier.size(size.dp)) {
                drawCircle(
                    color = color,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = size * 0.1f,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                            floatArrayOf(size * 0.18f, size * 0.14f),
                        ),
                    ),
                )
            }
        }
    }
}
