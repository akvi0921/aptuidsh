package com.aptuidsh.kui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aptuidsh.kui.file.FileOpenKit
import com.aptuidsh.kui.net.DshClient
import com.aptuidsh.kui.ui.theme.APTUIDSHTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

// ==================== 调色板(与内容区亮/暗一致) ====================
private val FBgLight = Color(0xFFECF2F0)
private val FBgDark = Color(0xFF101218)
private val FTextLight = Color(0xFF1A1D26)
private val FTextDark = Color(0xFFE6F0EC)
private val FSubLight = Color(0xFF5B6478)
private val FSubDark = Color(0xFFA3B5AC)
private val FDivLight = Color(0xFFD8DEE4)
private val FDivDark = Color(0xFF2A322E)
private val FTopLight = Color(0xFF4D6BFE)
private val FError = Color(0xFFE5484D)
private val FLink = Color(0xFF4D6BFE)
private val FNoteLight = Color(0xFFF6E7C1)
private val FNoteDark = Color(0xFF403A26)

/**
 * 内置文件浏览器。
 *
 * <p>两类数据源:
 * <ul>
 *   <li><b>本地直读</b>(共享存储且已授权 / 本应用目录):File.listFiles 真实列出
 *       目录+文件+大小(区分文件类型可靠);</li>
 *   <li><b>后端 host.listDirectory</b>(Termux 工作区等 APP 不可读路径):实测该接口
 *       <b>只返回目录条目、不返回普通文件</b>,因此浏览此类目录仅能看文件夹,
 *       界面会给出说明并支持一键跳转 Downloads。</li>
 * </ul>
 * 点击条目:能读文件系统时按 isDirectory 直达;否则沿用「后端探测目录,失败按文件打开」。
 */
class FileBrowserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startPath = intent.getStringExtra("path") ?: ""
        enableEdgeToEdge()
        setContent {
            APTUIDSHTheme(darkTheme = isSystemInDarkTheme()) {
                BrowserScreen(initialPath = startPath)
            }
        }
    }

    companion object {
        /** 打开文件浏览器;path 为空 → 主目录。 */
        fun start(ctx: Context, path: String = "") {
            ctx.startActivity(Intent(ctx, FileBrowserActivity::class.java).putExtra("path", path))
        }
    }
}

/** 单条列表项(isDir=null 表示后端列表,条目只可能是目录)。 */
private data class Entry(
    val name: String,
    val path: String,
    val hidden: Boolean = false,
    val isDir: Boolean? = null,
    val size: Long = -1L,
)

/** 目录快照。 */
private data class DirState(
    val path: String,
    val home: String,
    val crumbs: List<Entry>,
    val entries: List<Entry>,
    val local: Boolean = false,   // true=本地文件系统直读(条目含真实文件)
)

