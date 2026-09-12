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
        DshAuth.init(this)
        ApiCompat.setDshVersion(readDshVersion())
        syncGuestResolvConf()
        maybeAutoStartBackend()
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

    /** 环境已就绪就自动启动后端；未安装则交给首屏引导，不在后台静默下载/解压。 */
    private fun maybeAutoStartBackend() {
        if (!ProrootEnv.isInstalled(this)) return
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

        /** 后端是否正在监听。 */
        fun backendRunning(): Boolean = DshBackend.get().probePort()
    }
}
