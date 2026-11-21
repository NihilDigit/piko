package dev.piko.ui.screens.drive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.github.panpf.zoomimage.CoilZoomAsyncImage
import com.github.panpf.zoomimage.rememberCoilZoomState
import com.github.panpf.zoomimage.subsampling.TileState
import dev.piko.download.DownloadStatus
import dev.piko.shared.data.LeasedFile
import dev.piko.shared.data.LocalLocation
import dev.piko.shared.i18n.tr
import dev.piko.shared.state.driveItemName
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.MediaLoadingIndicator
import dev.piko.ui.components.PikoSheet
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.previewImageCoverUrl
import dev.piko.ui.components.rememberDownloadLauncher
import dev.piko.ui.components.trackPointerSource
import dev.piko.ui.platform.LocalFramelessWindow
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.captionExclusion
import dev.piko.ui.platform.resolveCoilModel
import dev.piko.ui.platform.windowDragArea
import dev.piko.ui.screens.player.ClusterIconButton
import dev.piko.ui.screens.player.MediaWindowControlsCluster
import dev.piko.ui.screens.player.PlayerIconButton
import dev.piko.ui.screens.player.PlayerPillCluster
import dev.piko.ui.screens.player.PlayerTextShadow
import dev.piko.ui.screens.player.PlayerTheme
import dev.piko.ui.screens.player.PlayerWindowActions
import dev.piko.ui.screens.player.TopScrim
import dev.piko.ui.theme.LocalFixedColors
import dev.piko.ui.theme.tabular
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val KEY_ZOOM_STEP = 1.5f
private val HOVER_REVEAL_TOP_ZONE = 72.dp
private val HOVER_REVEAL_BOTTOM_ZONE = 88.dp

private sealed interface ViewerCommand {
    data class Zoom(val factor: Float?) : ViewerCommand
    data object RotateCw : ViewerCommand
}

