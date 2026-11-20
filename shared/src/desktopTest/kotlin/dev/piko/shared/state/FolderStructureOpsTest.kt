package dev.piko.shared.state

import dev.piko.shared.data.DriveChange
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.smoke.FakePikPakServer
import dev.piko.shared.smoke.MemoryPreferences
import dev.piko.shared.smoke.smoke
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FolderStructureOpsTest {

    private class FakeBackend : FolderStructureBackend {
        private val nodes = LinkedHashMap<String, FileStat>()
        val vaultedFolders = HashSet<String>()
        val movedBatches = mutableListOf<Pair<List<String>, String>>()
        val renamedCalls = mutableListOf<Pair<String, String>>()
        val trashedBatches = mutableListOf<List<String>>()

        fun addFolder(id: String, name: String, parentId: String = ""): FileStat {
            val stat = FileStat(kind = FileKind.FOLDER, id = id, parentId = parentId, name = name)
            nodes[id] = stat
            return stat
        }

        fun addFile(id: String, name: String, parentId: String = "", size: Long = 1024L): FileStat {
            val stat = FileStat(kind = FileKind.FILE, id = id, parentId = parentId, name = name, size = size.toString())
            nodes[id] = stat
            return stat
        }

        fun childrenOf(parentId: String): List<FileStat> =
            nodes.values.filter { it.parentId == parentId && !it.trashed }

        fun node(id: String): FileStat? = nodes[id]

        override suspend fun listChildren(folderId: String): Result<List<FileStat>> =
            Result.success(childrenOf(folderId))

        override suspend fun moveFiles(ids: List<String>, targetParentId: String): Result<Unit> {
            movedBatches += ids to targetParentId
            for (id in ids) {
                nodes[id]?.let { nodes[id] = it.copy(parentId = targetParentId) }
            }
            return Result.success(Unit)
        }

        override suspend fun renameFile(fileId: String, newName: String, parentId: String?): Result<Unit> {
            renamedCalls += fileId to newName
            nodes[fileId]?.let { nodes[fileId] = it.copy(name = newName) }
            return Result.success(Unit)
        }

        override suspend fun trashFiles(ids: List<String>): Result<Unit> {
            trashedBatches += ids
            for (id in ids) {
                nodes[id]?.let { nodes[id] = it.copy(trashed = true) }
            }
            return Result.success(Unit)
        }

        override fun isVaultedFolder(folderId: String): Boolean = folderId in vaultedFolders
    }

    @Test
    fun `unwrap penetrates single-child wrapper chain when enabled and lifts direct children when disabled`() = runBlocking {
        val backend = FakeBackend()
        val work = backend.addFolder("work", "Downloads")
        val outer = backend.addFolder("outer", "Outer", parentId = work.id)
        val inner = backend.addFolder("inner", "Inner", parentId = outer.id)
        val real = backend.addFolder("real", "Real", parentId = inner.id)
        val movie = backend.addFile("movie", "movie.mkv", parentId = real.id)
        val subs = backend.addFile("subs", "movie.srt", parentId = real.id)
        val ops = FolderStructureOps(backend)

        // 1. Dry-run with unwrapSingleChildChain = false -> moves only direct child [inner].
        val shallowPlan = ops.plan(
            workingFolderId = work.id,
            workingFolderName = work.name,
            selectedFolders = listOf(outer),
            options = FolderStructureOptions(FolderStructureMode.UNWRAP, unwrapSingleChildChain = false),
        ).getOrThrow()
        assertEquals(0, shallowPlan.penetratedWrapperCount)
        assertEquals(listOf(inner.id), shallowPlan.moves.map { it.item.id })
        assertEquals(listOf(outer.id), shallowPlan.foldersToTrash.map { it.id })

        // 2. Dry-run & execute with unwrapSingleChildChain = true -> penetrates Inner + Real, lifts movie & subs!
        val deepPlan = ops.plan(
            workingFolderId = work.id,
            workingFolderName = work.name,
            selectedFolders = listOf(outer),
            options = FolderStructureOptions(FolderStructureMode.UNWRAP, unwrapSingleChildChain = true),
        ).getOrThrow()
        assertEquals(2, deepPlan.penetratedWrapperCount)
        assertEquals(setOf(movie.id, subs.id), deepPlan.moves.map { it.item.id }.toSet())
        assertEquals(listOf(outer.id), deepPlan.foldersToTrash.map { it.id })

        val outcome = ops.execute(deepPlan)
        assertTrue(outcome.isSuccess)
        assertEquals(2, outcome.movedCount)
        assertEquals(1, outcome.trashedFolderCount)
        assertEquals(setOf("movie.mkv", "movie.srt"), backend.childrenOf(work.id).map { it.name }.toSet())
        assertTrue(backend.node(outer.id)!!.trashed)
    }

    @Test
    fun `flatten recursively extracts all nested files and trashes emptied tree`() = runBlocking {
        val backend = FakeBackend()
        val work = backend.addFolder("work", "Media")
        val root = backend.addFolder("root", "Pack", parentId = work.id)
        val s1 = backend.addFolder("s1", "Season 1", parentId = root.id)
        val subs = backend.addFolder("subs", "Subtitles", parentId = s1.id)
        val empty = backend.addFolder("empty", "Empty", parentId = root.id)
        val f1 = backend.addFile("f1", "ep01.mkv", parentId = s1.id)
        val f2 = backend.addFile("f2", "ep01.ass", parentId = subs.id)
        val f3 = backend.addFile("f3", "readme.txt", parentId = root.id)
        val ops = FolderStructureOps(backend)

        val plan = ops.plan(
            workingFolderId = work.id,
            workingFolderName = work.name,
            selectedFolders = listOf(root),
            options = FolderStructureOptions(FolderStructureMode.FLATTEN),
        ).getOrThrow()

        assertEquals(setOf(f1.id, f2.id, f3.id), plan.moves.map { it.item.id }.toSet())
        assertEquals(listOf(root.id), plan.foldersToTrash.map { it.id })
        assertTrue(empty.id !in plan.moves.map { it.item.id })

        val outcome = ops.execute(plan)
        assertTrue(outcome.isSuccess)
        assertEquals(3, outcome.movedCount)
        assertEquals(1, outcome.trashedFolderCount)
        assertEquals(setOf("ep01.mkv", "ep01.ass", "readme.txt"), backend.childrenOf(work.id).map { it.name }.toSet())
    }

    @Test
    fun `merge picks largest folder by default and moves direct children from remaining folders`() = runBlocking {
        val backend = FakeBackend()
        val work = backend.addFolder("work", "Library")
        val small = backend.addFolder("small", "Part 1", parentId = work.id)
        val large = backend.addFolder("large", "Main", parentId = work.id)
        val other = backend.addFolder("other", "Part 2", parentId = work.id)
        backend.addFile("s1", "a.mkv", parentId = small.id)
        backend.addFile("l1", "m1.mkv", parentId = large.id)
        backend.addFile("l2", "m2.mkv", parentId = large.id)
        backend.addFile("o1", "b.mkv", parentId = other.id)
        val ops = FolderStructureOps(backend)

        val plan = ops.plan(
            workingFolderId = work.id,
            workingFolderName = work.name,
            selectedFolders = listOf(small, large, other),
            options = FolderStructureOptions(FolderStructureMode.MERGE),
        ).getOrThrow()

        assertEquals(large.id, plan.targetFolderId)
        assertEquals(setOf("s1", "o1"), plan.moves.map { it.item.id }.toSet())
        assertEquals(setOf(small.id, other.id), plan.foldersToTrash.map { it.id }.toSet())

        val outcome = ops.execute(plan)
        assertTrue(outcome.isSuccess)
        assertEquals(2, outcome.movedCount)
        assertEquals(2, outcome.trashedFolderCount)
        assertEquals(setOf("m1.mkv", "m2.mkv", "a.mkv", "b.mkv"), backend.childrenOf(large.id).map { it.name }.toSet())
        assertEquals(listOf(large.id), backend.childrenOf(work.id).map { it.id })
    }

    @Test
    fun `collision handling safely skips colliding items without trashing their parent folders or renames with suffix`() = runBlocking {
        val backend = FakeBackend()
        val work = backend.addFolder("work", "Root")
        backend.addFile("existing", "movie.mkv", parentId = work.id)
        val dirA = backend.addFolder("dirA", "A", parentId = work.id)
        val subClean = backend.addFolder("subClean", "Clean", parentId = dirA.id)
        val subDup = backend.addFolder("subDup", "Dup", parentId = dirA.id)
        backend.addFile("clean-file", "unique.mkv", parentId = subClean.id)
        backend.addFile("dup-file", "movie.mkv", parentId = subDup.id)
        val ops = FolderStructureOps(backend)

        // Mode SKIP: skips dup-file, trashes ONLY subClean, keeps subDup and dirA intact!
        val skipPlan = ops.plan(
            workingFolderId = work.id,
            workingFolderName = work.name,
            selectedFolders = listOf(dirA),
            options = FolderStructureOptions(FolderStructureMode.FLATTEN, collisionStrategy = CollisionStrategy.SKIP),
        ).getOrThrow()
        assertEquals(1, skipPlan.skippedCollisions.size)
        assertEquals(listOf("clean-file"), skipPlan.moves.map { it.item.id })
        assertEquals(listOf("subClean"), skipPlan.foldersToTrash.map { it.id }, "Must never trash ancestors of skipped files")

        // Mode RENAME_SUFFIX: renames dup-file -> movie (2).mkv, moves both, and trashes dirA!
        val renamePlan = ops.plan(
            workingFolderId = work.id,
            workingFolderName = work.name,
            selectedFolders = listOf(dirA),
            options = FolderStructureOptions(FolderStructureMode.FLATTEN, collisionStrategy = CollisionStrategy.RENAME_SUFFIX),
        ).getOrThrow()
        assertEquals(0, renamePlan.skippedCollisions.size)
        assertEquals(1, renamePlan.renamedCollisions.size)
        assertEquals("movie (2).mkv", renamePlan.renamedCollisions.single().resolvedName)
        assertEquals(listOf(dirA.id), renamePlan.foldersToTrash.map { it.id })

        val outcome = ops.execute(renamePlan)
        assertTrue(outcome.isSuccess)
        assertEquals(2, outcome.movedCount)
        assertEquals(1, outcome.renamedCount)
        assertEquals(1, outcome.trashedFolderCount)
        assertEquals(setOf("movie.mkv", "unique.mkv", "movie (2).mkv"), backend.childrenOf(work.id).map { it.name }.toSet())
    }

    @Test
    fun `vaulted folders or folders containing vault manifests are safely blocked`() = runBlocking {
        val backend = FakeBackend()
        val work = backend.addFolder("work", "Root")
        val folder = backend.addFolder("vaulted", "Archived", parentId = work.id)
        backend.addFile("m1", ".piko-vault-v1-0123456789abcdef.json", parentId = folder.id)
        val ops = FolderStructureOps(backend)

        val plan = ops.plan(
            workingFolderId = work.id,
            workingFolderName = work.name,
            selectedFolders = listOf(folder),
            options = FolderStructureOptions(FolderStructureMode.UNWRAP),
        ).getOrThrow()
        assertNotNull(plan.blockedReason)
        assertFalse(plan.canExecute)
    }

    @Test
    fun `executing through PikoDriveRepository emits DriveChange Moved and Trashed via applyChange`() = smoke { scope ->
        val server = FakePikPakServer()
        val work = server.addFolder("Workspace")
        val outer = server.addFolder("Outer", parentId = work.id)
        val inner = server.addFolder("Inner", parentId = outer.id)
        val movie = server.addFile("film.mkv", parentId = inner.id, hash = "HASH-FILM")
        val repo = PikoDriveRepository(server.provider(), MemoryPreferences())
        // Warm listings so repository learns parents cleanly.
        repo.listAllFiles(work.id).getOrThrow()

        val observedChanges = mutableListOf<DriveChange>()
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            repo.folderChanges.take(2).toList(observedChanges)
        }

        val ops = FolderStructureOps(repo.asFolderStructureBackend())
        val outerStat = FileStat(kind = FileKind.FOLDER, id = outer.id, parentId = work.id, name = outer.name)
        val plan = ops.plan(
            workingFolderId = work.id,
            workingFolderName = work.name,
            selectedFolders = listOf(outerStat),
            options = FolderStructureOptions(FolderStructureMode.UNWRAP, unwrapSingleChildChain = true),
        ).getOrThrow()
        assertNull(plan.blockedReason)
        assertEquals(listOf(movie.id), plan.moves.map { it.item.id })

        val outcome = ops.execute(plan)
        assertTrue(outcome.isSuccess)
        assertEquals(1, outcome.movedCount)
        assertEquals(1, outcome.trashedFolderCount)
        assertEquals(work.id, server.node(movie.id)?.parentId)
        assertTrue(server.node(outer.id)?.trashed == true)

        collector.join()
        assertTrue(observedChanges.any { it is DriveChange.Moved && movie.id in it.ids && it.to == work.id })
        assertTrue(observedChanges.any { it is DriveChange.Trashed && outer.id in it.ids })
    }
}
