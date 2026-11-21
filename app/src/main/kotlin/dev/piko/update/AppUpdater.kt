package dev.piko.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
 * 对不上不安装。安装走 PackageInstaller 会话而不是 ACTION_INSTALL_PACKAGE，后者自 API 29 弃用。
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
    private class ActiveInstall(val sessionId: Int, val update: AvailableUpdate)

    private data class PersistedInstallUpdate(
        override val version: String,
        override val notes: String,
        override val pageUrl: String,
        override val canInstallInApp: Boolean,
        override val downloadSize: Long,
        override val localizedNotes: Map<AppLanguage, String> = emptyMap(),
        override val modeLabel: String = "APK",
    ) : AvailableUpdate

    private val sessionPrefs by lazy {
        context.getSharedPreferences(SESSION_PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Volatile
    private var activeInstall: ActiveInstall? = null

    init {
        if (sessionPrefs.getString(KEY_PENDING_VERSION, null) == BuildConfig.VERSION_NAME) {
            clearPersistedSession()
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

    /** 未授予「安装未知应用」时先带用户去授权，授权后需再点一次。Installing 不拦：系统安装器被划掉时可能不回调。 */
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

    /** 下载并校验，成功后提交安装。运行在应用级 [serviceScope] 内，关闭弹窗不中断下载。 */
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
            withContext(Dispatchers.IO) { install(file, update) }
        } catch (e: CancellationException) {
            if (status is UpdateStatus.Downloading) {
                status = UpdateStatus.Available(update)
            }
            throw e
        } catch (e: SecurityException) {
            PikoLog.w(TAG, "Downloaded update APK failed identity verification for ${update.apk.name}", e)
            status = UpdateStatus.Failed(
                tr("安装包校验未通过，请从下载页获取", "Package verification failed. Use the download page", "パッケージの検証に失敗しました。ダウンロードページから取得してください", "설치 패키지 검증에 실패했습니다. 다운로드 페이지에서 받으세요", "安裝套件驗證未通過，請從下載頁取得"),
                update,
            )
        } catch (e: Exception) {
            PikoLog.w(TAG, "Failed to commit PackageInstaller session for ${update.apk.name}", e)
            if (status !is UpdateStatus.Failed) {
                status = UpdateStatus.Failed(
                    tr("无法启动安装", "Unable to start installation", "インストールを開始できません", "설치를 시작할 수 없습니다", "無法啟動安裝"),
                    update,
                )
            }
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

    private fun install(apk: File, update: AndroidUpdate) {
        transitionDownloadStage(
            update = update,
            stage = UpdateStage.Verifying,
            totalBytes = update.apk.size,
            modeLabel = update.modeLabel,
        )
        try {
            verifyDownloadedApk(apk)
        } catch (e: Throwable) {
            apk.delete()
            throw e
        }
        transitionDownloadStage(
            update = update,
            stage = UpdateStage.Staging,
            totalBytes = update.apk.size,
            modeLabel = update.modeLabel,
        )
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        var committed = false
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("piko.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, INSTALL_COPY_BUFFER_BYTES) }
                    session.fsync(out)
                }
                val callback = Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName)
                // 系统要往回调 Intent 里填状态与确认页，必须可变
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, sessionId, callback, flags)
                persistPendingSession(sessionId, update)
                activeInstall = ActiveInstall(sessionId, update)
                status = UpdateStatus.Installing(update)
                session.commit(pending.intentSender)
                committed = true
            }
        } finally {
            apk.delete()
            if (!committed) {
                if (activeInstall?.sessionId == sessionId) {
                    activeInstall = null
                }
                clearPersistedSession(sessionId)
                runCatching { installer.abandonSession(sessionId) }
            }
        }
    }

    /** 提交 PackageInstaller 会话前核对包名、版本号与签名证书，防止镜像或异常构件越权替换或降级安装。 */
    private fun verifyDownloadedApk(apk: File) {
        val pm = context.packageManager
        val flags = packageSigningFlags()
        val installedInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, flags)
        }
        val archiveInfo = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.absolutePath, flags)
        }) ?: throw SecurityException("Unable to parse downloaded APK archive: ${apk.name}")

        val installedSigners = extractSigners(installedInfo)
        val archiveSigners = extractSigners(archiveInfo)
        verifyArchiveIdentity(
            expectedPackageName = context.packageName,
            installedVersionCode = packageVersionCode(installedInfo),
            installedSigners = installedSigners,
            archivePackageName = archiveInfo.packageName,
            archiveVersionCode = packageVersionCode(archiveInfo),
            archiveSigners = archiveSigners,
        )
    }

    private fun packageSigningFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Android 9-12 (b/136784845) 在仅传 GET_SIGNING_CERTIFICATES 时 getPackageArchiveInfo 的 signingInfo 可能返回 null
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    private fun packageVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    private fun extractSigners(info: PackageInfo): PackageSigners {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo
            if (signingInfo != null) {
                if (signingInfo.hasMultipleSigners()) {
                    val active = signingInfo.apkContentsSigners.orEmpty().mapTo(linkedSetOf(), ::signatureSha256)
                    if (active.isNotEmpty()) return PackageSigners(activeSigners = active)
                } else {
                    val history = signingInfo.signingCertificateHistory.orEmpty().map(::signatureSha256)
                    val contents = signingInfo.apkContentsSigners.orEmpty().mapTo(linkedSetOf(), ::signatureSha256)
                    val active = contents.ifEmpty { history.lastOrNull()?.let(::setOf).orEmpty() }
                    if (active.isNotEmpty()) {
                        return PackageSigners(activeSigners = active, rotationLineage = history)
                    }
                }
            }
        }
        @Suppress("DEPRECATION")
        val legacy = info.signatures.orEmpty().mapTo(linkedSetOf(), ::signatureSha256)
        return PackageSigners(activeSigners = legacy)
    }

    private fun signatureSha256(signature: Signature): String =
        MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) }

    internal fun isActiveSession(sessionId: Int, requireSessionOwnership: Boolean = false): Boolean {
        if (sessionId == -1) return false
        val knownInMemory = activeInstall?.sessionId == sessionId
        val knownPersisted = sessionPrefs.getInt(KEY_PENDING_SESSION_ID, -1) == sessionId &&
            sessionPrefs.getString(KEY_PENDING_PACKAGE, null) == context.packageName
        if (!knownInMemory && !knownPersisted) return false
        if (!requireSessionOwnership) return true
        val info = runCatching { context.packageManager.packageInstaller.getSessionInfo(sessionId) }.getOrNull()
        val valid = info != null &&
            info.installerPackageName == context.packageName &&
            (info.appPackageName == null || info.appPackageName == context.packageName)
        if (!valid && !knownInMemory) {
            clearPersistedSession(sessionId)
        }
        return valid
    }

    internal fun onInstallSucceeded(sessionId: Int) {
        if (activeInstall?.sessionId == sessionId) {
            activeInstall = null
        }
        clearPersistedSession(sessionId)
    }

    internal fun onInstallFailed(
        sessionId: Int,
        userMessage: String,
        logReason: String,
        cause: Throwable? = null,
    ) {
        val update = resolveSessionUpdate(sessionId) ?: run {
            PikoLog.w(TAG, "Ignoring PackageInstaller failure for non-active sessionId=$sessionId: $logReason", cause)
            return
        }
        if (activeInstall?.sessionId == sessionId) {
            activeInstall = null
        }
        clearPersistedSession(sessionId)
        runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
        PikoLog.w(TAG, "System package installation did not complete (sessionId=$sessionId): $logReason", cause)
        status = UpdateStatus.Failed(
            tr("安装未完成", "Installation incomplete", "インストールが未完了です", "설치 미완료", "安裝未完成"),
            update,
        )
        mutableMessages.tryEmit(userMessage)
    }

    private fun persistPendingSession(sessionId: Int, update: AndroidUpdate) {
        sessionPrefs.edit()
            .putInt(KEY_PENDING_SESSION_ID, sessionId)
            .putString(KEY_PENDING_PACKAGE, context.packageName)
            .putString(KEY_PENDING_VERSION, update.version)
            .putString(KEY_PENDING_NOTES, update.notes)
            .putString(KEY_PENDING_PAGE_URL, update.pageUrl)
            .putLong(KEY_PENDING_SIZE, update.downloadSize)
            .commit()
    }

    private fun clearPersistedSession(expectedSessionId: Int? = null) {
        val stored = sessionPrefs.getInt(KEY_PENDING_SESSION_ID, -1)
        if (expectedSessionId != null && stored != -1 && stored != expectedSessionId) return
        sessionPrefs.edit().clear().apply()
    }

    private fun resolveSessionUpdate(sessionId: Int): AvailableUpdate? {
        if (sessionId == -1) return null
        activeInstall?.takeIf { it.sessionId == sessionId }?.let { return it.update }
        if (sessionPrefs.getInt(KEY_PENDING_SESSION_ID, -1) != sessionId) return null
        if (sessionPrefs.getString(KEY_PENDING_PACKAGE, null) != context.packageName) return null
        val version = sessionPrefs.getString(KEY_PENDING_VERSION, null)?.takeIf { it.isNotBlank() } ?: return null
        return PersistedInstallUpdate(
            version = version,
            notes = sessionPrefs.getString(KEY_PENDING_NOTES, null).orEmpty(),
            pageUrl = sessionPrefs.getString(KEY_PENDING_PAGE_URL, null).orEmpty(),
            canInstallInApp = !BuildConfig.DEBUG,
            downloadSize = sessionPrefs.getLong(KEY_PENDING_SIZE, 0L),
        )
    }

    private fun pickApk(release: LatestRelease): ReleaseAsset? =
        Build.SUPPORTED_ABIS.firstNotNullOfOrNull { release.asset("piko-${release.version}-$it.apk") }
            ?: release.asset("piko-${release.version}-universal.apk")

    private companion object {
        const val TAG = "Update"
        const val UPDATE_DIR = "updates"
        const val INSTALL_COPY_BUFFER_BYTES = 256 * 1024
        const val SESSION_PREFS_NAME = "piko_pending_update_session"
        const val KEY_PENDING_SESSION_ID = "session_id"
        const val KEY_PENDING_PACKAGE = "package_name"
        const val KEY_PENDING_VERSION = "version"
        const val KEY_PENDING_NOTES = "notes"
        const val KEY_PENDING_PAGE_URL = "page_url"
        const val KEY_PENDING_SIZE = "download_size"
    }
}

