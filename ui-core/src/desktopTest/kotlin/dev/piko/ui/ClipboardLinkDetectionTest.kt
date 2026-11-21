package dev.piko.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClipboardLinkDetectionTest {
    private val magnet = "magnet:?xt=urn:btih:" + "a".repeat(40)

    @Test fun onlyImportableLinksAreSuggested() {
        val detector = ClipboardLinkDetection()
        assertNull(detector.detect(null))
        assertNull(detector.detect("https://example.com magnet:?xt=invalid"))
        assertEquals(magnet, detector.detect("链接 $magnet https://example.com"))
    }

    @Test fun sameContentIsNeverSuggestedTwiceEvenAfterOtherContent() {
        val detector = ClipboardLinkDetection()
        assertEquals(magnet, detector.detect(magnet))
        val share = "https://mypikpak.com/s/example"
        assertEquals(share, detector.detect(share))
        assertNull(detector.detect(magnet))
        assertNull(detector.detect(share))
    }

    @Test fun externallyHandledLinksAreNotSuggestedAgain() {
        val detector = ClipboardLinkDetection()
        detector.handled(magnet)
        assertNull(detector.detect("复制的链接：$magnet"))
    }

    @Test fun sharePasscodeIsPreserved() {
        assertEquals("https://mypikpak.com/s/example?pwd=1234",
            ClipboardLinkDetection().detect("https://mypikpak.com/s/example 提取码：1234"))
    }
}
