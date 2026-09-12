package com.aptuidsh.kui.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aptuidsh.kui.ChatActivity
import com.aptuidsh.kui.R
import com.aptuidsh.kui.ui.theme.ErrorRed
import com.aptuidsh.kui.ui.theme.SuccessGreen

private const val DEFAULT_BASE_URL = "http://127.0.0.1:3081"

/**
 * 首屏:顶部连接状态条 → 品牌卡(含后端基址,标题行右侧内置透明图) → 后端信息卡
 * (host.describe 真实数据) → 错误卡(失败时) → 事件通道状态行 + 使用提示。
 *
 * <p>布局与文案对齐参考项目 dsh-android 的 ConnectionStatusView:
 * 状态点/状态文案三态切换、信息卡 label 96dp 左列 + 值右列、等宽字体展示路径。
 * 字体统一带轻微笔画阴影(立体但不夸张)。
 */
@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val state by viewModel.uiState.collectAsState()
    val channelHost by viewModel.channelHost.collectAsState()
    val channelMux by viewModel.channelMux.collectAsState()

    val dotColor = when (state) {
        is HomeUiState.Connected -> SuccessGreen
        is HomeUiState.Failed -> ErrorRed
        HomeUiState.Connecting -> MaterialTheme.colorScheme.primary
    }
    val statusText = when (state) {
        HomeUiState.Connecting -> "正在连接后端…"
        is HomeUiState.Connected -> "已连接 · DeepSeek Harness 在线"
        is HomeUiState.Failed -> "连接失败"
    }
    val baseUrl = (state as? HomeUiState.Connected)?.baseUrl ?: DEFAULT_BASE_URL
    val info = (state as? HomeUiState.Connected)?.info ?: BackendInfo("—", "—", "—", "—", "—", "—")
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        // 整体内容垂直居中(内容超高时仍可滚动)
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
            // ===== 顶部连接状态条 =====
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(color = dotColor, shape = CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = statusText,
                    style = softShadowed(MaterialTheme.typography.titleSmall),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.weight(1f))
                if (state is HomeUiState.Failed) {
                    TextButton(onClick = viewModel::refresh) {
                        Text(
                            text = "重试",
                            style = softShadowed(MaterialTheme.typography.bodyMedium),
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // ===== 内置环境控制卡(proroot + Ubuntu + dsh 本体) =====
            EnvControlCard(
                onOpenConsole = {
                    context.startActivity(
                        Intent(context, com.aptuidsh.kui.EnvConsoleActivity::class.java),
                    )
                },
                onOpenWebUi = {
                    context.startActivity(
                        Intent(context, com.aptuidsh.kui.WebUiActivity::class.java),
                    )
                },
            )

            Spacer(Modifier.height(12.dp))

            // ===== 品牌卡 =====
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    // 标题行:APTUIDSH 左对齐,右侧靠齐内置透明背景图片
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "APTUIDSH",
                            style = softShadowed(MaterialTheme.typography.titleLarge),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.weight(1f))
                        TitleImage()
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "DeepSeek Harness · 原生安卓客户端",
                        style = softShadowed(MaterialTheme.typography.bodySmall),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(10.dp))
                    InfoRow(
                        label = "后端基址",
                        value = baseUrl,
                        valueColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        mono = true,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ===== 后端信息卡(host.describe 真实数据) =====
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text(
                        text = "后端信息",
                        style = softShadowed(MaterialTheme.typography.bodySmall),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InfoRow("版本", info.version, mono = true)
                    InfoRow("服务提供方", info.provider)
                    InfoRow("模型", info.model, mono = true)
                    InfoRow("工作目录", info.cwd, mono = true)
                    InfoRow("用户目录", info.home, mono = true)
                    InfoRow("已附加会话", info.attachedSessions)
                }
            }

            // ===== 横幅图(后端信息卡下方,事件通道上方;靠右对齐,点击进入子页面) =====
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                Image(
                    painter = painterResource(R.drawable.banner),
                    contentDescription = "横幅图",
                    modifier = Modifier
                        .height(48.dp)
                        .aspectRatio(250f / 100f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            context.startActivity(
                                Intent(context, ChatActivity::class.java),
                            )
                        },
                    contentScale = ContentScale.Fit,
                )
            }

            // ===== 错误卡(失败时展示) =====
            if (state is HomeUiState.Failed) {
                Spacer(Modifier.height(12.dp))
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Text(
                            text = "连接失败",
                            style = softShadowed(MaterialTheme.typography.titleSmall),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(6.dp))
                        SelectionContainer {
                            Text(
                                text = (state as HomeUiState.Failed).detail,
                                style = softShadowed(MaterialTheme.typography.bodySmall),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "事件通道: host $channelHost · mux $channelMux",
                style = softShadowed(MaterialTheme.typography.bodySmall),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "请确认 dsh 后端已在本机 $DEFAULT_BASE_URL 运行",
                style = softShadowed(MaterialTheme.typography.bodySmall),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            }
        }
    }
}

/** 标题行右侧图片:内置资源按比例显示(高 32dp,313:80),透明背景直接叠加。 */
@Composable
private fun RowScope.TitleImage() {
    Image(
        painter = painterResource(R.drawable.title_logo),
        contentDescription = null,
        modifier = Modifier
            .height(32.dp)
            .aspectRatio(313f / 80f),
        contentScale = ContentScale.Fit,
    )
}

/** 给文字样式附加轻微笔画阴影(下移 1dp + 2dp 模糊 + 20% 透明度,立体但不夸张)。 */
@Composable
private fun softShadowed(style: TextStyle): TextStyle {
    val density = LocalDensity.current
    val shadowColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.20f)
    val offset = with(density) { Offset(0.dp.toPx(), 1.dp.toPx()) }
    val blur = with(density) { 2.dp.toPx() }
    return style.copy(shadow = Shadow(color = shadowColor, offset = offset, blurRadius = blur))
}

/** 信息行:label 固定 96dp 左列 + 值右列(等宽字体展示路径/标识)。 */
@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    mono: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(96.dp),
            style = softShadowed(MaterialTheme.typography.bodyMedium),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                text = value,
                modifier = Modifier.weight(1f),
                style = softShadowed(MaterialTheme.typography.bodyMedium),
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                color = valueColor,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
