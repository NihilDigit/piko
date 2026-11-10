package dev.piko.shared.data

import dev.piko.data.repository.FileCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FolderContentMemoryTest {

    @Test
    fun samplesAndAuxiliaryTablesAreBoundedInMemory() = runBlocking {
        val memory = FolderContentMemory(null, this)
        memory.put("large", List(100) { ChildFile("$it.mkv") })
        assertEquals(40, memory.get("large")!!.size)
        repeat(2_001) {
            memory.markVaulted("$it", true)
            memory.rememberCover("$it", "hash")
            memory.rememberCompleteness("$it", false, emptySet(), true)
        }
        assertEquals(2_000, memory.vaultedFolders.value.size)
        assertEquals(2_000, memory.vaultCovers.value.size)
        assertEquals(2_000, memory.fullyVaultedFolders.first().size)
    }

    @Test
    fun fullArchiveRequiresCompleteChildrenAndNoRemainingFiles() = runBlocking {
        val memory = FolderContentMemory(null, this)
        memory.rememberCompleteness("outer", false, setOf("inner"), true)
        assertEquals(emptySet(), memory.fullyVaultedFolders.first())
        memory.rememberCompleteness("inner", true, emptySet(), true)
        assertEquals(emptySet(), memory.fullyVaultedFolders.first())
        memory.rememberCompleteness("inner", false, emptySet(), true)
        assertEquals(setOf("inner", "outer"), memory.fullyVaultedFolders.first())
        memory.invalidate(setOf("inner"))
        assertEquals(emptySet(), memory.fullyVaultedFolders.first())
    }

    @Test
    fun partialListingsCannotProveFullArchiveAndAccountSwitchClearsProof() = runBlocking {
        val memory = FolderContentMemory(null, this)
        memory.rememberCompleteness("folder", false, emptySet(), false)
        assertEquals(emptySet(), memory.fullyVaultedFolders.first())
        memory.markFullyVaulted(setOf("outer", "inner"), mapOf("inner" to "outer"))
        assertEquals(setOf("outer", "inner"), memory.fullyVaultedFolders.first())
        memory.rememberCompleteness("inner", true, emptySet(), false)
        assertEquals(emptySet(), memory.fullyVaultedFolders.first())
        memory.markFullyVaulted(setOf("folder"), emptyMap())
        memory.switchAccount("other")
        assertEquals(emptySet(), memory.fullyVaultedFolders.first())
    }

    @Test
    fun readsAndIdenticalWritesRefreshLruOrder() = runBlocking {
        val memory = FolderContentMemory(null, this)
        val sample = listOf(ChildFile("Show.mkv"))
        repeat(2_000) { memory.put("$it", sample) }
        assertEquals(sample, memory.get("0"))
        assertEquals("0", memory.state.value.keys.last())
        memory.put("1", sample)
        assertEquals("1", memory.state.value.keys.last())
        memory.put("new", sample)
        assertNull(memory.get("2"))
        assertEquals(sample, memory.get("0"))
        assertEquals(sample, memory.get("1"))
    }

    @Test
    fun invalidationDuringDiskLoadDoesNotRestoreAnOldSample() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = object : PikoCacheStore {
            override suspend fun read(key: String): String? {
                if (!key.startsWith("folder-contents-")) return null
                started.complete(Unit)
                release.await()
                return """[{"id":"folder","files":[{"n":"Old.mkv"}]},{"id":"valid","files":[{"n":"Valid.mkv"}]}]"""
            }
            override suspend fun write(key: String, value: String) = Unit
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val memory = FolderContentMemory(store, scope)
            memory.switchAccount("alice")
            started.await()
            memory.invalidate(setOf("folder"))
            release.complete(Unit)
            assertNull(memory.get("folder"))
            assertEquals(listOf(ChildFile("Valid.mkv")), memory.get("valid"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun memoryEvictsOldSamplesAndInvalidatesChangedFolders() = runBlocking {
        val memory = FolderContentMemory(null, this)
        val sample = listOf(ChildFile("Show - 01.mkv"))
        repeat(2_000) { memory.put("$it", sample) }
        memory.put("0", listOf(ChildFile("Updated.mkv")))
        memory.put("new", sample)
        assertEquals(2_000, memory.state.value.size)
        assertNull(memory.get("1"))
        assertEquals(listOf(ChildFile("Updated.mkv")), memory.get("0"))
        memory.invalidate(setOf("0"))
        assertNull(memory.get("0"))
        assertEquals(sample, memory.get("new"))
        memory.invalidate(null)
        assertEquals(emptyMap(), memory.state.value)
    }

    @Test
    fun concurrentLoadAndInvalidationOfAnEmptyMemoryNeverRestoreTheInvalidatedFolder() = runBlocking {
        withTimeout(10_000) {
            repeat(100) {
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val written = CompletableDeferred<Unit>()
                val store = object : PikoCacheStore {
                    override suspend fun read(key: String): String? {
                        if (!key.startsWith("folder-contents-")) return null
                        started.complete(Unit)
                        release.await()
                        return """[{"id":"folder","files":[{"n":"Old.mkv"}]}]"""
                    }
                    override suspend fun write(key: String, value: String) { written.complete(Unit) }
                }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val memory = FolderContentMemory(store, scope, saveDelayMillis = 0)
                    memory.switchAccount("alice")
                    started.await()
                    assertEquals(emptyMap(), memory.state.value)
                    scope.async {
                        release.complete(Unit)
                        memory.invalidate(setOf("folder"))
                    }.await()
                    // 单写者在账号存续期间常驻，等待实际提交而非所有作用域子任务结束。
                    written.await()
                    assertNull(memory.get("folder"))
                } finally {
                    scope.cancel()
                }
            }
        }
    }

    @Test
    fun clearDuringDiskLoadKeepsOnlyNewSamples() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = object : PikoCacheStore {
            override suspend fun read(key: String): String? {
                if (!key.startsWith("folder-contents-")) return null
                started.complete(Unit)
                release.await()
                return """[{"id":"old","files":[{"n":"Old.mkv"}]}]"""
            }
            override suspend fun write(key: String, value: String) = Unit
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val memory = FolderContentMemory(store, scope)
            memory.switchAccount("alice")
            started.await()
            memory.clear()
            val fresh = listOf(ChildFile("Fresh.mkv"))
            memory.put("new", fresh)
            release.complete(Unit)
            assertEquals(mapOf("new" to fresh), memory.state.value)
        } finally {
            scope.cancel()
        }
    }

    private class MapStore : PikoCacheStore {
        val files = mutableMapOf<String, String>()
        val writes = Channel<String>(Channel.UNLIMITED)
        override suspend fun read(key: String) = files[key]
        override suspend fun write(key: String, value: String) {
            files[key] = value
            writes.send(key)
        }
    }

    @Test
    fun `remembered contents survive a restart for the same account only`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = MapStore()
        val bare = ChildFile("[Grp] Show - 12 [1080p][END]", FileCategory.VIDEO)
        val contents = listOf(bare, ChildFile("Show - 13.mkv"))
        withTimeout(5_000) {
            FolderContentMemory(store, scope, saveDelayMillis = 0).apply {
                switchAccount("alice")
                put("folder", contents)
            }
            store.writes.receive()

            val restarted = FolderContentMemory(store, scope, saveDelayMillis = 0)
            restarted.switchAccount("alice")
            restarted.state.first { it["folder"] == contents }
            assertEquals(contents, restarted.get("folder"))

            restarted.switchAccount("bob")
            assertNull(restarted.get("folder"), "另一个账号的目录内容不该出现")
        }
        scope.cancel()
    }

    @Test
    fun folderFailurePreservesAllTablesAndDoesNotPoisonNextAccount() = runBlocking {
        for (prefix in listOf("folder-contents-", "vault-folders-", "vault-covers-")) {
            val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
            val files = mutableMapOf("folder-contents-bob.json" to """[{"id":"b","files":[{"n":"b.mkv"}]}]""")
            val writes = Channel<String>(Channel.UNLIMITED)
            var failingKey: String? = "${prefix}alice.json"
            var failures = 0
            val store = object : PikoCacheStore {
                override suspend fun read(key: String) = files[key]
                override suspend fun write(key: String, value: String) {
                    if (key == failingKey) {
                        failures++
                        error("disk full")
                    }
                    files[key] = value
                    writes.send(key)
                }
            }
            suspend fun awaitWrite(key: String) {
                while (writes.receive() != key) Unit
            }
            try {
                withTimeout(5_000) {
                    val memory = FolderContentMemory(store, scope, saveDelayMillis = 0)
                    memory.switchAccount("alice")
                    val sample = listOf(ChildFile("a.mkv"))
                    memory.put("a", sample)
                    memory.markVaulted("a", true)
                    memory.rememberCover("a", "HASH")
                    memory.switchAccount("bob")
                    memory.state.first { it["b"] == listOf(ChildFile("b.mkv")) }
                    assertTrue(failures > 0)
                    assertTrue(scope.coroutineContext[Job]!!.isActive)
                    memory.put("fresh", listOf(ChildFile("fresh.mkv")))
                    awaitWrite("vault-covers-bob.json")
                    assertTrue(files.getValue("folder-contents-bob.json").contains("fresh.mkv"))

                    failingKey = null
                    memory.switchAccount("alice")
                    memory.state.first { it["a"] == sample }
                    assertEquals(setOf("a"), memory.vaultedFolders.value)
                    assertEquals(mapOf("a" to "HASH"), memory.vaultCovers.value)
                    awaitWrite("vault-covers-alice.json")
                }
            } finally {
                scope.cancel()
            }
        }
    }
}
