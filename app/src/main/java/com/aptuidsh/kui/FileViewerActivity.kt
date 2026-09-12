package com.aptuidsh.kui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aptuidsh.kui.file.FileOpenKit
import com.aptuidsh.kui.ui.theme.APTUIDSHTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val ViewerTopLight = Color(0xFF4D6BFE)
private val ViewerTextLight = Color(0xFF1A1D26)
private val ViewerTextDark = Color(0xFFE6F0EC)
private val ViewerSubLight = Color(0xFF5B6478)
private val ViewerSubDark = Color(0xFFA3B5AC)
private val ViewerBgLight = Color(0xFFFFFFFF)
private val ViewerBgDark = Color(0xFF101218)

/**
 * 内置文件查看器:文本 / 图片(按路径或 MediaStore URI 读取,防 OOM 采样解码)。
 *
 * <p>打开方式(见 FileOpenKit.openFile):文本/源码走本页;图片消息与图片文件也走本页。
 */
class FileViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("mode") ?: "text"
        val path = intent.getStringExtra("path") ?: ""
        val uri = intent.getStringExtra("uri") ?: ""
        enableEdgeToEdge()
        setContent {
            APTUIDSHTheme(darkTheme = isSystemInDarkTheme()) {
                ViewerScreen(mode = mode, path = path, uri = uri)
            }
        }
    }

    companion object {
        private fun intent(ctx: Context, mode: String, path: String, uri: String = ""): Intent =
            Intent(ctx, FileViewerActivity::class.java)
                .putExtra("mode", mode)
                .putExtra("path", path)
                .putExtra("uri", uri)

        /** 打开文本/源码文件(仅可读域:共享存储+全部文件权限 / 本应用目录)。 */
        fun startText(ctx: Context, path: String) {
            ctx.startActivity(intent(ctx, "text", path))
        }

        /** 打开图片文件(uri 为 MediaStore content URI 兜底,免全部文件权限可读)。 */
        fun startImage(ctx: Context, path: String, uri: String = "") {
            ctx.startActivity(intent(ctx, "image", path, uri))
        }
    }
}

@Composable
private fun ViewerScreen(mode: String, path: String, uri: String) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val text = if (dark) ViewerTextDark else ViewerTextLight
    val sub = if (dark) ViewerSubDark else ViewerSubLight
    val bg = if (dark) ViewerBgDark else ViewerBgLight
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().background(bg).statusBarsPadding()) {
        ViewerTopBar(title = if (mode == "image") "图片查看" else "文本查看", sub = path.substringAfterLast('/'))
        Box(Modifier.fillMaxSize().padding(12.dp)) {
            when (mode) {
                "image" -> ImageContent(path = path, uri = uri)
                else -> TextContent(path = path, uri = uri, textColor = text, subColor = sub)
            }
        }
    }
}

