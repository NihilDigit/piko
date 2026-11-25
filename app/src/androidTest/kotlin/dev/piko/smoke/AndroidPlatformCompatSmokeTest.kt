package dev.piko.smoke

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.piko.download.VideoSegmentExtractor
import dev.piko.platform.AndroidPikoPlatform
import dev.piko.ui.theme.PikoMotionScale
import dev.piko.util.PikPakAppLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * 覆盖 JVM 单测测不出来的 Android 跨版本兼容性边界（API 26 / 29 / 34）：
 * - PikPakAppLink 编码深链解码在 API < 33 不触发 URLDecoder.decode(String, Charset) NoSuchMethodError；
 * - 默认下载根目录 FileProvider 打开目录不抛 IllegalArgumentException；
 * - AndroidManifest 正确声明 USE_BIOMETRIC 权限供 API 30+ BiometricPrompt 使用；
 * - VideoSegmentExtractor 失败时不破坏已存在的目标文件。
 */
@RunWith(AndroidJUnit4::class)
class AndroidPlatformCompatSmokeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun encodedOfficialDeepLinkDecodesWithoutApi33NoSuchMethodError() {
        val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
        val encodedMagnet = "magnet%3A%3Fxt%3Durn%3Abtih%3A0123456789abcdef0123456789abcdef01234567"
        val nested = "https://toapp.mypikpak.com/toapp?deepLink=%2Fdrive%2Fadd%3Fadd_url%3D$encodedMagnet"
        val parsed = PikPakAppLink.parse(nested)
        assertEquals(PikPakAppLink.Target.Import(magnet), parsed)
    }

    @Test
    fun openContainingFolderOnDefaultDownloadRootDoesNotThrow() {
        val platform = AndroidPikoPlatform(
            context = context,
            updater = { error("updater unused in compat smoke test") },
            motionScale = PikoMotionScale(),
        )
        val rootDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "Piko")
        rootDir.mkdirs()
        val sample = File(rootDir, "compat-smoke-${UUID.randomUUID()}.bin")
        sample.writeBytes(byteArrayOf(1, 2, 3, 4))
        try {
            // 默认根目录本身等于 file_paths.xml 的 external-files-path 根，调用不得抛 IllegalArgumentException
            platform.localFiles.openContainingFolder(sample.absolutePath)
        } finally {
            sample.delete()
        }
    }

    @Test
    fun manifestDeclaresUseBiometricPermission() {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        }
        val permissions = info.requestedPermissions?.toSet().orEmpty()
        assertTrue(
            "Manifest must declare USE_BIOMETRIC for API 30+ BiometricPrompt",
            Manifest.permission.USE_BIOMETRIC in permissions,
        )
    }

    @Test
    fun videoSegmentExtractorFailurePreservesExistingDestination() = runBlocking {
        val dir = File(context.cacheDir, "extractor-compat-${UUID.randomUUID()}").apply { mkdirs() }
        val existing = File(dir, "existing.mp4").apply { writeBytes(ByteArray(2048) { 42 }) }
        val invalidSource = File(dir, "corrupt.mp4").apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }
        try {
            val result = VideoSegmentExtractor.remux(
                context = context,
                sourcePath = invalidSource.absolutePath,
                destinationFile = existing,
                durationHintMs = 1_000L,
                onProgress = {},
            )
            assertTrue("Corrupt source must fail extraction", result.isFailure)
            assertTrue("Existing destination must be preserved on failure", existing.exists())
            assertEquals(2048L, existing.length())
        } finally {
            dir.deleteRecursively()
        }
    }
}
