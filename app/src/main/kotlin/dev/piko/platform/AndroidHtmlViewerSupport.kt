package dev.piko.platform

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.Build
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import dev.piko.ui.platform.HtmlViewerSupport
import java.io.ByteArrayInputStream
import kotlinx.coroutines.flow.Flow

/**
 * Android 原生沙箱 `WebView` 实现：
 * - 默认启用 JavaScript（支持 SingleFile 归档与交互目录），但零 `@JavascriptInterface` 暴露；
 * - 严格禁止 `file://`、`content://`、`domStorage`、`database` 与混合内容；
 * - 仅允许在 [allowedUrlPrefix] 内加载与导航，拦截并拒绝向本机其他回环路径/会话的越界请求；
 * - 外部 `http(s)` 链接安全交由系统浏览器打开，拦截 `javascript:` / `intent:` / `file:` 等高危 scheme。
 */
internal class AndroidHtmlViewerSupport : HtmlViewerSupport {
    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    override fun Content(
        url: String,
        allowedUrlPrefix: String,
        javascriptEnabled: Boolean,
        darkTheme: Boolean,
        isEInk: Boolean,
        reloadTrigger: Int,
        onCanGoBackChanged: (Boolean) -> Unit,
        onTitleChanged: (String?) -> Unit,
        onLoadingChanged: (Boolean) -> Unit,
        onOpenExternalUrl: (String) -> Unit,
        goBackEvents: Flow<Unit>,
        modifier: Modifier,
    ) {
        val latestAllowedPrefix by rememberUpdatedState(allowedUrlPrefix)
        val latestCanGoBack by rememberUpdatedState(onCanGoBackChanged)
        val latestTitle by rememberUpdatedState(onTitleChanged)
        val latestLoading by rememberUpdatedState(onLoadingChanged)
        val latestExternal by rememberUpdatedState(onOpenExternalUrl)
        val latestDark by rememberUpdatedState(darkTheme)
        val latestEInk by rememberUpdatedState(isEInk)
        val latestJsEnabled by rememberUpdatedState(javascriptEnabled)

        val surfaceArgb = MaterialTheme.colorScheme.surface.toArgb()
        var webViewRef by remember { mutableStateOf<WebView?>(null) }
        var loadedUrl by remember { mutableStateOf<String?>(null) }
        var appliedReload by remember { mutableStateOf(reloadTrigger) }
        var appliedJs by remember { mutableStateOf(javascriptEnabled) }

        LaunchedEffect(goBackEvents, webViewRef) {
            goBackEvents.collect {
                val wv = webViewRef ?: return@collect
                if (wv.canGoBack()) {
                    wv.goBack()
                }
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                val wv = webViewRef
                webViewRef = null
                if (wv != null) {
                    runCatching {
                        wv.stopLoading()
                        wv.webChromeClient = null
                        wv.webViewClient = WebViewClient()
                        wv.loadUrl("about:blank")
                        wv.clearHistory()
                        wv.removeAllViews()
                        wv.destroy()
                    }
                }
            }
        }

        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(if (latestEInk) AndroidColor.WHITE else surfaceArgb)
                    configureSandboxSettings(settings, javascriptEnabled = latestJsEnabled, darkTheme = latestDark, isEInk = latestEInk)
                    webChromeClient = object : WebChromeClient() {
                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            latestTitle(title?.takeIf { it.isNotBlank() && !it.startsWith("http://127.0.0.1") })
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val uri = request?.url ?: return true
                            val raw = uri.toString()
                            if (raw == "about:blank") return false
                            val prefix = latestAllowedPrefix
                            val host = uri.host?.lowercase()
                            val isLoopback = host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]"
                            if (
                                prefix.isNotEmpty() &&
                                isLoopback &&
                                uri.pathSegments.none { it == "." || it == ".." } &&
                                raw.startsWith(prefix)
                            ) {
                                return false
                            }
                            val scheme = uri.scheme?.lowercase()
                            if ((scheme == "http" || scheme == "https") && request.isForMainFrame && request.hasGesture()) {
                                latestExternal(raw)
                                return true
                            }
                            // 阻止非用户手势跳转、子框架跳转及 file: / content: / javascript: / intent: / data: 等顶层跳转
                            return true
                        }

                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val uri = request?.url ?: return blockedResponse()
                            val raw = uri.toString()
                            if (raw == "about:blank") return null
                            val scheme = uri.scheme?.lowercase()
                            if (scheme == "data") return null
                            val host = uri.host?.lowercase()
                            val prefix = latestAllowedPrefix
                            val isLoopback = host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]"
                            if (
                                prefix.isNotEmpty() &&
                                (scheme == "http" || scheme == "https") &&
                                isLoopback &&
                                uri.pathSegments.none { it == "." || it == ".." } &&
                                raw.startsWith(prefix)
                            ) {
                                return super.shouldInterceptRequest(view, request)
                            }
                            return blockedResponse()
                        }

                        override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                            latestLoading(true)
                            latestCanGoBack(view?.canGoBack() == true)
                        }

                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
                            latestLoading(false)
                            latestCanGoBack(view?.canGoBack() == true)
                            if (view != null && latestJsEnabled) {
                                applyThemeCss(view, darkTheme = latestDark, isEInk = latestEInk)
                            }
                        }

                        override fun doUpdateVisitedHistory(view: WebView?, pageUrl: String?, isReload: Boolean) {
                            latestCanGoBack(view?.canGoBack() == true)
                        }
                    }
                    webViewRef = this
                }
            },
            update = { webView ->
                webView.setBackgroundColor(if (isEInk) AndroidColor.WHITE else surfaceArgb)
                configureSandboxSettings(webView.settings, javascriptEnabled = javascriptEnabled, darkTheme = darkTheme, isEInk = isEInk)
                val needsLoad = loadedUrl != url
                val needsReload = !needsLoad && (appliedReload != reloadTrigger || appliedJs != javascriptEnabled)
                appliedReload = reloadTrigger
                appliedJs = javascriptEnabled
                if (needsLoad) {
                    loadedUrl = url
                    webView.loadUrl(url)
                } else if (needsReload) {
                    webView.reload()
                }
            },
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun configureSandboxSettings(
        settings: WebSettings,
        javascriptEnabled: Boolean,
        darkTheme: Boolean,
        isEInk: Boolean,
    ) {
        settings.javaScriptEnabled = javascriptEnabled
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.domStorageEnabled = false
        settings.databaseEnabled = false
        settings.setGeolocationEnabled(false)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.safeBrowsingEnabled = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            settings.isAlgorithmicDarkeningAllowed = darkTheme && !isEInk
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            settings.forceDark = if (darkTheme && !isEInk) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
        }
    }

    private fun applyThemeCss(webView: WebView, darkTheme: Boolean, isEInk: Boolean) {
        val css = when {
            isEInk -> EINK_CSS
            darkTheme && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> DARK_FALLBACK_CSS
            else -> return
        }
        val script = """
            (function(){
              var id = '__piko_reader_theme__';
              var el = document.getElementById(id);
              if (!el) {
                el = document.createElement('style');
                el.id = id;
                (document.head || document.documentElement).appendChild(el);
              }
              el.textContent = ${jsStringLiteral(css)};
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        403,
        "Forbidden",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )

    private fun jsStringLiteral(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private companion object {
        const val EINK_CSS =
            "html, body { background: #FFFFFF !important; color: #000000 !important; } " +
                "a { color: #000000 !important; text-decoration: underline !important; } " +
                "* { box-shadow: none !important; text-shadow: none !important; transition: none !important; animation: none !important; }"
        const val DARK_FALLBACK_CSS =
            ":root { color-scheme: dark; }"
    }
}
