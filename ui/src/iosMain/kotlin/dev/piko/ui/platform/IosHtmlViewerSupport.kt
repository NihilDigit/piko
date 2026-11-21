@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.piko.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.readValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.UIKit.UIUserInterfaceStyle
import platform.WebKit.*
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * iOS 原生沙箱 `WKWebView` 实现：
 * - 非持久化数据存储（`nonPersistentDataStore`），不跨会话留存 Cookie / Storage；
 * - 零 `WKScriptMessageHandler` 暴露；
 * - 首次加载前先编译并挂载 `WKContentRuleList`，阻断除 [allowedUrlPrefix] 外的全部 `http(s)` / `ws(s)` 请求；
 * - 仅主框架上的用户显式点击链接才交由系统浏览器安全打开。
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

        var currentSafeUrl by remember(url, allowedUrlPrefix) { mutableStateOf(url) }
        var compiledRuleList by remember(allowedUrlPrefix) { mutableStateOf<WKContentRuleList?>(null) }
        var compiledForPrefix by remember { mutableStateOf<String?>(null) }

        val ruleIdentifier = remember(allowedUrlPrefix) {
            "piko_doc_${allowedUrlPrefix.hashCode().toUInt().toString(16)}"
        }

        LaunchedEffect(allowedUrlPrefix, ruleIdentifier) {
            compiledRuleList = null
            compiledForPrefix = null
            if (allowedUrlPrefix.isBlank()) {
                latestLoading(false)
                return@LaunchedEffect
            }
            val store = WKContentRuleListStore.defaultStore()
            if (store == null) {
                latestLoading(false)
                return@LaunchedEffect
            }
            val rulesJson = buildContentBlockerJson(allowedUrlPrefix)
            val compiled = suspendCancellableCoroutine<WKContentRuleList?> { cont ->
                store.compileContentRuleListForIdentifier(ruleIdentifier, rulesJson) { list, _ ->
                    if (cont.isActive) cont.resume(list)
                }
            }
            if (compiled != null) {
                compiledRuleList = compiled
                compiledForPrefix = allowedUrlPrefix
            } else {
                latestLoading(false)
            }
        }

        DisposableEffect(ruleIdentifier) {
            onDispose {
                WKContentRuleListStore.defaultStore()?.removeContentRuleListForIdentifier(ruleIdentifier) { _ -> }
            }
        }

        val activeRuleList = compiledRuleList?.takeIf { compiledForPrefix == allowedUrlPrefix } ?: return

        // WKWebView.configuration 在 Objective-C 中返回副本，切换 JavaScript 或规则集时重建实例以使新配置可靠生效
        key(activeRuleList, javascriptEnabled) {
            var webViewRef by remember { mutableStateOf<WKWebView?>(null) }
            var loadedUrl by remember { mutableStateOf<String?>(null) }
            var appliedReload by remember { mutableStateOf(reloadTrigger) }

            val delegate = remember {
                IosHtmlNavigationDelegate(
                    allowedPrefix = { latestAllowedPrefix },
                    isEInk = { latestEInk },
                    jsEnabled = { latestJsEnabled },
                    onCanGoBack = { latestCanGoBack(it) },
                    onTitle = { latestTitle(it) },
                    onLoading = { latestLoading(it) },
                    onOpenExternal = { latestExternal(it) },
                    onSafeUrlVisited = { visited -> currentSafeUrl = visited },
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
                        defaultWebpagePreferences.allowsContentJavaScript = javascriptEnabled
                        userContentController.addContentRuleList(activeRuleList)
                    }
                    WKWebView(frame = CGRectZero.readValue(), configuration = config).apply {
                        navigationDelegate = delegate
                        allowsBackForwardNavigationGestures = true
                        overrideUserInterfaceStyle = resolveInterfaceStyle(darkTheme = darkTheme, isEInk = isEInk)
                        webViewRef = this
                        val initialTarget = currentSafeUrl.takeIf { it.startsWith(allowedUrlPrefix) } ?: url
                        loadedUrl = url
                        NSURL.URLWithString(initialTarget)?.let { nsUrl ->
                            loadRequest(NSURLRequest.requestWithURL(nsUrl))
                        }
                    }
                },
                update = { webView ->
                    webView.overrideUserInterfaceStyle = resolveInterfaceStyle(darkTheme = darkTheme, isEInk = isEInk)
                    val needsLoad = loadedUrl != url
                    val needsReload = !needsLoad && appliedReload != reloadTrigger
                    appliedReload = reloadTrigger
                    if (needsLoad) {
                        loadedUrl = url
                        currentSafeUrl = url
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
}

private fun resolveInterfaceStyle(darkTheme: Boolean, isEInk: Boolean): UIUserInterfaceStyle =
    if (darkTheme && !isEInk) {
        UIUserInterfaceStyle.UIUserInterfaceStyleDark
    } else {
        UIUserInterfaceStyle.UIUserInterfaceStyleLight
    }

private fun buildContentBlockerJson(allowedUrlPrefix: String): String {
    val escapedRegex = buildString(allowedUrlPrefix.length + 16) {
        append('^')
        for (ch in allowedUrlPrefix) {
            if (ch in ".\\+*?^\$()[]{}|") {
                append('\\')
            }
            append(ch)
        }
    }
    val jsonPattern = escapedRegex.replace("\\", "\\\\").replace("\"", "\\\"")
    return """[{"trigger":{"url-filter":"^http://"},"action":{"type":"block"}},""" +
        """{"trigger":{"url-filter":"^https://"},"action":{"type":"block"}},""" +
        """{"trigger":{"url-filter":"^ws://"},"action":{"type":"block"}},""" +
        """{"trigger":{"url-filter":"^wss://"},"action":{"type":"block"}},""" +
        """{"trigger":{"url-filter":"$jsonPattern","url-filter-is-case-sensitive":true},"action":{"type":"ignore-previous-rules"}}]"""
}

private class IosHtmlNavigationDelegate(
    private val allowedPrefix: () -> String,
    private val isEInk: () -> Boolean,
    private val jsEnabled: () -> Boolean,
    private val onCanGoBack: (Boolean) -> Unit,
    private val onTitle: (String?) -> Unit,
    private val onLoading: (Boolean) -> Unit,
    private val onOpenExternal: (String) -> Unit,
    private val onSafeUrlVisited: (String) -> Unit,
) : NSObject(), WKNavigationDelegateProtocol {
    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val nsUrl = decidePolicyForNavigationAction.request.URL
        val raw = nsUrl?.absoluteString.orEmpty()
        val scheme = nsUrl?.scheme?.lowercase()
        val prefix = allowedPrefix()
        when {
            raw == "about:blank" || (prefix.isNotEmpty() && raw.startsWith(prefix)) -> {
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            }
            scheme == "http" || scheme == "https" -> {
                if (
                    decidePolicyForNavigationAction.targetFrame?.mainFrame == true &&
                    decidePolicyForNavigationAction.navigationType == WKNavigationTypeLinkActivated
                ) {
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
        val visited = webView.URL?.absoluteString
        val prefix = allowedPrefix()
        if (visited != null && prefix.isNotEmpty() && visited.startsWith(prefix)) {
            onSafeUrlVisited(visited)
        }
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