internal data class PackageSigners(
    val activeSigners: Set<String>,
    val rotationLineage: List<String> = emptyList(),
)

/** 核对归档 APK 的包名、版本号（禁止降级）与活跃签名证书（及正向密钥轮换链）是否与当前已安装应用一致。 */
internal fun verifyArchiveIdentity(
    expectedPackageName: String,
    installedVersionCode: Long,
    installedSigners: PackageSigners,
    archivePackageName: String?,
    archiveVersionCode: Long,
    archiveSigners: PackageSigners,
) {
    if (archivePackageName != expectedPackageName) {
        throw SecurityException(
            "Update APK package mismatch: expected=$expectedPackageName, actual=$archivePackageName",
        )
    }
    if (archiveVersionCode < installedVersionCode) {
        throw SecurityException(
            "Update APK version downgrade rejected: installed=$installedVersionCode, archive=$archiveVersionCode",
        )
    }
    if (installedSigners.activeSigners.isEmpty() || archiveSigners.activeSigners.isEmpty()) {
        throw SecurityException(
            "Missing signing certificates: installed=${installedSigners.activeSigners.size}, archive=${archiveSigners.activeSigners.size}",
        )
    }
    val exactActiveMatch = archiveSigners.activeSigners == installedSigners.activeSigners
    val validForwardRotation = installedSigners.activeSigners.size == 1 &&
        archiveSigners.activeSigners.size == 1 &&
        archiveSigners.rotationLineage.size >= 2 &&
        archiveSigners.rotationLineage.last() == archiveSigners.activeSigners.single() &&
        installedSigners.activeSigners.single() in archiveSigners.rotationLineage
    if (!(exactActiveMatch || validForwardRotation)) {
        throw SecurityException("Update APK signing certificate does not match installed app")
    }
}
