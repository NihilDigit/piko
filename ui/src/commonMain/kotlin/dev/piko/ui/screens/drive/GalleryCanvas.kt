package dev.piko.ui.screens.drive

import dev.piko.shared.i18n.tr

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import coil3.request.ErrorResult
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.decode.BlackholeDecoder
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import dev.piko.ui.adaptive.currentWidthClass
import dev.piko.ui.components.ContextMenuArea
import dev.piko.ui.components.FileItemSelection
import dev.piko.ui.components.FileOverlaySelectionControl
import dev.piko.ui.components.PikoBrandIcons
import dev.piko.ui.components.PosterSpoilerBlur
import dev.piko.ui.components.SpoilerThumbnail
import dev.piko.ui.components.fileItemHover
import dev.piko.ui.components.focusIndication
import dev.piko.ui.components.locateHighlight
import dev.piko.ui.components.LocalOwnClicks
import dev.piko.ui.components.OwnClicks
import dev.piko.ui.components.ownsClicks
import dev.piko.ui.components.placeholderColors
import dev.piko.ui.components.selectionClicks
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.components.watermarkIcon
import dev.piko.ui.components.zoomOnWheel
import dev.piko.data.repository.isPlayableVideo
import dev.piko.data.repository.isPreviewableImage
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.ShortcutModifier
import dev.piko.ui.platform.resolveCoilModel
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.imageThumbnailUrlOf
import io.github.nihildigit.pikpak.videoThumbnailUrlOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

private fun FileStat.galleryCoverUrl(): String =
    thumbnailLink.ifBlank {
        when {
            hash.isBlank() || isFolder -> ""
            isPreviewableImage() && !isPlayableVideo() -> imageThumbnailUrlOf(hash)
            else -> videoThumbnailUrlOf(hash)
        }
    }

/** Keep this state at the browsing entry, so opening and closing playback retains the band. */
internal class GalleryCanvasState internal constructor(anchor: GalleryAnchor? = null) {
    internal val layout = GalleryCanvasLayout(anchor)
    internal var revision by mutableIntStateOf(0)
    internal var visibleFileIds by mutableStateOf<Set<String>>(emptySet())

    internal fun scrollBy(pixels: Float): Float {
        layout.scrollBy(-pixels.toDouble())
        revision++
        return pixels
    }

    internal companion object {
        val Saver = listSaver<GalleryCanvasState, Any>(
            save = {
                val anchor = it.layout.anchor()
                listOf(anchor?.fileId.orEmpty(), anchor?.ordinal ?: 0L, anchor?.column ?: 0, anchor?.fraction ?: 0.0)
            },
            restore = {
                GalleryCanvasState(GalleryAnchor((it[0] as String).takeIf(String::isNotEmpty), it[1] as Long, it[2] as Int, it[3] as Double))
            },
        )
    }
}

@Composable
internal fun rememberGalleryCanvasState(): GalleryCanvasState = rememberSaveable(saver = GalleryCanvasState.Saver) { GalleryCanvasState() }

internal fun galleryAspectRatio(file: FileStat): Float? {
    val width = file.params["width"]?.toFloatOrNull() ?: return null
    val height = file.params["height"]?.toFloatOrNull() ?: return null
    return (width / height).takeIf { width > 0 && height > 0 && it.isFinite() }
}

