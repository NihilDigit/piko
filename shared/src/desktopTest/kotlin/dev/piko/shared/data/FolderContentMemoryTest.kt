package dev.piko.shared.data

import dev.piko.data.repository.FileCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FolderContentMemoryTest {

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
            restarted.loads.first { it == 1 }
            assertEquals(contents, restarted.get("folder"))

            restarted.switchAccount("bob")
            assertNull(restarted.get("folder"), "另一个账号的目录内容不该出现")
        }
        scope.cancel()
    }
}
