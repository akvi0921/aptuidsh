package com.aptuidsh.kui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aptuidsh.kui.net.BalanceClient
import com.aptuidsh.kui.ui.theme.LocalDarkTheme

/**
 * 消费统计弹窗(按设计草图布局)。
 *
 * <pre>
 * ┌──────────────────────────────┐
 * │ 消费统计                      │
 * │ 提供方  deepseek    余额: 0.00│  ← 提供方可点击 → 弹密钥输入框
 * ├──────────────────────────────┤
 * │  输入  │  命中  │ 未命中 │ 总输出│  ← 四列(竖线分隔)
 * ├──────────────────────────────┤
 * │  轮次  │  步数  │ LLM/s  │ 工具/s│
 * ├──────────────────────────────┤
 * │    输出tok/s    │  系统提示词tok │
 * └──────────────────────────────┘
 * </pre>
 *
 * <p><b>布局实现要点(踩坑记录)</b>:早期版本用
 * {@code Row(Modifier.height(固定值))} + 子项 {@code weight(1f)} + {@code Column(verticalScroll)}
 * 的组合,在 Compose 中属于**非法测量组合**——滚动容器给子项无限高约束,而固定高度
 * 与内部 padding/文字高度冲突,测量阶段抛异常导致点开弹窗即崩溃。
 * 现改为:每个单元格用 {@code weight(1f)} 但不强行设行高,行高由内容自然撑开并靠
 * {@code IntrinsicSize.Min} 让竖线跟随行高;竖线用 {@code fillMaxHeight()} 而非固定值,
 * 彻底消除"内容超出固定高度"的冲突。
 *
 * <p>口径:总输入 = 未命中 + 命中 + 缓存写;输出速度 = 总输出 ÷ LLM 耗时;
 * 系统提示词 ≈ 首轮未命中缓存的输入 token。
 */
