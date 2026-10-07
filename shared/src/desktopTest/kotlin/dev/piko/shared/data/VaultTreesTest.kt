package dev.piko.shared.data

import dev.piko.shared.smoke.FakePikPakServer
import dev.piko.shared.state.vaultTree
import dev.piko.shared.sync.RemoteSettingsStore
import dev.piko.shared.sync.VaultTreeSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class VaultTreesTest {

    // 选了「剧集」归档，条目写在「剧集/第一季/上」与「剧集/第二季」；「剧集」与「第一季」自己只有子文件夹
    private val tree = vaultTree(setOf("show"),setOf("s1a", "s2"), mapOf("s1" to "show", "s1a" to "s1", "s2" to "show"))

    @Test
    fun `the archived outer folder is marked while an inner folder still holds entries`() {
        assertEquals(mapOf("show" to setOf("s1a", "s2"), "s1" to setOf("s1a")), tree)
        val listed = setOf("show", "s1", "s1a", "s2")
        assertEquals(setOf("show", "s1", "s1a", "s2"), VaultTrees.marked(setOf("s1a", "s2"), listed, tree))
        // 逐项恢复掉「上」：「第一季」下面再没有放着条目的，跟着不标；「剧集」还有第二季
        assertEquals(setOf("show", "s2"), VaultTrees.marked(setOf("s2"), listed, tree))
        assertEquals(emptySet(), VaultTrees.marked(emptySet(), listed, tree))
        // 另一台设备归档的、这里还没进去看过：当作还有
        assertEquals(setOf("show", "s1"), VaultTrees.marked(emptySet(), setOf("show"), tree))
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
        // 同步只要一个当前账号，SDK 的请求走不到
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
}
