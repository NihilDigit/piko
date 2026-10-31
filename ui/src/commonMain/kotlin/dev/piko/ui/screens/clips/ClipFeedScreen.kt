package dev.piko.ui.screens.clips

import dev.piko.shared.i18n.tr

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFile
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.media.PreparedClip
import dev.piko.shared.media.player.PlaybackBackendEvent
import dev.piko.shared.media.player.PlaybackTarget
import dev.piko.shared.state.CLIP_LENGTH_MS
import dev.piko.shared.state.Clip
import dev.piko.shared.state.ClipFeedSession
import dev.piko.shared.state.ClipStreams
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.MediaLoadingIndicator
import dev.piko.ui.components.pageFocusTarget
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.PreviewBackend
import dev.piko.ui.screens.player.PlayerTheme
import dev.piko.data.auth.PlayerGestureDefaults
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.StreamRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.DpSize
import dev.piko.shared.media.player.PlayerAspectRatio
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * 信息流：竖向翻页，每页是某个视频里随机一处起的 30 秒，放完自动翻到下一页。队列在 ClipFeedSession 里，
 * 这一页只管播放：打开完整播放器时它可能被销毁，回来时从会话里的当前段接着放。
 *
 * 三个预览播放器轮换，按页码取模分给上一段、当前段与下一段：前后两段提前打开、停在第一帧，翻过去只需 play。
 * 只用一个播放器时，翻页中相邻页没有画面，停稳后换源又要等首帧，两处都黑。
 * 翻页器里只有取好的段，还没取好的在候补里，见 [ClipStreams] 与 ClipFeedSession.upcoming。
 *
 * 样子照短视频应用：顶部只有在刷的文件夹与静音、沉浸、关闭，右侧一列操作，左下是说明，底边一条细进度条，都常驻，
 * 只在拖进度条时让开。控件是翻页器之上的固定一层，只画当前段，不随页滑动。区域矮到竖排的操作栏放不下时
 * （横握的手机）操作栏改为右下横排。单击暂停或继续，双击收藏，长按两倍速，上下滑、滚轮或上下键翻页，
 * 左右键前进后退，空格暂停，M 静音。沉浸时控件连同顶栏全部藏起，单击只叫回控件。
 *
 * [compact] 是放在网盘页右侧的窄面板里：按钮与文字缩小，范围靠左。面板不画栏名，关闭也在这条顶栏上。
 * [onPopOut] 与 [onDock] 是桌面端在主窗口与独立窗口之间挪动它，平台没有独立窗口时为 null。
 */
@Composable
fun ClipFeedScreen(
    onBackClick: () -> Unit,
    onPlayFull: (file: FileStat, startMillis: Long) -> Unit,
    onLocate: (FileStat) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    pausedByHost: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null,
    onPopOut: (() -> Unit)? = null,
    onDock: (() -> Unit)? = null,
) {
    val services = LocalPikoServices.current
    val session = services.clipFeedSession
    val videoPreview = LocalPikoPlatform.current.videoPreview
    val clips = session.clips
    val scopeVersion = session.scopeVersion
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 挂在会话上，页面离开再回来（看完整、挂起）时取好的段都还在，见 ClipStreams
    val streams = session.streams
    // 头一段画面走起来没有。之前只备前 COLD_START_CLIPS 段：冷开时十来段一齐备会话、取开头，
    // 头一段分到的带宽只剩几分之一，实测要等 5 秒多才取好（2026-09-28）。
    // 页面重建（打开、继续刷）都从这一步重来
    var started by remember(scopeVersion) { mutableStateOf(false) }
    // 只取够用的几段：翻页器里到过的最远一段之后取好的不到 READY_AHEAD 段时，才从候补里补上差额去取。
    // 二十几段一起取，刷过去的多半轮不到，流量白花，还跟真要播的抢带宽；每段也只取开头几秒，见 PreparedClip。
    // 备会话只调接口不费流量，往前多备 PREPARE_AHEAD 段：轮到取时会话现成，取好一段只剩下载那一两 MB，
    // 连着快翻时供得上。从最远处数而不从当前段数，往回翻时照样取，见 ClipFeedSession.furthestIndex
    LaunchedEffect(streams, scopeVersion) {
        snapshotFlow {
            val ready = session.clips.size - 1 - session.furthestIndex
            val prepareAhead = if (started) PREPARE_AHEAD else COLD_START_CLIPS
            val readyAhead = if (started) READY_AHEAD else COLD_START_CLIPS
            session.upcoming.take((prepareAhead - ready).coerceAtLeast(0)) to (readyAhead - ready).coerceAtLeast(0)
        }.collect { (ahead, fetchCount) ->
            // 先让要取的段以前台身份建起会话，再为其余的提前备会话；反过来的话它们已以后台身份建好
            streams.ripen(ahead.take(fetchCount))
            ahead.forEach { streams.get(it) }
        }
    }

    val actions = remember(session) {
        ClipActions(session, services.driveRepository, services.downloadManager, scope) { message ->
            scope.launch { snackbarHostState.showSnackbar(message) }
        }
    }
    LaunchedEffect(actions) { actions.loadStars() }

    // 系统锁定了自动旋转时，横握手机也转不过来，由这里请求横屏；离开组合时平台恢复原来的方向
    val landscapeLock = LocalPikoPlatform.current.rememberLandscapeLock()
    if (landscapeLock != null) {
        LaunchedEffect(landscapeLock, session.landscape) {
            if (session.landscape) landscapeLock.lock() else landscapeLock.release()
        }
    }
    val playFull: (FileStat, Long) -> Unit = { file, startMillis ->
        // 先放开再压栈：播放器在组合时就记下当前方向，退出时恢复它，晚于这一步放开的话它记下的是横屏，
        // 退出后一直锁着。回来时会话里的 landscape 还在，这里重新锁上
        landscapeLock?.release()
        onPlayFull(file, startMillis)
    }

    // 横屏时 × 与返回都先回到竖屏，第二次才关掉信息流；后登记的先收到，压在外层的返回之上
    val inLandscape = landscapeLock != null && session.landscape
    BackHandler(enabled = inLandscape) { session.landscape = false }
    val toggleAutoAdvance: () -> Unit = {
        val next = !session.autoAdvance
        session.autoAdvance = next
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                if (next) {
                    tr("已开启自动连刷：播完当前片段自动翻到下一条", "Auto-advance enabled: advancing after each clip", "自動送りオン：再生後に次のクリップへ移動します", "자동 넘김 켜짐: 클립 재생 후 다음으로 이동합니다", "已開啟自動連刷：播完目前片段自動切換至下一條")
                } else {
                    tr("已关闭自动连刷", "Auto-advance disabled", "自動送りをオフにしました", "자동 넘김을 껐습니다", "已關閉自動連刷")
                },
            )
        }
    }
    val topBar: @Composable BoxScope.(onHideChrome: (() -> Unit)?) -> Unit = { onHideChrome ->
        ClipFeedTopBar(
            title = session.scopeLabel,
            muted = session.muted,
            onToggleMute = { session.muted = !session.muted },
            onClose = { if (inLandscape) session.landscape = false else onBackClick() },
            compact = compact,
            autoAdvance = session.autoAdvance,
            onToggleAutoAdvance = if (onHideChrome != null) toggleAutoAdvance else null,
            onPopOut = onPopOut,
            onDock = onDock,
            onToggleFullscreen = onToggleFullscreen,
            onHideChrome = onHideChrome,
            landscapeLocked = if (landscapeLock != null && !compact) session.landscape else null,
            onToggleLandscape = { session.landscape = !session.landscape },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }

    // 控件照播放器的样式，固定深色
    PlayerTheme {
        Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
            when {
                videoPreview == null -> FeedMessage(tr("此平台暂不支持信息流", "Feed is not supported on this platform yet", "このプラットフォームではフィードにまだ対応していません", "이 플랫폼은 아직 피드를 지원하지 않습니다", "此平台尚不支援資訊流"), null, topBar)
                clips.isEmpty() && !session.isEmpty -> {
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            MediaLoadingIndicator()
                            Text(stringResource(Res.string.text_preparing), color = Color.White, modifier = Modifier.padding(top = 16.dp))
                        }
                        topBar(null)
                    }
                }
                clips.isEmpty() -> FeedMessage(tr("当前范围无可播放视频", "No playable videos", "再生可能な動画がありません", "재생 가능한 동영상이 없습니다", "目前範圍無可播放影片"), tr("仅包含时长超过 1 分钟的正片视频（含子文件夹）", "Includes main videos over 1 minute, including subfolders", "サブフォルダーを含む1分以上の本編動画が対象です", "하위 폴더를 포함하여 1분 이상의 본편 동영상만 포함됩니다", "僅包含時長超過 1 分鐘的正片影片（含子資料夾）"), topBar)
                // 换了范围就是另一条队列，页码、播放器与各段的装载状态都不再对得上，整个重建
                else -> key(scopeVersion) {
                    ClipPager(
                        session = session,
                        clips = clips,
                        upcoming = session.upcoming,
                        streams = streams,
                        actions = actions,
                        snackbarHostState = snackbarHostState,
                        compact = compact,
                        pausedByHost = pausedByHost,
                        onPlayFull = playFull,
                        onLocate = onLocate,
                        onToggleAutoAdvance = toggleAutoAdvance,
                        onStarted = { started = true },
                        overlay = topBar,
                    )
                }
            }
            SnackbarHost(
                snackbarHostState,
                Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).padding(top = 64.dp),
            )
        }
    }

}

