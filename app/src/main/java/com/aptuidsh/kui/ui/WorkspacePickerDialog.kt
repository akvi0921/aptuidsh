package com.aptuidsh.kui.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import com.aptuidsh.kui.ui.theme.LocalDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aptuidsh.kui.AppRuntime
import com.aptuidsh.kui.R
import com.aptuidsh.kui.net.DshClient
import org.json.JSONObject

private val PrimaryBlue = Color(0xFF4D6BFE)
private val TextPrimaryLight = Color(0xFF1A1D26)
private val TextPrimaryDark = Color(0xFFE6F0EC)
private val TextSecondaryLight = Color(0xFF5B6478)
private val TextSecondaryDark = Color(0xFFA3B5AC)
private val PlaceholderLight = Color(0xFF9AA3B2)
private val PlaceholderDark = Color(0xFF7A8881)
private val BorderLight = Color(0xFFB8C4C0)
private val BorderDark = Color(0xFF3A4740)
private val DividerLight = Color(0xFFD8DEE4)
private val DividerDark = Color(0xFF2A322E)
private val IconTintDark = Color(0xFFDCE8E3)
private val DangerRed = Color(0xFFE5484D)

/** 文件夹列表容器高度(9 行)。 */
private val ListAreaHeight = 32.dp * 9f

/** 目录项(来自 host.listDirectory)。 */
private data class DirEntry(
    val name: String,
    val path: String,
    val hidden: Boolean,
)

/** 面包屑项(来自 host.listDirectory 的 crumbs)。 */
private data class CrumbItem(
    val name: String,
    val path: String,
)

/**
 * 选择工作区目录弹窗(添加工作区)。
 *
 * <p>结构:标题 → 面包屑/路径输入 → 分割线 →
 * 文件夹列表(9 行容器,可滚动,层级进入) → 分割线 →
 * 新建文件夹按钮 + 显示隐藏文件开关 → 打开/取消。
 *
 * <p>数据来自后端 host.listDirectory 真实目录浏览;
 * 新建文件夹调用 host.createDirectory;
 * 打开调用 workspace.create 创建真实工作区。
 */
