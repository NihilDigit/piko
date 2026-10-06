package dev.piko.ui.screens.player

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import dev.piko.shared.media.player.isPlayerSubtitleName
import dev.piko.ui.LocalPikoServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import dev.piko.data.auth.PlayerGestureDefaults
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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
    initialStartMillis: Long? = null,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = PikoApplication.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val driveRepo = app.driveRepository

    var siblingVideos by remember(initialFileId) { mutableStateOf<List<FileStat>>(emptyList()) }

    val preferences = LocalPikoServices.current.preferences
    val backend = remember {
        // 启动时已读过偏好，DataStore 有缓存，first() 不等磁盘
        val hardwareDecoding = runBlocking { preferences.hardwareDecodingFlow.first() }
        MpvPlaybackBackend(context.applicationContext, hardwareDecoding = hardwareDecoding)
    }
    val state = remember(initialFileId) {
        PlayerScreenState(
            repository = app.mediampMediaRepository,
            backend = backend,
            scope = scope,
            initialFileId = initialFileId,
            initialFileName = initialFileName,
            initialLocalPath = initialLocalPath,
            initialStartMillis = initialStartMillis,
            resolveLocalPath = { fileId, hint ->
                // SAF 目录里的下载是 content: URI，File 判断不了存在与否，交给后端去打开
                hint?.takeIf { it.startsWith("content:") || File(it).exists() }
                    ?: completedDownloadPath(fileId)
                    ?: siblingVideos.find { it.id == fileId }?.let { app.downloadManager.findCompletedLocalPath(it) }
            },
            defaultMaxHeight = { preferences.playbackMaxHeightFlow.first() },
        )
    }
    DisposableEffect(state) {
        onDispose { state.release() }
    }
    DisposableEffect(backend) {
        onDispose { backend.release() }
    }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // 全屏原先就等于横屏。竖着的片子（竖拍的，或转过 90 度的横片）全屏时屏幕是竖的，横竖推不出是否全屏，
    // 另记一个；不记的话返回键把竖着的全屏当成普通页面，直接退出播放器
    var portraitFullscreen by rememberSaveable { mutableStateOf(false) }
    val isFullscreen = isLandscape || portraitFullscreen
    val orientationController = rememberOrientationController()

    // 全屏时屏幕方向跟着片子走：横片横屏，竖片竖屏。与桌面端转画面时窗口横竖对调是同一件事
    fun enterFullscreen(landscapeVideo: Boolean) {
        portraitFullscreen = !landscapeVideo
        if (landscapeVideo) orientationController.setLandscape() else orientationController.setPortraitFullscreen()
    }

    fun exitFullscreen() {
        portraitFullscreen = false
        orientationController.setPortrait()
    }
    val brightness = rememberWindowBrightness()
    val mediaVolume = rememberMediaVolume()
    val snackbarHostState = remember { SnackbarHostState() }
    val isSpoilerBlurEnabled by app.sessionManager.spoilerBlurFlow.collectAsStateWithLifecycle(initialValue = true)
    val seekStepSeconds by preferences.playerSeekStepSecondsFlow
        .collectAsStateWithLifecycle(initialValue = PlayerGestureDefaults.SEEK_STEP_SECONDS)
    val longPressSpeed by preferences.playerBoostSpeedFlow
        .collectAsStateWithLifecycle(initialValue = PlayerGestureDefaults.BOOST_SPEED)

    LaunchedEffect(initialFileId) {
        val siblings = driveRepo.siblingMedia(initialFileId)
        siblingVideos = siblings.videos
        state.playlist = playlistOf(siblingVideos, app.sessionManager, siblings.subtitles)
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

    // 弹出选择框时正在放的视频。回调只带 URI，选文件与复制期间可能已连播到下一集；
    // 选择框开着时 Activity 可能被重建，所以存进 rememberSaveable
    var subtitlePickedFor by rememberSaveable { mutableStateOf("") }
    val subtitleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val videoFileId = subtitlePickedFor
        scope.launch {
            when (val copied = withContext(Dispatchers.IO) { copySubtitleToCache(context, uri) }) {
                is LocalSubtitle.Copied -> state.addLocalSubtitle(videoFileId, copied.file.absolutePath, copied.file.name)
                LocalSubtitle.Unsupported -> snackbarHostState.showSnackbar("不支持这种字幕格式", withDismissAction = true)
                LocalSubtitle.Unreadable -> snackbarHostState.showSnackbar("无法读取字幕文件", withDismissAction = true)
            }
        }
    }
    // 字幕的 MIME 类型各家登记得不一，按类型过滤会把 .ass 这类藏起来，只能全列、选完按扩展名判断
    val pickLocalSubtitle = {
        subtitlePickedFor = state.fileId
        subtitleLauncher.launch(arrayOf("*/*"))
    }

    LaunchedEffect(isFullscreen) {
        if (isFullscreen) orientationController.hideSystemBars() else orientationController.showSystemBars()
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
        if (isFullscreen) exitFullscreen() else onBackClick()
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
                // 比例还没拿到时按横片进，与原先一律横屏全屏相同
                onToggleFullscreen = { if (isFullscreen) exitFullscreen() else enterFullscreen(state.isLandscapeVideo != false) },
                isFullscreen = isFullscreen,
                isLandscapeVideo = state.isLandscapeVideo,
                playlist = state.playlist,
                currentFileId = state.fileId,
                hasPrevious = state.previousEntry != null,
                hasNext = state.nextEntry != null,
                onPrevious = state::playPrevious,
                onNext = state::playNext,
                onSelectEntry = state::playEntry,
                hideEpisodeThumbnails = isSpoilerBlurEnabled,
                seekStepSeconds = seekStepSeconds,
                onSeekStepChange = { scope.launch { preferences.setPlayerSeekStepSeconds(it) } },
                longPressSpeed = longPressSpeed,
                onLongPressSpeedChange = { scope.launch { preferences.setPlayerBoostSpeed(it) } },
                playbackStats = state::stats,
                audioTracks = state.audioTracks,
                selectedAudioTrackId = state.selectedAudioTrackId,
                onSelectAudioTrack = state::selectAudioTrack,
                subtitleTracks = state.subtitleTracks,
                selectedSubtitleTrackId = state.selectedSubtitleTrackId,
                onSelectSubtitleTrack = state::selectSubtitleTrack,
                brightness = brightness,
                volume = mediaVolume,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                fileActions = rememberPlayerFileActions(state.fileId, state.isLocalPlayback) { message ->
                    scope.launch { snackbarHostState.showSnackbar(message, withDismissAction = true) }
                },
                rotationDegrees = state.rotationDegrees.takeIf { state.supportsRotation },
                onRotationChange = { degrees ->
                    // 转了 90 度，片子横竖对调。全屏时屏幕跟着换方向；不在全屏时只转画面，页面照旧，
                    // 如同桌面窗口最大化时不动窗口。新比例由后端观察 video-params 异步报回，这里按对调直接推算
                    val turned = (degrees - state.rotationDegrees) % 180 != 0
                    val landscapeVideo = state.isLandscapeVideo
                    state.setRotation(degrees)
                    if (turned && isFullscreen && landscapeVideo != null) enterFullscreen(!landscapeVideo)
                },
                onPickLocalSubtitle = pickLocalSubtitle.takeIf { state.canAddSubtitle },
                onPickDriveSubtitle = { file: FileStat -> state.addDriveSubtitle(file.id, file.name) }
                    .takeIf { state.canAddSubtitle },
            )
        }
    }
}

private sealed interface LocalSubtitle {
    class Copied(val file: File) : LocalSubtitle
    data object Unsupported : LocalSubtitle
    data object Unreadable : LocalSubtitle
}

/**
 * 把选中的字幕复制进缓存目录，交给 mpv 的是普通路径。
 *
 * 不交 content: URI：mpv 读不了。也不像本地视频那样交 fdclose:// 描述符：描述符由 mpv 读完即关，
 * 而换清晰度、断线重连都要重开文件、再挂一遍这条字幕，那时描述符早已关掉。字幕只有几十到几百 KB，复制一份最省事。
 * 保留原文件名：mpv 按扩展名认格式，列表里也显示它。
 */
private fun copySubtitleToCache(context: Context, uri: Uri): LocalSubtitle {
    val resolver = context.contentResolver
    val name = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: return LocalSubtitle.Unreadable
    if (!isPlayerSubtitleName(name)) return LocalSubtitle.Unsupported
    val target = File(File(context.cacheDir, "subtitles").apply { mkdirs() }, name.replace('/', '_'))
    return runCatching {
        resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: error("打不开")
        LocalSubtitle.Copied(target)
    }.getOrElse { LocalSubtitle.Unreadable }
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
