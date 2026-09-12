package com.aptuidsh.kui

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.aptuidsh.kui.file.FileOpenKit
import com.aptuidsh.kui.ui.theme.APTUIDSHTheme
import java.io.File
import kotlinx.coroutines.delay

/**
 * 内置文件播放器(不依赖系统播放应用,本端自研):
 * <ul>
 *   <li>视频:VideoView + MediaController(系统控件提供播放/暂停/进度/全屏);</li>
 *   <li>音频:MediaPlayer(prepareAsync) + 自定义进度条/播放暂停/时间显示;</li>
 *   <li>数据源优先直读路径文件,否则用 content URI(MediaStore / 自建 Provider)。</li>
 * </ul>
 */
class PlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("mode") ?: "audio"
        val path = intent.getStringExtra("path") ?: ""
        val uri = intent.getStringExtra("uri") ?: ""
        enableEdgeToEdge()
        setContent {
            APTUIDSHTheme(darkTheme = isSystemInDarkTheme()) {
                PlayerScreen(mode = mode, path = path, uri = uri)
            }
        }
    }

    companion object {
        private fun intent(ctx: Context, mode: String, path: String, uri: String): Intent =
            Intent(ctx, PlayerActivity::class.java)
                .putExtra("mode", mode)
                .putExtra("path", path)
                .putExtra("uri", uri)

        /** 打开内置播放器(isVideo=true → 视频,false → 音频)。 */
        fun start(ctx: Context, isVideo: Boolean, path: String, uri: String) {
            ctx.startActivity(intent(ctx, if (isVideo) "video" else "audio", path, uri))
        }
    }
}

private val PlayerBg = Color(0xFF000000)
private val PlayerTitle = Color(0xFFFFFFFF)
private val PlayerSub = Color(0xFFBBBBBB)
private val PlayerAccent = Color(0xFF4D6BFE)

@Composable
private fun PlayerScreen(mode: String, path: String, uri: String) {
    val context = LocalContext.current
    val name = path.substringAfterLast('/').ifEmpty { if (mode == "video") "视频" else "音频" }
    Box(Modifier.fillMaxSize().background(PlayerBg).statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            // ---- 顶栏:关闭 + 名称 ----
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "← 关闭",
                    fontSize = 13.sp,
                    color = PlayerTitle,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { (context as? android.app.Activity)?.finish() }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    (if (mode == "video") "🎬 " else "🎵 ") + name,
                    fontSize = 14.sp,
                    color = PlayerTitle,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
            if (mode == "video") {
                VideoSurface(path = path, uri = uri, name = name)
            } else {
                AudioSurface(path = path, uri = uri, name = name)
            }
        }
    }
}

/** 可读数据源:优先本地文件,其次 content URI。 */
private fun readableSource(path: String, uri: String): Any? = when {
    path.isNotEmpty() && File(path).isFile -> path
    uri.isNotEmpty() -> Uri.parse(uri)
    else -> null
}

// ==================== 视频:VideoView + MediaController ====================

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.VideoSurface(path: String, uri: String, name: String) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxWidth().weight(1f)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                VideoView(ctx).apply {
                    setMediaController(MediaController(ctx).apply { setAnchorView(this@apply) })
                    setOnPreparedListener { loading = false }
                    setOnErrorListener { mp, what, extra ->
                        Toast.makeText(ctx, "视频播放失败($what/$extra)", Toast.LENGTH_LONG).show()
                        true
                    }
                    when (val src = readableSource(path, uri)) {
                        is String -> setVideoPath(src)
                        is Uri -> setVideoURI(src)
                        else -> {
                            Toast.makeText(ctx, "视频文件不可读", Toast.LENGTH_LONG).show()
                            (ctx as? android.app.Activity)?.finish()
                        }
                    }
                    start()
                }
            },
        )
        if (loading) {
            Text(
                "加载中…",
                fontSize = 14.sp,
                color = PlayerSub,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        // 点击 VideoView 之外的区域时提示名称
        Text(
            "🎬 $name",
            fontSize = 12.sp,
            color = PlayerTitle.copy(alpha = 0.5f),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp),
        )
    }
}

// ==================== 音频:MediaPlayer + 进度/播放控制 ====================

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.AudioSurface(path: String, uri: String, name: String) {
    val context = LocalContext.current
    var prepared by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }

    val player = remember { MediaPlayer() }

    fun bind() {
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        val src = readableSource(path, uri)
        when {
            src is String -> player.setDataSource(src)
            src is Uri -> player.setDataSource(context, src)
            else -> error = "音频文件不可读: $path"
        }
        player.setOnPreparedListener {
            durationMs = player.duration.toLong().coerceAtLeast(0L)
            prepared = true
            try {
                player.start()
                playing = true
            } catch (_: Exception) {}
        }
        player.setOnCompletionListener {
            playing = false
            positionMs = 0L
            try {
                player.seekTo(0)
            } catch (_: Exception) {}
        }
        player.setOnErrorListener { mp, what, extra ->
            error = "播放失败($what/$extra)"
            true
        }
        try {
            player.prepareAsync()
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }

    DisposableEffect(Unit) {
        bind()
        onDispose {
            try {
                if (player.isPlaying) player.stop()
            } catch (_: Exception) {}
            try {
                player.release()
            } catch (_: Exception) {}
        }
    }

    // 进度心跳(500ms 刷新当前播放位置)
    LaunchedEffect(prepared, playing) {
        while (prepared && !dragging) {
            positionMs = try {
                player.currentPosition.toLong().coerceAtLeast(0L)
            } catch (_: Exception) {
                0L
            }
            delay(500)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "🎵 $name",
            fontSize = 17.sp,
            color = PlayerTitle,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
        )
        Spacer(Modifier.height(26.dp))

        when {
            error.isNotEmpty() -> {
                Text(error, fontSize = 13.sp, color = Color(0xFFFF7B72))
                Text(
                    "关闭",
                    fontSize = 13.sp,
                    color = PlayerAccent,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { (context as? android.app.Activity)?.finish() }
                        .padding(6.dp),
                )
            }
            !prepared -> Text("加载中…", fontSize = 14.sp, color = PlayerSub)
            else -> {
                // 进度条(拖动结束才 seek)
                Slider(
                    value = positionMs.toFloat().coerceIn(0f, durationMs.coerceAtLeast(1L).toFloat()),
                    onValueChange = {
                        positionMs = it.toLong()
                        dragging = true
                    },
                    onValueChangeFinished = {
                        dragging = false
                        try {
                            player.seekTo(positionMs.toInt())
                        } catch (_: Exception) {}
                    },
                    valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(time(positionMs), fontSize = 12.sp, color = PlayerSub)
                    Text(time(durationMs), fontSize = 12.sp, color = PlayerSub)
                }
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 播放/暂停
                    Text(
                        if (playing) "⏸ 暂停" else "▶ 播放",
                        fontSize = 16.sp,
                        color = PlayerTitle,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(PlayerAccent)
                            .clickable {
                                try {
                                    if (player.isPlaying) {
                                        player.pause()
                                        playing = false
                                    } else {
                                        player.start()
                                        playing = true
                                    }
                                } catch (_: Exception) {}
                            }
                            .padding(horizontal = 22.dp, vertical = 10.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "关闭",
                        fontSize = 15.sp,
                        color = PlayerSub,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { (context as? android.app.Activity)?.finish() }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

private fun time(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0L)
    return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60)
}
