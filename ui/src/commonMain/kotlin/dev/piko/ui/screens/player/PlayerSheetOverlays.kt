package dev.piko.ui.screens.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.piko.shared.media.player.MediaTrack
import dev.piko.shared.media.player.PlaybackStatsSection
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.shared.media.player.PlaylistEntry
import dev.piko.shared.media.player.VideoCropRegion
import dev.piko.ui.components.SheetAction
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.common_cancel
import dev.piko.ui.resources.text_drag_the_corners_or_the_box_to_select_the_area_that_fills_the_screen
import dev.piko.ui.resources.text_fill_screen
import dev.piko.ui.resources.text_reset_fit
import io.github.nihildigit.pikpak.FileStat

/**
 * 播放器侧边/底部设置与选集面板宿主：独立顶层无状态组件，播放期间 positionMillis 每帧跳动时若未打开面板可直接跳过。
 */
@Composable
internal fun PlayerSheetsOverlay(
    openSheet: PlayerSheet?,
    isLandscape: Boolean,
    title: String,
    playlist: List<PlaylistEntry>,
    currentFileId: String,
    hideEpisodeThumbnails: Boolean,
    fileActions: List<SheetAction>,
    playbackSpeed: Float?,
    qualityOptions: List<String>,
    currentQuality: String?,
    aspectRatio: PlayerAspectRatio?,
    seekStepSeconds: Int,
    longPressSpeed: Float,
    audioTracks: List<MediaTrack>,
    selectedAudioTrackId: String?,
    subtitleTracks: List<MediaTrack>,
    selectedSubtitleTrackId: String?,
    onDismiss: () -> Unit,
    onOpenSheet: (PlayerSheet) -> Unit,
    onSelectEntry: (PlaylistEntry) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onQualityChange: (String) -> Unit,
    onAspectRatioChange: (PlayerAspectRatio) -> Unit,
    onBeginCropEdit: () -> Unit,
    onSeekStepChange: ((Int) -> Unit)?,
    onLongPressSpeedChange: ((Float) -> Unit)?,
    onSelectAudioTrack: (MediaTrack) -> Unit,
    onSelectSubtitleTrack: (MediaTrack?) -> Unit,
    onPickLocalSubtitle: (() -> Unit)?,
    onPickDriveSubtitle: ((FileStat) -> Unit)?,
    playbackStats: (() -> List<PlaybackStatsSection>)?,
) {
    PlayerSheetHost(
        sheet = openSheet,
        isLandscape = isLandscape,
        onDismiss = onDismiss,
    ) { sheet ->
        when (sheet) {
            PlayerSheet.Episodes -> EpisodePanel(
                entries = playlist,
                currentFileId = currentFileId,
                hideThumbnails = hideEpisodeThumbnails,
                onSelect = {
                    onDismiss()
                    onSelectEntry(it)
                },
            )
            PlayerSheet.Settings -> PlayerSettingsPanel(
                title = title,
                actions = emptyList(),
                fileActions = fileActions.map { action ->
                    SheetAction(
                        action.icon,
                        action.label,
                        {
                            onDismiss()
                            action.onClick()
                        },
                        shortLabel = action.shortLabel,
                    )
                },
                playbackSpeed = playbackSpeed,
                onSpeedChange = onSpeedChange,
                qualityOptions = qualityOptions,
                currentQuality = currentQuality,
                onQualityChange = {
                    onDismiss()
                    onQualityChange(it)
                },
                aspectRatio = aspectRatio,
                onAspectRatioChange = { mode ->
                    if (mode == PlayerAspectRatio.RegionCrop) onBeginCropEdit()
                    else onAspectRatioChange(mode)
                },
                seekStepSeconds = seekStepSeconds,
                onSeekStepChange = onSeekStepChange,
                longPressSpeed = longPressSpeed,
                onLongPressSpeedChange = onLongPressSpeedChange,
            )
            PlayerSheet.Tracks -> TracksPanel(
                audioTracks = audioTracks,
                selectedAudioTrackId = selectedAudioTrackId,
                onSelectAudio = onSelectAudioTrack,
                subtitleTracks = subtitleTracks,
                selectedSubtitleTrackId = selectedSubtitleTrackId,
                onSelectSubtitle = onSelectSubtitleTrack,
                onPickLocalSubtitle = onPickLocalSubtitle,
                onPickDriveSubtitle = onPickDriveSubtitle?.let { { onOpenSheet(PlayerSheet.DriveSubtitles) } },
            )
            PlayerSheet.DriveSubtitles -> DriveSubtitlePanel(
                videoFileId = currentFileId,
                onPick = { file ->
                    onPickDriveSubtitle?.invoke(file)
                    onOpenSheet(PlayerSheet.Tracks)
                },
                modifier = Modifier.weight(1f),
            )
            PlayerSheet.Stats -> playbackStats?.let { PlaybackStatsPanel(read = it) }
        }
    }
}

