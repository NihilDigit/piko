package dev.piko.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.piko.desktop.winrt.WindowsCaption
import java.awt.Frame
import java.awt.GraphicsEnvironment
import org.junit.Assume.assumeFalse
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val HTCLIENT = 1
private const val HTCAPTION = 2
private const val HTCLOSE = 20

class CaptionHitTest {
    private fun withTestFrame(block: (WindowsCaption) -> Unit) {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val frame = Frame()
        try {
            block(WindowsCaption(frame))
        } finally {
            frame.dispose()
        }
    }

    @Test
    fun `exclusions override overlapping drag areas while preserving caption buttons`() = withTestFrame { caption ->
        caption.updateLayout(
            dragAreas = listOf(Rect(0f, 0f, 800f, 48f)),
            buttons = mapOf(WindowsCaption.Button.CLOSE to Rect(754f, 0f, 800f, 32f)),
            exclusions = listOf(Rect(8f, 4f, 48f, 44f)),
        )

        // Top-left interactive Back/Action button inside exclusion returns HTCLIENT, never HTCAPTION.
        assertEquals(HTCLIENT, caption.hitTestClientPositionForTest(Offset(24f, 24f)))
        // Empty title bar space outside exclusions still drags the window via HTCAPTION.
        assertEquals(HTCAPTION, caption.hitTestClientPositionForTest(Offset(320f, 24f)))
        // Native caption close button still resolves to HTCLOSE.
        assertEquals(HTCLOSE, caption.hitTestClientPositionForTest(Offset(776f, 16f)))
    }

    @Test
    fun `CompactCaption suspends and restores hit-testing across nested modal blockers`() = withTestFrame { caption ->
        val compact = CompactCaption(caption)
        compact.setHostedForTest(true)
        compact.setDragArea("tabBar", Rect(0f, 0f, 600f, 48f))
        compact.setExclusion("newTab", Rect(240f, 8f, 280f, 40f))

        assertEquals(HTCAPTION, caption.hitTestClientPositionForTest(Offset(120f, 20f)))
        assertEquals(HTCLIENT, caption.hitTestClientPositionForTest(Offset(260f, 20f)))

        // Opening a modal sheet/dialog/fullscreen overlay suspends all underlying HTCAPTION rects without dropping Host.
        compact.setModalBlocked(true)
        compact.setModalBlocked(true)
        assertTrue(compact.hosted)
        assertTrue(compact.isModalBlocked)
        assertEquals(HTCLIENT, caption.hitTestClientPositionForTest(Offset(120f, 20f)))

        // Closing inner modal keeps outer modal blocked.
        compact.setModalBlocked(false)
        assertTrue(compact.isModalBlocked)
        assertEquals(HTCLIENT, caption.hitTestClientPositionForTest(Offset(120f, 20f)))

        // Disposing final modal restores exact drag and exclusion hit rects immediately.
        compact.setModalBlocked(false)
        assertFalse(compact.isModalBlocked)
        assertEquals(HTCAPTION, caption.hitTestClientPositionForTest(Offset(120f, 20f)))
        assertEquals(HTCLIENT, caption.hitTestClientPositionForTest(Offset(260f, 20f)))
    }

    @Test
    fun `CaptionFramelessWindow respects exclusions and modal blockers`() = withTestFrame { caption ->
        val frameless = CaptionFramelessWindow(
            window = null,
            caption = caption,
            initialDragEnabled = true,
            initialAlwaysOnTop = false,
            onClose = {},
        )
        frameless.updateDragArea("header", Rect(0f, 0f, 900f, 56f))
        frameless.updateExclusion("actionCluster", Rect(720f, 8f, 888f, 48f))

        assertEquals(HTCAPTION, caption.hitTestClientPositionForTest(Offset(200f, 24f)))
        assertEquals(HTCLIENT, caption.hitTestClientPositionForTest(Offset(800f, 24f)))

        frameless.setModalBlocked(true)
        assertEquals(HTCLIENT, caption.hitTestClientPositionForTest(Offset(200f, 24f)))

        frameless.setModalBlocked(false)
        assertEquals(HTCAPTION, caption.hitTestClientPositionForTest(Offset(200f, 24f)))
        assertEquals(HTCLIENT, caption.hitTestClientPositionForTest(Offset(800f, 24f)))
    }
}
