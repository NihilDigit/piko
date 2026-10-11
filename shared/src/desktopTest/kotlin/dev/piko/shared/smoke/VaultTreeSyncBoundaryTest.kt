package dev.piko.shared.smoke

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.VaultTrees
import dev.piko.shared.sync.RemoteSettingsStore
import dev.piko.shared.sync.VaultTreeSync
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class VaultTreeSyncBoundaryTest {
    @Test
    fun `an archive recorded during the first remote write is included in the following push`() = smoke { scope ->
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
    }
}
