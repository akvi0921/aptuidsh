package com.aptuidsh.kui

import android.app.Application
import android.util.Log
import com.aptuidsh.kui.env.DshAuth
import com.aptuidsh.kui.env.DshBackend
import com.aptuidsh.kui.env.DshService
import com.aptuidsh.kui.env.ProrootEnv
import com.aptuidsh.kui.net.ApiCompat
import java.io.File

/**
 * APTUIDSH 应用入口。
 *
 * <p>进程启动时做三件与环境相关的事：
 * <ol>
 *   <li>初始化 [DshAuth]（恢复上次的 launchToken / Cookie，避免每次冷启都重新交换）；</li>
 *   <li>从内置镜像的版本标记文件里读出 dsh 版本号，注入 [ApiCompat]（`host.describe`
 *       在新版 API 里已被移除，版本号由本地补齐）；</li>
 *   <li>环境已装好时静默拉起前台服务，让内置 dsh 后端跟着 APP 一起起来。</li>
 * </ol>
 */
class AptuidshApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        com.aptuidsh.kui.env.EnvLog.attach(this)
        com.aptuidsh.kui.env.EnvLog.i("APP 启动（pid=${android.os.Process.myPid()}）")
        DshAuth.init(this)
        ApiCompat.setDshVersion(readDshVersion())
        syncGuestResolvConf()
        maybeAutoStartBackend()
        // 后台线程写一份「用户可直接发送」的诊断报告：
        // 真机上没有 logcat 权限时，这就是唯一客观证据。
        Thread {
            try {
                com.aptuidsh.kui.env.EnvLog.i("== 生成启动诊断报告 ==")
                ProrootEnv.writeEnvReport(this)
            } catch (t: Throwable) {
                com.aptuidsh.kui.env.EnvLog.e("写诊断报告异常", t)
            }
        }.start()
    }

    /**
     * 全局崩溃捕获：把堆栈写到**用户可读**的位置，否则真机上的崩溃完全无从定位。
     *
     * <p>写两处：
     * <ul>
     *   <li>{@code getExternalFilesDir()} —— 无需任何权限，文件管理器可访问
     *       （Android/data/com.aptuidsh.kui/files/）；</li>
     *   <li>{@code /sdcard/APTUIDSH/crash.txt} —— 已授予「所有文件访问」时更易取。</li>
     * </ul>
     * 记录后仍然交回默认处理器，保证系统行为不变（用户照样看到「应用已停止」）。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = java.io.StringWriter()
                error.printStackTrace(java.io.PrintWriter(sw))
                val text = buildString {
                    append("APTUIDSH 崩溃报告\n")
                    append("时间: ").append(java.util.Date()).append('\n')
                    append("线程: ").append(thread.name).append('\n')
                    append("版本: ").append(versionName()).append('\n')
                    append("设备: ").append(android.os.Build.MANUFACTURER).append(' ')
                    append(android.os.Build.MODEL).append(" / Android ")
                    append(android.os.Build.VERSION.RELEASE)
                    append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n\n")
                    append(sw.toString())
                }
                writeCrash(text)
            } catch (t: Throwable) {
                Log.e(TAG, "写崩溃报告失败", t)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun writeCrash(text: String) {
        val targets = mutableListOf<java.io.File>()
        getExternalFilesDir(null)?.let { targets.add(java.io.File(it, "crash.txt")) }
        try {
            val shared = java.io.File("/sdcard/APTUIDSH")
            if (shared.isDirectory || shared.mkdirs()) {
                targets.add(java.io.File(shared, "crash.txt"))
            }
        } catch (ignored: Throwable) {
        }
        for (f in targets) {
            try {
                f.writeText(text)
                Log.e(TAG, "崩溃报告已写入 " + f.absolutePath)
            } catch (t: Throwable) {
                Log.e(TAG, "写入失败 " + f, t)
            }
        }
    }

    private fun versionName(): String =
        try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (t: Throwable) {
            "?"
        }

    /** 从 `rootfs/.aptuidsh-image` 里解析 `dsh=` 行。 */
    private fun readDshVersion(): String {
        val info = ProrootEnv.imageInfo(this) ?: return "0.1.5+"
        return info.lineSequence()
            .firstOrNull { it.startsWith("dsh=") }
            ?.removePrefix("dsh=")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "0.1.5+"
    }

    /** guest 有自己的 /etc，DNS 必须由宿主按当前网络写入。 */
    private fun syncGuestResolvConf() {
        if (!ProrootEnv.isInstalled(this)) return
        try {
            ProrootEnv.syncResolvConf(this)
        } catch (t: Throwable) {
            Log.w(TAG, "sync resolv.conf failed: $t")
        }
    }

    /**
     * 自动引导：环境已装好就拉起后端；**未装则直接开始安装**。
     *
     * <p>为什么要自动装：首版把安装完全押在首屏那个按钮上（且跑在 Compose 的
     * rememberCoroutineScope 里），一旦这条路径失效，用户看到的就是「点了没反应」，
     * 而 APP 自己什么也不会做。环境自举本来就是本 APP 的职责，不该依赖一次点击。
     * 安装全过程有前台通知 + 首屏进度卡，用户随时可感知。
     */
    private fun maybeAutoStartBackend() {
        if (!ProrootEnv.isInstalled(this)) {
            Log.i(TAG, "环境未安装，自动开始引导安装")
            try {
                DshService.requestInstallAndStart(this)
            } catch (t: Throwable) {
                Log.w(TAG, "auto install failed: $t")
            }
            return
        }
        try {
            DshService.requestStart(this)
        } catch (t: Throwable) {
            Log.w(TAG, "auto start backend failed: $t")
        }
    }

    /** 内置环境的磁盘占用（供控制台展示）。 */
    fun installedBytes(): Long = ProrootEnv.rootfsDir(this).let { root ->
        if (!root.exists()) 0L else ProrootEnv.installMarker(this).let { _ -> dirSize(root) }
    }

    private fun dirSize(f: File): Long {
        if (!f.exists()) return 0L
        if (f.isFile) return f.length()
        var sum = 0L
        f.listFiles()?.forEach { sum += dirSize(it) }
        return sum
    }

    companion object {
        private const val TAG = "AptuidshApp"

        // 说明：这里刻意不提供「同步探活」便捷方法。
        // 探活是网络操作，任何在组合期/主线程调用它的写法都会触发
        // NetworkOnMainThreadException（首版就是这样把「官方 Web UI」页搞崩的）。
        // 需要探活请用 DshBackend.probeDsh() 并放到 IO 线程。
    }
}