@Composable
private fun ViewerTopBar(title: String, sub: String) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(ViewerTopLight)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "← 返回",
                fontSize = 13.sp,
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { (context as? android.app.Activity)?.finish() }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, color = Color.White)
                if (sub.isNotEmpty()) {
                    Text(sub, fontSize = 10.sp, color = Color.White.copy(alpha = 0.8f), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun TextContent(path: String, uri: String, textColor: Color, subColor: Color) {
    val context = LocalContext.current
    var content by remember { mutableStateOf<String?>(null) }   // null=加载中
    var error by remember { mutableStateOf("") }
    var truncated by remember { mutableStateOf(false) }

    LaunchedEffect(path, uri) {
        content = null
        error = ""
        truncated = false
        val result = withContext(Dispatchers.IO) {
            try {
                if (uri.isNotEmpty() && !path.startsWith("/data/data/com.aptuidsh.kui")) {
                    readFromUri(context, uri)
                } else {
                    readFromPath(path)
                }
            } catch (e: Exception) {
                ReadResult.Fail(e.message ?: e.javaClass.simpleName)
            }
        }
        when (result) {
            is ReadResult.Ok -> {
                content = result.text
                truncated = result.truncated
            }
            is ReadResult.Fail -> error = result.message
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        when {
            error.isNotEmpty() -> {
                Text("读取失败: $error", fontSize = 13.sp, color = Color(0xFFE5484D))
                // 未授权时给一键引导
                if (Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
                    Text(
                        "去授权「所有文件访问」",
                        fontSize = 13.sp,
                        color = Color(0xFF4D6BFE),
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { FileOpenKit.openAllFilesSettings(context) }
                            .padding(6.dp),
                    )
                }
            }
            content == null -> Text("加载中…", fontSize = 13.sp, color = subColor)
            else -> {
                Text(
                    content!!,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = textColor,
                    fontFamily = FontFamily.Monospace,
                )
                if (truncated) {
                    Text("…(文件过大,仅显示前 2MB,请用 Termux 查看全文)",
                        fontSize = 11.sp, color = subColor)
                }
            }
        }
    }
}

private sealed class ReadResult {
    class Ok(val text: String, val truncated: Boolean) : ReadResult()
    class Fail(val message: String) : ReadResult()
}

private fun readFromPath(path: String): ReadResult {
    val f = File(path)
    if (!f.isFile) return ReadResult.Fail("文件不存在: $path")
    val cap = 2 * 1024 * 1024
    val bytes = if (f.length() > cap) {
        java.io.FileInputStream(f).use { input ->
            val buf = ByteArray(cap)
            var off = 0
            while (off < cap) {
                val n = input.read(buf, off, cap - off)
                if (n <= 0) break
                off += n
            }
            buf.copyOf(off)
        }
    } else {
        f.readBytes()
    }
    return ReadResult.Ok(String(bytes, Charsets.UTF_8), f.length() > cap)
}

private fun readFromUri(context: Context, uriString: String): ReadResult {
    val ins = context.contentResolver.openInputStream(Uri.parse(uriString))
        ?: return ReadResult.Fail("无法读取文件")
    ins.use { input ->
        val bos = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var n: Int
        var total = 0
        var over = false
        while (input.read(buf).also { n = it } > 0) {
            if (total + n > 2 * 1024 * 1024) {
                bos.write(buf, 0, (2 * 1024 * 1024 - total).coerceAtLeast(0))
                over = true
                break
            }
            bos.write(buf, 0, n)
            total += n
        }
        return ReadResult.Ok(String(bos.toByteArray(), Charsets.UTF_8), over)
    }
}

@Composable
private fun ImageContent(path: String, uri: String) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val sub = if (dark) ViewerSubDark else ViewerSubLight
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(path, uri) {
        bitmap = null
        error = ""
        val bmp = withContext(Dispatchers.IO) {
            try {
                val f = File(path)
                if (f.isFile) {
                    decodeSampled(f.absolutePath)
                } else if (uri.isNotEmpty()) {
                    val ins = context.contentResolver.openInputStream(Uri.parse(uri)) ?: return@withContext null
                    ins.use { BitmapFactory.decodeStream(it) }
                } else null
            } catch (e: Exception) {
                null
            }
        }
        if (bmp == null) error = "图片解码失败"
        else bitmap = bmp
    }

    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        when {
            bmp != null -> {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = path.substringAfterLast('/'),
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            error.isNotEmpty() -> Text(error, fontSize = 13.sp, color = Color(0xFFE5484D))
            else -> Text("加载中…", fontSize = 13.sp, color = sub)
        }
    }
}

/** 按屏幕宽高采样解码(防大图 OOM)。 */
private fun decodeSampled(path: String): android.graphics.Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, o)
    if (o.outWidth <= 0 || o.outHeight <= 0) return null
    val metrics = android.content.res.Resources.getSystem().displayMetrics
    val sw = metrics.widthPixels
    val sh = metrics.heightPixels
    var sample = 1
    while (o.outWidth / sample > sw * 2 || o.outHeight / sample > sh * 2) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(path, opts)
}
