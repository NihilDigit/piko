package dev.piko.desktop

import java.awt.Frame
import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxFullscreenTest {
    @Test
    fun transitionGraceCoversWindowImmediatelyAfterRequest() {
        val start = 1_000_000_000L
        assertFalse(LinuxFullscreen.isWithinTransitionGrace(0L, start))
        assertTrue(LinuxFullscreen.isWithinTransitionGrace(start, start))
        assertTrue(LinuxFullscreen.isWithinTransitionGrace(start, start + LinuxFullscreen.TRANSITION_GRACE_NANOS - 1L))
        assertFalse(LinuxFullscreen.isWithinTransitionGrace(start, start + LinuxFullscreen.TRANSITION_GRACE_NANOS))
    }

    @Test
    fun nonLinuxOrUndisplayableWindowSafelyDeclinesEwmhEnter() {
        if (GraphicsEnvironment.isHeadless()) return
        val frame = Frame()
        try {
            val fullscreen = LinuxFullscreen(frame)
            assertFalse(fullscreen.isFullscreen)
            assertFalse(fullscreen.enter())
            assertFalse(fullscreen.isFullscreen)
            assertTrue(fullscreen.exit())
        } finally {
            frame.dispose()
        }
    }
}
