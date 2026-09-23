package dev.piko.ui.screens.player

import android.content.res.Configuration
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import dev.piko.PikoApplication
import dev.piko.data.repository.isPlayableVideo
import dev.piko.download.DownloadStatus
import dev.piko.shared.media.ORIGINAL_QUALITY
import dev.piko.shared.media.player.PlayerScreenState
import dev.piko.ui.components.FullScreenLoading
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.delay
import java.io.File

/**
 * Android 播放器。取流策略、续播与重连在共用的 [PlayerScreenState]，播放后端是 libmpv，
 * 这里只负责布局、手势与系统胶水（亮度、音量、横竖屏、常亮）。
 *
 * 名字沿用 MediaMP 时期的入口，导航处的调用无需改动。
 */
@Composable
fun MediampVideoPlayerScreen(
    initialFileId: String,
    initialFileName: String,
    initialLocalPath: String? = null,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = PikoApplication.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val driveRepo = app.driveRepository

    // 同目录的其他视频。取一次即可：播放期间目录内容变了也不该让播放列表在脚下重排
    var siblingVideos by remember(initialFileId) { mutableStateOf<List<FileStat>>(emptyList()) }

    val backend = remember { MpvPlaybackBackend(context.applicationContext) }
    val state = remember(initialFileId) {
        PlayerScreenState(
            repository = app.mediampMediaRepository,
            backend = backend,
            scope = scope,
            initialFileId = initialFileId,
            initialFileName = initialFileName,
            initialLocalPath = initialLocalPath,
            resolveLocalPath = { fileId, hint ->
                hint?.takeIf { File(it).exists() }
                    ?: completedDownloadPath(fileId)
                    ?: siblingVideos.find { it.id == fileId }?.let { app.downloadManager.findCompletedLocalPath(it) }
            },
        )
    }
    DisposableEffect(state) {
        onDispose { state.release() }
    }
    DisposableEffect(backend) {
        onDispose { backend.release() }
    }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val orientationController = rememberOrientationController()
    val brightness = rememberWindowBrightness()
    val volume = rememberMediaVolume(context)
    val snackbarHostState = remember { SnackbarHostState() }

    var showPlaylist by remember { mutableStateOf(false) }
    LaunchedEffect(initialFileId) {
        val parentId = driveRepo.getFileDetail(initialFileId).getOrNull()?.parentId ?: return@LaunchedEffect
        siblingVideos = driveRepo.listAllFiles(parentId)
            .getOrNull()
            .orEmpty()
            .filter { it.isPlayableVideo() }
    }

    // 内存任务表 App 重启就空：同目录元数据到了之后，用磁盘再验一次，
    // 下好的片子直接从当前位置换到本地文件，不必再从云端取流
    LaunchedEffect(state.fileId, siblingVideos) {
        if (state.isLocalPlayback) return@LaunchedEffect
        val stat = siblingVideos.find { it.id == state.fileId } ?: return@LaunchedEffect
        app.downloadManager.findCompletedLocalPath(stat)?.let(state::useLocalCopy)
    }

    LaunchedEffect(state) {
        state.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var activeGesture by remember { mutableStateOf<PlayerGesture?>(null) }
    var doubleTapForward by remember { mutableStateOf<Boolean?>(null) }
    var isSpeedBoosting by remember { mutableStateOf(false) }
    // 用户选的倍速。长按加速时后端的倍速临时变成 BOOST_SPEED，松手要回到这里
    var userSpeed by remember { mutableFloatStateOf(1f) }

    val isImage = state.isImage
    val durationMillis = state.durationMillis
    val speedSupported = state.playbackSpeed != null

    fun applySpeed(value: Float) {
        userSpeed = value
        state.setSpeed(value)
    }

    LaunchedEffect(doubleTapForward) {
        if (doubleTapForward != null) {
            delay(650)
            doubleTapForward = null
        }
    }

    LaunchedEffect(controlsVisible, state.isPlaying, isLocked) {
        if (controlsVisible && state.isPlaying && !isLocked) {
            delay(CONTROLS_HIDE_DELAY_MILLIS)
            controlsVisible = false
        }
    }

    LaunchedEffect(isLandscape) {
        if (isLandscape) orientationController.hideSystemBars() else orientationController.showSystemBars()
    }

    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // 没有后台播放与媒体通知，退到后台就暂停；Surface 此时也被销毁，画面本就停了
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, state) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) state.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler {
        if (isLandscape) orientationController.setPortrait() else onBackClick()
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (isImage) {
            AsyncImage(
                model = state.mediaInfo?.currentUrl,
                contentDescription = state.title,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            MpvVideoSurface(backend, Modifier.fillMaxSize())

            PlayerGestureLayer(
                isLocked = isLocked,
                durationMillis = durationMillis,
                positionProvider = { state.positionMillis },
                brightness = brightness,
                volume = volume,
                onGestureChange = { activeGesture = it },
                onToggleControls = { controlsVisible = !controlsVisible },
                onSeekTo = state::seekTo,
                onDoubleTap = { zone ->
                    if (zone == DoubleTapZone.PlayPause) {
                        state.togglePlayPause()
                    } else {
                        val forward = zone == DoubleTapZone.Forward
                        state.seekBy(if (forward) SEEK_STEP_MILLIS else -SEEK_STEP_MILLIS)
                        doubleTapForward = forward
                    }
                },
                onSpeedBoost = { active ->
                    isSpeedBoosting = active
                    state.setSpeed(if (active) BOOST_SPEED else userSpeed)
                },
            )
        }

        if (state.isLoading) {
            FullScreenLoading()
        }

        activeGesture?.let { PlayerGestureHud(it, durationMillis) }

        doubleTapForward?.let { DoubleTapIndicator(it) }

        SpeedBoostCapsule(
            visible = isSpeedBoosting,
            isLandscape = isLandscape,
            speed = BOOST_SPEED,
        )

        // 竖屏放横屏片子时画面只占中间一条，下面整片黑边闲着。全屏入口在顶栏
        // 那排图标里太小也太远，这里给一个落在拇指位置的大目标。
        FullscreenPromptButton(
            visible = !isImage && !isLocked && !isLandscape && state.isLandscapeVideo == true,
            controlsVisible = controlsVisible,
            onClick = { orientationController.setLandscape() },
        )

        ResumeTipCapsule(
            visible = state.resumedFromMillis != null && !isLocked,
            resumedPositionMillis = state.resumedFromMillis ?: 0L,
            isLandscape = isLandscape,
            controlsVisible = controlsVisible,
            onRestart = state::restartFromBeginning,
        )

        state.errorMessage?.let { message ->
            PlaybackErrorCard(
                message = message,
                onRetry = state::retry,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (!isImage) {
            AnimatedVisibility(
                visible = controlsVisible || isLocked,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .displayCutoutPadding()
                    .padding(start = 16.dp),
            ) {
                LockToggle(
                    isLocked = isLocked,
                    onToggle = {
                        isLocked = !isLocked
                        if (isLocked) controlsVisible = false
                    },
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible && !isLocked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                PlayerTopBar(
                    title = state.title,
                    isLocalPlayback = state.isLocalPlayback,
                    aspectRatioMode = if (isImage) null else state.aspectRatio,
                    qualityOptions = if (isImage) emptyList() else state.qualityOptions,
                    currentQuality = state.currentQuality ?: ORIGINAL_QUALITY,
                    showSpeedEntry = !isImage && speedSupported,
                    showPlaylistEntry = !isImage && siblingVideos.size > 1,
                    onPlaylistClick = { showPlaylist = true },
                    onBackClick = {
                        orientationController.resetOrientation()
                        onBackClick()
                    },
                    onAspectRatioChange = state::setAspectRatio,
                    onQualityChange = state::selectQuality,
                    onSpeedClick = { showSpeedDialog = true },
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                if (!isImage) {
                    PlayerBottomBar(
                        isPlaying = state.isPlaying,
                        isLandscape = isLandscape,
                        positionMillis = state.positionMillis,
                        durationMillis = durationMillis,
                        bufferedFraction = if (durationMillis > 0L) {
                            (state.bufferedPositionMillis.toFloat() / durationMillis).coerceIn(0f, 1f)
                        } else {
                            0f
                        },
                        playbackSpeed = userSpeed,
                        speedSupported = speedSupported,
                        onPlayPause = state::togglePlayPause,
                        onSeekTo = state::seekTo,
                        onSpeedClick = { showSpeedDialog = true },
                        onToggleFullscreen = { orientationController.toggleOrientation(isLandscape) },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
        )

        if (showPlaylist) {
            PlayerPlaylistSheet(
                videos = siblingVideos,
                currentFileId = state.fileId,
                onSelect = { target ->
                    showPlaylist = false
                    state.switchTo(target.id, target.name, completedDownloadPath(target.id))
                },
                onDismiss = { showPlaylist = false },
            )
        }

        if (showSpeedDialog && speedSupported) {
            PlaybackSpeedDialog(
                speed = userSpeed,
                isLandscape = isLandscape,
                onSpeedChange = ::applySpeed,
                onDismiss = { showSpeedDialog = false },
            )
        }
    }
}

/**
 * 这个文件已下载到本地的完整副本，没有则为 null。
 *
 * 分段下载的片段不算，路径对应的文件也要还在。
 */
private fun completedDownloadPath(fileId: String): String? =
    PikoApplication.instance.downloadManager.tasks.value.values
        .find { it.fileId == fileId && it.status == DownloadStatus.COMPLETED && !it.isSegment }
        ?.destinationPath
        ?.takeIf { File(it).exists() }

private const val CONTROLS_HIDE_DELAY_MILLIS = 4_500L
private const val BOOST_SPEED = 2.0f