@Composable
internal fun GalleryCanvas(
    files: List<FileStat>,
    tileSize: TileSize,
    isBlurred: (FileStat) -> Boolean,
    callbacks: DriveItemCallbacks,
    modifier: Modifier = Modifier,
    state: GalleryCanvasState = rememberGalleryCanvasState(),
    isSelectionMode: Boolean = false,
    selectedIds: Set<String> = emptySet(),
    highlightedIds: Set<String> = emptySet(),
    onZoom: (Boolean) -> Unit = {},
    aspectRatio: (FileStat) -> Float? = ::galleryAspectRatio,
    locationLabel: (FileStat) -> String? = { null },
    onVisibleFilesChanged: (Set<String>) -> Unit = {},
) {
    val widthClass = currentWidthClass()
    val columns = galleryTileColumns(tileSize, widthClass)
    val maximumWidth = galleryTileMaxWidth(tileSize)
    val learnedShapes = remember(state) { mutableStateMapOf<String, GalleryShape>() }
    val failedCovers = remember(state) { mutableStateSetOf<String>() }
    val prefetched = remember(state) { hashSetOf<String>() }
    val inFlight = remember(state) { hashSetOf<String>() }
    val prefetchScope = rememberCoroutineScope()
    val prefetchLimit = remember(state) { Semaphore(COVER_PREFETCH_CONCURRENCY) }
    val platformContext = LocalPlatformContext.current
    val platform = LocalPikoPlatform.current
    val coverFiles = files
    val byId by remember(coverFiles) {
        derivedStateOf { coverFiles.associateBy { it.id } }
    }
    fun recordDecodedRatio(file: FileStat, ratio: Float) {
        if (aspectRatio(file) != null) return
        val decodedShape = GalleryShape.nearest(ratio.toDouble())
        val currentShape = learnedShapes[file.id] ?: GalleryShape.nearest(null)
        if (decodedShape != currentShape) {
            learnedShapes[file.id] = decodedShape
        }
    }
    val descriptors by remember(coverFiles, aspectRatio) {
        derivedStateOf {
            coverFiles.distinctBy { it.id }.map { file ->
                val knownRatio = aspectRatio(file)
                val shape = if (knownRatio != null) {
                    GalleryShape.nearest(knownRatio.toDouble())
                } else {
                    learnedShapes[file.id] ?: GalleryShape.nearest(null)
                }
                GalleryLayoutItem(file.id, shape)
            }
        }
    }
    val onVisible by rememberUpdatedState(onVisibleFilesChanged)
    LaunchedEffect(state) {
        snapshotFlow { state.visibleFileIds }.collect { onVisible(it) }
    }
    // 封面预取：轻微防抖并跳过屏幕上已由 AsyncImage 高优加载的可见项，避免在高延迟线路上与首屏争抢连接；
    // 已知宽高比的封面仅预热磁盘缓存（零 CPU 解码），未知宽高比的仅按小尺寸探测比例。
    LaunchedEffect(coverFiles, state.visibleFileIds) {
        val visible = state.visibleFileIds
        if (visible.isNotEmpty()) delay(COVER_PREFETCH_DEBOUNCE_MS)
        val anchorIndex = if (visible.isEmpty()) 0 else coverFiles.indexOfLast { it.id in visible }.coerceAtLeast(0)
        val targets = coverFiles.drop((anchorIndex - COVER_PREFETCH_BEHIND).coerceAtLeast(0))
            .take(COVER_PREFETCH_AHEAD + COVER_PREFETCH_BEHIND)
            .filter { it.id !in visible && it.galleryCoverUrl().isNotEmpty() && it.id !in prefetched && inFlight.add(it.id) }
        if (targets.isEmpty()) return@LaunchedEffect
        val loader = SingletonImageLoader.get(platformContext)
        targets.forEach { file ->
            val coverModel = platform.resolveCoilModel(file.galleryCoverUrl())
            val shapeKnown = aspectRatio(file) != null || file.id in learnedShapes
            prefetchScope.launch {
                try {
                    prefetchLimit.withPermit {
                        @OptIn(coil3.annotation.ExperimentalCoilApi::class)
                        val request = ImageRequest.Builder(platformContext)
                            .data(coverModel)
                            .apply {
                                if (shapeKnown) {
                                    // 已有内存缓存时直接命中短路；未缓存时经 BlackholeDecoder 仅写入磁盘缓存，跳过位图 CPU 解码
                                    memoryCachePolicy(CachePolicy.READ_ONLY)
                                    decoderFactory(BlackholeDecoder.Factory())
                                } else {
                                    size(COVER_PROBE_DECODE_PX, COVER_PROBE_DECODE_PX)
                                }
                            }
                            .build()
                        val result = loader.execute(request)
                        val image = result.image
                        if (shapeKnown && result !is ErrorResult) {
                            prefetched.add(file.id)
                        } else if (image != null) {
                            prefetched.add(file.id)
                            if (image.width > 0 && image.height > 0) {
                                recordDecodedRatio(file, image.width.toFloat() / image.height)
                            }
                        } else if (result is ErrorResult &&
                            result.throwable !is kotlinx.coroutines.CancellationException &&
                            result.throwable.cause !is kotlinx.coroutines.CancellationException
                        ) {
                            failedCovers.add(file.id)
                        }
                    }
                } finally {
                    inFlight.remove(file.id)
                }
            }
        }
    }
    val scroll = rememberScrollableState(state::scrollBy)
    SubcomposeLayout(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .zoomOnWheel(onZoom)
            .scrollable(scroll, Orientation.Vertical)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val delta = when (event.key) {
                    Key.PageUp -> state.layout.viewportHeight.toFloat() * 0.9f
                    Key.PageDown -> -state.layout.viewportHeight.toFloat() * 0.9f
                    else -> return@onKeyEvent false
                }
                state.scrollBy(delta)
                true
            }
            .focusable()
            .semantics {
                collectionInfo = CollectionInfo(rowCount = -1, columnCount = columns)
                scrollBy { _, y -> state.scrollBy(-y); true }
            },
    ) { constraints ->
        state.revision
        val gap = 8.dp.toPx().toDouble()
        val inset = 12.dp.toPx().toDouble()
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        if (width == 0 || height == 0) return@SubcomposeLayout layout(width, height) {}
        val availableWidth = (width - inset * 2).coerceAtLeast(1.0)
        val count = max(columns, ceil((availableWidth + gap) / (maximumWidth.toPx() + gap)).toInt())
        val placements = state.layout.frame(descriptors, count, availableWidth, height.toDouble(), gap)
        val nextVisible = placements.filter { it.top < height && it.top + it.height > 0 }.mapNotNullTo(HashSet()) { it.fileId }
        if (nextVisible != state.visibleFileIds) state.visibleFileIds = nextVisible
        val measured = placements.map { placement ->
            val file = placement.fileId?.let(byId::get)
            val placeable = subcompose(placement.key) {
                if (file == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            PikoBrandIcons.Glyph,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f),
                            modifier = Modifier.size(32.dp),
                        )
                    }
                } else {
                    GalleryCanvasCover(
                        file, isSelectionMode, file.id in selectedIds, file.id in highlightedIds,
                        isBlurred(file), callbacks,
                        locationLabel = locationLabel(file),
                        onAspectRatio = { ratio -> recordDecodedRatio(file, ratio) },
                        onCoverFailed = { failedCovers.add(file.id) },
                    )
                }
            }.single().measure(Constraints.fixed(placement.width.roundToInt().coerceAtLeast(1), placement.height.roundToInt().coerceAtLeast(1)))
            placement to placeable
        }
        layout(width, height) {
            measured.forEach { (placement, placeable) -> placeable.place((placement.left + inset).roundToInt(), placement.top.roundToInt()) }
        }
    }
}

