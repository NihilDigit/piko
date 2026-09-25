package dev.piko.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.update.ChecksumMismatchException
import dev.piko.shared.update.GithubReleaseClient
import dev.piko.shared.update.LatestRelease
import dev.piko.shared.update.ReleaseCheck
import dev.piko.shared.update.isNetworkFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 可供更新的版本。安装所需的附件由各平台的实现带着，界面只读这几项。 */
interface AvailableUpdate {
    val version: String
    val notes: String
    val pageUrl: String

    /** 这次实际要下载的字节数。桌面端增量更新时远小于整个安装包。 */
    val downloadSize: Long

    /**
     * 为 false 时只给下载页链接：Android 的 debug 包与 Release 包名不同，装上去是另一个应用；
     * 桌面端的便携版要整包更新时没有安装器可用。
     */
    val canInstallInApp: Boolean
}

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Available(val update: AvailableUpdate) : UpdateStatus
    data class Downloading(val update: AvailableUpdate, val progress: Float) : UpdateStatus

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
    /** 由 Compose State 支撑，设置页直接读。 */
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

    /** Android 下完即交给系统安装器；桌面端下完停在 [UpdateStatus.ReadyToRestart]。 */
    suspend fun downloadAndInstall(update: AvailableUpdate)

    /** 桌面端：退出并替换文件，完成后重新启动。 */
    suspend fun restartToInstall(update: AvailableUpdate) {}
}

/**
 * 两端共用的检查逻辑：取 GitHub 最新 Release、比版本、按平台挑附件。
 *
 * [resolve] 返回 null 表示这个 Release 里还没有本平台的附件。桌面端的附件由另一条流水线
 * 在 Release 建好后几分钟才挂上，这段时间按「没有新版本」处理，不报错。
 */
abstract class GithubUpdateService<U : AvailableUpdate>(
    protected val releases: GithubReleaseClient,
    private val currentVersion: String,
    /** 开发构建为 false：本地版本号比任何 Release 都小，每次启动都会弹窗。 */
    private val checksOnStartup: Boolean,
    protected val log: (String, Throwable?) -> Unit,
) : AppUpdateService {

    final override var status by mutableStateOf<UpdateStatus>(UpdateStatus.Idle)
        protected set

    protected val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val messages: SharedFlow<String> = mutableMessages.asSharedFlow()

    private var startupChecked = false

    protected abstract suspend fun resolve(release: LatestRelease): U?

    override suspend fun check(silent: Boolean) {
        if (status is UpdateStatus.Checking || status is UpdateStatus.Downloading || status is UpdateStatus.ReadyToRestart) return
        status = UpdateStatus.Checking
        val result = when (val check = releases.check(currentVersion)) {
            is ReleaseCheck.Newer -> try {
                resolve(check.release)?.let { UpdateStatus.Available(it) } ?: UpdateStatus.UpToDate
            } catch (e: CancellationException) {
                status = UpdateStatus.Idle
                throw e
            } catch (e: Exception) {
                failure(e)
            }
            ReleaseCheck.UpToDate -> UpdateStatus.UpToDate
            is ReleaseCheck.Failed -> failure(check.cause)
        }
        status = if (silent && result is UpdateStatus.Failed) UpdateStatus.Idle else result
    }

    final override var startupUpdate by mutableStateOf<AvailableUpdate?>(null)
        private set

    override suspend fun checkOnStartup(isIgnored: suspend (version: String) -> Boolean) {
        if (!checksOnStartup || startupChecked) return
        startupChecked = true
        check(silent = true)
        val update = (status as? UpdateStatus.Available)?.update ?: return
        if (!isIgnored(update.version)) startupUpdate = update
    }

    override fun dismissStartupUpdate() {
        startupUpdate = null
    }

    /**
     * 异常原文只进日志。界面上的一句话只回答「先换个网还是稍后再试」，
     * UnknownHostException 之类的原文用户读不懂也用不上。
     */
    private fun failure(cause: Throwable): UpdateStatus.Failed {
        log("检查更新失败", cause)
        return UpdateStatus.Failed(if (cause.isNetworkFailure()) "网络不通，检查网络后重试" else "检查更新未完成，稍后重试")
    }

    /** 下载与校验失败，措辞同上。校验不符单独说：重试多半还是同一个结果，该去下载页。 */
    protected fun downloadFailed(cause: Throwable, update: AvailableUpdate): UpdateStatus.Failed {
        log("下载更新失败", cause)
        val message = when {
            cause is ChecksumMismatchException -> "安装包校验未通过，请从下载页获取"
            cause.isNetworkFailure() -> "网络不通，检查网络后重试"
            else -> "下载未完成，稍后重试"
        }
        return UpdateStatus.Failed(message, update)
    }

    @Suppress("UNCHECKED_CAST")
    protected fun AvailableUpdate.own(): U = this as U
}
