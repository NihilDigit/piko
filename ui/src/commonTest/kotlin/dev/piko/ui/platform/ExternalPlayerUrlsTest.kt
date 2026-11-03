package dev.piko.ui.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalPlayerUrlsTest {
    @Test
    fun preservesSignedUrlAsSingleParameter() {
        val source = "https://example.com/video.mp4?token=a%2Bb&name=中文 #1"
        for (player in listOf("SenPlayer", "Rex", "VLC", "Outplayer")) {
            val result = requireNotNull(externalPlayerUrl(player, source))
            assertEquals("https%3A%2F%2Fexample.com%2Fvideo.mp4%3Ftoken%3Da%252Bb%26name%3D%E4%B8%AD%E6%96%87%20%231", result.substringAfter("?url="))
        }
        assertNull(externalPlayerUrl("Unknown", source))
    }
}
