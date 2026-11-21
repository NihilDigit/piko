package dev.piko.shared.media.proxy

import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFile
import dev.piko.shared.media.MediaPlaybackService
import dev.piko.shared.media.PreparedPlayback
import dev.piko.shared.memory.PikoMemoryGovernor
import dev.piko.shared.util.PikoLock
import dev.piko.shared.util.withPikoLock
import io.github.nihildigit.pikpak.BufferBudget
import io.github.nihildigit.pikpak.ConsumerIntent
import io.github.nihildigit.pikpak.ServiceIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val TAG = "ExternalPlayer"

/**
 * 为外部播放器开一个代理会话，返回 127.0.0.1 的地址。
 *
 * 取原画：外部播放器自己解码，也不会随网速换清晰度。没有 gcid 或代理不可用时建不起代理会话，
 * 这时宁可失败也不交出直链：直链几小时后过期，外部播放器拖动或续播时就断了，代理会重取。
 */
suspend fun MediaPlaybackService.openForExternalPlayer(fileId: String): Result<String> {
    val playback = preparePlayback(fileId).getOrElse { return Result.failure(it) }
    val fileLabel = logFile(fileId, playback.info.name)
    val url = playback.proxyUrl
    if (url == null) {
        val missingHash = playback.info.gcid.isBlank()
        PikoLog.w(TAG, "Failed to open external player stream: $fileLabel, reason=${if (missingHash) "missing_gcid" else "proxy_unavailable"}")
        playback.close()
        val message = if (missingHash) {
            tr("文件缺少内容哈希，无法读取", "File is missing content hash and cannot be read", "ファイルにハッシュ情報がないため読み込めません", "파일에 콘텐츠 해시가 없어 읽을 수 없습니다", "檔案缺少內容雜湊值，無法讀取")
        } else {
            tr("无法创建本地播放会话", "Unable to create local playback session", "ローカル再生セッションを作成できません", "로컬 재생 세션을 만들 수 없습니다", "無法建立本機播放工作階段")
        }
        return Result.failure(IllegalStateException(message))
    }
    try {
        ExternalPlayerStreams.retain(PreparedExternalPlayerHandle(fileId, fileLabel, playback))
    } catch (e: CancellationException) {
        playback.close()
        throw e
    }
    PikoLog.i(TAG, "Opened external player stream: $fileLabel")
    return Result.success(url)
}

internal interface ExternalPlayerHandle : AutoCloseable {
    val fileId: String
    val displayName: String
    val label: String
    fun activatePlaying(epoch: Long)
    fun pauseSpeculation()
    fun setOnReadActivity(callback: (() -> Unit)?)
}

private class PreparedExternalPlayerHandle(
    override val fileId: String,
    override val label: String,
    private val playback: PreparedPlayback,
) : ExternalPlayerHandle {
    override val displayName: String get() = playback.info.name

    override fun activatePlaying(epoch: Long) {
        playback.submitIntent(
            ConsumerIntent(
                epoch = epoch,
                service = ServiceIntent.PLAYING,
                bufferBudget = playback.defaultBufferBudget,
            ),
        )
    }

    override fun pauseSpeculation() {
        playback.withdrawSpeculation()
        playback.submitIntent(
            ConsumerIntent(
                epoch = playback.demand.intent.epoch,
                service = ServiceIntent.PAUSED,
                bufferBudget = BufferBudget.NONE,
            ),
        )
    }

    override fun setOnReadActivity(callback: (() -> Unit)?) {
        playback.setOnReadActivity(callback)
    }

    override fun close() {
        playback.setOnReadActivity(null)
        playback.close()
    }
}

/**
 * 管理交给外部播放器的代理会话：
 * 1. 新打开流即切换为 `ServiceIntent.PLAYING`，避免长时间停在 `WAITING` 导致预读受限与抖动；
 * 2. 同文件重开直接替换旧会话，防止同一文件保留多条会话互相抢占连接槽位引发连续 `Preempted`；
 * 3. 同时仅允许一条活跃外部会话进行投机预读，其余保留会话降为 `PAUSED`（保留回环 URL，读请求到来时自动恢复）；
 * 4. 空闲超时后自动撤回预读并结束后台保活，退出/切换账号时统一关闭全部外部会话。
 */