@Composable
private fun FeedMessage(title: String, description: String?, topBar: @Composable BoxScope.(onHideChrome: (() -> Unit)?) -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        PikoEmptyState(title = title, description = description)
        topBar(null)
    }
}

@Composable
private fun ClipPager(
    session: ClipFeedSession,
    clips: List<Clip>,
    upcoming: List<Clip>,
    streams: ClipStreams,
    actions: ClipActions,
    snackbarHostState: SnackbarHostState,
    compact: Boolean,
    pausedByHost: Boolean,
    onPlayFull: (FileStat, Long) -> Unit,
    onLocate: (FileStat) -> Unit,
    onToggleAutoAdvance: () -> Unit,
    /** 头一段画面走起来了，冷开的限制可以放开。 */
    onStarted: () -> Unit,
    /** 顶栏，传进去的是进入沉浸；沉浸时整条藏起。 */
    overlay: @Composable BoxScope.(onHideChrome: (() -> Unit)?) -> Unit,
) {
    val videoPreview = LocalPikoPlatform.current.videoPreview ?: return
    val scope = rememberCoroutineScope()
    // 与播放器共用播放设置里的长按倍速与进退步长
    val preferences = LocalPikoServices.current.preferences
    val boostSpeed by preferences.playerBoostSpeedFlow.collectAsState(initial = PlayerGestureDefaults.BOOST_SPEED)
    val seekStepSeconds by preferences.playerSeekStepSecondsFlow.collectAsState(initial = PlayerGestureDefaults.SEEK_STEP_SECONDS)
    val clipAutoExtend by preferences.clipAutoExtendFlow.collectAsState(initial = true)
    val latestAutoExtend by rememberUpdatedState(clipAutoExtend)
    val autoAdvance = session.autoAdvance
    val latestAutoAdvance by rememberUpdatedState(autoAdvance)
    val seekStepMillis = seekStepSeconds * 1000L
    // 末尾多一页等待页：下一段还没取好时翻得过去看它转圈，再往后翻不动；候补一取好，这一页就原地变成那一段
    val pageCount = clips.size + if (upcoming.isNotEmpty()) 1 else 0
    val pagerState = rememberPagerState(initialPage = session.currentIndex) { pageCount }
    val players = List(POOL_SIZE) { videoPreview.rememberPreviewBackend(keyframeStart = true) }
    // 在协程里读列表的最新值；参数本身是组合时的快照
    val latestClips by rememberUpdatedState(clips)
    val latestUpcoming by rememberUpdatedState(upcoming)
    // 每个播放器眼下装着哪一段。一页只在它的播放器装的正是这一段时挂上画面：翻页器会多组合前后各一页，
    // 同一时刻最多四页在组合里，取模必有两页撞在同一个播放器上
    val loaded = remember { mutableStateListOf<Clip?>(*arrayOfNulls(POOL_SIZE)) }
    // 每个播放器已出过首帧的那一段，开播日志据此区分是不是预渲染好的
    val rendered = remember { mutableStateListOf<Clip?>(*arrayOfNulls(POOL_SIZE)) }
    // 每个播放器放到过流尾的那一段
    val ended = remember { mutableStateListOf<Clip?>(*arrayOfNulls(POOL_SIZE)) }
    // 每个播放器所装那一段在播放器时钟上的起点：转码切片从 0 起，原画从片中起点起，见 PreparedClip
    val startOnPlayer = remember { List(POOL_SIZE) { 0L }.toMutableStateList() }
    // 每个播放器所装那一段实际从全片哪一刻开始，读自切片的时间戳，见 PreparedClip.clipStartMs。
    // 看完整从这里接着放；Clip.startMs 只是挑段时定的目标，切片按码率估的位置会偏开它
    val clipStartMs = remember { List(POOL_SIZE) { 0L }.toMutableStateList() }
    // 每个播放器所装那一段当前开放的播放长度，初值为 CLIP_LENGTH_MS，播完一段自动往后续 30s
    val clipLengths = remember { List(POOL_SIZE) { CLIP_LENGTH_MS }.toMutableStateList() }
    // 每个播放器所装那一段允许扩展到的最大时长（有界 TS 切片取切片窗口上限，原画取剩余片长）
    val maxWindowLengths = remember { List<Long?>(POOL_SIZE) { null }.toMutableStateList() }
    // 按 Clip 记住已扩展的窗口长度与离开时的播放进度，回翻重新装入时保留进度与窗口，不重置回 0s / 30s
    val rememberedLengths = remember { mutableStateMapOf<Clip, Long>() }
    val rememberedPositions = remember { mutableStateMapOf<Clip, Long>() }
    val loadJobs = remember { arrayOfNulls<Job>(POOL_SIZE) }
    val focusRequester = remember { FocusRequester() }
    var paused by remember { mutableStateOf(pausedByHost) }
    val latestPaused by rememberUpdatedState(paused)
    val latestHostPaused by rememberUpdatedState(pausedByHost)
    // 长按期间两倍速
    var boosting by remember { mutableStateOf(false) }
    // 拖进度条时说明与操作栏让开，看得到画面
    var scrubbing by remember { mutableStateOf(false) }
    // 沉浸：控件连同顶栏全部藏起，点画面任一处叫回。记在会话上，看完整、弹出窗口回来照旧
    var immersive by session::immersive
    // 区域矮到右侧竖排的操作栏放不下时（几乎就是横握的手机）按横屏排控件，见 LandscapeBelowHeight
    var areaHeight by remember { mutableStateOf(0) }
    val landscape = areaHeight > 0 && with(LocalDensity.current) { areaHeight.toDp() } < LandscapeBelowHeight
    // 当前段已走起来、前后两段可以开始装的是哪一段，见 LaunchedEffect(current)
    var neighboursFor by remember { mutableStateOf<Clip?>(null) }
    // 定位之后、画面重新走起来之前。不单靠后端报的缓冲：拖到已缓存的地方不报，拖到没缓存的地方两端报得也不一致
    var seekPending by remember { mutableStateOf(0) }
    val motion = MaterialTheme.motionScheme
    val chromeAlpha by animateFloatAsState(if (scrubbing) 0f else 1f, motion.defaultEffectsSpec())
    val muted = session.muted
    val latestMuted by rememberUpdatedState(muted)
    // 开播前先缓存好的段数；到 WARM_UP_CLIPS 或超时才开播
    var warmed by remember { mutableIntStateOf(0) }
    var warmUpDone by remember { mutableStateOf(false) }

    // 眼下算作在看的那一页：手指按着时是停稳的那页，一松手就是要去的那页（targetPage），不等吸附动画收尾。
    // 等 settledPage 的话，手机上页面看着已经到位，吸附还要收尾一阵，这期间进度条不出、也不开播，
    // 翻到预渲染好的一段也要顿 0.3 到 0.4 秒；桌面用滚轮与按键翻页，收尾短，看不出来（2026-09-28）
    val dragging by pagerState.interactionSource.collectIsDraggedAsState()
    val settled = (if (dragging) pagerState.settledPage else pagerState.targetPage).coerceIn(0, pageCount - 1)
    val latestSettled by rememberUpdatedState(settled)
    // 停在等待页上时为 null
    val current = clips.getOrNull(settled)
    fun slotOf(page: Int) = page % POOL_SIZE
    val currentPlayer = players[slotOf(settled)]

    fun go(delta: Int) {
        // 手动能翻到等待页为止；自动翻页等下一段取好才翻，见 autoStep
        val target = (pagerState.currentPage + delta).coerceIn(0, pagerState.pageCount - 1)
        if (target != pagerState.currentPage) scope.launch { pagerState.animateScrollToPage(target) }
    }

    // 按下还没松开的键，用来认出连发
    var heldKey by remember { mutableStateOf<Key?>(null) }

    // 自动翻页请求绑定来源片段身份与开关状态：慢网预取超过 10s 不会丢失请求，关闭开关或换页立即取消
    var pendingAdvanceClip by remember { mutableStateOf<Clip?>(null) }

    LaunchedEffect(autoAdvance, current) {
        if (!autoAdvance || pendingAdvanceClip != current) {
            pendingAdvanceClip = null
        }
    }

    /**
     * 放满一段后翻到下一段。翻页器里只有取好的，下一段还没取好就让这一段接着放，取好了再翻，
     * 不翻过去对着黑屏。
     */
    fun autoStep(forClip: Clip?) {
        if (forClip != null && latestAutoAdvance) {
            pendingAdvanceClip = forClip
        }
    }
    LaunchedEffect(pendingAdvanceClip, autoAdvance) {
        val sourceClip = pendingAdvanceClip
        if (!autoAdvance || sourceClip == null) return@LaunchedEffect
        val sourcePage = latestSettled
        if (latestClips.getOrNull(sourcePage) != sourceClip) {
            pendingAdvanceClip = null
            return@LaunchedEffect
        }
        val target = sourcePage + 1
        snapshotFlow { latestClips.size > target }.first { it }
        if (latestAutoAdvance && pendingAdvanceClip == sourceClip && latestSettled == sourcePage && latestClips.getOrNull(sourcePage) == sourceClip) {
            pendingAdvanceClip = null
            go(1)
        } else {
            pendingAdvanceClip = null
        }
    }

    fun maxPlayableLength(slot: Int, clip: Clip, videoStart: Long = clipStartMs[slot]): Long {
        val videoRemaining = if (clip.videoDurationMs > 0L) (clip.videoDurationMs - videoStart).coerceAtLeast(1_000L) else null
        val cap = maxWindowLengths[slot]?.takeIf { it > 0L }
        val playerDur = if (rendered[slot] == clip) {
            (players[slot].durationMillis - startOnPlayer[slot]).takeIf { it > 0L }
        } else null
        var bound = videoRemaining ?: Long.MAX_VALUE
        if (cap != null) bound = minOf(bound, cap)
        if (playerDur != null) bound = minOf(bound, playerDur)
        return bound.coerceAtLeast(1_000L)
    }

    fun saveSlotProgress(slot: Int) {
        val oldClip = loaded[slot] ?: return
        if (rendered[slot] != oldClip) return
        val len = clipLengths[slot].coerceAtLeast(CLIP_LENGTH_MS)
        val maxRem = maxPlayableLength(slot, oldClip)
        val pos = (players[slot].positionMillis - startOnPlayer[slot]).coerceIn(0L, len)
        val finished = ended[slot] == oldClip || (len >= maxRem && pos >= len - REPLAY_WITHIN_MS)
        rememberedPositions[oldClip] = if (finished) 0L else pos
    }

    /** 把第 [page] 段装进它的播放器，停在片段起点（或上次离开处）的第一帧。已经装着这一段就不动，停在哪里算哪里。 */
    fun load(page: Int): Job? {
        val clip = clips.getOrNull(page) ?: return null
        val slot = slotOf(page)
        if (loaded[slot] == clip) return loadJobs[slot]
        saveSlotProgress(slot)
        loadJobs[slot]?.cancel()
        loaded[slot] = clip
        rendered[slot] = null
        ended[slot] = null
        return scope.launch {
            val started = TimeSource.Monotonic.markNow()
            val prepared = streams.get(clip, forPlayer = true).await()
            val url = prepared?.url
            if (prepared == null || url.isNullOrBlank()) {
                loaded[slot] = null
                return@launch
            }
            val preparedMs = started.elapsedNow().inWholeMilliseconds
            startOnPlayer[slot] = prepared.startOnPlayer
            clipStartMs[slot] = prepared.clipStartMs
            maxWindowLengths[slot] = prepared.maxWindowMs
            val maxRem = maxPlayableLength(slot, clip, prepared.clipStartMs)
            val savedLength = if (latestAutoAdvance || !latestAutoExtend) CLIP_LENGTH_MS else (rememberedLengths[clip] ?: CLIP_LENGTH_MS)
            val initialLength = savedLength.coerceAtMost(maxRem)
            clipLengths[slot] = initialLength
            val rawSaved = (rememberedPositions[clip] ?: 0L).coerceIn(0L, initialLength)
            val resumeOffset = if (initialLength >= maxRem && rawSaved >= initialLength - REPLAY_WITHIN_MS) 0L else rawSaved
            try {
                // 回翻恢复到半途位置时，先放开代理侧预读，确保跳出开头 5 秒预取区也能顺畅解出首帧
                if (resumeOffset > 0L) streams.boost(clip, BOOSTED_READ_AHEAD_SECONDS, BOOSTED_MIN_AHEAD_BYTES)
                // 新装的段先压着，真看起来才升档，见 LaunchedEffect(current)
                players[slot].setBufferAhead(HOLD_BUFFER_SECONDS)
                players[slot].open(
                    prepared.localPath?.let { PlaybackTarget.LocalFile(it) } ?: PlaybackTarget.Url(url),
                    startMillis = prepared.startOnPlayer + resumeOffset,
                    playWhenReady = false,
                )
                // 换文件后音量是否沿用看后端，静音时每次装完都再设一遍
                if (latestMuted) players[slot].setVolume(0f)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 打不开的这一段交给翻到它时的跳过；异常不能漏出去，这个作用域的异常会一路报到 UI 线程
                PikoLog.w("Clips", "第 $page 段打不开：${logFile(clip.fileId, clip.name)}", e)
                loaded[slot] = null
                return@launch
            }
            PikoLog.d(
                "Clips",
                "装入第 $page 段 ${logFile(clip.fileId, clip.name)}（${if (prepared.sliced) "转码切片" else "原画"}）：" +
                    "取流 $preparedMs ms，打开 ${started.elapsedNow().inWholeMilliseconds} ms",
            )
            snapshotFlow { rendered[slot] == clip }.first { it }
            PikoLog.d("Clips", "第 $page 段就绪：共 ${started.elapsedNow().inWholeMilliseconds} ms")
        }.also { loadJobs[slot] = it }
    }

    fun stopBoost() {
        if (!boosting) return
        boosting = false
        players.forEach { it.setSpeed(1f) }
    }

    DisposableEffect(Unit) {
        onDispose { players.forEach(PreviewBackend::release) }
    }
    val pointerSource = dev.piko.ui.components.LocalPointerSource.current
    LaunchedEffect(pointerSource.isTouchLike) {
        if (!pointerSource.isTouchLike) runCatching { focusRequester.requestFocus() }
    }
    LaunchedEffect(pagerState) { snapshotFlow { latestSettled }.collect(session::moveTo) }
    LaunchedEffect(muted) { players.forEach { it.setVolume(if (muted) 0f else 1f) } }
    // 手指还按着就翻走了，松手的事件落不到这一页
    LaunchedEffect(settled) { stopBoost() }

    // 先攒几段取好的再开播：一上来就刷的头几段最容易赶上还没取好的
    LaunchedEffect(Unit) {
        withTimeoutOrNull(WARM_UP_TIMEOUT) {
            snapshotFlow { (latestClips.size - latestSettled).coerceAtLeast(0) }
                .first { ready ->
                    warmed = ready.coerceAtMost(WARM_UP_CLIPS)
                    ready >= WARM_UP_CLIPS
                }
        }
        warmUpDone = true
    }

    players.forEachIndexed { slot, player ->
        LaunchedEffect(player) {
            player.events.collect { event ->
                when (event) {
                    PlaybackBackendEvent.Ready -> {
                        rendered[slot] = loaded[slot]
                        ended[slot] = null
                    }
                    // 预渲染的那两个出错不翻页，轮到它们时再装一次
                    PlaybackBackendEvent.Ended -> {
                        ended[slot] = loaded[slot]
                        loaded[slot]?.let { rememberedPositions[it] = 0L }
                        if (slot == slotOf(latestSettled)) {
                            if (latestAutoAdvance) {
                                autoStep(loaded[slot])
                            } else {
                                ended[slot] = null
                                player.seekTo(startOnPlayer[slot])
                                if (!latestPaused) player.play()
                            }
                        }
                    }
                    is PlaybackBackendEvent.Error -> if (slot == slotOf(latestSettled)) go(1)
                }
            }
        }
    }

    // 停在末尾时后面接上了新取好的段，也装进播放器预渲染，不等翻过去。当前段走起来之后才装，见 neighboursFor
    LaunchedEffect(settled, clips.size, warmUpDone, neighboursFor) {
        if (!warmUpDone || current == null) return@LaunchedEffect
        streams.bringToFront(setOf(current) + NEIGHBOURS.mapNotNull { clips.getOrNull(settled + it) })
        if (neighboursFor != current) return@LaunchedEffect
        NEIGHBOURS.forEach { load(settled + it) }
    }

    // 换到新的一段：当前段升为前台并播放，前后两段装好停在第一帧，其余播放器暂停；再往后的交给预取
    LaunchedEffect(current) {
        snapshotFlow { warmUpDone }.first { it }
        if (current == null) {
            // 停在等待页上：上一段别在看不见的地方接着放。等它变成一段，这里带着那一段重来
            players.forEach(PreviewBackend::pause)
            val waiting = TimeSource.Monotonic.markNow()
            try {
                awaitCancellation()
            } finally {
                PikoLog.d("Clips", "等待页停了 ${waiting.elapsedNow().inWholeMilliseconds} ms，候补 ${latestUpcoming.size} 段")
            }
        }
        // 等待页原地变成一段时页码没变，翻页器不会再报停稳，当前位置得在这里补报
        session.moveTo(settled)
        val started = TimeSource.Monotonic.markNow()
        val preRendered = rendered[slotOf(settled)] == current
        // 两半都取最新的：一段可能恰好在这期间从候补接进翻页器，一半用旧的，它就两边都不在，会话被关掉
        val visible = latestClips
        val window = visible.subList((settled - KEPT_BEHIND).coerceIn(0, visible.size), visible.size) + latestUpcoming
        streams.sync(window.toSet())
        // 播放器装上一段、当前段与下一段，前后翻一页都是现成的。原先装当前段与后两段，上一段让出播放器，
        // 往回翻时从缓存重装，手机上实测每次 430 到 650 ms 看着缩略图等（2026-09-27）；
        // 换来的只是连着快翻两页时第二页不必现装，而那一页现装也只要三四百毫秒
        val neighbours = NEIGHBOURS.mapNotNull { clips.getOrNull(settled + it) }
        streams.bringToFront(setOf(current) + neighbours)
        // 翻走的那段若升过档，降回来：停在第一帧的段不该再以最高档往后读
        players.forEachIndexed { slot, player ->
            if (slot != slotOf(settled)) {
                player.pause()
                player.setBufferAhead(HOLD_BUFFER_SECONDS)
            }
        }
        streams.holdAllBut(current)
        if ((rememberedPositions[current] ?: 0L) <= 0L) {
            streams.boost(current, ACTIVE_INITIAL_READ_AHEAD_SECONDS, ACTIVE_INITIAL_MIN_AHEAD_BYTES)
        }
        val loading = load(settled)
        loading?.join()
        if (loaded[slotOf(settled)] != current) {
            snackbarHostState.showSnackbar(tr("无法播放此片段，已自动跳过", "Unable to play clip; skipped", "このクリップを再生できないためスキップしました", "이 클립을 재생할 수 없어 건너뛰었습니다", "無法播放此片段，已自動略過"))
            go(1)
            return@LaunchedEffect
        }
        // 翻回看过的一段，它停在哪里就从哪里接着；已经快放完了就从头再放，免得一翻回来就被自动翻走
        val slot = slotOf(settled)
        val start = startOnPlayer[slot]
        val end = start + clipLengths[slot]
        // 只看出过首帧的：刚装上的，位置读数可能还是这个播放器上一段的。放到了切片末尾的也从头来，
        // 播放器停在流尾，只按播放不会动
        val positionIsOurs = rendered[slot] == current
        val atEnd = ended[slot] == current
        val maxRemaining = maxPlayableLength(slot, current)
        if (positionIsOurs && (atEnd || (end >= start + maxRemaining && currentPlayer.positionMillis >= end - REPLAY_WITHIN_MS))) {
            ended[slot] = null
            rememberedPositions[current] = 0L
            currentPlayer.seekTo(start)
        }
        if ((rememberedPositions[current] ?: 0L) > 0L) {
            currentPlayer.setBufferAhead(BOOSTED_SECONDS)
            streams.boost(current, BOOSTED_READ_AHEAD_SECONDS, BOOSTED_MIN_AHEAD_BYTES)
        }
        // 持续同步当前已渲染段的相对播放进度，翻过多个槽位再翻回来时也能精确接上
        launch {
            snapshotFlow {
                if (rendered[slot] != current) null
                else (currentPlayer.positionMillis - startOnPlayer[slot]).coerceIn(0L, clipLengths[slot].coerceAtLeast(CLIP_LENGTH_MS))
            }.collect { pos ->
                if (pos != null && ended[slot] != current) {
                    rememberedPositions[current] = pos
                }
            }
        }
        // 翻到一段就是要看它：在上一段暂停过，不能带到这一段，看着像没播动
        paused = latestHostPaused
        if(paused) currentPlayer.pause() else currentPlayer.play()
        // 前后两段等这一段走起来再装，至多等 NEIGHBOURS_AFTER：装一段要解文件头、建解码器、解第一帧，
        // 手机上与这一段起步挤在同一刻，翻到预渲染好的一段也要顿 0.3 到 0.4 秒才走（2026-09-28）
        launch {
            withTimeoutOrNull(NEIGHBOURS_AFTER) {
                val from = currentPlayer.positionMillis
                snapshotFlow { currentPlayer.isPlaying && currentPlayer.positionMillis > from }.first { it }
            }
            neighboursFor = current
        }
        // 有人正等着这一段时读得最急，见 PikPakStreamReader.urgent：还没出画面、卡在缓冲上、刚定位过。
        // 平时各播放器往后缓冲的读都排在它之后，定位不必与它们排队
        launch {
            launch {
                // 定位后位置走过 PLAYING_EVIDENCE_MS 才算画面重新走起来；等不到也不一直急下去。
                // 先等一下再读起点：刚定位时报的位置可能还是定位前的
                snapshotFlow { seekPending }.collectLatest { pending ->
                    if (pending == 0) return@collectLatest
                    // 连着定位（按住方向键）时前一次被这一次取代，只记最后一次
                    val sought = TimeSource.Monotonic.markNow()
                    // 报了缓冲是在等网络，没报是慢在打开或解码，与完整播放器的「拖动后画面走起来」对照
                    var sawBuffering = currentPlayer.isBuffering
                    val bufferingWatch = launch { snapshotFlow { currentPlayer.isBuffering }.first { it }; sawBuffering = true }
                    val resumed = withTimeoutOrNull(SEEK_URGENT_FOR) {
                        delay(SEEK_SETTLE)
                        val from = currentPlayer.positionMillis
                        snapshotFlow { currentPlayer.isPlaying && currentPlayer.positionMillis >= from + PLAYING_EVIDENCE_MS }.first { it }
                    } != null
                    bufferingWatch.cancel()
                    // 暂停着定位的本来就不走，不算
                    if (resumed || !paused) {
                        PikoLog.d(
                            "Clips",
                            if (resumed) {
                                "定位后画面走起来 ${sought.elapsedNow().inWholeMilliseconds - PLAYING_EVIDENCE_MS} ms，" +
                                    "${if (sawBuffering) "等过缓冲" else "没报缓冲"} ${logFile(current.fileId, current.name)}"
                            } else {
                                "定位后 ${SEEK_URGENT_FOR.inWholeSeconds} 秒没走起来 ${logFile(current.fileId, current.name)}"
                            },
                        )
                    }
                    seekPending = 0
                }
            }
            snapshotFlow { rendered[slotOf(settled)] != current || currentPlayer.isBuffering || seekPending != 0 }
                .collect { waiting -> streams.setUrgent(current, waiting) }
        }
        // 升档：这一段放过 BOOST_AFTER_MS 才算真在看，播放器的缓冲与代理的预读一起放开往后取。
        // 在这之前它只读预取好的开头，带宽留给后面几段的开头；刷的时候多数段几秒内就翻走了。
        // 翻回一段已放过这么久的，立即升档
        launch {
            snapshotFlow { rendered[slotOf(settled)] == current && currentPlayer.positionMillis - start >= BOOST_AFTER_MS }.first { it }
            // bufferedPositionMillis 在转码切片上按流里的时间戳算，与播放位置不在一把尺子上，核对要问 mpv 自己
            val before = currentPlayer.bufferReport()
            currentPlayer.setBufferAhead(BOOSTED_SECONDS)
            streams.boost(current, BOOSTED_READ_AHEAD_SECONDS, BOOSTED_MIN_AHEAD_BYTES)
            delay(BOOST_CHECK_AFTER)
            PikoLog.d(
                "Clips",
                "升档 ${logFile(current.fileId, current.name)}：升档前 $before，${BOOST_CHECK_AFTER.inWholeSeconds} 秒后 ${currentPlayer.bufferReport()}",
            )
        }
        // 就绪只说明文件打开了，起点的画面未必到手；位置走起来才是看的人感到的开播
        var began = false
        launch {
            delay(NOT_STARTED_REPORT_AFTER)
            // 暂停着的不算：点「看完整」时这一段就是停着的
            if (!began && !paused) {
                PikoLog.d(
                    "Clips",
                    "第 $settled 段 $NOT_STARTED_REPORT_AFTER 没走起来 ${logFile(current.fileId, current.name)}：" +
                        "装着 ${loaded[slotOf(settled)] == current}，就绪 ${rendered[slotOf(settled)] == current}，" +
                        "播放 ${currentPlayer.isPlaying}，缓冲 ${currentPlayer.isBuffering}，位置 ${currentPlayer.positionMillis - start} ms",
                )
            }
        }
        launch {
            snapshotFlow { rendered[slotOf(settled)] == current }.first { it }
            val openedMs = started.elapsedNow().inWholeMilliseconds
            val from = currentPlayer.positionMillis
            snapshotFlow { currentPlayer.isPlaying && currentPlayer.positionMillis >= from + PLAYING_EVIDENCE_MS }.first { it }
            began = true
            onStarted()
            PikoLog.d(
                "Clips",
                "开播 ${logFile(current.fileId, current.name)}：画面走起来 ${started.elapsedNow().inWholeMilliseconds - PLAYING_EVIDENCE_MS} ms，" +
                    "就绪 $openedMs ms，" + if (preRendered) tr("翻到时已就绪", "Ready when reached", "表示時に準備済み", "도달 시 준비됨", "切換至此時已就緒") else tr("翻到时未就绪", "Not ready when reached", "表示時に未準備", "도달 시 미준비", "切換至此時尚未就緒"),
            )
            // 位置停住却没报缓冲：切片到头、解码卡住一类，转圈不会出现，只能看位置
            launch {
                var last = currentPlayer.positionMillis
                var stillSince: kotlin.time.TimeMark? = null
                while (true) {
                    delay(FREEZE_POLL)
                    val now = currentPlayer.positionMillis
                    val frozen = currentPlayer.isPlaying && !currentPlayer.isBuffering && now == last
                    last = now
                    val since = stillSince
                    if (!frozen) {
                        stillSince = null
                    } else if (since == null) {
                        stillSince = TimeSource.Monotonic.markNow()
                    } else if (since.elapsedNow() >= FREEZE_REPORT_AFTER) {
                        PikoLog.d("Clips", "画面停住 ${logFile(current.fileId, current.name)}：位置 ${now - start} ms，未报缓冲")
                        stillSince = null
                        last = -1
                    }
                }
            }
            // 开播之后的每次卡顿，翻走即停
            var stallStart: kotlin.time.TimeMark? = null
            snapshotFlow { currentPlayer.isBuffering }.collect { buffering ->
                val mark = stallStart
                if (buffering && mark == null) {
                    stallStart = TimeSource.Monotonic.markNow()
                } else if (!buffering && mark != null) {
                    stallStart = null
                    val stalledMs = mark.elapsedNow().inWholeMilliseconds
                    if (stalledMs >= SPINNER_DELAY.inWholeMilliseconds) {
                        PikoLog.d("Clips", "卡顿 ${logFile(current.fileId, current.name)}：$stalledMs ms，位置 ${currentPlayer.positionMillis - start} ms")
                    }
                }
            }
        }
        // 刚换源时 positionMillis 可能还是上一段的，只认落在这一段范围里的位置。
        // 开启自动连刷时，播完当前片段自动翻到下一条；未开启自动连刷时，按「信息流自动续播」设置顺延 30 秒或单段循环重播
        snapshotFlow {
            val length = clipLengths[slot]
            val reachedWindowEnd = rendered[slot] == current && dev.piko.shared.state.clipWindowEnded(
                currentPlayer.positionMillis, start, length,
                dev.piko.shared.data.LocalLocation.isLocal(current.fileId), STALE_POSITION_SLACK_MS,
            )
            Triple(reachedWindowEnd, length, clipStartMs[slot])
        }.collect { (reachedWindowEnd, length, videoStart) ->
            if (!reachedWindowEnd) {
                return@collect
            }
            val maxRemaining = maxPlayableLength(slot, current, videoStart)
            when {
                latestAutoAdvance -> autoStep(current)
                latestAutoExtend && ended[slot] != current && length < maxRemaining -> {
                    val nextLength = (length + CLIP_LENGTH_MS).coerceAtMost(maxRemaining)
                    clipLengths[slot] = nextLength
                    rememberedLengths[current] = nextLength
                    currentPlayer.setBufferAhead(BOOSTED_SECONDS)
                    streams.boost(current, BOOSTED_READ_AHEAD_SECONDS, BOOSTED_MIN_AHEAD_BYTES)
                    PikoLog.d("Clips", "自动续播 30s ${logFile(current.fileId, current.name)}：当前窗口 ${nextLength / 1000}s")
                }
                else -> {
                    ended[slot] = null
                    rememberedPositions[current] = 0L
                    currentPlayer.seekTo(start)
                    if (!latestPaused) currentPlayer.play()
                }
            }
        }
    }

    LaunchedEffect(pausedByHost) {
        if(pausedByHost) { paused = true; currentPlayer.pause() }
    }

    fun togglePause() {
        stopBoost()
        paused = !paused
        if (paused) currentPlayer.pause() else currentPlayer.play()
    }

    /** 长按起速，松手恢复。暂停中长按不起作用：加速地停着没有意义。 */
    fun startBoost() {
        if (paused || !currentPlayer.supportsSpeed) return
        boosting = true
        currentPlayer.setSpeed(boostSpeed)
    }

    // 定位只在当前段的范围里：段首是播放器时钟上的 startOnPlayer，见 PreparedClip
    val currentSlot = slotOf(settled)
    val currentStart = startOnPlayer[currentSlot]
    val currentLength = clipLengths[currentSlot].coerceAtLeast(1_000L)
    // 出过首帧才定位：刚装上的播放器位置读数可能还是它上一段的
    val seekable = current != null && rendered[currentSlot] == current

    /** 当前段已放了多久，从段首算起；回翻重装首帧未出时回落到离开前记住的位置，避免进度条闪回 0。 */
    fun clipPosition(): Long = when {
        current == null -> 0L
        rendered[currentSlot] == current -> (currentPlayer.positionMillis - currentStart).coerceIn(0L, currentLength)
        else -> (rememberedPositions[current] ?: 0L).coerceIn(0L, currentLength)
    }

    fun seekClip(millis: Long) {
        val page = latestSettled
        val activeClip = latestClips.getOrNull(page)
        val slot = slotOf(page)
        if (activeClip == null || rendered[slot] != activeClip) {
            PikoLog.d("Clips", "忽略定位：page=$page, clips=${latestClips.size}, active=${activeClip != null}, rendered=${rendered[slot] == activeClip}")
            return
        }
        val player = players[slot]
        // 放到流尾停住的播放器靠定位重新走起来；不清掉的话翻回来时会被当作已放完，从头再放
        ended[slot] = null
        // 先于 player.seekTo 同步置上 urgent（优先级 100）并放开预读窗口：mpv C 线程在 0.5 ms 内即发出新 HTTP Range 请求，
        // 晚于 seekTo 再经 snapshotFlow 异步置 urgent 会错失定位首块的最高优先级
        streams.setUrgent(activeClip, true)
        player.setBufferAhead(BOOSTED_SECONDS)
        streams.boost(activeClip, BOOSTED_READ_AHEAD_SECONDS, BOOSTED_MIN_AHEAD_BYTES)
        val currentClipLen = clipLengths[slot].coerceAtLeast(1_000L)
        val maxRemaining = maxPlayableLength(slot, activeClip)
        if (latestAutoExtend && !latestAutoAdvance && millis >= currentClipLen - SEEK_EXTEND_THRESHOLD_MS && currentClipLen < maxRemaining) {
            val nextLength = (currentClipLen + CLIP_LENGTH_MS).coerceAtMost(maxRemaining)
            clipLengths[slot] = nextLength
            rememberedLengths[activeClip] = nextLength
        }
        val limit = clipLengths[slot].coerceAtLeast(1_000L)
        val clampedOffset = millis.coerceIn(0L, limit)
        if (clampedOffset < limit - SEEK_EXTEND_THRESHOLD_MS && pendingAdvanceClip == activeClip) {
            pendingAdvanceClip = null
        }
        rememberedPositions[activeClip] = clampedOffset
        val targetOnPlayer = startOnPlayer[slot] + clampedOffset
        PikoLog.d("Clips", "请求定位 ${logFile(activeClip.fileId, activeClip.name)}：段内 $clampedOffset/$limit ms（播放器 $targetOnPlayer ms）")
        player.seekTo(targetOnPlayer)
        seekPending++
    }

    val latestSeekClip by rememberUpdatedState(::seekClip)

    fun seekClipBy(deltaMillis: Long) {
        val page = latestSettled
        val activeClip = latestClips.getOrNull(page) ?: return
        val slot = slotOf(page)
        val len = clipLengths[slot].coerceAtLeast(CLIP_LENGTH_MS)
        val base = if (rendered[slot] == activeClip) {
            (players[slot].positionMillis - startOnPlayer[slot]).coerceIn(0L, len)
        } else {
            (rememberedPositions[activeClip] ?: 0L).coerceIn(0L, len)
        }
        latestSeekClip(base + deltaMillis)
    }

    // 按键拦在最外层：焦点可能落在翻页器里的操作栏上，也可能落在翻页器外的顶栏按钮上，两处都要接得住。
    // 用 Preview 先于按钮拦下：焦点落在按钮上时，空格不该变成点一下那个按钮
    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp) {
                    if (event.key == heldKey) heldKey = null
                    return@onPreviewKeyEvent false
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // 按住空格的连发会来回暂停，只认第一下。桌面端的按键事件不带连发标记，按下而没松开就再来一次即是连发
                val repeat = event.key == heldKey
                heldKey = event.key
                when (event.key) {
                    Key.DirectionDown, Key.PageDown, Key.MediaNext -> go(1)
                    Key.DirectionUp, Key.PageUp, Key.MediaPrevious -> go(-1)
                    Key.DirectionLeft, Key.MediaRewind -> seekClipBy(-seekStepMillis)
                    Key.DirectionRight, Key.MediaFastForward -> seekClipBy(seekStepMillis)
                    Key.Spacebar, Key.MediaPlayPause -> if (!repeat) togglePause()
                    Key.MediaPlay -> if (!repeat && paused) togglePause()
                    Key.MediaPause, Key.MediaStop -> if (!repeat && !paused) togglePause()
                    Key.M -> if (!repeat) session.muted = !session.muted
                    Key.A -> if (!repeat) onToggleAutoAdvance()
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            // 焦点目标也挂在最外层：侧栏里与网盘页并排时，点顶栏或画面任一处，方向键都归信息流
            .pageFocusTarget(focusRequester)
            .onSizeChanged { areaHeight = it.height },
    ) {
        // 画面层，在翻页器底下：每个播放器一个常驻的画面表面，按它装着的那一段所在的页跟着翻页平移。
        // 表面原先长在页里，播放器换一页负责，表面就在旧页销毁、在新页新建，mpv 跟着拆建一次视频输出：
        // 手机上每翻一页在主线程上等一两百毫秒，挪到 mpv 自己的线程上又因为新表面要等第一帧而闪一下（2026-09-28）。
        // 翻页器的页是透明的，只画属于这一段的东西：就绪前的缩略图（盖住画面）、暂停标记、转圈与双击的星
        BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
            // 画面表面的尺寸不跟着比例变，裁切与留边交给播放器：MediaMP 0.5.0 的 D3D11 表面在上一次改尺寸
            // 还没被界面线程确认时再改一次，渲染线程会握着锁空转，界面线程随之卡死（MpvSurfaceRing 的
            // ack_retired_buffers）。区域本身在变（侧栏展开的动画、拖宽、窗口缩放）时，停稳一会儿才交给表面
            var surfaceSize by remember { mutableStateOf(DpSize(maxWidth, maxHeight)) }
            LaunchedEffect(maxWidth, maxHeight) {
                delay(SURFACE_RESIZE_SETTLE)
                surfaceSize = DpSize(maxWidth, maxHeight)
            }
            val boxAspect = constraints.maxWidth.toFloat() / constraints.maxHeight.coerceAtLeast(1)
            players.forEachIndexed { slot, player ->
                key(slot) {
                    val clip = loaded[slot]
                    // 装着的那一段在第几页：只会是当前页附近、页码取模落在这个播放器上的那一页
                    val page = clip?.let { loadedClip ->
                        (settled - 2..settled + 2).firstOrNull { it >= 0 && slotOf(it) == slot && clips.getOrNull(it) == loadedClip }
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                // 在绘制阶段走 GPU RenderNode 硬件层矩阵平移，滑动中零 Layout 重测、不触发 AndroidView 重新 layout；
                                // 完全移出视口外的播放器置透明以跳过 3 层全屏大纹理混合 Overdraw
                                val pages = if (page == null) 2f else page - pagerState.currentPage - pagerState.currentPageOffsetFraction
                                translationY = pages * size.height
                                alpha = if (abs(pages) >= 1.05f) 0f else 1f
                            },
                    ) {
                        if (clip != null) {
                            val ready = rendered[slot] == clip
                            val listed = session.listedFile(clip.fileId)
                            // 就绪之后以播放器报的为准，之前先用列目录带回的宽高，免得就绪那一下画面跳一次
                            val aspect = (if (ready) player.videoAspect else null) ?: listed?.let(::listedAspect)
                            // 比例相近就铺满，裁掉两边一点，照短视频应用；差得多才留边，垫上模糊的缩略图。
                            // 横屏排法下一律完整显示：横握手机约 2.2:1，16:9 的片子落在容差内，铺满会裁去上下近两成
                            val fills = !landscape && (aspect == null || abs(ln(aspect / boxAspect)) < FILL_TOLERANCE)
                            if (ready && !fills) ClipBackdrop(listed?.thumbnailLink, Modifier.fillMaxSize())
                            LaunchedEffect(player, fills) { player.setAspectRatio(if (fills) PlayerAspectRatio.Crop else PlayerAspectRatio.Fit) }
                        }
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            videoPreview.Surface(player, Modifier.requiredSize(surfaceSize))
                        }
                    }
                }
            }
        }
        VerticalPager(
            state = pagerState,
            // 按下标，不按段：等待页取好后原地变成一段，按段作键的话它的键跟着变，翻页器会跳去追那个键。
            // 翻页器里的段只在末尾追加，下标本来就稳
            // 前后各多组合一页：翻页途中上下一段的缩略图已经在那里
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val clip = clips.getOrNull(page)
            if (clip == null) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    MediaLoadingIndicator()
                    Text(stringResource(Res.string.text_buffering_next_clip), color = Color.White, modifier = Modifier.padding(top = 16.dp))
                }
                return@VerticalPager
            }
            val active = page == settled
            val slot = slotOf(page)
            val player = players[slot]
            val ready = rendered[slot] == clip
            val listed = session.listedFile(clip.fileId)
            val bursts = remember { mutableStateListOf<StarBurst>() }

            // 手势在 pointerInput 里只装一次，处理时要读最新的组合
            val onTap by rememberUpdatedState {
                // 沉浸时控件都藏着，点一下只是叫回控件，不暂停
                if (immersive) immersive = false else if (active) togglePause()
            }
            val onDoubleTap by rememberUpdatedState { at: Offset ->
                if (active) {
                    actions.setStarred(clip, true)
                    bursts += StarBurst(nextBurstId++, at, Random.nextFloat() * 2 * BURST_TILT - BURST_TILT)
                }
            }
            val onLongPress by rememberUpdatedState { if (active) startBoost() }
            val onRelease by rememberUpdatedState { stopBoost() }

            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    // 单击要等过了双击的间隔才认，暂停因此慢一拍，与短视频应用一致。
                    // 沉浸时不认双击，点一下当场叫回控件，不必等那一拍
                    .pointerInput(immersive) {
                        detectTapGestures(
                            onTap = { onTap() },
                            onDoubleTap = if (immersive) null else { at -> onDoubleTap(at) },
                            onLongPress = { onLongPress() },
                            onPress = {
                                tryAwaitRelease()
                                onRelease()
                            },
                        )
                    },
            ) {
                // 画面在底下的画面层里，见翻页器之前。播放器轮换着装各段，刚换上这一段时画面里还是它上一段的
                // 最后一帧，就绪之前用缩略图盖住
                if (!ready) ClipBackdrop(listed?.thumbnailLink, Modifier.fillMaxSize())

                if (active && !warmUpDone) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        MediaLoadingIndicator()
                        Text(tr("正在缓存 $warmed/$WARM_UP_CLIPS", "Buffering $warmed/$WARM_UP_CLIPS", "バッファ中 $warmed/$WARM_UP_CLIPS", "버퍼링 중 $warmed/$WARM_UP_CLIPS", "正在快取 $warmed/$WARM_UP_CLIPS"), color = Color.White, modifier = Modifier.padding(top = 16.dp))
                    }
                } else {
                    // 缓冲过一会儿才转圈：开播与换源时缓冲状态会闪一两下，每次都转就像卡了两次
                    var stalled by remember { mutableStateOf(false) }
                    val waiting = active && (!ready || player.isBuffering)
                    LaunchedEffect(waiting) {
                        stalled = false
                        if (waiting) {
                            delay(SPINNER_DELAY)
                            stalled = true
                        }
                    }
                    if (stalled) MediaLoadingIndicator(Modifier.align(Alignment.Center))
                    AnimatedVisibility(
                        visible = active && paused && !stalled,
                        enter = fadeIn(motion.fastEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = PAUSED_MARK_ENTER_SCALE),
                        exit = fadeOut(motion.defaultEffectsSpec()) + scaleOut(motion.defaultSpatialSpec(), targetScale = PAUSED_MARK_ENTER_SCALE),
                        modifier = Modifier.align(Alignment.Center),
                    ) {
                        PausedMark(compact)
                    }
                }

                StarBursts(bursts, onFinished = { bursts.remove(it) })
            }
        }

        // 控件层，在翻页器之上、不随页位移：只画当前这一段的说明、操作与进度，换段时就地淡出淡入。
        // 原先长在页里，翻页途中两段的说明与操作栏连同底部遮罩一起滑过画面，看着乱。
        // 这一层只有按钮与进度条接输入，其余空白处的点按与拖动落到底下的页上
        AnimatedVisibility(
            visible = !immersive,
            enter = fadeIn(motion.defaultEffectsSpec()),
            exit = fadeOut(motion.defaultEffectsSpec()),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(if (landscape) LANDSCAPE_BOTTOM_SCRIM_FRACTION else BOTTOM_SCRIM_FRACTION)
                        .graphicsLayer { alpha = chromeAlpha }
                        .background(Brush.verticalGradient(BottomScrim)),
                )
                Crossfade(
                    targetState = current,
                    animationSpec = motion.defaultEffectsSpec(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .graphicsLayer { alpha = chromeAlpha },
                ) { clip ->
                    // 淡出中的那一段照旧作用于它自己：按页码找回它的播放器与起点
                    val page = clips.indexOf(clip)
                    if (clip == null || page < 0) return@Crossfade
                    val slot = slotOf(page)
                    val player = players[slot]
                    val videoStart = if (loaded[slot] == clip) clipStartMs[slot] else clip.startMs
                    ClipControls(
                        clip = clip,
                        startMs = videoStart,
                        folderName = session.folderName(clip.parentId),
                        starred = actions.isStarred(clip),
                        onToggleStar = { actions.setStarred(clip) },
                        onPlayFull = {
                            val active = page == latestSettled
                            player.pause()
                            if (active) paused = true
                            val watched = if (active) (player.positionMillis - startOnPlayer[slot]).coerceAtLeast(0) else 0L
                            onPlayFull(clip.file, videoStart + watched)
                        },
                        onLocate = { currentPlayer.pause(); paused = true; onLocate(clip.file) },
                        compact = compact,
                        landscape = landscape,
                    )
                }
                if (current != null) {
                    ActiveClipProgressBar(
                        clip = current,
                        player = currentPlayer,
                        isRendered = { rendered[currentSlot] == current },
                        rememberedPosition = { rememberedPositions[current] ?: 0L },
                        currentStart = currentStart,
                        currentLength = currentLength,
                        onSeek = { latestSeekClip(it) },
                        onScrubbingChange = { scrubbing = it },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = !immersive,
            enter = fadeIn(motion.defaultEffectsSpec()),
            exit = fadeOut(motion.defaultEffectsSpec()),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Box(Modifier.fillMaxWidth()) { overlay { immersive = true } }
        }
        AnimatedVisibility(
            visible = boosting,
            enter = fadeIn(motion.fastEffectsSpec()),
            exit = fadeOut(motion.fastEffectsSpec()),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(top = if (compact) 52.dp else 68.dp),
        ) {
            BoostPill(boostSpeed)
        }
    }
}

