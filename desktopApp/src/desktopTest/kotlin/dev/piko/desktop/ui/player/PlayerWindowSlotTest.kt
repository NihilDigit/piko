package dev.piko.desktop.ui.player

import dev.piko.desktop.DesktopPikoPlatform
import dev.piko.desktop.DesktopSettingsStore
import dev.piko.ui.VideoPlayerRequest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlayerWindowSlotTest {
    private val tempDir = Files.createTempDirectory("piko-player-slot").toFile()
    private val settingsFile = tempDir.resolve("settings.properties")

    @AfterTest
    fun cleanUp() {
        DesktopSettingsStore.flushPendingFor(settingsFile.toPath())
        tempDir.deleteRecursively()
    }

    @Test
    fun opensSeparateWindowsWhenReplaceIsDisabled() {
        val slots = mutableListOf<PlayerWindowSlot>()
        val ep1 = VideoPlayerRequest(fileId = "ep1", fileName = "Episode 01.mkv", localPath = null)
        val ep2 = VideoPlayerRequest(fileId = "ep2", fileName = "Episode 02.mkv", localPath = null)

        openPlayerRequest(slots, ep1, replaceExisting = false)
        openPlayerRequest(slots, ep2, replaceExisting = false)

        assertEquals(2, slots.size)
        assertSame(ep1, slots[0].request)
        assertSame(ep2, slots[1].request)
        assertNotSame(slots[0], slots[1])
        assertEquals(0, slots[0].raise)
        assertEquals(0, slots[1].raise)
    }

    @Test
    fun reusesExistingWindowSlotAndRaisesWhenReplaceIsEnabled() {
        val slots = mutableListOf<PlayerWindowSlot>()
        val ep1 = VideoPlayerRequest(fileId = "ep1", fileName = "Episode 01.mkv", localPath = null)
        val ep2 = VideoPlayerRequest(fileId = "ep2", fileName = "Episode 02.mkv", localPath = null)
        val ep3 = VideoPlayerRequest(fileId = "ep3", fileName = "Episode 03.mkv", localPath = null)

        openPlayerRequest(slots, ep1, replaceExisting = true)
        assertEquals(1, slots.size)
        val initialSlot = slots.single()
        assertSame(ep1, initialSlot.request)
        assertEquals(0, initialSlot.raise)

        openPlayerRequest(slots, ep2, replaceExisting = true)
        assertEquals(1, slots.size)
        assertSame(initialSlot, slots.single())
        assertSame(ep2, initialSlot.request)
        assertEquals(1, initialSlot.raise)

        openPlayerRequest(slots, ep3, replaceExisting = true)
        assertEquals(1, slots.size)
        assertSame(initialSlot, slots.single())
        assertSame(ep3, initialSlot.request)
        assertEquals(2, initialSlot.requestGeneration)
        assertEquals(2, initialSlot.raise)
    }

    @Test
    fun incrementsRequestGenerationWhenReopeningIdenticalRequest() {
        val slots = mutableListOf<PlayerWindowSlot>()
        val ep1First = VideoPlayerRequest(fileId = "ep1", fileName = "Episode 01.mkv", localPath = null)
        val ep1Again = VideoPlayerRequest(fileId = "ep1", fileName = "Episode 01.mkv", localPath = null)

        openPlayerRequest(slots, ep1First, replaceExisting = true)
        val slot = slots.single()
        assertEquals(0, slot.requestGeneration)
        assertEquals(0, slot.raise)

        openPlayerRequest(slots, ep1Again, replaceExisting = true)
        assertEquals(1, slots.size)
        assertSame(slot, slots.single())
        assertEquals(ep1Again, slot.request)
        assertEquals(1, slot.requestGeneration)
        assertEquals(1, slot.raise)
    }

    @Test
    fun collapsesMultipleOpenWindowsToSingleWindowWhenToggledOnMidSession() {
        val slots = mutableListOf<PlayerWindowSlot>()
        val ep1 = VideoPlayerRequest(fileId = "ep1", fileName = "Episode 01.mkv", localPath = null)
        val ep2 = VideoPlayerRequest(fileId = "ep2", fileName = "Episode 02.mkv", localPath = null)
        val ep3 = VideoPlayerRequest(fileId = "ep3", fileName = "Episode 03.mkv", localPath = null)

        openPlayerRequest(slots, ep1, replaceExisting = false)
        openPlayerRequest(slots, ep2, replaceExisting = false)
        assertEquals(2, slots.size)
        val latestWindow = slots[1]

        openPlayerRequest(slots, ep3, replaceExisting = true)
        assertEquals(1, slots.size)
        assertSame(latestWindow, slots.single())
        assertSame(ep3, latestWindow.request)
        assertEquals(1, latestWindow.raise)
    }

    @Test
    fun persistsReplacePlayerWindowToggleAcrossPlatformRestarts() {
        val store = DesktopSettingsStore(settingsFile)
        val platform = DesktopPikoPlatform(store)
        assertFalse(platform.replacePlayerWindow.enabled.value)

        platform.replacePlayerWindow.set(true)
        assertTrue(platform.replacePlayerWindow.enabled.value)

        val reopened = DesktopPikoPlatform(DesktopSettingsStore(settingsFile))
        assertTrue(reopened.replacePlayerWindow.enabled.value)

        reopened.replacePlayerWindow.set(false)
        val reopenedAgain = DesktopPikoPlatform(DesktopSettingsStore(settingsFile))
        assertFalse(reopenedAgain.replacePlayerWindow.enabled.value)
    }
}
