package dev.piko.shared.media.player

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.piko.shared.media.ORIGINAL_QUALITY
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.media.PlayableMediaInfo
import dev.piko.shared.media.PlayableMediaKind
import dev.piko.shared.media.PreparedPlayback
import dev.piko.shared.media.bestTranscodeName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放器的取流策略与播放状态，两端共用。
 *
 * 取流顺序：本机完整副本 → 本机代理（SDK reader）→ 直链 → 转码流。前三步读的是同一份字节，
 * 只在「还没出第一帧就失败」时往下走一级；播到一半断掉则原路退避重连，从断点续上。
 * 续播位置每 5 秒写一次、离开时补一次，放到最后十秒记为 0。
 *
 * 控件与画面表面各端自己做，这里只暴露控件需要的纯数据与动作，让控件保持无状态。
 * 形状与 DriveScreenState 相同：Compose State、派生值走 derivedStateOf、一次性提示走 [messages]。
 */
class PlayerScreenState(
    private val repository: PikoMediaRepository,
    private val backend: PlaybackBackend,
    private val scope: CoroutineScope,
    initialFileId: String,
    initialFileName: String,
    initialLocalPath: String? = null,
    /**
     * 这个文件在本机的完整副本，没有返回 null。[hint] 是调用方已知的路径，可能已被删除。
     * 分段下载的片段不算完整副本，由平台自己排除。
     */
    private val resolveLocalPath: suspend (fileId: String, hint: String?) -> String? = { _, hint -> hint },
) {
    var fileId by mutableStateOf(initialFileId)
        private set
    var title by mutableStateOf(initialFileName)
        private set
    private var localPathHint: String? = initialLocalPath

    var isLocalPlayback by mutableStateOf(false)
        private set
    var mediaInfo by mutableStateOf<PlayableMediaInfo?>(null)
        private set
    var isPreparing by mutableStateOf(true)
        private set

    /** 退避重连或换源途中。期间不报错，否则每次重连都会闪一次失败卡片。 */
    var isRecovering by mutableStateOf(false)
        private set
    private var failure by mutableStateOf<String?>(null)

    /** 实际在放的变体，null 为原画。与用户所选不同之处在于它包含自动换上的转码流。 */
    private var activeQuality by mutableStateOf<String?>(null)

    /** 本次从续播位置开始时非 null，5 秒后清掉；控件据此显示「从头播放」提示。 */
    var resumedFromMillis by mutableStateOf<Long?>(null)
        private set

    val isImage by derivedStateOf { mediaInfo?.kind == PlayableMediaKind.Image }

    val isPlaying: Boolean get() = backend.isPlaying
    val positionMillis: Long get() = backend.positionMillis
    val bufferedPositionMillis: Long get() = backend.bufferedPositionMillis
    val aspectRatio: PlayerAspectRatio? get() = backend.aspectRatio

    val isLoading by derivedStateOf { isPreparing || isRecovering || (!isImage && backend.isBuffering) }

    val durationMillis by derivedStateOf {
        backend.durationMillis.takeIf { it > 0L } ?: ((mediaInfo?.durationSeconds ?: 0L) * 1000L)
    }

    /** 后端不能调速时为 null，控件据此隐藏倍速入口。 */
    val playbackSpeed by derivedStateOf { if (backend.supportsSpeed) backend.speed else null }

    val qualityOptions by derivedStateOf {
        val info = mediaInfo
        if (isLocalPlayback || info == null || info.kind != PlayableMediaKind.Video) {
            emptyList()
        } else {
            val variants = info.availableVariants
                .map { it.mediaName.ifBlank { it.resolutionName } }
                .filter { it.isNotBlank() }
            if (variants.isEmpty()) emptyList() else (listOf(ORIGINAL_QUALITY) + variants).distinct()
        }
    }

    val currentQuality by derivedStateOf {
        if (qualityOptions.isEmpty()) null else activeQuality ?: ORIGINAL_QUALITY
    }

    val errorMessage by derivedStateOf { if (isRecovering) null else failure }

    /** 横向画面。优先信后端解出的画面参数（已计入旋转），其次信服务端元数据。 */
    val isLandscapeVideo by derivedStateOf {
        backend.videoAspect?.let { it > 1f } ?: mediaInfo?.isLandscapeVideo
    }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var prepared: PreparedPlayback? = null
    private var prepareJob: Job? = null
    private var recoveryJob: Job? = null
    private var resumeTipJob: Job? = null

    private var requestedQuality: String? = null
    private var pendingStartMillis: Long? = null

    /** 本轮 open 用的起点，还没出第一帧就换源时从这里重来。 */
    private var attemptStartMillis = 0L
    private var usingProxy = false
    private var preferDirectLink = false
    private var directLinkTried = false
    private var stableJob: Job? = null
    private var startedThisAttempt = false
    private var retryAttempt = 0

    // 出错瞬间后端的位置可能已经归零，重连与持久化都用这个
    private var lastKnownPositionMillis = 0L
    private var released = false

    /** 续播记录的键。从下载页进来的本地文件可能没有 fileId，只能退回路径。 */
    private val positionKey: String get() = fileId.ifBlank { localPathHint.orEmpty() }

    init {
        scope.launch { backend.events.collect(::onBackendEvent) }
        scope.launch {
            snapshotFlow { backend.positionMillis }.collect { position ->
                // 新文件出第一帧前，后端报的可能还是上一个文件的位置
                if (startedThisAttempt && position > 0L) lastKnownPositionMillis = position
            }
        }
        scope.launch { persistLoop() }
        reload()
    }

    fun togglePlayPause() {
        if (backend.isPlaying) backend.pause() else backend.play()
    }

    fun play() = backend.play()

    fun pause() = backend.pause()

    fun seekTo(positionMillis: Long) {
        val upper = durationMillis.takeIf { it > 0L } ?: Long.MAX_VALUE
        backend.seekTo(positionMillis.coerceIn(0L, upper))
    }

    fun seekBy(deltaMillis: Long) = seekTo(backend.positionMillis + deltaMillis)

    fun setSpeed(speed: Float) = backend.setSpeed(speed)

    fun setAspectRatio(mode: PlayerAspectRatio) = backend.setAspectRatio(mode)

    fun selectQuality(quality: String) {
        pendingStartMillis = currentPosition()
        requestedQuality = quality
        // 切清晰度是用户动作，不是故障：退避次数与换源进度都给新流重新算
        resetRecovery()
        reload()
    }

    fun retry() {
        pendingStartMillis = currentPosition()
        resetRecovery()
        reload()
    }

    fun restartFromBeginning() {
        backend.seekTo(0L)
        resumedFromMillis = null
    }

    fun dismissResumeTip() {
        resumedFromMillis = null
    }

    /** 同目录换片。清晰度、续播与重试状态都跟着新文件重来。 */
    fun switchTo(fileId: String, fileName: String, localPath: String? = null) {
        if (fileId == this.fileId && localPath == localPathHint) return
        val previousKey = positionKey
        val previousPosition = lastKnownPositionMillis
        val previousDuration = durationMillis
        scope.launch { persist(previousKey, previousPosition, previousDuration) }

        this.fileId = fileId
        title = fileName
        localPathHint = localPath
        mediaInfo = null
        activeQuality = null
        requestedQuality = null
        pendingStartMillis = null
        lastKnownPositionMillis = 0L
        resetRecovery()
        reload()
    }

    /** 播放途中才发现本机有完整副本（如下载刚完成），从当前位置换到本地文件。 */
    fun useLocalCopy(path: String) {
        if (isLocalPlayback) return
        localPathHint = path
        pendingStartMillis = currentPosition()
        resetRecovery()
        reload()
    }

    /** 释放代理会话。后端由创建它的一方释放，续播位置由作用域取消时补写。 */
    fun release() {
        released = true
        prepareJob?.cancel()
        recoveryJob?.cancel()
        closePrepared()
    }

    private fun currentPosition(): Long =
        backend.positionMillis.takeIf { it > 0L && startedThisAttempt } ?: lastKnownPositionMillis

    private fun resetRecovery() {
        recoveryJob?.cancel()
        stableJob?.cancel()
        retryAttempt = 0
        preferDirectLink = false
        directLinkTried = false
        isRecovering = false
    }

    private fun reload() {
        prepareJob?.cancel()
        prepareJob = scope.launch { prepare() }
    }

    private suspend fun prepare() {
        isPreparing = true
        failure = null
        startedThisAttempt = false
        resumeTipJob?.cancel()
        resumedFromMillis = null
        backend.stop()
        closePrepared()
        try {
            val pending = pendingStartMillis
            pendingStartMillis = null
            val saved = if (pending == null) {
                repository.getPlaybackPosition(positionKey).takeIf { it > RESUME_THRESHOLD_MILLIS }
            } else {
                null
            }
            val startMillis = pending ?: saved ?: 0L
            attemptStartMillis = startMillis

            val localPath = resolveLocalPath(fileId, localPathHint)
            if (localPath != null) {
                isLocalPlayback = true
                usingProxy = false
                activeQuality = null
                backend.open(PlaybackTarget.LocalFile(localPath), startMillis)
            } else {
                isLocalPlayback = false
                val playback = repository.preparePlayback(fileId, requestedQuality).getOrThrow()
                prepared = playback
                mediaInfo = playback.info
                activeQuality = requestedQuality?.takeUnless { it == ORIGINAL_QUALITY }
                when (playback.info.kind) {
                    PlayableMediaKind.Image -> Unit
                    PlayableMediaKind.UnsupportedImage -> failure = "GIF 暂不支持预览"
                    PlayableMediaKind.Video -> {
                        val proxyUrl = playback.proxyUrl.takeUnless { preferDirectLink }
                        usingProxy = proxyUrl != null
                        backend.open(PlaybackTarget.Url(proxyUrl ?: playback.info.currentUrl), startMillis)
                    }
                }
            }
            if (saved != null && !isImage) showResumeTip(saved)
            isPreparing = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e.message ?: "无法打开媒体"
            isPreparing = false
            // 这一轮连流都没拿到，退避到此为止，否则失败卡片会被加载态一直挡着
            isRecovering = false
        }
    }

    private fun onBackendEvent(event: PlaybackBackendEvent) {
        if (released) return
        when (event) {
            PlaybackBackendEvent.Ready -> {
                startedThisAttempt = true
                isRecovering = false
                // 出第一帧不代表故障过去了：流每次放几秒就断，立刻清零会无限重连。
                // 稳定播放一段时间才把退避次数还原
                stableJob?.cancel()
                stableJob = scope.launch {
                    delay(STABLE_PLAYBACK_MILLIS)
                    retryAttempt = 0
                }
            }

            PlaybackBackendEvent.Ended -> {
                val key = positionKey
                scope.launch { runCatchingNonCancel { repository.savePlaybackPosition(key, 0L) } }
            }

            is PlaybackBackendEvent.Error -> onPlaybackError(event.detail)
        }
    }

    private fun onPlaybackError(detail: String?) {
        stableJob?.cancel()
        val message = detail?.takeIf { it.isNotBlank() }?.let { "播放失败：$it" } ?: "播放中断"
        // 本地文件重来一遍还是同一个错误，直接交给用户
        if (isLocalPlayback) {
            failure = message
            return
        }

        if (!startedThisAttempt) {
            // 还没出第一帧：同一条路原样重试没有意义，换一条
            pendingStartMillis = attemptStartMillis
            if (usingProxy && !directLinkTried) {
                // 代理与直链读的是同一份字节，先排除代理本身的问题。只试一次：
                // 直链也失败就说明坏的不是代理，换到转码流后不必再绕一遍直链
                directLinkTried = true
                preferDirectLink = true
                isRecovering = true
                reload()
                return
            }
            val transcode = mediaInfo?.bestTranscodeName()
            if (activeQuality == null && transcode != null) {
                preferDirectLink = false
                requestedQuality = transcode
                isRecovering = true
                _messages.tryEmit("原画无法播放，已切换到转码画质")
                reload()
                return
            }
            pendingStartMillis = null
            isRecovering = false
            failure = message
            return
        }

        if (retryAttempt >= RECOVERY_DELAYS_MILLIS.size) {
            isRecovering = false
            failure = message
            return
        }
        val resumeFrom = currentPosition()
        isRecovering = true
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            delay(RECOVERY_DELAYS_MILLIS[retryAttempt])
            retryAttempt += 1
            pendingStartMillis = resumeFrom
            reload()
        }
    }

    private fun showResumeTip(fromMillis: Long) {
        resumedFromMillis = fromMillis
        resumeTipJob?.cancel()
        resumeTipJob = scope.launch {
            delay(RESUME_TIP_MILLIS)
            resumedFromMillis = null
        }
    }

    private suspend fun persistLoop() {
        try {
            while (true) {
                delay(PERSIST_INTERVAL_MILLIS)
                persist(positionKey, lastKnownPositionMillis, durationMillis)
            }
        } finally {
            // 离开播放器时补一次，否则最后不足 5 秒的进度会丢
            withContext(NonCancellable) {
                persist(positionKey, lastKnownPositionMillis, durationMillis)
            }
        }
    }

    private suspend fun persist(key: String, positionMillis: Long, durationMillis: Long) {
        if (key.isBlank() || isImage) return
        runCatchingNonCancel {
            // 放完最后十秒记 0，下次从头播，而不是停在片尾
            if (durationMillis > 0L && positionMillis >= durationMillis - NEAR_END_MILLIS) {
                repository.savePlaybackPosition(key, 0L)
            } else if (positionMillis > MIN_PERSIST_MILLIS) {
                repository.savePlaybackPosition(key, positionMillis)
            }
        }
    }

    private fun closePrepared() {
        prepared?.close()
        prepared = null
    }

    private suspend fun runCatchingNonCancel(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 持久化失败不能掐掉播放
        }
    }

    private companion object {
        const val RESUME_THRESHOLD_MILLIS = 3_000L
        const val NEAR_END_MILLIS = 10_000L
        const val MIN_PERSIST_MILLIS = 1_500L
        const val PERSIST_INTERVAL_MILLIS = 5_000L
        const val RESUME_TIP_MILLIS = 5_000L
        const val STABLE_PLAYBACK_MILLIS = 15_000L
        val RECOVERY_DELAYS_MILLIS = longArrayOf(500L, 1_500L, 4_000L)
    }
}
