package dev.piko.desktop.update

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 样本在 resources/zsync 下：old.bin 是本机的旧文件，new.bin 在它中间插入、改动过一段，
 * new.bin.zsync 由真的 zsyncmake（0.6.2，-b 512）出。对着它测，读错格式（rsum 取哪几个字节、MD4 截几位、
 * 成对匹配）会当场拼错或一块也认不出，而不是在用户那里悄悄退回整包下载。
 */
class ZsyncTest {
    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/zsync/$name")) { "缺少样本 $name" }.use { it.readBytes() }

    @Test
    fun rebuildsTheNewFileFromTheOldOneAndTheMissingRanges() = runBlocking {
        val newBytes = resource("new.bin")
        val control = ZsyncControl.parse(resource("new.bin.zsync"))
        assertEquals(newBytes.size.toLong(), control.length)

        val dir = Files.createTempDirectory("zsync-test").toFile()
        try {
            val old = File(dir, "old.bin").apply { writeBytes(resource("old.bin")) }
            val plan = planZsync(control, old)
            // 新文件约 25 KB，与旧文件相同的约 23 KB；认出的块不到一半就是匹配出了问题。
            // 按块数看，不看 downloadBytes：文件这么小，缺块之间的空隙都在合并的范围内，会并成一整段
            val found = plan.sources.count { it >= 0 }
            assertTrue(found > control.blockCount / 2, "只认出 $found / ${control.blockCount} 块")

            val target = File(dir, "new.bin")
            val fetched = AtomicLong()
            assembleZsync(plan, old, target) { range, onChunk ->
                val slice = newBytes.copyOfRange(range.first.toInt(), range.last.toInt() + 1)
                fetched.addAndGet(slice.size.toLong())
                onChunk(slice, slice.size)
            }
            assertContentEquals(newBytes, target.readBytes())
            assertEquals(plan.downloadBytes, fetched.get())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun md4MatchesTheRfcVectors() {
        fun md4(text: String) = Md4().digest(text.toByteArray()).toHex()
        assertEquals("31d6cfe0d16ae931b73c59d7e0c089c0", md4(""))
        assertEquals("a448017aaf21d8525fc10ae87aa6729d", md4("abc"))
        assertEquals("e33b4ddc9c38f2199c3e7b164fcc0536", md4("1234567890".repeat(8)))
    }
}
