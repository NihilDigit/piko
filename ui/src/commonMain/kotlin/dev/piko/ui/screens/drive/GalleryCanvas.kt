package dev.piko.ui.screens.drive

import dev.piko.shared.data.CardBadgeKind
import dev.piko.shared.data.DetailField
import dev.piko.shared.data.FolderStyle
import dev.piko.shared.data.isDriveFolderId
import dev.piko.shared.i18n.PikoLocale
import dev.piko.shared.i18n.tr
import dev.piko.shared.naming.TagKind
import dev.piko.shared.naming.parseMediaName

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.collectAsState
import dev.piko.ui.LocalPikoServices
import dev.piko.shared.memory.MemoryPolicy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.decode.BlackholeDecoder
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import dev.piko.ui.adaptive.currentWidthClass
import dev.piko.ui.components.WindowAnchoredActionMenu
import dev.piko.ui.components.FileArtwork
import dev.piko.ui.components.FileArtworkFormat
import dev.piko.ui.components.FileItemMarks
import dev.piko.ui.components.FileItemSelection
import dev.piko.ui.components.FileOverlaySelectionControl
import dev.piko.ui.components.MetaRow
import dev.piko.ui.components.PikoBrandIcons
import dev.piko.ui.components.PosterSpoilerBlur
import dev.piko.ui.components.SpoilerThumbnail
import dev.piko.ui.components.detailLineParts
import dev.piko.ui.components.displayTitle
import dev.piko.ui.components.extensionLabel
import dev.piko.ui.components.fileDurationMillis
import dev.piko.ui.components.fileItemHover
import dev.piko.ui.components.fileItemMarks
import dev.piko.ui.components.fileVisual
import dev.piko.ui.components.focusIndication
import dev.piko.ui.components.locateHighlight
import dev.piko.ui.components.LocalOwnClicks
import dev.piko.ui.components.OwnClicks
import dev.piko.ui.components.onSecondaryPress
import dev.piko.ui.components.ownsClicks
import dev.piko.ui.components.placeholderColors
import dev.piko.ui.components.resolveThumbnailModelForClarity
import dev.piko.ui.components.selectionClicks
import dev.piko.ui.components.thumbnailDiskCacheKey
import dev.piko.ui.components.thumbnailMemoryCacheKey
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.components.watermarkIcon
import dev.piko.ui.components.zoomOnWheel
import dev.piko.ui.format.formatDurationMillis
import dev.piko.data.repository.isPlayableVideo
import dev.piko.data.repository.isPreviewableImage
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.ShortcutModifier
import dev.piko.ui.platform.resolveCoilModel
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.imageThumbnailUrlOf
import io.github.nihildigit.pikpak.videoThumbnailUrlOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
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

private data class GallerySlotItem(
    val key: GalleryOccurrenceKey,
    val fileId: String?,
    val leftPx: Int,
    val absoluteTopPx: Int,
    val widthPx: Int,
    val heightPx: Int,
)

/** Keep this state at the browsing entry, so opening and closing playback retains the band. */
@Stable
internal class GalleryCanvasState internal constructor(anchor: GalleryAnchor? = null) {
    internal val layout = GalleryCanvasLayout(anchor)
    internal var revision by mutableIntStateOf(0)
    internal var visibleFileIds by mutableStateOf<Set<String>>(emptySet())
    internal val learnedShapes = mutableStateMapOf<String, GalleryShape>()
    internal val failedCovers = mutableStateSetOf<String>()
    internal val prefetched = hashSetOf<String>()

