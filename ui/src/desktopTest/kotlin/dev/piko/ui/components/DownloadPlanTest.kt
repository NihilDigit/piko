package dev.piko.ui.components

import dev.piko.shared.media.DownloadQuality
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadPlanTest {
    @Test
    fun unsetDefaultAsksOnlyWhenThereIsAVideo() = runBlocking {
        assertEquals(DownloadPlan.Ask, planDownload(null) { true })
        assertEquals(DownloadPlan.Direct(0), planDownload(null) { false })
    }

    @Test
    fun setDefaultDownloadsDirectlyWithoutProbingFolders() = runBlocking {
        // 原画（0）也是用户显式设过的默认，不能当成未设置再问一遍
        for (height in listOf(0, 1080, 480)) {
            val plan = planDownload(height) { error("设了默认画质还去列文件夹") }
            assertEquals(DownloadPlan.Direct(height), plan)
        }
    }

    @Test
    fun onlyACheckedChoiceBecomesTheDefault() {
        val transcode = DownloadQuality(name = "540P", mediaId = "m540", height = 540, sizeBytes = 1, durationMs = 1)
        val original = DownloadQuality(name = null, mediaId = null, height = 2160, sizeBytes = 1, durationMs = 1)
        assertNull(keptDefaultMaxHeight(DownloadChoice.Exact(transcode), keep = false))
        assertNull(keptDefaultMaxHeight(DownloadChoice.Cap(720), keep = false))
        // 存下的要是设置里列得出的一档，540P 落到 720P 这一级
        assertEquals(720, keptDefaultMaxHeight(DownloadChoice.Exact(transcode), keep = true))
        assertEquals(0, keptDefaultMaxHeight(DownloadChoice.Exact(original), keep = true))
        assertEquals(480, keptDefaultMaxHeight(DownloadChoice.Cap(480), keep = true))
    }
}
