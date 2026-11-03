package dev.piko.ui.platform

import io.ktor.http.decodeURLPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalPlayerUrlsTest {
    @Test
    fun preservesSignedUrlAsSingleParameter() {
        val source = "https://example.com/video.mp4?token=a%2Bb&name=中文 #1"
        for (player in listOf("SenPlayer", "Rex", "VLC", "Outplayer")) {
            val result = requireNotNull(externalPlayerUrl(player, source))
            assertEquals(source, result.substringAfter("?url=").decodeURLPart())
        }
        assertNull(externalPlayerUrl("Unknown", source))
    }
}
