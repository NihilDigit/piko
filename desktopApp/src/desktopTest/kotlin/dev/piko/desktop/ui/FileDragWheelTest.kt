package dev.piko.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.piko.desktop.DesktopPikoPlatform
import dev.piko.desktop.DesktopSettingsStore
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.ui.components.FileDragPayload
import dev.piko.ui.components.FileDragState
import dev.piko.ui.components.LocalFileDrag
import dev.piko.ui.components.fileDragHost
import dev.piko.ui.components.fileDragSource
import dev.piko.ui.components.fileDropTarget
import dev.piko.ui.platform.LocalPikoPlatform
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class FileDragWheelTest {
    @Test
    fun `wheel before and during drag preserves the gesture until release`() = runComposeUiTest {
        val platform = DesktopPikoPlatform(DesktopSettingsStore(File.createTempFile("piko-settings", ".properties")))
        val drag = FileDragState()
        val drops = mutableListOf<String>()
        val payload = FileDragPayload(listOf("source"), setOf(""), "source") { target, _ -> drops += target.id }
        setContent {
            CompositionLocalProvider(LocalPikoPlatform provides platform, LocalFileDrag provides drag) {
                Column(Modifier.size(300.dp).testTag("host").fileDragHost(drag)) {
                    Box(Modifier.fillMaxWidth().height(80.dp).fileDragSource { payload })
                    Box(Modifier.fillMaxWidth().height(220.dp).fileDropTarget("target", PikoPathBreadcrumb("target", "target")))
                }
            }
        }
        val host = onNodeWithTag("host")
        host.performMouseInput {
            moveTo(Offset(30f, 30f))
            press()
            scroll(1f)
            moveTo(Offset(65f, 30f))
        }
        waitForIdle()
        assertNotNull(drag.payload)
        host.performMouseInput { scroll(1f) }
        waitForIdle()
        assertNotNull(drag.payload)
        assertEquals(emptyList(), drops)
        host.performMouseInput { moveTo(Offset(65f, 180f)); release() }
        waitForIdle()
        assertNull(drag.payload)
        assertEquals(listOf("target"), drops)
    }
}