@Composable
fun WorkspacePickerDialog(
    onDismiss: () -> Unit,
    onWorkspaceCreated: ((String) -> Unit)? = null, // 创建工作区后的回调(workspaceId)
) {
    val dark = LocalDarkTheme.current
    val textPrimary = if (dark) TextPrimaryDark else TextPrimaryLight
    val textSecondary = if (dark) TextSecondaryDark else TextSecondaryLight
    val placeholder = if (dark) PlaceholderDark else PlaceholderLight
    val borderColor = if (dark) BorderDark else BorderLight
    val dividerColor = if (dark) DividerDark else DividerLight
    val iconTint: Color? = if (dark) IconTintDark else null
    val bg = if (dark) SidebarBgDark else SidebarBgLight
    val context = LocalContext.current

    // 当前目录状态
    var currentPath by remember { mutableStateOf("") } // 当前路径(空=未加载)
    var homePath by remember { mutableStateOf("") }    // 主目录路径
    var crumbs by remember { mutableStateOf(listOf<CrumbItem>()) }
    var entries by remember { mutableStateOf(listOf<DirEntry>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    var showPathInput by remember { mutableStateOf(false) }
    var showHidden by remember { mutableStateOf(false) }
    var newFolderDialog by remember { mutableStateOf(false) }

    // 加载目录
    fun loadDirectory(path: String?) {
        loading = true
        error = null
        val gateway = AppRuntime.gateway()
        if (gateway == null) {
            error = "未连接后端"
            loading = false
            return
        }
        gateway.client().let { client ->
            val rpcCallback = object : DshClient.DshCallback {
                override fun onResult(value: JSONObject?) {
                    val v = value?.optJSONObject("result")?.optJSONObject("value") ?: value
                    if (v == null) {
                        error = "返回数据为空"
                        loading = false
                        return
                    }
                    currentPath = v.optString("path", "")
                    homePath = v.optString("home", "")
                    // 解析面包屑
                    val crumbsArr = v.optJSONArray("crumbs")
                    val newCrumbs = ArrayList<CrumbItem>()
                    if (crumbsArr != null) {
                        for (i in 0 until crumbsArr.length()) {
                            val c = crumbsArr.optJSONObject(i) ?: continue
                            newCrumbs.add(CrumbItem(
                                name = c.optString("name", ""),
                                path = c.optString("path", ""),
                            ))
                        }
                    }
                    crumbs = newCrumbs
                    // 解析目录项
                    val entriesArr = v.optJSONArray("entries")
                    val newEntries = ArrayList<DirEntry>()
                    if (entriesArr != null) {
                        for (i in 0 until entriesArr.length()) {
                            val e = entriesArr.optJSONObject(i) ?: continue
                            newEntries.add(DirEntry(
                                name = e.optString("name", ""),
                                path = e.optString("path", ""),
                                hidden = e.optBoolean("hidden", false),
                            ))
                        }
                    }
                    entries = newEntries
                    loading = false
                }

                override fun onError(code: Int, message: String?, details: JSONObject?) {
                    error = message ?: "加载失败"
                    loading = false
                }
            }
            // 空路径用无参版本(默认 home 目录),非空用指定路径
            if (path.isNullOrBlank()) {
                client.hostListDirectory(rpcCallback)
            } else {
                client.hostListDirectory(path, rpcCallback)
            }
        }
    }

    // 首次加载
    LaunchedEffect(Unit) {
        loadDirectory(null)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = bg),
        ) {
            // 输入框模式点击弹窗空白 → 恢复面包屑
            Box(
                Modifier.clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) {
                    if (showPathInput) showPathInput = false
                },
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    // ===== 行1 标题 =====
                    Text(
                        text = "选择工作区目录",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = textPrimary,
                    )

                    Spacer(Modifier.height(10.dp))

                    // ===== 行2 面包屑 / 路径输入(整行可点) =====
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !showPathInput) { showPathInput = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (showPathInput) {
                            // 输入框:显示当前完整路径
                            Text(
                                text = currentPath.ifEmpty { "加载中…" },
                                fontSize = 12.sp,
                                color = textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            // 面包屑:主目录 + 各级文件夹(点击跳转)
                            Row(
                                Modifier
                                    .weight(1f)
                                    .horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "主目录",
                                    fontSize = 13.sp,
                                    color = textPrimary,
                                    modifier = Modifier.clickable {
                                        loadDirectory(homePath)
                                    },
                                )
                                crumbs.drop(1).forEachIndexed { i, crumb ->
                                    Text(" › ", fontSize = 13.sp, color = textSecondary)
                                    Text(
                                        text = crumb.name,
                                        fontSize = 13.sp,
                                        color = textPrimary,
                                        modifier = Modifier.clickable {
                                            loadDirectory(crumb.path)
                                        },
                                    )
                                }
                            }
                        }
                        // 右侧图标
                        Spacer(Modifier.width(8.dp))
                        Image(
                            painter = painterResource(R.drawable.ic_ws_path),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            contentScale = ContentScale.Fit,
                            colorFilter = iconTint?.let { ColorFilter.tint(it) },
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    // ===== 行3 分割线 =====
                    HorizontalDivider(color = dividerColor)

                    // ===== 行4~12 文件夹列表(9 行容器,可滚动,层级进入) =====
                    val filteredEntries = entries.filter { !it.hidden || showHidden }
                    if (loading) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(ListAreaHeight),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("加载中…", fontSize = 13.sp, color = textSecondary)
                        }
                    } else if (error != null) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(ListAreaHeight),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(error ?: "错误", fontSize = 13.sp, color = DangerRed)
                        }
                    } else {
                        LazyColumn(Modifier.height(ListAreaHeight)) {
                            items(filteredEntries, key = { it.path }) { entry ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { loadDirectory(entry.path) }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // 左侧文件夹图标
                                    Image(
                                        painter = painterResource(R.drawable.ic_ws_item),
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        contentScale = ContentScale.Fit,
                                        colorFilter = iconTint?.let { ColorFilter.tint(it) },
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = entry.name,
                                        fontSize = 13.sp,
                                        color = if (entry.hidden) textSecondary else textPrimary,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    // 右侧箭头图标
                                    Image(
                                        painter = painterResource(R.drawable.ic_com_model),
                                        contentDescription = null,
                                        modifier = Modifier.size(13.dp),
                                        contentScale = ContentScale.Fit,
                                        colorFilter = iconTint?.let { ColorFilter.tint(it) },
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // ===== 行13 分割线 =====
                    HorizontalDivider(color = dividerColor)

                    Spacer(Modifier.height(8.dp))

                    // ===== 行14 新建文件夹 + 显示隐藏文件 =====
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 新建文件夹
                        Box(
                            Modifier
                                .border(1.dp, borderColor)
                                .clickable { newFolderDialog = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Image(
                                    painter = painterResource(R.drawable.ic_ws_add),
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    contentScale = ContentScale.Fit,
                                    colorFilter = iconTint?.let { ColorFilter.tint(it) },
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("新建文件夹", fontSize = 13.sp, color = textPrimary)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        // 显示隐藏文件
                        Text(
                            text = if (showHidden) "显示隐藏文件 ✓" else "显示隐藏文件",
                            fontSize = 13.sp,
                            color = if (showHidden) DangerRed else textSecondary,
                            modifier = Modifier.clickable { showHidden = !showHidden },
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // ===== 行15 打开 + 取消 =====
                    Row {
                        Box(
                            Modifier
                                .weight(1f)
                                .border(1.dp, borderColor)
                                .clickable {
                                    // 调用 workspace.create 创建真实工作区
                                    val gateway = AppRuntime.gateway()
                                    if (gateway != null && currentPath.isNotEmpty()) {
                                        gateway.client().workspaceCreate(currentPath, object : DshClient.DshCallback {
                                            override fun onResult(value: JSONObject?) {
                                                val ws = value?.optJSONObject("result")?.optJSONObject("value")?.optJSONObject("workspace")
                                                    ?: value?.optJSONObject("workspace")
                                                val wsId = ws?.optString("workspaceId", "") ?: ""
                                                Toast.makeText(context, "工作区已创建: ${ws?.optString("title", currentPath)}", Toast.LENGTH_SHORT).show()
                                                if (wsId.isNotEmpty()) {
                                                    onWorkspaceCreated?.invoke(wsId)
                                                }
                                                onDismiss()
                                            }

                                            override fun onError(code: Int, message: String?, details: JSONObject?) {
                                                Toast.makeText(context, "创建失败: $message", Toast.LENGTH_SHORT).show()
                                            }
                                        })
                                    }
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("打开", fontSize = 14.sp, color = textPrimary)
                        }
                        Spacer(Modifier.width(10.dp))
                        Box(
                            Modifier
                                .weight(1f)
                                .border(1.dp, borderColor)
                                .clickable(onClick = onDismiss)
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("取消", fontSize = 14.sp, color = textPrimary)
                        }
                    }
                }
            }
        }
    }

    // ===== 新建文件夹子弹窗 =====
    if (newFolderDialog) {
        NewFolderDialog(
            currentPath = currentPath,
            onDismiss = { newFolderDialog = false },
            onCreated = { loadDirectory(currentPath) }, // 创建后刷新列表
        )
    }
}

/** 新建文件夹弹窗(调用 host.createDirectory)。 */
@Composable
private fun NewFolderDialog(
    currentPath: String,
    onDismiss: () -> Unit,
    onCreated: () -> Unit,
) {
    val dark = LocalDarkTheme.current
    val textPrimary = if (dark) TextPrimaryDark else TextPrimaryLight
    val textSecondary = if (dark) TextSecondaryDark else TextSecondaryLight
    val placeholder = if (dark) PlaceholderDark else PlaceholderLight
    val borderColor = if (dark) BorderDark else BorderLight
    val bg = if (dark) SidebarBgDark else SidebarBgLight
    val context = LocalContext.current

    var name by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = bg),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(
                    text = "新建文件夹",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "在 \"$currentPath\" 中新建文件夹",
                    fontSize = 12.sp,
                    color = textSecondary,
                )
                Spacer(Modifier.height(10.dp))
                // 输入框
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    textStyle = TextStyle(fontSize = 14.sp, color = textPrimary),
                    cursorBrush = SolidColor(PrimaryBlue),
                    decorationBox = { inner ->
                        Box {
                            if (name.isEmpty()) {
                                Text("未命名文件夹", fontSize = 14.sp, color = placeholder)
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
                Row {
                    Box(
                        Modifier
                            .weight(1f)
                            .border(1.dp, borderColor)
                            .clickable(onClick = onDismiss)
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("取消", fontSize = 14.sp, color = textPrimary)
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .weight(1f)
                            .border(1.dp, borderColor)
                            .clickable(enabled = !creating && name.isNotBlank()) {
                                creating = true
                                val gateway = AppRuntime.gateway()
                                if (gateway != null) {
                                    gateway.client().hostCreateDirectory(currentPath, name.trim(), object : DshClient.DshCallback {
                                        override fun onResult(value: JSONObject?) {
                                            Toast.makeText(context, "已创建: ${name.trim()}", Toast.LENGTH_SHORT).show()
                                            creating = false
                                            onCreated()
                                            onDismiss()
                                        }

                                        override fun onError(code: Int, message: String?, details: JSONObject?) {
                                            Toast.makeText(context, "创建失败: $message", Toast.LENGTH_SHORT).show()
                                            creating = false
                                        }
                                    })
                                } else {
                                    creating = false
                                }
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (creating) "创建中…" else "创建",
                            fontSize = 14.sp,
                            color = if (name.isNotBlank()) textPrimary else placeholder,
                        )
                    }
                }
            }
        }
    }
}
