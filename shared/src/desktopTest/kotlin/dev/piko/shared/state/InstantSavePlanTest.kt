package dev.piko.shared.state

import dev.piko.shared.data.InstantFileItem
import io.github.nihildigit.pikpak.ResolvedFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstantSavePlanTest {
    private fun item(path: String, size: Long, gcid: String? = "G-$path") =
        InstantFileItem(ResolvedFile(path, size, gcid), isInstantReady = gcid != null)

    private val gb = 1L shl 30
    private val pack = listOf(
        item("E01.mkv", 2 * gb),
        item("E01.ass", 100_000),
        item("E02.mkv", 2 * gb),
        item("extra.nfo", 1_000, gcid = null),
    )

    @Test
    fun `one video with its bundled subtitle is still one entry and goes instant`() {
        val plan = assertNotNull(planSave(pack, setOf(0, 1), selectedEntryCount = 1, remainingBytes = null))
        assertEquals(SaveRoute.INSTANT, plan.route)
        assertEquals(2, plan.fileCount)
        assertEquals(uploadCharge(2 * gb + 100_000), plan.uploadCostBytes)
    }

    @Test
    fun `two entries go offline even when both are indexed`() {
        val plan = assertNotNull(planSave(pack, setOf(0, 2), selectedEntryCount = 2, remainingBytes = null))
        assertEquals(SaveRoute.OFFLINE_PACK, plan.route)
        assertEquals(2, plan.prunedCount)
    }

    @Test
    fun `a single unindexed file can only go offline`() {
        val plan = assertNotNull(planSave(pack, setOf(3), selectedEntryCount = 1, remainingBytes = null))
        assertEquals(SaveRoute.OFFLINE_PACK, plan.route)
    }

    /** 选中的放得下、整包放不下时也要拦：离线要先把整包落进网盘。 */
    @Test
    fun `space is checked against the whole pack, not the selection`() {
        val plan = assertNotNull(planSave(pack, setOf(0, 2), selectedEntryCount = 2, remainingBytes = 4 * gb))
        assertTrue(plan.lacksSpace)
        val fallback = assertNotNull(plan.fallback)
        assertEquals(2, fallback.fileCount)
    }

    @Test
    fun `the instant fallback skips unindexed files and has none when nothing is indexed`() {
        val mixed = assertNotNull(planSave(pack, setOf(0, 3), selectedEntryCount = 2, remainingBytes = gb))
        assertEquals(1, mixed.fallback?.fileCount)
        assertEquals(1, mixed.fallback?.skippedCount)

        val onlyUnindexed = assertNotNull(planSave(pack, setOf(3), selectedEntryCount = 1, remainingBytes = 0))
        assertTrue(onlyUnindexed.lacksSpace)
        assertNull(onlyUnindexed.fallback)
    }

    @Test
    fun `unknown remaining space does not block`() {
        val plan = assertNotNull(planSave(pack, setOf(0, 2), selectedEntryCount = 2, remainingBytes = null))
        assertFalse(plan.lacksSpace)
    }

    @Test
    fun `upload charge is fifteen percent rounded up`() {
        assertEquals(15, uploadCharge(100))
        assertEquals(1, uploadCharge(1))
        assertEquals(0, uploadCharge(0))
    }
}