/** 移动端与 iOS 应用内全屏看图弹窗入口；桌面端经 `ImageViewerWindow` 直接承载 [ImageViewerSurface]。 */
@Composable
fun ImageViewer(images: List<FileStat>, initialIndex: Int, onDismiss: () -> Unit) {
    if (images.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val chromeVisibleState = remember { mutableStateOf(true) }
    LocalPikoPlatform.current.FullscreenDialog(
        onDismiss = onDismiss,
        immersive = true,
        systemBarsVisible = chromeVisibleState.value,
    ) {
        ImageViewerSurface(
            images = images,
            initialIndex = initialIndex,
            onDismiss = onDismiss,
            isChromeVisibleState = chromeVisibleState,
        )
    }
}

/**
 * 跨平台看图主表面：ZoomImage 独占画布缩放与平移，外层承载触控下滑退出、桌面无边框窗口顶栏控制、
 * 指针移入顶/底边缘唤醒 HUD、下载与属性面板。
 */
@Composable
fun ImageViewerSurface(
    images: List<FileStat>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    windowActions: PlayerWindowActions? = null,
    onToggleFullscreen: (() -> Unit)? = null,
    onCurrentFileChange: ((FileStat) -> Unit)? = null,
    onLocate: ((FileStat) -> Unit)? = null,
    externalModalOpen: Boolean = false,
    registerKeyFallback: (((KeyEvent) -> Boolean)?) -> Unit = {},
    isChromeVisibleState: MutableState<Boolean> = remember { mutableStateOf(true) },
) {
    if (images.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val services = LocalPikoServices.current
    val platform = LocalPikoPlatform.current
    val shortcutModifier = platform.shortcutModifier
    val pointers = LocalPointerSource.current
    val density = LocalDensity.current
    val pager = rememberPagerState(initialPage = initialIndex.coerceIn(images.indices)) { images.size }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val snackbarHostState = remember { SnackbarHostState() }
    val downloads = rememberDownloadLauncher()

    var isChromeVisible by isChromeVisibleState
    var pointerNearTop by remember { mutableStateOf(false) }
    var pointerNearBottom by remember { mutableStateOf(false) }
    var dismissProgress by remember { mutableFloatStateOf(0f) }
    // The registered callback belongs to the active page's scope, not the dialog scope.
    var viewerCommand by remember { mutableStateOf<((ViewerCommand) -> Unit)?>(null) }
    var zoomPercent by remember { mutableIntStateOf(100) }
    var propertiesFile by remember { mutableStateOf<FileStat?>(null) }

    val anyModalOpen = externalModalOpen || propertiesFile != null
    val currentFile = images[pager.currentPage.coerceIn(images.indices)]
    val latestOnCurrentFileChange by rememberUpdatedState(onCurrentFileChange)
    LaunchedEffect(currentFile) {
        latestOnCurrentFileChange?.invoke(currentFile)
    }

    val completedLocalPath = services.downloadManager.tasks.value.values.find {
        it.fileId == currentFile.id && it.status == DownloadStatus.COMPLETED && !it.isSegment
    }?.destinationPath?.takeIf(platform.localFiles::exists)
    val explicitLocalPath = currentFile.params["local_path"]
        ?.takeIf { it.isNotBlank() && !it.startsWith("content://") && platform.localFiles.exists(it) }
    val localFilePath = explicitLocalPath ?: completedLocalPath
    val isLocalImage = LocalLocation.isLocal(currentFile.id) || localFilePath != null
    val canDownload = !isLocalImage && currentFile.id.isNotEmpty()

    fun showMessage(text: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(text, withDismissAction = true)
        }
    }

    fun downloadCurrent() {
        if (!canDownload) return
        val file = currentFile
        val resolved = LeasedFile.resolvedFileOf(file.id)
        if (resolved != null) {
            downloads.downloadResolved(listOf(resolved), "") { notice ->
                showMessage(notice ?: tr("已加入下载", "Added to downloads", "ダウンロードに追加しました", "다운로드에 추가했습니다", "已加入下載"))
            }
        } else if (file.size.isNotBlank() && file.size != "0") {
            downloads.download(listOf(file)) { notice ->
                showMessage(notice ?: tr("已加入下载", "Added to downloads", "ダウンロードに追加しました", "다운로드에 추가했습니다", "已加入下載"))
            }
        } else {
            scope.launch {
                services.driveRepository.getFileDetail(file.id)
                    .onSuccess { detail ->
                        val full = FileStat(
                            kind = FileKind.FILE,
                            id = detail.id,
                            parentId = detail.parentId,
                            name = detail.name,
                            size = detail.size,
                            hash = detail.hash,
                            mimeType = detail.mimeType,
                            fileExtension = detail.fileExtension,
                            params = detail.params,
                        )
                        downloads.download(listOf(full)) { notice ->
                            showMessage(notice ?: tr("已加入下载", "Added to downloads", "ダウンロードに追加しました", "다운로드에 추가했습니다", "已加入下載"))
                        }
                    }
                    .onFailure {
                        showMessage(tr("未找到此文件，可能已被删除", "File not found. It may have been deleted", "ファイルが見つかりません。削除された可能性があります", "파일을 찾을 수 없습니다. 삭제되었을 수 있습니다", "找不到此檔案，可能已被刪除"))
                    }
            }
        }
    }

    LaunchedEffect(pointers.isTouchLike, anyModalOpen) {
        if (!pointers.isTouchLike && !anyModalOpen) runCatching { focus.requestFocus() }
    }

    fun goTo(page: Int) {
        val target = page.coerceIn(images.indices)
        if (target != pager.currentPage) scope.launch { pager.animateScrollToPage(target) }
    }

    val handleKeyEvent: (KeyEvent) -> Boolean = keyHandler@{ event ->
        if (anyModalOpen || event.type != KeyEventType.KeyDown) return@keyHandler false
        if (shortcutModifier.isPressed(event)) {
            if (event.key == Key.S && canDownload) {
                downloadCurrent()
                return@keyHandler true
            }
            return@keyHandler false
        }
        when (event.key) {
            Key.DirectionLeft, Key.PageUp -> goTo(pager.currentPage - 1)
            Key.DirectionRight, Key.PageDown, Key.Spacebar -> goTo(pager.currentPage + 1)
            Key.MoveHome -> goTo(0)
            Key.MoveEnd -> goTo(images.lastIndex)
            Key.Plus, Key.Equals, Key.NumPadAdd -> viewerCommand?.invoke(ViewerCommand.Zoom(KEY_ZOOM_STEP))
            Key.Minus, Key.NumPadSubtract -> viewerCommand?.invoke(ViewerCommand.Zoom(1f / KEY_ZOOM_STEP))
            Key.Zero, Key.NumPad0 -> viewerCommand?.invoke(ViewerCommand.Zoom(null))
            Key.R -> viewerCommand?.invoke(ViewerCommand.RotateCw)
            Key.I -> propertiesFile = if (propertiesFile == null) currentFile else null
            Key.F, Key.F11 -> {
                if (onToggleFullscreen != null) onToggleFullscreen() else return@keyHandler false
            }
            Key.T -> {
                val pin = windowActions?.onToggleAlwaysOnTop
                if (pin != null) pin() else return@keyHandler false
            }
            Key.Escape -> {
                if (windowActions?.isFullscreen == true && onToggleFullscreen != null) {
                    onToggleFullscreen()
                } else {
                    onDismiss()
                }
            }
            else -> return@keyHandler false
        }
        true
    }
    val latestHandleKeyEvent by rememberUpdatedState(handleKeyEvent)
    DisposableEffect(registerKeyFallback) {
        registerKeyFallback { event -> latestHandleKeyEvent(event) }
        onDispose { registerKeyFallback(null) }
    }

    val topHoverThresholdPx = with(density) {
        (HOVER_REVEAL_TOP_ZONE + (windowActions?.topChromePadding ?: 0.dp)).toPx()
    }
    val bottomHoverThresholdPx = with(density) { HOVER_REVEAL_BOTTOM_ZONE.toPx() }
    val topBarVisible = isChromeVisible || (!pointers.isTouchLike && pointerNearTop && !anyModalOpen)
    val bottomBarVisible = (isChromeVisible || (!pointers.isTouchLike && pointerNearBottom && !anyModalOpen)) && viewerCommand != null
    val dragAreaEnabled = topBarVisible && windowActions?.isFullscreen != true && !anyModalOpen

    val motion = MaterialTheme.motionScheme
    Box(
        modifier
            .fillMaxSize()
            .trackPointerSource(pointers)
            .pointerInput(topHoverThresholdPx, bottomHoverThresholdPx) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        when (event.type) {
                            PointerEventType.Move, PointerEventType.Enter -> {
                                val change = event.changes.firstOrNull()
                                if (change != null && change.type == PointerType.Mouse) {
                                    val y = change.position.y
                                    pointerNearTop = y in 0f..topHoverThresholdPx
                                    pointerNearBottom = size.height > 0 && y >= (size.height - bottomHoverThresholdPx)
                                }
                            }
                            PointerEventType.Exit -> {
                                val change = event.changes.firstOrNull()
                                if (change == null || !Rect(Offset.Zero, size.toSize()).contains(change.position)) {
                                    pointerNearTop = false
                                    pointerNearBottom = false
                                }
                            }
                        }
                    }
                }
            }
            .background(Color.Black.copy(alpha = 1f - dismissProgress * 0.55f))
            .focusRequester(focus)
            .focusable(!pointers.isTouchLike)
            .onPreviewKeyEvent(handleKeyEvent),
    ) {
        HorizontalPager(
            state = pager,
            key = { "${it}:${images[it].id}" },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val file = images[page]
            // Do not create a decoder for adjacent precomposed pages. In particular, Desktop's
            // SkiaRegionDecoder retains the encoded original, not just visible tile pixels.
            if (page == pager.settledPage) {
                ZoomableImagePage(
                    file = file,
                    isPaging = { pager.isScrollInProgress },
                    allowTouchDismiss = windowActions == null,
                    onViewerCommand = { viewerCommand = it },
                    onZoomPercentChange = { zoomPercent = it },
                    onTap = { isChromeVisible = !isChromeVisible },
                    onDismiss = onDismiss,
                    onDismissProgress = { dismissProgress = it },
                )
            } else {
                ViewerPreview(file)
            }
        }
        PlayerTheme {
            AnimatedVisibility(
                visible = topBarVisible,
                enter = fadeIn(motion.fastEffectsSpec()),
                exit = fadeOut(motion.fastEffectsSpec()),
                modifier = Modifier.align(Alignment.TopCenter).graphicsLayer { alpha = 1f - dismissProgress },
            ) {
                ViewerTopBar(
                    title = currentFile.name,
                    position = if (images.size > 1) "${pager.currentPage + 1} / ${images.size}" else null,
                    onClose = onDismiss,
                    onDownload = if (canDownload) ::downloadCurrent else null,
                    downloadShortcut = shortcutModifier.label("S"),
                    onRevealLocalFolder = localFilePath?.let { path -> { platform.localFiles.openContainingFolder(path) } },
                    onShowProperties = { propertiesFile = currentFile },
                    onToggleFullscreen = onToggleFullscreen,
                    windowActions = windowActions,
                    dragAreaEnabled = dragAreaEnabled,
                )
            }
            if (images.size > 1 && !pointers.isTouchLike) {
                PageButton(
                    (isChromeVisible || pointerNearTop || pointerNearBottom) && pager.currentPage > 0,
                    Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                    tr("上一张", "Previous image", "前の画像", "이전 이미지", "上一張"), "←",
                    { goTo(pager.currentPage - 1) }, Modifier.align(Alignment.CenterStart),
                )
                PageButton(
                    (isChromeVisible || pointerNearTop || pointerNearBottom) && pager.currentPage < images.lastIndex,
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    tr("下一张", "Next image", "次の画像", "다음 이미지", "下一張"), "→",
                    { goTo(pager.currentPage + 1) }, Modifier.align(Alignment.CenterEnd),
                )
            }
            AnimatedVisibility(
                visible = bottomBarVisible,
                enter = slideInVertically(motion.fastSpatialSpec()) { it / 2 } + fadeIn(motion.fastEffectsSpec()),
                exit = slideOutVertically(motion.fastSpatialSpec()) { it / 2 } + fadeOut(motion.fastEffectsSpec()),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer { alpha = 1f - dismissProgress },
            ) {
                ViewerControlCapsule(
                    zoomPercent = zoomPercent,
                    onZoomOut = { viewerCommand?.invoke(ViewerCommand.Zoom(1f / KEY_ZOOM_STEP)) },
                    onResetZoom = { viewerCommand?.invoke(ViewerCommand.Zoom(null)) },
                    onZoomIn = { viewerCommand?.invoke(ViewerCommand.Zoom(KEY_ZOOM_STEP)) },
                    onRotate = { viewerCommand?.invoke(ViewerCommand.RotateCw) },
                )
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(bottom = if (bottomBarVisible) 76.dp else 16.dp),
            )
        }
    }

    propertiesFile?.let { initialFile ->
        val detailedFile by produceState(initialValue = initialFile, key1 = initialFile.id) {
            if (initialFile.size.isBlank() &&
                initialFile.id.isNotEmpty() &&
                !LocalLocation.isLocal(initialFile.id) &&
                !LeasedFile.isLeased(initialFile.id)
            ) {
                services.driveRepository.getFileDetail(initialFile.id).onSuccess { detail ->
                    value = FileStat(
                        kind = FileKind.FILE,
                        id = detail.id,
                        parentId = detail.parentId,
                        name = detail.name,
                        size = detail.size,
                        hash = detail.hash,
                        mimeType = detail.mimeType,
                        fileExtension = detail.fileExtension,
                        createdTime = detail.createdTime,
                        modifiedTime = detail.modifiedTime,
                        thumbnailLink = initialFile.thumbnailLink,
                        params = initialFile.params + detail.params,
                    )
                }
            }
        }
        PikoSheet(
            onDismissRequest = { propertiesFile = null },
            sideSheetTitle = tr("属性", "Properties", "プロパティ", "속성", "屬性"),
        ) {
            PropertiesPane(
                target = PropertiesTarget.Single(
                    name = driveItemName(detailedFile, null, null),
                    tags = emptyList(),
                    location = null,
                    isBlurred = false,
                ),
                onLocate = onLocate?.let { locate ->
                    { target ->
                        hideThen { locate(target) }
                    }
                } ?: localFilePath?.let { path ->
                    { _ ->
                        hideThen { platform.localFiles.openContainingFolder(path) }
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageButton(
    visible: Boolean,
    icon: ImageVector,
    description: String,
    shortcut: String,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val fixedColors = LocalFixedColors.current
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motion.fastEffectsSpec()),
        exit = fadeOut(motion.fastEffectsSpec()),
        modifier = modifier.padding(16.dp),
    ) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
            tooltip = { PlainTooltip { Text("$description ($shortcut)") } },
            state = rememberTooltipState(),
        ) {
            Surface(
                onClick = onClick,
                shape = CircleShape,
                color = fixedColors.ScrimOnMediaStrong,
                contentColor = fixedColors.OnMedia,
                border = BorderStroke(1.dp, fixedColors.HudSpecularBorder),
                modifier = Modifier.size(48.dp).captionExclusion(),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = description)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ViewerTopBar(
    title: String,
    position: String?,
    onClose: () -> Unit,
    onDownload: (() -> Unit)?,
    downloadShortcut: String,
    onRevealLocalFolder: (() -> Unit)?,
    onShowProperties: () -> Unit,
    onToggleFullscreen: (() -> Unit)?,
    windowActions: PlayerWindowActions?,
    dragAreaEnabled: Boolean,
) {
    val fixedColors = LocalFixedColors.current
    val framelessWindow = LocalFramelessWindow.current
    val isDesktopWindow = framelessWindow != null || windowActions != null
    val effectivePin = windowActions?.onToggleAlwaysOnTop ?: framelessWindow?.let { fw -> { fw.setAlwaysOnTop(!fw.isAlwaysOnTop) } }
    val effectiveOnTop = windowActions?.isAlwaysOnTop ?: framelessWindow?.isAlwaysOnTop ?: false
    val showWindowButtons = windowActions?.showWindowButtons ?: (framelessWindow != null)
    val effectiveClose = windowActions?.onClose ?: framelessWindow?.let { fw -> fw::close }
    val hasWindowCluster = effectivePin != null || (showWindowButtons && (windowActions?.onMinimize != null || windowActions?.onToggleMaximize != null || effectiveClose != null))
    val isFullscreen = windowActions?.isFullscreen == true

    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(TopScrim))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(
                start = 12.dp,
                end = 12.dp,
                top = 8.dp + (windowActions?.topChromePadding ?: 0.dp),
                bottom = 16.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!isDesktopWindow) {
            PlayerIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                label = tr("关闭", "Close", "閉じる", "닫기", "關閉"),
                onClick = onClose,
                tooltipBelow = true,
                shortcut = "Esc",
                containerSize = IconButtonDefaults.smallContainerSize(IconButtonDefaults.IconButtonWidthOption.Narrow),
                modifier = Modifier.captionExclusion(),
            )
        }
        Box(
            Modifier
                .weight(1f)
                .windowDragArea(enabled = dragAreaEnabled)
                .padding(horizontal = 8.dp),
        ) {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                tooltip = { PlainTooltip { Text(title) } },
                state = rememberTooltipState(),
            ) {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMediumEmphasized.copy(shadow = PlayerTextShadow),
                        color = fixedColors.OnMedia,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    if (position != null) {
                        Text(
                            text = position,
                            style = MaterialTheme.typography.labelLarge.tabular.copy(shadow = PlayerTextShadow),
                            color = fixedColors.OnMedia.copy(alpha = 0.82f),
                            maxLines = 1,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
        PlayerPillCluster {
            if (onDownload != null) {
                ClusterIconButton(
                    icon = Icons.Outlined.Download,
                    label = tr("下载", "Download", "ダウンロード", "다운로드", "下載"),
                    onClick = onDownload,
                    tooltipBelow = true,
                    shortcut = downloadShortcut,
                )
            }
            if (onRevealLocalFolder != null) {
                ClusterIconButton(
                    icon = Icons.Outlined.FolderOpen,
                    label = tr("在所在文件夹中显示", "Show in containing folder", "保存先フォルダーで表示", "포함된 폴더에서 보기", "在所在資料夾中顯示"),
                    onClick = onRevealLocalFolder,
                    tooltipBelow = true,
                )
            }
            ClusterIconButton(
                icon = Icons.Outlined.Info,
                label = tr("属性", "Properties", "プロパティ", "속성", "屬性"),
                onClick = onShowProperties,
                tooltipBelow = true,
                shortcut = "I",
            )
            if (onToggleFullscreen != null) {
                ClusterIconButton(
                    icon = if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    label = if (isFullscreen) tr("退出全屏", "Exit fullscreen", "全画面を終了", "전체 화면 종료", "結束全螢幕") else tr("全屏", "Fullscreen", "全画面", "전체 화면", "全螢幕"),
                    onClick = onToggleFullscreen,
                    tooltipBelow = true,
                    shortcut = "F",
                )
            }
        }
        if (hasWindowCluster) {
            MediaWindowControlsCluster(
                windowActions = windowActions,
                effectivePin = effectivePin,
                effectiveOnTop = effectiveOnTop,
                showWindowButtons = showWindowButtons,
                effectiveClose = effectiveClose,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ViewerControlCapsule(
    zoomPercent: Int,
    onZoomOut: () -> Unit,
    onResetZoom: () -> Unit,
    onZoomIn: () -> Unit,
    onRotate: () -> Unit,
) {
    val fixedColors = LocalFixedColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, fixedColors.ScrimOnMedia.copy(alpha = 0.6f))))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(top = 20.dp, bottom = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        HorizontalFloatingToolbar(
            expanded = true,
            colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
                toolbarContainerColor = fixedColors.ScrimOnMediaStrong,
                toolbarContentColor = fixedColors.OnMedia,
            ),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
            expandedShadowElevation = 0.dp,
            collapsedShadowElevation = 0.dp,
            modifier = Modifier
                .border(1.dp, fixedColors.HudSpecularBorder, FloatingToolbarDefaults.ContainerShape)
                .captionExclusion(),
        ) {
            TooltipIconButton(
                icon = Icons.Outlined.ZoomOut,
                label = tr("缩小", "Zoom out", "縮小", "축소", "縮小"),
                onClick = onZoomOut,
                shortcut = "-",
                tint = fixedColors.OnMedia,
            )
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                tooltip = { PlainTooltip { Text(tr("重置缩放 (0)", "Reset zoom (0)", "ズームをリセット (0)", "배율 재설정 (0)", "重設縮放 (0)")) } },
                state = rememberTooltipState(),
            ) {
                TextButton(
                    onClick = onResetZoom,
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.textButtonColors(contentColor = fixedColors.OnMedia),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    Text(
                        text = "${zoomPercent}%",
                        style = MaterialTheme.typography.labelLarge.tabular,
                        maxLines = 1,
                    )
                }
            }
            TooltipIconButton(
                icon = Icons.Outlined.ZoomIn,
                label = tr("放大", "Zoom in", "拡大", "확대", "放大"),
                onClick = onZoomIn,
                shortcut = "+",
                tint = fixedColors.OnMedia,
            )
            VerticalDivider(
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .padding(horizontal = 4.dp)
                    .height(18.dp),
                color = fixedColors.HudSpecularBorder,
            )
            TooltipIconButton(
                icon = Icons.Outlined.RotateRight,
                label = tr("旋转 90°", "Rotate 90°", "90° 回転", "90° 회전", "旋轉 90°"),
                onClick = onRotate,
                shortcut = "R",
                tint = fixedColors.OnMedia,
            )
        }
    }
}

@Composable
private fun ViewerPreview(file: FileStat) {
    val platform = LocalPikoPlatform.current
    val cover = file.previewImageCoverUrl().ifBlank { file.params["local_path"].orEmpty() }
    if (cover.isBlank()) return
    val model = remember(platform, cover) { platform.resolveCoilModel(cover) }
    AsyncImage(model, file.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
}

@Composable
private fun ZoomableImagePage(
    file: FileStat,
    isPaging: () -> Boolean,
    allowTouchDismiss: Boolean,
    onViewerCommand: (((ViewerCommand) -> Unit)?) -> Unit,
    onZoomPercentChange: (Int) -> Unit,
    onTap: () -> Unit,
    onDismiss: () -> Unit,
    onDismissProgress: (Float) -> Unit,
) {
    val services = LocalPikoServices.current
    val platform = LocalPikoPlatform.current
    val context = LocalPlatformContext.current
    var fullUrl by remember(file.id) { mutableStateOf<String?>(null) }
    var ready by remember(file.id) { mutableStateOf(false) }
    var hasImage by remember(file.id) { mutableStateOf(false) }
    var failedUrl by remember(file.id) { mutableStateOf<String?>(null) }
    var fallbackTried by remember(file.id) { mutableStateOf(false) }
    LaunchedEffect(file.id, services) {
        val explicitLocalPath = file.params["local_path"]
            ?.takeIf { it.isNotBlank() && platform.localFiles.exists(it) }
        val completedLocalPath = services.downloadManager.tasks.value.values.find {
            it.fileId == file.id && it.status == DownloadStatus.COMPLETED && !it.isSegment
        }?.destinationPath?.takeIf(platform.localFiles::exists)
        fullUrl = when {
            explicitLocalPath != null -> explicitLocalPath
            completedLocalPath != null -> completedLocalPath
            LocalLocation.isLocal(file.id) -> file.thumbnailLink.takeIf { it.isNotBlank() }
            else -> services.driveRepository.originalImageUrl(file.id)
                ?: services.mediaRepository.fetchDownloadUrl(file.id).getOrNull()
        }
        if (fullUrl == null) ready = true
    }
    // One refresh per page lifetime. URL identity rejects callbacks from an earlier request.
    LaunchedEffect(file.id, failedUrl) {
        val failed = failedUrl ?: return@LaunchedEffect
        if (failed != fullUrl) return@LaunchedEffect
        if (!fallbackTried && LeasedFile.isLeased(file.id)) {
            fallbackTried = true
            val replacement = services.mediaRepository.fetchDownloadUrl(file.id).getOrNull()
            if (replacement != null && replacement != failed) {
                ready = false
                hasImage = false
                fullUrl = replacement
            } else ready = true
        } else ready = true
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (!hasImage) ViewerPreview(file)
        fullUrl?.let { url ->
            // Replacing the URL disposes the old loader, decoder and all gesture/keyboard jobs.
            key(file.id, url) {
                // Library diagnostics contain raw source URLs; do not emit them to platform logs.
                val zoom = rememberCoilZoomState(logLevel = com.github.panpf.zoomimage.util.Logger.Level.Assert)
                val scope = rememberCoroutineScope()
                var keyJob by remember { mutableStateOf<Job?>(null) }
                var dragY by remember { mutableFloatStateOf(0f) }
                val atFit by remember { derivedStateOf { zoom.zoomable.transform.scaleX <= zoom.zoomable.minScale * 1.001f } }
                val currentZoomPercent by remember {
                    derivedStateOf {
                        val base = zoom.zoomable.minScale.takeIf { it > 0.001f } ?: 1f
                        ((zoom.zoomable.transform.scaleX / base) * 100f).roundToInt().coerceAtLeast(10)
                    }
                }
                LaunchedEffect(currentZoomPercent) { onZoomPercentChange(currentZoomPercent) }
                val paging by rememberUpdatedState(isPaging)
                val tap by rememberUpdatedState(onTap)
                val dismiss by rememberUpdatedState(onDismiss)
                val progress by rememberUpdatedState(onDismissProgress)
                DisposableEffect(zoom) {
                    // Avoid retaining tile history in Coil's shared thumbnail cache. Only visible
                    // foreground tiles remain owned by this page; no second background tile layer.
                    zoom.subsampling.setDisabledTileImageCache(true)
                    zoom.subsampling.setDisabledBackgroundTiles(true)
                    onViewerCommand { command ->
                        keyJob?.cancel()
                        keyJob = scope.launch {
                            when (command) {
                                is ViewerCommand.Zoom -> {
                                    if (command.factor == null) {
                                        zoom.zoomable.scale(zoom.zoomable.minScale)
                                        zoom.zoomable.reset()
                                    } else zoom.zoomable.scaleBy(command.factor, animated = true)
                                }
                                ViewerCommand.RotateCw -> zoom.zoomable.rotateBy(90)
                            }
                        }
                    }
                    onDispose {
                        keyJob?.cancel()
                        zoom.subsampling.setStopped(true)
                        zoom.setSubsamplingImage(null as com.github.panpf.zoomimage.subsampling.SubsamplingImage?)
                        zoom.zoomable.reset()
                        onViewerCommand(null)
                        onZoomPercentChange(100)
                        progress(0f)
                    }
                }
                LaunchedEffect(zoom, url) {
                    snapshotFlow { zoom.subsampling.foregroundTiles.any { it.state == TileState.STATE_ERROR } }
                        .collect { if (it && fullUrl == url) failedUrl = url }
                }
                val model = remember(platform, url) { platform.resolveCoilModel(url) }
                val request = remember(context, model) {
                    ImageRequest.Builder(context).data(model)
                        // ZoomImage supplies a constraints size resolver: the base bitmap is only
                        // viewport-sized, while subsampling reuses the disk-cached original.
                        .memoryCachePolicy(CachePolicy.DISABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build()
                }
                CoilZoomAsyncImage(
                    model = request,
                    contentDescription = file.name,
                    zoomState = zoom,
                    scrollBar = null,
                    onTap = { tap() },
                    onSuccess = { if (fullUrl == url) { ready = true; hasImage = true } },
                    onError = { if (fullUrl == url) failedUrl = url },
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(zoom, allowTouchDismiss) {
                            if (!allowTouchDismiss) return@pointerInput
                            try {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    if (down.type != PointerType.Touch || !atFit || paging()) return@awaitEachGesture
                                    var total = Offset.Zero
                                    var owned = false
                                    var cancelled = false
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        if (event.changes.size != 1 || !atFit || paging()) {
                                            cancelled = true
                                            break
                                        }
                                        val change = event.changes.single()
                                        if (change.isConsumed) { cancelled = true; break }
                                        total += change.position - change.previousPosition
                                        if (!owned && total.getDistance() > viewConfiguration.touchSlop) {
                                            // Horizontal, upward and multi-pointer gestures remain ZoomImage/Pager's.
                                            if (total.y <= abs(total.x)) { cancelled = true; break }
                                            owned = true
                                        }
                                        if (owned) {
                                            dragY = total.y.coerceAtLeast(0f)
                                            progress((dragY / (size.height * 0.4f)).coerceIn(0f, 1f))
                                            change.consume()
                                        }
                                    } while (event.changes.any { it.pressed })
                                    if (owned && !cancelled && dragY > size.height * 0.16f) dismiss()
                                    dragY = 0f
                                    progress(0f)
                                }
                            } finally {
                                dragY = 0f
                                progress(0f)
                            }
                        }
                        .graphicsLayer {
                            val shrink = 1f - (dragY / (size.height * 0.4f)).coerceIn(0f, 1f) * 0.2f
                            scaleX = shrink
                            scaleY = shrink
                            translationY = dragY
                        },
                )
            }
        }
        if (!ready) MediaLoadingIndicator(Modifier.align(Alignment.Center))
    }
}
