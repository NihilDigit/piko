package dev.piko.shared.state

import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.VaultEdits
import dev.piko.shared.data.VaultEntry
import dev.piko.shared.data.VaultFolderIo
import dev.piko.shared.data.VaultStore
import dev.piko.shared.data.isVaulted
import dev.piko.shared.smoke.FakePikPakServer
import dev.piko.shared.smoke.MemoryPreferences
import dev.piko.shared.smoke.awaitUntil
import dev.piko.shared.smoke.smoke
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.TaskPhase
import io.github.nihildigit.pikpak.batchDelete
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class VideoDiscoveryTest {
    private fun folder(id: String) = FileStat(id = id, name = id, kind = FileKind.FOLDER)
    private fun video(id: String) = FileStat(id = id, name = "$id.mkv", kind = FileKind.FILE)

    @Test fun breadthFirstTraversalIsDemandDrivenAndDoesNotRevisitFolders() = runBlocking {
        val tree = mapOf(
            "root" to listOf(folder("a"), folder("b"), folder("a")),
            "a" to listOf(folder("a1"), folder("root"), video("a-video")),
            "b" to listOf(folder("b1")),
            "a1" to emptyList(),
            "b1" to emptyList(),
        )
        val reads = mutableListOf<String>()
        val discovery = VideoDiscovery("root", { id -> reads += id; Result.success(tree.getValue(id)) }, Random(3))
        assertTrue(reads.isEmpty())
        assertEquals("root", assertNotNull(discovery.next()).folderId)
        assertEquals(listOf("root"), reads)
        val batches = mutableListOf<VideoFolderBatch>()
        while (discovery.hasPending) batches += assertNotNull(discovery.next())
        assertEquals(setOf("a", "b"), batches.take(2).map { it.folderId }.toSet())
        assertEquals(setOf("a1", "b1"), batches.drop(2).map { it.folderId }.toSet())
        assertEquals(5, reads.size)
        assertTrue(discovery.isComplete)
        assertNull(discovery.next())
    }

    @Test fun failureIsVisibleAndTheFailedFolderCanBeRetried() = runBlocking {
        var fail = true
        val discovery = VideoDiscovery("root", {
            if (fail) Result.failure(IllegalStateException("offline")) else Result.success(listOf(video("one")))
        })
        assertNotNull(assertNotNull(discovery.next()).failure)
        assertFalse(discovery.hasPending)
        assertFalse(discovery.isComplete)
        assertEquals(setOf("root"), discovery.failedFolders.keys)
        fail = false
        discovery.retryFailed()
        assertEquals("one", assertNotNull(discovery.next()).candidates.single().id)
        assertTrue(discovery.isComplete)
        assertTrue(discovery.failedFolders.isEmpty())
    }

    @Test fun refreshingAddsANewSubtreeWithoutRestartingAlreadyExploredFolders() = runBlocking {
        val contents = mutableMapOf("root" to listOf(folder("old")), "old" to listOf(video("one")))
        val reads = mutableListOf<String>()
        val discovery = VideoDiscovery("root", { id -> reads += id; Result.success(contents.getValue(id)) })
        discovery.next()
        discovery.next()
        assertTrue(discovery.isComplete)
        contents["root"] = listOf(folder("old"), folder("new"))
        contents["new"] = listOf(video("two"))
        discovery.refreshFolder("root")
        assertTrue(discovery.hasPending)
        assertEquals("new", discovery.next()?.folderId)
        assertEquals(listOf("root", "old", "root", "new"), reads)
        assertTrue(discovery.isComplete)
    }

    @Test fun cancellingAnInFlightFolderDoesNotLoseItOrAdvanceTraversal() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val reads = mutableListOf<String>()
        val discovery = VideoDiscovery("root", { id ->
            reads += id
            entered.complete(Unit)
            released.await()
            Result.success(listOf(folder("child")))
        })
        val request = async(start = CoroutineStart.UNDISPATCHED) { discovery.next() }
        entered.await()
        request.cancelAndJoin()
        assertEquals(listOf("root"), reads)
        assertTrue(discovery.hasPending)
        assertTrue(discovery.failedFolders.isEmpty())
        released.complete(Unit)
        assertEquals("root", assertNotNull(discovery.next()).folderId)
        assertEquals(listOf("root", "root"), reads)
    }

    @Test fun emptyAndSingleFoldersFinishAndUnknownDurationRemainsPlayable() = runBlocking {
        val empty = VideoDiscovery("empty", { Result.success(emptyList()) })
        assertTrue(assertNotNull(empty.next()).candidates.isEmpty())
        assertTrue(empty.isComplete)
        val single = VideoDiscovery("one", { Result.success(listOf(video("unknown"))) })
        assertEquals("unknown", assertNotNull(single.next()).candidates.single().id)
        assertTrue(single.isComplete)
    }

    @Test fun pendingUploadsAndTrashedFilesAreNotDiscoveredAsVideos() = runBlocking {
        val batch = analyzeVideoFolder("root", listOf(
            video("ready"), video("uploading").copy(phase = TaskPhase.PENDING), video("deleted").copy(trashed = true),
        ))
        assertEquals(listOf("ready"), batch.candidates.map { it.id })
    }

    @Test fun repositoryBoundaryIncludesVaultMetadataAndHidesInternalFolders() = smoke { _ ->
        val server = FakePikPakServer()
        val hidden = server.addFolder(".piko")
        server.addVideo("secret.mkv", hidden.id)
        val visible = server.addFolder("Show")
        server.addVideo("short.mkv", visible.id, durationSeconds = 5.0)
        val repo = repositoryWithVault(server)
        val archived = VaultEntry.create("archived.mkv", 100, "VAULT-HASH", "magnet:?xt=urn:btih:source", 0)
        repo.vault.update(visible.id, VaultEdits.add(listOf(archived))).getOrThrow()
        val discovery = VideoDiscovery(repo, assertNotNull(repo.currentAccount), "")
        val root = assertNotNull(discovery.next())
        assertEquals(listOf(visible.id), root.files.filter { it.isFolder }.map { it.id })
        val batch = assertNotNull(discovery.next())
        val vaulted = batch.files.single { it.isVaulted }
        assertEquals(archived.source, vaulted.sourceUrl)
        assertEquals(archived.gcid, vaulted.hash)
        assertTrue(vaulted.thumbnailLink.isNotEmpty())
        assertTrue(batch.candidates.any { it.id == vaulted.id })
        assertTrue(discovery.isComplete)
    }

    @Test fun clientIsFixedAtDiscoveryCreationAndDoesNotReadTheNextAccount() = smoke { _ ->
        val first = FakePikPakServer("a")
        val second = FakePikPakServer("b")
        val provider = object : PikoClientProvider {
            override val currentClient = MutableStateFlow<PikPakClient?>(first.client(account = "a@piko.dev"))
        }
        val repo = PikoDriveRepository(provider)
        val discovery = VideoDiscovery(repo, "a@piko.dev", "")
        provider.currentClient.value = second.client(account = "b@piko.dev")
        assertFailsWith<CancellationException> { discovery.next() }
        assertEquals(0, second.count("GET /drive/v1/files"), "旧遍历不能向新账号列目录")
        assertTrue(repo.folderIndex.flow.value.nodes.isEmpty())
    }

    @Test fun switchingAccountsDuringAListingDoesNotPublishOldFoldersIntoTheNewIndex() = smoke { scope ->
        val first = FakePikPakServer("a")
        first.addFolder("old-account-folder")
        first.apiDelayMs = 100
        val second = FakePikPakServer("b")
        val provider = object : PikoClientProvider {
            override val currentClient = MutableStateFlow<PikPakClient?>(first.client(account = "a@piko.dev"))
        }
        val repo = PikoDriveRepository(provider)
        val discovery = VideoDiscovery(repo, "a@piko.dev", "")
        val request = scope.async { discovery.next() }
        awaitUntil("旧账号目录请求在途") { first.count("GET /drive/v1/files") > 0 }
        provider.currentClient.value = second.client(account = "b@piko.dev")
        assertFailsWith<CancellationException> { request.await() }
        assertEquals(0, second.count("GET /drive/v1/files"), "额度刷新可以继续，旧遍历不能向新账号列目录")
        assertTrue(repo.folderIndex.flow.value.nodes.isEmpty())
    }

    private fun repositoryWithVault(server: FakePikPakServer) = object : PikoDriveRepository(server.provider(), MemoryPreferences()) {
        override val vault = VaultStore(object : VaultFolderIo {
            override suspend fun list(folderId: String) = listAllFiles(folderId).getOrThrow()
            override suspend fun read(fileId: String) = assertNotNull(server.node(fileId)).content
            override suspend fun upload(folderId: String, name: String, bytes: ByteArray): String =
                server.addFile(name, folderId, content = bytes).id
            override suspend fun delete(ids: List<String>) { server.client().batchDelete(ids) }
        }, confirmDelay = Duration.ZERO)
    }
}
