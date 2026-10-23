package dev.piko.ui.workbench

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import dev.piko.shared.log.PikoLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

private class RecordedPage {
    var drawRevision = 0L
    var recordedRevision = 0L
    var captureRequested = true
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
                    PikoLog.w("Workspace", "工作标签页面快照失败", error)
                    return@LaunchedEffect
                }
            } else if (page.drawRevision != lastObservedDraw && !page.captureRequested) {
                // 页面停止高频绘制后按需请求录制一帧缩略图，避免滚动期间每帧重复录制整棵 DisplayList
                lastObservedDraw = page.drawRevision
                page.captureRequested = true
            }
        }
    }
    Box(Modifier.fillMaxSize().drawWithContent {
        drawContent()
        page.drawRevision++
        if (page.captureRequested && size.width > 0 && size.height > 0) {
            page.captureRequested = false
            val ratio = (512f / maxOf(size.width, size.height)).coerceAtMost(1f)
            val thumbnailSize = IntSize((size.width * ratio).roundToInt().coerceAtLeast(1), (size.height * ratio).roundToInt().coerceAtLeast(1))
            layer.record(size = thumbnailSize) {
                scale(ratio, ratio, pivot = Offset.Zero) { this@drawWithContent.drawContent() }
            }
            page.recordedRevision++
        }
    }) { content() }
}
