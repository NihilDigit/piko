package dev.piko.ui.workbench

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import dev.piko.shared.log.PikoLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

private class RecordedPage { var revision = 0L }
internal val LocalWorkspaceSnapshotEnabled = staticCompositionLocalOf { true }

/** Captures the existing draw pass; this never composes or initializes another work context. */
@Composable
internal fun WorkspacePageSnapshot(model: WorkspaceViewModel, contextId: Long, content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    val page = remember(contextId) { RecordedPage() }
    val enabled = LocalWorkspaceSnapshotEnabled.current
    LaunchedEffect(model, contextId, layer, enabled) {
        if (!enabled) return@LaunchedEffect
        var capturedRevision = 0L
        while (isActive) {
            delay(350)
            if (page.revision == capturedRevision) continue
            val revision = page.revision
            try {
                model.retainPageSnapshot(contextId, layer.toImageBitmap())
                capturedRevision = revision
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                PikoLog.w("Workspace", "工作标签页面快照失败", error)
                return@LaunchedEffect
            }
        }
    }
    Box(Modifier.fillMaxSize().drawWithContent {
        drawContent()
        if (enabled && size.width > 0 && size.height > 0) {
            val ratio = (512f / maxOf(size.width, size.height)).coerceAtMost(1f)
            val thumbnailSize = IntSize((size.width * ratio).roundToInt().coerceAtLeast(1), (size.height * ratio).roundToInt().coerceAtLeast(1))
            layer.record(size = thumbnailSize) {
                scale(ratio, ratio, pivot = Offset.Zero) { this@drawWithContent.drawContent() }
            }
            page.revision++
        }
    }) { content() }
}
