package com.aptuidsh.kui

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import com.aptuidsh.kui.env.DshAuth
import com.aptuidsh.kui.env.DshBackend
import com.aptuidsh.kui.env.DshService
import com.aptuidsh.kui.env.EnvLog
import com.aptuidsh.kui.env.ProrootEnv
import com.aptuidsh.kui.ui.theme.APTUIDSHTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 内嵌官方 dsh Web UI。
 *
 * <p>定位：原生 Compose 界面（继承自 dsh-aui）是主界面；本页把 dsh 自带的官方 Web UI
 * 原样嵌进来作为对照与兜底——它由 dsh 本体自带，永远与后端版本严格同步。
 *
 * <h3>两处必要适配</h3>
 * <ol>
 *   <li><b>鉴权</b>：dsh ≥ 0.1.5 的 Web UI 需要签名 Cookie。优先走官方路径——把
 *       {@code /?token=<launchToken>} 交给 WebView，服务端 303 下发 Cookie 并跳到干净的 {@code /}。</li>
 *   <li><b>兼容性垫片</b>：官方界面内嵌的 PDF.js 用到 {@code Iterator}（Chromium 122+ 才有的
 *       JS 全局对象），较旧的系统 WebView 上会直接抛
 *       {@code ReferenceError: Iterator is not defined}，表现为「Failed to load plugins」整页报错。
 *       这里用 {@code shouldInterceptRequest} 拦下根文档、在 {@code <head>} 之后插入
 *       [WebPolyfill.SCRIPT]，确保垫片先于任何模块脚本执行。</li>
 * </ol>
 */
class WebUiActivity : ComponentActivity() {

    private var webView: WebView? = null

    /**
     * 页面错误轮询：把 WebView 里收集到的 JS 报错转发进环境控制台日志。
     *
     * <p>WebView 没有控制台，官方界面里任何一个客户端插件加载失败，用户只会看到一个
     * 语焉不详的界面（例如文件预览显示「文件资源服务不可用」），真正的原因
     * （哪个模块、抛了什么）完全拿不到 —— 本机也没有 logcat。这里每 2 秒拉一次
     * `window.__aptuidshErrors` 的新增项，写进 EnvLog；用户用环境控制台的
     * 「一键复制日志」就能把一手证据回传。
     */
    private val errorPoller = object : Runnable {
        override fun run() {
            val wv = webView
            if (wv != null) {
                wv.evaluateJavascript(
                    "(function(){try{var e=window.__aptuidshErrors||[];" +
                            "var out=e.slice(" + reportedErrors + ").join('\\n');" +
                            "return out;}catch(err){return '';}})()",
                ) { result -> drainWebErrors(result) }
            }
            handler.postDelayed(this, 2000)
        }
    }
    private val handler = Handler(Looper.getMainLooper())

