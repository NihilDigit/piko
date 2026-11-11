package dev.piko.shared.log

import dev.piko.shared.data.LocalLocation
import dev.piko.shared.data.VaultEntry
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.io.files.Path

class LogWriterTest {
    private val directory: File = Files.createTempDirectory("piko-log").toFile()
    private val writer = LogWriter(Path(directory.path), TimeZone.UTC)

    // 2026-10-06 12:00:00 UTC
    private val now = 1_791_288_000_000L

    private fun line(level: LogLevel, tag: String, message: String, error: Throwable? = null, at: Long = now) =
        LogLine(at, level, tag, message, error)

    private fun String.mainSection() = substringAfter("==== Full Log")
    private fun String.problemSection() = substringBefore("==== Full Log")

    @Test
    fun repeatsCollapseAndTheCountLandsWhenInterrupted() {
        repeat(5) { writer.write(line(LogLevel.DEBUG, "Clips", "Pick segment", at = now + it)) }
        repeat(3) { writer.write(line(LogLevel.WARN, "Proxy", "Read failed", IOException("reset"))) }
        writer.write(line(LogLevel.WARN, "Proxy", "Read failed", IOException("timeout")))
        writer.write(line(LogLevel.WARN, "Proxy", "Read failed", IOException("timeout", IOException("broken pipe"))))
        writer.flush()
        val lines = writer.export().mainSection().lines().filter { ": " in it && !it.startsWith("java.") && !it.startsWith("\t") && !it.startsWith("Caused by:") }

        assertEquals(
            listOf(
                "D Clips: Pick segment",
                "D Clips: Previous entry repeated 4 more times",
                "W Proxy: Read failed",
                "W Proxy: Previous entry repeated 2 more times",
                "W Proxy: Read failed",
                "W Proxy: Read failed",
            ),
            lines.map { it.substring(24) },
        )
        assertTrue(lines[1].startsWith("2026-10-06 12:00:00.004"), lines[1])
    }

    @Test
    fun interleavedDebugDoesNotBreakWarnDeduplicationInProblemLog() {
        repeat(4) { i ->
            writer.write(line(LogLevel.WARN, "Net", "Probe timed out", at = now + i * 100L))
            writer.write(line(LogLevel.DEBUG, "Clips", "Tick $i", at = now + i * 100L + 10L))
        }
        val exported = writer.export()
        val problems = exported.problemSection()
        val main = exported.mainSection()

        assertEquals(1, Regex("W Net: Probe timed out").findAll(problems).count(), problems)
        assertTrue("W Net: Previous entry repeated 3 more times" in problems, problems)
        assertEquals(4, Regex("W Net: Probe timed out").findAll(main).count(), main)
    }

    @Test
    fun repeatsSpanningBatchesStillCollapse() {
        repeat(3) {
            writer.write(line(LogLevel.DEBUG, "Clips", "Pick segment"))
            writer.flush()
        }
        val exported = writer.export()
        assertEquals(1, Regex("D Clips: Pick segment").findAll(exported).count(), exported)
        assertTrue("Previous entry repeated 2 more times" in exported, exported)
    }

    @Test
    fun errorsSurviveTheMainLogRollingOver() {
        writer.write(line(LogLevel.ERROR, "Player", "Failed before first frame", IOException("boom")))
        val filler = "x".repeat(1_000)
        repeat(6_000) { writer.write(line(LogLevel.DEBUG, "Test", "$it $filler")) }
        val exported = writer.export()

        val problems = exported.problemSection()
        val main = exported.mainSection()
        assertTrue(exported.startsWith("==== Warnings & Errors"))
        assertTrue("E Player: Failed before first frame" in problems && "java.io.IOException: boom" in problems, problems)
        assertTrue("Failed before first frame" !in main)

        val numbers = main.lineSequence().mapNotNull { Regex(""" D Test: ([0-9]+) """).find(it)?.groupValues?.get(1)?.toInt() }.toList()
        assertEquals(5_999, numbers.last())
        assertTrue(numbers.zipWithNext().all { (a, b) -> b == a + 1 })
        assertTrue(main.length <= 4 * (1 shl 20))
    }

