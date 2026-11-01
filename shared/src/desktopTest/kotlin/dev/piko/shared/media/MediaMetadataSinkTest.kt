package dev.piko.shared.media

import dev.piko.shared.data.DriveDetailObserver
import dev.piko.shared.data.DriveMetadataSink
import dev.piko.shared.media.testing.FakePikPakCloud
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MediaMetadataSinkTest {
    @Test
    fun `media detail is observed with original identity and captured client`() = runBlocking {
        val cloud = FakePikPakCloud(ByteArray(1024))
        val media = PikoMediaRepository(cloud.provider)
        var observed = 0
        media.metadataSink = DriveMetadataSink { owner ->
            assertSame(cloud.provider.currentClient.value, owner)
            DriveDetailObserver { originalId, detail ->
                assertEquals("f1", originalId)
                assertEquals(1024L, detail.sizeBytes)
                observed++
            }
        }
        media.prepareMedia("f1").getOrThrow()
        assertEquals(1, observed)
        assertEquals(1, cloud.detailCalls.get())
    }
}
