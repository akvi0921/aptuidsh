package com.aptuidsh.kui.file

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import com.aptuidsh.kui.AppRuntime
import com.aptuidsh.kui.FileBrowserActivity
import com.aptuidsh.kui.FileViewerActivity
import com.aptuidsh.kui.PlayerActivity
import com.aptuidsh.kui.net.DshClient
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern

/**
 * 路径识别 / 文件类型识别 / 打开分发工具(APTUIDSH 自研实现)。
 *
 * <p>语义参照 Android 文件访问的现实约束(完全自行编写,不复刻任何外部源码):
 * <ul>
 *   <li><b>路径识别</b>:从聊天正文中找出「绝对/别名前缀 + 已知扩展名」的路径片段;</li>
 *   <li><b>别名解析</b>:~/、/sdcard、Downloads/、Termux storage 软链等统一解析为真实绝对路径;</li>
 *   <li><b>类型识别</b>:扩展名 → Kind(图片/视频/音频/安装包/文本/源码/压缩包/PDF/其他);</li>
 *   <li><b>打开分发</b>:媒体走 MediaStore、共享存储走自建 FileServeProvider、APK 走系统安装器,
 *       文本/图片内置查看,Termux 私有目录给引导提示;</li>
 *   <li><b>目录探测</b>:无扩展名的路径先经后端 host.listDirectory 探测是否目录
 *       (本后端列表项不带类型标志,只能以「能否列出」判断),是目录则打开文件浏览器。</li>
 * </ul>
 */
object FileOpenKit {

    // ==================== 常量与枚举 ====================

    /** Termux 家目录(后端进程工作区;Android APP 进程一般不可直接读)。 */
    const val TERMUX_HOME = "/data/data/com.termux/files/home"

    /** 自建文件伺服 Provider 的 authority(与 AndroidManifest 注册一致)。 */
    const val AUTHORITY = "com.aptuidsh.kui.files"

    /** 文件所属域:决定读取/打开策略。 */
    enum class Realm(val label: String) {
        SHARED("共享存储"),      // /storage/emulated/0* /sdcard*(可经 MediaStore / 全部文件权限直读)
        APP("应用目录"),         // 本应用私有目录(可直接 File 读写)
        TERMUX("Termux 私有目录"), // /data/data/com.termux*(仅后端可见,APP 不可直接读)
        OTHER("系统目录");       // 其余(视权限而定)

        companion object {
            fun of(p: String): Realm = when {
                p.startsWith("/storage/emulated/0") || p.startsWith("/sdcard") -> SHARED
                p.startsWith("/data/data/com.aptuidsh.kui") -> APP
                p.startsWith("/data/data/com.termux") -> TERMUX
                else -> OTHER
            }
        }
    }

    /** 文件类型分类(扩展名识别 + 图标/标签)。 */
    enum class Kind(val label: String, val icon: String) {
        DIRECTORY("目录", "📁"),
        IMAGE("图片", "🖼️"),
        VIDEO("视频", "🎬"),
        AUDIO("音频", "🎵"),
        APK("安装包", "📦"),
        TEXT("文本", "📄"),
        CODE("源码", "📝"),
        ARCHIVE("压缩包", "🗜️"),
        PDF("PDF", "📕"),
        OTHER("文件", "📄");

        companion object {
            /** 未知扩展名(可能目录或扩展名罕见的文件)。 */
            fun guess(ext: String): Kind? = when (ext.lowercase()) {
                "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico", "heic" -> IMAGE
                "mp4", "mkv", "avi", "webm", "mov", "3gp", "flv", "ts", "m4v" -> VIDEO
                "mp3", "wav", "flac", "ogg", "m4a", "aac", "opus", "mid", "amr" -> AUDIO
                "apk" -> APK
                "txt", "md", "json", "xml", "log", "yaml", "yml", "toml", "ini", "cfg", "properties",
                "csv", "tsv", "srt", "vtt" -> TEXT
                "java", "kt", "kts", "ts", "tsx", "js", "jsx", "py", "sh", "css", "html", "htm",
                "c", "h", "cpp", "cc", "hpp", "go", "rs", "sql", "gradle", "yml" -> CODE
                "zip", "tar", "gz", "tgz", "7z", "rar", "bz2", "xz", "jar" -> ARCHIVE
                "pdf" -> PDF
                else -> null
            }
        }
    }

    // ==================== 路径识别(正则) ====================

