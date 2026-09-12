package com.aptuidsh.kui.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aptuidsh.kui.R
import com.aptuidsh.kui.ui.theme.LocalDarkTheme

private val PrimaryBlue = Color(0xFF4D6BFE)
private val DangerRed = Color(0xFFE5484D)
private val TextSecondaryLight = Color(0xFF5B6478)
private val TextSecondaryDark = Color(0xFFA3B5AC)
private val InterjectBgLight = Color(0xFFF4FBF9)
private val InterjectBgDark = Color(0xFF1B221F)
private val InterjectSteeringLight = Color(0xFFE8F4FD)
private val InterjectSteeringDark = Color(0xFF1A2A3A)

/**
 * 插话悬浮球：透明图标，可拖动，点击展开为编辑器+发送按钮
 * 显示条件：设置开启 + 助手正在输出
 * 显示排队消息列表
 */
@Composable
fun InterjectFloatingBall(
    enabled: Boolean,
    isAssistantStreaming: Boolean,
    queueItems: List<QueueItem>,
    onSend: (String, String) -> Unit,
    onEditQueueItem: (QueueItem, String) -> Unit,
    onDeleteQueueItem: (QueueItem) -> Unit,
    onSteerQueueItem: (QueueItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = LocalDarkTheme.current
    val bg = if (dark) InterjectBgDark else InterjectBgLight
    val textSecondary = if (dark) TextSecondaryDark else TextSecondaryLight
    val steeringBg = if (dark) InterjectSteeringDark else InterjectSteeringLight
    
    // 过滤排队消息（只显示 queued 的消息，不显示 steering 的立即插入消息）
    val userQueueItems = remember(queueItems) {
        queueItems.filter { it.source == "user" && it.placement == "queued" }
    }

    // 显示条件：设置开启 + (助手正在输出 或 排队列表有未发送消息)
    if (!enabled || (!isAssistantStreaming && userQueueItems.isEmpty())) return
    
    var isExpanded by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    
    // 悬浮球位置（可拖动）
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    
    if (isExpanded) {
        // 展开状态：横幅状容器 + 排队消息列表
        Box(
            modifier = modifier
                .fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset { IntOffset(offsetX.toInt(), offsetY.toInt()) }
                    .width(300.dp)
                    .padding(end = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            offsetY += dragAmount.y
                        }
                    },
            ) {
                // 编辑器区域
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 图标（点击收起）
                    Image(
                        painter = painterResource(R.drawable.ic_interject),
                        contentDescription = "收起",
                        modifier = Modifier
                            .size(32.dp)
                            .clickable { isExpanded = false },
                        contentScale = ContentScale.Fit,
                    )
                    
                    Spacer(Modifier.width(8.dp))
                    
                    // 编辑器
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .background(Color.Gray.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        textStyle = TextStyle(fontSize = 14.sp, color = textSecondary),
                        cursorBrush = SolidColor(PrimaryBlue),
                        decorationBox = { innerTextField ->
                            Box {
                                if (text.isEmpty()) {
                                    Text(
                                        text = "输入插话内容...",
                                        fontSize = 14.sp,
                                        color = textSecondary.copy(alpha = 0.5f),
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                    
                    Spacer(Modifier.width(8.dp))
                    
                    // 发送按钮
                    TextButton(
                        onClick = {
                            if (text.isNotEmpty()) {
                                onSend(text, "")
                                text = ""
                            }
                        },
                        modifier = Modifier.height(40.dp),
                    ) {
                        Text("发送", color = PrimaryBlue)
                    }
                }
                
                // 排队消息列表（如果有排队消息，支持内部滚动）
                if (userQueueItems.isNotEmpty()) {
                    HorizontalDivider(color = Color.Gray.copy(alpha = 0.3f))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                    ) {
                        Text(
                            text = "排队消息 (${userQueueItems.size})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = textSecondary,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        
                        userQueueItems.forEach { item ->
                            QueueItemRow(
                                item = item,
                                onEdit = { onEditQueueItem(item, text) },
                                onDelete = { onDeleteQueueItem(item) },
                                onSteer = { onSteerQueueItem(item) },
                                textSecondary = textSecondary,
                            )
                        }
                    }
                }
            }
        }
    } else {
        // 收起状态：透明图标
        Box(
            modifier = modifier
                .fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset { IntOffset(offsetX.toInt(), offsetY.toInt()) }
                    .size(48.dp)
                    .padding(end = 16.dp)
                    .alpha(0.7f)
                    .clip(CircleShape)
                    .clickable { isExpanded = true }
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            offsetY += dragAmount.y
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_interject),
                    contentDescription = "插话",
                    modifier = Modifier.size(32.dp),
                    contentScale = ContentScale.Fit,
                )
                // 排队消息计数徽章
                if (userQueueItems.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(16.dp)
                            .background(DangerRed, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${userQueueItems.size}",
                            fontSize = 10.sp,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 排队消息行：显示消息文本 + 编辑/删除/立即插入按钮
 */
@Composable
private fun QueueItemRow(
    item: QueueItem,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSteer: () -> Unit,
    textSecondary: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(Color.Gray.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 消息文本
        Text(
            text = item.text,
            fontSize = 12.sp,
            color = textSecondary,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            maxLines = 2,
        )
        
        // 编辑按钮
        Text(
            text = "编辑",
            fontSize = 11.sp,
            color = PrimaryBlue,
            modifier = Modifier
                .clickable { onEdit() }
                .padding(horizontal = 4.dp),
        )
        
        // 删除按钮
        Text(
            text = "删除",
            fontSize = 11.sp,
            color = DangerRed,
            modifier = Modifier
                .clickable { onDelete() }
                .padding(horizontal = 4.dp),
        )
        
        // 立即插入按钮
        Text(
            text = "插入",
            fontSize = 11.sp,
            color = PrimaryBlue,
            modifier = Modifier
                .clickable { onSteer() }
                .padding(horizontal = 4.dp),
        )
    }
}

/**
 * 水平分割线
 */
@Composable
private fun HorizontalDivider(color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color),
    )
}
