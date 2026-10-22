package dev.piko.shared.smoke

import dev.piko.shared.data.*
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.TaskPhase
import kotlin.test.*
import kotlin.time.Duration

class ReferenceSaveSmokeTest {
    @Test
    fun unknownManifestConfirmationRetainsTheAttemptWithoutCreatingContent() = smoke { _ ->
        val server = FakePikPakServer()
        val io = MemoryManifestIo(failConfirmation = true)
        val vault = VaultStore(io, confirmDelay = Duration.ZERO)
        val files = listOf(InstantFileItem(ResolvedFile("原名.mkv", 100, "GCID1"), true))
        val result = InstantMagnetRepository(server.provider()).saveReferences(files, "", false, emptyMap(), emptyMap(), vault, null)
        val receipt = assertIs<InstantSaveIncomplete>(result.exceptionOrNull()).receipt
        assertEquals(setOf("原名.mkv"), receipt.unconfirmed)
        assertTrue(receipt.requiresInspection)
        assertEquals(0, server.instantCreates.get())
        assertEquals(1, io.uploads)
    }

    @Test
    fun referencesKeepPathsAndRenamesWithoutInstantCreatingTheContent() = smoke { _ ->
        val server = FakePikPakServer()
        val io = MemoryManifestIo()
        val vault = VaultStore(io, confirmDelay = Duration.ZERO)
        val repository = InstantMagnetRepository(server.provider())
        val files = listOf(InstantFileItem(ResolvedFile("正片/原名.mkv", 100, "GCID1"), true),
            InstantFileItem(ResolvedFile("字幕/原名.ass", 10, "GCID2"), true))
        val members = mutableMapOf<String, Set<String>>()
        val ids = repository.saveReferences(files + InstantFileItem(ResolvedFile("未知.mkv", 50, null), false), "", true,
            mapOf("正片/原名.mkv" to "最终名.mkv"), emptyMap(), vault, "magnet:source") { added ->
            added.forEach { (ancestor, leaves) -> members[ancestor] = members[ancestor].orEmpty() + leaves }
        }.getOrThrow()
        assertEquals(0, server.instantCreates.get())
        assertEquals(2, ids.size)
        assertTrue(ids.all(VaultEntry::isVaulted))
        val folders = server.children("").associateBy { it.name }
        assertEquals(folders.values.map { it.id }.toSet(), members[""])
        assertEquals(setOf(folders.getValue("正片").id), members[folders.getValue("正片").id])
        val video = vault.committed(folders.getValue("正片").id).getOrThrow().single()
        assertEquals("最终名.mkv", video.name)
        assertEquals("magnet:source", video.source)
        assertEquals("GCID1", video.gcid)
        assertEquals("原名.ass", vault.committed(folders.getValue("字幕").id).getOrThrow().single().name)
    }

    private class MemoryManifestIo(private val failConfirmation: Boolean = false) : VaultFolderIo {
        private val files = linkedMapOf<String, Pair<FileStat, ByteArray>>()
        var uploads = 0
            private set
        override suspend fun list(folderId: String): List<FileStat> {
            if (failConfirmation && uploads > 0) throw java.io.IOException("confirmation unavailable")
            return files.values.map { it.first }.filter { it.parentId == folderId }
        }
        override suspend fun read(fileId: String) = files.getValue(fileId).second
        override suspend fun upload(folderId: String, name: String, bytes: ByteArray): String {
            uploads++
            val id = "manifest-${files.size}-$name"
            files[id] = FileStat(id = id, parentId = folderId, name = name, size = bytes.size.toString(), kind = FileKind.FILE, phase = TaskPhase.COMPLETE) to bytes
            return id
        }
        override suspend fun delete(ids: List<String>) { ids.forEach(files::remove) }
    }
}
