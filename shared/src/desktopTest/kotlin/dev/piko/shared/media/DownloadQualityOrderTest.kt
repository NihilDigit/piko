package dev.piko.shared.media

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadQualityOrderTest {
    private fun original(height: Int) = DownloadQuality(null, null, height, 1_000L, 0L)
    private fun transcode(height: Int, unreadable: Boolean = false) =
        DownloadQuality("${height}P", "m$height", height, null, 0L, unreadable)

    private fun picked(options: List<DownloadQuality>, cap: Int) = chooseDownloadQuality(options, cap)?.name ?: "原画"

    private val full = listOf(original(2160), transcode(1080), transcode(720), transcode(480))

    @Test
    fun exactLevelWins() {
        assertEquals("720P", picked(full, 720))
    }

    @Test
    fun missingLevelFallsToNextLower() {
        assertEquals("480P", picked(listOf(original(2160), transcode(1080), transcode(480)), 720))
    }

    @Test
    fun unreadableLevelIsSkippedDownward() {
        val options = listOf(original(2160), transcode(1080), transcode(720, unreadable = true), transcode(480))
        assertEquals(listOf("480P", "1080P", null), downloadQualityOrder(options, 720).map { it.name })
    }

    @Test
    fun nothingLowerTakesLowestAvailableNotOriginal() {
        assertEquals("720P", picked(listOf(original(2160), transcode(1080), transcode(720)), 480))
    }

    @Test
    fun originalOnlyVideoDownloadsOriginalAtAnyCap() {
        assertEquals("原画", picked(listOf(original(1080)), 480))
        assertEquals("原画", picked(listOf(original(0)), 480))
    }

    @Test
    fun originalCapIgnoresTranscodes() {
        assertEquals(listOf(null), downloadQualityOrder(full, 0).map { it.name })
    }

    @Test
    fun originalWithinCapBeatsSameHeightTranscode() {
        assertEquals("原画", picked(listOf(original(720), transcode(720), transcode(480)), 1080))
    }

    @Test
    fun originalOfUnknownHeightRanksAboveTranscodes() {
        // 高度未知的原画若按 0 排，选 480P 时会被当成最低一档取走
        assertEquals("480P", picked(listOf(original(0), transcode(720), transcode(480)), 480))
        assertEquals("720P", picked(listOf(original(0), transcode(720)), 480))
    }

    @Test
    fun keptChoiceMapsToSmallestLevelNotBelowIt() {
        assertEquals(0, downloadMaxHeightFor(original(720)))
        assertEquals(720, downloadMaxHeightFor(transcode(720)))
        // 非标准高度取不低于它的一级，否则以后这个视频挑不到选中的那一档
        assertEquals(720, downloadMaxHeightFor(transcode(540)))
        assertEquals(0, downloadMaxHeightFor(transcode(1440)))
    }
}
