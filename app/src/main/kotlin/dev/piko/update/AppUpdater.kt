package dev.piko.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import dev.piko.BuildConfig
import dev.piko.shared.update.ChecksumMismatchException
import dev.piko.shared.update.GithubReleaseClient
import dev.piko.shared.update.LatestRelease
import dev.piko.shared.update.ReleaseAsset
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
) : AvailableUpdate {
    override val downloadSize: Long get() = apk.size
}

/**
 * 从 GitHub Releases 检查并安装新版本。
 *
 * 发版由 v* tag 触发，Release 附按 ABI 拆分的 APK（piko-<版本>-<abi>.apk）与通用包。这里选
 * 本机主 ABI 的包，没有就退回通用包；下载时边写边算 SHA-256，与 GitHub 为附件公布的 digest
 * 对不上不安装。安装走 PackageInstaller 会话而不是 ACTION_INSTALL_PACKAGE，后者自 API 29 弃用。
 *
 * debug 包的包名是 dev.piko.debug，Release 里是 dev.piko，装上去是另一个应用而不是升级，
 * 所以 debug 包只检查、不安装，由界面改为打开 Release 页面；开屏也不查。
 */
class AppUpdater(private val context: Context) : GithubUpdateService<AndroidUpdate>(
    releases = GithubReleaseClient(HttpClient(OkHttp), userAgent = "Piko/${BuildConfig.VERSION_NAME}"),
    currentVersion = BuildConfig.VERSION_NAME,
    checksOnStartup = !BuildConfig.DEBUG,
    log = { message, cause -> Log.w(TAG, message, cause) },
) {
    override suspend fun resolve(release: LatestRelease): AndroidUpdate? {
        val apk = pickApk(release) ?: return null
        return AndroidUpdate(
            version = release.version,
            notes = release.notes,
            pageUrl = release.pageUrl,
            canInstallInApp = !BuildConfig.DEBUG,
            apk = apk,
        )
    }

    /** 下载并校验，成功后提交安装。未授予「安装未知应用」时先带用户去授权，授权后需再点一次。 */
    override suspend fun downloadAndInstall(update: AvailableUpdate) {
        val own = update.own()
        if (!own.canInstallInApp) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
            return
        }
        status = UpdateStatus.Downloading(update, 0f)
        val file = try {
            download(own)
        } catch (e: CancellationException) {
            status = UpdateStatus.Available(update)
            throw e
        } catch (e: Exception) {
            status = downloadFailed(e, update)
            return
        }
        runCatching { install(file) }
            .onSuccess { status = UpdateStatus.Installing(update) }
            .onFailure { status = UpdateStatus.Failed("无法启动安装", update) }
    }

    private suspend fun download(update: AndroidUpdate): File = withContext(Dispatchers.IO) {
        val expected = update.apk.sha256 ?: throw ChecksumMismatchException("Release 没有公布摘要")
        val dir = File(context.cacheDir, UPDATE_DIR).apply { deleteRecursively(); mkdirs() }
        val target = File(dir, update.apk.name)
        val digest = MessageDigest.getInstance("SHA-256")
        target.outputStream().use { out ->
            releases.download(
                update.apk,
                onChunk = { buffer, length ->
                    out.write(buffer, 0, length)
                    digest.update(buffer, 0, length)
                },
                onProgress = { status = UpdateStatus.Downloading(update, it) },
            )
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            target.delete()
            throw ChecksumMismatchException("${update.apk.name}: $actual != $expected")
        }
        target
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("piko.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName)
            // 系统要往回调 Intent 里填状态与确认页，必须可变
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, callback, flags)
            session.commit(pending.intentSender)
        }
    }

    internal fun onInstallFailed(message: String) {
        val update = (status as? UpdateStatus.Installing)?.update
        status = UpdateStatus.Failed("安装未完成", update)
        mutableMessages.tryEmit(message)
    }

    private fun pickApk(release: LatestRelease): ReleaseAsset? =
        Build.SUPPORTED_ABIS.firstNotNullOfOrNull { release.asset("piko-${release.version}-$it.apk") }
            ?: release.asset("piko-${release.version}-universal.apk")

    private companion object {
        const val TAG = "AppUpdater"
        const val UPDATE_DIR = "updates"
    }
}