/** 独立隔离高频变化的播放位置与缓冲读数，避免每 250ms 触发外层 ClipPager 整树重组。 */
@Composable
private fun ActiveClipProgressBar(
    clip: Clip,
    player: PreviewBackend,
    isRendered: () -> Boolean,
    rememberedPosition: () -> Long,
    currentStart: Long,
    currentLength: Long,
    onSeek: (Long) -> Unit,
    onScrubbingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pos = if (isRendered()) {
        (player.positionMillis - currentStart).coerceIn(0L, currentLength)
    } else {
        rememberedPosition().coerceIn(0L, currentLength)
    }
    val buffered = (player.bufferedPositionMillis - currentStart).coerceIn(0L, currentLength)
    ClipProgressBar(
        positionMillis = pos,
        lengthMillis = currentLength,
        bufferedPositionMillis = buffered,
        onSeek = onSeek,
        onScrubbingChange = onScrubbingChange,
        modifier = modifier,
    )
}

/** 列目录时带回的显示宽高比；没有宽高的为 null。不计旋转，只作就绪前的估计。 */
private fun listedAspect(file: FileStat): Float? {
    val width = file.params["width"]?.toFloatOrNull() ?: return null
    val height = file.params["height"]?.toFloatOrNull() ?: return null
    return if (width > 0f && height > 0f) width / height else null
}

