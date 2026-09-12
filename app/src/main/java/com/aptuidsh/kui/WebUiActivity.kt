package com.aptuidsh.kui

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.aptuidsh.kui.env.DshAuth
import com.aptuidsh.kui.env.DshBackend
import com.aptuidsh.kui.env.ProrootEnv
import com.aptuidsh.kui.ui.theme.APTUIDSHTheme

/**
 * 内嵌官方 dsh Web UI。
 *
 * <p>定位：原生 Compose 界面（继承自 dsh-aui）是主界面；本页把 dsh 自带的官方 Web UI
 * 原样嵌进来作为对照与兜底——它由 dsh 本体自带，永远与后端版本严格同步。
 *
 * <h3>鉴权</h3>
 * dsh ≥ 0.1.5 的 Web UI 需要携带签名 Cookie。这里优先使用官方推荐路径：
 * 直接把 `/?token=<launchToken>` 交给 WebView，服务端会 303 下发 Cookie 并跳到干净的 `/`。
 * 若令牌尚未捕获（例如刚冷启），则退化为主动把已持有的 Cookie 写进 WebView 的 CookieJar。
 */
class WebUiActivity : ComponentActivity() {

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            APTUIDSHTheme {
                WebUiPage(
                    onBack = { finish() },
                    onReload = { reload() },
                    onCreateWebView = { ctx -> createWebView(ctx) },
                    onAttach = { wv -> webView = wv },
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: android.content.Context): WebView {
        val wv = WebView(context)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = WebViewClient()
        wv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        return wv
    }

    private fun targetUrl(): String {
        val token = DshAuth.launchToken()
        return if (!token.isNullOrEmpty()) {
            "${ProrootEnv.BASE_URL}/?token=$token"
        } else {
            val cookie = DshAuth.cookieHeader()
            if (!cookie.isNullOrEmpty()) {
                try {
                    CookieManager.getInstance().setCookie(ProrootEnv.BASE_URL + "/", cookie)
                    CookieManager.getInstance().flush()
                } catch (t: Throwable) {
                    Log.w(TAG, "inject cookie failed: $t")
                }
            }
            ProrootEnv.BASE_URL + "/"
        }
    }

    private fun reload() {
        val wv = webView ?: return
        // 探活是网络操作，绝不能在主线程做（否则 NetworkOnMainThreadException 崩溃）
        Thread {
            try {
                if (!DshBackend.get().probeDsh(this)) {
                    DshServiceWrapper.start(this)
                }
            } catch (t: Throwable) {
                com.aptuidsh.kui.env.EnvLog.e("WebUI 探活失败", t)
            }
            runOnUiThread { wv.loadUrl(targetUrl()) }
        }.start()
    }

    override fun onDestroy() {
        webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AptuDshWebUi"
    }
}

/** 小包装：避免在 Compose 回调里直接持有 Service 调用链。 */
private object DshServiceWrapper {
    fun start(ctx: android.content.Context) {
        com.aptuidsh.kui.env.DshService.requestStart(ctx)
    }
}

@Composable
private fun WebUiPage(
    onBack: () -> Unit,
    onReload: () -> Unit,
    onCreateWebView: (android.content.Context) -> WebView,
    onAttach: (WebView) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var loading by remember { mutableStateOf(true) }
    // 注意：探活是网络操作。首版在这里直接调用 probePort()，
    // 由于它位于组合期（主线程）→ NetworkOnMainThreadException → 一开本页就崩溃。
    var backendAlive by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        backendAlive = withContext(Dispatchers.IO) {
            runCatching { DshBackend.get().probeDsh(context) }.getOrDefault(false)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            Text(
                text = "官方 Web UI · ${ProrootEnv.BASE_URL}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(Modifier.weight(1f))
            TextButton(onClick = onReload) { Text("重新加载") }
        }
        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val wv = onCreateWebView(ctx)
                    onAttach(wv)
                    val token = DshAuth.launchToken()
                    val url = if (!token.isNullOrEmpty()) {
                        "${ProrootEnv.BASE_URL}/?token=$token"
                    } else {
                        ProrootEnv.BASE_URL + "/"
                    }
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            loading = false
                        }
                    }
                    try {
                        wv.loadUrl(url)
                    } catch (t: Throwable) {
                        com.aptuidsh.kui.env.EnvLog.e("WebView.loadUrl 失败", t)
                    }
                    wv
                },
            )
            if (backendAlive == false) {
                Text(
                    text = "内置 dsh 后端尚未就绪，请先在主界面启动环境",
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
