package dev.piko.shared.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountCacheHandoffFailureTest {
    private class FailingStore : PikoCacheStore {
        val files = mutableMapOf<String, String>()
        val writes = Channel<String>(Channel.UNLIMITED)
        var failingKey: String? = null
        var failures = 0

        override suspend fun read(key: String) = files[key]

        override suspend fun write(key: String, value: String) {
            if (key == failingKey) {
                failures++
                error("disk full")
            }
            files[key] = value
            writes.send(key)
        }

        suspend fun awaitWrite(key: String) {
            while (writes.receive() != key) Unit
        }
    }

    @Test
    fun sourceFailureDoesNotPoisonNextAccountAndDirtyAccountCanRetry(): Unit = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val store = FailingStore()
        val a = mapOf("A:1" to SourceRecord("source-a", "a.mkv", 1))
        val b = mapOf("B:2" to SourceRecord("source-b", "b.mkv", 2))
        store.files["sources-bob.json"] = SourceLedger.encode(b)
        store.failingKey = "sources-alice.json"
        try {
            withTimeout(5_000) {
                val ledger = SourceLedger(store, scope, saveDelayMillis = 0)
                ledger.switchAccount("alice")
                ledger.merge(a)
                ledger.switchAccount("bob")
                ledger.flow.first { it == b }
                assertTrue(store.failures > 0)
                assertTrue(scope.coroutineContext[Job]!!.isActive)
                val fresh = mapOf("C:3" to SourceRecord("source-c", "c.mkv", 3))
                ledger.merge(fresh)
                store.awaitWrite("sources-bob.json")
                assertEquals(b + fresh, SourceLedger.decode(store.files.getValue("sources-bob.json")))

                store.failingKey = null
                ledger.switchAccount("alice")
                ledger.flow.first { it == a }
                store.awaitWrite("sources-alice.json")
                assertEquals(a, SourceLedger.decode(store.files.getValue("sources-alice.json")))
                val restarted = SourceLedger(store, scope, saveDelayMillis = 0)
                restarted.switchAccount("alice")
                restarted.flow.first { it == a }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun folderFailurePreservesAllTablesAndDoesNotPoisonNextAccount() = runBlocking {
        // 三份文件中任一提交失败，都必须保留整个账号快照以便重试。
        for (prefix in listOf("folder-contents-", "vault-folders-", "vault-covers-")) {
            val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
            val store = FailingStore()
            store.files["folder-contents-bob.json"] = """[{"id":"b","files":[{"n":"b.mkv"}]}]"""
            store.failingKey = "${prefix}alice.json"
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
                    assertTrue(store.failures > 0)
                    assertTrue(scope.coroutineContext[Job]!!.isActive)
                    memory.put("fresh", listOf(ChildFile("fresh.mkv")))
                    store.awaitWrite("vault-covers-bob.json")
                    assertTrue(store.files.getValue("folder-contents-bob.json").contains("fresh.mkv"))

                    store.failingKey = null
                    memory.switchAccount("alice")
                    memory.state.first { it["a"] == sample }
                    assertEquals(setOf("a"), memory.vaultedFolders.value)
                    assertEquals(mapOf("a" to "HASH"), memory.vaultCovers.value)
                    store.awaitWrite("vault-covers-alice.json")
                    val restarted = FolderContentMemory(store, scope, saveDelayMillis = 0)
                    restarted.switchAccount("alice")
                    restarted.state.first { it["a"] == sample }
                    assertEquals(setOf("a"), restarted.vaultedFolders.value)
                    assertEquals(mapOf("a" to "HASH"), restarted.vaultCovers.value)
                }
            } finally {
                scope.cancel()
            }
        }
    }
}
