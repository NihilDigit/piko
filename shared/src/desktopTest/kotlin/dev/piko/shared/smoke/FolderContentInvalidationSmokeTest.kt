package dev.piko.shared.smoke

import dev.piko.shared.data.DriveChange
import dev.piko.shared.data.PikoDriveRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FolderContentInvalidationSmokeTest {
    @Test
    fun changesInvalidateOnlyAffectedSamplesAndNextFetchReadsNewNames() = smoke {
        val server = FakePikPakServer()
        val folder = server.addFolder("Show")
        val other = server.addFolder("Other")
        val video = server.addVideo("Show - 01.mkv", folder.id)
        server.addVideo("Other - 01.mkv", other.id)
        val repo = PikoDriveRepository(server.provider(), MemoryPreferences())
        repo.listAllFiles(folder.id).getOrThrow()
        repo.listAllFiles(other.id).getOrThrow()
        assertNotNull(repo.knownChildContents(folder.id))
        repo.rename(video.id, "Show - 02.mkv").getOrThrow()
        assertNull(repo.knownChildContents(folder.id))
        assertNotNull(repo.knownChildContents(other.id))
        assertEquals(listOf("Show - 02.mkv"), repo.fetchChildContents(folder.id)?.map { it.name })
        repo.applyChange(DriveChange.Created(folder.id, "new", "Show - 03.mkv", false))
        assertNull(repo.knownChildContents(folder.id))
        repo.fetchChildContents(folder.id)
        repo.applyChange(DriveChange.Trashed(listOf(video.id), setOf(folder.id)))
        assertNull(repo.knownChildContents(folder.id))
        assertNotNull(repo.knownChildContents(other.id))
        repo.applyChange(DriveChange.ContentsChanged(null))
        assertNull(repo.knownChildContents(other.id))
    }
}
