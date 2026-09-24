package com.aptuidsh.kui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aptuidsh.kui.env.DshBackend
import com.aptuidsh.kui.env.DshService
import com.aptuidsh.kui.env.EnvLog
import com.aptuidsh.kui.env.ProrootEnv
import com.aptuidsh.kui.env.RootfsInstaller
import com.aptuidsh.kui.ui.theme.ErrorRed
import com.aptuidsh.kui.ui.theme.SuccessGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 环境控制台里共享的「标签 + 值」一行（首页的运行信息卡也用这个）。 */
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

// =====================================================================
// 环境控制台
// ---------------------------------------------------------------------
// 【布局原则】这一页的价值在**日志**，所以日志必须是主角：
//   ① 外层 Column 用 fillMaxSize（曾经只有 fillMaxWidth → 高度不受限 →
//      日志卡的 weight(1f) 只能分到 0 高度，日志被彻底压没，实测踩过）；
//   ② 上半部分全部做成「紧凑」形态：小按钮、横向可滚动的按钮条、可横向排的概览；
//   ③ 长文本（环境事实核查、开源许可）默认**收起**，点开才占高度且有自身的滚动上限；
//   ④ 日志区 weight(1f) 吃掉剩余全部高度，自己滚动 —— 不再让整页当一个大长条滚。
// =====================================================================