private var nextBurstId = 0L

/** 轮换的预览播放器数：当前段与前后各一段，相邻三页按页码取模正好各占一个。 */
private const val POOL_SIZE = 3

/** 除当前段外装进播放器、停在第一帧的段，相对当前页的偏移。下一段在前，先装它。 */
private val NEIGHBOURS = listOf(1, -1)

/** 冷开时头一段画面走起来之前，只备这么多段、只取这么多段的开头，见 ClipFeedScreen 的 started。 */
private const val COLD_START_CLIPS = 3

/** 放过这么久才算真在看，升档往后取 [BOOSTED_SECONDS]，见 ClipPager 的升档。 */
private const val BOOST_AFTER_MS = 2_500L
private const val BOOSTED_SECONDS = 10
/** 代理侧预读必须显著超前于播放器的 cache-secs（10s），使并发 READ_AHEAD 大块铺路而不退化为单块阻塞读。 */
private const val ACTIVE_INITIAL_READ_AHEAD_SECONDS = 8
private const val ACTIVE_INITIAL_MIN_AHEAD_BYTES = 2L * 1024 * 1024
private const val BOOSTED_READ_AHEAD_SECONDS = 25
private const val BOOSTED_MIN_AHEAD_BYTES = 6L * 1024 * 1024

/**
 * 还没升档的段播放器往后缓冲的秒数。播放器的缓冲读都是最高档，压着它才不越过预取好的 5 秒开头：
 * 放到 3 秒升档时，缓冲到的正是 5 秒。
 */
