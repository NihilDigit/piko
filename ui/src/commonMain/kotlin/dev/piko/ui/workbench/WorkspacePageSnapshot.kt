package dev.piko.ui.workbench

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntSize
import dev.piko.shared.log.PikoLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt

private class RecordedPage {
    var drawRevision = 0L
    var recordedRevision = 0L
    var captureRequested = true
    var containerTopInWindow: Float = 0f
    var contentTopInWindow: Float? = null
    var lastCropTopPx: Float = 0f

    fun cropTopPx(maxHeight: Float): Float {
        val contentTop = contentTopInWindow ?: return 0f
        return (contentTop - containerTopInWindow).coerceIn(0f, (maxHeight - 1f).coerceAtLeast(0f))
    }
}
internal val LocalWorkspaceSnapshotEnabled = staticCompositionLocalOf { true }

/** Captures the existing draw pass; this never composes or initializes another work context. */
@Composable
internal fun WorkspacePageSnapshot(
    contextId: Long,
    enabled: Boolean = true,
    onSnapshot: (ImageBitmap) -> Unit,
    content: @Composable () -> Unit,
) {
    if (!enabled || !LocalWorkspaceSnapshotEnabled.current) {
        content()
        return
    }
    val layer = rememberGraphicsLayer()
    val page = remember(contextId) { RecordedPage() }
    val retainSnapshot by rememberUpdatedState(onSnapshot)
    LaunchedEffect(contextId, layer) {
        var capturedRevision = 0L
        var lastObservedDraw = 0L
        while (isActive) {
            delay(500)
            if (page.recordedRevision != capturedRevision) {
                val revision = page.recordedRevision
                try {
                    retainSnapshot(layer.toImageBitmap())
                    capturedRevision = revision
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    PikoLog.w("Workspace", "Page thumbnail capture failed for contextId=$contextId; stopping snapshots for this context", error)
                    return@LaunchedEffect
                }
            } else if (page.drawRevision != lastObservedDraw && !page.captureRequested) {
                // 页面停止高频绘制后按需请求录制一帧缩略图，避免滚动期间每帧重复录制整棵 DisplayList
                lastObservedDraw = page.drawRevision
                page.captureRequested = true
            }
        }
    }
    val reportContentTop = remember(page) {
        { top: Float? ->
            val prev = page.contentTopInWindow
            if (prev != top) {
                page.contentTopInWindow = top
                page.captureRequested = true
            }
        }
    }
    CompositionLocalProvider(LocalWorkspaceSnapshotContentTop provides reportContentTop) {
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    val top = coords.positionInWindow().y
                    if (abs(page.containerTopInWindow - top) > 1f) {
                        page.containerTopInWindow = top
                        page.captureRequested = true
                    }
                }
                .drawWithContent {
                    drawContent()
                    page.drawRevision++
                    val cropTopPx = page.cropTopPx(size.height)
                    if (abs(cropTopPx - page.lastCropTopPx) > 1f) {
                        page.captureRequested = true
                    }
                    if (page.captureRequested && size.width > 0 && size.height > 0) {
                        page.captureRequested = false
                        page.lastCropTopPx = cropTopPx
                        val visibleHeight = (size.height - cropTopPx).coerceAtLeast(1f)
                        val ratio = (512f / maxOf(size.width, visibleHeight)).coerceAtMost(1f)
                        val thumbnailSize = IntSize(
                            (size.width * ratio).roundToInt().coerceAtLeast(1),
                            (visibleHeight * ratio).roundToInt().coerceAtLeast(1),
                        )
                        layer.record(size = thumbnailSize) {
                            scale(ratio, ratio, pivot = Offset.Zero) {
                                translate(top = -cropTopPx) {
                                    this@drawWithContent.drawContent()
                                }
                            }
                        }
                        page.recordedRevision++
                    }
                },
        ) { content() }
    }
}
