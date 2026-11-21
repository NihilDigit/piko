package dev.piko.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.data.auth.AppLanguage
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.update.ChecksumMismatchException
import dev.piko.shared.update.GithubReleaseClient
import dev.piko.shared.update.LatestRelease
import dev.piko.shared.update.ReleaseCheck
import dev.piko.shared.update.isNetworkFailure
import dev.piko.shared.util.PikoLock
import dev.piko.shared.util.withPikoLock
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** 可供更新的版本。安装所需的附件由各平台的实现带着，界面只读这几项。 */
interface AvailableUpdate {
    val version: String
    val notes: String
    val localizedNotes: Map<AppLanguage, String> get() = emptyMap()
    fun notesFor(language: AppLanguage): String =
        localizedNotes[language]?.takeIf { it.isNotBlank() }
            ?: localizedNotes[AppLanguage.ZH_CN]?.takeIf { it.isNotBlank() }
            ?: notes

    val pageUrl: String

    /** 这次实际要下载的字节数。桌面端增量更新时远小于整个安装包。 */
    val downloadSize: Long

    /**
     * 为 false 时只给下载页链接：Android 的 debug 包与 Release 包名不同，装上去是另一个应用；
     * 其他不支持应用内直接替换的形态也只跳转下载页。
     */
    val canInstallInApp: Boolean

    /** 本次更新采用的传输/安装方式标签（如 zstd 差分、增量补丁、按需提取、MSI、AppImage、APK）。 */
    val modeLabel: String? get() = null
}

/** 更新下载与准备阶段，供进度条样式与状态文案区分下载、校验与暂存。 */
enum class UpdateStage {
    Downloading,
    Verifying,
    Staging,
}

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Available(val update: AvailableUpdate) : UpdateStatus
    data class Downloading(
        val update: AvailableUpdate,
        val progress: Float,
        val downloadedBytes: Long = if (update.downloadSize > 0L) (update.downloadSize * progress).toLong() else 0L,
        val totalBytes: Long = update.downloadSize,
        val bytesPerSec: Long = 0L,
        val stage: UpdateStage = UpdateStage.Downloading,
        val modeLabel: String? = update.modeLabel,
    ) : UpdateStatus

    /** 已下载并校验，等用户同意退出重启。只有桌面端经过这一步：替换文件要先退出自己。 */
    data class ReadyToRestart(val update: AvailableUpdate) : UpdateStatus

    /** 已交给系统安装器，等用户确认。 */
    data class Installing(val update: AvailableUpdate) : UpdateStatus
    data class Failed(val message: String, val update: AvailableUpdate? = null) : UpdateStatus
}

/**
 * 应用内更新。检查只在两处发生：开屏一次（[checkOnStartup]，一次进程一次）与设置页手动检查，
 * 没有后台轮询。
 */
interface AppUpdateService {
    /** 由 Compose State 支撑，设置页、侧边栏与弹窗直接读。 */
    val status: UpdateStatus

    /** 安装器回传的失败原因等一次性提示。 */
    val messages: SharedFlow<String>

    /** [silent] 为 true 时失败不改状态，只记日志。 */
    suspend fun check(silent: Boolean = false)

    /**
     * 开屏检查发现、还没被关掉的新版本，开屏弹窗据此显示。放在这里而不是界面的 remember 里，
     * Android 转屏重建界面后弹窗还在，而检查不会再做一次。
     */
    val startupUpdate: AvailableUpdate?

    /**
     * 开屏检查，一次进程一次，开发构建不查。[isIgnored] 为真的版本不提示。
     * 失败只记日志：没人在等这个结果，弹一句「检查更新失败」只是打扰。
     */
    suspend fun checkOnStartup(isIgnored: suspend (version: String) -> Boolean)

    fun dismissStartupUpdate()

    /**
     * 在应用级作用域内启动下载与安装，使关闭弹窗时下载可在后台继续；返回 false 表示当前实现
     * 未接管作用域，调用方应回退到自行调用 [downloadAndInstall]。
     */
    fun startDownload(update: AvailableUpdate): Boolean = false

    /** 显式取消正在进行的应用内更新下载，并将状态回退至可更新。 */
    fun cancelDownload() {}

    /** Android 下完即交给系统安装器；桌面端下完停在 [UpdateStatus.ReadyToRestart]。 */
    suspend fun downloadAndInstall(update: AvailableUpdate)

    /** 桌面端：退出并替换文件，完成后重新启动。 */
    suspend fun restartToInstall(update: AvailableUpdate) {}
}

/**
 * 节流统计更新下载进度与平滑速率（每涨 >= 1% 或间隔 >= 250ms 触发一次回调），
 * 避免每块 64 KiB 读取都触发 Compose 重组，并支持多线程并行 Range 下载（Linux zsync）。
 */
