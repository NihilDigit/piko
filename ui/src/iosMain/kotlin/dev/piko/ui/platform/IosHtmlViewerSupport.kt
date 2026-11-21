@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.piko.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.readValue
import kotlinx.coroutines.flow.Flow
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject

/**
 * iOS 原生沙箱 `WKWebView` 实现：
 * - 非持久化数据存储（`nonPersistentDataStore`），不跨会话留存 Cookie / Storage；
 * - 零 `WKScriptMessageHandler` 暴露；
 * - 仅允许在 [allowedUrlPrefix] 内加载，外部 `http(s)` 链接经系统浏览器安全打开，阻止高危 scheme。
 */
internal class IosHtmlViewerSupport : HtmlViewerSupport {
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
        val latestEInk by rememberUpdatedState(isEInk)
        val latestJsEnabled by rememberUpdatedState(javascriptEnabled)

        var webViewRef by remember { mutableStateOf<WKWebView?>(null) }
        var loadedUrl by remember { mutableStateOf<String?>(null) }
        var appliedReload by remember { mutableStateOf(reloadTrigger) }
        var appliedJs by remember { mutableStateOf(javascriptEnabled) }

        val delegate = remember {
            IosHtmlNavigationDelegate(
                allowedPrefix = { latestAllowedPrefix },
                isEInk = { latestEInk },
                jsEnabled = { latestJsEnabled },
                onCanGoBack = { latestCanGoBack(it) },
                onTitle = { latestTitle(it) },
                onLoading = { latestLoading(it) },
                onOpenExternal = { latestExternal(it) },
            )
        }

        LaunchedEffect(goBackEvents, webViewRef) {
            goBackEvents.collect {
                val wv = webViewRef ?: return@collect
                if (wv.canGoBack) {
                    wv.goBack()
                }
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                val wv = webViewRef
                webViewRef = null
                if (wv != null) {
                    wv.stopLoading()
                    wv.navigationDelegate = null
                }
            }
        }

        UIKitView(
            modifier = modifier,
            factory = {
                val config = WKWebViewConfiguration().apply {
                    websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
                    defaultWebpagePreferences.allowsContentJavaScript = latestJsEnabled
                }
                WKWebView(frame = CGRectZero.readValue(), configuration = config).apply {
                    navigationDelegate = delegate
                    allowsBackForwardNavigationGestures = true
                    webViewRef = this
                }
            },
            update = { webView ->
                webView.configuration.defaultWebpagePreferences.allowsContentJavaScript = javascriptEnabled
                val needsLoad = loadedUrl != url
                val needsReload = !needsLoad && (appliedReload != reloadTrigger || appliedJs != javascriptEnabled)
                appliedReload = reloadTrigger
                appliedJs = javascriptEnabled
                if (needsLoad) {
                    loadedUrl = url
                    NSURL.URLWithString(url)?.let { nsUrl ->
                        webView.loadRequest(NSURLRequest.requestWithURL(nsUrl))
                    }
                } else if (needsReload) {
                    webView.reload()
                }
            },
        )
    }
}

private class IosHtmlNavigationDelegate(
    private val allowedPrefix: () -> String,
    private val isEInk: () -> Boolean,
    private val jsEnabled: () -> Boolean,
    private val onCanGoBack: (Boolean) -> Unit,
    private val onTitle: (String?) -> Unit,
    private val onLoading: (Boolean) -> Unit,
    private val onOpenExternal: (String) -> Unit,
) : NSObject(), WKNavigationDelegateProtocol {
    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val nsUrl = decidePolicyForNavigationAction.request.URL
        val raw = nsUrl?.absoluteString.orEmpty()
        val scheme = nsUrl?.scheme?.lowercase()
        when {
            raw == "about:blank" || raw.startsWith(allowedPrefix()) -> {
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            }
            scheme == "http" || scheme == "https" -> {
                if (decidePolicyForNavigationAction.targetFrame?.mainFrame != false) {
                    onOpenExternal(raw)
                }
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
            }
            else -> {
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
            }
        }
    }

    @Suppress("CONFLICTING_OVERLOADS")
    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didStartProvisionalNavigation: WKNavigation?) {
        onLoading(true)
        onCanGoBack(webView.canGoBack)
    }

    @Suppress("CONFLICTING_OVERLOADS")
    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        onLoading(false)
        onCanGoBack(webView.canGoBack)
        val pageTitle = webView.title?.takeIf { it.isNotBlank() && !it.startsWith("http://127.0.0.1") }
        onTitle(pageTitle)
        if (isEInk() && jsEnabled()) {
            val css = "html, body { background: #FFFFFF !important; color: #000000 !important; } " +
                "a { color: #000000 !important; text-decoration: underline !important; } " +
                "* { box-shadow: none !important; text-shadow: none !important; transition: none !important; animation: none !important; }"
            val js = "(function(){var s=document.createElement('style');s.textContent=\"$css\";(document.head||document.documentElement).appendChild(s);})();"
            webView.evaluateJavaScript(js, completionHandler = null)
        }
    }
}
