package dev.piko

import dev.piko.data.repository.FileNameSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNameSanitizerTest {

    @Test
    fun testSanitizeRemovesIllegalCharacters() {
        val raw = """[Group] Anime: Fate/stay night [Heaven's Feel] ? * "test" <1080p> | OK.mp4"""
        val sanitized = FileNameSanitizer.sanitize(raw, "mp4")
        assertFalse(sanitized.contains(':'))
        assertFalse(sanitized.contains('/'))
        assertFalse(sanitized.contains('\\'))
        assertFalse(sanitized.contains('*'))
        assertFalse(sanitized.contains('?'))
        assertFalse(sanitized.contains('"'))
        assertFalse(sanitized.contains('|'))
        assertTrue(sanitized.endsWith(".mp4"))
    }

    @Test
    fun testSanitizeAppendsExtensionIfMissing() {
        val raw = "[SweetSub] Oshi no Ko - 01 [1080p]"
        val sanitized = FileNameSanitizer.sanitize(raw, "mkv")
        assertEquals("[SweetSub] Oshi no Ko - 01 [1080p].mkv", sanitized)
    }

    @Test
    fun testSanitizePreservesExistingExtension() {
        val raw = "[SweetSub] Oshi no Ko - 01 [1080p].mkv"
        val sanitized = FileNameSanitizer.sanitize(raw, "mkv")
        assertEquals("[SweetSub] Oshi no Ko - 01 [1080p].mkv", sanitized)
    }

    @Test
    fun testSanitizeRemovesTrailingDotsAndSpaces() {
        val raw = "My Video Name. . "
        val sanitized = FileNameSanitizer.sanitize(raw, "mp4")
        assertEquals("My Video Name.mp4", sanitized)
    }

    @Test
    fun testSanitizeBlankFallback() {
        val raw = ":::***///???"
        val sanitized = FileNameSanitizer.sanitize(raw, "mp4")
        assertEquals("unnamed_video.mp4", sanitized)
    }

    @Test
    fun testVideoDetection() {
        assertTrue(FileNameSanitizer.isVideoFileName("movie.mp4"))
        assertTrue(FileNameSanitizer.isVideoFileName("movie.mkv"))
        assertTrue(FileNameSanitizer.isVideoFileName("video.TS"))
        assertFalse(FileNameSanitizer.isVideoFileName("readme.txt"))
        assertFalse(FileNameSanitizer.isVideoFileName("image.png"))
    }

    @Test
    fun testDominantVideoSingleVideo() {
        val files = listOf(
            "movie.mkv" to 2_000_000_000L,
            "readme.txt" to 1024L,
            "poster.jpg" to 500_000L,
        )
        val dominant = FileNameSanitizer.findDominantVideoIndex(files)
        assertEquals(0, dominant)
    }

    @Test
    fun testDominantVideoWithExtraSmallClipsExceeding10x() {
        val files = listOf(
            "sample.mkv" to 50_000_000L,       // 50 MB
            "movie.mkv" to 2_000_000_000L,     // 2000 MB (40x of 50 MB)
            "promo.mp4" to 10_000_000L,       // 10 MB
            "info.txt" to 500L,
        )
        val dominant = FileNameSanitizer.findDominantVideoIndex(files)
        assertEquals(1, dominant) // index 1 is movie.mkv
    }

    @Test
    fun testDominantVideoMultiEpisodeFails10xRatio() {
        val files = listOf(
            "Episode_01.mkv" to 500_000_000L, // 500 MB
            "Episode_02.mkv" to 480_000_000L, // 480 MB (only ~1.04x)
        )
        val dominant = FileNameSanitizer.findDominantVideoIndex(files)
        assertEquals(null, dominant)
    }
}