    // 前缀:~/ | /storage/emulated/0/ | /sdcard/ | /data/<至少一级>/ | Download[s]/ (大小写不敏感)
    private val PATH_PREFIX = "(?:~/|/storage/emulated/0/|/sdcard/|/data/[^\\s()\\[\\]<>\"']+/|Download[s]?/)"
    private val EXT_ALT = "(?:apk|mp4|mkv|avi|webm|mov|3gp|flv|ts|m4v|mp3|wav|flac|ogg|m4a|aac|opus|png|jpe?g|gif|webp|bmp|svg|ico|heic|pdf|txt|md|json|xml|log|ya?ml|toml|ini|cfg|properties|csv|tsv|srt|vtt|java|kt|kts|tsx?|jsx?|py|sh|css|html?|c|h|cpp|cc|hpp|go|rs|sql|gradle|zip|tar|gz|tgz|7z|rar|bz2|xz|jar)"
    // 路径本体:不允许空白/括号/尖括号/引号(避免吞掉行内代码容器与相邻标点)
    private val PATH_BODY = "[^\\s()\\[\\]<>\"']*"
    private val PATH_REGEX = Pattern.compile(PATH_PREFIX + PATH_BODY + "\\.(?:" + EXT_ALT + ")", Pattern.CASE_INSENSITIVE)

    /** 消息正文中识别出的可点击路径片段。 */
    data class PathHit(val start: Int, val end: Int, val raw: String, val resolved: String)

    /** 在文本中找出全部文件路径(带已知扩展名);重复不合并、不嵌套。 */
    fun findPaths(text: String?): List<PathHit> {
        if (text.isNullOrEmpty()) return emptyList()
        val out = ArrayList<PathHit>()
        val m = PATH_REGEX.matcher(text)
        while (m.find()) {
            val raw = m.group()
            out.add(PathHit(m.start(), m.end(), raw, resolve(raw)))
        }
        return out
    }

    // ==================== 别名解析 ====================

    /** 别名/软链路径 → 真实绝对路径。 */
    fun resolve(raw: String?): String {
        var p = raw?.trim() ?: ""
        if (p.startsWith("~/")) {
            p = TERMUX_HOME + p.substring(1) // ~/x → 家目录/x
        } else if (p.startsWith("$TERMUX_HOME/storage/")) {
            val rel = p.substring("$TERMUX_HOME/storage/".length)
            p = when {
                rel.startsWith("downloads/") -> "/storage/emulated/0/Download/" + rel.removePrefix("downloads/")
                rel.startsWith("pictures/") -> "/storage/emulated/0/Pictures/" + rel.removePrefix("pictures/")
                rel.startsWith("movies/") -> "/storage/emulated/0/Movies/" + rel.removePrefix("movies/")
                rel.startsWith("music/") -> "/storage/emulated/0/Music/" + rel.removePrefix("music/")
                rel.startsWith("documents/") -> "/storage/emulated/0/Documents/" + rel.removePrefix("documents/")
                else -> "/storage/emulated/0/" + rel
            }
        } else if (p.startsWith("Download/")) {
            p = "/storage/emulated/0/Download/" + p.removePrefix("Download/")
        } else if (p.startsWith("Downloads/")) {
            p = "/storage/emulated/0/Download/" + p.removePrefix("Downloads/")
        } else if (p.startsWith("/sdcard/")) {
            p = "/storage/emulated/0/" + p.removePrefix("/sdcard/")
        }
        return p
    }

    fun realmOf(path: String): Realm = Realm.of(path)

    fun extOf(path: String): String {
        val name = path.substringAfterLast('/')
        val i = name.lastIndexOf('.')
        if (i <= 0 || i == name.length - 1) return ""
        return name.substring(i + 1).lowercase()
    }

    fun kindOfPath(path: String): Kind? {
        val ext = extOf(path)
        return if (ext.isEmpty()) null else Kind.guess(ext)
    }

    /** 文件名 → 浏览列表图标文本(目录优先按名判断,无扩展名视为目录外观)。 */
    fun iconFor(name: String): String {
        val i = name.lastIndexOf('.')
        if (i <= 0 || i == name.length - 1) return Kind.DIRECTORY.icon
        val kind = Kind.guess(name.substring(i + 1))
        return kind?.icon ?: Kind.OTHER.icon
    }

    // ==================== 权限 ====================

