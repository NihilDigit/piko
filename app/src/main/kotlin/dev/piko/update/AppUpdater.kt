package dev.piko.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * 从 GitHub Releases 检查并安装新版本。
 *
 * 发版由 v* tag 触发，Release 附按 ABI 拆分的 APK（piko-<版本>-<abi>.apk）、通用包与
 * SHA256SUMS.txt。这里选本机主 ABI 的包，没有就退回通用包；下载时边写边算 SHA-256，
 * 与 SHA256SUMS 对不上不安装。安装走 PackageInstaller 会话而不是 ACTION_INSTALL_PACKAGE，
 * 后者自 API 29 弃用。
 *
 * debug 包的包名是 dev.piko.debug，Release 里是 dev.piko，装上去是另一个应用而不是升级，
 * 所以 debug 包只检查、不安装，由界面改为打开 Release 页面。
 */
class AppUpdater(private val context: Context) : AppUpdateService {

    override var status by mutableStateOf<UpdateStatus>(UpdateStatus.Idle)
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** 安装器回传的失败原因，由界面用 Snackbar 呈现。 */
    override val messages: SharedFlow<String> = _messages.asSharedFlow()

    override val canInstallInApp: Boolean get() = !BuildConfig.DEBUG

    private val json = Json { ignoreUnknownKeys = true }
    private val http by lazy { HttpClient(OkHttp) }

    /** [silent] 为 true 时失败不改状态：进入页面时的自动检查不该因为没网冒出错误。 */
    override suspend fun check(silent: Boolean) {
        if (status is UpdateStatus.Checking || status is UpdateStatus.Downloading) return
        status = UpdateStatus.Checking
        fetchLatest()
            .onSuccess { update -> status = update?.let { UpdateStatus.Available(it) } ?: UpdateStatus.UpToDate }
            .onFailure { status = if (silent) UpdateStatus.Idle else UpdateStatus.Failed("检查更新失败") }
    }

    private suspend fun fetchLatest(): Result<AvailableUpdate?> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val response = http.get(LATEST_RELEASE_API) {
                header("Accept", "application/vnd.github+json")
                header("User-Agent", "Piko/${BuildConfig.VERSION_NAME}")
            }
            check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
            val release = json.decodeFromString(GithubRelease.serializer(), response.bodyAsText())
            val version = release.tagName.removePrefix("v")
            if (!isNewer(version, BuildConfig.VERSION_NAME)) return@runSuspendCatching null
            val apk = pickApk(release.assets, version) ?: error("Release 里没有可用的安装包")
            AvailableUpdate(
                version = version,
                notes = release.body.orEmpty().trim(),
                pageUrl = release.htmlUrl,
                apkName = apk.name,
                apkUrl = apk.downloadUrl,
                apkSize = apk.size,
                checksumsUrl = release.assets.firstOrNull { it.name == CHECKSUMS_ASSET }?.downloadUrl,
            )
        }
    }

    /** 下载并校验，成功后提交安装。未授予「安装未知应用」时先带用户去授权，授权后需再点一次。 */
    override suspend fun downloadAndInstall(update: AvailableUpdate) {
        if (!canInstallInApp) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
            return
        }
        status = UpdateStatus.Downloading(update, 0f)
        val file = download(update).getOrElse {
            status = UpdateStatus.Failed("下载失败", update)
            return
        }
        runCatching { install(file) }
            .onSuccess { status = UpdateStatus.Installing(update) }
            .onFailure { status = UpdateStatus.Failed("无法启动安装", update) }
    }

    private suspend fun download(update: AvailableUpdate): Result<File> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val expected = update.checksumsUrl?.let { expectedChecksum(it, update.apkName) }
                ?: error("缺少校验和")
            val dir = File(context.cacheDir, UPDATE_DIR).apply { deleteRecursively(); mkdirs() }
            val target = File(dir, update.apkName)
            val digest = MessageDigest.getInstance("SHA-256")
            http.prepareGet(update.apkUrl) { header("User-Agent", "Piko/${BuildConfig.VERSION_NAME}") }.execute { response ->
                check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(64 * 1024)
                var written = 0L
                var reported = 0f
                target.outputStream().use { out ->
                    while (true) {
                        val read = channel.readAvailable(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read
                        // 每涨 1% 才更新状态：按块更新的话，几十 MB 的包要触发几百次重组
                        val progress = if (update.apkSize > 0) (written.toFloat() / update.apkSize).coerceIn(0f, 1f) else 0f
                        if (progress - reported >= 0.01f) {
                            reported = progress
                            status = UpdateStatus.Downloading(update, progress)
                        }
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != expected) {
                target.delete()
                error("校验和不符")
            }
            target
        }
    }

    private suspend fun expectedChecksum(url: String, fileName: String): String? {
        val text = http.get(url) { header("User-Agent", "Piko/${BuildConfig.VERSION_NAME}") }.bodyAsText()
        // sha256sum 的格式：「<hex>  <文件名>」，文件名前可能带表示二进制模式的 *
        return text.lineSequence()
            .map { it.trim().split(Regex("\\s+"), limit = 2) }
            .firstOrNull { it.size == 2 && it[1].removePrefix("*") == fileName }
            ?.get(0)?.lowercase()
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
        _messages.tryEmit(message)
    }

    private fun pickApk(assets: List<GithubAsset>, version: String): GithubAsset? {
        val byName = assets.associateBy { it.name }
        return Build.SUPPORTED_ABIS.firstNotNullOfOrNull { byName["piko-$version-$it.apk"] }
            ?: byName["piko-$version-universal.apk"]
    }

    companion object {
        private const val LATEST_RELEASE_API = "https://api.github.com/repos/NihilDigit/piko/releases/latest"
        private const val CHECKSUMS_ASSET = "SHA256SUMS.txt"
        private const val UPDATE_DIR = "updates"

        /** 按数字逐段比较，忽略 -debug 这类后缀；段数不同时缺的一段按 0 计。 */
        internal fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}

@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    val body: String? = null,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
private data class GithubAsset(
    val name: String,
    val size: Long = 0L,
    @SerialName("browser_download_url") val downloadUrl: String,
)

/** 与 shared 里的 runSuspendCatching 同义（那个是 internal）：取消照常抛出，不当作失败。 */
private inline fun <T> runSuspendCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