    @Test
    fun coldStartRotatesPreExistingFullLogFile() {
        val freshDir = Files.createTempDirectory("piko-log-cold").toFile()
        val existing = File(freshDir, "piko.log")
        existing.writeText("2026-10-06 11:00:00.000 I Boot: " + "a".repeat((1 shl 20) + 128) + "\n")
        val coldWriter = LogWriter(Path(freshDir.path), TimeZone.UTC)
        coldWriter.write(line(LogLevel.INFO, "Boot", "Cold start entry"))
        coldWriter.flush()

        assertTrue(File(freshDir, "piko.1.log").exists(), "Expected oversized piko.log to rotate on first write")
        assertTrue(File(freshDir, "piko.log").length() < 1024L)
        assertTrue("Cold start entry" in coldWriter.export().mainSection())
    }

    @Test
    fun retentionIsAWeekForAllAndAMonthForProblems() {
        File(directory, "piko.log").writeText(
            "2026-09-26 12:00:00.000 E Old: ten days ago\n" +
                "2026-10-01 12:00:00.000 I Recent: five days ago\n",
        )
        File(directory, "piko-errors.log").writeText(
            "2026-08-27 12:00:00.000 E Ancient: forty days ago\n" +
                "2026-09-26 12:00:00.000 E Old: ten days ago\n",
        )
        writer.dropExpired(now)
        val exported = writer.export()

        assertTrue("five days ago" in exported.mainSection())
        assertTrue("ten days ago" !in exported.mainSection(), exported)
        assertTrue("ten days ago" in exported.problemSection())
        assertTrue("forty days ago" !in exported, exported)
    }

    @Test
    fun clearEmptiesBothFilesAndForgetsPendingRepeats() {
        writer.write(line(LogLevel.WARN, "Sync", "Conflict"))
        writer.write(line(LogLevel.WARN, "Sync", "Conflict"))
        writer.clear()
        writer.write(line(LogLevel.INFO, "App", "Started"))
        val exported = writer.export()

        assertTrue("Conflict" !in exported && "repeated" !in exported, exported)
        assertTrue("I App: Started" in exported.mainSection(), exported)
    }

