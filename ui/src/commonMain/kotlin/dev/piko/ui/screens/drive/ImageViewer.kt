package dev.piko.ui.screens.drive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.github.panpf.zoomimage.CoilZoomAsyncImage
import com.github.panpf.zoomimage.rememberCoilZoomState
import com.github.panpf.zoomimage.subsampling.TileState
import dev.piko.shared.data.LeasedFile
import dev.piko.shared.data.LocalLocation
import dev.piko.shared.i18n.tr
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.MediaLoadingIndicator
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.previewImageCoverUrl
import dev.piko.ui.components.trackPointerSource
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.resolveCoilModel
import io.github.nihildigit.pikpak.FileStat
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val KEY_ZOOM_STEP = 1.5f

/** ZoomImage alone owns image transforms; the outer layer only owns downward dismissal. */
@Composable
internal fun ImageViewer(images: List<FileStat>, initialIndex: Int, onDismiss: () -> Unit) {
    if (images.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    var isChromeVisible by remember { mutableStateOf(true) }
    LocalPikoPlatform.current.FullscreenDialog(
        onDismiss = onDismiss,
        immersive = true,
        systemBarsVisible = isChromeVisible,
    ) {
        val pointers = LocalPointerSource.current
        val pager = rememberPagerState(initialPage = initialIndex.coerceIn(images.indices)) { images.size }
        val scope = rememberCoroutineScope()
        val focus = remember { FocusRequester() }
        var dismissProgress by remember { mutableFloatStateOf(0f) }
        // The registered callback belongs to the active page's scope, not the dialog scope.
        var zoomCommand by remember { mutableStateOf<((Float?) -> Unit)?>(null) }
        LaunchedEffect(pointers.isTouchLike) {
            if (!pointers.isTouchLike) runCatching { focus.requestFocus() }
        }
        fun goTo(page: Int) {
            val target = page.coerceIn(images.indices)
            if (target != pager.currentPage) scope.launch { pager.animateScrollToPage(target) }
        }
        Box(
            Modifier.fillMaxSize()
                .trackPointerSource(pointers)
                .background(Color.Black.copy(alpha = 1f - dismissProgress * 0.55f))
                .focusRequester(focus)
                .focusable(!pointers.isTouchLike)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft, Key.PageUp -> goTo(pager.currentPage - 1)
                        Key.DirectionRight, Key.PageDown, Key.Spacebar -> goTo(pager.currentPage + 1)
                        Key.MoveHome -> goTo(0)
                        Key.MoveEnd -> goTo(images.lastIndex)
                        Key.Plus, Key.Equals, Key.NumPadAdd -> zoomCommand?.invoke(KEY_ZOOM_STEP)
                        Key.Minus, Key.NumPadSubtract -> zoomCommand?.invoke(1f / KEY_ZOOM_STEP)
                        Key.Zero, Key.NumPad0 -> zoomCommand?.invoke(null)
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                },
        ) {
            HorizontalPager(
                state = pager,
                key = { images[it].id },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val file = images[page]
                // Do not create a decoder for adjacent precomposed pages. In particular, Desktop's
                // SkiaRegionDecoder retains the encoded original, not just visible tile pixels.
                if (page == pager.settledPage) {
                    ZoomableImagePage(
                        file = file,
                        isPaging = { pager.isScrollInProgress },
                        onZoomCommand = { zoomCommand = it },
                        onTap = { isChromeVisible = !isChromeVisible },
                        onDismiss = onDismiss,
                        onDismissProgress = { dismissProgress = it },
                    )
                } else {
                    ViewerPreview(file)
                }
            }
            AnimatedVisibility(
                visible = isChromeVisible,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).graphicsLayer { alpha = 1f - dismissProgress },
            ) {
                ViewerTopBar(
                    images[pager.currentPage.coerceIn(images.indices)].name,
                    if (images.size > 1) "${pager.currentPage + 1} / ${images.size}" else null,
                    onDismiss,
                )
            }
            if (images.size > 1 && !pointers.isTouchLike) {
                PageButton(
                    isChromeVisible && pager.currentPage > 0,
                    Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                    tr("上一张", "Previous image", "前の画像", "이전 이미지", "上一張"), "←",
                    { goTo(pager.currentPage - 1) }, Modifier.align(Alignment.CenterStart),
                )
                PageButton(
                    isChromeVisible && pager.currentPage < images.lastIndex,
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    tr("下一张", "Next image", "次の画像", "다음 이미지", "下一張"), "→",
                    { goTo(pager.currentPage + 1) }, Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageButton(
    visible: Boolean, icon: ImageVector, description: String, shortcut: String,
    onClick: () -> Unit, modifier: Modifier,
) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier.padding(16.dp)) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
            tooltip = { PlainTooltip { Text("$description ($shortcut)") } },
            state = rememberTooltipState(),
        ) {
            FilledTonalIconButton(
                onClick = onClick,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.45f), contentColor = Color.White,
                ),
            ) { Icon(icon, contentDescription = description) }
        }
    }
}

@Composable
private fun ViewerTopBar(title: String, position: String?, onClose: () -> Unit) {
    Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))) {
        Row(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TooltipIconButton(Icons.AutoMirrored.Outlined.ArrowBack, tr("关闭", "Close", "閉じる", "닫기", "關閉"), onClose, shortcut = "Esc", tint = Color.White)
            Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (position != null) {
                Spacer(Modifier.width(12.dp))
                Text(position, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
private fun ViewerPreview(file: FileStat) {
    val platform = LocalPikoPlatform.current
    val cover = file.previewImageCoverUrl()
    val model = remember(platform, cover) { platform.resolveCoilModel(cover) }
    AsyncImage(model, file.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
}

@Composable
private fun ZoomableImagePage(
    file: FileStat,
    isPaging: () -> Boolean,
    onZoomCommand: (((Float?) -> Unit)?) -> Unit,
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
        fullUrl = if (LocalLocation.isLocal(file.id)) file.thumbnailLink.takeIf { it.isNotBlank() }
        else services.driveRepository.originalImageUrl(file.id)
            ?: services.mediaRepository.fetchDownloadUrl(file.id).getOrNull()
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
                val paging by rememberUpdatedState(isPaging)
                val tap by rememberUpdatedState(onTap)
                val dismiss by rememberUpdatedState(onDismiss)
                val progress by rememberUpdatedState(onDismissProgress)
                DisposableEffect(zoom) {
                    // Avoid retaining tile history in Coil's shared thumbnail cache. Only visible
                    // foreground tiles remain owned by this page; no second background tile layer.
                    zoom.subsampling.setDisabledTileImageCache(true)
                    zoom.subsampling.setDisabledBackgroundTiles(true)
                    onZoomCommand { factor ->
                        keyJob?.cancel()
                        keyJob = scope.launch {
                            if (factor == null) {
                                zoom.zoomable.scale(zoom.zoomable.minScale)
                                zoom.zoomable.reset()
                            } else zoom.zoomable.scaleBy(factor, animated = true)
                        }
                    }
                    onDispose {
                        keyJob?.cancel()
                        zoom.subsampling.setStopped(true)
                        zoom.setSubsamplingImage(null as com.github.panpf.zoomimage.subsampling.SubsamplingImage?)
                        zoom.zoomable.reset()
                        onZoomCommand(null)
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
                        .pointerInput(zoom) {
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
