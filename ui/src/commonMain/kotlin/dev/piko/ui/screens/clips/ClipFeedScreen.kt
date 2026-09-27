package dev.piko.ui.screens.clips

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.MediaLoadingIndicator
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.PreviewBackend
import dev.piko.ui.screens.player.LONG_PRESS_BOOST_SPEED
import dev.piko.ui.screens.player.PlayerTheme
import dev.piko.ui.screens.player.SEEK_STEP_MILLIS
import dev.piko.ui.screens.share.ShareDialog
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.ln
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
 * 样子照短视频应用：顶部只有范围与静音、关闭，右侧一列操作，左下是说明，底边一条细进度条，都常驻，
 * 只在拖进度条时让开。单击暂停或继续，双击收藏，长按两倍速，上下滑、滚轮或上下键翻页，
 * 左右键前进后退，空格暂停，M 静音。
 *
 * [compact] 是放在网盘页右侧的窄面板里：按钮与文字缩小，关闭交给面板自己的标题栏，这里不再显示。
 */
@Composable
fun ClipFeedScreen(
    onBackClick: () -> Unit,
    onPlayFull: (file: FileStat, startMillis: Long) -> Unit,
    onLocate: (FileStat) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val services = LocalPikoServices.current
    val session = services.clipFeedSession
    val videoPreview = LocalPikoPlatform.current.videoPreview
    val clips = session.clips
    val rootId = session.root?.id
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 在翻页器之外：翻页器要等头一段取好才出现，而取好这件事就是它做的。
    // 换了范围整个换掉：旧范围里在取的段取好了也接不进新队列，留着只占连接
    val streams = remember(rootId) { ClipStreams(services.mediaRepository, onReady = session::promote, onDead = session::drop) }
    DisposableEffect(streams) { onDispose { streams.closeAll() } }
    // 只取够用的几段：翻页器里到过的最远一段之后取好的不到 READY_AHEAD 段时，才从候补里补上差额去取。
    // 二十几段一起取，刷过去的多半轮不到，流量白花，还跟真要播的抢带宽；少取几段，每段取深一点，见 PreparedClip。
    // 备会话只调接口不费流量，往前多备 PREPARE_AHEAD 段：轮到取时会话现成，取好一段只剩下载那一两 MB，
    // 连着快翻时供得上。从最远处数而不从当前段数，往回翻时照样取，见 ClipFeedSession.furthestIndex
    LaunchedEffect(streams) {
        snapshotFlow {
            // 候补位里的也是取好的，算进去
            val ready = session.clips.size - 1 - session.furthestIndex + session.reserve.size
            session.upcoming.take((PREPARE_AHEAD - ready).coerceAtLeast(0)) to (READY_AHEAD - ready).coerceAtLeast(0)
        }.collect { (ahead, fetchCount) ->
            ahead.forEach { streams.get(it) }
            streams.ripen(ahead.take(fetchCount))
        }
    }

    val actions = remember(session) {
        ClipActions(session, services.driveRepository, services.downloadManager, scope) { message ->
            scope.launch { snackbarHostState.showSnackbar(message) }
        }
    }
    LaunchedEffect(actions) { actions.loadStars() }

    val driveStack by services.driveRepository.folderStackFlow.collectAsState()
    val scopeMenu = ClipScopeMenu(
        title = session.root?.name ?: "信息流",
        current = driveStack.lastOrNull(),
        recent = session.recentFolders,
        selectedId = rootId,
        onOpen = { scope.launch { session.loadRecentFolders() } },
        onPick = { folder -> scope.launch { session.open(folder) } },
    )
    val topBar: @Composable BoxScope.() -> Unit = {
        ClipFeedTopBar(
            scope = scopeMenu,
            muted = session.muted,
            onToggleMute = { session.muted = !session.muted },
            onClose = if (compact) null else onBackClick,
            compact = compact,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }

    // 控件照播放器的样式，固定深色
    PlayerTheme {
        Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
            when {
                videoPreview == null -> FeedMessage("此平台暂不支持信息流", null, topBar)
                clips.isEmpty() && !session.isEmpty -> {
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            MediaLoadingIndicator()
                            Text(if (session.upcoming.isEmpty()) "正在查找视频" else "正在缓存", color = Color.White, modifier = Modifier.padding(top = 16.dp))
                        }
                        topBar()
                    }
                }
                clips.isEmpty() -> FeedMessage("这里没有可播放的视频", "只挑一分钟以上、已有 720P 转码的正片", topBar)
                // 换了范围就是另一条队列，页码、播放器与各段的装载状态都不再对得上，整个重建
                else -> key(rootId) {
                    ClipPager(
                        session = session,
                        clips = clips,
                        // 候补位也是关窗时要留着会话的，与候补一起算进窗口
                        upcoming = session.reserve + session.upcoming,
                        streams = streams,
                        actions = actions,
                        snackbarHostState = snackbarHostState,
                        compact = compact,
                        onPlayFull = onPlayFull,
                        onLocate = onLocate,
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

    actions.sharing?.let { file ->
        ShareDialog(
            files = listOf(file),
            onDismiss = actions::dismissShare,
            onCopied = { scope.launch { snackbarHostState.showSnackbar("已复制分享链接") } },
        )
    }
}

@Composable
private fun FeedMessage(title: String, description: String?, topBar: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        PikoEmptyState(title = title, description = description)
        topBar()
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
    onPlayFull: (FileStat, Long) -> Unit,
    onLocate: (FileStat) -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
) {
    val videoPreview = LocalPikoPlatform.current.videoPreview ?: return
    val scope = rememberCoroutineScope()
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
    val loadJobs = remember { arrayOfNulls<Job>(POOL_SIZE) }
    val focusRequester = remember { FocusRequester() }
    var paused by remember { mutableStateOf(false) }
    // 长按期间两倍速
    var boosting by remember { mutableStateOf(false) }
    // 拖进度条时说明与操作栏让开，看得到画面
    var scrubbing by remember { mutableStateOf(false) }
    val motion = MaterialTheme.motionScheme
    val chromeAlpha by animateFloatAsState(if (scrubbing) 0f else 1f, motion.defaultEffectsSpec())
    val muted = session.muted
    val latestMuted by rememberUpdatedState(muted)
    // 开播前先缓存好的段数；到 WARM_UP_CLIPS 或超时才开播
    var warmed by remember { mutableIntStateOf(0) }
    var warmUpDone by remember { mutableStateOf(false) }

    val settled = pagerState.settledPage.coerceIn(0, pageCount - 1)
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

    // 自动翻页等着下一段取好
    var autoStepPending by remember { mutableStateOf(false) }

    /**
     * 放满一段后翻到下一段。翻页器里只有取好的，下一段还没取好就让这一段接着放，取好了再翻，
     * 不翻过去对着黑屏。
     */
    fun autoStep() {
        autoStepPending = true
    }
    LaunchedEffect(autoStepPending) {
        if (!autoStepPending) return@LaunchedEffect
        val target = pagerState.currentPage + 1
        snapshotFlow { latestClips.size > target }.first { it }
        autoStepPending = false
        go(1)
    }

    /** 把第 [page] 段装进它的播放器，停在片段起点的第一帧。已经装着这一段就不动，停在哪里算哪里。 */
    fun load(page: Int): Job? {
        val clip = clips.getOrNull(page) ?: return null
        val slot = slotOf(page)
        if (loaded[slot] == clip) return loadJobs[slot]
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
            try {
                players[slot].open(PlaybackTarget.Url(url), startMillis = prepared.startOnPlayer, playWhenReady = false)
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
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    // 分享框关掉后焦点不会自己回来，按键就无处可去
    LaunchedEffect(actions.sharing == null) { if (actions.sharing == null) runCatching { focusRequester.requestFocus() } }
    LaunchedEffect(pagerState) { snapshotFlow { pagerState.settledPage }.collect(session::moveTo) }
    LaunchedEffect(muted) { players.forEach { it.setVolume(if (muted) 0f else 1f) } }
    // 手指还按着就翻走了，松手的事件落不到这一页
    LaunchedEffect(settled) { stopBoost() }

    // 先攒几段取好的再开播：一上来就刷的头几段最容易赶上还没取好的
    LaunchedEffect(Unit) {
        withTimeoutOrNull(WARM_UP_TIMEOUT) {
            snapshotFlow { (latestClips.size - pagerState.settledPage).coerceAtLeast(0) }
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
                        if (slot == slotOf(pagerState.settledPage)) autoStep()
                    }
                    is PlaybackBackendEvent.Error -> if (slot == slotOf(pagerState.settledPage)) go(1)
                }
            }
        }
    }

    // 停在末尾时后面接上了新取好的段，也装进播放器预渲染，不等翻过去
    LaunchedEffect(settled, clips.size, warmUpDone) {
        if (!warmUpDone || current == null) return@LaunchedEffect
        streams.bringToFront(setOf(current) + NEIGHBOURS.mapNotNull { clips.getOrNull(settled + it) })
        NEIGHBOURS.forEach { load(settled + it) }
    }

    // 换到新的一段：当前段升为前台并播放，前后两段装好停在第一帧，其余播放器暂停；再往后的交给预取
    LaunchedEffect(current) {
        snapshotFlow { warmUpDone }.first { it }
        if (current == null) {
            // 停在等待页上：上一段别在看不见的地方接着放。等它变成一段，这里带着那一段重来
            players.forEach(PreviewBackend::pause)
            val waiting = TimeSource.Monotonic.markNow()
            // 候补位里有现成的就接上。没有的话，下一段取好时翻页器后面一段都没有，会直接接上，不进候补位
            session.stopgap()
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
        val window = visible.subList((settled - QUEUE_AROUND).coerceIn(0, visible.size), visible.size) + latestUpcoming
        streams.sync(window.toSet())
        // 播放器装上一段、当前段与下一段，前后翻一页都是现成的。原先装当前段与后两段，上一段让出播放器，
        // 往回翻时从缓存重装，手机上实测每次 430 到 650 ms 看着缩略图等（2026-09-27）；
        // 换来的只是连着快翻两页时第二页不必现装，而那一页现装也只要三四百毫秒
        val neighbours = NEIGHBOURS.mapNotNull { clips.getOrNull(settled + it) }
        streams.bringToFront(setOf(current) + neighbours)
        players.forEachIndexed { slot, player -> if (slot != slotOf(settled)) player.pause() }
        val loading = load(settled)
        NEIGHBOURS.forEach { load(settled + it) }
        loading?.join()
        if (loaded[slotOf(settled)] != current) {
            snackbarHostState.showSnackbar("这一段打不开，已跳过")
            go(1)
            return@LaunchedEffect
        }
        // 翻回看过的一段，它停在哪里就从哪里接着；已经快放完了就从头再放，免得一翻回来就被自动翻走
        val start = startOnPlayer[slotOf(settled)]
        val end = start + CLIP_LENGTH_MS
        // 只看出过首帧的：刚装上的，位置读数可能还是这个播放器上一段的。放到了切片末尾的也从头来，
        // 播放器停在流尾，只按播放不会动
        val positionIsOurs = rendered[slotOf(settled)] == current
        val atEnd = ended[slotOf(settled)] == current
        if (positionIsOurs && (atEnd || currentPlayer.positionMillis >= end - REPLAY_WITHIN_MS)) {
            ended[slotOf(settled)] = null
            currentPlayer.seekTo(start)
        }
        // 翻到一段就是要看它：在上一段暂停过，不能带到这一段，看着像没播动
        paused = false
        currentPlayer.play()
        // 就绪只说明文件打开了，起点的画面未必到手；位置走起来才是看的人感到的开播
        var began = false
        launch {
            delay(NOT_STARTED_REPORT_AFTER)
            if (!began) {
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
            PikoLog.d(
                "Clips",
                "开播 ${logFile(current.fileId, current.name)}：画面走起来 ${started.elapsedNow().inWholeMilliseconds - PLAYING_EVIDENCE_MS} ms，" +
                    "就绪 $openedMs ms，" + if (preRendered) "翻到时已就绪" else "翻到时未就绪",
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
        // 一直看着而不是只等一次：等下一段取好期间拖回段内，就不该再被带走
        snapshotFlow { currentPlayer.positionMillis in end..end + STALE_POSITION_SLACK_MS }.collect { atEnd ->
            if (atEnd) autoStep() else autoStepPending = false
        }
    }

    fun togglePause() {
        stopBoost()
        paused = !paused
        if (paused) currentPlayer.pause() else currentPlayer.play()
    }

    /** 长按起两倍速，松手恢复。暂停中长按不起作用：两倍速地停着没有意义。 */
    fun startBoost() {
        if (paused || !currentPlayer.supportsSpeed) return
        boosting = true
        currentPlayer.setSpeed(LONG_PRESS_BOOST_SPEED)
    }

    // 定位只在当前段的范围里：段首是播放器时钟上的 startOnPlayer，见 PreparedClip
    val currentStart = startOnPlayer[slotOf(settled)]
    // 出过首帧才定位：刚装上的播放器位置读数可能还是它上一段的
    val seekable = current != null && rendered[slotOf(settled)] == current

    /** 当前段已放了多久，从段首算起。 */
    fun clipPosition(): Long = (currentPlayer.positionMillis - currentStart).coerceIn(0L, CLIP_LENGTH_MS)

    fun seekClip(millis: Long) {
        if (!seekable) return
        // 放到流尾停住的播放器靠定位重新走起来；不清掉的话翻回来时会被当作已放完，从头再放
        ended[slotOf(settled)] = null
        currentPlayer.seekTo(currentStart + millis.coerceIn(0L, CLIP_LENGTH_MS))
    }

    fun seekClipBy(deltaMillis: Long) = seekClip(clipPosition() + deltaMillis)

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
                    Key.DirectionDown, Key.PageDown -> go(1)
                    Key.DirectionUp, Key.PageUp -> go(-1)
                    Key.DirectionLeft -> seekClipBy(-SEEK_STEP_MILLIS)
                    Key.DirectionRight -> seekClipBy(SEEK_STEP_MILLIS)
                    Key.Spacebar -> if (!repeat) togglePause()
                    Key.M -> if (!repeat) session.muted = !session.muted
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        VerticalPager(
            state = pagerState,
            // 按下标，不按段：等待页取好后原地变成一段，按段作键的话它的键跟着变，翻页器会跳去追那个键。
            // 翻页器里的段只在末尾追加，下标本来就稳
            // 前后各多组合一页：预渲染的上下两段要挂着画面表面才出第一帧，翻过去时它已经在那里
            beyondViewportPageCount = 1,
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable(),
        ) { page ->
            val clip = clips.getOrNull(page)
            if (clip == null) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    MediaLoadingIndicator()
                    Text("正在缓存下一段", color = Color.White, modifier = Modifier.padding(top = 16.dp))
                }
                return@VerticalPager
            }
            val active = page == settled
            val slot = slotOf(page)
            val player = players[slot]
            val start = startOnPlayer[slot]
            val videoStart = if (loaded[slot] == clip) clipStartMs[slot] else clip.startMs
            val ready = rendered[slot] == clip
            val listed = session.listedFile(clip.fileId)
            val bursts = remember { mutableStateListOf<StarBurst>() }

            // 手势在 pointerInput 里只装一次，处理时要读最新的组合
            val onTap by rememberUpdatedState { if (active) togglePause() }
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
                    // 单击要等过了双击的间隔才认，暂停因此慢一拍，与短视频应用一致
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onTap() },
                            onDoubleTap = { onDoubleTap(it) },
                            onLongPress = { onLongPress() },
                            onPress = {
                                tryAwaitRelease()
                                onRelease()
                            },
                        )
                    },
            ) {
                // 就绪之后以播放器报的为准，之前先用列目录带回的宽高，免得就绪那一下画面跳一次
                val aspect = (if (ready) player.videoAspect else null) ?: listed?.let(::listedAspect)
                val boxAspect = constraints.maxWidth.toFloat() / constraints.maxHeight.coerceAtLeast(1)
                val fills = aspect == null || abs(ln(aspect / boxAspect)) < FILL_TOLERANCE
                val thumbnail = listed?.thumbnailLink

                if (ready && !fills) ClipBackdrop(thumbnail, Modifier.fillMaxSize())
                // 比例相近就铺满，裁掉两边一点，照短视频应用；差得多才留边，垫上模糊的缩略图
                LaunchedEffect(player, fills) { player.setAspectRatio(if (fills) PlayerAspectRatio.Crop else PlayerAspectRatio.Fit) }
                // 画面表面的尺寸不跟着比例变，裁切与留边交给播放器：MediaMP 0.5.0 的 D3D11 表面在上一次改尺寸
                // 还没被界面线程确认时再改一次，渲染线程会握着锁空转，界面线程随之卡死（MpvSurfaceRing 的
                // ack_retired_buffers）。区域本身在变（侧栏展开的动画、拖宽、窗口缩放）时，停稳一会儿才交给表面
                var surfaceSize by remember { mutableStateOf(DpSize(maxWidth, maxHeight)) }
                LaunchedEffect(maxWidth, maxHeight) {
                    delay(SURFACE_RESIZE_SETTLE)
                    surfaceSize = DpSize(maxWidth, maxHeight)
                }
                if (loaded[slot] == clip) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        videoPreview.Surface(player, Modifier.requiredSize(surfaceSize))
                    }
                }
                // 播放器轮换着装各段，刚换上这一段时画面里还是它上一段的最后一帧，就绪之前盖住。
                // 表面本身不撤：预渲染要挂着它才解得出第一帧
                if (!ready) ClipBackdrop(thumbnail, Modifier.fillMaxSize())

                if (active && !warmUpDone) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        MediaLoadingIndicator()
                        Text("正在缓存 $warmed/$WARM_UP_CLIPS", color = Color.White, modifier = Modifier.padding(top = 16.dp))
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

                // 说明与操作栏随页一起滑动，翻页途中看得到下一段的名字；进度只有当前段有
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(BOTTOM_SCRIM_FRACTION)
                        .graphicsLayer { alpha = chromeAlpha }
                        .background(Brush.verticalGradient(BottomScrim)),
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                        .padding(
                            start = if (compact) 12.dp else 16.dp,
                            end = if (compact) 6.dp else 10.dp,
                            bottom = if (compact) 18.dp else 24.dp,
                        )
                        .graphicsLayer { alpha = chromeAlpha },
                    verticalAlignment = Alignment.Bottom,
                ) {
                    ClipCaption(
                        clip = clip,
                        startMs = videoStart,
                        folderName = session.folderName(clip.parentId),
                        compact = compact,
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                    )
                    ClipActionRail(
                        starred = actions.isStarred(clip),
                        onToggleStar = { actions.setStarred(clip) },
                        onPlayFull = {
                            player.pause()
                            if (active) paused = true
                            val watched = if (active) (player.positionMillis - start).coerceAtLeast(0) else 0L
                            onPlayFull(clip.file, videoStart + watched)
                        },
                        onLocate = { onLocate(clip.file) },
                        onShare = { actions.share(clip) },
                        onDownload = { actions.download(clip) },
                        compact = compact,
                    )
                }
                if (active) {
                    ClipProgressBar(
                        positionMillis = (player.positionMillis - start).coerceIn(0L, CLIP_LENGTH_MS),
                        lengthMillis = CLIP_LENGTH_MS,
                        bufferedPositionMillis = (player.bufferedPositionMillis - start).coerceIn(0L, CLIP_LENGTH_MS),
                        onSeek = ::seekClip,
                        onScrubbingChange = { scrubbing = it },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
                    )
                }
            }
        }

        overlay()
        AnimatedVisibility(
            visible = boosting,
            enter = fadeIn(motion.fastEffectsSpec()),
            exit = fadeOut(motion.fastEffectsSpec()),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(top = if (compact) 52.dp else 68.dp),
        ) {
            BoostPill()
        }
    }
}