    @Test
    fun accountsPhonesTokensUrlsAndLocalPathsAreRedactedAndIdempotent(): Unit = runBlocking {
        val echoed = mutableListOf<Pair<String, String?>>()
        var clearedCount = 0
        PikoLog.install(
            directory = Files.createTempDirectory("piko-log-redact").toFile().path,
            extraExport = { "\n==== Desktop Update History ====\nApplied from C:\\Users\\alice\\Updates\\patch.zip" },
            onClear = { clearedCount++ },
            echo = { _, tag, msg, err ->
                if (tag == "Redact") echoed += msg to err?.stackTraceToString()
            },
        )
        val secretName = "某部作品 第01话 [1080p]"
        PikoLog.w(
            "Redact",
            "Server note: user someone.name+tag@mail-secret.org restricted, file size 14973770608, phone +86 138-1234-5678 / 86-13987654321 / 18612345678",
            FileNotFoundException("C:\\Users\\alice\\Downloads\\Piko\\$secretName.mkv (系统找不到指定的文件。)"),
        )
        PikoLog.w("Redact", "Android path /storage/emulated/0/Download/$secretName.mp4")
        PikoLog.w("Redact", "Linux mount /media/alice/USB Drive/$secretName.mkv and /run/user/1000/gvfs/a.pdf and /root/secret.log")
        PikoLog.w("Redact", "UNC \\\\NAS01\\PrivateShare\\$secretName.mkv failed: disk full")
        PikoLog.w("Redact", "Read /Users/alice/a.mkv 失败：磁盘已满")
        PikoLog.w("Redact", "content://media/external/video/42 and file:///Users/alice/secret.flac")
        PikoLog.w("Redact", "HTTP 403: (https://dl-a10b.mypikpak.com/download/?fid=secretFid&g=SECRETGCID&sign=secretSign) and https://api.example.com?token=secretQuery")
        PikoLog.w("Redact", "WebDAV https://davUser:davSecret@192.168.1.2:8080/dav/Movies/a.mkv vs loopback http://127.0.0.1:7890/pac and http://[::1]:1080")
        PikoLog.w("Redact", "Magnet magnet:?xt=urn:btih:0123456789abcdef&dn=Secret.Movie.2026&tr=https://tracker.example/announce")
        PikoLog.w("Redact", """Auth {"access_token":"eyJSecretAccess","refresh_token":"secRefresh","password":"myPassword"} Authorization: Bearer bearerSecret123 refreshToken=kvRefresh456""")
        PikoLog.w("Redact", "Metrics range=0+14973770608 delivered=15234567890 B")

        val fullExport = PikoLog.export()
        val exported = fullExport.lineSequence().filter { "Redact" in it || "FileNotFound" in it || "Applied from" in it }.joinToString("\n")

        listOf(
            "someone.name", "mail-secret.org", "138-1234-5678", "13987654321", "18612345678",
            "alice", secretName, "emulated", "NAS01", "PrivateShare",
            "secretFid", "SECRETGCID", "secretSign", "secretQuery",
            "davUser", "davSecret", "Secret.Movie.2026",
            "eyJSecretAccess", "secRefresh", "myPassword", "bearerSecret123", "kvRefresh456",
        ).forEach {
            assertFalse(it in exported, "Redaction leaked '$it':\n$exported")
        }

        // Console echo must also receive redacted message & stack trace
        assertTrue(echoed.isNotEmpty())
        val (firstEchoMsg, firstEchoErr) = echoed.first()
        assertFalse("someone.name" in firstEchoMsg, firstEchoMsg)
        assertTrue("<email>" in firstEchoMsg && "<phone>" in firstEchoMsg, firstEchoMsg)
        assertFalse("alice" in firstEchoErr.orEmpty(), firstEchoErr)
        assertTrue("<path>.mkv" in firstEchoErr.orEmpty(), firstEchoErr)

        // Numeric non-phones, loopback URLs, and trailing suffixes must survive intact
        assertTrue("14973770608" in exported, exported)
        assertTrue("15234567890 B" in exported, exported)
        assertTrue("http://127.0.0.1:7890/pac" in exported && "http://[::1]:1080" in exported, exported)
        assertTrue("https://192.168.1.2:8080/<redacted>" in exported, exported)
        assertTrue("(https://dl-a10b.mypikpak.com/<redacted>)" in exported, exported)
        assertTrue("https://api.example.com/<redacted>" in exported, exported)
        assertTrue("dn=<redacted>" in exported && "xt=urn:btih:0123456789abcdef" in exported, exported)
        assertTrue("<path>.mkv (系统找不到指定的文件。)" in exported, exported)
        assertTrue("Linux mount <path>.mkv and <path>.pdf and <path>.log" in exported, exported)
        assertTrue("UNC <path>.mkv failed: disk full" in exported, exported)
        assertTrue("Read <path>.mkv 失败：磁盘已满" in exported, exported)
        assertTrue("==== Desktop Update History ====" in fullExport && "Applied from <path>.zip" in fullExport, fullExport)

        // Idempotency guarantee
        assertEquals(exported, PikoLog.redact(exported))

        PikoLog.clear()
        assertEquals(1, clearedCount)
    }

    @Test
    fun logHelpersFormatEnglishIdentitiesAndSkipCancellation() = runBlocking {
        val logDir = Files.createTempDirectory("piko-log-helpers").toFile()
        PikoLog.install(logDir.path)

        assertEquals("file abc123 (.mkv)", logFile("abc123", "Movie.MKV"))
        assertEquals("file ?", logFile(null, "README"))
        val vaultId = VaultEntry(id = "entry99", name = "clip.mp4", size = 100L, gcid = "GCID", addedAt = 0L).virtualId
        assertEquals("file vault entry99 (.mp4)", logFile(vaultId, "clip.mp4"))
        val localId = LocalLocation("usb-1", listOf("secret", "ep1.mkv"), directory = false).id
        assertEquals("file local usb-1 (.mkv)", logFile(localId, "ep1.mkv"))
        assertEquals("folder root", logFolder(""))
        assertEquals("folder ?", logFolder(null))
        assertEquals("folder folder-7", logEntry("folder-7", "Ignored", isFolder = true))
        assertEquals("file file-7 (.srt)", logEntry("file-7", "sub.srt", isFolder = false))

        var uiShown: String? = null
        Result.failure<Unit>(CancellationException("cancelled"))
            .logFailure("TestCancel", "Should not log cancellation")
            .reportFailure("TestCancel", "保存", "Should not report cancellation") { uiShown = it }
        assertEquals(null, uiShown)

        Result.failure<Unit>(IOException("disk error"))
            .reportFailure("TestReport", "保存", "Failed to save item") { uiShown = it }
        assertTrue(uiShown != null)

        val exported = PikoLog.export()
        assertFalse("TestCancel" in exported, exported)
        assertTrue("W TestReport: Failed to save item" in exported, exported)
    }
}