internal class ExternalPlayerStreamRegistry(
    private val scope: CoroutineScope,
    private val idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MILLIS,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val onActiveChanged: (Boolean) -> Unit = {},
) {
    private class RetainedStream(val handle: ExternalPlayerHandle)

    private val lock = PikoLock()
    private val retained = ArrayDeque<RetainedStream>()
    private var activeStream: RetainedStream? = null
    private var epoch = 0L
    private var idleJob: Job? = null
    private var lastActivityMark: TimeMark? = null

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _activeFileName = MutableStateFlow<String?>(null)
    val activeFileName: StateFlow<String?> = _activeFileName.asStateFlow()

    fun retain(handle: ExternalPlayerHandle) {
        val toClose = ArrayList<ExternalPlayerHandle>(2)
        val toPause = ArrayList<ExternalPlayerHandle>(capacity)
        val entry = RetainedStream(handle)
        val newEpoch: Long
        withPikoLock(lock) {
            val iter = retained.iterator()
            while (iter.hasNext()) {
                val existing = iter.next()
                if (existing.handle.fileId == handle.fileId) {
                    iter.remove()
                    if (activeStream === existing) activeStream = null
                    toClose += existing.handle
                } else {
                    toPause += existing.handle
                }
            }
            retained.addLast(entry)
            while (retained.size > capacity) {
                val evicted = retained.removeFirst()
                if (activeStream === evicted) activeStream = null
                toClose += evicted.handle
            }
            activeStream = entry
            newEpoch = ++epoch
            lastActivityMark = TimeSource.Monotonic.markNow()
            scheduleIdleLocked()
        }
        toPause.forEach { other ->
            runCatching { other.pauseSpeculation() }
        }
        handle.setOnReadActivity { onReadActivity(entry) }
        handle.activatePlaying(newEpoch)
        publishActive(true, handle.displayName)
        toClose.forEach { old ->
            val reason = if (old.fileId == handle.fileId) "replaced" else "capacity=$capacity"
            PikoLog.i(TAG, "Closing superseded external player stream ($reason): ${old.label}")
            runCatching { old.close() }
                .onFailure { PikoLog.w(TAG, "Failed to close external player stream: ${old.label}", it) }
        }
    }

    private fun onReadActivity(entry: RetainedStream) {
        var promoteEpoch: Long? = null
        var previousToPause: ExternalPlayerHandle? = null
        var displayName: String? = null
        var shouldPublish = false
        withPikoLock(lock) {
            if (retained.none { it === entry }) return
            val now = TimeSource.Monotonic.markNow()
            val last = lastActivityMark
            val sameActive = activeStream === entry && _active.value
            if (sameActive && last != null && last.elapsedNow().inWholeMilliseconds < ACTIVITY_DEBOUNCE_MILLIS) {
                return
            }
            lastActivityMark = now
            if (activeStream !== entry || !_active.value) {
                previousToPause = activeStream?.takeIf { it !== entry }?.handle
                activeStream = entry
                promoteEpoch = ++epoch
                displayName = entry.handle.displayName
                shouldPublish = true
            }
            scheduleIdleLocked()
        }
        previousToPause?.let { runCatching { it.pauseSpeculation() } }
        promoteEpoch?.let { nextEpoch ->
            runCatching { entry.handle.activatePlaying(nextEpoch) }
        }
        if (shouldPublish) {
            publishActive(true, displayName)
        }
    }

    private fun scheduleIdleLocked() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(idleTimeoutMillis)
            onIdleTimeout()
        }
    }

    private fun onIdleTimeout() {
        val toPause: List<ExternalPlayerHandle>
        val becameInactive: Boolean
        withPikoLock(lock) {
            val last = lastActivityMark
            if (last != null && last.elapsedNow().inWholeMilliseconds < idleTimeoutMillis) {
                val remaining = (idleTimeoutMillis - last.elapsedNow().inWholeMilliseconds).coerceAtLeast(100L)
                idleJob = scope.launch {
                    delay(remaining)
                    onIdleTimeout()
                }
                return
            }
            becameInactive = _active.value || activeStream != null
            activeStream = null
            idleJob = null
            toPause = retained.map { it.handle }
        }
        toPause.forEach { handle ->
            runCatching { handle.pauseSpeculation() }
        }
        if (becameInactive) {
            PikoLog.d(TAG, "External player streams idle for ${idleTimeoutMillis}ms; paused speculation and released background keepalive")
            publishActive(false, null)
        }
    }

    fun closeAll() {
        val toClose: List<ExternalPlayerHandle>
        withPikoLock(lock) {
            idleJob?.cancel()
            idleJob = null
            activeStream = null
            lastActivityMark = null
            toClose = retained.map { it.handle }
            retained.clear()
        }
        publishActive(false, null)
        toClose.forEach { old ->
            runCatching { old.close() }
                .onFailure { PikoLog.w(TAG, "Failed to close external player stream: ${old.label}", it) }
        }
    }

    private fun publishActive(isActive: Boolean, fileName: String?) {
        val changed = _active.value != isActive
        _activeFileName.value = if (isActive) fileName else null
        _active.value = isActive
        if (changed) onActiveChanged(isActive)
    }

    companion object {
        const val DEFAULT_CAPACITY = 4
        const val DEFAULT_IDLE_TIMEOUT_MILLIS = 90_000L
        private const val ACTIVITY_DEBOUNCE_MILLIS = 1_000L
    }
}

/** 进程级外部播放器流注册表与后台活跃状态源。 */
object ExternalPlayerStreams {
    private val registry = ExternalPlayerStreamRegistry(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        onActiveChanged = PikoMemoryGovernor.Shared::setBackgroundPlaybackActive,
    )

    /** 外部播放器当前是否处于活跃拉流/保活窗口内。 */
    val active: StateFlow<Boolean> get() = registry.active

    /** 当前活跃外部播放器正在播放的文件名（用于后台保活通知）。 */
    val activeFileName: StateFlow<String?> get() = registry.activeFileName

    internal fun retain(handle: ExternalPlayerHandle) = registry.retain(handle)

    fun closeAll() = registry.closeAll()
}
