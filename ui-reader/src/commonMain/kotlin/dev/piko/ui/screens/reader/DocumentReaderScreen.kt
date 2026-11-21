package dev.piko.ui.screens.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TextFormat
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.LocalLocation
import dev.piko.shared.media.reader.DocumentByteResult
import dev.piko.shared.media.reader.DocumentEncodingOption
import dev.piko.shared.media.reader.DocumentKind
import dev.piko.shared.media.reader.DocumentLoader
import dev.piko.shared.media.reader.DocumentTextDecoder
import dev.piko.shared.media.reader.ParsedDocument
import dev.piko.shared.media.reader.searchDocumentBlocks
import dev.piko.shared.i18n.tr
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoErrorState
import dev.piko.ui.components.AdaptiveBar
import dev.piko.ui.components.BarItem
import dev.piko.ui.components.FullScreenLoading
import dev.piko.ui.components.IslandPage
import dev.piko.ui.components.IslandTitle
import dev.piko.ui.components.PikoScaffold
import dev.piko.ui.components.PikoSheet
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.PinnedPriority
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.connectedToggleShapes
import dev.piko.ui.components.iconBarItem
import dev.piko.ui.components.rememberListScrollTint
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.reader_action_copy_all
import dev.piko.ui.resources.reader_action_display_settings
import dev.piko.ui.resources.reader_action_download_full
import dev.piko.ui.resources.reader_action_open_external
import dev.piko.ui.resources.reader_action_raw_source
import dev.piko.ui.resources.reader_action_rendered_view
import dev.piko.ui.resources.reader_action_retry
import dev.piko.ui.resources.reader_action_search
import dev.piko.ui.resources.reader_binary_notice
import dev.piko.ui.resources.reader_copied_notice
import dev.piko.ui.resources.reader_empty_document
import dev.piko.ui.resources.reader_load_failed
import dev.piko.ui.resources.reader_search_next
import dev.piko.ui.resources.reader_search_no_matches
import dev.piko.ui.resources.reader_search_placeholder
import dev.piko.ui.resources.reader_search_prev
import dev.piko.ui.resources.reader_settings_encoding
import dev.piko.ui.resources.reader_settings_encoding_auto
import dev.piko.ui.resources.reader_settings_font_family
import dev.piko.ui.resources.reader_settings_font_larger
import dev.piko.ui.resources.reader_settings_font_mono
import dev.piko.ui.resources.reader_settings_font_sans
import dev.piko.ui.resources.reader_settings_font_serif
import dev.piko.ui.resources.reader_settings_font_size
import dev.piko.ui.resources.reader_settings_font_smaller
import dev.piko.ui.resources.reader_settings_line_spacing
import dev.piko.ui.resources.reader_settings_spacing_compact
import dev.piko.ui.resources.reader_settings_spacing_normal
import dev.piko.ui.resources.reader_settings_spacing_relaxed
import dev.piko.ui.resources.reader_settings_title
import dev.piko.ui.resources.reader_truncated_notice
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.LocalPikoMotion
import dev.piko.ui.theme.isEInk
import dev.piko.ui.theme.tabular
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class ReaderFontChoice { Default, Serif, Monospace }

private enum class ReaderLineSpacing(val multiplier: Float) {
    Compact(1.35f),
    Normal(1.60f),
    Relaxed(1.90f),
}

private val CODE_LIKE_EXTENSIONS = setOf(
    "json", "yaml", "yml", "xml", "ini", "conf", "cfg", "csv", "tsv", "toml", "log", "srt", "ass", "ssa", "vtt",
)