/** 列目录时带回的显示宽高比；没有宽高的为 null。不计旋转，只作就绪前的估计。 */
private fun listedAspect(file: FileStat): Float? {
    val width = file.params["width"]?.toFloatOrNull() ?: return null
    val height = file.params["height"]?.toFloatOrNull() ?: return null
    return if (width > 0f && height > 0f) width / height else null
}

private var nextBurstId = 0L

/**
 * 队列里各段的代理会话与预取。取流慢：查详情、取直链、连上 CDN、读到容器头与起点附近的数据，
 * 前后要好几秒，所以队列里的每一段都提前备好并预取，轮到播放器装它时读缓存；出了队列的关掉。
 *
 * 候补里的段各自备会话、预取，取好的交给 [onReady] 接进翻页器，取不好的交给 [onDead] 扔掉，见 [ripen]。
 * 翻页器里因此只有取好的段，翻到哪一段都有得放。
 *
 * 每段各自一个协程，不挂在会随翻页取消的任务上：连着快翻时每翻一页任务就重来，挂在上面的永远等不到取完。
 * 备会话要查详情，同时至多 [PREPARE_LANES] 个，几十段一起查容易被限流。
 * 空闲的会话不占连接，只占内存：每段的 reader 缓存着预取的与播过的部分。
 *
 * 用自己的作用域，不用页面的：离开页面时要关掉全部会话，而页面的作用域那时已经取消，
 * 挂在它上面的关闭根本不会执行。主线程上的作用域，映射只在主线程上改，不必加锁。
 */
