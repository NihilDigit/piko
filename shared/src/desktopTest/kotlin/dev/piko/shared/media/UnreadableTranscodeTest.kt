package dev.piko.shared.media

import dev.piko.shared.media.testing.FakePikPakCloud
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 服务端坏掉的转码档：回 206、Content-Range 写着全长，正文为空。只看响应头的长度探测认不出它，
 * 列出来让用户选，下载到第一个块才失败，提示是 SDK 的原文。
 */
class UnreadableTranscodeTest {

    private val cloud = FakePikPakCloud(
        origin = FakePikPakCloud.payload(64 * 1024),
        transcode = FakePikPakCloud.payload(32 * 1024),
        unreadableTranscodeHeight = 720,
    )
    private val repository = PikoMediaRepository(cloud.provider)

    @Test
    fun unreadableTranscodeIsMarked() = runBlocking {
        val final = repository.downloadQualities("f1").toList().last()
        assertEquals(listOf(null, "720P", "480P"), final.map { it.name })
        assertEquals(listOf(false, true, false), final.map { it.unreadable })
        assertEquals(32L * 1024, final.single { it.name == "480P" }.sizeBytes)
    }

    @Test
    fun listedProbeIsReusedWhenTheDownloadStarts() = runBlocking {
        repository.downloadQualities("f1").toList()
        val requests = cloud.cdnRequests.get() to cloud.detailCalls.get()
        // 上限取 720：原画是 1080，上限 1080 时原画本身就不高于它
        assertEquals("480P", repository.downloadVariant("f1", name = null, maxHeight = 720).getOrThrow()?.name)
        assertIs<UnreadableTranscodeException>(repository.downloadVariant("f1", name = "720P", maxHeight = 0).exceptionOrNull())
        assertEquals(requests, cloud.cdnRequests.get() to cloud.detailCalls.get())
    }

    @Test
    fun capSkipsToTheHighestReadableTranscode() = runBlocking {
        val chosen = repository.downloadVariant("f1", name = null, maxHeight = 720).getOrThrow()
        assertEquals("480P", chosen?.name)
        assertEquals(32L * 1024, chosen?.sizeBytes)
    }

    @Test
    fun originalWithinCapIsNotProbedAgainstTranscodes() = runBlocking {
        assertEquals(null, repository.downloadVariant("f1", name = null, maxHeight = 1080).getOrThrow())
        assertEquals(0, cloud.cdnRequests.get())
    }

    @Test
    fun explicitlyChosenUnreadableTranscodeFailsWithReadableMessage() = runBlocking {
        val error = repository.downloadVariant("f1", name = "720P", maxHeight = 0).exceptionOrNull()
        assertIs<UnreadableTranscodeException>(error)
        // 这句就是任务的失败原因，要点明是服务端的问题，否则用户以为网络或 Piko 坏了
        assertTrue(error.message.orEmpty().startsWith("PikPak"), error.message)
    }
}
