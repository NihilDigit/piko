package dev.piko.shared.log

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class PikoLogTest {

    @Test
    fun accountsAndLocalPathsNeverReachTheFile(): Unit = runBlocking {
        PikoLog.install(Files.createTempDirectory("piko-log").toFile().path)
        val secretName = "某部作品 第01话 [1080p]"
        PikoLog.w(
            "Redact",
            "服务端说明：user someone.name+tag@example.com 已被限制，手机号 13812345678",
            java.io.FileNotFoundException("C:\\Users\\alice\\Downloads\\Piko\\$secretName.mkv (系统找不到指定的文件。)"),
        )
        // 文件名里允许空格，路径一直算到行尾，所以一行里只放一个路径
        PikoLog.w("Redact", "Android 路径 /storage/emulated/0/Download/$secretName.mp4")
        PikoLog.w("Redact", "content://media/external/video/42")
        PikoLog.w("Redact", "HTTP 403: https://dl-a10b.mypikpak.com/download/?fid=secretFid&g=SECRETGCID&sign=secretSign 未授权")
        val exported = PikoLog.export().lineSequence().filter { "Redact" in it || "FileNotFound" in it }.joinToString("\n")

        listOf("someone.name", "example.com", "13812345678", "alice", secretName, "emulated", "secretFid", "SECRETGCID", "secretSign").forEach {
            assertTrue(it !in exported, "日志里不该出现「$it」：\n$exported")
        }
        // 扩展名与 Java 异常后面的原因保留，排查要看
        assertTrue("<路径>.mkv (系统找不到指定的文件。)" in exported, exported)
        assertTrue("<路径>.mp4" in exported, exported)
    }

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
        // 不删目录：PikoLog 是全局的，只装一次，同一进程里别的用例还往这里写
    }
}