    /** 是否需要「所有文件访问」(共享存储中非媒体文件直读/直装)。 */
    fun hasStoragePermission(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else if (Build.VERSION.SDK_INT >= 23) {
            ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
    }

    fun promptStoragePermission(ctx: Context, hint: String) {
        AlertDialog.Builder(ctx)
            .setTitle("需要文件访问权限")
            .setMessage(hint + "\n\n请授予「所有文件访问权限」(仅用于读取你选择的文件)。")
            .setPositiveButton("去授权") { _, _ -> openAllFilesSettings(ctx) }
            .setNegativeButton("取消", null)
            .show()
    }

    fun openAllFilesSettings(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + ctx.packageName))
            )
        } catch (_: Exception) {
            try {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) { /* 无可用入口 */ }
        }
    }

    /** 是否可安装未知来源(REQINSTALL_PACKAGES 已在清单声明,此处查用户授权)。 */
    fun canInstallApks(ctx: Context): Boolean {
        return Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()
    }

    fun promptInstallPermission(ctx: Context) {
        AlertDialog.Builder(ctx)
            .setTitle("需要安装权限")
            .setMessage("安装 APK 需要允许「安装未知应用」。")
            .setPositiveButton("去设置") { _, _ ->
                try {
                    ctx.startActivity(
                        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + ctx.packageName))
                    )
                } catch (_: Exception) { /* 无可用入口 */ }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 工具小函数 ====================

    private val ui = Handler(Looper.getMainLooper())
    private fun ui(f: () -> Unit) = ui.post(f)

    fun toast(ctx: Context, msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    fun copyPath(ctx: Context, path: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("aptuidsh-path", path))
        toast(ctx, "已复制路径")
    }

    /** 通用提示对话框(标题/消息/可选复制路径)。 */
    fun dialog(ctx: Context, title: String, message: String, path: String? = null) {
        val b = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("关闭", null)
        if (!path.isNullOrEmpty()) {
            b.setPositiveButton("复制路径") { _, _ -> copyPath(ctx, path) }
        }
        b.show()
    }

    // ==================== MediaStore / 内容 URI ====================

    /** 按 显示名 + 相对路径 查 MediaStore,返回 content URI(媒体无需额外权限)。 */
    fun queryMediaUri(ctx: Context, absPath: String, kind: Kind?): Uri? {
        val collection = when (kind) {
            Kind.IMAGE -> MediaStore.Images.Media.getContentUri("external")
            Kind.VIDEO -> MediaStore.Video.Media.getContentUri("external")
            Kind.AUDIO -> MediaStore.Audio.Media.getContentUri("external")
            else -> return null
        }
        val name = absPath.substringAfterLast('/')
        val dir = absPath.substringBeforeLast('/') + "/"
        val rel = if (dir.startsWith("/storage/emulated/0/")) {
            dir.removePrefix("/storage/emulated/0/")
        } else null
        if (rel != null) {
            queryId(ctx, collection, MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " +
                MediaStore.MediaColumns.RELATIVE_PATH + "=?", arrayOf(name, rel))?.let { return it }
        }
        return queryId(ctx, collection, MediaStore.MediaColumns.DISPLAY_NAME + "=?", arrayOf(name))
    }

    private fun queryId(ctx: Context, collection: Uri, sel: String, args: Array<String>): Uri? {
        var c: Cursor? = null
        return try {
            c = ctx.contentResolver.query(
                collection, arrayOf(MediaStore.MediaColumns._ID), sel, args, null
            )
            if (c != null && c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null
        } catch (_: Exception) {
            null
        } finally {
            try { c?.close() } catch (_: Exception) {}
        }
    }

    /** 按绝对路径精确查 MediaStore.Files(需全部文件权限,否则 _data 被遮蔽)。 */
    fun queryFilesUriByData(ctx: Context, absPath: String): Uri? {
        var c: Cursor? = null
        return try {
            c = ctx.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.Files.FileColumns._ID),
                MediaStore.Files.FileColumns.DATA + "=?",
                arrayOf(absPath), null
            )
            if (c != null && c.moveToFirst()) {
                ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), c.getLong(0))
            } else null
        } catch (_: Exception) {
            null
        } finally {
            try { c?.close() } catch (_: Exception) {}
        }
    }

    /** 交给其它应用打开的 content URI:媒体 → MediaStore;其余 → 自建 Provider。 */
    fun contentUriFor(ctx: Context, absPath: String): Uri? {
        val kind = kindOfPath(absPath)
        queryMediaUri(ctx, absPath, kind)?.let { return it }
        val f = File(absPath)
        if (f.isFile) return FileServeProvider.uriFor(absPath)
        return null
    }

    /** 通用 MIME(扩展名 → 类型)。 */
    fun mimeOf(ext: String): String = when (ext.lowercase()) {
        "apk" -> "application/vnd.android.package-archive"
        else -> {
            val kind = Kind.guess(ext)
            when (kind) {
                Kind.IMAGE -> "image/*"
                Kind.VIDEO -> "video/*"
                Kind.AUDIO -> "audio/*"
                Kind.PDF -> "application/pdf"
                Kind.ARCHIVE -> "application/zip"
                else -> "application/octet-stream"
            }
        }
    }

    // ==================== 后端目录探测(点击分发用) ====================

    /**
     * 探测 path 是否为目录:host.listDirectory 成功即视为目录,否则按文件处理。
     * 回调均切回主线程。本后端列表项无 type 标志,「能否列出」是唯一可靠判据。
     */
    fun probePath(path: String, onDir: (String) -> Unit, onFile: () -> Unit) {
        val gw = AppRuntime.gateway()
        if (gw == null) {
            ui { onFile() }
            return
        }
        gw.client().hostListDirectory(path, object : DshClient.DshCallback {
            override fun onResult(value: JSONObject?) {
                ui {
                    if (value != null) onDir(path) else onFile()
                }
            }

            override fun onError(code: Int, message: String?, details: JSONObject?) {
                ui { onFile() }
            }
        })
    }

    // ==================== 打开分发 ====================

    /**
     * 消息正文里点到的路径(主入口):
     * 已知文件扩展名 → 直接按文件打开;否则先探测是否为目录(是 → 打开文件浏览器)。
     */
    fun openFromMessage(ctx: Context, raw: String) {
        val resolved = resolve(raw)
        val kind = kindOfPath(resolved)
        if (kind != null) {
            openFile(ctx, resolved)
            return
        }
        probePath(resolved, { dir -> FileBrowserActivity.start(ctx, dir) }, { openFile(ctx, resolved) })
    }

    /**
     * 按路径打开一个文件(已知是文件):内置查看 / APK 安装 / 系统应用打开分发。
     */
    fun openFile(ctx: Context, path: String) {
        val p = resolve(path)
        val realm = realmOf(p)
        if (realm != Realm.SHARED && realm != Realm.APP) {
            // Termux / 系统私有目录:APP 进程读不到,给引导(与后端协作复制到 Downloads)
            termuxGuidance(ctx, p)
            return
        }
        val kind = kindOfPath(p)
        try {
            when (kind) {
                Kind.IMAGE -> {
                    val mu = queryMediaUri(ctx, p, Kind.IMAGE)
                    FileViewerActivity.startImage(ctx, p, mu?.toString() ?: "")
                }
                Kind.TEXT, Kind.CODE -> FileViewerActivity.startText(ctx, p)
                Kind.APK -> installApk(ctx, p)
                Kind.VIDEO, Kind.AUDIO -> openMedia(ctx, p, kind)
                Kind.PDF, Kind.ARCHIVE, Kind.OTHER, null -> openExternal(ctx, p)
                Kind.DIRECTORY -> FileBrowserActivity.start(ctx, p)
            }
        } catch (e: Exception) {
            dialog(ctx, "打开失败", e.message ?: e.javaClass.simpleName, p)
        }
    }

    /** Termux 私有目录文件:提示通过会话复制到 Downloads(与后端文件输出规范一致)。 */
    private fun termuxGuidance(ctx: Context, path: String) {
        dialog(
            ctx,
            "Termux 私有目录",
            "该文件位于 Termux 私有目录(APP 无读取权限)。\n\n" +
                "可在会话里让助手把文件复制到 /sdcard/Download/ 后再次点击打开,或在 Termux 中查看。",
            path
        )
    }

    /**
     * 媒体:内置播放器(自研 PlayerActivity,不依赖系统播放应用)。
     * 数据源 = MediaStore URI(免权限)优先;未入库的文件需「所有文件访问」后由播放器直读路径。
     */
    private fun openMedia(ctx: Context, p: String, kind: Kind) {
        val uri = queryMediaUri(ctx, p, kind)
        val needPerm = !hasStoragePermission(ctx) && uri == null
        if (needPerm) {
            promptStoragePermission(
                ctx,
                "要播放 ${p.substringAfterLast('/')},需授予「所有文件访问权限」。(媒体已入库时可直接播放)"
            )
            return
        }
        PlayerActivity.start(ctx, kind == Kind.VIDEO, p, uri?.toString() ?: "")
    }

    /** 其余类型:经 Provider 交系统应用;必要时先要权限。 */
    private fun openExternal(ctx: Context, p: String) {
        val kind = kindOfPath(p)
        if (!hasStoragePermission(ctx)) {
            promptStoragePermission(ctx, "要打开 ${p.substringAfterLast('/')},需要「所有文件访问权限」。")
            return
        }
        val f = File(p)
        if (!f.isFile) {
            dialog(ctx, "未找到文件", "系统中不存在该文件。\n若它是目录,请使用文件浏览器进入。", p)
            return
        }
        val uri = FileServeProvider.uriFor(p)
        try {
            ctx.startActivity(viewIntent(uri, if (kind != null) mimeOf(extOf(p)) else "application/octet-stream"))
        } catch (_: ActivityNotFoundException) {
            dialog(ctx, "没有可打开的应用", "没有应用可以打开该类型文件(${extOf(p)})。", p)
        }
    }

    private fun viewIntent(uri: Uri, mime: String): Intent {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        i.clipData = ClipData.newRawUri("", uri)
        return i
    }

    // ==================== APK 安装唤醒 ====================

    /**
     * APK 安装:先媒体扫描把文件纳入 MediaStore,再经 Files.DATA 查 URI 交系统安装器
     * (华为 ACTION_VIEW 兼容);扫描/查询失败则用 PackageInstaller 会话直装兜底。
     */
    fun installApk(ctx: Context, apkPath: String) {
        val f = File(apkPath)
        if (!f.isFile) {
            dialog(ctx, "未找到文件", "系统中不存在该 APK 文件。", apkPath)
            return
        }
        if (!canInstallApks(ctx)) {
            promptInstallPermission(ctx)
            return
        }
        if (!hasStoragePermission(ctx) && !apkPath.startsWith("/data/data/com.aptuidsh.kui")) {
            promptStoragePermission(ctx, "要安装 ${f.name},需要「所有文件访问权限」读取 APK。")
            return
        }
        val handler = Handler(Looper.getMainLooper())
        android.media.MediaScannerConnection.scanFile(
            ctx, arrayOf(apkPath), null
        ) { _, _ ->
            // 扫描回调线程不确定:查询与流式安装全部放后台线程,UI 提示回主线程
            Thread {
                val mUri = queryFilesUriByData(ctx, apkPath)
                if (mUri != null) {
                    try {
                        val i = viewIntent(mUri, "application/vnd.android.package-archive")
                        ctx.startActivity(i)
                        handler.post { com.aptuidsh.kui.AppBanner.show(ctx, "正在唤起系统安装器…") }
                        return@Thread
                    } catch (_: Exception) {
                        // ACTION_VIEW 失败 → 走 PackageInstaller 兜底
                    }
                }
                streamedInstall(handler, ctx, f)
            }.start()
        }
    }

    /** PackageInstaller 会话流式直装兜底(文件 IO 在后台线程;结果经 InstallReceiver 广播回显)。 */
    private fun streamedInstall(handler: Handler, ctx: Context, apk: File) {
        try {
            val pi = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = pi.createSession(params)
            val session = pi.openSession(sessionId)
            val input = java.io.FileInputStream(apk)
            val output = session.openWrite("base.apk", 0, -1)
            val buf = ByteArray(65536)
            var n: Int
            var total = 0L
            while (input.read(buf).also { n = it } > 0) {
                output.write(buf, 0, n)
                total += n
                if (total > 512L * 1024 * 1024) break
            }
            session.fsync(output)
            output.close()
            input.close()
            val done = Intent(ctx, com.aptuidsh.kui.InstallReceiver::class.java)
            val pi2 = android.app.PendingIntent.getBroadcast(
                ctx, 1, done,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            session.commit(pi2.intentSender)
            session.close()
            handler.post { com.aptuidsh.kui.AppBanner.show(ctx, "安装请求已提交") }
        } catch (e: Exception) {
            handler.post { dialog(ctx, "安装失败", e.message ?: e.javaClass.simpleName, apk.absolutePath) }
        }
    }
}
