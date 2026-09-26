package dev.piko.shared.log

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class PikoLogTest {

    @Test
    fun rotationKeepsTheNewestLinesInOrderWithinTheCap(): Unit = runBlocking {
        val directory = Files.createTempDirectory("piko-log").toFile()
        PikoLog.install(directory.path)
        // 约 6 MB，超过四份文件的上限：最早的要被滚掉，留下的要按先后接好
        val filler = "x".repeat(1_000)
        repeat(6_000) { PikoLog.d("Test", "$it $filler") }
        val exported = PikoLog.export()

        val numbers = exported.lineSequence().mapNotNull { Regex(""" D Test: (\d+) """).find(it)?.groupValues?.get(1)?.toInt() }.toList()
        assertTrue(numbers.last() == 5_999, "最后一条应是最新写入的")
        assertTrue(numbers.zipWithNext().all { (a, b) -> b == a + 1 }, "跨文件拼接后应连续且按时间先后")
        assertTrue(numbers.first() > 0, "超出上限的最早日志应已滚掉")
        assertTrue(exported.length <= 4 * (1 shl 20), "导出总量不超过四份文件")
        directory.deleteRecursively()
    }
}