private class ClipStreams(
    private val repository: PikoMediaRepository,
    private val onReady: (Clip) -> Unit,
    private val onDead: (Clip) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val streams = mutableMapOf<Clip, Deferred<PreparedClip?>>()
    private val ripening = mutableSetOf<Clip>()
    private var foreground: Set<Clip> = emptySet()
    private val preparing = Semaphore(PREPARE_LANES)

    /**
     * 备好的会话一律以后台建起，装进播放器的由 [bringToFront] 升为前台。来源见 PikoMediaRepository.prepareClip。
     *
     * [forPlayer] 是播放器马上要装的，不跟候补排队备会话：接着上次看的那一段要现备，排在候补的二十几段后面
     * 实测等了七八秒。这种至多三段，不至于招来限流。
     */
    fun get(clip: Clip, forPlayer: Boolean = false): Deferred<PreparedClip?> =
        streams.getOrPut(clip) {
            val role = if (clip in foreground) StreamRole.FOREGROUND else StreamRole.BACKGROUND
            scope.async {
                val started = TimeSource.Monotonic.markNow()
                suspend fun prepare() = repository.prepareClip(clip.fileId, clip.startMs, clip.videoDurationMs, role).getOrNull()
                val prepared = (if (forPlayer) prepare() else preparing.withPermit { prepare() }) ?: return@async null
                prepared.limitReadAhead()
                PikoLog.d(
                    "Clips",
                    "备好会话 ${logFile(clip.fileId, clip.name)}（${if (prepared.fromDisk) "磁盘切片" else if (prepared.sliced) "转码切片" else "原画"}，" +
                        "${(prepared.streamBytes ?: 0) / 1024} KiB）：${started.elapsedNow().inWholeMilliseconds} ms，$role",
                )
                prepared
            }
        }

    /**
     * 让候补里还没开始的段各自去取：备会话、预取开头，取好了交给 [onReady]。
     *
     * 预取以前台身份发出，会话本身仍是后台：以后台身份，有段在放时每段只有两路在途，实测取好一段要五六秒，
     * 连着翻就供不上。前台预取排在正在放的段之后，同一档里先提出的先取完（SDK 按需求先后排），
     * 所以按队列顺序调用，最近要翻到的最先好，不必再自己限同时取几段。
     * 取不到流的交给 [onDead]：会话打不开，或者 SDK 换过主机、重试过仍取不下来。
     */
    fun ripen(upcoming: List<Clip>) {
        for (clip in upcoming) {
            if (!ripening.add(clip)) continue
            scope.launch {
                val prepared = get(clip).await()
                val fetched = prepared != null && fetchOnce(clip, prepared)
                ripening -= clip
                if (fetched) {
                    onReady(clip)
                } else {
                    PikoLog.d("Clips", "取不到 ${logFile(clip.fileId, clip.name)}，扔掉")
                    streams.remove(clip)?.let(::close)
                    onDead(clip)
                }
            }
        }
    }

    private suspend fun fetchOnce(clip: Clip, prepared: PreparedClip): Boolean {
        val started = TimeSource.Monotonic.markNow()
        val fetched = try {
            withTimeoutOrNull(RIPEN_TIMEOUT) { withContext(Dispatchers.Default) { prepared.prefetch(StreamRole.FOREGROUND) } } != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PikoLog.w("Clips", "预取 ${logFile(clip.fileId, clip.name)} 失败", e)
            false
        }
        if (fetched) PikoLog.d("Clips", "取好 ${logFile(clip.fileId, clip.name)}：预取 ${started.elapsedNow().inWholeMilliseconds} ms")
        return fetched
    }

    /**
     * 装在播放器里的几段升为前台，其余降为后台。都不丢缓存。
     *
     * 不只当前段：下一段要在翻过去之前预渲染好第一帧，mpv 为此要依次读文件头、文件尾的索引与起点，
     * 每一处都是一次新请求。以后台身份，它们与远处的预取同排在最后，只有两路在途，
     * 实测一处要等 0.6 到 1.5 秒，几处串起来就赶不上翻页。
     */
    fun bringToFront(clips: Set<Clip>) {
        val demoted = foreground - clips
        foreground = clips
        for ((clip, role) in demoted.map { it to StreamRole.BACKGROUND } + clips.map { it to StreamRole.FOREGROUND }) {
            streams[clip]?.let { stream -> scope.launch { runCatching { stream.await() }.getOrNull()?.role = role } }
        }
    }

    /** 翻出 [window] 的段关掉会话；看过的在窗口里保留缓存，不再为它发请求。 */
    fun sync(window: Set<Clip>) {
        (streams.keys - window).forEach { clip -> close(streams.remove(clip)!!) }
    }

    fun closeAll() {
        scope.coroutineContext[Job]?.cancelChildren()
        streams.values.forEach(::close)
        streams.clear()
        ripening.clear()
    }

    // 还没备好的先取消；取消前刚好备好的，照样关掉
    private fun close(stream: Deferred<PreparedClip?>) {
        stream.cancel()
        scope.launch { runCatching { stream.await() }.getOrNull()?.close() }
    }
}