@Composable
private fun BrowserScreen(initialPath: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dark = isSystemInDarkTheme()
    val bg = if (dark) FBgDark else FBgLight
    val text = if (dark) FTextDark else FTextLight
    val sub = if (dark) FSubDark else FSubLight
    val divider = if (dark) FDivDark else FDivLight

    var dir by remember { mutableStateOf<DirState?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var localGrantVisible by remember { mutableStateOf(false) } // 提示开启全部文件访问

    // ---- 数据源 1:本地文件系统直读(共享存储+授权 / 本应用目录) ----
    fun localList(path: String): DirState? {
        return try {
            val realm = FileOpenKit.realmOf(path)
            val accessible = realm == FileOpenKit.Realm.APP ||
                (realm == FileOpenKit.Realm.SHARED && FileOpenKit.hasStoragePermission(context))
            if (!accessible) return null
            val root = File(path)
            if (!root.isDirectory) return null
            val files = root.listFiles()?.filter { !it.name.startsWith(".") } ?: emptyList()
            val sorted = files.sortedWith(
                compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
            )
            val entries = sorted.map {
                Entry(
                    name = it.name,
                    path = it.absolutePath,
                    hidden = false,
                    isDir = it.isDirectory,
                    size = if (it.isFile) it.length() else -1L,
                )
            }
            DirState(
                path = path,
                home = "/storage/emulated/0",
                crumbs = listOf(Entry("/", "/"), Entry(path.substringAfterLast('/').ifEmpty { path }, path)),
                entries = entries,
                local = true,
            )
        } catch (_: Exception) {
            null
        }
    }

    // ---- 数据源 2:后端 host.listDirectory(APP 不可读路径;仅目录) ----
    suspend fun remoteList(path: String): DirState? = suspendCancellableCoroutine { cont ->
        val gw = com.aptuidsh.kui.AppRuntime.gateway()
        if (gw == null) {
            if (cont.isActive) cont.resume(null)
            return@suspendCancellableCoroutine
        }
        gw.client().hostListDirectory(path, object : DshClient.DshCallback {
            override fun onResult(value: JSONObject?) {
                if (cont.isActive) cont.resume(parseRemoteDir(value))
            }

            override fun onError(code: Int, message: String?, details: JSONObject?) {
                if (cont.isActive) cont.resume(null)
            }
        })
    }

    suspend fun goto(path: String) {
        loading = true
        error = ""
        val realm = FileOpenKit.realmOf(path)
        val needGrant = realm == FileOpenKit.Realm.SHARED && !FileOpenKit.hasStoragePermission(context)
        localGrantVisible = needGrant
        val state = withContext(Dispatchers.IO) {
            localList(path) ?: remoteList(path)
        }
        loading = false
        if (state == null) {
            error = "无法列出目录: $path"
            return
        }
        dir = state
    }

    // 首次加载:优先 intent 路径;否则主目录(共享存储/应用目录可读→真实文件,Termux→主目录)
    LaunchedEffect(Unit) {
        val gw = com.aptuidsh.kui.AppRuntime.gateway()
        val home = if (initialPath.isNotEmpty()) {
            initialPath
        } else if (gw != null) {
            withContext(Dispatchers.IO) {
                suspendCancellableCoroutine<String> { cont ->
                    gw.client().hostDescribe(object : DshClient.DshCallback {
                        override fun onResult(value: JSONObject?) {
                            if (cont.isActive) cont.resume(value?.optString("home", "") ?: "")
                        }

                        override fun onError(code: Int, message: String?, details: JSONObject?) {
                            if (cont.isActive) cont.resume("")
                        }
                    })
                }
            }.ifEmpty { "/data/data/com.termux/files/home" }
        } else "/data/data/com.termux/files/home"
        goto(home)
    }

    Column(Modifier.fillMaxSize().background(bg).statusBarsPadding()) {
        // ===== 顶栏 =====
        Row(
            Modifier
                .fillMaxWidth()
                .background(FTopLight)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val cur = dir?.path ?: ""
            val homeOf = dir?.home ?: "/data/data/com.termux/files/home"
            BarAction("⌂") { scope.launch { goto(homeOf) } }
            Spacer(Modifier.width(4.dp))
            BarAction("↑") {
                val c = dir
                if (c != null) {
                    val parent = c.path.substringBeforeLast('/', "")
                    if (parent.isNotEmpty()) scope.launch { goto(parent) }
                }
            }
            Spacer(Modifier.width(4.dp))
            BarAction("⟳") { scope.launch { goto(cur) } }
            Spacer(Modifier.width(4.dp))
            BarAction("📂") {
                scope.launch { goto("/storage/emulated/0/Download") }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "文件浏览器",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            BarAction("⧉") { FileOpenKit.copyPath(context, cur) }
            BarAction("✕") { (context as? android.app.Activity)?.finish() }
        }

        // ===== 面包屑(本地用父链回溯) =====
        val st = dir
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (dark) FDivDark.copy(alpha = 0.35f) else Color(0xFFE2E9E6))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (st == null) {
                Text("/", fontSize = 12.sp, color = sub)
            } else {
                val crumbs: List<Entry> = if (st.local) {
                    val parts = st.path.split('/').filter { it.isNotEmpty() }
                    val acc = StringBuilder("/")
                    buildList {
                        add(Entry("/", "/"))
                        parts.forEach { p ->
                            acc.append(p)
                            add(Entry(p, acc.toString()))
                            acc.append('/')
                        }
                    }
                } else st.crumbs
                crumbs.forEachIndexed { i, c ->
                    val isLast = i == crumbs.size - 1
                    Text(
                        text = if (c.name == "/") "/" else c.name,
                        fontSize = 12.sp,
                        maxLines = 1,
                        color = if (isLast) FLink else sub,
                        fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .then(
                                if (!isLast) Modifier.clickable { scope.launch { goto(c.path) } }
                                else Modifier
                            )
                            .padding(horizontal = 3.dp, vertical = 1.dp),
                    )
                    if (!isLast) Text("›", fontSize = 11.sp, color = sub)
                }
            }
        }

        // ===== 提示行(本地/远端能力说明) =====
        val curState = dir
        if (curState != null) {
            if (!curState.local && localGrantVisible) {
                NoteBar(
                    text = "未开启「所有文件访问」,仅能浏览文件夹。开启后可查看文件与大小。",
                    dark = dark,
                    actionLabel = "去开启",
                    onAction = {
                        FileOpenKit.openAllFilesSettings(context)
                        scope.launch { goto(curState.path) }
                    },
                )
            } else if (!curState.local) {
                NoteBar(
                    text = "后端列表仅含目录(不返回普通文件);文件请让助手复制到 Downloads 或点击消息中的文件路径。",
                    dark = dark,
                )
            } else {
                val dirs = curState.entries.count { it.isDir == true }
                val files = curState.entries.size - dirs
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (dark) FDivDark.copy(alpha = 0.25f) else Color(0xFFE6EBE8))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Text("$dirs 个目录 · $files 个文件", fontSize = 10.sp, color = sub)
                }
            }
        }

        // ===== 列表区 =====
        Box(Modifier.fillMaxSize()) {
            when {
                error.isNotEmpty() -> Column(
                    Modifier
                        .align(Alignment.Center)
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(error, fontSize = 13.sp, color = FError)
                    Text(
                        "重试",
                        fontSize = 13.sp,
                        color = FLink,
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                scope.launch {
                                    goto(dir?.path ?: "/data/data/com.termux/files/home")
                                }
                            }
                            .padding(8.dp),
                    )
                }
                loading && dir == null -> Text(
                    "加载中…",
                    fontSize = 13.sp,
                    color = sub,
                    modifier = Modifier.align(Alignment.Center),
                )
                dir == null -> Text(
                    "无数据",
                    fontSize = 13.sp,
                    color = sub,
                    modifier = Modifier.align(Alignment.Center),
                )
                dir!!.entries.isEmpty() -> Text(
                    if (dir!!.local) "空目录" else "无可见条目(后端仅返回目录)",
                    fontSize = 13.sp,
                    color = sub,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> {
                    val shown = dir!!.entries.filter { !it.hidden && !it.name.startsWith(".") }
                    LazyColumn(
                        contentPadding = PaddingValues(vertical = 4.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(shown) { index, e ->
                            if (index > 0) Spacer(Modifier.height(1.dp).background(divider))
                            EntryRow(
                                entry = e,
                                textColor = text,
                                subColor = sub,
                                onClick = {
                                    scope.launch {
                                        val local = dir?.local ?: false
                                        val isDir = when {
                                            e.isDir != null -> e.isDir
                                            local -> false // 本地模式条目类型一定已知
                                            else -> {
                                                // 远端模式:后端探测(能列出=目录)
                                                withContext(Dispatchers.IO) {
                                                    suspendCancellableCoroutine<Boolean> { cont ->
                                                        val gw = com.aptuidsh.kui.AppRuntime.gateway()
                                                        if (gw == null) {
                                                            if (cont.isActive) cont.resume(false)
                                                            return@suspendCancellableCoroutine
                                                        }
                                                        gw.client().hostListDirectory(
                                                            e.path,
                                                            object : DshClient.DshCallback {
                                                                override fun onResult(value: JSONObject?) {
                                                                    if (cont.isActive) cont.resume(value != null)
                                                                }

                                                                override fun onError(
                                                                    code: Int, message: String?,
                                                                    details: JSONObject?
                                                                ) {
                                                                    if (cont.isActive) cont.resume(false)
                                                                }
                                                            },
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        if (isDir) {
                                            goto(e.path)
                                        } else {
                                            FileOpenKit.openFile(context, e.path)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 顶栏小按钮。 */
@Composable
private fun BarAction(symbol: String, onClick: () -> Unit) {
    Text(
        text = symbol,
        fontSize = 15.sp,
        color = Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

/** 说明条(能力限制 / 授权引导)。 */
@Composable
private fun NoteBar(text: String, dark: Boolean, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (dark) FNoteDark else FNoteLight)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "ℹ️ " + text,
            fontSize = 11.sp,
            color = if (dark) Color(0xFFF2C879) else Color(0xFF7A5B00),
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                fontSize = 11.sp,
                color = FLink,
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/** 列表行:类型图标 + 名称 + 大小/类型说明。 */
@Composable
private fun EntryRow(
    entry: Entry,
    textColor: Color,
    subColor: Color,
    onClick: () -> Unit,
) {
    val icon = when {
        entry.isDir == true -> "📁"
        entry.isDir == false -> FileOpenKit.kindOfPath(entry.path)?.icon ?: "📄"
        entry.name.lastIndexOf('.') <= 0 -> "📁"
        else -> FileOpenKit.kindOfPath(entry.path)?.icon ?: "📄"
    }
    val subText = when {
        entry.isDir == true -> "目录"
        entry.isDir == false && entry.size >= 0 -> formatSize(entry.size)
        entry.isDir == false -> FileOpenKit.kindOfPath(entry.path)?.label ?: "文件"
        entry.name.lastIndexOf('.') <= 0 -> "目录"
        else -> FileOpenKit.kindOfPath(entry.path)?.label ?: "文件"
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, fontSize = 15.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                fontSize = 14.sp,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(subText, fontSize = 11.sp, color = subColor)
        }
        Text("›", fontSize = 13.sp, color = subColor.copy(alpha = 0.6f))
    }
}

/** 解析后端 host.listDirectory 返回(条目仅目录)。 */
private fun parseRemoteDir(value: JSONObject?): DirState? {
    if (value == null) return null
    val path = value.optString("path", "")
    val home = value.optString("home", "")
    val crumbs = ArrayList<Entry>()
    val arr = value.optJSONArray("crumbs")
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            crumbs.add(Entry(o.optString("name", ""), o.optString("path", "")))
        }
    }
    val entries = ArrayList<Entry>()
    val es = value.optJSONArray("entries")
    if (es != null) {
        for (i in 0 until es.length()) {
            val o = es.optJSONObject(i) ?: continue
            entries.add(Entry(o.optString("name", ""), o.optString("path", "")))
        }
    }
    return DirState(path, home, crumbs, entries, local = false)
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 ->
        String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}
