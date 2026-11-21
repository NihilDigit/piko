package dev.piko.ui.screens.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.unit.dp
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.media.proxy.DocumentProxyHandle
import dev.piko.shared.media.proxy.DriveHtmlDocumentResolver
import dev.piko.shared.media.proxy.HtmlDocumentProxySessions
import dev.piko.shared.i18n.tr
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.AdaptiveBar
import dev.piko.ui.components.BarItem
import dev.piko.ui.components.FullScreenLoading
import dev.piko.ui.components.IslandPage
import dev.piko.ui.components.IslandTitle
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoErrorState
import dev.piko.ui.components.PikoScaffold
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.PinnedPriority
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.iconBarItem
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.reader_action_javascript
import dev.piko.ui.resources.reader_action_open_browser
import dev.piko.ui.resources.reader_action_reload
import dev.piko.ui.resources.reader_action_retry
import dev.piko.ui.resources.reader_html_js_disabled_notice
import dev.piko.ui.resources.reader_html_js_enabled_notice
import dev.piko.ui.resources.reader_html_open_failed
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.LocalPikoMotion
import dev.piko.ui.theme.LocalWindowVisible
import dev.piko.ui.theme.isDark
import dev.piko.ui.theme.isEInk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * 移动端原生 HTML 查看器页面（Android `WebView` / iOS `WKWebView`）：
 * - 通过本机回环代理 `/doc/<高熵令牌>/<文件名>` 提供入口页面与同目录子树内的相对资源；
 * - 退出组合时确定性关闭代理会话，不滞留令牌；
 * - 默认启用 JavaScript（支持 SingleFile 等交互页面），保留零 JS Bridge、无 `file://` 访问与严格同源边界，
 *   并在溢出菜单提供一键开关 JavaScript 与在系统浏览器打开选项。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HtmlViewerScreen(
    fileId: String,
    fileName: String,
    parentFolderId: String? = null,
    localPath: String? = null,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val services = LocalPikoServices.current
    val platform = LocalPikoPlatform.current
    val viewer = platform.htmlViewer
    val appearance = LocalAppearance.current
    val darkTheme = appearance.isDark()
    val isEInk = appearance.isEInk
    val reducedMotion = LocalPikoMotion.current.reduced
    val windowVisible = LocalWindowVisible.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var proxyHandle by remember(fileId, parentFolderId, localPath) { mutableStateOf<DocumentProxyHandle?>(null) }
    var proxyError by remember(fileId, parentFolderId, localPath) { mutableStateOf<String?>(null) }
    var setupKey by remember { mutableIntStateOf(0) }

    var javascriptEnabled by rememberSaveable { mutableStateOf(true) }
    var reloadTrigger by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var pageTitle by remember { mutableStateOf<String?>(null) }
    var pageLoading by remember { mutableStateOf(true) }
    val goBackEvents = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 4) }

    LaunchedEffect(fileId, parentFolderId, localPath, setupKey) {
        proxyHandle?.close()
        proxyHandle = null
        proxyError = null
        try {
            val resolver = DriveHtmlDocumentResolver(
                entryFileId = fileId,
                entryFileName = fileName,
                parentFolderId = parentFolderId,
                localPath = localPath,
                driveRepository = services.driveRepository,
                mediaRepository = services.mediaRepository,
            )
            proxyHandle = services.mediaRepository.loopbackProxy.registerDocument(
                entryFileName = fileName,
                resolver = resolver,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            proxyError = e.message
        }
    }

    DisposableEffect(fileId, parentFolderId, localPath) {
        onDispose {
            proxyHandle?.close()
            proxyHandle = null
        }
    }

    BackHandler(enabled = canGoBack) {
        goBackEvents.tryEmit(Unit)
    }

    val reloadLabel = stringResource(Res.string.reader_action_reload)
    val openBrowserLabel = stringResource(Res.string.reader_action_open_browser)
    val jsLabel = stringResource(Res.string.reader_action_javascript)
    val jsDisabledNotice = stringResource(Res.string.reader_html_js_disabled_notice)
    val jsEnabledNotice = stringResource(Res.string.reader_html_js_enabled_notice)

    val topActions = buildList {
        add(
            iconBarItem(
                icon = Icons.Outlined.Refresh,
                label = reloadLabel,
                onClick = { reloadTrigger++ },
                priority = 30,
                enabled = proxyHandle != null,
                key = "html_reload",
            ),
        )
        add(
            iconBarItem(
                icon = Icons.Outlined.OpenInBrowser,
                label = openBrowserLabel,
                onClick = {
                    scope.launch {
                        HtmlDocumentProxySessions.openForExternalBrowser(
                            fileId = fileId,
                            fileName = fileName,
                            parentFolderId = parentFolderId,
                            localPath = localPath,
                            driveRepository = services.driveRepository,
                            mediaRepository = services.mediaRepository,
                        ).onSuccess { externalHandle ->
                            platform.openUrl(externalHandle.url)
                        }.onFailure {
                            proxyHandle?.url?.let(platform::openUrl)
                        }
                    }
                },
                priority = 20,
                enabled = proxyHandle != null,
                key = "html_open_browser",
            ),
        )
    }

    val moreActions = listOf(
        SheetAction(
            icon = Icons.Outlined.Code,
            label = jsLabel,
            onClick = {
                val next = !javascriptEnabled
                javascriptEnabled = next
                scope.launch {
                    snackbarHostState.showSnackbar(
                        if (next) jsEnabledNotice else jsDisabledNotice,
                        withDismissAction = true,
                    )
                }
            },
            checked = javascriptEnabled,
        ),
    )

    val resolvedTitle = pageTitle?.takeIf { it.isNotBlank() } ?: fileName
    val handleBack = {
        if (canGoBack) {
            goBackEvents.tryEmit(Unit)
        } else {
            onBackClick()
        }
    }

    PikoScaffold(
        modifier = modifier.fillMaxSize(),
        island = IslandPage(
            header = {
                AdaptiveBar(
                    modifier = Modifier.weight(1f),
                    leading = listOf(
                        BarItem("back", PinnedPriority) {
                            TooltipIconButton(
                                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                                label = tr("返回", "Back", "戻る", "뒤로", "返回"),
                                onClick = { handleBack() },
                            )
                        },
                    ),
                    middle = { IslandTitle(resolvedTitle) },
                    middleMinWidth = 112.dp,
                    reserveMiddle = true,
                    trailing = topActions,
                    moreActions = moreActions,
                    gap = 4.dp,
                    dragWindow = true,
                )
            },
        ),
        topBar = {
            PikoTopBar(
                title = resolvedTitle,
                onBackClick = { handleBack() },
                actions = topActions,
                moreActions = moreActions,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                viewer == null -> {
                    PikoEmptyState(
                        title = stringResource(Res.string.reader_html_open_failed),
                        icon = Icons.Outlined.OpenInBrowser,
                        actionText = openBrowserLabel,
                        onActionClick = { proxyHandle?.url?.let(platform::openUrl) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                proxyError != null -> {
                    PikoErrorState(
                        title = stringResource(Res.string.reader_html_open_failed),
                        message = proxyError.orEmpty(),
                        onRetry = { setupKey++ },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                proxyHandle == null -> {
                    FullScreenLoading(modifier = Modifier.fillMaxSize())
                }
                else -> {
                    val active = proxyHandle!!
                    Column(Modifier.fillMaxSize()) {
                        if (pageLoading) {
                            if (reducedMotion || !windowVisible) {
                                LinearProgressIndicator(
                                    progress = { 1f },
                                    modifier = Modifier.fillMaxWidth().height(2.dp),
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
                            }
                        }
                        viewer.Content(
                            url = active.url,
                            allowedUrlPrefix = active.allowedUrlPrefix,
                            javascriptEnabled = javascriptEnabled,
                            darkTheme = darkTheme,
                            isEInk = isEInk,
                            reloadTrigger = reloadTrigger,
                            onCanGoBackChanged = { canGoBack = it },
                            onTitleChanged = { pageTitle = it },
                            onLoadingChanged = { pageLoading = it },
                            onOpenExternalUrl = { external ->
                                if (isSafeExternalLink(external)) platform.openUrl(external)
                            },
                            goBackEvents = goBackEvents,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