/** 紧凑按钮：高 34dp。官方 Material 按钮默认太高，一排三四个就把首屏占满了。 */
@Composable
private fun MiniButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    val padding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
    val m = modifier.height(34.dp)
    if (filled) {
        Button(onClick = onClick, enabled = enabled, modifier = m, contentPadding = padding) {
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = m, contentPadding = padding) {
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** 横向可滚动的按钮条：一排放不下就横滑，绝不换行去挤占纵向空间。 */
@Composable
private fun ButtonBar(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** 可折叠区块：标题一行（带 ▸/▾），展开后正文有自身高度上限与滚动。 */
@Composable
private fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    maxBodyHeight: Int = 190,
    body: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (expanded) "▾" else "▸",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (expanded) {
        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth().heightIn(max = maxBodyHeight.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState()),
            ) { body() }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * 环境控制台整页。
 *
 * <h3>布局（自上而下）</h3>
 * <pre>
 * ┌ 顶栏：返回 · 环境控制台 ······················ ● 状态词
 * ├ 概览（3 行，紧凑）：状态 / 端口 + 内置 dsh / 后端地址
 * ├ 操作条（小按钮，横滑）：启动 停止 重启 │ 自检 重装 卸载
 * ├ 数据条（小按钮，横滑）：复制日志 崩溃报告 完整报告
 * ├ 提示行（仅在有内容时出现）：toast / 自检结果（可关）
 * ├ 日志头：日志 · 共 N 行 ······ 只看 dsh 启动日志 | 跳到最新
 * ├ 日志区 ★ weight(1f) 吃掉剩余高度，自身滚动
 * └ 折叠区：环境事实核查 / 开源许可与致谢（默认收起）
 * </pre>
 *
 * <p>内置环境出问题时（解压失败、端口占用、dsh 崩溃），日志是唯一能自证的地方，
 * 所以这里把绝大部分屏幕留给它。
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
    // 长文本默认收起：它们一展开就是十几行，会把日志顶出屏幕
    var showFacts by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }
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
    // 看起来像"日志没输出"（首版就吃过这个亏）。
    // 只在"用户本来就在底部"时下滚，用户往上翻阅历史时不要把他拽回去。
    LaunchedEffect(logs.size) {
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

    val dotColor = when (status.phase) {
        DshBackend.Phase.RUNNING -> SuccessGreen
        DshBackend.Phase.ERROR -> ErrorRed
        DshBackend.Phase.INSTALLING, DshBackend.Phase.STARTING, DshBackend.Phase.STOPPING ->
            MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    val dshVersion = runCatching { ProrootEnv.installedDshVersion(context) }.getOrNull()
        ?: runCatching { ProrootEnv.bundledDshVersion(context) }.getOrNull()
        ?: "—"

    // fillMaxSize 是关键：日志区的 weight(1f) 只有在高度受限时才有意义
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        // ===================== 顶栏 =====================
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("‹ 返回", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = "环境控制台",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            StatusDot(dotColor)
            Spacer(Modifier.width(6.dp))
            Text(
                text = status.phase.name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (status.phase == DshBackend.Phase.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
        if (status.message.isNotEmpty()) {
            Text(
                text = status.message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(8.dp))

        // ===================== 概览（紧凑 3 行） =====================
        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KV("端口", if (status.portAlive) "已监听" else "未监听", weight = 1f)
                    KV("内置 dsh", dshVersion, mono = true, weight = 1f)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KV("后端", ProrootEnv.BASE_URL, mono = true, weight = 1f)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ===================== 操作条（小按钮 · 横滑） =====================
        ButtonBar {
            MiniButton("启动", filled = true, enabled = !busy) { DshService.requestStart(context) }
            MiniButton("停止", enabled = !busy) { DshService.requestStop(context) }
            MiniButton("重启", enabled = !busy) { DshService.requestRestart(context) }
            MiniButton("运行自检", enabled = !busy) {
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
            }
            MiniButton("重装环境", enabled = !busy) {
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
            }
            MiniButton("卸载环境", enabled = !busy) {
                Thread {
                    try {
                        DshBackend.get().stop(context)
                        RootfsInstaller.uninstall(context)
                        EnvLog.i("环境已卸载")
                    } catch (t: Throwable) {
                        EnvLog.e("卸载失败", t)
                    }
                }.start()
            }
        }

        Spacer(Modifier.height(6.dp))

        // ===================== 数据条（真机排障的第一手手段） =====================
        ButtonBar {
            MiniButton("复制日志") {
                clipboard.setText(AnnotatedString(logs.joinToString("\n")))
                toast = "已复制 " + logs.size + " 行日志，可直接粘贴发送"
            }
            MiniButton("复制崩溃报告") {
                val crash = ProrootEnv.crashText(context)
                if (crash.isNullOrEmpty()) {
                    toast = "暂无崩溃报告"
                } else {
                    clipboard.setText(AnnotatedString(crash))
                    toast = "已复制崩溃报告（" + crash.length + " 字符）"
                }
            }
            MiniButton("导出完整报告") {
                val f = ProrootEnv.writeEnvReport(context)
                toast = if (f == null) "导出失败" else "已导出到 " + f.absolutePath
            }
        }

        // ===================== 提示行（只在有内容时出现） =====================
        val notice = toast
        if (notice != null) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = notice,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    onClick = { toast = null },
                    contentPadding = PaddingValues(horizontal = 6.dp),
                ) { Text("✕", style = MaterialTheme.typography.labelMedium) }
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(6.dp))

        // ===================== 日志头 =====================
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (onlyDsh) "dsh 启动日志" else "日志 · 共 " + logs.size + " 行",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            MiniButton(if (onlyDsh) "显示全部" else "只看 dsh") { onlyDsh = !onlyDsh }
            Spacer(Modifier.width(6.dp))
            MiniButton("跳到最新") {
                scope.launch { logScroll.animateScrollTo(logScroll.maxValue) }
            }
        }

        Spacer(Modifier.height(6.dp))

        // ===================== 日志区 ★ 吃掉剩余全部高度 =====================
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
                    text = text.ifEmpty { "（暂无输出）" },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ===================== 折叠区（默认全收起） =====================
        Spacer(Modifier.height(2.dp))
        CollapsibleSection(
            title = "环境事实核查（启动器 / 镜像 / 空间）· 点开查看",
            expanded = showFacts,
            onToggle = { showFacts = !showFacts },
        ) {
            // 这些是「安装点了没反应」时最需要一眼看到的客观事实
            Text(
                text = "镜像: " + (status.imageInfo?.replace("\n", " · ") ?: "—"),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            for (line in ProrootEnv.diagnose(context)) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "日志文件: " + EnvLog.file(context).absolutePath,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val smoke = smokeResult
        if (smoke != null) {
            CollapsibleSection(
                title = "自检结果（点开查看）",
                expanded = true,
                onToggle = { smokeResult = null },
                maxBodyHeight = 130,
            ) {
                Text(
                    text = smoke,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ===== 开源许可与致谢 =====
        // proroot 的许可证（第 4、5 条）强制要求：随包附带许可声明，
        // 并在「应用描述 / 关于页 / 第三方许可声明」中署名 proroot。
        // 这里就是那个署名位置，删掉会导致分发不合规。（默认收起是为了给日志让位，
        // 但标题常驻可见，点一下就能看到全文。）
        CollapsibleSection(
            title = "开源许可与致谢",
            expanded = showLicense,
            onToggle = { showLicense = !showLicense },
            maxBodyHeight = 220,
        ) {
            Text(
                text = ATTRIBUTION,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 概览里一个紧凑的「标签 / 值」块，可参与等宽排布。 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.KV(
    label: String,
    value: String,
    mono: Boolean = false,
    weight: Float? = null,
) {
    val m = if (weight != null) Modifier.weight(weight) else Modifier
    Column(m) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 顶栏那个小状态点。 */
@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .height(9.dp)
            .width(9.dp)
            .background(color = color, shape = CircleShape),
    )
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
@deepseek-ai/dsh 0.1.7-rc.1

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