private enum class CropDragHandle {
    TopLeft, TopRight, BottomLeft, BottomRight, Inside
}

/**
 * 画面框选填充编辑器：在适应屏幕的完整画面上叠加可拖动四角或内部的矩形选框，确定后将该区域放大铺满窗口。
 */
@Composable
internal fun CropRegionEditorOverlay(
    initialRegion: VideoCropRegion,
    videoAspect: Float?,
    onCancel: () -> Unit,
    onReset: () -> Unit,
    onConfirm: (VideoCropRegion) -> Unit,
) {
    var region by remember(initialRegion) { mutableStateOf(initialRegion) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var isDraggingBox by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val hitRadiusPx = with(density) { 44.dp.toPx() }
    val cornerRadiusPx = with(density) { 6.dp.toPx() }
    val strokePx = with(density) { 2.5.dp.toPx() }

    fun videoBounds(size: IntSize): Rect {
        if (size.width <= 0 || size.height <= 0) return Rect.Zero
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        val aspect = videoAspect?.takeIf { it > 0f } ?: (w / h)
        val containerAspect = w / h
        return if (containerAspect > aspect) {
            val vw = h * aspect
            val left = (w - vw) * 0.5f
            Rect(left, 0f, left + vw, h)
        } else {
            val vh = w / aspect
            val top = (h - vh) * 0.5f
            Rect(0f, top, w, top + vh)
        }
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(videoAspect, initialRegion) {
                var activeHandle: CropDragHandle? = null
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val bounds = videoBounds(size)
                    if (bounds.width <= 0f || bounds.height <= 0f) return@awaitEachGesture
                    val leftPx = bounds.left + region.left * bounds.width
                    val topPx = bounds.top + region.top * bounds.height
                    val rightPx = bounds.left + region.right * bounds.width
                    val bottomPx = bounds.top + region.bottom * bounds.height
                    val offset = down.position
                    fun distSq(x: Float, y: Float) = (offset.x - x) * (offset.x - x) + (offset.y - y) * (offset.y - y)
                    val rSq = hitRadiusPx * hitRadiusPx
                    val corners = listOf(
                        CropDragHandle.TopLeft to distSq(leftPx, topPx),
                        CropDragHandle.TopRight to distSq(rightPx, topPx),
                        CropDragHandle.BottomLeft to distSq(leftPx, bottomPx),
                        CropDragHandle.BottomRight to distSq(rightPx, bottomPx),
                    )
                    val closestCorner = corners.minByOrNull { it.second }?.takeIf { it.second <= rSq }?.first
                    activeHandle = closestCorner ?: if (offset.x in (leftPx - hitRadiusPx)..(rightPx + hitRadiusPx) &&
                        offset.y in (topPx - hitRadiusPx)..(bottomPx + hitRadiusPx)
                    ) CropDragHandle.Inside else null
                    if (activeHandle == null) return@awaitEachGesture
                    down.consume()
                    var prevPos = down.position
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            val delta = change.position - prevPos
                            prevPos = change.position
                            if (delta != Offset.Zero) {
                                isDraggingBox = true
                                change.consume()
                                val currentBounds = videoBounds(size)
                                if (currentBounds.width > 0f && currentBounds.height > 0f) {
                                    val dx = delta.x / currentBounds.width
                                    val dy = delta.y / currentBounds.height
                                    val minSpan = 0.12f
                                    region = when (requireNotNull(activeHandle)) {
                                        CropDragHandle.TopLeft -> region.copy(
                                            left = (region.left + dx).coerceIn(0f, (region.right - minSpan).coerceAtLeast(0f)),
                                            top = (region.top + dy).coerceIn(0f, (region.bottom - minSpan).coerceAtLeast(0f)),
                                        )
                                        CropDragHandle.TopRight -> region.copy(
                                            right = (region.right + dx).coerceIn((region.left + minSpan).coerceAtMost(1f), 1f),
                                            top = (region.top + dy).coerceIn(0f, (region.bottom - minSpan).coerceAtLeast(0f)),
                                        )
                                        CropDragHandle.BottomLeft -> region.copy(
                                            left = (region.left + dx).coerceIn(0f, (region.right - minSpan).coerceAtLeast(0f)),
                                            bottom = (region.bottom + dy).coerceIn((region.top + minSpan).coerceAtMost(1f), 1f),
                                        )
                                        CropDragHandle.BottomRight -> region.copy(
                                            right = (region.right + dx).coerceIn((region.left + minSpan).coerceAtMost(1f), 1f),
                                            bottom = (region.bottom + dy).coerceIn((region.top + minSpan).coerceAtMost(1f), 1f),
                                        )
                                        CropDragHandle.Inside -> {
                                            val w = region.width
                                            val h = region.height
                                            val newLeft = (region.left + dx).coerceIn(0f, (1f - w).coerceAtLeast(0f))
                                            val newTop = (region.top + dy).coerceIn(0f, (1f - h).coerceAtLeast(0f))
                                            region.copy(left = newLeft, top = newTop, right = newLeft + w, bottom = newTop + h)
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        activeHandle = null
                        isDraggingBox = false
                    }
                }
            },
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            val bounds = videoBounds(IntSize(size.width.toInt(), size.height.toInt()))
            drawRect(Color.Black.copy(alpha = 0.58f))
            if (bounds.width > 0f && bounds.height > 0f) {
                val leftPx = bounds.left + region.left * bounds.width
                val topPx = bounds.top + region.top * bounds.height
                val wPx = (region.right - region.left) * bounds.width
                val hPx = (region.bottom - region.top) * bounds.height
                drawRoundRect(
                    color = Color.Transparent,
                    topLeft = Offset(leftPx, topPx),
                    size = Size(wPx, hPx),
                    cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                    blendMode = BlendMode.Clear,
                )
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(leftPx, topPx),
                    size = Size(wPx, hPx),
                    cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                    style = Stroke(width = strokePx),
                )
                val handleRadius = strokePx * 3.2f
                listOf(
                    Offset(leftPx, topPx),
                    Offset(leftPx + wPx, topPx),
                    Offset(leftPx, topPx + hPx),
                    Offset(leftPx + wPx, topPx + hPx),
                ).forEach { corner ->
                    drawCircle(color = primaryColor, radius = handleRadius, center = corner)
                    drawCircle(color = Color.White, radius = handleRadius, center = corner, style = Stroke(width = strokePx * 0.8f))
                }
            }
        }

        if (!isDraggingBox) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .padding(horizontal = 16.dp, vertical = 20.dp),
            ) {
                Text(
                    text = stringResource(Res.string.text_drag_the_corners_or_the_box_to_select_the_area_that_fills_the_screen),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }

            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 28.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(onClick = onReset) { Text(stringResource(Res.string.text_reset_fit)) }
                    FilledTonalButton(onClick = onCancel) { Text(stringResource(Res.string.common_cancel)) }
                    Button(
                        onClick = {
                            val containerAspect = if (containerSize.width > 0 && containerSize.height > 0) {
                                containerSize.width.toFloat() / containerSize.height.toFloat()
                            } else 0f
                            onConfirm(region.copy(containerAspect = containerAspect))
                        },
                    ) { Text(stringResource(Res.string.text_fill_screen)) }
                }
            }
        }
    }
}