@Composable
fun ConsumptionStatsDialog(
    providerName: String,
    usage: TokenUsage,
    stats: SessionStats,
    firstInputTokens: Long,
    balance: BalanceClient.Result?,
    balanceLoading: Boolean,
    /** 投影数据拉取中(打开弹窗即现场拉取;期间显示占位而非误导性的 0)。 */
    usageLoading: Boolean = false,
    onProviderClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dark = LocalDarkTheme.current
    val textPrimary = if (dark) Color(0xFFE6EDE9) else Color(0xFF1B221F)
    val textSecondary = if (dark) Color(0xFF8C9A93) else Color(0xFF6B7684)
    val cardBg = if (dark) Color(0xFF1B221F) else Color(0xFFF7FBFA)
    val line = if (dark) Color(0xFF39423E) else Color(0xFFCFD8D3)
    val accent = Color(0xFFE5484D) // 草图中提供方名为红色
    val warn = Color(0xFFE5A33D)

    Dialog(onDismissRequest = onDismiss) {
        // 外层限制最大高度,内层滚动:避免滚动容器直接把无限高约束传给内容
        Box(
            Modifier
                .width(340.dp)
                .heightIn(max = 560.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(cardBg),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
            ) {
                // ===== 标题 =====
                Text("消费统计", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                Spacer(Modifier.height(16.dp))

                // ===== 第 1 行:提供方 | 余额 =====
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("提供方", fontSize = 14.sp, color = textPrimary)
                    Spacer(Modifier.width(12.dp))
                    // 提供方名:可点击 → 弹出密钥输入框
                    Text(
                        text = providerName.ifBlank { "—" },
                        fontSize = 14.sp,
                        color = accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onProviderClick() }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Text("余额:", fontSize = 14.sp, color = textPrimary)
                    Spacer(Modifier.width(6.dp))
                    if (balanceLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            strokeWidth = 2.dp,
                            color = accent,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    val (txt, color) = when {
                        balanceLoading -> "…" to textSecondary
                        balance == null -> "—" to textSecondary
                        balance.isOk -> balance.display() to textPrimary
                        balance.unsupported -> "不支持" to warn
                        balance.needsKey -> "待配置" to warn
                        else -> "—" to warn
                    }
                    Text(txt, fontSize = 14.sp, color = color, fontWeight = FontWeight.Medium)
                }
                if (balance != null && !balance.isOk) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = balance.error + if (balance.needsKey) "(点击提供方名称输入)" else "",
                        fontSize = 11.sp,
                        color = warn,
                    )
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = line, thickness = 1.dp)

                // ===== 第 2 行:输入 / 命中 / 未命中 / 总输出 =====
                QuadRow(
                    labels = listOf("输入", "命中", "未命中", "总输出"),
                    values = listOf(
                        formatTokens(usage.totalInputTokens),
                        formatTokens(usage.cacheReadTokens),
                        formatTokens(usage.uncachedInputTokens),
                        formatTokens(usage.outputTokens),
                    ),
                    loading = usageLoading,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    line = line,
                )
                HorizontalDivider(color = line, thickness = 1.dp)

                // ===== 第 3 行:轮次 / 步数 / LLM/s / 工具/s =====
                QuadRow(
                    labels = listOf("轮次", "步数", "LLM/s", "工具/s"),
                    values = listOf(
                        "${stats.turns}",
                        "${stats.steps}",
                        formatSeconds(stats.llmMs),
                        formatSeconds(stats.toolMs),
                    ),
                    loading = usageLoading,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    line = line,
                )
                HorizontalDivider(color = line, thickness = 1.dp)

                // ===== 第 4 行:输出tok/s | 系统提示词tok =====
                val speed = if (stats.llmMs > 0L) usage.outputTokens * 1000.0 / stats.llmMs else -1.0
                SplitRow(
                    left = "输出tok/s" to when {
                        usageLoading -> "…"
                        speed >= 0 -> String.format("%.1f", speed)
                        else -> "—"
                    },
                    right = "系统提示词tok" to when {
                        usageLoading -> "…"
                        firstInputTokens > 0L -> formatTokens(firstInputTokens)
                        else -> "—"
                    },
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    line = line,
                )
                HorizontalDivider(color = line, thickness = 1.dp)

                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        text = "关闭",
                        fontSize = 14.sp,
                        color = accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onDismiss() }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * 四等分单元格行(标签在上、数值在下,列间竖线)。
 *
 * 用 {@code IntrinsicSize.Min} 让整行高度由最高单元格决定,竖线 {@code fillMaxHeight()}
 * 自动跟随——避免早期"固定行高 56dp 但内容更高"导致的测量冲突崩溃。
 */
@Composable
private fun QuadRow(
    labels: List<String>,
    values: List<String>,
    loading: Boolean,
    textPrimary: Color,
    textSecondary: Color,
    line: Color,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        labels.forEachIndexed { i, label ->
            if (i > 0) VerticalLine(line)
            StatCell(
                label = label,
                value = if (loading) "…" else values.getOrElse(i) { "—" },
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 两等分单元格行(草图最底部一行)。 */
@Composable
private fun SplitRow(
    left: Pair<String, String>,
    right: Pair<String, String>,
    textPrimary: Color,
    textSecondary: Color,
    line: Color,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        StatCell(left.first, left.second, textPrimary, textSecondary, Modifier.weight(1f))
        VerticalLine(line)
        StatCell(right.first, right.second, textPrimary, textSecondary, Modifier.weight(1f))
    }
}

/** 单个统计单元格:标签在上、数值在下,水平居中。 */
@Composable
private fun StatCell(
    label: String,
    value: String,
    textPrimary: Color,
    textSecondary: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(vertical = 14.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = textSecondary,
            maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = value,
            fontSize = 14.sp,
            color = textPrimary,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** 单元格之间的竖线分隔(高度跟随所在行,不设固定值)。 */
@Composable
private fun VerticalLine(color: Color) {
    Box(
        Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(color),
    )
}

/** token 数带千分位;大数用 K/M 紧凑显示并与草图一致(纯数字,无单位后缀)。 */
private fun formatTokens(n: Long): String = when {
    n >= 1_000_000L -> String.format("%.2fM", n / 1_000_000.0)
    n >= 100_000L -> String.format("%.1fK", n / 1000.0)
    else -> "$n"
}

/** 毫秒 → 秒(草图列名为 LLM/s、工具/s,故单位统一为秒)。 */
private fun formatSeconds(ms: Long): String {
    if (ms <= 0L) return "0"
    return String.format("%.1f", ms / 1000.0)
}
