package dev.piko.shared.media.cache

import dev.piko.shared.media.testing.FakePikPakCloud
import dev.piko.shared.smoke.smoke
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileCachePoolTest {
    @Test
    fun `completing one copy preserves another paused copy and pruning skips retained and active files`() = smoke { scope ->
        val root = Files.createTempDirectory("piko-cache-pool").toFile()
        val cloud = FakePikPakCloud(ByteArray(262144))
        val client = cloud.provider.currentClient.value!!
        val pool = PikoFileCachePool(scope) { root.resolve("$it.data").path }
        val first = pool.acquire(client, "f1", "GCID", 262144, "one", retained = true, owner = "one")
        pool.remember(client.account, "GCID", 262144, "two")
        first.entry.store.write("key", 0, ByteArray(262144))
        pool.complete(first, "one")
        val saved = File(first.entry.store.path)
        first.release()
        assertTrue(saved.exists(), "另一份暂停下载仍持有这个缓存")
        val orphan = root.resolve("${"a".repeat(64)}.data").apply { writeBytes(byteArrayOf(1)) }
        val unrelated = root.resolve("notes.data").apply { writeBytes(byteArrayOf(2)) }
        pool.prune()
        assertTrue(saved.exists())
        assertFalse(orphan.exists())
        assertTrue(unrelated.exists())
        val playing = pool.acquire(client, "f1", "GCID", 262144, "one")
        pool.discard(client.account, "GCID", 262144, "two")
        pool.prune()
        assertTrue(saved.exists(), "正在播放的文件不能被清理")
        playing.release()
        assertFalse(saved.exists())
        assertFalse(File("${saved.path}.blocks").exists())
    }
}