    /** 已经转发过多少条，避免重复刷日志。 */
    private var reportedErrors = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            APTUIDSHTheme {
                WebUiPage(
                    onBack = { finish() },
                    onReload = { reload() },
                    onCreateWebView = { ctx, onLoaded -> createWebView(ctx, onLoaded) },
                    onAttach = { wv -> webView = wv },
                )
            }
        }
        handler.postDelayed(errorPoller, 3000)
    }

    /**
     * 解码 evaluateJavascript 回传的字符串并把新增的网页报错写进日志。
     *
     * <p>`evaluateJavascript` 回传的是 **JSON 字符串字面量**（带引号与转义），
     * 所以要先用 JSONTokener 解一次，不能直接当普通文本用。
     */
    private fun drainWebErrors(raw: String?) {
        if (raw.isNullOrEmpty() || raw == "null") return
        val text = try {
            val v = org.json.JSONTokener(raw).nextValue()
            if (v is String) v else return
        } catch (t: Throwable) {
            return
        }
        val lines = text.split('\n').filter { it.isNotBlank() }
        if (lines.isEmpty()) return
        reportedErrors += lines.size
        for (line in lines) {
            EnvLog.w("[web] " + line)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(
        context: android.content.Context,
        onLoaded: () -> Unit,
    ): WebView {
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
            builtInZoomControls = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.webChromeClient = WebChromeClient()
        // 注意：这里装的客户端负责注入垫片，后续任何地方都不要再覆盖 webViewClient
        wv.webViewClient = PolyfillInjectingClient(onLoaded)
        wv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        try {
            EnvLog.i("WebView UA: " + wv.settings.userAgentString)
        } catch (t: Throwable) {
            Log.w(TAG, "read UA failed", t)
        }
        return wv
    }

    /**
     * 拦截根文档并注入兼容性垫片。
     *
     * <p>只处理主框架、路径为 {@code /} 或 {@code /index.html} 的请求；其余返回 null 交给
     * WebView 正常加载。任何异常都退化为「不拦截」，保证最坏情况只是没有垫片，而不是打不开页面。
     */
    private inner class PolyfillInjectingClient(private val onLoaded: () -> Unit) : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            try {
                if (!request.isForMainFrame) return null
                val url: Uri = request.url
                var target = url.toString()
                if (!target.startsWith(ProrootEnv.BASE_URL)) return null
                val path = url.path ?: return null
                if (path != "/" && path != "/index.html") return null

                // 关键：必须自己跟随重定向。
                // 首次加载的是 /?token=<launchToken>，服务端返回 303 并在 Set-Cookie 里下发
                // 签名 Cookie；而 WebView 跟随重定向后的那次请求**不会再经过
                // shouldInterceptRequest**，导致垫片永远注不进去（实测日志：
                // "跳过垫片注入：根文档返回 HTTP 303" 之后 "typeof Iterator = undefined"）。
                // 这里手动跟完重定向链，把 Set-Cookie 写进 WebView 的 CookieJar，再对最终
                // 的 200 HTML 注入垫片。
                var cookie = CookieManager.getInstance().getCookie(ProrootEnv.BASE_URL + "/")
                var hops = 0
                while (hops++ < 5) {
                    val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 8000
                        readTimeout = 15000
                        instanceFollowRedirects = false
                        setRequestProperty("Connection", "close")
                        if (!cookie.isNullOrEmpty()) setRequestProperty("Cookie", cookie)
                    }
                    val code = conn.responseCode

                    // 捕获并保存服务端下发的 Cookie（token 交换就靠这一步）
                    conn.headerFields["Set-Cookie"]?.forEach { raw ->
                        val pair = raw.substringBefore(';').trim()
                        if (pair.isNotEmpty()) {
                            CookieManager.getInstance().setCookie(ProrootEnv.BASE_URL + "/", pair)
                            EnvLog.i("已保存服务端下发的 Cookie: " + pair.substringBefore('=') + "=…")
                        }
                    }
                    CookieManager.getInstance().flush()

                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        val loc = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (loc.isNullOrEmpty()) {
                            EnvLog.w("重定向缺少 Location，放弃注入")
                            return null
                        }
                        target = URL(URL(target), loc).toString()
                        cookie = CookieManager.getInstance().getCookie(ProrootEnv.BASE_URL + "/")
                        continue
                    }
                    if (code != HttpURLConnection.HTTP_OK) {
                        conn.disconnect()
                        EnvLog.w("跳过垫片注入：根文档最终返回 HTTP " + code)
                        return null
                    }
                    val html = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    conn.disconnect()

                    val injected = WebPolyfill.inject(html)
                    EnvLog.i("已向官方 Web UI 注入兼容性垫片（原文档 " + html.length + " 字节，"
                            + hops + " 跳）")
                    return WebResourceResponse(
                        "text/html", "utf-8", injected.byteInputStream(Charsets.UTF_8))
                }
                EnvLog.w("重定向链超过 5 跳，放弃注入")
                return null
            } catch (t: Throwable) {
                EnvLog.w("注入垫片失败（将按原页面加载）: " + t)
                return null
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            onLoaded()
            // 垫片是否生效，直接问页面自己；这条日志是排查官方 UI 报错的第一手线索
            view?.evaluateJavascript(
                "(function(){try{return String(typeof Iterator);}catch(e){return 'error:'+e;}})()"
            ) { result ->
                EnvLog.i("页面内 typeof Iterator = " + result)
            }
        }
    }

    /** 官方推荐路径：用令牌 URL，让服务端下发 Cookie 并跳转到干净的 `/`。 */
    private fun targetUrl(): String {
        val token = DshAuth.launchToken()
        if (!token.isNullOrEmpty()) {
            return "${ProrootEnv.BASE_URL}/?token=$token"
        }
        val cookie = DshAuth.cookieHeader()
        if (!cookie.isNullOrEmpty()) {
            try {
                CookieManager.getInstance().setCookie(ProrootEnv.BASE_URL + "/", cookie)
                CookieManager.getInstance().flush()
            } catch (t: Throwable) {
                Log.w(TAG, "inject cookie failed", t)
            }
        }
        return ProrootEnv.BASE_URL + "/"
    }

    private fun reload() {
        val wv = webView ?: return
        // 探活是网络操作，绝不能在主线程做（否则 NetworkOnMainThreadException 崩溃）
        Thread {
            try {
                if (!DshBackend.get().probeDsh(this)) {
                    DshService.requestStart(this)
                }
            } catch (t: Throwable) {
                EnvLog.e("WebUI 探活失败", t)
            }
            runOnUiThread { wv.loadUrl(targetUrl()) }
        }.start()
    }

    override fun onDestroy() {
        handler.removeCallbacks(errorPoller)
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

@Composable
private fun WebUiPage(
    onBack: () -> Unit,
    onReload: () -> Unit,
    onCreateWebView: (android.content.Context, () -> Unit) -> WebView,
    onAttach: (WebView) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var loading by remember { mutableStateOf(true) }
    // 探活是网络操作：首版在组合期直接调用 probePort()（主线程）导致一开本页就崩溃，
    // 现改为 IO 线程异步探测。
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
                    val wv = onCreateWebView(ctx) { loading = false }
                    onAttach(wv)
                    try {
                        val token = DshAuth.launchToken()
                        wv.loadUrl(
                            if (!token.isNullOrEmpty()) "${ProrootEnv.BASE_URL}/?token=$token"
                            else ProrootEnv.BASE_URL + "/"
                        )
                    } catch (t: Throwable) {
                        EnvLog.e("WebView.loadUrl 失败", t)
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
