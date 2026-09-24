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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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

@Composable
internal fun InfoLine(label: String, value: String, mono: Boolean = false) {
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
    // 日志筛选：RPC 明细会让日志很长，需要能单独看「内置 dsh 的启动输出」
    var onlyDsh by remember { mutableStateOf(false) }
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
        // 只在"用户本来就在底部"时自动下滚；用户往上翻阅历史时不要把他拽回去
        if (logs.isNotEmpty() && logScroll.value >= logScroll.maxValue - 48) {
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

                // ===== 复制 / 导出：真机排障的第一手手段 =====
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val text = logs.joinToString("\n")
                        clipboard.setText(AnnotatedString(text))
                        toast = "已复制 " + logs.size + " 行日志到剪贴板，可直接粘贴发送"
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("一键复制全部日志") }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val crash = ProrootEnv.crashText(context)
                            if (crash.isNullOrEmpty()) {
                                toast = "暂无崩溃报告（" + ProrootEnv.crashFile(context).absolutePath + "）"
                            } else {
                                clipboard.setText(AnnotatedString(crash))
                                toast = "已复制崩溃报告（" + crash.length + " 字符）"
                            }
                        },
                    ) { Text("复制崩溃报告") }
                    OutlinedButton(
                        onClick = {
                            val f = ProrootEnv.writeEnvReport(context)
                            toast = if (f == null) "导出失败" else "已导出到 " + f.absolutePath
                        },
                    ) { Text("导出完整报告") }
                }
                if (toast != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = toast!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
            text = if (onlyDsh) "内置 dsh 启动日志" else "全过程日志 · 共 " + logs.size + " 行",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onlyDsh = !onlyDsh }) {
                Text(if (onlyDsh) "显示全部日志" else "只看 dsh 启动日志")
            }
            OutlinedButton(onClick = { scope.launch { logScroll.animateScrollTo(logScroll.maxValue) } }) {
                Text("跳到最新")
            }
        }
        Spacer(Modifier.height(6.dp))
        // ===== 开源许可与致谢 =====
        // proroot 的许可证（第 4、5 条）强制要求：随包附带许可声明，
        // 并在「应用描述 / 关于页 / 第三方许可声明」中署名 proroot。
        // 这里就是那个署名位置，删掉会导致分发不合规。
        Text(
            text = "开源许可与致谢",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = ATTRIBUTION,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
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
                val source = if (onlyDsh) {
                    // dsh 进程 stdout、启动阶段与鉴权链路——排查"后端到底起没起、怎么起的"时只看这些
                    logs.filter {
                        it.contains("[dsh]") || it.contains("== start") || it.contains("== install")
                                || it.contains("鉴权交换") || it.contains("端口 3081")
                                || it.contains("guest 自检") || it.contains("exec(")
                                || it.contains("已启动") || it.contains("唤醒锁")
                    }
                } else {
                    logs
                }
                val text = source.takeLast(600).joinToString("\n")
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

/**
 * 第三方组件署名。
 *
 * <p><b>不要删除</b>：proroot 的许可证第 4、5 条强制要求随包附带许可声明，
 * 并在应用描述 / 关于页 / 第三方许可声明中署名 proroot。本区块即该署名位置，
 * 完整清单见仓库 `third_party/OPEN-SOURCE.md` 与 `third_party/proroot-LICENSE.txt`。
 */
private const val ATTRIBUTION = """本 APP 打包了以下第三方开源/第三方组件：

【proroot】rootless Linux 运行时
Copyright (c) 2026 coderred
https://github.com/coderredlab/proroot
以未修改形式随本应用包分发（arm64-v8a 下 5 个 .so）。
许可要点：不得分发修改版；未修改版仅可作为完整应用包的一部分分发；
须随副本附带许可声明并在应用内署名 proroot。

【DeepSeek Harness (dsh)】MIT License
@deepseek-ai/dsh 0.1.5-rc.1

【Node.js】MIT License
v22.22.2 (linux-arm64)

【Ubuntu Base】主要遵循 GPL / LGPL
Ubuntu 24.04.5 LTS base arm64
完整清单见 guest 内 /usr/share/doc/*/copyright

【后端依赖（部分）】
sharp / @img/sharp-linux-arm64 —— Apache-2.0
node-pty —— MIT
koffi / @koromix/koffi-linux-arm64 —— MIT

【前端】
AndroidX / Jetpack Compose / Kotlin —— Apache-2.0

【运行期调用（未打包）】
AOSP toybox（tar / kill / linker64）—— BSD-3-Clause
PDF.js（位于 dsh 的 sidebar-documentpreview 插件内）—— Apache-2.0

完整许可文本与清单见仓库 third_party/ 目录。"""
