package dev.piko.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.piko.BuildConfig
import dev.piko.data.auth.AppLanguage
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.update.ChecksumMismatchException
import dev.piko.shared.update.GithubReleaseClient
import dev.piko.shared.update.LatestRelease
import dev.piko.shared.update.ReleaseAsset
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** [apk] 已按本机 ABI 选好。 */
data class AndroidUpdate(
    override val version: String,
    override val notes: String,
    override val pageUrl: String,
    override val canInstallInApp: Boolean,
    val apk: ReleaseAsset,
    override val localizedNotes: Map<AppLanguage, String> = emptyMap(),
) : AvailableUpdate {
    override val downloadSize: Long get() = apk.size
    override val modeLabel: String get() = "APK"
}

/**
 * 从 GitHub Releases 检查并安装新版本。
 *
 * 发版由 v* tag 触发，Release 附按 ABI 拆分的 APK（piko-<版本>-<abi>.apk）与通用包。这里选
 * 本机主 ABI 的包，没有就退回通用包；下载时边写边算 SHA-256，与 GitHub 为附件公布的 digest
 * 对不上不安装。安装通过 FileProvider + ACTION_VIEW 移交系统或第三方软件包安装器（不再使用
 * PackageInstaller 会话，避免触发 Google Play Protect 对侧载 Dropper 的启发式误报）。
 *
 * debug 包的包名是 dev.piko.debug，Release 里是 dev.piko，装上去是另一个应用而不是升级，
 * 所以 debug 包只检查、不安装，由界面改为打开 Release 页面；开屏也不查。
 */
class AppUpdater(private val context: Context) : GithubUpdateService<AndroidUpdate>(
    releases = GithubReleaseClient(HttpClient(OkHttp), userAgent = "Piko/${BuildConfig.VERSION_NAME}"),
    currentVersion = BuildConfig.VERSION_NAME,
    checksOnStartup = !BuildConfig.DEBUG,
    log = { message, cause -> PikoLog.w("Update", message, cause) },
) {
    init {
        // ACTION_VIEW 移交后需由系统安装器异步读取 APK，不能立即删除；在下次启动时清理上次遗留的缓存包
        serviceScope.launch(Dispatchers.IO) {
            if (status !is UpdateStatus.Downloading) {
                runCatching { File(context.cacheDir, UPDATE_DIR).deleteRecursively() }
            }
        }
    }

    override suspend fun resolve(release: LatestRelease): AndroidUpdate? {
        val apk = pickApk(release) ?: return null
        return AndroidUpdate(
            version = release.version,
            notes = release.notes,
            localizedNotes = release.localizedNotes,
            pageUrl = release.pageUrl,
            canInstallInApp = !BuildConfig.DEBUG,
            apk = apk,
        )
    }

    /** 未授予「安装未知应用」时先带用户去授权，授权后需再点一次。 */
    override fun prepareDownload(update: AndroidUpdate): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            PikoLog.i(TAG, "Unknown app install permission not granted; opening system settings")
            runCatching { context.startActivity(intent) }
                .onFailure { PikoLog.w(TAG, "Failed to open unknown app sources settings", it) }
            return false
        }
        return true
    }

    /** 下载并校验，成功后通过 ACTION_VIEW 拉起系统安装器。运行在应用级 [serviceScope] 内，关闭弹窗不中断下载。 */
    override suspend fun performDownloadAndInstall(update: AndroidUpdate) {
        val file = try {
            download(update)
        } catch (e: CancellationException) {
            status = UpdateStatus.Available(update)
            throw e
        } catch (e: Exception) {
            status = downloadFailed(e, update)
            return
        }
        PikoLog.i(TAG, "Update APK downloaded and verified: ${update.apk.name} (${file.length()} bytes)")
        try {
            launchApkInstaller(file)
            // ACTION_VIEW 移交系统安装器后无结果回调，回到可更新态，避免用户取消安装后按钮卡死在等待中
            status = UpdateStatus.Available(update)
        } catch (e: Exception) {
            PikoLog.w(TAG, "Failed to launch APK installer for ${update.apk.name}", e)
            status = UpdateStatus.Failed(
                tr("无法启动安装", "Unable to start installation", "インストールを開始できません", "설치를 시작할 수 없습니다", "無法啟動安裝"),
                update,
            )
        }
    }

    private suspend fun download(update: AndroidUpdate): File = withContext(Dispatchers.IO) {
        val expected = update.apk.sha256 ?: throw ChecksumMismatchException("Release asset has no SHA-256 digest")
        val dir = File(context.cacheDir, UPDATE_DIR).apply { deleteRecursively(); mkdirs() }
        val target = File(dir, update.apk.name)
        val digest = MessageDigest.getInstance("SHA-256")
        val total = update.apk.size
        val tracker = UpdateProgressTracker(total) { progress, downloaded, totalBytes, bytesPerSec ->
            reportDownloadProgress(update, progress, downloaded, totalBytes, bytesPerSec, update.modeLabel)
        }
        try {
            target.outputStream().use { out ->
                releases.download(
                    update.apk,
                    onChunk = { buffer, length ->
                        out.write(buffer, 0, length)
                        digest.update(buffer, 0, length)
                        tracker.onBytes(length)
                    },
                    onProgress = {},
                )
            }
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
        transitionDownloadStage(
            update = update,
            stage = UpdateStage.Verifying,
            totalBytes = total,
            modeLabel = update.modeLabel,
        )
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            target.delete()
            throw ChecksumMismatchException("${update.apk.name}: $actual != $expected")
        }
        target
    }

    private fun launchApkInstaller(apk: File) {
        val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = createApkInstallIntent(apkUri, apk.name)
        // 部分设备将系统安装器替换为第三方实现，提前向所有匹配到的处理器授予 URI 读权限
        context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .asSequence()
            .map { it.activityInfo.packageName }
            .distinct()
            .forEach { packageName ->
                runCatching {
                    context.grantUriPermission(packageName, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }.onFailure {
                    PikoLog.w(TAG, "Failed to grant APK read permission to $packageName", it)
                }
            }
        context.startActivity(intent)
    }

    private fun pickApk(release: LatestRelease): ReleaseAsset? =
        Build.SUPPORTED_ABIS.firstNotNullOfOrNull { release.asset("piko-${release.version}-$it.apk") }
            ?: release.asset("piko-${release.version}-universal.apk")

    private companion object {
        const val TAG = "Update"
        const val UPDATE_DIR = "updates"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}

internal fun createApkInstallIntent(apkUri: Uri, apkName: String): Intent = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(apkUri, "application/vnd.android.package-archive")
    clipData = ClipData.newRawUri(apkName, apkUri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
}
