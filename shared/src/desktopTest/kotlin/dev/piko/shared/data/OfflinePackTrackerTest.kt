package dev.piko.shared.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class OfflinePackTrackerTest {
    @Test
    fun `polling is quick for cached content and backs off to a cap`() {
        // 已缓存的内容 5 到 10 秒下完，第一分钟的间隔要比这短，否则一次就多等一轮
        assertTrue(offlinePollDelay(0.seconds) <= 5.seconds)
        val samples = listOf(0.seconds, 30.seconds, 2.minutes, 10.minutes, 1.hours, 1.days, 30.days)
            .map(::offlinePollDelay)
        assertEquals(samples.sorted(), samples, "间隔不能随时间变短")
        assertTrue(samples.last() <= 5.minutes)
    }

    private fun job(taskId: String, stage: OfflinePackStage, finishedAtMs: Long = 0) = OfflinePackJob(
        account = "a",
        taskId = taskId,
        url = "magnet:?xt=urn:btih:x",
        targetId = "T",
        folderName = "F",
        keep = setOf("E01.mkv"),
        totalFiles = 3,
        totalBytes = 10,
        createdAtMs = 0,
        stage = stage,
        finishedAtMs = finishedAtMs,
    )

    @Test
    fun `restore keeps unfinished and failed jobs and drops old finished ones`() {
        val now = 30.days.inWholeMilliseconds
        val jobs = listOf(
            job("running", OfflinePackStage.DOWNLOADING),
            job("pruning", OfflinePackStage.PRUNING),
            job("failed-long-ago", OfflinePackStage.FAILED, finishedAtMs = 1),
            job("done-recently", OfflinePackStage.DONE, finishedAtMs = now - 1.days.inWholeMilliseconds),
            job("done-long-ago", OfflinePackStage.DONE, finishedAtMs = 1),
        )
        val serialized = Json.encodeToString(ListSerializer(OfflinePackJob.serializer()), jobs)

        val restored = restoreOfflinePacks(serialized, now).map { it.taskId }

        assertEquals(listOf("running", "pruning", "failed-long-ago", "done-recently"), restored)
    }

    @Test
    fun `a corrupt record starts empty instead of failing`() {
        assertEquals(emptyList(), restoreOfflinePacks("{not json", 0))
        assertEquals(emptyList(), restoreOfflinePacks("", 0))
    }
}
