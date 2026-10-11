package dev.piko.shared.media

import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.media.testing.FakePikPakCloud
import dev.piko.shared.media.testing.request
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.StreamRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MediaClientScopeTest {
    @Test
    fun `clip details are reused within a client and refreshed after same account client reconstruction`() = runBlocking {
        val first = FakePikPakCloud(ByteArray(1024) { 1 })
        val second = FakePikPakCloud(ByteArray(2048) { 2 })
        val provider = object : PikoClientProvider {
            override val currentClient = MutableStateFlow<PikPakClient?>(first.provider.currentClient.value)
        }
        val media = PikoMediaRepository(provider)
        media.clipProbe("f1")
        assertEquals(1, first.detailCalls.get())
        media.prepareClip("f1", 0, 600_000, StreamRole.FOREGROUND).getOrThrow().use { clip ->
            assertEquals(1024L, clip.streamBytes)
        }
        assertEquals(1, first.detailCalls.get(), "同一 client 的刚查询详情应复用")
        media.clipProbe("f1")
        assertEquals(2, first.detailCalls.get())
        provider.currentClient.value = second.provider.currentClient.value
        assertEquals(first.provider.currentClient.value!!.account, provider.currentClient.value!!.account)
        media.prepareClip("f1", 0, 600_000, StreamRole.FOREGROUND).getOrThrow().use { clip ->
            assertEquals(2048L, clip.streamBytes, "新 client 不能复用旧详情的长度与签名直链")
            assertContentEquals(second.origin, request(assertNotNull(clip.url), range = "bytes=0-").body)
        }
        assertEquals(1, second.detailCalls.get())
    }
}
