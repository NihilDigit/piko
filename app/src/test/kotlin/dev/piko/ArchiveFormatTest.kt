package dev.piko

import dev.piko.shared.data.isExtractableArchive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveFormatTest {

    @Test
    fun rarVolumesAfterTheFirstAreNotStandaloneArchives() {
        assertTrue(isExtractableArchive("Show.part1.rar"))
        assertTrue(isExtractableArchive("Show.part01.rar"))
        assertTrue(isExtractableArchive("Show.PART001.RAR"))
        assertFalse(isExtractableArchive("Show.part2.rar"))
        assertFalse(isExtractableArchive("Show.part10.rar"))
        // 名字里碰巧带 part 字样的普通 rar
        assertTrue(isExtractableArchive("part2.rar"))
        assertTrue(isExtractableArchive("Show.part.rar"))
    }

    @Test
    fun numberedVolumesAndUnreadableFormatsAreExcluded() {
        assertTrue(isExtractableArchive("Pack.ZIP"))
        assertFalse(isExtractableArchive("Pack.7z.001"))
        assertFalse(isExtractableArchive("Pack.z01"))
        assertFalse(isExtractableArchive("Folder.tar"))
        assertFalse(isExtractableArchive("zip"))
    }
}
