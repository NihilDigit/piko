package dev.piko.ui.screens.drive

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
import androidx.compose.material.icons.outlined.Folder
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
import dev.piko.ui.components.toReadableSize
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
        val gap = 8.dp.toPx().toDouble()
        val inset = 12.dp.toPx().toDouble()
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        if (width == 0 || height == 0) return@SubcomposeLayout layout(width, height) {}
        val availableWidth = (width - inset * 2).coerceAtLeast(1.0)
        val count = max(columns, ceil((availableWidth + gap) / (maximumWidth.toPx() + gap)).toInt())
        val placements = state.layout.frame(descriptors, count, availableWidth, height.toDouble(), gap)
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
) {
    val focus = remember { FocusRequester() }
    val shape = MaterialTheme.shapes.medium
    val duration = file.params["duration"]?.toLongOrNull()?.takeIf { it > 0 }
    val badgeLabel = duration?.let { seconds ->
        val minutes = (seconds % 3600 / 60).toString().padStart(2, '0')
        val remainder = (seconds % 60).toString().padStart(2, '0')
        if (seconds >= 3600) "${seconds / 3600}:$minutes:$remainder" else "$minutes:$remainder"
    } ?: file.sizeBytes.takeIf { it > 0 }?.toReadableSize()
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
            .selectionClicks(
                onToggle = { callbacks.onToggleSelect(file.id) },
                onExtend = { callbacks.onExtendSelect(file.id) },
                onDoubleClick = if (isSelectionMode) null else ({ callbacks.onOpen(file) }),
                onFocus = { runCatching { focus.requestFocus() } },
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
                .cardInteraction(isSelectionMode, isSelected, { callbacks.onOpen(file) }, callbacks.onStartSelection?.let { select -> ({ select(file) }) }, { callbacks.onSelect(file, it) })
                .border(
                    if (isSelected) 3.dp else 1.dp,
                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                    shape,
                )
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
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.28f), Color.Black.copy(alpha = 0.80f))))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(file.name.substringBeforeLast('.', file.name), color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!locationLabel.isNullOrBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Outlined.Folder, contentDescription = null, tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(12.dp))
                        Text(locationLabel, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (badgeLabel != null) {
                Text(
                    badgeLabel,
                    modifier = Modifier.align(if (isSelectionMode || isSelected) Alignment.TopStart else Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.extraSmall)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
            if (isSelectionMode || isSelected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp)
                        .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f), CircleShape)
                        .then(if (isSelected) Modifier else Modifier.border(1.5.dp, Color.White, CircleShape)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