/** 轮换的预览播放器数：当前段与前后各一段，相邻三页按页码取模正好各占一个。 */
private const val POOL_SIZE = 3

/** 除当前段外装进播放器、停在第一帧的段，相对当前页的偏移。下一段在前，先装它。 */
private val NEIGHBOURS = listOf(1, -1)

/** 开播前先缓存好的段数。 */
// 一段就够：翻页器里只有取好的，后面没取好翻不过去，第一段放着的三十秒里后面的自然取好了
private const val WARM_UP_CLIPS = 1

/** 翻页器里当前段之后要有这么多段取好；不到就从候补里取。每段一两 MB，连着一秒一段地翻也供得上。 */
private const val READY_AHEAD = 8

/** 往前备好会话的段数，比取的多：备会话只查详情、探长度，不费流量，却是取好一段里最慢的一步。 */
private const val PREPARE_AHEAD = 12

/**
 * 候补的一段这么久还没取完开头，当它取不到，见 ClipStreams.ripen。只兜住一直在慢慢出字节、不报错的主机；
 * 坏主机与断流 SDK 自己会换、会报错。计时从请求起，而请求一齐发出、按先后取，排在第八段的要等前七段，
 * 在 1 MB/s 的线路上约 16 秒，所以留得宽。
 */
private val RIPEN_TIMEOUT = 60.seconds

