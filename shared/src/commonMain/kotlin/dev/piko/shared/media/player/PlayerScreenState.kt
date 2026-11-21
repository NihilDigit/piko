package dev.piko.shared.media.player

import kotlinx.coroutines.IO
import dev.piko.shared.i18n.tr

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.piko.data.auth.PlayerGestureDefaults
import dev.piko.data.repository.isPlayableAudio
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFile
import dev.piko.shared.media.AUTO_QUALITY
import dev.piko.shared.media.MediaService
import dev.piko.shared.media.ORIGINAL_QUALITY
import dev.piko.shared.media.PlayableMediaInfo
import dev.piko.shared.media.PlayableMediaKind
import dev.piko.shared.media.PreparedPlayback
import dev.piko.shared.media.bestTranscodeName
import dev.piko.shared.media.transcodeName
import dev.piko.shared.media.proxy.ProxyStream
import dev.piko.shared.media.storyboard.StoryboardSource
import dev.piko.shared.media.storyboard.StoryboardSourceResolver
import dev.piko.shared.runtime.PikoWorkloadGovernor
import dev.piko.shared.runtime.WorkPhase
import dev.piko.shared.runtime.WorkPurpose
import io.github.nihildigit.pikpak.BufferBudget
import io.github.nihildigit.pikpak.ConsumerIntent
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PlaybackBufferSnapshot
import io.github.nihildigit.pikpak.ServiceIntent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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
    private val repository: MediaService,
    private val backend: PlaybackBackend,
    private val scope: CoroutineScope,
    initialFileId: String,
    initialFileName: String,
    initialLocalPath: String? = null,
    /** 从这里开播，不查续播记录。随机片段里「看完整」从正在看的那一处接着放。 */
    initialStartMillis: Long? = null,
    /**
     * 这个文件在本机的完整副本，没有返回 null。[hint] 是调用方已知的路径，可能已被删除。
     * 分段下载的片段不算完整副本，由平台自己排除。
     */
    private val resolveLocalPath: suspend (fileId: String, hint: String?) -> String? = { _, hint -> hint },
    /**
     * 设置里的播放画质：-1（[PlayerGestureDefaults.PLAYBACK_AUTO_HEIGHT]）是自动，0 是原画，正整数是画面高度上限。
     * 每次准备时读，播放器开着时改了设置，下一集起生效。
     * 挂起取值而不是收集成 State：头一集准备时收集多半还没拿到第一个值，会按默认值开。
     */
    private val defaultMaxHeight: suspend () -> Int = { PlayerGestureDefaults.PLAYBACK_AUTO_HEIGHT },
) {
    var fileId by mutableStateOf(initialFileId)
        private set
    var title by mutableStateOf(initialFileName)
        private set
    private var localPathHint: String? = initialLocalPath

    var isLocalPlayback by mutableStateOf(false)
        private set
    /** 当前本地播放时实际打开的本机路径/URI，供预览帧缓存本地抽帧使用。 */
    var resolvedLocalPath by mutableStateOf<String?>(null)
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

    /** 是否处于「自动」动态画质模式：按倍速有效码率需求、实测吞吐与缓冲余量在原画与转码档位间平滑升降。 */
    var isAutoQuality by mutableStateOf(false)
        private set

    /** 当播放转码流且原画为大体积未缓存 MKV 时，探测到的原画内封字幕轨元数据（供字幕列表提示）。 */
    var originalSubtitleHints by mutableStateOf<List<OriginalSubtitleTrackInfo>>(emptyList())
        private set

    /** 本次从续播位置开始时非 null，5 秒后清掉；控件据此显示「从头播放」提示。 */
    var resumedFromMillis by mutableStateOf<Long?>(null)
        private set

    val isImage by derivedStateOf { mediaInfo?.kind == PlayableMediaKind.Image }
    val isAudioFile by derivedStateOf { mediaInfo?.kind == PlayableMediaKind.Audio || title.isPlayableAudio() }

    private val knownContent
        get() = if (isLocalPlayback || fileId.isBlank()) null else repository.knownContentMetadata(fileId)

    val isPlaying: Boolean get() = backend.isPlaying
    val positionMillis: Long get() = backend.positionMillis
    val bufferedPositionMillis: Long get() = backend.bufferedPositionMillis
    val aspectRatio: PlayerAspectRatio? get() = backend.aspectRatio
    val cropRegion: VideoCropRegion? get() = backend.cropRegion
    val videoAspect: Float? by derivedStateOf {
        backend.videoAspect ?: mediaInfo?.let { info ->
            if (info.width > 0 && info.height > 0) {
                if (rotationDegrees % 180 == 0) info.width.toFloat() / info.height
                else info.height.toFloat() / info.width
            } else null
        } ?: knownContent?.let { content ->
            val w = content.width ?: 0
            val h = content.height ?: 0
            if (w > 0 && h > 0) {
                if (rotationDegrees % 180 == 0) w.toFloat() / h else h.toFloat() / w
            } else null
        }
    }

    val isLoading by derivedStateOf { isPreparing || isRecovering || (!isImage && (backend.isBuffering || seeking)) }

    /** 加载与定位缓冲期间的实时网络速度（如 `"4.2 MB/s"` 或 `"680 KB/s"`）；本地播放或未采样到时为 null。 */
    var loadingSpeedText by mutableStateOf<String?>(null)
        private set

    val durationMillis by derivedStateOf {
        backend.durationMillis.takeIf { it > 0L }
            ?: ((mediaInfo?.durationSeconds ?: 0L) * 1000L).takeIf { it > 0L }
            ?: (knownContent?.durationMillis ?: 0L)
    }

    /** 后端不能调速时为 null，控件据此隐藏倍速入口。 */
    val playbackSpeed by derivedStateOf { if (backend.supportsSpeed) backend.speed else null }

    val qualityOptions by derivedStateOf {
        val info = mediaInfo
        if (isLocalPlayback) {
            emptyList()
        } else if (info != null) {
            if (info.kind != PlayableMediaKind.Video) {
                emptyList()
            } else {
                val variants = info.availableVariants
                    .filter { !it.isOrigin && it.video != null && it.link.url.isNotBlank() }
                    .map { it.mediaName.ifBlank { it.resolutionName } }
                    .filter { it.isNotBlank() }
                if (variants.isEmpty()) emptyList() else (listOf(AUTO_QUALITY, ORIGINAL_QUALITY) + variants).distinct()
            }
        } else {
            val transcodes = knownContent?.transcodes
                ?.filter { !it.unreadable && it.name.isNotBlank() }
                ?.map { it.name }
                .orEmpty()
            if (transcodes.isEmpty()) emptyList() else (listOf(AUTO_QUALITY, ORIGINAL_QUALITY) + transcodes).distinct()
        }
    }

    val currentQuality by derivedStateOf {
        if (qualityOptions.isEmpty()) null
        else if (isAutoQuality) AUTO_QUALITY
        else activeQuality ?: ORIGINAL_QUALITY
    }

    /** 处于「自动」画质时实际正在播放的档位（`ORIGINAL_QUALITY` 或转码名），非自动模式为 null。 */
    val autoResolvedQuality by derivedStateOf {
        if (qualityOptions.isEmpty() || !isAutoQuality) null else activeQuality ?: ORIGINAL_QUALITY
    }

    /**
     * 经过严格 `<= 480P` MPEG-TS 门控的雪碧图源；纯原画视频、最低转码档 `>= 720P` 或本地文件恒为 `null`。
     */
    val storyboardSource: StoryboardSource? by derivedStateOf {
        val info = mediaInfo ?: return@derivedStateOf null
        val contentSizeBytes = info.originalSizeBytes.takeIf { it > 0L } ?: info.sizeBytes
        val unreadable = repository.knownUnreadableTranscodeIds(info.fileId, info.gcid, contentSizeBytes)
        StoryboardSourceResolver.resolve(
            isLocalPlayback = isLocalPlayback,
            mediaInfo = info,
            durationMillis = durationMillis,
            unreadableMediaIds = unreadable,
            knownStreamSizeLookup = { mediaId ->
                repository.knownTranscodeStreamSizeFor(info.fileId, info.gcid, contentSizeBytes, mediaId)
            },
        )
    }

    /** 当前视频是否启用全片雪碧图预览与「速览」时间轴条（严格要求存在 `<= 480P` MPEG-TS 转码档）。 */
    val hasStoryboard: Boolean by derivedStateOf { storyboardSource != null }

    /** 首帧是否已渲染完成，供雪碧图冷启动触发门控读取。 */
    val isFirstFrameReady: Boolean get() = startedThisAttempt

    /**
     * 当前播放点前方的可信缓冲余量（毫秒），兼顾 `bufferedPositionMillis` 与后端 `bufferSnapshot.bufferedDuration`。
     * 后端尚未上报任何缓冲读数时返回 `null`，以便雪碧图调度器区分「真实 0ms 耗尽」与「后端未上报」。
     */
    val storyboardBufferedAheadMillis: Long? by derivedStateOf {
        val hasPosReading = bufferedPositionMillis > 0L
        val snapshotMs = backend.bufferSnapshot?.bufferedDuration?.inWholeMilliseconds?.coerceAtLeast(0L)
        if (!hasPosReading && snapshotMs == null) {
            null
        } else {
            val fromBufferedPos = if (hasPosReading) (bufferedPositionMillis - positionMillis).coerceAtLeast(0L) else 0L
            maxOf(fromBufferedPos, snapshotMs ?: 0L)
        }
    }

    /** 播放器当前是否处于加载/定位/重连/卡顿忙态，供雪碧图后台抓取主动退让。 */
    val isPlaybackBusyForStoryboard: Boolean by derivedStateOf {
        isPreparing || isRecovering || backend.isBuffering || seeking
    }

    val errorMessage by derivedStateOf { if (isRecovering) null else failure }

    /** 横向画面。优先信后端解出的画面参数（已计入旋转），其次信服务端元数据。 */
    val isLandscapeVideo by derivedStateOf {
        backend.videoAspect?.let { it > 1f }
            ?: mediaInfo?.isLandscapeVideo
            ?: knownContent?.let { content ->
                val w = content.width ?: 0
                val h = content.height ?: 0
                if (w <= 0 || h <= 0) null else w > h
            }
    }

    /**
     * 同目录的视频，按自然顺序，由调用方取来填入。只有一项或为空时控件不给选集入口。
     * 取不到时也要填一次空列表：打开第一个文件前会等它，好带上外挂字幕。
     */
    var playlist: List<PlaylistEntry>
        get() = playlistState
        set(value) {
            playlistState = normalizePlaylistForCurrentFile(value, fileId)
            isPlaylistLoaded = true
        }
    private var playlistState by mutableStateOf<List<PlaylistEntry>>(emptyList())
    private var isPlaylistLoaded by mutableStateOf(false)

    // 只在第一次打开时等播放列表；等过一次仍没有，之后的换集、重试都不再等
    private var waitedForPlaylist = false

    val audioTracks: List<MediaTrack> get() = backend.audioTracks
    val subtitleTracks: List<MediaTrack> get() = backend.subtitleTracks
    val selectedAudioTrackId: String? get() = backend.selectedAudioTrackId
    val selectedSubtitleTrackId: String? get() = backend.selectedSubtitleTrackId

    // 用户选过的轨道，跨集沿用。编号在文件之间不稳定，存的是整条轨道，换集后按标题与语言找对应的
    private var preferredAudio: MediaTrack? = null
    private var preferredSubtitle: MediaTrack? = null
    private var prefersSubtitlesOff = false

    // 这个文件里用户是否亲手换过轨道。换过就不再按上一集的偏好改回去
    private var tracksChosenThisFile = false

    private var subtitleStreams: List<AutoCloseable> = emptyList()

    // 用户手动挂上的字幕，只属于当前文件。换清晰度、重连、换到本地副本都要重开文件，每次 open 时一并带上
    private var manualSubtitles: List<ManualSubtitle> = emptyList()

    // 刚手动挂上、还没出现在轨道列表里的那条，出现后按用户亲手选择记下，见 init
    private var pendingManualTitle: String? = null

    /** 能手动挂外挂字幕。 */
    val canAddSubtitle: Boolean get() = backend.canAddSubtitle

    /**
     * 用户叠加的画面旋转，顺时针度数。整个播放器会话沿用，换集不归零：要转的多是手机竖拍或录屏的片子，
     * 同一目录里的几段通常出自同一台设备，一集一集重新转反而麻烦。mpv 的 video-rotate 本就跨 loadfile 保留，
     * 这里只是记住给控件显示。
     */
    var rotationDegrees by mutableIntStateOf(0)
        private set
    val supportsRotation: Boolean get() = backend.supportsRotation

    val currentEntry by derivedStateOf { playlist.find { it.fileId == fileId } }

    // 上一集、下一集与自动连播都只在当前分区里走：正片放完不该跳进 PV 或菜单。
    // 同一内容的几个版本算一集，按组走
    private val sectionGroups by derivedStateOf {
        val key = currentEntry?.sectionKey ?: return@derivedStateOf emptyList()
        playlist.filter { it.sectionKey == key }.groupBy { it.groupKey }.values.toList()
    }
    private val groupIndex by derivedStateOf { sectionGroups.indexOfFirst { group -> group.any { it.fileId == fileId } } }
    val previousEntry by derivedStateOf { sectionGroups.getOrNull(groupIndex - 1)?.takeIf { groupIndex > 0 }?.let(::preferredIn) }
    val nextEntry by derivedStateOf { sectionGroups.getOrNull(groupIndex + 1)?.takeIf { groupIndex >= 0 }?.let(::preferredIn) }

    private fun preferredIn(group: List<PlaylistEntry>): PlaylistEntry = preferredVersion(group, currentEntry?.versionLabel)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    internal var prepared by mutableStateOf<PreparedPlayback?>(null)
        private set

    // 拖动之后、画面重新走起来之前，至多 SEEK_WATCH_MS，见 watchSeek。init 里就要读，声明在它前面
    private var seeking by mutableStateOf(false)
    private var prepareJob: Job? = null
    private var persistJob: Job? = null
    private var lateSubtitlesJob: Job? = null
    private var originalSubtitlesJob: Job? = null
    private var recoveryJob: Job? = null
    private var resumeTipJob: Job? = null

    private var requestedQuality: String? = null
    private var sessionAutoQuality: Boolean? = null
    private val failedAutoQualities = HashSet<String>()
    private var cachedCoverageByMediaId: Map<String, Float> = emptyMap()
    private var cachedWindowRenditions: Set<String> = emptySet()
    private val abrClockOrigin = TimeSource.Monotonic.markNow()
    private val abrController = AdaptiveBitrateController()
    private var lastSeekAtMillis: Long = Long.MIN_VALUE

    private fun abrNowMillis(): Long = abrClockOrigin.elapsedNow().inWholeMilliseconds

    // 这次打开播放器期间在播放器里选过的清晰度（画面高度，0 是原画）与倍速，连播换集时带着走，关掉播放器即忘。
    // 倍速不交给后端自己保留：桌面经 MediaMP 换片时会不会复位没有保证，每次打开后照这里设回去
    private var sessionMaxHeight: Int? = null
    private var sessionSpeed: Float? = null
    private var pendingStartMillis: Long? = initialStartMillis

    /** 本轮 open 用的起点，还没出第一帧就换源时从这里重来。 */
    private var attemptStartMillis = 0L

    /**
     * 续播定位是否已真正落到目标附近（或由用户主动拖动/从头播放覆盖）。
     * 容器尾部索引读取失败导致 mpv 丢失初始 seek、退回 0:00 附近输出画面时，
     * 禁止把几秒钟的错误位置覆盖进本机续播记录、云端播放历史或切档/重连起点。
     */
    private var resumePositionVerified = true
    private var usingProxy = false
    private var preferDirectLink = false
    private var directLinkTried = false
    private var stableJob: Job? = null
    private var startedThisAttempt by mutableStateOf(false)
    private var openStartedAt = TimeSource.Monotonic.markNow()
    private var retryAttempt = 0
    private var intentEpoch by mutableLongStateOf(0L)
    private var acceptedNativeGeneration = Long.MIN_VALUE

    // 出错或销毁瞬间后端的位置与时长可能已经归零，重连、持久化与云端上报都用缓存值兜底
    private var lastKnownPositionMillis = 0L
    private var lastKnownDurationMillis = 0L
    /** 当前文件是否已产生过可信的播放进度（仅在换片 reset，不因切画质/ABR/重连重置）。 */
    private var hasVerifiedProgressForFile = false
    /** 当前文件开播时绑定的账号客户端，避免关闭前切账号导致进度错报到新账号。 */
    private var playbackAccountClient: PikPakClient? = repository.currentAccountClient()
    private val playbackTransition = Mutex()
    private var released by mutableStateOf(false)
    private val workloadLease = PikoWorkloadGovernor.Shared.register(
        WorkPurpose.Playback,
        if (initialLocalPath != null) WorkPhase.Paused else WorkPhase.Urgent,
    )

    /** 续播记录的键。从下载页进来的本地文件可能没有 fileId，只能退回路径。 */
    private val positionKey: String get() = fileId.ifBlank { localPathHint.orEmpty() }

    init {
        abrController.resetSession(nowMillis = abrNowMillis(), clearBandwidth = false)
        scope.launch { backend.events.collect(::onBackendEvent) }
        scope.launch {
            snapshotFlow { Triple(backend.positionMillis, startedThisAttempt, durationMillis) }.collect { (position, started, duration) ->
                if (duration > 0L) {
                    lastKnownDurationMillis = duration
                }
                // 新文件出第一帧前，后端报的可能还是上一个文件的位置
                if (started && position > 0L) {
                    if (!resumePositionVerified && isInitialResumeLanded(position)) {
                        resumePositionVerified = true
                    }
                    if (resumePositionVerified) {
                        lastKnownPositionMillis = position
                        hasVerifiedProgressForFile = true
                    }
                }
            }
        }
        persistJob = scope.launch { persistLoop() }
        scope.launch { autoQualityLoop() }
        scope.launch {
            snapshotFlow { backend.playbackGeneration to backend.isBuffering }.collect { (gen, waiting) ->
                if (acceptedNativeGeneration != Long.MIN_VALUE && gen > acceptedNativeGeneration) {
                    acceptedNativeGeneration = gen
                    intentEpoch = maxOf(intentEpoch + 1L, gen)
                }
                if (!waiting) seeking = false
            }
        }
        scope.launch {
            snapshotFlow {
                val phase = currentWorkPhase()
                Triple(prepared, phase, intentEpoch)
            }.collect { (active, phase, epoch) ->
                workloadLease.updatePhase(phase)
                if (active != null) {
                    submitPlaybackIntent(active, phase, epoch)
                }
            }
        }
        scope.launch {
            // 加载与定位缓冲期间采样当前流底层收到的网络字节增量，向加载指示器提供实时网速读数。
            snapshotFlow { isLoading && !isLocalPlayback && !isImage }.collectLatest { active ->
                if (!active) {
                    loadingSpeedText = null
                    return@collectLatest
                }
                var notifiedReactivationFor: Any? = null
                observeLoadingSpeed(
                    token = { prepared },
                    sampleBytes = { prepared?.receivedBytes },
                    isReactivating = { prepared?.isReactivating?.value == true },
                    onReactivating = { curPrepared ->
                        if (curPrepared != null && notifiedReactivationFor !== curPrepared) {
                            notifiedReactivationFor = curPrepared
                            PikoLog.i(TAG, "Cloud cold-storage reactivation detected: ${logFile(fileId, title)}")
                            _messages.tryEmit(
                                tr(
                                    "文件长期未访问，云端正在唤醒恢复，请稍候…",
                                    "File is being reactivated in the cloud. Please wait…",
                                    "長期間未アクセスのファイルをクラウドで復元しています。しばらくお待ちください…",
                                    "오랫동안 접근하지 않은 파일을 클라우드에서 복구 중입니다. 잠시만 기다려 주세요…",
                                    "檔案長期未存取，雲端正在喚醒還原，請稍候…",
                                ),
                            )
                        }
                    },
                    onUpdate = { loadingSpeedText = it },
                )
            }
        }
        scope.launch {
            snapshotFlow { Triple(prepared, isPreparing, backend.bufferSnapshot) }.collect { (active, preparingNow, snapshot) ->
                if (active != null && !preparingNow && snapshot != null && usingProxy && !isImage) {
                    if (acceptedNativeGeneration != Long.MIN_VALUE && snapshot.generation < acceptedNativeGeneration) {
                        return@collect
                    }
                    val wasUnset = acceptedNativeGeneration == Long.MIN_VALUE
                    if (snapshot.generation > acceptedNativeGeneration) {
                        acceptedNativeGeneration = snapshot.generation
                        if (!wasUnset) {
                            intentEpoch = maxOf(intentEpoch + 1L, snapshot.generation)
                        }
                    }
                    val remapped = snapshot.copy(
                        generation = intentEpoch,
                        mediaBytesPerSecond = snapshot.mediaBytesPerSecond ?: active.estimatedBytesPerSecond,
                    )
                    if (!wasUnset && active.demand.intent.epoch != intentEpoch) {
                        submitPlaybackIntent(active, currentWorkPhase(), intentEpoch, remapped)
                    } else {
                        active.reportPlaybackBuffer(remapped)
                    }
                }
            }
        }
        scope.launch {
            // 外挂字幕在文件加载之后才挂上，列表会分几次变长，每次都重新套用
            snapshotFlow { backend.audioTracks to backend.subtitleTracks }.collect {
                selectPendingManualSubtitle()
                applyTrackPreferences()
            }
        }
        reload()
    }

    fun selectAudioTrack(track: MediaTrack) {
        preferredAudio = track
        tracksChosenThisFile = true
        backend.selectAudioTrack(track.id)
    }

    /** [track] 为 null 时关闭字幕。 */
    fun selectSubtitleTrack(track: MediaTrack?) {
        preferredSubtitle = track
        prefersSubtitlesOff = track == null
        tracksChosenThisFile = true
        backend.selectSubtitleTrack(track?.id)
    }

    /**
     * 本机的字幕文件。[path] 要是后端能直接读的路径，Android 的 content: URI 由平台先复制出来。
     * [forFileId] 是弹出选择框时正在放的视频：选文件与复制期间可能已自动连播到下一集，
     * 字幕只属于选它时的那一集，换过就不挂。
     */
    fun addLocalSubtitle(forFileId: String, path: String, name: String) {
        if (fileId != forFileId || released) {
            _messages.tryEmit(tr("视频已切换，未加载字幕", "Video changed; subtitle not loaded", "動画が切り替わったため、字幕を読み込めませんでした", "동영상이 전환되어 자막을 불러오지 않았습니다", "影片已切換，未載入字幕"))
            return
        }
        val inferred = inferSubtitleLanguageCode(null, name)
        if (inferred != null || !isTextSubtitleName(name)) {
            val subtitle = ManualSubtitle(title = name, localPath = path, fileId = null, detectedLanguage = inferred)
            manualSubtitles = manualSubtitles + subtitle
            attachManualSubtitle(ExternalSubtitle(url = path, title = name, language = inferred))
            return
        }
        scope.launch {
            val sniffed = withContext(Dispatchers.IO) { sniffLocalSubtitleLanguage(path, name) }
            if (fileId != forFileId || released) return@launch
            val subtitle = ManualSubtitle(title = name, localPath = path, fileId = null, detectedLanguage = sniffed)
            manualSubtitles = manualSubtitles + subtitle
            attachManualSubtitle(ExternalSubtitle(url = path, title = name, language = sniffed))
        }
    }

    /** 网盘上的字幕文件，与自动挂的外挂字幕一样经本机代理读。 */
    fun addDriveSubtitle(subtitleFileId: String, name: String) {
        val videoFileId = fileId
        val videoTitle = title
        scope.launch {
            val localSubtitle = try {
                repository.localPath(subtitleFileId, subtitle = true)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                PikoLog.w(TAG, "Failed to resolve local path for drive subtitle ${logFile(subtitleFileId, name)} on ${logFile(videoFileId, videoTitle)}", e)
                _messages.tryEmit(tr("无法打开字幕文件", "Unable to open subtitle file", "字幕ファイルを開けません", "자막 파일을 열 수 없습니다", "無法開啟字幕檔案"))
                return@launch
            }
            localSubtitle?.let { path ->
                addLocalSubtitle(videoFileId, path, name)
                return@launch
            }
            val inferred = inferSubtitleLanguageCode(null, name)
            val prepared = repository.prepareExternalSubtitle(subtitleFileId, knownLanguage = inferred)
            if (prepared == null) {
                _messages.tryEmit(tr("无法打开字幕文件", "Unable to open subtitle file", "字幕ファイルを開けません", "자막 파일을 열 수 없습니다", "無法開啟字幕檔案"))
                return@launch
            }
            // 取流期间换了集，这条字幕不属于新文件
            if (fileId != videoFileId || released) {
                prepared.stream.close()
                return@launch
            }
            val detectedLang = inferred ?: prepared.detectedLanguage
            subtitleStreams = subtitleStreams + prepared.stream
            manualSubtitles = manualSubtitles + ManualSubtitle(
                title = name,
                localPath = null,
                fileId = subtitleFileId,
                detectedLanguage = detectedLang,
            )
            attachManualSubtitle(
                ExternalSubtitle(url = prepared.stream.url, title = name, language = detectedLang),
                subtitleFileId = subtitleFileId,
            )
        }
    }

    private fun attachManualSubtitle(subtitle: ExternalSubtitle, subtitleFileId: String? = null) {
        // 日志只留扩展名或脱敏后的字幕文件标识，原始明文文件名不直接裸写进导出日志
        val ext = subtitle.title.substringAfterLast('.', "").lowercase().ifBlank { "unknown" }
        val subDesc = if (subtitleFileId != null) {
            "drive=${logFile(subtitleFileId, subtitle.title)}, ext=$ext"
        } else {
            "source=local, ext=$ext"
        }
        PikoLog.i(TAG, "Attached manual subtitle ($subDesc) for ${logFile(fileId, title)}")
        pendingManualTitle = subtitle.title
        backend.addSubtitle(subtitle)
    }

    /**
     * 后端挂上时已经选中，这里只把它记成用户的选择：之后重开文件（换清晰度、重连）时按标题找回它，
     * 而不是被上一集的偏好或自动选择换掉。
     */
    private fun selectPendingManualSubtitle() {
        val title = pendingManualTitle ?: return
        val track = backend.subtitleTracks.lastOrNull { it.isExternal && it.title == title } ?: return
        pendingManualTitle = null
        selectSubtitleTrack(track)
    }

    fun setRotation(degrees: Int) {
        val normalized = degrees.mod(360) / 90 * 90
        if (normalized != rotationDegrees && cropRegion != null) setCropRegion(null)
        rotationDegrees = normalized
        backend.setRotation(normalized)
    }

    private fun applyTrackPreferences() {
        if (tracksChosenThisFile) return
        preferredAudio?.let { preferred ->
            matchTrack(backend.audioTracks, preferred)?.takeIf { it.id != backend.selectedAudioTrackId }
                ?.let { backend.selectAudioTrack(it.id) }
        }
        when {
            prefersSubtitlesOff -> if (backend.selectedSubtitleTrackId != null && backend.subtitleTracks.isNotEmpty()) {
                backend.selectSubtitleTrack(null)
            }
            preferredSubtitle != null -> {
                val preferred = preferredSubtitle!!
                matchTrack(backend.subtitleTracks, preferred)?.takeIf { it.id != backend.selectedSubtitleTrackId }
                    ?.let { backend.selectSubtitleTrack(it.id) }
            }
            backend.subtitleTracks.isNotEmpty() -> {
                val preferredVariant = preferredChineseSubtitleVariant()
                val current = backend.subtitleTracks.firstOrNull { it.id == backend.selectedSubtitleTrackId }
                if (current == null || current.chineseVariant != preferredVariant) {
                    val best = selectPreferredSubtitleTrack(backend.subtitleTracks, preferredVariant)
                    if (best != null && best.chineseVariant == preferredVariant && best.id != backend.selectedSubtitleTrackId) {
                        backend.selectSubtitleTrack(best.id)
                    }
                }
            }
        }
    }

    fun togglePlayPause() {
        if (backend.isPlaying) pause() else play()
    }

    fun play() = backend.play()

    fun pause() {
        val wasPlaying = backend.isPlaying
        backend.pause()
        if (wasPlaying) {
            flushProgress(urgent = true)
        }
    }

    private fun currentWorkPhase(): WorkPhase = when {
        released || isLocalPlayback || isImage || failure != null -> WorkPhase.Paused
        isPreparing || isRecovering || backend.isBuffering || seeking -> WorkPhase.Urgent
        backend.isPlaying && !startedThisAttempt -> WorkPhase.Urgent
        backend.isPlaying -> WorkPhase.Steady
        else -> WorkPhase.Paused
    }

    private fun submitPlaybackIntent(
        active: PreparedPlayback,
        phase: WorkPhase,
        epoch: Long,
        explicitSample: PlaybackBufferSnapshot? = null,
    ) {
        val service = when (phase) {
            WorkPhase.Urgent -> ServiceIntent.WAITING
            WorkPhase.Steady -> ServiceIntent.PLAYING
            WorkPhase.Paused, WorkPhase.Active -> ServiceIntent.PAUSED
        }
        val budget = if (service == ServiceIntent.PAUSED) BufferBudget.NONE else active.defaultBufferBudget
        val sample = explicitSample?.copy(
            generation = epoch,
            mediaBytesPerSecond = explicitSample.mediaBytesPerSecond ?: active.estimatedBytesPerSecond,
        ) ?: active.demand.intent.playbackSample?.takeIf { it.generation == epoch }
        val prevService = active.demand.intent.service
        active.submitIntent(
            ConsumerIntent(
                epoch = epoch,
                service = service,
                bufferBudget = budget,
                playbackSample = sample,
            ),
        )
        if (service == ServiceIntent.PAUSED && prevService != ServiceIntent.PAUSED) {
            active.withdrawSpeculation()
        }
    }

    fun seekTo(positionMillis: Long) {
        val upper = currentDuration().takeIf { it > 0L } ?: Long.MAX_VALUE
        val clamped = positionMillis.coerceIn(0L, upper)
        val beforeSeek = backend.positionMillis
        resumePositionVerified = true
        if (clamped > 0L) {
            lastKnownPositionMillis = clamped
            hasVerifiedProgressForFile = true
        }
        val expectedNativeGen = backend.playbackGeneration + 1L
        acceptedNativeGeneration = expectedNativeGen
        val nextEpoch = maxOf(intentEpoch + 1L, expectedNativeGen)
        intentEpoch = nextEpoch
        val seekAt = abrNowMillis()
        lastSeekAtMillis = seekAt
        abrController.notePlaybackReady(seekAt)
        seeking = true
        prepared?.let { active ->
            active.cancelPrewarm()
            val freshSample = PlaybackBufferSnapshot(
                generation = nextEpoch,
                bufferedDuration = null,
                sampledAt = TimeSource.Monotonic.markNow(),
                mediaBytesPerSecond = active.estimatedBytesPerSecond,
            )
            submitPlaybackIntent(active, WorkPhase.Urgent, nextEpoch, freshSample)
        }
        backend.seekTo(clamped)
        watchSeek(beforeSeek = beforeSeek, targetMillis = clamped)
    }

    private var seekWatch: Job? = null

    /**
     * 记下拖动发出到播放位置重新向前推进的真实挂钟耗时（毫秒），与信息流的定位恢复耗时对照。
     * 连着拖（按住方向键）只记最后一次；暂停状态下拖动等不到播放推进，不记。
     */
    private fun watchSeek(beforeSeek: Long, targetMillis: Long) {
        seekWatch?.cancel()
        val sought = TimeSource.Monotonic.markNow()
        seeking = true
        seekWatch = scope.launch {
            // 记录后端是否报告等待；该状态不能区分网络与解码耗时。
            var sawBuffering = backend.isBuffering
            val bufferingWatch = launch { snapshotFlow { backend.isBuffering }.first { it }; sawBuffering = true }
            try {
                // 刚发出异步定位时后端报出的位置可能仍是定位前的旧值：大幅跳转可一旦观察到位置跳离旧值即提前结束防抖，小步微调则等满防抖窗口
                if (kotlin.math.abs(targetMillis - beforeSeek) > SEEK_JUMP_DETECT_THRESHOLD_MS) {
                    withTimeoutOrNull(SEEK_SETTLE_MS) {
                        snapshotFlow {
                            backend.isBuffering || kotlin.math.abs(backend.positionMillis - beforeSeek) > SEEK_JUMP_MOVED_MS
                        }.first { it }
                    }
                } else {
                    delay(SEEK_SETTLE_MS)
                }
                val settled = withTimeoutOrNull(SEEK_WATCH_MS) {
                    snapshotFlow { !backend.isBuffering }.first { it }
                } != null
                if (!settled || !backend.isPlaying) return@launch
                val anchor = backend.positionMillis
                val remainingMs = (SEEK_WATCH_MS - sought.elapsedNow().inWholeMilliseconds).coerceAtLeast(1L)
                val resumed = withTimeoutOrNull(remainingMs) {
                    snapshotFlow { backend.isPlaying && !backend.isBuffering && backend.positionMillis > anchor }.first { it }
                } != null
                if (resumed) {
                    val elapsedMs = sought.elapsedNow().inWholeMilliseconds
                    PikoLog.d(
                        TAG,
                        "Seek playback resumed: ${logFile(fileId, title)}, targetMs=$targetMillis, posMs=${backend.positionMillis}, elapsedMs=$elapsedMs, buffered=$sawBuffering",
                    )
                }
            } finally {
                bufferingWatch.cancel()
            }
        }
    }

    fun seekBy(deltaMillis: Long) = seekTo(backend.positionMillis + deltaMillis)

    fun setSpeed(speed: Float) {
        sessionSpeed = speed
        backend.setSpeed(speed)
    }

    fun setAspectRatio(mode: PlayerAspectRatio) = backend.setAspectRatio(mode)

    fun setCropRegion(region: VideoCropRegion?) = backend.setCropRegion(region)

    /** 播放详细信息面板的内容：从哪里读、哪一档，再加 mpv 读得到的编码与缓冲。面板开着时每秒取一次。 */
    fun stats(): List<PlaybackStatsSection> {
        // 走不走本机代理是实现细节，看的人只关心是不是在用流量
        val source = if (isLocalPlayback) tr("本机", "This device", "この端末", "이 기기", "本機") else tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟")
        val resolvedLabel = activeQuality ?: tr("原画", "Original", "オリジナル", "원본", "原畫")
        val quality = when {
            isLocalPlayback -> null
            isAutoQuality -> "${tr("自动", "Auto", "自動", "자동", "自動")} ($resolvedLabel)"
            else -> resolvedLabel
        }
        val playback = PlaybackStatsSection(tr("播放", "Play", "再生", "재생", "播放"), listOfNotNull(tr("来源", "Source", "ソース", "출처", "來源") to source, quality?.let { tr("画质", "Quality", "画質", "화질", "畫質") to it }))
        return listOf(playback) + readPlaybackStats(backend::mpvProperty)
    }

    fun selectQuality(quality: String) {
        if (quality == AUTO_QUALITY) {
            isAutoQuality = true
            sessionAutoQuality = true
            sessionMaxHeight = null
            abrController.noteManualSwitch(abrNowMillis())
            val target = pickInitialAutoQuality()
            val activeName = activeQuality ?: ORIGINAL_QUALITY
            if (target == activeName && !isPreparing && failure == null) {
                return
            }
            pendingStartMillis = currentPosition()
            requestedQuality = target
            resetRecovery()
            reload()
            return
        }
        isAutoQuality = false
        sessionAutoQuality = false
        abrController.noteManualSwitch(abrNowMillis())
        pendingStartMillis = currentPosition()
        requestedQuality = quality
        // 之后几集按这一档的画面高度挑，不照名字找：别的集未必有同名的一档，照名字找不到会退回原画
        sessionMaxHeight = if (quality == ORIGINAL_QUALITY) {
            0
        } else {
            mediaInfo?.availableVariants?.firstOrNull { it.mediaName == quality || it.resolutionName == quality }?.video?.height
        }
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
        val key = positionKey
        resumePositionVerified = true
        lastKnownPositionMillis = 0L
        hasVerifiedProgressForFile = false
        val expectedNativeGen = backend.playbackGeneration + 1L
        acceptedNativeGeneration = expectedNativeGen
        val nextEpoch = maxOf(intentEpoch + 1L, expectedNativeGen)
        intentEpoch = nextEpoch
        val seekAt = abrNowMillis()
        lastSeekAtMillis = seekAt
        abrController.notePlaybackReady(seekAt)
        seeking = true
        prepared?.let { active ->
            active.cancelPrewarm()
            submitPlaybackIntent(
                active,
                WorkPhase.Urgent,
                nextEpoch,
                PlaybackBufferSnapshot(nextEpoch, null, TimeSource.Monotonic.markNow(), active.estimatedBytesPerSecond),
            )
        }
        backend.seekTo(0L)
        resumedFromMillis = null
        if (key.isNotBlank() && !isImage) {
            repository.savePlaybackPositionAsync(key, 0L)
        }
    }

    fun dismissResumeTip() {
        resumedFromMillis = null
    }

    /** 同目录换片。清晰度、续播与重试状态都跟着新文件重来。 */
    fun switchTo(fileId: String, fileName: String, localPath: String? = null) {
        if (fileId == this.fileId && localPath == localPathHint) return
        // 换片前同步捕获上一集的最终进度，交给仓库级队列落盘与上报（不随播放器作用域取消）
        flushProgress(urgent = true)

        this.fileId = fileId
        title = fileName
        localPathHint = localPath
        playbackAccountClient = repository.currentAccountClient()
        mediaInfo = null
        activeQuality = null
        requestedQuality = null
        originalSubtitleHints = emptyList()
        failedAutoQualities.clear()
        cachedCoverageByMediaId = emptyMap()
        cachedWindowRenditions = emptySet()
        lastSeekAtMillis = Long.MIN_VALUE
        abrController.resetSession(nowMillis = abrNowMillis(), clearBandwidth = false)
        pendingStartMillis = null
        lastKnownPositionMillis = 0L
        lastKnownDurationMillis = 0L
        resumePositionVerified = true
        hasVerifiedProgressForFile = false
        lastPeriodicCloudFileId = ""
        lastPeriodicCloudPosSec = -1L
        manualSubtitles = emptyList()
        pendingManualTitle = null
        resetRecovery()
        reload()
    }

    /** 点到正在放的那集只收起面板：它若是本地副本，按 fileId 重开会丢掉本地路径的提示并从头取流。 */
    fun playEntry(entry: PlaylistEntry) {
        if (entry.fileId != fileId) switchTo(entry.fileId, entry.name)
    }

    fun playPrevious() {
        previousEntry?.let(::playEntry)
    }

    fun playNext() {
        nextEntry?.let(::playEntry)
    }

    /** 播放途中才发现本机有完整副本（如下载刚完成），从当前位置换到本地文件。 */
    fun useLocalCopy(path: String) {
        if (isLocalPlayback) return
        localPathHint = path
        pendingStartMillis = currentPosition()
        resetRecovery()
        reload()
    }

    /**
     * 立即捕获当前已验证的播放进度并提交本机续播记录与云端播放历史。
     * 由 [PikoMediaRepository] 后台队列异步完成（遵守 PikPak 同文件冷却并支持重试），免疫播放器窗口关闭与作用域取消；
     * 可在退出、换集、暂停或 Android `ON_STOP` 退后台时调用。
     */
    fun flushProgress(urgent: Boolean = true) {
        if (isImage) return
        val pos = currentPosition()
        val dur = currentDuration()
        val verified = hasVerifiedProgressForFile || (startedThisAttempt && resumePositionVerified)
        val key = positionKey
        val id = fileId
        val currentTitle = title
        if (!verified) {
            if (urgent) {
                PikoLog.d(
                    TAG,
                    "Skipped flushProgress (unverified progress, posMs=$pos, durMs=$dur): ${logFile(id, currentTitle)}",
                )
            }
            return
        }
        persistAsync(key, pos, dur)
        val sessionClient = playbackAccountClient
        if (id.isNotBlank() && sessionClient != null) {
            repository.enqueuePlayHistory(
                fileId = id,
                positionMillis = pos,
                durationMillis = dur,
                urgent = urgent,
                title = currentTitle,
                client = sessionClient,
            )
        }
    }

    /**
     * 释放代理会话。释放前先捕获并刷出最终进度（赶在 `backend.stop()` 清零时长之前），
     * 再取消周期持久化循环并停止后端。
     * [afterClosed] 在全部代理流关闭后由 IO 作用域调用，包括没有代理流的本地播放。
     */
    fun release(afterClosed: (() -> Unit)? = null) {
        if (released) return
        flushProgress(urgent = true)
        released = true
        persistJob?.cancel()
        workloadLease.close()
        prepareJob?.cancel()
        lateSubtitlesJob?.cancel()
        originalSubtitlesJob?.cancel()
        recoveryJob?.cancel()
        val oldStreams = detachPrepared()
        backend.stop()
        withdrawPlaybackTransport(oldStreams)
        retireScope.launch {
            try { playbackTransition.withLock { stopPlayback(backend, oldStreams) } }
            finally { afterClosed?.invoke() }
        }
    }

    private fun currentPosition(): Long =
        backend.positionMillis.takeIf { it > 0L && startedThisAttempt && resumePositionVerified } ?: lastKnownPositionMillis

    private fun currentDuration(): Long {
        val live = durationMillis
        if (live > 0L) lastKnownDurationMillis = live
        return live.takeIf { it > 0L } ?: lastKnownDurationMillis
    }

    private fun isInitialResumeLanded(position: Long): Boolean {
        if (attemptStartMillis <= RESUME_THRESHOLD_MILLIS) return true
        if (attemptStartMillis > RESUME_VERIFY_TOLERANCE_MILLIS) {
            return position >= attemptStartMillis - RESUME_VERIFY_TOLERANCE_MILLIS
        }
        return currentDuration() > 0L && position > 0L
    }

    private fun applyAttemptStart(positionMillis: Long): Long {
        attemptStartMillis = positionMillis
        if (positionMillis > RESUME_THRESHOLD_MILLIS) {
            lastKnownPositionMillis = positionMillis
            resumePositionVerified = false
        } else {
            resumePositionVerified = true
        }
        return positionMillis
    }

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
        lateSubtitlesJob?.cancel()
        originalSubtitlesJob?.cancel()
        prepareJob = scope.launch { prepare() }
    }

    private suspend fun prepare() = playbackTransition.withLock {
        isPreparing = true
        failure = null
        startedThisAttempt = false
        resumeTipJob?.cancel()
        resumedFromMillis = null
        val oldStreams = detachPrepared()
        if (oldStreams.isNotEmpty()) {
            withContext(NonCancellable) { stopPlayback(backend, oldStreams) }
        } else {
            backend.stop()
        }
        val pending = pendingStartMillis
        pendingStartMillis = null
        // 同步开着时优先用 PikPak 播放历史里的位置：它含其他客户端看到的进度。查它要一次请求，
        // 与取流并行，到开播前才取结果，等不到就退回本机记录
        val prepareStarted = TimeSource.Monotonic.markNow()
        val cloudPosition = if (pending == null) scope.async { repository.cloudPlaybackPosition(fileId) } else null
        try {
            val localSaved = if (pending == null) {
                repository.getPlaybackPosition(positionKey).takeIf { it > RESUME_THRESHOLD_MILLIS }
            } else null
            var saved: Long? = null
            suspend fun startPosition(): Long {
                if (pending != null) return applyAttemptStart(pending)
                val waitBudget = if (localSaved != null) LOCAL_RESUME_WAIT_MILLIS else CLOUD_RESUME_WAIT_MILLIS
                val remaining = (waitBudget - prepareStarted.elapsedNow().inWholeMilliseconds).coerceAtLeast(0L)
                // 预算耗尽也要使用已经返回的云端结果；withTimeoutOrNull(0) 不会执行 await。
                val cloud = if (cloudPosition?.isCompleted == true) {
                    cloudPosition.await()
                } else {
                    withTimeoutOrNull(remaining) { cloudPosition?.await() }
                }
                saved = (cloud ?: localSaved)?.takeIf { it > RESUME_THRESHOLD_MILLIS }
                saved?.let { PikoLog.d(TAG, "Resuming playback at ${it}ms (source=${if (cloud != null) "cloud" else "local"}): ${logFile(fileId, title)}") }
                return applyAttemptStart(saved ?: 0L)
            }

            tracksChosenThisFile = false
            if (playbackAccountClient == null) {
                playbackAccountClient = repository.currentAccountClient()
            }
            val localPath = resolveLocalPath(fileId, repository.localPath(fileId) ?: localPathHint)
            if (localPath != null) {
                isLocalPlayback = true
                resolvedLocalPath = localPath
                usingProxy = false
                activeQuality = null
                isAutoQuality = false
                originalSubtitleHints = emptyList()
                PikoLog.i(TAG, "Opening local file for playback: ${logFile(fileId, title)}")
                val startAt = startPosition()
                val subs = openSubtitles()
                openStartedAt = TimeSource.Monotonic.markNow()
                backend.open(PlaybackTarget.LocalFile(localPath), startAt, subtitles = subs)
                sessionSpeed?.let(backend::setSpeed)
            } else {
                isLocalPlayback = false
                resolvedLocalPath = null
                val configuredMaxHeight = sessionMaxHeight ?: defaultMaxHeight()
                val prewarmStartHint = pending
                    ?: (if (cloudPosition?.isCompleted == true) cloudPosition.await() else null)
                    ?: localSaved
                    ?: 0L
                val playbackStarted = TimeSource.Monotonic.markNow()
                var unassignedPlayback: PreparedPlayback? = null
                val autoMode: Boolean
                val playback: PreparedPlayback = try {
                    var candidate = repository.preparePlayback(
                        fileId = fileId,
                        preferredResolution = requestedQuality,
                        defaultMaxHeight = configuredMaxHeight,
                        startPositionMillis = prewarmStartHint,
                    ).getOrThrow()
                    unassignedPlayback = candidate
                    candidate.client?.let { playbackAccountClient = it }
                    val hasTranscodes = candidate.info.availableVariants.any { !it.isOrigin && it.video != null && it.link.url.isNotBlank() }
                    autoMode = hasTranscodes && when {
                        sessionAutoQuality != null -> sessionAutoQuality == true
                        requestedQuality != null -> false
                        else -> configuredMaxHeight == PlayerGestureDefaults.PLAYBACK_AUTO_HEIGHT
                    }
                    // 自动画质冷开播/重播时先查本地缓存覆盖：若某档位已有可观本地缓存（>= 25% 或起点窗口命中），
                    // 直接切换到已缓存档位开播，实现零额外流量秒开。
                    if (autoMode && requestedQuality == null && candidate.info.kind == PlayableMediaKind.Video) {
                        val infoDurMs = candidate.info.durationSeconds * 1_000L
                        val (cov, win) = try {
                            repository.cachedRenditionCoverage(candidate.info, prewarmStartHint, infoDurMs)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            emptyMap<String, Float>() to emptySet()
                        }
                        cachedCoverageByMediaId = cov
                        cachedWindowRenditions = win
                        if (cov.isNotEmpty() || win.isNotEmpty()) {
                            val cands = autoQualityCandidatesFor(candidate.info, infoDurMs)
                            val speed = (sessionSpeed ?: backend.speed).coerceAtLeast(0.5f)
                            val preferredCached = abrController.pickInitial(cands, speed, cov, win)
                            val openedName = candidate.info.transcodeName ?: ORIGINAL_QUALITY
                            if (preferredCached.isNotBlank() && preferredCached != openedName) {
                                val reopened = repository.preparePlayback(
                                    fileId = fileId,
                                    preferredResolution = preferredCached,
                                    defaultMaxHeight = configuredMaxHeight,
                                    startPositionMillis = prewarmStartHint,
                                ).getOrNull()
                                if (reopened != null) {
                                    candidate.close()
                                    candidate = reopened
                                    unassignedPlayback = candidate
                                    candidate.client?.let { playbackAccountClient = it }
                                }
                            }
                        }
                    }
                    if (released) throw CancellationException("Player released during prepare")
                    // 出第一帧前直接提交 WAITING 意图（未知缓冲保持 bufferedDuration = null，绝不伪装成 0s），
                    // 并预先对齐 backend.open 内部的 generation 递增，防止开播探测中途二次递增 epoch。
                    acceptedNativeGeneration = Long.MIN_VALUE
                    val initialEpoch = maxOf(intentEpoch + 1L, backend.playbackGeneration + 1L)
                    intentEpoch = initialEpoch
                    submitPlaybackIntent(
                        candidate,
                        WorkPhase.Urgent,
                        initialEpoch,
                        PlaybackBufferSnapshot(initialEpoch, null, TimeSource.Monotonic.markNow(), candidate.estimatedBytesPerSecond),
                    )
                    prepared = candidate
                    unassignedPlayback = null
                    candidate
                } catch (t: Throwable) {
                    unassignedPlayback?.close()
                    throw t
                }
                val prepareMillis = playbackStarted.elapsedNow().inWholeMilliseconds
                mediaInfo = playback.info
                val knownDur = durationMillis
                if (knownDur > 0L) lastKnownDurationMillis = knownDur
                // 按实际打开的那一档标：没点选过时由设置的上限挑，点选的档位没有时 SDK 退回原画
                activeQuality = playback.info.transcodeName
                isAutoQuality = autoMode
                if (playback.info.isOrigin) {
                    originalSubtitleHints = emptyList()
                }
                when (playback.info.kind) {
                    PlayableMediaKind.Image -> Unit
                    PlayableMediaKind.UnsupportedImage -> failure = tr("GIF 暂不支持预览", "GIF preview is not supported yet", "GIFのプレビューにはまだ対応していません", "GIF 미리 보기는 아직 지원하지 않습니다", "GIF 暫不支援預覽")
                    PlayableMediaKind.Video, PlayableMediaKind.Audio -> {
                        val proxyUrl = playback.proxyUrl.takeUnless { preferDirectLink }
                        usingProxy = proxyUrl != null
                        val info = playback.info
                        val variantDesc = if (info.isOrigin) {
                            ORIGINAL_QUALITY
                        } else {
                            val label = info.transcodeName
                                ?: info.height.takeIf { it > 0 }?.let { "${it}p" }
                                ?: info.currentResolution.takeUnless { it == ORIGINAL_QUALITY }?.let { "${it}p" }
                                ?: "unknown"
                            "transcode($label)"
                        }
                        PikoLog.i(
                            TAG,
                            "Opening stream: ${logFile(fileId, title)}, transport=${if (usingProxy) "proxy" else "direct"}, variant=$variantDesc, " +
                                "video=${info.width}x${info.height}, size=${info.sizeBytes} B, prepareMs=$prepareMillis" +
                                (requestedQuality?.let { ", requested=$it" } ?: "") +
                                (if (isAutoQuality) ", auto=true" else "") +
                                (if (retryAttempt > 0) ", reconnect=$retryAttempt/${RECOVERY_DELAYS_MILLIS.size}" else ""),
                        )
                        val startAt = startPosition()
                        val subs = openSubtitles()
                        openStartedAt = TimeSource.Monotonic.markNow()
                        backend.open(
                            PlaybackTarget.Url(
                                url = proxyUrl ?: playback.info.currentUrl,
                                isMpegTsTranscode = !info.isOrigin,
                            ),
                            startAt,
                            subtitles = subs,
                        )
                        sessionSpeed?.let(backend::setSpeed)
                        if (!info.isOrigin && backend.canAddSubtitle) {
                            attachOriginalEmbeddedSubtitles(fileId)
                        }
                    }
                }
            }
            cloudPosition?.cancel()
            saved?.let { if (!isImage) showResumeTip(it) }
            isPreparing = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PikoLog.w(TAG, "Failed to prepare playback: ${logFile(fileId, title)}", e)
            failure = dev.piko.shared.notice.PikoErrorMapper.failure(tr("播放", "Playback", "再生", "재생", "播放"), e)
            isPreparing = false
            // 这一轮连流都没拿到，退避到此为止，否则失败卡片会被加载态一直挡着
            isRecovering = false
        } finally {
            cloudPosition?.cancel()
        }
    }

    private fun onBackendEvent(event: PlaybackBackendEvent) {
        if (released) return
        when (event) {
            PlaybackBackendEvent.Ready -> {
                if (!startedThisAttempt) {
                    val transport = when {
                        isLocalPlayback -> "local"
                        usingProxy -> "proxy"
                        else -> "direct"
                    }
                    PikoLog.d(
                        TAG,
                        "Playback backend first frame ready: ${logFile(fileId, title)}, transport=$transport, firstFrameMs=${openStartedAt.elapsedNow().inWholeMilliseconds}",
                    )
                    abrController.notePlaybackReady(abrNowMillis())
                }
                startedThisAttempt = true
                val liveDur = durationMillis
                if (liveDur > 0L) lastKnownDurationMillis = liveDur
                val livePos = backend.positionMillis
                if (livePos > 0L) {
                    if (!resumePositionVerified && isInitialResumeLanded(livePos)) {
                        resumePositionVerified = true
                    }
                    if (resumePositionVerified) {
                        lastKnownPositionMillis = livePos
                        hasVerifiedProgressForFile = true
                    }
                }
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
                val id = fileId
                val currentTitle = title
                val sessionClient = playbackAccountClient
                val dur = currentDuration()
                val pos = currentPosition()
                val verified = hasVerifiedProgressForFile || (startedThisAttempt && resumePositionVerified)
                // 在切下一集或关闭前先清零本集活跃标记与已知进度，防止后端滞后的旧位置覆盖 0ms 重置与完播上报
                startedThisAttempt = false
                lastKnownPositionMillis = 0L
                hasVerifiedProgressForFile = false
                if (key.isNotBlank() && !isImage) {
                    repository.savePlaybackPositionAsync(key, 0L)
                }
                if (verified && !isImage && id.isNotBlank() && dur > 0L && sessionClient != null) {
                    repository.enqueuePlayHistory(
                        fileId = id,
                        positionMillis = maxOf(pos, dur),
                        durationMillis = dur,
                        urgent = true,
                        title = currentTitle,
                        client = sessionClient,
                    )
                }
                nextEntry?.let(::playEntry)
            }

            is PlaybackBackendEvent.Error -> onPlaybackError(event.detail)
        }
    }

    private fun onPlaybackError(detail: String?) {
        stableJob?.cancel()
        val safeDetail = detail?.takeUnless { dev.piko.shared.data.LocalLocation.isLocal(fileId) }
        val message = safeDetail?.takeIf { it.isNotBlank() }?.let { tr("播放失败：$it", "Playback failed: $it", "再生に失敗しました：$it", "재생 실패: $it", "播放失敗：$it") } ?: tr("播放中断", "Playback interrupted", "再生が中断されました", "재생이 중단되었습니다", "播放中斷")
        val transport = when {
            isLocalPlayback -> "local"
            usingProxy -> "proxy"
            else -> "direct"
        }
        val stage = if (startedThisAttempt) "mid-stream" else "pre-first-frame"
        val nextRetry = if (!isLocalPlayback && startedThisAttempt && retryAttempt < RECOVERY_DELAYS_MILLIS.size) {
            "${retryAttempt + 1}/${RECOVERY_DELAYS_MILLIS.size}"
        } else {
            "none"
        }
        PikoLog.w(
            TAG,
            "Playback backend error: ${logFile(fileId, title)}, transport=$transport, stage=$stage, posMs=${currentPosition()}, reconnect=$nextRetry, detail=${safeDetail?.ifBlank { null } ?: "none"}",
        )
        // 本地文件重来一遍还是同一个错误，直接交给用户
        if (isLocalPlayback) {
            failure = message
            return
        }

        if (!startedThisAttempt) {
            val activePrepared = prepared
            val proxyUrl = activePrepared?.proxyUrl
            if (usingProxy && activePrepared != null && proxyUrl != null && activePrepared.isReactivating.value) {
                // 云端冷存正在唤醒：播放器自身 HTTP 读超时早于冷存恢复完成，保留当前代理会话等待唤醒结束再重开，不退回同样未唤醒的直链
                PikoLog.i(TAG, "Playback timed out while cloud reactivation is in progress, holding proxy session: ${logFile(fileId, title)}")
                isRecovering = true
                recoveryJob?.cancel()
                recoveryJob = scope.launch {
                    val woke = withTimeoutOrNull(REACTIVATION_WAIT_MILLIS) {
                        activePrepared.isReactivating.first { !it }
                        true
                    } == true
                    if (released || prepared !== activePrepared) return@launch
                    if (!woke) {
                        PikoLog.w(TAG, "Cloud reactivation timed out after ${REACTIVATION_WAIT_MILLIS}ms: ${logFile(fileId, title)}")
                        isRecovering = false
                        failure = dev.piko.shared.notice.PikoErrorMapper.failure(
                            tr("播放", "Playback", "再生", "재생", "播放"),
                            io.github.nihildigit.pikpak.FileReactivatingException(),
                        )
                        return@launch
                    }
                    // isReactivating 置回 false 仅代表后台轮询结束，需查验云端文件已确实恢复可读链接而非轮询失败退出
                    val probe = repository.prepareMedia(fileId, activeQuality)
                    if (released || prepared !== activePrepared) return@launch
                    val readyInfo = probe.getOrNull()
                    val probeError = probe.exceptionOrNull()
                    if (readyInfo == null || readyInfo.currentUrl.isBlank()) {
                        PikoLog.w(TAG, "Cloud reactivation finished without a playable stream: ${logFile(fileId, title)}", probeError)
                        isRecovering = false
                        failure = dev.piko.shared.notice.PikoErrorMapper.failure(
                            tr("播放", "Playback", "再生", "재생", "播放"),
                            probeError ?: io.github.nihildigit.pikpak.FileReactivatingException(),
                        )
                        return@launch
                    }
                    PikoLog.i(TAG, "Cloud reactivation succeeded, reopening proxy stream: ${logFile(fileId, title)}")
                    val subs = openSubtitles()
                    if (released || prepared !== activePrepared) return@launch
                    acceptedNativeGeneration = Long.MIN_VALUE
                    val reopenEpoch = maxOf(intentEpoch + 1L, backend.playbackGeneration + 1L)
                    intentEpoch = reopenEpoch
                    submitPlaybackIntent(
                        activePrepared,
                        WorkPhase.Urgent,
                        reopenEpoch,
                        PlaybackBufferSnapshot(reopenEpoch, null, TimeSource.Monotonic.markNow(), activePrepared.estimatedBytesPerSecond),
                    )
                    openStartedAt = TimeSource.Monotonic.markNow()
                    backend.open(PlaybackTarget.Url(proxyUrl), attemptStartMillis, subtitles = subs)
                    sessionSpeed?.let(backend::setSpeed)
                }
                return
            }
            // 还没出第一帧：同一条路原样重试没有意义，换一条
            pendingStartMillis = attemptStartMillis
            if (usingProxy && !directLinkTried) {
                // 代理与直链读的是同一份字节，先排除代理本身的问题。只试一次：
                // 直链也失败就说明坏的不是代理，换到转码流后不必再绕一遍直链
                PikoLog.i(TAG, "Pre-first-frame proxy failure, retrying once via direct URL: ${logFile(fileId, title)}")
                directLinkTried = true
                preferDirectLink = true
                isRecovering = true
                reload()
                return
            }
            val transcode = mediaInfo?.bestTranscodeName()
            if (isAutoQuality && activeQuality != null) {
                failedAutoQualities += activeQuality!!
                val fallback = autoQualityCandidates().firstOrNull { it.name != activeQuality }?.name ?: ORIGINAL_QUALITY
                PikoLog.i(TAG, "Auto-selected transcode $activeQuality failed before first frame, falling back to $fallback: ${logFile(fileId, title)}")
                preferDirectLink = false
                requestedQuality = fallback
                isRecovering = true
                reload()
                return
            }
            if (activeQuality == null && transcode != null) {
                PikoLog.i(TAG, "Original stream failed before first frame, falling back to $transcode transcode: ${logFile(fileId, title)}")
                preferDirectLink = false
                requestedQuality = transcode
                isRecovering = true
                _messages.tryEmit(tr("无法播放原画，已切换至转码画质", "Unable to play original; switched to transcoded stream", "オリジナルを再生できないため、変換ストリームに切り替えました", "원본을 재생할 수 없어 변환 화질로 전환했습니다", "無法播放原畫，已切換至轉碼畫質"))
                reload()
                return
            }
            PikoLog.w(
                TAG,
                "Pre-first-frame playback failed after exhausting fallbacks (directTried=$directLinkTried, quality=${activeQuality ?: ORIGINAL_QUALITY}): ${logFile(fileId, title)}",
            )
            pendingStartMillis = null
            isRecovering = false
            // 转码流一帧都没放出来，多半是服务端那一档坏了（有的档回 206 却没有正文，见 PikoMediaRepository.probeTranscode），
            // 播放器给的原因只是读不到数据，照抄会让人以为是网络或 Piko 的问题
            failure = activeQuality?.let { tr("$it 转码流暂不可用，请切换其他画质", "$it transcoded stream is unavailable. Choose another quality", "$it の変換ストリームを利用できません。別の画質を選択してください", "$it 변환 스트림을 사용할 수 없습니다. 다른 화질을 선택해 주세요", "$it 轉碼串流暫時無法使用，請切換其他畫質") } ?: message
            return
        }

        if (retryAttempt >= RECOVERY_DELAYS_MILLIS.size) {
            PikoLog.w(
                TAG,
                "Mid-stream playback interrupted and ${RECOVERY_DELAYS_MILLIS.size} reconnect attempts failed, giving up: ${logFile(fileId, title)}, posMs=${currentPosition()}",
            )
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
                val verified = hasVerifiedProgressForFile || (startedThisAttempt && resumePositionVerified)
                if (verified) {
                    persist(positionKey, currentPosition(), currentDuration())
                }
                reportPlayPeriodic()
            }
        } finally {
            // 离开播放器时补写本机进度；若作用域先于 release() 被取消，同时补交云端播放历史
            withContext(NonCancellable) {
                val verified = hasVerifiedProgressForFile || (startedThisAttempt && resumePositionVerified)
                if (verified) {
                    val pos = currentPosition()
                    val dur = currentDuration()
                    persist(positionKey, pos, dur)
                    val sessionClient = playbackAccountClient
                    if (!released && !isImage && fileId.isNotBlank() && sessionClient != null) {
                        repository.enqueuePlayHistory(
                            fileId = fileId,
                            positionMillis = pos,
                            durationMillis = dur,
                            urgent = true,
                            title = title,
                            client = sessionClient,
                        )
                    }
                }
            }
        }
    }

    // 周期云端上报去重与节流；实际服务端同文件冷却与失败重试由 PlayHistoryReporter 兜底保障
    private var lastPeriodicCloudFileId = ""
    private var lastPeriodicCloudPosSec = -1L
    private var lastPeriodicCloudAt = TimeSource.Monotonic.markNow()

    private fun reportPlayPeriodic() {
        val id = fileId
        val sessionClient = playbackAccountClient ?: return
        if (!startedThisAttempt || !resumePositionVerified || !backend.isPlaying || isImage || id.isBlank()) return
        val pos = currentPosition()
        val dur = currentDuration()
        if (pos < PlayHistoryReporter.MIN_REPORT_POSITION_MILLIS || dur <= 0L) return
        val posSec = pos / 1_000L
        val sameFile = id == lastPeriodicCloudFileId
        if (sameFile && posSec == lastPeriodicCloudPosSec) return
        if (sameFile && lastPeriodicCloudAt.elapsedNow() < PLAY_REPORT_INTERVAL) return
        if (repository.enqueuePlayHistory(id, pos, dur, urgent = false, title = title, client = sessionClient)) {
            lastPeriodicCloudFileId = id
            lastPeriodicCloudPosSec = posSec
            lastPeriodicCloudAt = TimeSource.Monotonic.markNow()
        }
    }

    private fun localPositionToSave(positionMillis: Long, durationMillis: Long): Long? = when {
        // 放完最后十秒记 0，下次从头播，而不是停在片尾
        durationMillis > 0L && positionMillis >= durationMillis - NEAR_END_MILLIS -> 0L
        positionMillis > MIN_PERSIST_MILLIS -> positionMillis
        else -> null
    }

    private fun persistAsync(key: String, positionMillis: Long, durationMillis: Long) {
        if (key.isBlank() || isImage) return
        val toSave = localPositionToSave(positionMillis, durationMillis) ?: return
        repository.savePlaybackPositionAsync(key, toSave)
    }

    private suspend fun persist(key: String, positionMillis: Long, durationMillis: Long) {
        if (key.isBlank() || isImage) return
        val toSave = localPositionToSave(positionMillis, durationMillis) ?: return
        val targetLog = logFile(fileId, title)
        runCatchingNonCancel("save local playback position ($targetLog)") {
            repository.savePlaybackPosition(key, toSave)
        }
    }

    private fun detachPrepared(): List<AutoCloseable> {
        val active = prepared
        prepared = null
        val subs = subtitleStreams
        subtitleStreams = emptyList()
        return buildList {
            if (active != null) add(active)
            addAll(subs)
        }
    }

    /**
     * 当前视频挂着的外挂字幕，各开一个代理会话，用户手动挂过的排在后面。播放列表还没取到时最多等一会儿：
     * 开播后虽然也能再挂（见 [PlaybackBackend.addSubtitle]），但「文件里没有选中的字幕就选第一条外挂」
     * 只在打开时判断一次，晚到的字幕不会被自动选中。开不起来的那条跳过。
     */
    private suspend fun openSubtitles(): List<ExternalSubtitle> {
        if (!isPlaylistLoaded && !waitedForPlaylist) {
            waitedForPlaylist = true
            val waitMillis = if (backend.canAddSubtitle) FAST_PLAYLIST_WAIT_MILLIS else PLAYLIST_WAIT_MILLIS
            withTimeoutOrNull(waitMillis) { snapshotFlow { isPlaylistLoaded }.first { it } }
            if (!isPlaylistLoaded && backend.canAddSubtitle) {
                attachLatePlaylistSubtitles(fileId)
            }
        }
        val refs = currentEntry?.subtitles.orEmpty()
        val subtitles = refs.mapNotNull { ref -> openPlaylistSubtitle(ref) }
        val manual = manualSubtitles.mapNotNull { subtitle ->
            val knownLang = subtitle.detectedLanguage ?: inferSubtitleLanguageCode(null, subtitle.title)
            val localPath = subtitle.localPath
            if (localPath != null) {
                val lang = knownLang ?: withContext(Dispatchers.IO) { sniffLocalSubtitleLanguage(localPath, subtitle.title) }
                ExternalSubtitle(url = localPath, title = subtitle.title, language = lang)
            } else {
                val id = subtitle.fileId ?: return@mapNotNull null
                val prepared = repository.prepareExternalSubtitle(id, knownLanguage = knownLang) ?: return@mapNotNull null
                val url = registerSubtitleStream(prepared.stream)
                ExternalSubtitle(url = url, title = subtitle.title, language = knownLang ?: prepared.detectedLanguage)
            }
        }
        return subtitles + manual
    }

    // 每开一条就登记，由 detachPrepared 统一拆出关闭。攒齐了再交出去的话，换集取消准备时已开的几条无人关闭；
    // 整体赋值还会盖掉这期间 addDriveSubtitle 登记的那条
    private fun registerSubtitleStream(stream: ProxyStream): String {
        subtitleStreams = subtitleStreams + stream
        return stream.url
    }

    private suspend fun openPlaylistSubtitle(ref: SubtitleRef): ExternalSubtitle? {
        val inferredLang = inferSubtitleLanguageCode(ref.language, ref.name)
        repository.localPath(ref.fileId, subtitle = true)?.let { path ->
            val detectedLang = inferredLang ?: withContext(Dispatchers.IO) { sniffLocalSubtitleLanguage(path, ref.name) }
            val displayTitle = externalSubtitleTitleFor(ref = ref, videoTitle = title, detectedLang = detectedLang)
            return ExternalSubtitle(
                url = path,
                title = displayTitle,
                language = detectedLang,
            )
        }
        val prepared = repository.prepareExternalSubtitle(ref.fileId, knownLanguage = inferredLang) ?: return null
        val detectedLang = inferredLang ?: prepared.detectedLanguage
        val displayTitle = externalSubtitleTitleFor(ref = ref, videoTitle = title, detectedLang = detectedLang)
        return ExternalSubtitle(
            url = registerSubtitleStream(prepared.stream),
            title = displayTitle,
            language = detectedLang,
        )
    }

    private fun autoQualityCandidates(): List<AbrRendition> {
        val info = mediaInfo ?: return emptyList()
        return autoQualityCandidatesFor(info, durationMillis)
    }

    private fun autoQualityCandidatesFor(info: PlayableMediaInfo, fallbackDurationMs: Long): List<AbrRendition> {
        if (info.kind != PlayableMediaKind.Video) return emptyList()
        val durationSec = info.durationSeconds.takeIf { it > 0L }
            ?: (fallbackDurationMs / 1_000L).takeIf { it > 0L }
            ?: 0L
        val out = ArrayList<AbrRendition>()
        val origVariant = info.availableVariants.firstOrNull { it.isOrigin }
        val origSize = info.originalSizeBytes.takeIf { it > 0L }
            ?: if (info.isOrigin && info.sizeBytes > 0L) info.sizeBytes else 0L
        val origBySize = if (origSize > 0L && durationSec > 0L) origSize / durationSec else 0L
        val origByBitRate = (origVariant?.video?.bitRate ?: 0L) / 8L
        val origBps = maxOf(origBySize, origByBitRate).takeIf { it > 0L } ?: (400L * 1024L)
        if (ORIGINAL_QUALITY !in failedAutoQualities) {
            out += AbrRendition(
                name = ORIGINAL_QUALITY,
                bytesPerSec = origBps,
                height = origVariant?.video?.height?.takeIf { it > 0 } ?: info.height.takeIf { it > 0 } ?: 1080,
                mediaId = null,
            )
        }
        val seen = HashSet<String>()
        for (variant in info.availableVariants) {
            if (variant.isOrigin || variant.video == null || variant.link.url.isBlank()) continue
            val name = variant.mediaName.ifBlank { variant.resolutionName }.takeIf { it.isNotBlank() } ?: continue
            if (name in failedAutoQualities || !seen.add(name)) continue
            val h = variant.video?.height ?: 0
            val bps = ((variant.video?.bitRate ?: 0L) / 8L).takeIf { it > 0L }
                ?: when {
                    h >= 1000 -> 140L * 1024L
                    h >= 680 -> 90L * 1024L
                    else -> 60L * 1024L
                }
            out += AbrRendition(name = name, bytesPerSec = bps, height = h, mediaId = variant.mediaId)
        }
        return out.sortedWith(compareByDescending<AbrRendition> { it.bytesPerSec }.thenByDescending { it.height })
    }

    private fun pickInitialAutoQuality(): String {
        val candidates = autoQualityCandidates()
        if (candidates.isEmpty()) return ORIGINAL_QUALITY
        val speed = (sessionSpeed ?: backend.speed).coerceAtLeast(0.5f)
        return abrController.pickInitial(
            candidates = candidates,
            playbackSpeed = speed,
            cachedCoverageByMediaId = cachedCoverageByMediaId,
            windowCachedRenditions = cachedWindowRenditions,
        ).ifEmpty { ORIGINAL_QUALITY }
    }

    private fun switchAutoQuality(decision: AbrDecision) {
        val activeName = activeQuality ?: ORIGINAL_QUALITY
        if (decision.targetName == activeName) return
        PikoLog.i(
            TAG,
            "Auto quality switching $activeName -> ${decision.targetName} " +
                "(reason=${decision.reason}, forSpeed=${decision.forSpeed}, speed=${sessionSpeed ?: backend.speed}, " +
                "ewmaBps=${abrController.estimatedBytesPerSec ?: -1L}): ${logFile(fileId, title)}",
        )
        pendingStartMillis = currentPosition()
        requestedQuality = decision.targetName
        resetRecovery()
        reload()
    }

    /**
     * 「自动」画质自适应循环（Shaka Dual-EWMA 活跃传输测速 + DASH.js BOLA 缓冲效用混合算法）：
     * - 仅在活跃网络拉取期间累计字节与耗时，消除低画质满缓冲时的限速低估陷阱；
     * - 严格受 `isAutoQuality`（设置「自动」档或播放器内显式切换至「自动」）门控。
     */
    private suspend fun autoQualityLoop() {
        var lastToken: PreparedPlayback? = null
        var lastActiveBytes = 0L
        var lastActiveNanos = 0L
        var pendingBurstBytes = 0L
        var pendingBurstNanos = 0L
        var coveragePollTick = 0

        while (!released) {
            delay(AUTO_QUALITY_POLL_MS)
            if (released || isLocalPlayback || isImage || isPreparing || isRecovering || failure != null) {
                continue
            }
            val active = prepared
            val nowMs = abrNowMillis()
            if (active !== lastToken) {
                lastToken = active
                val snap = active?.activeTransferSnapshot() ?: (0L to 0L)
                lastActiveBytes = snap.first
                lastActiveNanos = snap.second
                pendingBurstBytes = 0L
                pendingBurstNanos = 0L
                coveragePollTick = 0
                continue
            }
            if (active != null && startedThisAttempt) {
                val (curActiveBytes, curActiveNanos) = active.activeTransferSnapshot()
                val deltaBytes = (curActiveBytes - lastActiveBytes).coerceAtLeast(0L)
                val deltaNanos = (curActiveNanos - lastActiveNanos).coerceAtLeast(0L)
                lastActiveBytes = curActiveBytes
                lastActiveNanos = curActiveNanos
                if (deltaBytes > 0L && deltaNanos > 0L) {
                    pendingBurstBytes += deltaBytes
                    pendingBurstNanos += deltaNanos
                    if (pendingBurstBytes >= DualEwmaBandwidthEstimator.MIN_BURST_BYTES &&
                        pendingBurstNanos >= DualEwmaBandwidthEstimator.MIN_BURST_NANOS
                    ) {
                        abrController.recordActiveBurst(pendingBurstBytes, pendingBurstNanos, nowMs)
                        pendingBurstBytes = 0L
                        pendingBurstNanos = 0L
                    }
                }
            }

            if (!isAutoQuality || !startedThisAttempt) continue
            if (seeking) {
                lastSeekAtMillis = nowMs
                abrController.notePlaybackReady(nowMs)
                continue
            }
            if (lastSeekAtMillis != Long.MIN_VALUE && nowMs - lastSeekAtMillis < POST_SEEK_ABR_GRACE_MS) {
                continue
            }
            val candidates = autoQualityCandidates()
            if (candidates.size < 2) continue

            val info = mediaInfo
            if (info != null && coveragePollTick % ABR_CACHE_POLL_EVERY_TICKS == 0) {
                val (cov, win) = try {
                    repository.cachedRenditionCoverage(info, backend.positionMillis, durationMillis)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    cachedCoverageByMediaId to cachedWindowRenditions
                }
                cachedCoverageByMediaId = cov
                cachedWindowRenditions = win
            }
            coveragePollTick++

            val activeName = activeQuality ?: ORIGINAL_QUALITY
            val activeCandidate = candidates.firstOrNull { it.name == activeName }
            val currentWindowHit = activeName in cachedWindowRenditions ||
                (activeCandidate?.mediaId != null && activeCandidate.mediaId in cachedWindowRenditions)
            val decision = abrController.evaluate(
                AbrObservation(
                    nowMillis = nowMs,
                    candidates = candidates,
                    currentName = activeName,
                    playbackSpeed = (sessionSpeed ?: backend.speed).coerceAtLeast(0.5f),
                    bufferSeconds = backend.bufferSnapshot?.bufferedDuration?.inWholeMilliseconds?.div(1_000.0),
                    isBuffering = backend.isBuffering && backend.isPlaying,
                    isPlaying = backend.isPlaying,
                    cachedCoverageByMediaId = cachedCoverageByMediaId,
                    currentWindowCached = currentWindowHit,
                    windowCachedRenditions = cachedWindowRenditions,
                ),
            )
            if (decision != null) {
                switchAutoQuality(decision)
            }
        }
    }

    /**
     * 转码流播放时，后台尝试从原画容器中探测并挂载仅原画携带的内封字幕（MP4 稀疏 Range / 小体积 MKV）。
     */
    private fun attachOriginalEmbeddedSubtitles(targetFileId: String) {
        originalSubtitlesJob?.cancel()
        originalSubtitlesJob = scope.launch {
            val result = repository.prepareOriginalEmbeddedSubtitles(targetFileId)
            if (fileId != targetFileId || released || mediaInfo?.isOrigin == true) {
                result.attached.forEach { (_, stream) -> runCatching { stream.close() } }
                return@launch
            }
            originalSubtitleHints = result.unextracted
            if (result.attached.isNotEmpty()) {
                val existingTitles = backend.subtitleTracks.mapNotNull { it.title }.toSet()
                for ((ext, stream) in result.attached) {
                    if (fileId != targetFileId || released) {
                        runCatching { stream.close() }
                        continue
                    }
                    registerSubtitleStream(stream)
                    if (ext.title !in existingTitles) {
                        backend.addSubtitle(ext)
                    }
                }
                PikoLog.i(TAG, "Mounted ${result.attached.size} original-only embedded subtitle track(s) onto transcode stream: ${logFile(targetFileId, title)}")
                applyTrackPreferences()
            } else if (result.unextracted.isNotEmpty()) {
                PikoLog.d(
                    TAG,
                    "Detected ${result.unextracted.size} original-only embedded subtitle track(s) in large MKV (${result.unextracted.joinToString { "${it.codecId}:${it.language ?: "?"}" }}): ${logFile(targetFileId, title)}",
                )
            }
        }
    }

    private fun attachLatePlaylistSubtitles(targetFileId: String) {
        lateSubtitlesJob?.cancel()
        lateSubtitlesJob = scope.launch {
            val loaded = withTimeoutOrNull(PLAYLIST_WAIT_MILLIS) { snapshotFlow { isPlaylistLoaded }.first { it } }
            if (loaded != true || fileId != targetFileId || released) return@launch
            val refs = currentEntry?.subtitles.orEmpty()
            if (refs.isEmpty()) return@launch
            for (ref in refs) {
                if (fileId != targetFileId || released) return@launch
                val ext = openPlaylistSubtitle(ref) ?: continue
                if (fileId != targetFileId || released) return@launch
                backend.addSubtitle(ext)
            }
            applyTrackPreferences()
        }
    }

    private suspend fun runCatchingNonCancel(operation: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 本地进度写入异常不能中断播放；云端上报异常已由 repository.reportPlay 内部记录并吞掉
            PikoLog.w(TAG, "Failed to $operation", e)
        }
    }

    private companion object {
        const val TAG = "Player"

        const val SEEK_SETTLE_MS = 200L
        const val SEEK_JUMP_DETECT_THRESHOLD_MS = 5_000L
        const val SEEK_JUMP_MOVED_MS = 2_000L
        const val SEEK_WATCH_MS = 15_000L
        const val SPEED_SAMPLE_INTERVAL_MS = 350L
        const val RESUME_THRESHOLD_MILLIS = 3_000L
        const val RESUME_VERIFY_TOLERANCE_MILLIS = 30_000L

        // 打开第一个文件前等播放列表的上限，超过就不带外挂字幕先放；支持运行时挂载字幕的后端只短暂等缓存命中
        const val PLAYLIST_WAIT_MILLIS = 3_000L
        const val FAST_PLAYLIST_WAIT_MILLIS = 80L

        // 从并行查询发起时计时，准备取流消耗同一预算；有本机续播记录时更早开播。
        const val CLOUD_RESUME_WAIT_MILLIS = 400L
        const val LOCAL_RESUME_WAIT_MILLIS = 100L

        // 实测服务端丢掉间隔约 1.5 秒的上报、收下 6 秒的，留足余量
        val PLAY_REPORT_INTERVAL = 10.seconds
        const val NEAR_END_MILLIS = 10_000L
        const val MIN_PERSIST_MILLIS = 1_500L
        const val PERSIST_INTERVAL_MILLIS = 5_000L
        const val RESUME_TIP_MILLIS = 5_000L
        const val STABLE_PLAYBACK_MILLIS = 15_000L
        const val STOP_DRAIN_TIMEOUT_MILLIS = 350L
        const val REACTIVATION_WAIT_MILLIS = 5 * 60_000L
        const val AUTO_QUALITY_POLL_MS = 1_000L
        const val POST_SEEK_ABR_GRACE_MS = 3_000L
        const val ABR_CACHE_POLL_EVERY_TICKS = 3
        val RECOVERY_DELAYS_MILLIS = longArrayOf(500L, 1_500L, 4_000L)
        val retireScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

fun formatTransferRate(bytesPerSec: Long): String {
    val kb = bytesPerSec / 1024.0
    return if (kb >= 1000.0) {
        val mb = kb / 1024.0
        val tenths = (mb * 10.0).toLong()
        "${tenths / 10}.${tenths % 10} MB/s"
    } else {
        "${kb.toLong().coerceAtLeast(0L)} KB/s"
    }
}

/** 手动挂上的一条字幕：本机的记路径，网盘的记 ID，每次打开文件时重新开代理会话。 */
private class ManualSubtitle(
    val title: String,
    val localPath: String?,
    val fileId: String?,
    val detectedLanguage: String? = null,
)