class UpdateProgressTracker(
    private val totalBytes: Long,
    private val onUpdate: (progress: Float, downloadedBytes: Long, totalBytes: Long, bytesPerSec: Long) -> Unit,
) {
    private val lock = PikoLock()
    private var downloadedBytes = 0L
    private var lastReportedProgress = 0f
    private val startMark = TimeSource.Monotonic.markNow()
    private var lastEmitMark = startMark
    private var lastSampleMark = startMark
    private var lastSampleBytes = 0L
    private var smoothedBytesPerSec = 0.0

    fun onBytes(count: Int) {
        if (count <= 0) return
        var emitProgress = -1f
        var emitDownloaded = 0L
        var emitSpeed = 0L
        withPikoLock(lock) {
            downloadedBytes += count
            val progress = if (totalBytes > 0L) {
                (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            val sinceEmitMs = lastEmitMark.elapsedNow().inWholeMilliseconds
            val progressDelta = progress - lastReportedProgress
            val completed = totalBytes > 0L && downloadedBytes >= totalBytes && lastReportedProgress < 1f
            if (progressDelta >= 0.01f || sinceEmitMs >= 250L || completed) {
                val sampleElapsedMs = lastSampleMark.elapsedNow().inWholeMilliseconds
                if (sampleElapsedMs >= 200L) {
                    val deltaBytes = downloadedBytes - lastSampleBytes
                    val instant = deltaBytes.toDouble() * 1000.0 / sampleElapsedMs.toDouble()
                    smoothedBytesPerSec = if (smoothedBytesPerSec <= 0.0) {
                        instant
                    } else {
                        0.35 * instant + 0.65 * smoothedBytesPerSec
                    }
                    lastSampleMark = TimeSource.Monotonic.markNow()
                    lastSampleBytes = downloadedBytes
                } else if (smoothedBytesPerSec <= 0.0) {
                    val totalElapsedMs = startMark.elapsedNow().inWholeMilliseconds
                    if (totalElapsedMs >= 100L) {
                        smoothedBytesPerSec = downloadedBytes.toDouble() * 1000.0 / totalElapsedMs.toDouble()
                    }
                }
                lastReportedProgress = progress
                lastEmitMark = TimeSource.Monotonic.markNow()
                emitProgress = progress
                emitDownloaded = downloadedBytes
                emitSpeed = smoothedBytesPerSec.toLong().coerceAtLeast(0L)
            }
        }
        if (emitProgress >= 0f) {
            onUpdate(emitProgress, emitDownloaded, totalBytes, emitSpeed)
        }
    }
}

/**
 * 多端共用的检查与下载作用域管理：取 GitHub 最新 Release、比版本、按平台挑附件，
 * 并在进程级 [serviceScope] 中持有下载协程，确保关闭更新弹窗不中断后台下载。
 *
 * [resolve] 返回 null 表示这个 Release 里没有当前平台与架构匹配的附件，此时按「没有新版本」处理，不报错。
 */
abstract class GithubUpdateService<U : AvailableUpdate>(
    protected val releases: GithubReleaseClient,
    private val currentVersion: String,
    /** 开发构建为 false：本地版本号比任何 Release 都小，每次启动都会弹窗。 */
    private val checksOnStartup: Boolean,
    protected val log: (String, Throwable?) -> Unit,
) : AppUpdateService {

    protected val serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val downloadLock = PikoLock()
    private var downloadJob: Job? = null

    final override var status by mutableStateOf<UpdateStatus>(UpdateStatus.Idle)
        protected set

    protected val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val messages: SharedFlow<String> = mutableMessages.asSharedFlow()

    private var startupChecked = false

    protected abstract suspend fun resolve(release: LatestRelease): U?

    /** 下载启动前的前置校验（如 Android 未知来源权限检查或桌面端防重入）；返回 false 时不进入下载态。 */
    protected open fun prepareDownload(update: U): Boolean = true

    /** 在应用级 [serviceScope] 内执行实际下载、校验与暂存/移交安装。 */
    protected abstract suspend fun performDownloadAndInstall(update: U)

    override suspend fun check(silent: Boolean) {
        if (status is UpdateStatus.Checking || status is UpdateStatus.Downloading || status is UpdateStatus.ReadyToRestart) return
        status = UpdateStatus.Checking
        // 被取消时（Android 开屏检查期间界面重建）回到 Idle：停在 Checking 的话，之后的手动检查一进来
        // 就当作「正在查」直接返回，再也查不了
        val result = try {
            when (val check = releases.check(currentVersion)) {
                is ReleaseCheck.Newer -> try {
                    val update = resolve(check.release)
                    PikoLog.i(
                        TAG,
                        "Update check: $currentVersion -> ${check.release.version}" +
                            (update?.let { ", asset ${it.downloadSize} bytes, inAppInstall=${it.canInstallInApp}" }
                                ?: ", no matching platform asset"),
                    )
                    update?.let { UpdateStatus.Available(it) } ?: UpdateStatus.UpToDate
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure(e)
                }
                ReleaseCheck.UpToDate -> UpdateStatus.UpToDate.also {
                    PikoLog.d(TAG, "Update check: $currentVersion is up to date (startup=$silent)")
                }
                is ReleaseCheck.Failed -> failure(check.cause)
            }
        } catch (e: CancellationException) {
            status = UpdateStatus.Idle
            throw e
        }
        status = if (silent && result is UpdateStatus.Failed) UpdateStatus.Idle else result
    }

    final override var startupUpdate by mutableStateOf<AvailableUpdate?>(null)
        private set

    override suspend fun checkOnStartup(isIgnored: suspend (version: String) -> Boolean) {
        if (!checksOnStartup || startupChecked) return
        startupChecked = true
        // 查到一半被取消（界面重建）不算查过，下次进入组合时再查。从检查到读「忽略此版本」都在里面：
        // 读偏好也会挂起，在那里被取消的话，查到的新版就再也不提示了
        try {
            check(silent = true)
            val update = (status as? UpdateStatus.Available)?.update ?: return
            // 上次装这一版没成功：照样弹出，忽略过也不算数，用户点过更新，说明并没有打算跳过它
            if (takePreviousFailure(update.version)) {
                status = UpdateStatus.Failed(
                    tr(
                        "上次更新未完成，当前版本未受影响",
                        "Previous update was incomplete; current version is unchanged",
                        "前回の更新は未完了ですが、現在のバージョンは影響を受けていません",
                        "이전 업데이트가 완료되지 않았으며 현재 버전은 영향이 없습니다",
                        "上次更新未完成，目前版本未受影響",
                    ),
                    update,
                )
                startupUpdate = update
            } else if (!isIgnored(update.version)) {
                startupUpdate = update
            }
        } catch (e: CancellationException) {
            startupChecked = false
            throw e
        }
    }

    override fun startDownload(update: AvailableUpdate): Boolean {
        val own = update.own()
        if (!own.canInstallInApp) return true
        if (status is UpdateStatus.Downloading || status is UpdateStatus.ReadyToRestart) return true
        if (!prepareDownload(own)) return true
        withPikoLock(downloadLock) {
            if (downloadJob?.isActive == true) return true
            status = UpdateStatus.Downloading(
                update = update,
                progress = 0f,
                downloadedBytes = 0L,
                totalBytes = update.downloadSize,
                bytesPerSec = 0L,
                stage = UpdateStage.Downloading,
                modeLabel = update.modeLabel,
            )
            downloadJob = serviceScope.launch {
                try {
                    performDownloadAndInstall(own)
                } catch (e: CancellationException) {
                    withPikoLock(downloadLock) {
                        if (status is UpdateStatus.Downloading) {
                            status = UpdateStatus.Available(update)
                        }
                    }
                    throw e
                } finally {
                    withPikoLock(downloadLock) {
                        if (downloadJob === coroutineContext[Job]) {
                            downloadJob = null
                        }
                    }
                }
            }
        }
        return true
    }

    override fun cancelDownload() {
        val job = withPikoLock(downloadLock) {
            val active = downloadJob
            downloadJob = null
            val current = status
            if (current is UpdateStatus.Downloading) {
                status = UpdateStatus.Available(current.update)
            }
            active
        }
        job?.cancel()
    }

    /**
     * 在 [downloadLock] 下同步上报下载进度：
     * - 下载任务已取消或状态已离开 [UpdateStage.Downloading] 时直接丢弃迟到回调；
     * - 当 `totalBytes` 与 `modeLabel` 一致时强制 `downloadedBytes` 单调递增，避免多线程 Range 下载乱序回退。
     */
    protected fun reportDownloadProgress(
        update: AvailableUpdate,
        progress: Float,
        downloadedBytes: Long,
        totalBytes: Long,
        bytesPerSec: Long,
        modeLabel: String?,
    ) {
        withPikoLock(downloadLock) {
            if (downloadJob?.isActive != true) return
            val current = status as? UpdateStatus.Downloading ?: return
            if (current.update.version != update.version || current.stage != UpdateStage.Downloading) return
            if (current.totalBytes == totalBytes && current.modeLabel == modeLabel && downloadedBytes < current.downloadedBytes) return
            status = UpdateStatus.Downloading(
                update = update,
                progress = progress,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                bytesPerSec = bytesPerSec,
                stage = UpdateStage.Downloading,
                modeLabel = modeLabel,
            )
        }
    }

    /**
     * 在 [downloadLock] 下同步切换下载子阶段（如进入 [UpdateStage.Verifying]/[UpdateStage.Staging]，
     * 或降级回退至完整包时以 [resetProgress] 重置为 0%）。
     */
    protected fun transitionDownloadStage(
        update: AvailableUpdate,
        stage: UpdateStage,
        totalBytes: Long = update.downloadSize,
        modeLabel: String? = update.modeLabel,
        resetProgress: Boolean = false,
    ) {
        withPikoLock(downloadLock) {
            if (downloadJob?.isActive != true) return
            val current = status as? UpdateStatus.Downloading ?: return
            if (current.update.version != update.version) return
            status = if (resetProgress) {
                UpdateStatus.Downloading(
                    update = update,
                    progress = 0f,
                    downloadedBytes = 0L,
                    totalBytes = totalBytes,
                    bytesPerSec = 0L,
                    stage = UpdateStage.Downloading,
                    modeLabel = modeLabel,
                )
            } else {
                UpdateStatus.Downloading(
                    update = update,
                    progress = 1f,
                    downloadedBytes = totalBytes,
                    totalBytes = totalBytes,
                    bytesPerSec = 0L,
                    stage = stage,
                    modeLabel = modeLabel,
                )
            }
        }
    }

    override suspend fun downloadAndInstall(update: AvailableUpdate) {
        startDownload(update)
        val job = withPikoLock(downloadLock) { downloadJob }
        job?.join()
    }

    /**
     * 上次更新到 [version] 是否失败过，读过即清掉。更新在应用退出后才进行，失败时应用已经不在，
     * 只能留下记号、下次启动再说。只有桌面端会这样。
     */
    protected open fun takePreviousFailure(version: String): Boolean = false

    override fun dismissStartupUpdate() {
        startupUpdate = null
    }

    /**
     * 异常原文只进日志。界面上的一句话只回答「先换个网还是稍后再试」，
     * UnknownHostException 之类的原文用户读不懂也用不上。
     */
    private fun failure(cause: Throwable): UpdateStatus.Failed {
        log("Update check failed (current=$currentVersion)", cause)
        return UpdateStatus.Failed(
            if (cause.isNetworkFailure()) {
                tr(
                    "无法连接网络，请检查网络设置后重试",
                    "Unable to connect. Check your network settings and retry",
                    "接続できません。ネットワーク設定を確認して再試行してください",
                    "연결할 수 없습니다. 네트워크 설정을 확인한 후 다시 시도해 주세요",
                    "無法連線網路，請檢查網路設定後重試",
                )
            } else {
                tr(
                    "检查更新失败，请稍后重试",
                    "Update check failed. Try again later",
                    "更新を確認できませんでした。しばらくしてから再試行してください",
                    "업데이트를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요",
                    "檢查更新失敗，請稍後重試",
                )
            },
        )
    }

    /** 下载与校验失败，措辞同上。校验不符单独说：重试多半还是同一个结果，该去下载页。 */
    protected fun downloadFailed(cause: Throwable, update: AvailableUpdate): UpdateStatus.Failed {
        log("Update download failed for ${update.version}", cause)
        val message = when {
            cause is ChecksumMismatchException -> tr(
                "安装包校验未通过，请从下载页获取",
                "Package verification failed. Use the download page",
                "パッケージの検証に失敗しました。ダウンロードページから取得してください",
                "설치 패키지 검증에 실패했습니다. 다운로드 페이지에서 받으세요",
                "安裝套件驗證未通過，請從下載頁取得",
            )
            cause.isNetworkFailure() -> tr(
                "无法连接网络，请检查网络设置后重试",
                "Unable to connect. Check your network settings and retry",
                "接続できません。ネットワーク設定を確認して再試行してください",
                "연결할 수 없습니다. 네트워크 설정을 확인한 후 다시 시도해 주세요",
                "無法連線網路，請檢查網路設定後重試",
            )
            else -> tr(
                "下载未完成，请稍后重试",
                "Download incomplete. Try again later",
                "ダウンロードが完了しませんでした。しばらくしてから再試行してください",
                "다운로드가 완료되지 않았습니다. 잠시 후 다시 시도해 주세요",
                "下載未完成，請稍後重試",
            )
        }
        return UpdateStatus.Failed(message, update)
    }

    @Suppress("UNCHECKED_CAST")
    protected fun AvailableUpdate.own(): U = this as U

    private companion object {
        const val TAG = "Update"
    }
}
