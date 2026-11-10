package dev.piko.shared.data

import dev.piko.shared.smoke.FakePikPakServer
import dev.piko.shared.smoke.MemoryPreferences
import dev.piko.shared.state.vaultTree
import dev.piko.shared.sync.RemoteSettingsStore
import dev.piko.shared.sync.VaultTreeSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VaultTreesTest {

    private val tree = vaultTree(setOf("show"), setOf("s1a", "s2"), mapOf("s1" to "show", "s1a" to "s1", "s2" to "show"))

    @Test
    fun `the archived outer folder is marked while an inner folder still holds entries`() {
        assertEquals(mapOf("show" to setOf("s1a", "s2"), "s1" to setOf("s1a")), tree)
        val listed = setOf("show", "s1", "s1a", "s2")
        assertEquals(setOf("show", "s1", "s1a", "s2"), VaultTrees.marked(setOf("s1a", "s2"), listed, tree))
        assertEquals(setOf("show", "s2"), VaultTrees.marked(setOf("s2"), listed, tree))
        assertEquals(emptySet(), VaultTrees.marked(emptySet(), listed, tree))
        assertEquals(setOf("show", "s1"), VaultTrees.marked(emptySet(), setOf("show"), tree))
    }

    @Test
    fun nestedAggregatesFollowLiveDescendantsWithoutMarkingRootOrCycles() {
        val nested = mapOf("" to setOf("outer"), "outer" to setOf("season"), "season" to setOf("leaf"))
        val listed = setOf("", "outer", "season", "leaf")
        assertEquals(setOf("outer", "season", "leaf"), VaultTrees.marked(setOf("leaf"), listed, nested))
        assertEquals(emptySet(), VaultTrees.marked(emptySet(), listed, nested))
        assertEquals(emptySet(), VaultTrees.marked(emptySet(), setOf("a", "b"), mapOf("a" to setOf("b"), "b" to setOf("a"))))
    }

    private class MemoryRemote : RemoteSettingsStore {
        var text: String? = null
        override suspend fun read(account: String) = text
        override suspend fun write(account: String, text: String, stamp: Long) {
            this.text = text
        }
    }

    @Test
    fun `two devices keep both archives`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val remote = MemoryRemote()
        val clients = FakePikPakServer().provider()
        withTimeout(5_000) {
            val laptop = VaultTrees(null, scope).apply { switchAccount("alice") }
            val desktop = VaultTrees(null, scope).apply { switchAccount("alice") }
            laptop.merge(tree)
            desktop.merge(mapOf("movies" to setOf("m1")))

            VaultTreeSync(clients, remote, laptop, scope, flowOf(true)).syncNow()
            VaultTreeSync(clients, remote, desktop, scope, flowOf(true)).syncNow()
            VaultTreeSync(clients, remote, laptop, scope, flowOf(true)).syncNow()

            val both = tree + mapOf("movies" to setOf("m1"))
            assertEquals(both, laptop.flow.value)
            assertEquals(both, desktop.flow.value)
            assertEquals(both, VaultTrees.decode(remote.text!!))
        }
        scope.cancel()
    }

    @Test
    fun `an archive recorded during the first remote write is included in the following push`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val provider = FakePikPakServer().provider()
            val trees = PikoDriveRepository(provider, MemoryPreferences()).vaultTrees
            trees.merge(mapOf("root" to setOf("first")))
            val entered = Channel<Unit>(1)
            val release = Channel<Unit>(1)
            val written = Channel<String>(Channel.UNLIMITED)
            var remoteText: String? = null
            var writes = 0
            val remote = object : RemoteSettingsStore {
                override suspend fun read(account: String) = remoteText
                override suspend fun write(account: String, text: String, stamp: Long) {
                    if (++writes == 1) { entered.send(Unit); release.receive() }
                    remoteText = text
                    written.send(text)
                }
            }
            VaultTreeSync(provider, remote, trees, scope, flowOf(true)).start()
            withTimeout(5_000) { entered.receive() }
            trees.merge(mapOf("root" to setOf("second")))
            release.send(Unit)
            val first = assertNotNull(VaultTrees.decode(withTimeout(5_000) { written.receive() }))
            val second = assertNotNull(VaultTrees.decode(withTimeout(7_000) { written.receive() }))
            assertEquals(setOf("first"), first["root"])
            assertEquals(setOf("first", "second"), second["root"])
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `vault stars lww merge respects unstar tombstones and is strictly commutative on equal timestamps`() {
        val starOnDeviceA = mapOf(
            "e1" to VaultStarRecord("e1", "f1", "a.mkv", 10L, "G1", starred = true, updatedAt = 1_000L),
            "e2" to VaultStarRecord("e2", "f1", "renamed.mkv", 20L, "G2", starred = true, updatedAt = 1_000L, restoredFileId = "real-2"),
        )
        val unstarOnDeviceB = mapOf(
            "e1" to VaultStarRecord("e1", "f1", "a.mkv", 10L, "G1", starred = false, updatedAt = 2_000L),
            "e2" to VaultStarRecord("e2", "f1", "old.mkv", 20L, "G2", starred = true, updatedAt = 1_000L, restoredFileId = null),
        )
        val ab = VaultStars.union(starOnDeviceA, unstarOnDeviceB, nowMs = 3_000L)
        val ba = VaultStars.union(unstarOnDeviceB, starOnDeviceA, nowMs = 3_000L)
        assertEquals(ab, ba)
        assertFalse(ab.getValue("e1").starred)
        assertTrue(ab.getValue("e2").starred)
        assertEquals("real-2", ab.getValue("e2").restoredFileId)
    }
}
