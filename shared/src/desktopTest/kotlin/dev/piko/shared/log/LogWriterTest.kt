package dev.piko.shared.log

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import kotlinx.io.files.Path

class LogWriterTest {
    private val directory: File = Files.createTempDirectory("piko-log").toFile()
    private val writer = LogWriter(Path(directory.path), TimeZone.UTC)

    // 2026-10-06 12:00:00 UTC
    private val now = 1_791_288_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun line(level: LogLevel, tag: String, message: String, error: Throwable? = null, at: Long = now) =
        LogLine(at, level, tag, message, error)

    private fun String.mainSection() = substringAfter("==== 全部日志")
    private fun String.problemSection() = substringBefore("==== 全部日志")

    @Test
    fun repeatsCollapseAndTheCountLandsWhenInterrupted() {
        repeat(5) { writer.write(line(LogLevel.DEBUG, "Clips", "挑段", at = now + it)) }
        // 同一处抛出的异常每次是新对象，仍算重复；信息不同则不算
        repeat(3) { writer.write(line(LogLevel.WARN, "Proxy", "读取失败", IOException("reset"))) }
        writer.write(line(LogLevel.WARN, "Proxy", "读取失败", IOException("timeout")))
        writer.flush()
        val lines = writer.export().mainSection().lines().filter { ": " in it && !it.startsWith("java.") && !it.startsWith("\t") }

        assertEquals(
            listOf(
                "D Clips: 挑段",
                "D Clips: 上一条又重复 4 次",
                "W Proxy: 读取失败",
                "W Proxy: 上一条又重复 2 次",
                "W Proxy: 读取失败",
            ),
            lines.map { it.substring(24) },
        )
        // 次数行带最后一次重复的时间，看得出这一段持续了多久
        assertTrue(lines[1].startsWith("2026-10-06 12:00:00.004"), lines[1])
    }

    // 批末的 flush 不能结算：间隔着到来的重复每次都单独成批，在那里结算就永远合并不了
    @Test
    fun repeatsSpanningBatchesStillCollapse() {
        repeat(3) {
            writer.write(line(LogLevel.DEBUG, "Clips", "挑段"))
            writer.flush()
        }
        val exported = writer.export()
        assertEquals(1, Regex("D Clips: 挑段").findAll(exported).count(), exported)
        assertTrue("上一条又重复 2 次" in exported, exported)
    }

    @Test
    fun errorsSurviveTheMainLogRollingOver() {
        writer.write(line(LogLevel.ERROR, "Player", "首帧前失败", IOException("boom")))
        val filler = "x".repeat(1_000)
        // 约 6 MB，超过主日志四份文件的上限
        repeat(6_000) { writer.write(line(LogLevel.DEBUG, "Test", "$it $filler")) }
        val exported = writer.export()

        val problems = exported.problemSection()
        val main = exported.mainSection()
        assertTrue(exported.startsWith("==== 警告与错误"), "侧文件放在最前")
        assertTrue("E Player: 首帧前失败" in problems && "java.io.IOException: boom" in problems, problems)
        assertTrue("首帧前失败" !in main, "主日志里的那条应已滚掉")

        val numbers = main.lineSequence().mapNotNull { Regex(""" D Test: (\d+) """).find(it)?.groupValues?.get(1)?.toInt() }.toList()
        assertEquals(5_999, numbers.last(), "最后一条应是最新写入的")
        assertTrue(numbers.zipWithNext().all { (a, b) -> b == a + 1 }, "跨文件拼接后应连续且按时间先后")
        assertTrue(main.length <= 4 * (1 shl 20), "主日志总量不超过四份文件")
    }

    @Test
    fun retentionIsAWeekForAllAndAMonthForProblems() {
        File(directory, "piko.log").writeText(
            "2026-09-26 12:00:00.000 E Old: 十天前\n" +
                "2026-10-01 12:00:00.000 I Recent: 五天前\n",
        )
        File(directory, "piko-errors.log").writeText(
            "2026-08-27 12:00:00.000 E Ancient: 四十天前\n" +
                "2026-09-26 12:00:00.000 E Old: 十天前\n",
        )
        writer.dropExpired(now)
        val exported = writer.export()

        assertTrue("五天前" in exported.mainSection(), "一周内的记录要留着")
        assertTrue("十天前" !in exported.mainSection(), exported)
        assertTrue("十天前" in exported.problemSection(), "错误在侧文件里留一个月")
        assertTrue("四十天前" !in exported, exported)
    }

    @Test
    fun clearEmptiesBothFilesAndForgetsPendingRepeats() {
        writer.write(line(LogLevel.WARN, "Sync", "冲突"))
        writer.write(line(LogLevel.WARN, "Sync", "冲突"))
        writer.clear()
        writer.write(line(LogLevel.INFO, "App", "启动"))
        val exported = writer.export()

        assertTrue("冲突" !in exported && "重复" !in exported, exported)
        assertTrue("I App: 启动" in exported.mainSection(), exported)
    }
}
