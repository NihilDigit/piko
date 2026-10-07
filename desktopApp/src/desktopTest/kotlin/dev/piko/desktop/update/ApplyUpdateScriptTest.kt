package dev.piko.desktop.update

import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume

/**
 * 真跑 apply-update.ps1（Windows PowerShell 5.1，与用户机器上同一个），对着临时目录里的假安装目录。
 * 只在 Windows 上跑。重启应用用一个 .cmd 顶替，它留下 started.txt。
 */
class ApplyUpdateScriptTest {
    private val root: File = Files.createTempDirectory("piko-apply").toFile()
    private val install = root.resolve("install")
    private val staging = root.resolve("staging")
    private val files = staging.resolve("files")
    private val started = install.resolve("started.txt")
    private val failed = staging.resolve("failed")
    private val journal = install.resolve(".piko-update.journal")

    @BeforeTest
    fun windowsOnly() {
        Assume.assumeTrue("更新脚本只在 Windows 上用", System.getProperty("os.name").startsWith("Windows"))
        install.resolve("app").mkdirs()
        files.resolve("app").mkdirs()
        install.resolve("relaunch.cmd").writeText("@echo started> \"%~dp0started.txt\"\r\n")
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun installed(path: String, content: String) = install.resolve(path).apply { parentFile.mkdirs(); writeText(content) }

    private fun stage(path: String, content: String) = files.resolve(path).apply { parentFile.mkdirs(); writeText(content) }

    private fun text(path: String) = install.resolve(path).readText()

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    /** 本机 1.0 换到 2.0：a.jar 与 Piko.cfg 换掉，b-2.jar 新增，b-1.jar 不在新版里。 */
    private fun standardUpdate() {
        installed("app/a.jar", "a 1")
        installed("app/b-1.jar", "b 1")
        installed("app/Piko.cfg", "cfg 1")
        stage("app/a.jar", "a 2")
        stage("app/b-2.jar", "b 2")
        stage("app/Piko.cfg", "cfg 2")
    }

    private fun run(mode: String = "patch", processId: Long = exitedPid(), waitSeconds: Int = 120): Int {
        val script = root.resolve("apply-update.ps1")
        javaClass.getResourceAsStream("/update/apply-update.ps1")!!.use { input -> script.outputStream().use { input.copyTo(it) } }
        val checksums = staging.resolve("staged.sha256")
        val keep = staging.resolve("keep.txt")
        if (mode == "patch") {
            checksums.writeText(files.walkTopDown().filter { it.isFile }.joinToString("") { "${sha256(it)}  files/${it.relativeTo(files).invariantSeparatorsPath}\n" })
            keep.writeText("app/a.jar\napp/b-2.jar\napp/Piko.cfg\n")
        }
        val process = ProcessBuilder(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-File", script.absolutePath,
            "-ProcessId", processId.toString(),
            "-InstallDir", shortPath(install),
            "-Mode", mode,
            "-Source", files.absolutePath,
            "-Executable", "relaunch.cmd",
            "-LogFile", staging.resolve("update.log").absolutePath,
            "-Checksums", checksums.absolutePath,
            "-KeepList", keep.absolutePath,
            "-WaitSeconds", waitSeconds.toString(),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        println(output)
        staging.resolve("update.log").takeIf { it.isFile }?.let { println(it.readText()) }
        return code
    }

    /**
     * 安装目录的 8.3 短路径。GitHub 的 Windows runner 上临时目录本来就是 C:\Users\RUNNER~1，本机通常是长路径，
     * 脚本拿短路径与 Get-ChildItem 报出的长路径比对时曾把整个新版当成多余文件删掉，这里在本机也照 CI 的样子传。
     * 卷上关了 8.3 名时得到的就是原路径。
     */
    private fun shortPath(dir: File): String {
        val process = ProcessBuilder("cmd.exe", "/c", "for %I in (\"${dir.absolutePath}\") do @echo %~sI").start()
        return process.inputStream.bufferedReader().readText().trim().also { process.waitFor() }.ifEmpty { dir.absolutePath }
    }

    /** 一个已经退出的进程，脚本不必等它。 */
    private fun exitedPid(): Long = ProcessBuilder("cmd.exe", "/c", "exit").start().also { it.waitFor() }.pid()

    private fun waitForStart() {
        repeat(50) { if (started.isFile) return; Thread.sleep(100) }
    }

    private fun assertNoLeftovers() {
        val left = install.walkTopDown().filter { it.name.endsWith(".old") || it.name.endsWith(".new") }.toList()
        assertEquals(emptyList(), left)
        assertFalse(journal.exists(), "事务记录没删")
    }

    private fun creationTime(path: String) = Files.getAttribute(install.resolve(path).toPath(), "basic:creationTime") as FileTime

    @Test
    fun patchReplacesFilesAndKeepsOnlyLogs() {
        standardUpdate()
        // MSI 装上的文件：创建时间早于新版的修改时间，Windows Installer 修复时才当成用户改过的、不换回旧版。
        // 默认的 NTFS 上文件名隧道也给出同一个值，脚本不显式设也能过；关了隧道（MaximumTunnelEntries=0）才分得出来
        val installedAt = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"))
        for (path in listOf("app/a.jar", "app/Piko.cfg")) Files.setAttribute(install.resolve(path).toPath(), "basic:creationTime", installedAt)
        assertEquals(0, run())
        assertEquals("a 2", text("app/a.jar"))
        assertEquals("b 2", text("app/b-2.jar"))
        assertEquals("cfg 2", text("app/Piko.cfg"))
        assertEquals(installedAt, creationTime("app/a.jar"))
        assertEquals(installedAt, creationTime("app/Piko.cfg"))
        assertFalse(install.resolve("app/b-1.jar").exists(), "新版没有的 jar 没删")
        assertNoLeftovers()
        assertFalse(files.exists(), "装过的暂存文件没删")
        assertFalse(staging.resolve("staged.sha256").exists())
        assertTrue(staging.resolve("update.log").isFile)
        assertFalse(failed.exists())
        waitForStart()
        assertTrue(started.isFile, "没有重新拉起应用")
    }

    // 杀毒软件、索引器短暂开着文件时，改名失败一下就放弃，更新便白白失败
    @Test
    fun briefLockOnTargetIsWaitedOut() {
        standardUpdate()
        val lock = FileInputStream(install.resolve("app/a.jar"))
        thread { Thread.sleep(1500); lock.close() }
        assertEquals(0, run())
        assertEquals("a 2", text("app/a.jar"))
        assertNoLeftovers()
    }

    @Test
    fun lastingLockRollsBackToTheOldImage() {
        standardUpdate()
        FileInputStream(install.resolve("app/a.jar")).use { assertEquals(1, run()) }
        assertEquals("a 1", text("app/a.jar"))
        assertEquals("cfg 1", text("app/Piko.cfg"))
        assertEquals("b 1", text("app/b-1.jar"))
        assertFalse(install.resolve("app/b-2.jar").exists(), "回滚没删新增的文件")
        assertNoLeftovers()
        assertTrue(failed.isFile)
        assertTrue(files.isDirectory, "失败后暂存应留着重试")
        waitForStart()
        assertTrue(started.isFile)
    }

    // 断电或脚本被杀后的现场：b-2.jar 已放上，a.jar 已换（旧的在 .old），Piko.cfg 还没换（新的在 .new）
    @Test
    fun recoverRollsBackAnInterruptedSwap() {
        installed("app/b-2.jar", "b 2")
        installed("app/b-1.jar", "b 1")
        installed("app/a.jar", "a 2")
        installed("app/a.jar.old", "a 1")
        installed("app/Piko.cfg", "cfg 1")
        installed("app/Piko.cfg.new", "cfg 2")
        journal.writeText("staging\t${staging.absolutePath}\n+\tapp\\b-2.jar\n~\tapp\\a.jar\n~\tapp\\Piko.cfg\n")
        assertEquals(1, run(mode = "recover"))
        assertEquals("a 1", text("app/a.jar"))
        assertEquals("cfg 1", text("app/Piko.cfg"))
        assertEquals("b 1", text("app/b-1.jar"))
        assertFalse(install.resolve("app/b-2.jar").exists())
        assertNoLeftovers()
        assertTrue(failed.isFile, "下次启动要能提示更新未完成")
        waitForStart()
        assertTrue(started.isFile)
    }

    @Test
    fun appThatDoesNotExitFailsTheUpdateAndRelaunches() {
        standardUpdate()
        assertEquals(1, run(processId = ProcessHandle.current().pid(), waitSeconds = 1))
        assertEquals("a 1", text("app/a.jar"))
        assertTrue(failed.isFile)
        waitForStart()
        assertTrue(started.isFile)
    }
}
