package com.aptuidsh.kui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aptuidsh.kui.env.DshBackend
import com.aptuidsh.kui.env.DshService
import com.aptuidsh.kui.env.ProrootEnv
import com.aptuidsh.kui.ui.theme.ErrorRed
import com.aptuidsh.kui.ui.theme.SuccessGreen
import kotlinx.coroutines.delay

/**
 * 首屏 · 环境控制台。
 *
 * <h3>这个 APP 现在的形态</h3>
 * 自研原生对话界面已全部移除，前端只有一套：**官方 Web UI**（内嵌 WebView）。
 * 原生侧只保留「把内置 Linux 环境管起来」这一件事，因此首屏就是环境控制台：
 * 看清环境在不在、后端跑没跑，然后一键进入官方界面。
 *
 * <h3>布局（自上而下四块）</h3>
 * <ol>
 *   <li><b>状态卡</b>：一个状态点 + 一句人话，安装时再带一条进度条 —— 扫一眼就知道能不能用。</li>
 *   <li><b>信息卡</b>：label/value 两列，只放排障真正会用到的字段。</li>
 *   <li><b>操作卡</b>：**一个主按钮占满整行**（安装并启动 / 启动后端 / 更新环境），
 *       下面一排两个等宽次按钮（停止 / 重启）。主次分明，不再把四五个按钮混在一行。</li>
 *   <li><b>入口区</b>：整宽主色大按钮「打开官方 Web UI」——它是主入口，给最大最显眼的位置；
 *       旁边一条描边按钮进环境控制台看日志。</li>
 * </ol>
 *
 * <p>状态来源：DshBackend 的 Listener 回推 + 1.2s 轮询兜底
 * （自己的状态直接读本地，不经过 HTTP，天然规避了「本机回环被拒」的问题）。
 */