private const val HOLD_BUFFER_SECONDS = 2

/** 当前段起步后至多等这么久再装前后两段，见 neighboursFor。 */
private val NEIGHBOURS_AFTER = 800.milliseconds

/** 定位后至多这么久算有人在等，画面走起来即止，见 seekPending。 */
private val SEEK_URGENT_FOR = 5.seconds
private val SEEK_SETTLE = 200.milliseconds

/** 升档后隔这么久记一次缓冲，看升档真的生效了没有。 */
private val BOOST_CHECK_AFTER = 3.seconds

/** 开播前先缓存好的段数。 */
// 一段就够：翻页器里只有取好的，后面没取好翻不过去，第一段放着的三十秒里后面的自然取好了
private const val WARM_UP_CLIPS = 1

/** 翻页器里当前段之后要有这么多段取好；不到就从候补里取。每段一两 MB，连着一秒一段地翻也供得上。 */
private const val READY_AHEAD = 8

/** 往前备好会话的段数，比取的多：备会话只查详情、探长度，不费流量，却是取好一段里最慢的一步。 */
private const val PREPARE_AHEAD = 12

/** 预热等这么久还没攒够就先开播，网络再差也不至于停在缓存画面上。 */
private val WARM_UP_TIMEOUT = 20.seconds

/** 翻到后这么久还没走起来的记下播放器状态。 */
private val NOT_STARTED_REPORT_AFTER = 5.seconds

