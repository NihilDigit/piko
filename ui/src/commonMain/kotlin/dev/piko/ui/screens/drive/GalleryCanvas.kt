package dev.piko.ui.screens.drive

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.piko.ui.adaptive.currentWidthClass
import dev.piko.ui.components.ContextMenuArea
import dev.piko.ui.components.PikoBrandIcons
import dev.piko.ui.components.PosterSpoilerBlur
import dev.piko.ui.components.SpoilerThumbnail
import dev.piko.ui.components.focusIndication
import dev.piko.ui.components.locateHighlight
import dev.piko.ui.components.selectionClicks
import dev.piko.ui.components.zoomOnWheel
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.ShortcutModifier
import io.github.nihildigit.pikpak.FileStat
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

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
    val columns = tileColumns(DriveViewMode.GALLERY, tileSize, widthClass)
    val maximumWidth = tileMaxWidth(DriveViewMode.GALLERY, tileSize)
    val ratios = remember(state) { mutableStateMapOf<String, Float>() }
    val descriptors = files.distinctBy { it.id }.map { file ->
        GalleryLayoutItem(file.id, GalleryShape.nearest((aspectRatio(file) ?: ratios[file.id])?.toDouble()))
    }
    val byId = files.associateBy { it.id }
    val onVisible by rememberUpdatedState(onVisibleFilesChanged)
    LaunchedEffect(state) {
        snapshotFlow { state.visibleFileIds }.collect { onVisible(it) }
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
                    Key.DirectionUp -> 48f
                    Key.DirectionDown -> -48f
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
        val gap = 6.dp.toPx().toDouble()
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        if (width == 0 || height == 0) return@SubcomposeLayout layout(width, height) {}
        val count = max(columns, ceil((width + gap) / (maximumWidth.toPx() + gap)).toInt())
        val placements = state.layout.frame(descriptors, count, width.toDouble(), height.toDouble(), gap)
        state.visibleFileIds = placements.filter { it.top < height && it.top + it.height > 0 }.mapNotNull { it.fileId }.toSet()
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
                        onAspectRatio = { ratio -> if (ratios[file.id] != ratio) ratios[file.id] = ratio },
                    )
                }
            }.single().measure(Constraints.fixed(placement.width.roundToInt().coerceAtLeast(1), placement.height.roundToInt().coerceAtLeast(1)))
            placement to placeable
        }
        layout(width, height) {
            measured.forEach { (placement, placeable) -> placeable.place(placement.left.roundToInt(), placement.top.roundToInt()) }
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
) {
    val focus = remember { FocusRequester() }
    val shape = MaterialTheme.shapes.small
    val isMac = LocalPikoPlatform.current.shortcutModifier == ShortcutModifier.Command
    var coverFailed by remember(file.thumbnailLink) { mutableStateOf(false) }
    ContextMenuArea(
        actions = { callbacks.contextActions(file) },
        onSelect = if (isSelectionMode) null else callbacks.onStartSelection?.let { select -> ({ select(file) }) },
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .onFocusChanged { callbacks.onFocusChanged(file, it.hasFocus) }
            .onPlaced { callbacks.onPlaced(file, it) }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press && event.changes.any { it.type == PointerType.Mouse }) {
                            runCatching { focus.requestFocus() }
                        }
                    }
                }
            }
            .selectionClicks(
                onToggle = { callbacks.onToggleSelect(file.id) },
                onExtend = { callbacks.onExtendSelect(file.id) },
                onDoubleClick = if (isSelectionMode) null else ({ callbacks.onOpen(file) }),
            )
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
                .focusIndication(shape)
                .clip(shape)
                .cardInteraction(isSelectionMode, isSelected, { callbacks.onOpen(file) }, callbacks.onStartSelection?.let { select -> ({ select(file) }) }, { callbacks.onSelect(file, it) })
                .then(if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                .locateHighlight(isHighlighted, shape),
        ) {
            if (file.thumbnailLink.isEmpty() || coverFailed) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
                }
            } else {
                SpoilerThumbnail(file.thumbnailLink, isBlurred, PosterSpoilerBlur, Modifier.fillMaxSize(), onAspectRatio = onAspectRatio, onError = { coverFailed = true })
            }
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(file.name, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!locationLabel.isNullOrBlank()) {
                    Text(locationLabel, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (isSelectionMode) {
                Checkbox(isSelected, onCheckedChange = null, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
            }
        }
    }
}
