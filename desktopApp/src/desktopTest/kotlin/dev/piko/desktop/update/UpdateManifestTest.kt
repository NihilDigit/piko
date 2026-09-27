package dev.piko.desktop.update

import dev.piko.shared.update.ChecksumMismatchException
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateManifestTest {
    private val root: File = Files.createTempDirectory("piko-manifest").toFile()
    private val install = root.resolve("install")

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun entry(path: String, content: String, patch: Boolean = false) = ManifestFile(
        path = path,
        size = content.toByteArray().size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).toHex(),
        mtime = 1_700_000_000_000L,
        patch = patch,
    )

    private fun write(path: String, content: String) =
        install.resolve(path).apply { parentFile.mkdirs(); writeText(content) }

    private val manifest = UpdateManifest(
        version = "0.0.2",
        files = listOf(
            entry("Piko.exe", "exe v2", patch = true),
            entry("app/desktopApp-desktop.jar", "jar v2", patch = true),
            entry("runtime/lib/modules", "modules"),
            entry("app/resources/mpv/libmpv-2.dll", "mpv"),
        ),
    )

    @Test
    fun patchFilesMayDifferButEverythingElseMustMatch() {
        write("Piko.exe", "exe v1")
        write("app/desktopApp-desktop.jar", "jar v1")
        write("runtime/lib/modules", "modules")
        write("app/resources/mpv/libmpv-2.dll", "mpv")
        write("app/leftover.txt", "not in the new version")
        assertTrue(canPatch(install, manifest))

        // 同样大小、不同内容：只比大小会误判为可以增量
        write("app/resources/mpv/libmpv-2.dll", "MPV")
        assertFalse(canPatch(install, manifest))
    }

    @Test
    fun missingRuntimeFileNeedsFullInstall() {
        write("Piko.exe", "exe v1")
        write("app/resources/mpv/libmpv-2.dll", "mpv")
        assertFalse(canPatch(install, manifest))
    }

    private fun zip(vararg files: Pair<String, String>): File = root.resolve("app.zip").apply {
        ZipOutputStream(outputStream()).use { out ->
            files.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
    }

    @Test
    fun extractRestoresManifestMtime() {
        val staged = root.resolve("staged")
        extractPatch(zip("Piko.exe" to "exe v2", "app/desktopApp-desktop.jar" to "jar v2"), manifest, staged)
        val jar = staged.resolve("app/desktopApp-desktop.jar")
        assertEquals("jar v2", jar.readText())
        // AOT 缓存按 jar 的修改时间校验，差一毫秒整份作废
        assertEquals(1_700_000_000_000L, jar.lastModified())
    }

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/update/$name")) { name }.use { it.readBytes() }

    private val deltaTarget = fixture("delta-target.bin")

    // 只有一个补丁文件的清单，差分包里就只有它
    private val deltaManifest = UpdateManifest(
        version = "0.0.2",
        files = listOf(
            ManifestFile(
                path = "app/desktopApp-desktop.jar",
                size = deltaTarget.size.toLong(),
                sha256 = MessageDigest.getInstance("SHA-256").digest(deltaTarget).toHex(),
                mtime = 1_700_000_000_000L,
                patch = true,
            ),
        ),
    )

    private fun deltaZip(base: String? = null): File = root.resolve("delta.zip").apply {
        ZipOutputStream(outputStream()).use { out ->
            out.putNextEntry(ZipEntry("app/desktopApp-desktop.jar.zst"))
            out.write(fixture("delta-target.bin.zst"))
            out.closeEntry()
            if (base != null) {
                out.putNextEntry(ZipEntry("app/desktopApp-desktop.jar.base"))
                out.write(base.toByteArray())
                out.closeEntry()
            }
        }
    }

    // 夹具由 zstd CLI 的 --patch-from 生成，参数与 delta-updates.sh 相同；验证的是 CLI 压出的差分 zstd-jni 能否还原
    @Test
    fun deltaFromCliRestoresAgainstInstalledBase() {
        install.resolve("app").mkdirs()
        install.resolve("app/desktopApp-desktop.jar").writeBytes(fixture("delta-base.bin"))
        val staged = root.resolve("staged")
        applyDelta(deltaZip(), deltaManifest, install, staged)
        val jar = staged.resolve("app/desktopApp-desktop.jar")
        assertTrue(jar.readBytes().contentEquals(deltaTarget))
        assertEquals(1_700_000_000_000L, jar.lastModified())
    }

    // 模块 jar 每次构建换名，字典是 .base 指向的旧文件，不是新路径上的（本机没有）
    @Test
    fun renamedJarRestoresAgainstNamedBase() {
        install.resolve("app").mkdirs()
        install.resolve("app/desktopApp-desktop-aa.jar").writeBytes(fixture("delta-base.bin"))
        val staged = root.resolve("staged")
        applyDelta(deltaZip(base = "app/desktopApp-desktop-aa.jar"), deltaManifest, install, staged)
        assertTrue(staged.resolve("app/desktopApp-desktop.jar").readBytes().contentEquals(deltaTarget))
    }

    // 调用方据这个异常退回完整补丁包，换成别的异常就成了更新失败
    @Test
    fun deltaAgainstWrongBaseFailsAsChecksumMismatch() {
        install.resolve("app").mkdirs()
        install.resolve("app/desktopApp-desktop.jar").writeBytes(fixture("delta-target.bin"))
        assertFailsWith<ChecksumMismatchException> {
            applyDelta(deltaZip(), deltaManifest, install, root.resolve("a"))
        }
        assertFailsWith<ChecksumMismatchException> {
            applyDelta(deltaZip(base = "app/desktopApp-desktop-gone.jar"), deltaManifest, install, root.resolve("b"))
        }
    }

    @Test
    fun extractRejectsIncompleteOrForeignArchives() {
        assertFailsWith<ChecksumMismatchException> {
            extractPatch(zip("Piko.exe" to "exe v2"), manifest, root.resolve("a"))
        }
        assertFailsWith<ChecksumMismatchException> {
            extractPatch(zip("Piko.exe" to "exe v2", "app/desktopApp-desktop.jar" to "jar v1"), manifest, root.resolve("b"))
        }
        assertFailsWith<ChecksumMismatchException> {
            extractPatch(
                zip("Piko.exe" to "exe v2", "app/desktopApp-desktop.jar" to "jar v2", "../evil.dll" to "x"),
                manifest,
                root.resolve("c"),
            )
        }
    }
}