/** 播放中位置不动、也没报缓冲，这么久记作画面停住；每 [FREEZE_POLL] 看一次。 */
private val FREEZE_REPORT_AFTER = 1500.milliseconds
private val FREEZE_POLL = 500.milliseconds

/** 缓冲超过这么久才转圈，也才记作一次卡顿。 */
private val SPINNER_DELAY = 300.milliseconds

/** 位置走过这么多才算真在放，开播日志从耗时里扣掉它。 */
private const val PLAYING_EVIDENCE_MS = 300L

/** 翻回的一段离终点不到这么多，就从头再放。 */
private const val REPLAY_WITHIN_MS = 5_000L

/** 超过片段终点这么多还当作本段的位置；再大就是换源前上一段残留的读数。 */
private const val STALE_POSITION_SLACK_MS = 10_000L

/** 进度条或按键快进接近当前段尾这么多以内时，顺手往后续上一段 30 秒。 */
private const val SEEK_EXTEND_THRESHOLD_MS = 2_000L

/**
 * 当前段往回留着会话的段数，再早的关掉，往回翻到时重开（转码切片的开头在磁盘上，重开快）。
 * 看过的段在内存里缓存着开头与播过的部分，升过档的还多取了十秒，一段几 MB 到十来 MB；
 * 原先往回留 25 段，会话挂到 ClipFeedSession 上不再随页面关掉之后，手机上刷一阵就把 256 MB 的堆用光（2026-09-28）。
 */
