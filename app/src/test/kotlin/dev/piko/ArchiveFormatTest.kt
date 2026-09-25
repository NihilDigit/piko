package dev.piko

import dev.piko.shared.data.isArchiveVolume
import dev.piko.shared.data.isExtractableArchive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveFormatTest {

    @Test
    fun everyVolumeIsRecognisedIncludingTheFirst() {
        for (name in listOf("Show.part1.rar", "Show.PART001.RAR", "Show.part10.rar", "Pack.7z.001", "Pack.zip.002", "Pack.z01", "Old.r00")) {
            assertTrue(name, isArchiveVolume(name))
            assertFalse(name, isExtractableArchive(name))
        }
    }

    @Test
    fun namesThatOnlyLookLikeVolumesStayPlainArchives() {
        assertTrue(isExtractableArchive("part2.rar"))
        assertTrue(isExtractableArchive("Show.part.rar"))
        assertTrue(isExtractableArchive("Pack.ZIP"))
        assertFalse(isArchiveVolume("Episode.2019.mkv"))
        assertFalse(isExtractableArchive("Folder.tar"))
        assertFalse(isExtractableArchive("zip"))
    }
}