/**
 * 跨平台原生 TXT / Markdown 文档阅读器（Android / Desktop / iOS 共用）：
 * - 按块在 `LazyColumn` 中平滑虚拟化，避免大文本阻塞或内存爆炸；
 * - 支持 Markdown 渲染与源码一键切换、页内检索、字号/行距/字体/编码调整与全文选择复制；
 * - 完整适配水墨屏（零阴影、纯黑描边）、纯黑 OLED 与减少动画模式。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DocumentReaderScreen(
    fileId: String,
    fileName: String,
    localPath: String? = null,
    sizeBytes: Long = 0L,
    onBackClick: () -> Unit,
    onDownloadFullFile: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val services = LocalPikoServices.current
    val platform = LocalPikoPlatform.current
    val appearance = LocalAppearance.current
    val isEInk = appearance.isEInk
    val reducedMotion = LocalPikoMotion.current.reduced
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val defaultMonospace = remember(fileName) {
        fileName.substringAfterLast('.', "").lowercase() in CODE_LIKE_EXTENSIONS
    }

    var fontSizeSp by rememberSaveable { mutableFloatStateOf(16f) }
    var lineSpacing by rememberSaveable { mutableStateOf(ReaderLineSpacing.Normal) }
    var fontChoice by rememberSaveable {
        mutableStateOf(if (defaultMonospace) ReaderFontChoice.Monospace else ReaderFontChoice.Default)
    }
    var encodingOption by rememberSaveable { mutableStateOf(DocumentEncodingOption.AUTO) }
    var showRawSource by rememberSaveable { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var activeMatchPos by remember { mutableIntStateOf(0) }
    var reloadKey by remember { mutableIntStateOf(0) }

    var loading by remember(fileId, localPath, reloadKey) { mutableStateOf(true) }
    var errorMessage by remember(fileId, localPath, reloadKey) { mutableStateOf<String?>(null) }
    var rawBytes by remember(fileId, localPath, reloadKey) { mutableStateOf<DocumentByteResult?>(null) }
    var parsed by remember { mutableStateOf<ParsedDocument?>(null) }

    LaunchedEffect(fileId, localPath, reloadKey) {
        loading = true
        errorMessage = null
        parsed = null
        rawBytes = null
        DocumentLoader.loadBytes(
            mediaRepository = services.mediaRepository,
            fileId = fileId,
            localPath = localPath,
        ).onSuccess { result ->
            rawBytes = result
        }.onFailure { err ->
            errorMessage = err.message.orEmpty()
            loading = false
        }
    }

    LaunchedEffect(rawBytes, encodingOption, fileName) {
        val loaded = rawBytes ?: return@LaunchedEffect
        loading = true
        errorMessage = null
        try {
            parsed = DocumentLoader.decodeAndParse(
                fileName = fileName,
                raw = loaded,
                forcedCharset = encodingOption.charsetId,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            parsed = null
            errorMessage = e.message.orEmpty()
        } finally {
            loading = false
        }
    }

    val listState = rememberLazyListState()
    val scrollBehavior = rememberListScrollTint {
        listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
    }

    val activeBlocks by remember(parsed, showRawSource) {
        derivedStateOf {
            val doc = parsed ?: return@derivedStateOf emptyList()
            if (doc.kind == DocumentKind.Markdown && showRawSource) doc.rawBlocks else doc.blocks
        }
    }

    val renderItems by remember(activeBlocks, showRawSource) {
        derivedStateOf {
            expandDocumentBlocksForViewport(
                blocks = activeBlocks,
                keyPrefix = if (showRawSource) "raw" else "doc",
            )
        }
    }

    val searchResult by remember(activeBlocks, searchQuery, searchOpen) {
        derivedStateOf {
            if (!searchOpen || searchQuery.isBlank()) null
            else searchDocumentBlocks(activeBlocks, searchQuery)
        }
    }

    fun resolveLazyTargetIndex(targetBlock: Int): Int {
        val doc = parsed
        val bannerOffset = (if (doc?.truncated == true) 1 else 0) + (if (doc?.looksBinary == true) 1 else 0)
        val trimmedQuery = searchQuery.trim()
        val matchedSliceIdx = if (trimmedQuery.isNotEmpty()) {
            renderItems.indexOfFirst { it.sourceBlockIndex == targetBlock && it.containsQuery(trimmedQuery) }
        } else {
            -1
        }
        val sliceIdx = if (matchedSliceIdx >= 0) {
            matchedSliceIdx
        } else {
            renderItems.indexOfFirst { it.sourceBlockIndex == targetBlock }.coerceAtLeast(0)
        }
        return bannerOffset + sliceIdx
    }

    LaunchedEffect(searchResult) {
        activeMatchPos = 0
        val firstBlock = searchResult?.matches?.firstOrNull()?.blockIndex
        if (firstBlock != null && renderItems.isNotEmpty()) {
            val targetIdx = resolveLazyTargetIndex(firstBlock)
            if (reducedMotion) listState.scrollToItem(targetIdx) else listState.animateScrollToItem(targetIdx)
        }
    }

    fun jumpToMatch(delta: Int) {
        val matches = searchResult?.matches.orEmpty()
        if (matches.isEmpty() || renderItems.isEmpty()) return
        val next = ((activeMatchPos + delta) % matches.size + matches.size) % matches.size
        activeMatchPos = next
        val targetIdx = resolveLazyTargetIndex(matches[next].blockIndex)
        scope.launch {
            if (reducedMotion) listState.scrollToItem(targetIdx) else listState.animateScrollToItem(targetIdx)
        }
    }

    BackHandler(enabled = searchOpen) {
        searchOpen = false
        searchQuery = ""
    }

    val copiedNotice = stringResource(Res.string.reader_copied_notice)
    val searchLabel = stringResource(Res.string.reader_action_search)
    val displaySettingsLabel = stringResource(Res.string.reader_action_display_settings)
    val rawSourceLabel = stringResource(Res.string.reader_action_raw_source)
    val renderedViewLabel = stringResource(Res.string.reader_action_rendered_view)
    val copyAllLabel = stringResource(Res.string.reader_action_copy_all)
    val openExternalLabel = stringResource(Res.string.reader_action_open_external)
    val downloadFullLabel = stringResource(Res.string.reader_action_download_full)

    val effectiveFontFamily = when (fontChoice) {
        ReaderFontChoice.Default -> platform.fontFamily
        ReaderFontChoice.Serif -> FontFamily.Serif
        ReaderFontChoice.Monospace -> FontFamily.Monospace
    }
    val readerStyle = remember(fontSizeSp, lineSpacing, effectiveFontFamily, isEInk, showRawSource, parsed?.kind) {
        val rawMono = parsed?.kind == DocumentKind.Markdown && showRawSource
        ReaderTypographyStyle(
            fontSizeSp = fontSizeSp,
            lineHeightMultiplier = lineSpacing.multiplier,
            fontFamily = if (rawMono) FontFamily.Monospace else effectiveFontFamily,
            isEInk = isEInk,
        )
    }

    val isMarkdown = parsed?.kind == DocumentKind.Markdown
    val topBarActions = buildList {
        add(
            iconBarItem(
                icon = Icons.Outlined.Search,
                label = searchLabel,
                onClick = {
                    if (searchOpen) {
                        searchOpen = false
                        searchQuery = ""
                    } else {
                        searchOpen = true
                    }
                },
                priority = 30,
                shortcut = platform.shortcutModifier.label("F"),
                enabled = parsed != null,
                key = "reader_search",
            ),
        )
        if (isMarkdown) {
            add(
                iconBarItem(
                    icon = if (showRawSource) Icons.AutoMirrored.Outlined.Article else Icons.Outlined.Code,
                    label = if (showRawSource) renderedViewLabel else rawSourceLabel,
                    onClick = { showRawSource = !showRawSource },
                    priority = 20,
                    key = "reader_toggle_raw",
                ),
            )
        }
        add(
            iconBarItem(
                icon = Icons.Outlined.TextFormat,
                label = displaySettingsLabel,
                onClick = { showSettingsSheet = true },
                priority = 25,
                enabled = parsed != null,
                key = "reader_settings",
            ),
        )
    }

    val isLocalFile = !localPath.isNullOrBlank() || LocalLocation.isLocal(fileId)
    val moreActions = buildList {
        val doc = parsed
        if (doc != null && doc.rawText.isNotEmpty()) {
            add(
                SheetAction(
                    icon = Icons.Outlined.ContentCopy,
                    label = copyAllLabel,
                    onClick = {
                        platform.copyToClipboard(fileName, doc.rawText)
                        scope.launch { snackbarHostState.showSnackbar(copiedNotice, withDismissAction = true) }
                    },
                ),
            )
        }
        if (isLocalFile) {
            add(
                SheetAction(
                    icon = Icons.Outlined.OpenInNew,
                    label = openExternalLabel,
                    onClick = {
                        scope.launch {
                            val resolved = localPath ?: services.mediaRepository.localPath(fileId)
                            if (!resolved.isNullOrBlank()) {
                                platform.localFiles.openExternally(resolved, isMedia = false)
                            }
                        }
                    },
                ),
            )
        } else if (onDownloadFullFile != null) {
            add(
                SheetAction(
                    icon = Icons.Outlined.Download,
                    label = downloadFullLabel,
                    onClick = onDownloadFullFile,
                ),
            )
        }
    }

    PikoScaffold(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && platform.shortcutModifier.isPressed(event) && event.key == Key.F) {
                    if (parsed != null) searchOpen = true
                    true
                } else {
                    false
                }
            },
        island = IslandPage(
            header = {
                AdaptiveBar(
                    modifier = Modifier.weight(1f),
                    leading = listOf(
                        BarItem("back", PinnedPriority) {
                            TooltipIconButton(
                                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                                label = tr("返回", "Back", "戻る", "뒤로", "返回"),
                                onClick = onBackClick,
                            )
                        },
                    ),
                    middle = { IslandTitle(fileName) },
                    middleMinWidth = 112.dp,
                    reserveMiddle = true,
                    trailing = topBarActions,
                    moreActions = moreActions,
                    gap = 4.dp,
                    dragWindow = true,
                )
            },
        ),
        topBar = {
            PikoTopBar(
                title = fileName,
                onBackClick = onBackClick,
                actions = topBarActions,
                moreActions = moreActions,
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (searchOpen) {
                ReaderSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    matchIndex = if (searchResult?.matches.isNullOrEmpty()) 0 else activeMatchPos + 1,
                    matchCount = searchResult?.matches?.size ?: 0,
                    onPrev = { jumpToMatch(-1) },
                    onNext = { jumpToMatch(1) },
                    onClose = {
                        searchOpen = false
                        searchQuery = ""
                    },
                    isEInk = isEInk,
                )
            }

            when {
                loading -> {
                    FullScreenLoading(modifier = Modifier.weight(1f).fillMaxWidth())
                }
                errorMessage != null -> {
                    PikoErrorState(
                        title = stringResource(Res.string.reader_load_failed),
                        message = errorMessage ?: stringResource(Res.string.reader_load_failed),
                        onRetry = { reloadKey++ },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
                parsed != null -> {
                    val doc = parsed!!
                    val activeBlockIndex = searchResult?.matches?.getOrNull(activeMatchPos)?.blockIndex
                    val displayTotalBytes = maxOf(doc.totalBytes, sizeBytes)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        if (activeBlocks.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                PikoEmptyState(
                                    title = stringResource(Res.string.reader_empty_document),
                                    icon = Icons.AutoMirrored.Outlined.Article,
                                )
                            }
                        } else {
                            val blockGap = if (isMarkdown && !showRawSource) 12.dp else 6.dp
                            SelectionContainer {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(0.dp),
                                ) {
                                    if (doc.truncated) {
                                        item(key = "reader_truncated_banner") {
                                            ReaderNoticeBanner(
                                                text = stringResource(
                                                    Res.string.reader_truncated_notice,
                                                    doc.loadedBytes.toReadableSize(),
                                                    displayTotalBytes.toReadableSize(),
                                                ),
                                                actionLabel = if (!isLocalFile && onDownloadFullFile != null) downloadFullLabel else null,
                                                onAction = onDownloadFullFile,
                                                isEInk = isEInk,
                                                modifier = Modifier
                                                    .widthIn(max = 720.dp)
                                                    .fillMaxWidth()
                                                    .padding(bottom = blockGap),
                                            )
                                        }
                                    }
                                    if (doc.looksBinary) {
                                        item(key = "reader_binary_banner") {
                                            ReaderNoticeBanner(
                                                text = stringResource(Res.string.reader_binary_notice),
                                                actionLabel = null,
                                                onAction = null,
                                                isEInk = isEInk,
                                                modifier = Modifier
                                                    .widthIn(max = 720.dp)
                                                    .fillMaxWidth()
                                                    .padding(bottom = blockGap),
                                            )
                                        }
                                    }
                                    itemsIndexed(
                                        items = renderItems,
                                        key = { _, item -> item.key },
                                        contentType = { _, item -> item.contentType },
                                    ) { index, item ->
                                        val bottomPad = if (item.isLastInBlock && index < renderItems.lastIndex) blockGap else 0.dp
                                        Box(
                                            modifier = Modifier
                                                .widthIn(max = 720.dp)
                                                .fillMaxWidth()
                                                .padding(bottom = bottomPad),
                                        ) {
                                            ReaderRenderItemView(
                                                item = item,
                                                style = readerStyle,
                                                searchQuery = if (searchOpen) searchQuery else "",
                                                isActiveMatchBlock = activeBlockIndex == item.sourceBlockIndex,
                                                onOpenUrl = { url ->
                                                    if (isSafeExternalLink(url)) platform.openUrl(url)
                                                },
                                                onCopyCode = { code ->
                                                    platform.copyToClipboard(fileName, code)
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar(copiedNotice, withDismissAction = true)
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                            platform.ListScrollbar(
                                state = listState,
                                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSettingsSheet) {
        val detectedEncoding = parsed?.encodingName ?: "UTF-8"
        PikoSheet(
            onDismissRequest = { showSettingsSheet = false },
            sideSheetTitle = stringResource(Res.string.reader_settings_title),
        ) {
            ReaderSettingsSheetContent(
                isSideSheet = isSideSheet,
                fontSizeSp = fontSizeSp,
                onFontSizeChange = { fontSizeSp = it.coerceIn(12f, 28f) },
                lineSpacing = lineSpacing,
                onLineSpacingChange = { lineSpacing = it },
                fontChoice = fontChoice,
                onFontChoiceChange = { fontChoice = it },
                encodingOption = encodingOption,
                detectedEncoding = detectedEncoding,
                supportsLegacyCharsets = DocumentTextDecoder.current.supportsLegacyCharsets,
                onEncodingChange = { encodingOption = it },
                isEInk = isEInk,
            )
        }
    }
}

@Composable
private fun ReaderSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchIndex: Int,
    matchCount: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    isEInk: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }
    Surface(
        color = if (isEInk) colors.surface else colors.surfaceContainerLow,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text(stringResource(Res.string.reader_search_placeholder), maxLines = 1) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.largeIncreased,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                if (query.isNotEmpty()) onQueryChange("") else onClose()
                            },
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = null)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                )
                if (query.isNotBlank()) {
                    Text(
                        text = if (matchCount == 0) {
                            stringResource(Res.string.reader_search_no_matches)
                        } else {
                            "$matchIndex / $matchCount"
                        },
                        style = MaterialTheme.typography.labelMedium.tabular,
                        color = if (matchCount == 0 && !isEInk) colors.error else colors.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    TooltipIconButton(
                        icon = Icons.Outlined.KeyboardArrowUp,
                        label = stringResource(Res.string.reader_search_prev),
                        onClick = onPrev,
                        enabled = matchCount > 0,
                    )
                    TooltipIconButton(
                        icon = Icons.Outlined.KeyboardArrowDown,
                        label = stringResource(Res.string.reader_search_next),
                        onClick = onNext,
                        enabled = matchCount > 0,
                    )
                }
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = if (isEInk) colors.outline else colors.outlineVariant.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun ReaderNoticeBanner(
    text: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    isEInk: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = if (isEInk) colors.surface else colors.surfaceContainerHigh,
        border = BorderStroke(1.dp, if (isEInk) colors.outline else colors.outlineVariant),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = if (isEInk) colors.onSurface else colors.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction, shapes = ButtonDefaults.shapes()) {
                    Text(actionLabel, maxLines = 1)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ReaderSettingsSheetContent(
    isSideSheet: Boolean,
    fontSizeSp: Float,
    onFontSizeChange: (Float) -> Unit,
    lineSpacing: ReaderLineSpacing,
    onLineSpacingChange: (ReaderLineSpacing) -> Unit,
    fontChoice: ReaderFontChoice,
    onFontChoiceChange: (ReaderFontChoice) -> Unit,
    encodingOption: DocumentEncodingOption,
    detectedEncoding: String,
    supportsLegacyCharsets: Boolean,
    onEncodingChange: (DocumentEncodingOption) -> Unit,
    isEInk: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val toggleColors = ToggleButtonDefaults.colors(
        containerColor = if (isEInk) colors.surfaceContainer else colors.surfaceContainerHighest,
    )
    val encodingOptions = remember(supportsLegacyCharsets) {
        if (supportsLegacyCharsets) {
            DocumentEncodingOption.entries
        } else {
            listOf(
                DocumentEncodingOption.AUTO,
                DocumentEncodingOption.UTF_8,
                DocumentEncodingOption.UTF_16LE,
                DocumentEncodingOption.UTF_16BE,
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (!isSideSheet) {
            Text(
                text = stringResource(Res.string.reader_settings_title),
                style = MaterialTheme.typography.titleLargeEmphasized,
                color = colors.onSurface,
            )
        }

        // 字号
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(Res.string.reader_settings_font_size),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                FilledTonalIconButton(
                    onClick = { onFontSizeChange(fontSizeSp - 2f) },
                    enabled = fontSizeSp > 12f,
                ) {
                    Icon(Icons.Outlined.Remove, contentDescription = stringResource(Res.string.reader_settings_font_smaller))
                }
                Text(
                    text = "${fontSizeSp.toInt()} sp",
                    style = MaterialTheme.typography.titleMedium.tabular,
                    color = colors.onSurface,
                )
                FilledTonalIconButton(
                    onClick = { onFontSizeChange(fontSizeSp + 2f) },
                    enabled = fontSizeSp < 28f,
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = stringResource(Res.string.reader_settings_font_larger))
                }
            }
        }

        // 行距
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(Res.string.reader_settings_line_spacing),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
            )
            val spacingItems = listOf(
                ReaderLineSpacing.Compact to stringResource(Res.string.reader_settings_spacing_compact),
                ReaderLineSpacing.Normal to stringResource(Res.string.reader_settings_spacing_normal),
                ReaderLineSpacing.Relaxed to stringResource(Res.string.reader_settings_spacing_relaxed),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                spacingItems.forEachIndexed { index, (choice, label) ->
                    ToggleButton(
                        checked = lineSpacing == choice,
                        onCheckedChange = { if (it) onLineSpacingChange(choice) },
                        shapes = connectedToggleShapes(index, spacingItems.size),
                        colors = toggleColors,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        // 字体
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(Res.string.reader_settings_font_family),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
            )
            val fontItems = listOf(
                ReaderFontChoice.Default to stringResource(Res.string.reader_settings_font_sans),
                ReaderFontChoice.Serif to stringResource(Res.string.reader_settings_font_serif),
                ReaderFontChoice.Monospace to stringResource(Res.string.reader_settings_font_mono),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                fontItems.forEachIndexed { index, (choice, label) ->
                    ToggleButton(
                        checked = fontChoice == choice,
                        onCheckedChange = { if (it) onFontChoiceChange(choice) },
                        shapes = connectedToggleShapes(index, fontItems.size),
                        colors = toggleColors,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        // 文本编码
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(Res.string.reader_settings_encoding),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                encodingOptions.forEachIndexed { index, option ->
                    val selected = encodingOption == option
                    val label = if (option == DocumentEncodingOption.AUTO) {
                        stringResource(Res.string.reader_settings_encoding_auto, detectedEncoding)
                    } else {
                        option.displayName
                    }
                    val itemShape = ListItemDefaults.segmentedShapes(index, encodingOptions.size).shape
                    val contentColor = when {
                        selected && isEInk -> colors.surface
                        selected -> colors.onSecondaryContainer
                        else -> colors.onSurface
                    }
                    Surface(
                        onClick = { onEncodingChange(option) },
                        shape = itemShape,
                        color = when {
                            selected && isEInk -> colors.onSurface
                            selected -> colors.secondaryContainer
                            isEInk -> colors.surface
                            else -> colors.surfaceContainerLow
                        },
                        border = if (isEInk) BorderStroke(1.dp, colors.outline) else null,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = contentColor,
                            )
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = null,
                                    tint = contentColor,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}