@Composable
private fun GalleryCanvasCover(
    file: FileStat,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isHighlighted: Boolean,
    isBlurred: Boolean,
    callbacks: DriveItemCallbacks,
    locationLabel: String?,
    onAspectRatio: (Float) -> Unit,
    onCoverFailed: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val ownClicks = remember { OwnClicks() }
    val hover = fileItemHover()
    val shape = MaterialTheme.shapes.medium
    val isVideo = file.isPlayableVideo()
    val durationLabel = if (isVideo) {
        file.params["duration"]?.toDoubleOrNull()?.toLong()?.takeIf { it > 0 }?.let { seconds ->
            val minutes = (seconds % 3600 / 60).toString().padStart(2, '0')
            val remainder = (seconds % 60).toString().padStart(2, '0')
            if (seconds >= 3600) "${seconds / 3600}:$minutes:$remainder" else "$minutes:$remainder"
        }
    } else null
    val isMac = LocalPikoPlatform.current.shortcutModifier == ShortcutModifier.Command
    val coverUrl = file.galleryCoverUrl()
    var coverFailed by remember(coverUrl) { mutableStateOf(false) }
    CompositionLocalProvider(LocalOwnClicks provides ownClicks) {
        ContextMenuArea(
            actions = { callbacks.contextActions(file) },
            modifier = Modifier
                .fillMaxSize()
                .then(hover.modifier)
                .focusRequester(focus)
                .onFocusChanged { callbacks.onFocusChanged(file, it.hasFocus) }
                .onPlaced { callbacks.onPlaced(file, it) }
                .selectionClicks(
                    onToggle = { callbacks.onToggleSelect(file.id) },
                    onExtend = { callbacks.onExtendSelect(file.id) },
                    onDoubleClick = if (isSelectionMode) null else ({ callbacks.onOpen(file) }),
                    onFocus = { runCatching { focus.requestFocus() } },
                    ownClicks = ownClicks,
                )
                .pointerInput(file.id) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press && event.buttons.isTertiaryPressed) callbacks.onMiddleClick(file)
                        }
                    }
                }
                .onPreviewKeyEvent { event ->
                    val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                    if (enter && !isMac && event.isAltPressed) {
                        if (event.type == KeyEventType.KeyDown) callbacks.onProperties(file)
                        true
                    } else if (enter && isMac && !isSelectionMode && callbacks.onRename != null) {
                        if (event.type == KeyEventType.KeyDown) callbacks.onRename(file)
                        true
                    } else false
                },
        ) {
            Box(
                Modifier.fillMaxSize()
                    .semantics(mergeDescendants = true) {}
                    .focusIndication(shape, showPointerFocus = !isSelectionMode)
                    .clip(shape)
                    .cardInteraction(isSelectionMode, isSelected, { callbacks.onOpen(file) }, { callbacks.onLongClick(file) }, { callbacks.onSelect(file, it) })
                    .border(
                        if (isSelected) 3.dp else 1.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        shape,
                    )
                    .locateHighlight(isHighlighted, shape),
            ) {
                if (coverUrl.isEmpty() || coverFailed) {
                    val (container, content) = file.placeholderColors()
                    Box(Modifier.fillMaxSize().background(container), contentAlignment = Alignment.Center) {
                        Icon(
                            file.watermarkIcon(),
                            contentDescription = null,
                            tint = content.copy(alpha = 0.6f),
                            modifier = Modifier.size(40.dp).padding(bottom = 12.dp),
                        )
                    }
                } else {
                    SpoilerThumbnail(
                        coverUrl,
                        isBlurred,
                        PosterSpoilerBlur,
                        Modifier.fillMaxSize(),
                        onAspectRatio = onAspectRatio,
                        onError = {
                            coverFailed = true
                            onCoverFailed()
                        },
                    )
                }
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.28f), Color.Black.copy(alpha = 0.80f))))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        file.name.substringBeforeLast('.', file.name),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!locationLabel.isNullOrBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Outlined.Folder, contentDescription = null, tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(12.dp))
                            Text(locationLabel, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (isSelectionMode || (hover.mouseHovered && callbacks.onStartSelection != null)) {
                    FileOverlaySelectionControl(
                        FileItemSelection(
                            if (isSelected) ToggleableState.On else ToggleableState.Off,
                            { if (isSelectionMode) callbacks.onSelect(file, !isSelected) else callbacks.onStartSelection?.invoke(file) },
                            label = tr("选择 ${file.name}", "Select ${file.name}", "${file.name} を選択", "${file.name} 선택", "選取 ${file.name}"),
                        ),
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp).then(if (!isSelectionMode) Modifier.ownsClicks() else Modifier),
                        interactive = !isSelectionMode,
                    )
                }
                // 照片右上角不挂任何角标，将完整构图留给缩略图本身；仅视频保留紧凑的微型播放三角与时长胶囊
                if (isVideo) {
                    Row(
                        modifier = Modifier.align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.extraSmall)
                            .padding(horizontal = if (durationLabel != null) 6.dp else 5.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(11.dp),
                        )
                        if (durationLabel != null) {
                            Text(
                                durationLabel,
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val COVER_PREFETCH_AHEAD = 64
private const val COVER_PREFETCH_BEHIND = 12
private const val COVER_PREFETCH_CONCURRENCY = 8
private const val COVER_PREFETCH_DEBOUNCE_MS = 100L
private const val COVER_PROBE_DECODE_PX = 240