private const val KEPT_BEHIND = 2

/**
 * 画面与区域的宽高比之比落在 e 的这么多次方以内就铺满，裁掉的不到三成：竖屏视频在手机上、
 * 横屏视频在 16:10 的桌面窗口里都铺满；横屏视频放进竖的窄面板，差了两三倍，留边垫模糊背景。
 */
private const val FILL_TOLERANCE = 0.3f

/** 区域尺寸停稳这么久才交给画面表面，见 ClipFeedScreen 里设 surfaceSize 的地方。 */
private const val SURFACE_RESIZE_SETTLE = 250L

/** 底部遮罩占画面高度的比例，盖住说明与操作栏。横屏时操作栏横排、说明较矮，遮罩跟着矮些。 */
private const val BOTTOM_SCRIM_FRACTION = 0.45f
private const val LANDSCAPE_BOTTOM_SCRIM_FRACTION = 0.35f

/**
 * 区域矮于这个高度时按横屏排控件。看高度不看宽高比：竖排的操作栏连同标签约 250dp，加上顶栏与说明，
 * 不到这个高度就与顶栏挤在一起；桌面的独立窗口与侧栏够高，照竖屏排，与 isHeightCompact 同一条线。
 */
private val LandscapeBelowHeight = 480.dp

/** 暂停图标进出时的缩放起点：从大收到原大，像是按下去的。 */
private const val PAUSED_MARK_ENTER_SCALE = 1.3f

/** 双击冒出的星左右歪斜的最大角度。 */
private const val BURST_TILT = 15f
