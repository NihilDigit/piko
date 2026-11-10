package dev.piko.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileNameSanitizerTest {

    @Test
    fun sanitizeRemovesIllegalCharactersAndNormalizesExtension() {
        val raw = """[Group] Anime: Fate/stay night [Heaven's Feel] ? * "test" <1080p> | OK.mp4"""
        val sanitized = FileNameSanitizer.sanitize(raw, "mp4")
        for (illegal in listOf(':', '/', '\\', '*', '?', '"', '|')) {
            assertFalse(sanitized.contains(illegal), sanitized)
        }
        assertTrue(sanitized.endsWith(".mp4"))
        assertEquals("[SweetSub] Oshi no Ko - 01 [1080p].mkv", FileNameSanitizer.sanitize("[SweetSub] Oshi no Ko - 01 [1080p]", "mkv"))
        assertEquals("[SweetSub] Oshi no Ko - 01 [1080p].mkv", FileNameSanitizer.sanitize("[SweetSub] Oshi no Ko - 01 [1080p].mkv", "mkv"))
        assertEquals("My Video Name.mp4", FileNameSanitizer.sanitize("My Video Name. . ", "mp4"))
        assertEquals("unnamed_video.mp4", FileNameSanitizer.sanitize(":::***///???", "mp4"))
    }

    @Test
    fun windowsDeviceNamesGetPrefixedWithoutAlteringNormalPrefixes() {
        assertEquals("_CON", FileNameSanitizer.sanitize("CON"))
        assertEquals("_NUL.txt", FileNameSanitizer.sanitize("NUL.txt"))
        assertEquals("_con.mkv", FileNameSanitizer.sanitize("con.mkv"))
        assertEquals("_nul.tar.gz", FileNameSanitizer.sanitize("nul.tar.gz"))
        assertEquals("_COM¹.log", FileNameSanitizer.sanitize("COM¹.log"))
        assertEquals("_LPT9.mp4", FileNameSanitizer.sanitize("LPT9", "mp4"))
        assertEquals("_CON.txt", FileNameSanitizer.sanitize("CON .txt"))
        assertEquals("_AUX", FileNameSanitizer.sanitizeFolderName("AUX"))
        assertEquals("_NUL.mp4", FileNameSanitizer.sanitize("NUL", forceExtension = "mp4"))
        assertEquals("_CON", FileNameSanitizer.sanitizeFolderName("CON. . "))
        assertEquals("_PRN.mp4", FileNameSanitizer.sanitize("PRN. ", "mp4"))

        assertEquals("CONSOLE.txt", FileNameSanitizer.sanitize("CONSOLE.txt"))
        assertEquals("COM10.txt", FileNameSanitizer.sanitize("COM10.txt"))
        assertEquals("NULL", FileNameSanitizer.sanitizeFolderName("NULL"))
        assertEquals("con-tent.mkv", FileNameSanitizer.sanitize("con-tent.mkv"))
    }

    @Test
    fun dominantVideoDetectionRequires10xSizeMarginOverExtras() {
        assertTrue(FileNameSanitizer.isVideoFileName("movie.mp4"))
        assertTrue(FileNameSanitizer.isVideoFileName("video.TS"))
        assertFalse(FileNameSanitizer.isVideoFileName("readme.txt"))

        assertEquals(0, FileNameSanitizer.findDominantVideoIndex(listOf("movie.mkv" to 2_000_000_000L, "readme.txt" to 1024L)))
        assertEquals(
            1,
            FileNameSanitizer.findDominantVideoIndex(
                listOf("sample.mkv" to 50_000_000L, "movie.mkv" to 2_000_000_000L, "promo.mp4" to 10_000_000L, "info.txt" to 500L),
            ),
        )
        assertEquals(
            null,
            FileNameSanitizer.findDominantVideoIndex(listOf("Episode_01.mkv" to 500_000_000L, "Episode_02.mkv" to 480_000_000L)),
        )
    }
}