/** 同时备会话的段数。每段要查一次详情，再探一次转码流的长度。 */
private const val PREPARE_LANES = 4

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

/** 队列：当前段前后各这么多段，与 ClipFeedSession 存盘的窗口一致。在队列里的都预取，出队即关掉会话。 */
private const val QUEUE_AROUND = 25

/**
 * 画面与区域的宽高比之比落在 e 的这么多次方以内就铺满，裁掉的不到三成：竖屏视频在手机上、
 * 横屏视频在 16:10 的桌面窗口里都铺满；横屏视频放进竖的窄面板，差了两三倍，留边垫模糊背景。
 */
private const val FILL_TOLERANCE = 0.3f

/** 区域尺寸停稳这么久才交给画面表面，见 ClipFeedScreen 里设 surfaceSize 的地方。 */
private const val SURFACE_RESIZE_SETTLE = 250L

/** 底部遮罩占画面高度的比例，盖住说明与操作栏。 */
private const val BOTTOM_SCRIM_FRACTION = 0.45f

/** 暂停图标进出时的缩放起点：从大收到原大，像是按下去的。 */
private const val PAUSED_MARK_ENTER_SCALE = 1.3f

/** 双击冒出的星左右歪斜的最大角度。 */
private const val BURST_TILT = 15f
