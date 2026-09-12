package com.aptuidsh.kui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aptuidsh.kui.ui.theme.LocalDarkTheme

/**
 * API Key 输入弹窗(点击消费统计弹窗中的「提供方」名称唤起)。
 *
 * <p>背景:APP 与 Termux 是不同 Android 沙箱(实测 UID 10199 vs 10328),
 * 而 DSH 凭据文件权限 0600 + 目录 0700,**APP 无法读取**。
 * 因此密钥改为经用户输入获取,并有两条落盘路径:
 * <ol>
 *   <li><b>写入 DSH 后端</b>(credentials.set RPC)——推荐,后端随后可用它做
 *       模型调用与余额查询,密钥不出本机;</li>
 *   <li><b>仅存 APP 本地</b>——后端不可用时兜底,APP 自行查余额。</li>
 * </ol>
 *
 * @param onSubmit 回调 (key, saveToBackend)。
 */
@Composable
fun ApiKeyDialog(
    providerLabel: String,
    initialKey: String,
    backendConfigured: Boolean,
    onSubmit: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val dark = LocalDarkTheme.current
    val textPrimary = if (dark) Color(0xFFE6EDE9) else Color(0xFF1B221F)
    val textSecondary = if (dark) Color(0xFF8C9A93) else Color(0xFF6B7684)
    val cardBg = if (dark) Color(0xFF1B221F) else Color(0xFFF7FBFA)
    val fieldBg = if (dark) Color(0xFF252D29) else Color(0xFFEDF3F0)
    val line = if (dark) Color(0xFF39423E) else Color(0xFFCFD8D3)
    val accent = Color(0xFFE5484D)
    var text by remember { mutableStateOf(initialKey) }
    var saveToBackend by remember { mutableStateOf(true) }
    var reveal by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(340.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(cardBg)
                .padding(18.dp),
        ) {
            Text("配置 API Key", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = textPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "提供方:$providerLabel",
                fontSize = 12.sp,
                color = textSecondary,
            )
            Spacer(Modifier.height(4.dp))
            // 解释为什么需要手输(避免用户困惑于"为什么不能自动读")
            Text(
                text = "APP 与 DSH 运行在不同的系统沙箱中,无法直接读取 DSH 的凭据文件," +
                    "因此需要在此输入一次。",
                fontSize = 11.sp,
                color = textSecondary,
                lineHeight = 15.sp,
            )
            Spacer(Modifier.height(14.dp))

            // 输入框
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(fieldBg)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = textPrimary),
                    cursorBrush = SolidColor(accent),
                    visualTransformation = if (reveal) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (text.isEmpty()) {
                            Text(
                                text = "sk-...",
                                fontSize = 13.sp,
                                color = textSecondary,
                            )
                        }
                        inner()
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (reveal) "隐藏" else "显示",
                    fontSize = 12.sp,
                    color = accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { reveal = !reveal }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            // 保存位置选择
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { saveToBackend = !saveToBackend }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (saveToBackend) "☑" else "☐",
                    fontSize = 15.sp,
                    color = accent,
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = "同时写入 DSH 后端(推荐)",
                        fontSize = 13.sp,
                        color = textPrimary,
                    )
                    Text(
                        text = if (backendConfigured)
                            "后端当前已配置;勾选将覆盖为本次输入的值"
                        else "写入后后端可正常调用模型",
                        fontSize = 11.sp,
                        color = textSecondary,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (saveToBackend) {
                    "密钥将保存到后端凭据存储;APP 本地也保存一份用于直接查询余额。"
                } else {
                    "密钥仅保存在 APP 本地(后端未配置时可用,但不影响后端调用模型)。"
                },
                fontSize = 11.sp,
                color = textSecondary,
                lineHeight = 15.sp,
            )

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (initialKey.isNotEmpty()) {
                    Text(
                        text = "清除",
                        fontSize = 13.sp,
                        color = textSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSubmit("", saveToBackend) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "取消",
                    fontSize = 13.sp,
                    color = textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onDismiss() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "保存",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (text.trim().isEmpty()) textSecondary else accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { if (text.trim().isNotEmpty()) onSubmit(text.trim(), saveToBackend) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
