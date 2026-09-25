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
