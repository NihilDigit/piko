package dev.piko.ui.screens.player

import android.content.res.Configuration
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.piko.PikoApplication
import dev.piko.download.DownloadStatus
import dev.piko.shared.media.player.PlayerScreenState
import io.github.nihildigit.pikpak.FileStat
import java.io.File

/**
 * Android 播放器。取流策略、续播与重连在共用的 [PlayerScreenState]，播放后端是 libmpv，
 * 控件是 ui 模块里两端共用的 [MobilePlayerControls]；这里只负责画面表面与系统胶水
 * （横竖屏、常亮、生命周期、窗口亮度与系统媒体音量）。
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
                // SAF 目录里的下载是 content: URI，File 判断不了存在与否，交给后端去打开
                hint?.takeIf { it.startsWith("content:") || File(it).exists() }
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
    val mediaVolume = rememberMediaVolume()
    val snackbarHostState = remember { SnackbarHostState() }
    val isSpoilerBlurEnabled by app.sessionManager.spoilerBlurFlow.collectAsStateWithLifecycle(initialValue = true)

    LaunchedEffect(initialFileId) {
        siblingVideos = driveRepo.siblingVideos(initialFileId)
        state.playlist = playlistOf(siblingVideos, app.sessionManager)
    }

    // 内存任务表 App 重启就空：同目录元数据到了之后，用磁盘再验一次，
    // 下好的片子直接从当前位置换到本地文件，不必再从云端取流
    LaunchedEffect(state.fileId, siblingVideos) {
        if (state.isLocalPlayback) return@LaunchedEffect
        val stat = siblingVideos.find { it.id == state.fileId } ?: return@LaunchedEffect
        app.downloadManager.findCompletedLocalPath(stat)?.let(state::useLocalCopy)
    }

    LaunchedEffect(state) {
        state.messages.collect { snackbarHostState.showSnackbar(it, withDismissAction = true) }
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

    val leave = {
        orientationController.resetOrientation()
        onBackClick()
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (state.isImage) {
            AsyncImage(
                model = state.mediaInfo?.currentUrl,
                contentDescription = state.title,
                modifier = Modifier.fillMaxSize(),
            )
            PlayerTheme {
                PlayerTopBar(
                    title = state.title,
                    episodeLabel = null,
                    isLocalPlayback = state.isLocalPlayback,
                    onBackClick = leave,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        } else {
            MpvVideoSurface(backend, Modifier.fillMaxSize())

            MobilePlayerControls(
                title = state.title,
                isLocalPlayback = state.isLocalPlayback,
                isPlaying = state.isPlaying,
                isLoading = state.isLoading,
                positionMillis = state.positionMillis,
                durationMillis = state.durationMillis,
                bufferedPositionMillis = state.bufferedPositionMillis,
                playbackSpeed = state.playbackSpeed,
                aspectRatio = state.aspectRatio,
                qualityOptions = state.qualityOptions,
                currentQuality = state.currentQuality,
                errorMessage = state.errorMessage,
                resumedFromMillis = state.resumedFromMillis,
                onPlayPause = state::togglePlayPause,
                onSeek = state::seekTo,
                onSpeedChange = state::setSpeed,
                onAspectRatioChange = state::setAspectRatio,
                onQualityChange = state::selectQuality,
                onRetry = state::retry,
                onRestartFromBeginning = state::restartFromBeginning,
                onBack = leave,
                onToggleFullscreen = { orientationController.toggleOrientation(isLandscape) },
                isFullscreen = isLandscape,
                isLandscapeVideo = state.isLandscapeVideo,
                playlist = state.playlist,
                currentFileId = state.fileId,
                hasPrevious = state.previousEntry != null,
                hasNext = state.nextEntry != null,
                onPrevious = state::playPrevious,
                onNext = state::playNext,
                onSelectEntry = state::playEntry,
                hideEpisodeThumbnails = isSpoilerBlurEnabled,
                brightness = brightness,
                volume = mediaVolume,
                snackbarHost = { SnackbarHost(snackbarHostState) },
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