    internal fun scrollBy(pixels: Float): Float {
        val consumed = layout.scrollBy(-pixels.toDouble())
        if (consumed != 0.0) revision++
        return if (consumed == 0.0) 0f else (-consumed).toFloat()
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
    val learnedShapes = state.learnedShapes
    val failedCovers = state.failedCovers
    val prefetched = state.prefetched
    val services = LocalPikoServices.current
    val memoryPolicy by services.memoryGovernor.policy.collectAsState()
    val workBudget by services.workloadGovernor.budget.collectAsState()
    val platformContext = LocalPlatformContext.current
    val platform = LocalPikoPlatform.current
    val coverFiles = files
    val byId = remember(coverFiles) { coverFiles.associateBy { it.id } }
    val indexById = remember(coverFiles) {
        HashMap<String, Int>(coverFiles.size * 2).apply {
            coverFiles.forEachIndexed { idx, file -> put(file.id, idx) }
        }
    }
    val latestAspectRatio by rememberUpdatedState(aspectRatio)
    val onRecordAspectRatio: (FileStat, Float) -> Unit = remember(state) {
        { targetFile, ratio ->
            if (latestAspectRatio(targetFile) == null) {
                val decodedShape = GalleryShape.nearest(ratio.toDouble())
                val currentShape = state.learnedShapes[targetFile.id] ?: GalleryShape.nearest(null)
                if (decodedShape != currentShape && (targetFile.id in state.learnedShapes || state.learnedShapes.size < COVER_METADATA_LIMIT)) {
                    state.learnedShapes[targetFile.id] = decodedShape
                }
            }
        }
    }
    val onMarkCoverFailed: (String) -> Unit = remember(state) {
        { fileId ->
            if (state.failedCovers.size < COVER_METADATA_LIMIT) state.failedCovers.add(fileId)
        }
    }
    fun recordDecodedRatio(file: FileStat, ratio: Float) = onRecordAspectRatio(file, ratio)
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
    val currentDescriptors by rememberUpdatedState(descriptors)
    val galleryPrefs = LocalDriveAppearancePrefs.current.gallery
    val density = LocalDensity.current
    val gapPx = with(density) { 8.dp.toPx().toDouble() }
    val insetPx = with(density) { 12.dp.toPx().toDouble() }
    val maxTileWidthPx = with(density) { maximumWidth.toPx().toDouble() }
    val extraFooterDp = when {
        galleryPrefs.showTitle && galleryPrefs.showDetailLine -> 48.dp
        galleryPrefs.showTitle -> 30.dp
        galleryPrefs.showDetailLine -> 24.dp
        else -> 0.dp
    }
    val extraFooterPx = with(density) {
        (extraFooterDp * density.fontScale.coerceAtLeast(1f)).toPx().toDouble()
    }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    // 以绝对列坐标与稳定像素宽高构建当前视口槽位表：同批卡片内滚动时结构相等，0 重组、0 重测，仅 placement 阶段偏移
    val visibleSlots by remember(state, columns, maxTileWidthPx, gapPx, insetPx, extraFooterPx) {
        derivedStateOf {
            state.revision
            val width = viewportSize.width
            val height = viewportSize.height
            if (width <= 0 || height <= 0) {
                emptyList()
            } else {
                val availableWidth = (width - insetPx * 2).coerceAtLeast(1.0)
                val count = max(columns, ceil((availableWidth + gapPx) / (maxTileWidthPx + gapPx)).toInt())
                val placements = state.layout.frame(
                    currentDescriptors,
                    count,
                    availableWidth,
                    height.toDouble(),
                    gapPx,
                    extraTileHeight = extraFooterPx,
                )
                val scrollOffset = state.layout.scrollOffset
                placements.map { placement ->
                    GallerySlotItem(
                        key = placement.key,
                        fileId = placement.fileId,
                        leftPx = (placement.left + insetPx).roundToInt(),
                        absoluteTopPx = (placement.top + scrollOffset).roundToInt(),
                        widthPx = placement.width.roundToInt().coerceAtLeast(1),
                        heightPx = placement.height.roundToInt().coerceAtLeast(1),
                    )
                }
            }
        }
    }
    val currentVisibleSlots by rememberUpdatedState(visibleSlots)
    val onVisible by rememberUpdatedState(onVisibleFilesChanged)
    LaunchedEffect(state) {
        snapshotFlow {
            state.revision
            val height = viewportSize.height
            val scrollOffset = state.layout.scrollOffset
            val ids = HashSet<String>()
            val slots = currentVisibleSlots
            for (i in slots.indices) {
                val slot = slots[i]
                val top = slot.absoluteTopPx - scrollOffset
                if (top < height && top + slot.heightPx > 0) {
                    slot.fileId?.let(ids::add)
                }
            }
            ids as Set<String>
        }.distinctUntilChanged().collect { ids ->
            state.visibleFileIds = ids
            onVisible(ids)
        }
    }
    // 封面预取：使用单一持久协程 + snapshotFlow 防抖，仅取消离开预取窗口的任务，
    // 避免匀速滚动时把即将完成的磁盘预热请求反复打断重来；O(1) 反查锚点索引。
    val thumbnailClarity = LocalDriveAppearancePrefs.current.thumbnailClarity
    val prefetchConcurrency = workBudget.coverPrefetchConcurrency.coerceAtMost(COVER_PREFETCH_CONCURRENCY)
    LaunchedEffect(coverFiles, memoryPolicy, prefetchConcurrency, thumbnailClarity) {
        if (memoryPolicy != MemoryPolicy.Normal || prefetchConcurrency <= 0) return@LaunchedEffect
        val prefetchScope = this
        val prefetchLimit = Semaphore(prefetchConcurrency)
        val activeJobs = HashMap<String, Job>()
        val loader = SingletonImageLoader.get(platformContext)
        snapshotFlow { state.visibleFileIds }.collectLatest { visible ->
            if (visible.isNotEmpty()) delay(COVER_PREFETCH_DEBOUNCE_MS)
            val anchorIndex = if (visible.isEmpty()) 0 else visible.maxOfOrNull { indexById[it] ?: -1 }?.coerceAtLeast(0) ?: 0
            val window = galleryPrefetchWindow(coverFiles.size, anchorIndex).map { coverFiles[it] }
            val windowIds = window.mapTo(HashSet()) { it.id }
            prefetched.retainAll(windowIds)
            failedCovers.retainAll(windowIds)
            val staleIterator = activeJobs.entries.iterator()
            while (staleIterator.hasNext()) {
                val (id, job) = staleIterator.next()
                val keep = id in windowIds && id !in visible
                if (!keep || !job.isActive) {
                    if (!keep) job.cancel()
                    staleIterator.remove()
                }
            }
            val targets = window.distinctBy { it.id }
                .filter { it.id !in visible && it.galleryCoverUrl().isNotEmpty() && it.id !in prefetched && it.id !in failedCovers && it.id !in activeJobs }
            if (targets.isEmpty()) return@collectLatest
            targets.forEach { file ->
                val rawUrl = file.galleryCoverUrl()
                val stableId = file.hash.takeIf { it.isNotBlank() } ?: file.id.takeUnless { file.isFolder }?.takeIf { it.isNotBlank() }
                val clarityUrl = resolveThumbnailModelForClarity(rawUrl, thumbnailClarity)
                val coverModel = platform.resolveCoilModel(clarityUrl)
                val diskKey = thumbnailDiskCacheKey(rawUrl, thumbnailClarity, stableId = stableId)
                val memoryKey = thumbnailMemoryCacheKey(rawUrl, thumbnailClarity, stableId = stableId)
                val shapeKnown = latestAspectRatio(file) != null || file.id in learnedShapes
                lateinit var job: Job
                job = prefetchScope.launch {
                    try {
                        prefetchLimit.withPermit {
                            @OptIn(coil3.annotation.ExperimentalCoilApi::class)
                            val request = ImageRequest.Builder(platformContext)
                                .data(coverModel)
                                .memoryCacheKey(memoryKey)
                                .apply {
                                    if (diskKey != null) diskCacheKey(diskKey)
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
                                if (failedCovers.size < COVER_METADATA_LIMIT) failedCovers.add(file.id)
                            }
                        }
                    } finally {
                        if (activeJobs[file.id] === job) activeJobs.remove(file.id)
                    }
                }
                activeJobs[file.id] = job
            }
        }
    }
    val scroll = rememberScrollableState(state::scrollBy)
    var menuTarget by remember { mutableStateOf<Pair<String, IntOffset>?>(null) }
    val onOpenContextMenu: (String, IntOffset) -> Unit = remember {
        { fileId, offset -> menuTarget = fileId to offset }
    }
    Box(modifier.fillMaxSize()) {
        // 真正的虚拟化 Layout（无 SubcomposeLayout）：消除滚动期间逐像素 subcompose 与桌面弹层销毁崩溃隐患
        Layout(
            content = {
                visibleSlots.forEach { slot ->
                    key(slot.key) {
                        val file = slot.fileId?.let(byId::get)
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
                                file = file,
                                isSelectionMode = isSelectionMode,
                                isSelected = file.id in selectedIds,
                                isHighlighted = file.id in highlightedIds,
                                isBlurred = isBlurred(file),
                                callbacks = callbacks,
                                locationLabel = locationLabel(file),
                                onAspectRatio = onRecordAspectRatio,
                                onCoverFailed = onMarkCoverFailed,
                                onContextMenu = onOpenContextMenu,
                            )
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { if (viewportSize != it) viewportSize = it }
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
        ) { measurables, constraints ->
            val slots = visibleSlots
            val placeables = measurables.mapIndexed { index, measurable ->
                val slot = slots.getOrNull(index)
                if (slot != null) {
                    measurable.measure(Constraints.fixed(slot.widthPx, slot.heightPx))
                } else {
                    measurable.measure(Constraints.fixed(0, 0))
                }
            }
            layout(constraints.maxWidth, constraints.maxHeight) {
                state.revision
                val scrollOffset = state.layout.scrollOffset
                for (i in placeables.indices) {
                    val slot = slots.getOrNull(i) ?: continue
                    placeables[i].placeRelative(slot.leftPx, (slot.absoluteTopPx - scrollOffset).roundToInt())
                }
            }
        }
        menuTarget?.let { (targetId, pointer) ->
            val target = byId[targetId]
            if (target == null) {
                LaunchedEffect(targetId) { menuTarget = null }
            } else {
                WindowAnchoredActionMenu(pointer, callbacks.contextActions(target), onDismiss = { menuTarget = null })
            }
        }
    }
}

private val GalleryCoverScrimBrush = Brush.verticalGradient(
    listOf(Color.Transparent, Color.Black.copy(alpha = 0.28f), Color.Black.copy(alpha = 0.72f)),
)

private fun galleryHeightResolutionLabel(file: FileStat): String? {
    val height = file.params["height"]?.toIntOrNull() ?: return null
    return when {
        height >= 2160 -> "4K"
        height >= 1440 -> "2K"
        height >= 1080 -> "1080P"
        height >= 720 -> "720P"
        height >= 480 -> "480P"
        else -> null
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
    onAspectRatio: (FileStat, Float) -> Unit,
    onCoverFailed: (String) -> Unit,
    onContextMenu: (String, IntOffset) -> Unit,
) {
    val driveRepo = LocalPikoServices.current.driveRepository
    val folderStats = if (file.isFolder && isDriveFolderId(file.id)) {
        driveRepo.folderSubtreeStats(file.id)
    } else null
    val folderItemCount = folderStats?.fileCount?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
    val folderDurationMillis = folderStats?.durationMillis?.takeIf { it > 0L }
    val folderSizeBytes = folderStats?.bytes?.takeIf { it > 0L }

    val focus = remember { FocusRequester() }
    val ownClicks = remember { OwnClicks() }
    val isMac = LocalPikoPlatform.current.shortcutModifier == ShortcutModifier.Command
    // 普通引用数组保存布局坐标，避免滚动期间每帧每格写入 Compose Snapshot State
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val currentOnContextMenu by rememberUpdatedState(onContextMenu)
    val aspectRatioCallback: (Float) -> Unit = remember(file, onAspectRatio) {
        { ratio -> onAspectRatio(file, ratio) }
    }
    CompositionLocalProvider(LocalOwnClicks provides ownClicks) {
        GalleryCanvasTile(
            file = file,
            isSelectionMode = isSelectionMode,
            isSelected = isSelected,
            isHighlighted = isHighlighted,
            isBlurred = isBlurred,
            locationLabel = locationLabel,
            folderItemCount = folderItemCount,
            folderDurationMillis = folderDurationMillis,
            folderSizeBytes = folderSizeBytes,
            onClick = { callbacks.onOpen(file) },
            onLongClick = { callbacks.onLongClick(file) },
            onSelectToggle = { callbacks.onSelect(file, it) },
            onStartSelection = callbacks.onStartSelection?.let { start -> { start(file) } },
            onAspectRatio = aspectRatioCallback,
            onCoverFailed = onCoverFailed,
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focus)
                .onFocusChanged { callbacks.onFocusChanged(file, it.hasFocus) }
                .onPlaced { coordinates[0] = it; callbacks.onPlaced(file, it) }
                .selectionClicks(
                    onToggle = { callbacks.onToggleSelect(file.id) },
                    onExtend = { callbacks.onExtendSelect(file.id) },
                    onDoubleClick = if (isSelectionMode) null else ({ callbacks.onOpen(file) }),
                    onFocus = { runCatching { focus.requestFocus() } },
                    ownClicks = ownClicks,
                    onMiddleClick = { callbacks.onMiddleClick(file) },
                )
                .onSecondaryPress(enabled = true) { position ->
                    coordinates[0]?.takeIf { it.isAttached }?.let { coords ->
                        currentOnContextMenu(file.id, coords.localToWindow(position).round())
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
                }
                .semantics(mergeDescendants = true) {},
        )
    }
}

/**
 * 瀑布流单格真实组件：供 [GalleryCanvas] 与外观自定义页实时预览共用，确保四角徽标、底栏标题、
 * 详情行与边框遮罩在真实网盘与自定义预览中像素级一致。
 */
@Composable
internal fun GalleryCanvasTile(
    file: FileStat,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    isHighlighted: Boolean = false,
    isBlurred: Boolean = false,
    locationLabel: String? = null,
    folderItemCount: Int? = null,
    folderDurationMillis: Long? = null,
    folderSizeBytes: Long? = null,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onSelectToggle: (Boolean) -> Unit = {},
    onStartSelection: (() -> Unit)? = null,
    onAspectRatio: ((Float) -> Unit)? = null,
    onCoverFailed: ((String) -> Unit)? = null,
    /** 为 null 时封面占满剩余高度（用于虚拟化画布固定槽高）；指定比例时按比例自适应高度（用于设置页独立预览）。 */
    coverAspectRatio: Float? = null,
    modifier: Modifier = Modifier,
    coverOverlay: (@Composable BoxScope.() -> Unit)? = null,
) {
    val appearancePrefs = LocalDriveAppearancePrefs.current
    val galleryPrefs = appearancePrefs.gallery
    val folderStyle = appearancePrefs.grid.folderStyle
    val corners = galleryPrefs.corners
    val hover = fileItemHover()
    val shape = MaterialTheme.shapes.medium
    val colors = MaterialTheme.colorScheme
    val coverUrl = remember(file.id, file.thumbnailLink, file.hash) { file.galleryCoverUrl() }
    var coverFailed by remember(coverUrl) { mutableStateOf(false) }

    val hasResolutionCorner = corners.contains(CardBadgeKind.RESOLUTION)
    val hasStatusCorner = corners.contains(CardBadgeKind.STATUS_BADGES)
    val hasTagsCorner = corners.contains(CardBadgeKind.PARSED_TAGS)
    val needsParsed = hasTagsCorner || hasResolutionCorner ||
        (galleryPrefs.showDetailLine && DetailField.PARSED_TAGS in galleryPrefs.detailFields)
    val parsed = remember(file.id, file.name, needsParsed) {
        if (needsParsed && !file.isFolder) parseMediaName(file.name) else null
    }
    val resolutionLabel = remember(parsed, file.params) {
        parsed?.tags?.firstOrNull { it.kind == TagKind.RESOLUTION }?.text
            ?: galleryHeightResolutionLabel(file)
    }
    val allTags = remember(parsed) { parsed?.tags?.map { it.text }.orEmpty() }
    val cornerTags = remember(allTags, resolutionLabel, hasResolutionCorner) {
        allTags.filter { !hasResolutionCorner || it != resolutionLabel }.take(2)
    }
    val code = parsed?.av?.code
    val durationLabel = remember(file, folderDurationMillis) {
        val ms = if (file.isFolder) folderDurationMillis?.takeIf { it > 0L } else file.fileDurationMillis()
        ms?.let { formatDurationMillis(it) }
    }
    val sizeLabel = remember(file, folderSizeBytes) {
        val bytes = if (file.isFolder) folderSizeBytes?.takeIf { it > 0L } else file.sizeBytes.takeIf { it >= 0L }
        bytes?.toReadableSize()
    }
    val marks = remember(file) { file.fileItemMarks(folderHasVault = false) }
    val extensionText = remember(file) { if (file.isFolder) null else file.extensionLabel() }
    val badgeData = PosterCornerData(
        duration = durationLabel,
        resolution = resolutionLabel?.takeIf { it.isNotBlank() },
        tags = cornerTags,
        code = code,
        fileSize = sizeLabel,
        marks = marks,
        extension = extensionText,
    )
    val showSelectionOverlay = isSelectionMode || (hover.mouseHovered && onStartSelection != null)
    val hasTopStart = showSelectionOverlay || badgeData.hasContent(corners.topStart)
    val hasTopEnd = badgeData.hasContent(corners.topEnd)
    val hasBottomStart = badgeData.hasContent(corners.bottomStart)
    val hasBottomEnd = badgeData.hasContent(corners.bottomEnd)
    val showsMediaCover = (!file.isFolder && coverUrl.isNotEmpty() && !coverFailed) ||
        (file.isFolder && folderStyle == FolderStyle.MINIMAL_ICON && coverUrl.isNotEmpty() && !coverFailed)

    val detailParts = remember(
        file, galleryPrefs.showDetailLine, galleryPrefs.detailFields, hasTagsCorner, allTags,
        folderItemCount, folderDurationMillis, folderSizeBytes, PikoLocale.effectiveLanguage,
    ) {
        if (galleryPrefs.showDetailLine) {
            file.detailLineParts(
                fields = galleryPrefs.detailFields,
                tags = allTags,
                folderItemCount = folderItemCount,
                folderDurationMillis = folderDurationMillis,
                folderSizeBytes = folderSizeBytes,
                folderHasVault = false,
                includeParsedTags = !hasTagsCorner,
            )
        } else {
            emptyList()
        }
    }
    val showFooter = galleryPrefs.showTitle || galleryPrefs.showDetailLine

    Column(
        modifier = modifier
            .then(hover.modifier)
            .focusIndication(shape, showPointerFocus = !isSelectionMode)
            .clip(shape)
            .background(
                when {
                    isSelected -> colors.secondaryContainer.copy(alpha = 0.42f)
                    hover.mouseHovered && showFooter -> colors.surfaceContainerLow
                    else -> Color.Transparent
                },
            )
            .cardInteraction(isSelectionMode, isSelected, onClick, onLongClick, onSelectToggle),
    ) {
        val coverModifier = if (coverAspectRatio != null) {
            Modifier.fillMaxWidth().aspectRatio(coverAspectRatio)
        } else {
            Modifier.fillMaxWidth().weight(1f)
        }
        Box(
            coverModifier
                .clip(shape)
                .border(
                    if (isSelected) 3.dp else 1.dp,
                    if (isSelected) colors.primary else colors.outlineVariant.copy(alpha = 0.25f),
                    shape,
                )
                .locateHighlight(isHighlighted, shape),
        ) {
            if (file.isFolder) {
                FileArtwork(
                    file = file.fileVisual(),
                    format = FileArtworkFormat.Poster,
                    isSpoilerBlurred = isBlurred,
                    modifier = Modifier.fillMaxSize(),
                    folderStyle = folderStyle,
                )
            } else if (coverUrl.isEmpty() || coverFailed) {
                val (container, content) = file.placeholderColors()
                Box(Modifier.fillMaxSize().background(container), contentAlignment = Alignment.Center) {
                    Icon(
                        file.watermarkIcon(),
                        contentDescription = null,
                        tint = content.copy(alpha = 0.6f),
                        modifier = Modifier.size(40.dp),
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
                        onCoverFailed?.invoke(file.id)
                    },
                    stableId = file.hash.takeIf { it.isNotBlank() } ?: file.id.takeUnless { file.isFolder }?.takeIf { it.isNotBlank() },
                )
            }
            if ((hasBottomStart || hasBottomEnd) && showsMediaCover) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(GalleryCoverScrimBrush),
                )
            }
            if (hasTopStart || hasTopEnd) {
                val topEndFlex = corners.topEnd == CardBadgeKind.PARSED_TAGS && !showSelectionOverlay && corners.topStart != CardBadgeKind.PARSED_TAGS
                Row(
                    modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        modifier = if (topEndFlex) Modifier else Modifier.weight(1f),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        if (showSelectionOverlay) {
                            FileOverlaySelectionControl(
                                FileItemSelection(
                                    if (isSelected) ToggleableState.On else ToggleableState.Off,
                                    { if (isSelectionMode) onSelectToggle(!isSelected) else onStartSelection?.invoke() },
                                    label = tr("选择 ${file.name}", "Select ${file.name}", "${file.name} を選択", "${file.name} 선택", "選取 ${file.name}"),
                                ),
                                modifier = if (!isSelectionMode) Modifier.ownsClicks() else Modifier,
                                interactive = !isSelectionMode,
                            )
                        } else if (badgeData.hasContent(corners.topStart)) {
                            PosterCornerBadge(corners.topStart, badgeData)
                        }
                    }
                    if (hasTopEnd) {
                        Box(
                            modifier = if (topEndFlex) Modifier.weight(1f) else Modifier,
                            contentAlignment = Alignment.TopEnd,
                        ) {
                            PosterCornerBadge(corners.topEnd, badgeData)
                        }
                    }
                }
            }
            if (hasBottomStart || hasBottomEnd) {
                val bottomEndFlex = corners.bottomEnd == CardBadgeKind.PARSED_TAGS && corners.bottomStart != CardBadgeKind.PARSED_TAGS
                Row(
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = if (bottomEndFlex) Modifier else Modifier.weight(1f),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        if (hasBottomStart) {
                            PosterCornerBadge(corners.bottomStart, badgeData)
                        }
                    }
                    if (hasBottomEnd) {
                        Box(
                            modifier = if (bottomEndFlex) Modifier.weight(1f) else Modifier,
                            contentAlignment = Alignment.BottomEnd,
                        ) {
                            PosterCornerBadge(corners.bottomEnd, badgeData)
                        }
                    }
                }
            }
            if (coverOverlay != null) {
                coverOverlay()
            }
        }
        if (showFooter) {
            val showMarksBelowTitle = marks.isNotEmpty() && !hasStatusCorner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                if (showMarksBelowTitle) {
                    Box(Modifier.padding(top = 2.dp, end = 4.dp)) {
                        FileItemMarks(marks)
                    }
                }
                Column(Modifier.weight(1f)) {
                    if (galleryPrefs.showTitle) {
                        Text(
                            text = file.displayTitle(),
                            color = colors.onSurface,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (galleryPrefs.showDetailLine) {
                        val parts = if (detailParts.isNotEmpty()) detailParts else listOfNotNull(locationLabel?.takeIf { it.isNotBlank() })
                        if (parts.isNotEmpty()) {
                            MetaRow(
                                parts = parts,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                                modifier = if (galleryPrefs.showTitle) Modifier.padding(top = 2.dp) else Modifier,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Inclusive, bounded by the actual dataset even when an old scroll anchor outlives it. */
internal fun galleryPrefetchWindow(count: Int, anchor: Int): IntRange {
    if (count <= 0) return IntRange.EMPTY
    val center = anchor.coerceIn(0, count - 1)
    return (center - COVER_PREFETCH_BEHIND).coerceAtLeast(0)..
        (center.toLong() + COVER_PREFETCH_AHEAD).coerceAtMost(count.toLong() - 1).toInt()
}

private const val COVER_METADATA_LIMIT = 2048
private const val COVER_PREFETCH_AHEAD = 64
private const val COVER_PREFETCH_BEHIND = 12
private const val COVER_PREFETCH_CONCURRENCY = 8
private const val COVER_PREFETCH_DEBOUNCE_MS = 100L
private const val COVER_PROBE_DECODE_PX = 240
