package com.aptuidsh.kui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aptuidsh.kui.env.DshBackend
import com.aptuidsh.kui.env.EnvLog
import com.aptuidsh.kui.env.DshService
import com.aptuidsh.kui.env.ProrootEnv
import com.aptuidsh.kui.env.RootfsInstaller
import com.aptuidsh.kui.ui.theme.ErrorRed
import com.aptuidsh.kui.ui.theme.SuccessGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 内置环境控制卡：展示 proroot + rootfs + dsh 后端的实时状态，并提供安装/启停/重启入口。
 *
 * <p>APTUIDSH 与纯客户端最大的不同在于「后端是自己身上的一个 Linux 环境」，因此首屏必须
 * 让用户一眼看清：环境装没装、装多大、后端在不在跑、端口是不是 3081。
 */
@Composable
fun EnvControlCard(
    onOpenConsole: () -> Unit,
    onOpenWebUi: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backend = remember { DshBackend.get() }

    var status by remember { mutableStateOf(backend.status(context)) }

    // 由真实阶段推导「忙碌」，而不是靠本地布尔量：
    // 首版用本地 busy 标记，一旦工作转交外部执行就没有复位时机，按钮会永久禁用（表现为点了没反应）。
    val busy = status.phase == DshBackend.Phase.INSTALLING
            || status.phase == DshBackend.Phase.STARTING
            || status.phase == DshBackend.Phase.STOPPING

    // 轮询状态：安装/启动过程中会持续刷新进度
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

    val dotColor = when (status.phase) {
        DshBackend.Phase.RUNNING -> SuccessGreen
        DshBackend.Phase.ERROR -> ErrorRed
        DshBackend.Phase.INSTALLING, DshBackend.Phase.STARTING, DshBackend.Phase.STOPPING ->
            MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    val statusText = when (status.phase) {
        DshBackend.Phase.NOT_INSTALLED -> "内置环境未安装"
        DshBackend.Phase.INSTALLING -> status.message.ifEmpty { "正在安装内置环境…" }
        DshBackend.Phase.STOPPED -> if (status.installed) "环境就绪 · 后端未运行" else "内置环境未安装"
        DshBackend.Phase.STARTING -> status.message.ifEmpty { "正在启动内置 dsh…" }
        DshBackend.Phase.RUNNING -> status.message.ifEmpty { "内置 dsh 运行中" }
        DshBackend.Phase.STOPPING -> "正在停止…"
        DshBackend.Phase.ERROR -> status.message.ifEmpty { "启动异常" }
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(dotColor, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "内置运行环境",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "proroot · Ubuntu · dsh ${com.aptuidsh.kui.net.ApiCompat.dshVersion()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (status.phase == DshBackend.Phase.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (status.phase == DshBackend.Phase.INSTALLING && status.progressPercent in 0..100) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = status.progressPercent / 100f,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${status.progressPercent}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            InfoLine("后端地址", ProrootEnv.BASE_URL, mono = true)
            InfoLine("端口状态", if (status.portAlive) "已监听" else "未监听")
            InfoLine("环境占用", if (status.installed) "已安装" else "—")
            if (status.pid > 0) {
                InfoLine("后端 PID", status.pid.toString(), mono = true)
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!status.installed) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            // 交给前台服务执行：服务有自己的 worker 线程，
                            // 不受界面协程作用域影响，且全程写 EnvLog
                            DshService.requestInstallAndStart(context)
                        },
                    ) { Text("安装并启动") }
                } else if (status.phase == DshBackend.Phase.RUNNING) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            DshService.requestStop(context)
                        },
                    ) { Text("停止") }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            DshService.requestRestart(context)
                        },
                    ) { Text("重启") }
                } else {
                    Button(
                        enabled = !busy,
                        onClick = {
                            DshService.requestStart(context)
                        },
                    ) { Text("启动后端") }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenConsole) { Text("环境控制台") }
                OutlinedButton(onClick = onOpenWebUi) { Text("官方 Web UI") }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(88.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 环境控制台整页：状态 + 操作 + 实时运行日志。
 *
 * <p>内置环境出问题时（解压失败、端口占用、dsh 崩溃），日志是唯一能自证的地方，
 * 因此这里直接展示 proroot/dsh 的原始 stdout。
 */
@Composable
fun EnvConsoleScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backend = remember { DshBackend.get() }

    var status by remember { mutableStateOf(backend.status(context)) }
    var logs by remember { mutableStateOf(EnvLog.lines()) }
    var smokeResult by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val logScroll = rememberScrollState()
    val busy = status.phase == DshBackend.Phase.INSTALLING
            || status.phase == DshBackend.Phase.STARTING
            || status.phase == DshBackend.Phase.STOPPING

    LaunchedEffect(Unit) {
        while (true) {
            status = backend.status(context)
            logs = EnvLog.lines()
            delay(900)
        }
    }
    // 日志自动滚到底：日志区固定高度，不自动滚会一直停在最早几行，
    // 看起来像"日志没输出"（首版就吃过这个亏）
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            logScroll.animateScrollTo(logScroll.maxValue)
        }
    }
    DisposableEffect(Unit) {
        val listener = object : DshBackend.Listener {
            override fun onStatus(s: DshBackend.Status) {
                status = s
            }

            override fun onLogLine(line: String) {
                logs = EnvLog.lines()
            }
        }
        backend.addListener(listener)
        // EnvLog 是全过程日志（安装阶段 + 后端输出 + 异常栈），
        // 首版只看后端进程输出，导致「安装阶段出问题时控制台一片空白」
        val sink = EnvLog.Sink { logs = EnvLog.lines() }
        EnvLog.addSink(sink)
        onDispose {
            backend.removeListener(listener)
            EnvLog.removeSink(sink)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("返回") }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "环境控制台",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(12.dp))
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                InfoLine("状态", status.phase.name)
                InfoLine("说明", status.message.ifEmpty { "—" })
                InfoLine("后端", ProrootEnv.BASE_URL, mono = true)
                InfoLine("端口", if (status.portAlive) "已监听" else "未监听")
                InfoLine("dsh", com.aptuidsh.kui.net.ApiCompat.dshVersion())
                InfoLine("镜像", status.imageInfo?.replace("\n", " · ") ?: "—")
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "环境事实核查（无需点击即可见）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 这些是「安装点了没反应」时最需要一眼看到的东西：
                // 启动器在不在、能不能执行、镜像解没解开、空间够不够。
                for (line in ProrootEnv.diagnose(context)) {
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            DshService.requestStart(context)
                        },
                    ) { Text("启动") }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            DshService.requestStop(context)
                        },
                    ) { Text("停止") }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            DshService.requestRestart(context)
                        },
                    ) { Text("重启") }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                val smoke = withContext(Dispatchers.IO) {
                                    ProrootEnv.smokeTestGuest(context)
                                }
                                smokeResult = buildString {
                                    append(if (smoke.ok) "自检通过" else "自检失败")
                                    append("：").append(smoke.summary)
                                    if (smoke.output.isNotEmpty()) {
                                        append('\n').append(smoke.output.trim())
                                    }
                                }
                            }
                        },
                    ) { Text("运行自检") }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            Thread {
                                try {
                                    EnvLog.i("== 重装环境 ==")
                                    DshBackend.get().stop(context)
                                    RootfsInstaller.uninstall(context)
                                } catch (t: Throwable) {
                                    EnvLog.e("卸载旧环境失败", t)
                                }
                                DshService.requestInstallAndStart(context)
                            }.start()
                        },
                    ) { Text("重装环境") }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            Thread {
                                try {
                                    DshBackend.get().stop(context)
                                    RootfsInstaller.uninstall(context)
                                    EnvLog.i("环境已卸载")
                                } catch (t: Throwable) {
                                    EnvLog.e("卸载失败", t)
                                }
                            }.start()
                        },
                    ) { Text("卸载环境") }
                }
                if (smokeResult != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = smokeResult!!,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "全过程日志（含内置 dsh 启动输出）· 共 " + logs.size + " 行",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(10.dp)
                    .verticalScroll(logScroll),
            ) {
                val text = logs.takeLast(600).joinToString("\n")
                Text(
                    text = "日志文件: " + EnvLog.file(context).absolutePath + "\n\n" + text.ifEmpty { "（暂无输出）" },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