@Composable
fun HomeScreen(
    onOpenConsole: () -> Unit,
    onOpenWebUi: () -> Unit,
) {
    val context = LocalContext.current
    val backend = remember { DshBackend.get() }

    var status by remember { mutableStateOf(backend.status(context)) }

    // 忙碌状态由真实阶段推导，不用本地布尔量：
    // 首版用本地 busy 标记，工作一旦转交外部执行就没有复位时机，按钮会永久禁用（点了没反应）。
    val busy = status.phase == DshBackend.Phase.INSTALLING
            || status.phase == DshBackend.Phase.STARTING
            || status.phase == DshBackend.Phase.STOPPING

    LaunchedEffect(Unit) {
        while (true) {
            status = backend.status(context)
            delay(1200)
        }
    }
    DisposableEffect(Unit) {
        val listener = object : DshBackend.Listener {
            override fun onStatus(s: DshBackend.Status) {
                status = s
            }

            override fun onLogLine(line: String) = Unit
        }
        backend.addListener(listener)
        onDispose { backend.removeListener(listener) }
    }

    // 覆盖安装新 APK 后 filesDir 里那份 rootfs 仍是旧的（Android 不会替我们更新），
    // 靠镜像指纹比对识别（见 ProrootEnv.needsImageUpdate）—— 必须让用户看得见。
    val needsImageUpdate = remember(status.installed, status.phase, status.progressPercent) {
        runCatching { ProrootEnv.needsImageUpdate(context) }.getOrDefault(false)
    }
    val installedDshVersion: String? = remember(status.installed, status.phase) {
        runCatching { ProrootEnv.installedDshVersion(context) }.getOrNull()
    }
    val bundledDshVersion: String? = remember {
        runCatching { ProrootEnv.bundledDshVersion(context) }.getOrNull()
    }

    val dotColor = when (status.phase) {
        DshBackend.Phase.RUNNING -> SuccessGreen
        DshBackend.Phase.ERROR -> ErrorRed
        DshBackend.Phase.INSTALLING, DshBackend.Phase.STARTING, DshBackend.Phase.STOPPING ->
            MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    val headline = when (status.phase) {
        DshBackend.Phase.NOT_INSTALLED ->
            if (needsImageUpdate) "环境需要更新" else "环境尚未安装"
        DshBackend.Phase.INSTALLING -> "正在安装内置环境…"
        DshBackend.Phase.STOPPED -> if (needsImageUpdate) "环境需要更新" else "环境就绪 · 后端未运行"
        DshBackend.Phase.STARTING -> "正在启动后端…"
        DshBackend.Phase.RUNNING -> "内置 dsh 运行中"
        DshBackend.Phase.STOPPING -> "正在停止…"
        DshBackend.Phase.ERROR -> "出错了"
    }
    val detail = when {
        status.message.isNotEmpty() -> status.message
        status.phase == DshBackend.Phase.RUNNING -> "官方 Web UI 已可用，点下方按钮进入"
        status.phase == DshBackend.Phase.NOT_INSTALLED -> "首次使用需先安装内置环境（约 20~30 秒）"
        status.phase == DshBackend.Phase.STOPPED && !needsImageUpdate -> "点「启动后端」即可进入官方 Web UI"
        else -> ""
    }
    // 后端没在监听时官方界面进不去，按钮要禁用并说明原因
    val webUiReady = status.portAlive

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BrandHeader()

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(color = dotColor, shape = CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (status.phase == DshBackend.Phase.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            if (detail.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (status.phase == DshBackend.Phase.INSTALLING && status.progressPercent in 0..100) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { status.progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = status.progressPercent.toString() + "%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard(title = "运行信息") {
            InfoLine("后端地址", ProrootEnv.BASE_URL, mono = true)
            InfoLine("端口状态", if (status.portAlive) "已监听" else "未监听")
            InfoLine(
                "环境占用",
                when {
                    status.installed -> "已安装"
                    needsImageUpdate -> "已安装（需更新）"
                    else -> "未安装"
                },
            )
            val versionText = when {
                installedDshVersion != null && bundledDshVersion != null &&
                        installedDshVersion != bundledDshVersion ->
                    "已装 " + installedDshVersion + " → 内置 " + bundledDshVersion
                installedDshVersion != null -> installedDshVersion
                bundledDshVersion != null -> bundledDshVersion + "（未安装）"
                else -> "—"
            }
            InfoLine("内置 dsh", versionText, mono = true)
            if (status.pid > 0) {
                InfoLine("后端 PID", status.pid.toString(), mono = true)
            }
        }

        SectionCard(title = "环境操作") {
            // 主操作：整宽单按钮。三种形态互斥，任何时候只有一个「该按的」。
            if (needsImageUpdate) {
                PrimaryAction("更新环境", enabled = !busy) {
                    DshService.requestInstallAndStart(context)
                }
            } else if (!status.installed) {
                PrimaryAction("安装并启动", enabled = !busy) {
                    DshService.requestInstallAndStart(context)
                }
            } else if (status.phase != DshBackend.Phase.RUNNING) {
                PrimaryAction("启动后端", enabled = !busy) {
                    DshService.requestStart(context)
                }
            } else {
                PrimaryAction("后端运行中", enabled = false) {}
            }
            Spacer(Modifier.height(8.dp))
            // 次操作：两个等宽按钮，仅在后端运行时可用
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryAction(
                    text = "停止",
                    enabled = !busy && status.phase == DshBackend.Phase.RUNNING,
                    modifier = Modifier.weight(1f),
                ) { DshService.requestStop(context) }
                SecondaryAction(
                    text = "重启",
                    enabled = !busy && status.phase == DshBackend.Phase.RUNNING,
                    modifier = Modifier.weight(1f),
                ) { DshService.requestRestart(context) }
            }
        }

        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "前端",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onOpenWebUi,
                    enabled = webUiReady,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(
                        text = "打开官方 Web UI",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (!webUiReady) {
                    Text(
                        text = "后端未运行，先在上方把它启动起来",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedButton(
                    onClick = onOpenConsole,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) { Text("环境控制台") }
            }
        }
    }
}

/** 品牌行：应用名 + 一句话说明。比整张品牌卡省一半高度。 */
@Composable
private fun BrandHeader() {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp)) {
        Text(
            text = "APTUIDSH",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "内置 DeepSeek Harness · 前端为官方 Web UI",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 统一的分区卡片：可选标题 + 分隔线，保证各块容器样式完全一致。 */
@Composable
private fun SectionCard(
    title: String? = null,
    content: @Composable () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(4.dp))
            }
            content()
        }
    }
}

/** 主操作按钮：整宽、高 48dp。 */
@Composable
private fun PrimaryAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) { Text(text) }
}

/** 描边次操作按钮（可指定宽度，用于等宽并排）。 */
@Composable
private fun SecondaryAction(
    text: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(44.dp),
    ) { Text(text) }
}
