package dev.piko.ui.screens.player

import android.content.res.Configuration
import android.media.MediaMetadataRetriever
import android.net.Uri
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.piko.PikoApplication
import dev.piko.data.repository.isPlayableVideo
import dev.piko.data.repository.needsTranscodedPlayback
import dev.piko.download.DownloadStatus
import dev.piko.shared.media.PlayableMediaInfo
import dev.piko.shared.media.PlayableMediaKind
import dev.piko.shared.media.bestTranscodeName
import dev.piko.shared.media.originNeedsTranscode
import dev.piko.ui.components.FullScreenLoading
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.PlaybackErrorCode
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.errorOrNull
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.compose.rememberMediampPlayer
import org.openani.mediamp.features.AspectRatioMode
import org.openani.mediamp.features.Buffering
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.VideoAspectRatio
import org.openani.mediamp.isLoadingOrBuffering
import org.openani.mediamp.playUri
import org.openani.mediamp.togglePlayWhenReady
import java.io.File

/**
 * Android player backed by MediaMP. PikPak uses the shared seekable input so
 * ExoPlayer and the desktop MPV backend consume the same range reader.
 */
@OptIn(ExperimentalMediampApi::class)
@Composable
fun MediampVideoPlayerScreen(
    initialFileId: String,
    initialFileName: String,
    initialLocalPath: String? = null,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 播的是哪个文件由状态决定，入参只是初值：同目录的视频可以在播放器里直接切换，
    // 不退回列表再进来一次
    var fileId by remember(initialFileId) { mutableStateOf(initialFileId) }
    var fileName by remember(initialFileId) { mutableStateOf(initialFileName) }
    var localPath by remember(initialFileId) { mutableStateOf(initialLocalPath) }
    val app = PikoApplication.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = app.mediampMediaRepository
    val driveRepo = app.driveRepository
    val player = rememberMediampPlayer()
    val playerState by player.state.collectAsStateWithLifecycle()
    val positionMillis by player.currentPositionMillis.collectAsStateWithLifecycle()
    val mediaProperties by player.mediaProperties.collectAsStateWithLifecycle()

    val bufferingFeature = remember(player) { player.features[Buffering.Key] }
    val speedFeature = remember(player) { player.features[PlaybackSpeed.Key] }
    val aspectRatioFeature = remember(player) { player.features[VideoAspectRatio.Key] }
    val bufferedPercentage by remember(bufferingFeature) {
        bufferingFeature?.bufferedPercentage ?: flowOf(0)
    }.collectAsStateWithLifecycle(0)
    val aspectRatioMode = aspectRatioFeature?.mode?.collectAsStateWithLifecycle()?.value

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val orientationController = rememberOrientationController()
    val brightness = rememberWindowBrightness()
    val volume = rememberMediaVolume(context)

    val playbackKey = localPath ?: fileId
    var mediaInfo by remember { mutableStateOf<PlayableMediaInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isPreparing by remember { mutableStateOf(true) }
    var requestedQuality by remember(playbackKey) { mutableStateOf<String?>(null) }
    // 实际在放的变体。与 requestedQuality 的区别是它包含自动选的转码流，
    // 顶栏显示和「是否已经换过流」的判断都看这个
    var activeQuality by remember(playbackKey) { mutableStateOf<String?>(null) }
    // 重试时清晰度不变，靠这个计数器把准备流程重新拉起来
    var retryToken by remember(playbackKey) { mutableIntStateOf(0) }
    // 切清晰度时从当前位置续上；为 null 才去读持久化的进度
    var pendingStartMillis by remember(playbackKey) { mutableStateOf<Long?>(null) }
    // 解码出错后的自动退避次数。用户手动重试、切清晰度、恢复播放都会清零
    var retryAttempt by remember(playbackKey) { mutableIntStateOf(0) }
    var isRecovering by remember(playbackKey) { mutableStateOf(false) }
    // 出错瞬间 currentPositionMillis 可能已经归零，自动重连用这个位置续上
    var lastKnownPositionMillis by remember(playbackKey) { mutableLongStateOf(0L) }
    var resumedPositionMillis by remember { mutableLongStateOf(0L) }
    var showResumeTip by remember { mutableStateOf(false) }

    // 本地播放没有服务端元数据，画面朝向只能自己读一次容器头
    var localIsLandscapeVideo by remember(localPath) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(localPath) {
        val path = localPath ?: return@LaunchedEffect
        localIsLandscapeVideo = withContext(Dispatchers.IO) { readVideoIsLandscape(path) }
    }
    val isLandscapeVideo = localIsLandscapeVideo ?: mediaInfo?.isLandscapeVideo

    // 同目录的其他视频。取一次即可：播放期间目录内容变了也不该让播放列表在脚下重排
    var siblingVideos by remember(initialFileId) { mutableStateOf<List<FileStat>>(emptyList()) }
    var showPlaylist by remember { mutableStateOf(false) }
    LaunchedEffect(initialFileId) {
        val parentId = driveRepo.getFileDetail(initialFileId).getOrNull()?.parentId ?: return@LaunchedEffect
        siblingVideos = driveRepo.listAllFiles(parentId)
            .getOrNull()
            .orEmpty()
            .filter { it.isPlayableVideo() }
    }

    // 内存任务表 App 重启就空：同目录元数据到了之后，用磁盘再验一次，
    // 下好的片子直接播本地，不用再去云端取流。命中后 playbackKey 翻转，主流程重跑一遍播本地。
    LaunchedEffect(fileId, siblingVideos) {
        if (localPath != null) return@LaunchedEffect
        val stat = siblingVideos.find { it.id == fileId } ?: return@LaunchedEffect
        app.downloadManager.findCompletedLocalPath(stat)?.let { localPath = it }
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var activeGesture by remember { mutableStateOf<PlayerGesture?>(null) }
    var doubleTapForward by remember { mutableStateOf<Boolean?>(null) }
    var isSpeedBoosting by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(speedFeature?.value ?: 1f) }

    val durationMillis = mediaProperties?.durationMillis
        ?: mediaInfo?.durationSeconds?.times(1000L)
        ?: 0L
    val isImage = mediaInfo?.kind == PlayableMediaKind.Image
    // 准备阶段的异常由 error 捕获；解码阶段的错误只出现在播放状态里。
    // 退避重连期间不报错，否则每次重连都会闪一次失败卡片
    val displayedError = when {
        isRecovering -> null
        error != null -> error
        // 容器不认又没有转码流可换时，报底层的 3001/3003 对用户没有意义。
        // 盘里同一部片子有的有转码有的没有，所以这句要说清等的是什么
        playerState.errorOrNull != null &&
            (fileName.needsTranscodedPlayback() || mediaInfo?.originNeedsTranscode() == true) &&
            mediaInfo?.bestTranscodeName() == null ->
            "本机无法解码此格式，需等服务端转码完成"

        else -> playerState.errorOrNull?.let { it.message ?: "播放中断" }
    }

    fun applySpeed(value: Float) {
        playbackSpeed = value
        speedFeature?.set(value)
    }

    fun seekTo(target: Long) {
        player.seekTo(target.coerceAtLeast(0L))
    }

    LaunchedEffect(playbackKey, requestedQuality, retryToken) {
        isPreparing = true
        error = null
        showResumeTip = false
        player.stopPlayback()
        try {
            val startMillis = pendingStartMillis
                ?: repository.getPlaybackPosition(playbackKey).takeIf { it > RESUME_THRESHOLD_MILLIS }
                ?: 0L
            pendingStartMillis = null

            val localFile = localPath?.let(::File)?.takeIf { it.exists() }
            if (localFile != null) {
                player.playUri(Uri.fromFile(localFile).toString(), startPositionMillis = startMillis)
                isPreparing = false
            } else {
                val info = repository.prepareMedia(fileId, requestedQuality).getOrThrow()
                mediaInfo = info
                when (info.kind) {
                    PlayableMediaKind.Image -> isPreparing = false

                    PlayableMediaKind.UnsupportedImage -> {
                        error = "GIF 暂不支持预览"
                        isPreparing = false
                    }

                    PlayableMediaKind.Video -> {
                        // wmv/rm 这类容器 ExoPlayer 根本没有 extractor，原文件拉下来只会
                        // 报 3003 PARSING_CONTAINER_UNSUPPORTED，开播就直接要 PikPak 的
                        // 转码流。用户仍可从清晰度菜单切回原画。
                        val needsTranscode = fileName.needsTranscodedPlayback() || info.originNeedsTranscode()
                        val autoQuality = if (requestedQuality == null && needsTranscode) {
                            info.bestTranscodeName()
                        } else {
                            null
                        }
                        // 换了变体就要重取一次详情：失败回退用的直链必须与实际读的字节同源
                        val playedInfo = if (autoQuality != null) {
                            repository.prepareMedia(fileId, autoQuality).getOrThrow().also { mediaInfo = it }
                        } else {
                            info
                        }
                        val quality = requestedQuality ?: autoQuality
                        activeQuality = quality
                        val dataResult = repository.createMediaData(fileId, quality)
                        if (dataResult.isSuccess) {
                            player.setMediaData(
                                dataResult.getOrThrow().second,
                                playWhenReady = true,
                                startPositionMillis = startMillis,
                            )
                        } else {
                            // Direct URL remains a recovery path for files the range reader cannot open.
                            player.playUri(playedInfo.currentUrl, startPositionMillis = startMillis)
                        }
                        isPreparing = false
                    }
                }
            }

            if (startMillis > RESUME_THRESHOLD_MILLIS) {
                resumedPositionMillis = startMillis
                showResumeTip = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.localizedMessage ?: "无法打开媒体"
            isPreparing = false
            // 重连的这一轮连流都没拿到，退避到此为止，否则失败卡片会被加载态一直挡着
            isRecovering = false
        }
    }

    LaunchedEffect(player, playbackKey) {
        suspend fun persistProgress() {
            val position = player.currentPositionMillis.value
            val duration = player.mediaProperties.value?.durationMillis ?: 0L
            // 放完最后十秒记 0，下次从头播，而不是停在片尾
            if (duration > 0L && position >= duration - NEAR_END_MILLIS) {
                repository.savePlaybackPosition(playbackKey, 0L)
            } else if (position > MIN_PERSIST_MILLIS) {
                repository.savePlaybackPosition(playbackKey, position)
            }
        }

        try {
            while (isActive) {
                delay(5_000)
                persistProgress()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Playback state must not be cancelled by a persistence failure.
        } finally {
            // 离开播放器时补一次，否则最后不足 5 秒的进度会丢。
            // 播放器可能已经 close，此时位置读回 0，persistProgress 的下限判断会跳过写入。
            withContext(NonCancellable) {
                runCatching { persistProgress() }
            }
        }
    }

    LaunchedEffect(player, playbackKey) {
        player.currentPositionMillis.collect { if (it > 0L) lastKnownPositionMillis = it }
    }

    // 解码错误的退避重连。事件流是一次一发的，用它而不是 errorOrNull：
    // 后者是状态，重连过程中会被反复读到同一个错误，退避次数会被多扣
    LaunchedEffect(player, playbackKey) {
        player.events.collect { event ->
            if (event !is PlaybackEvent.ErrorOccurred) return@collect
            // 本地文件重来一遍还是同一个解码错误，直接交给用户
            if (localPath != null) return@collect

            // 原画解不开多半是容器不认（扩展名没拦住的那些），同一份字节重拉多少次
            // 都是一样的结果，有转码流就换过去，没有才走退避重连
            val transcode = mediaInfo?.bestTranscodeName()
            if (activeQuality == null && transcode != null) {
                pendingStartMillis = player.currentPositionMillis.value.takeIf { it > 0L }
                    ?: lastKnownPositionMillis
                retryAttempt = 0
                isRecovering = true
                requestedQuality = transcode
                return@collect
            }

            // 容器/编码不被支持（media3 的 3001~3004、4005 都归到这里）重拉多少次
            // 都是同一份解不开的字节，退避没有意义，直接把错误交给用户
            if (event.error.code == PlaybackErrorCode.UNSUPPORTED_FORMAT) {
                isRecovering = false
                return@collect
            }

            if (retryAttempt >= RECOVERY_DELAYS_MILLIS.size) {
                isRecovering = false
                return@collect
            }
            val resumeFrom = player.currentPositionMillis.value.takeIf { it > 0L }
                ?: lastKnownPositionMillis
            isRecovering = true
            delay(RECOVERY_DELAYS_MILLIS[retryAttempt])
            retryAttempt += 1
            pendingStartMillis = resumeFrom
            retryToken += 1
        }
    }

    // 重新播起来就认为这次故障过去了，退避次数还原
    LaunchedEffect(playerState.isPlaying) {
        if (playerState.isPlaying) {
            retryAttempt = 0
            isRecovering = false
        }
    }

    LaunchedEffect(showResumeTip) {
        if (showResumeTip) {
            delay(5_000)
            showResumeTip = false
        }
    }

    LaunchedEffect(doubleTapForward) {
        if (doubleTapForward != null) {
            delay(650)
            doubleTapForward = null
        }
    }

    LaunchedEffect(controlsVisible, playerState.isPlaying, isLocked) {
        if (controlsVisible && playerState.isPlaying && !isLocked) {
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

    DisposableEffect(player) {
        onDispose { player.close() }
    }

    BackHandler {
        if (isLandscape) orientationController.setPortrait() else onBackClick()
    }

    PlayerTheme { Box(modifier.fillMaxSize().background(Color.Black)) {
        if (isImage) {
            AsyncImage(
                model = mediaInfo?.currentUrl,
                contentDescription = fileName,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            MediampPlayerSurface(player, Modifier.fillMaxSize())

            PlayerGestureLayer(
                isLocked = isLocked,
                durationMillis = durationMillis,
                positionProvider = { player.currentPositionMillis.value },
                brightness = brightness,
                volume = volume,
                onGestureChange = { activeGesture = it },
                onToggleControls = { controlsVisible = !controlsVisible },
                onSeekTo = ::seekTo,
                onDoubleTap = { zone ->
                    if (zone == DoubleTapZone.PlayPause) {
                        player.togglePlayWhenReady()
                    } else {
                        val forward = zone == DoubleTapZone.Forward
                        val delta = if (forward) SEEK_STEP_MILLIS else -SEEK_STEP_MILLIS
                        seekTo((player.currentPositionMillis.value + delta).coerceAtMost(durationMillis))
                        doubleTapForward = forward
                    }
                },
                onSpeedBoost = { active ->
                    isSpeedBoosting = active
                    speedFeature?.set(if (active) BOOST_SPEED else playbackSpeed)
                },
            )
        }

        if (isPreparing || isRecovering || (!isImage && playerState.isLoadingOrBuffering)) {
            FullScreenLoading()
        }

        activeGesture?.let { PlayerGestureHud(it, durationMillis) }

        doubleTapForward?.let { DoubleTapIndicator(it, seconds = (SEEK_STEP_MILLIS / 1000).toInt()) }

        SpeedBoostCapsule(
            visible = isSpeedBoosting,
            isLandscape = isLandscape,
            speed = BOOST_SPEED,
        )

        // 竖屏放横屏片子时画面只占中间一条，下面整片黑边闲着。全屏入口在顶栏
        // 那排图标里太小也太远，这里给一个落在拇指位置的大目标。
        FullscreenPromptButton(
            visible = !isImage && !isLocked && !isLandscape && isLandscapeVideo == true,
            controlsVisible = controlsVisible,
            onClick = { orientationController.setLandscape() },
        )

        ResumeTipCapsule(
            visible = showResumeTip && !isLocked,
            resumedPositionMillis = resumedPositionMillis,
            isLandscape = isLandscape,
            controlsVisible = controlsVisible,
            onRestart = {
                seekTo(0L)
                showResumeTip = false
            },
        )

        displayedError?.let { message ->
            PlaybackErrorCard(
                message = message,
                onRetry = {
                    pendingStartMillis = player.currentPositionMillis.value.takeIf { it > 0L }
                        ?: lastKnownPositionMillis
                    retryAttempt = 0
                    retryToken += 1
                },
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
                    title = fileName,
                    isLocalPlayback = localPath != null,
                    aspectRatio = if (isImage) null else aspectRatioMode?.toPlayerAspectRatio(),
                    qualityOptions = if (isImage) emptyList() else qualityOptionsOf(mediaInfo, localPath),
                    currentQuality = activeQuality ?: ORIGINAL_QUALITY,
                    showPlaylistEntry = !isImage && siblingVideos.size > 1,
                    onPlaylistClick = { showPlaylist = true },
                    onBackClick = {
                        orientationController.resetOrientation()
                        onBackClick()
                    },
                    onAspectRatioChange = { aspectRatioFeature?.setMode(it.toAspectRatioMode()) },
                    onQualityChange = { quality ->
                        pendingStartMillis = player.currentPositionMillis.value
                        // 切清晰度是用户动作，不是故障，退避次数给新流重新算
                        retryAttempt = 0
                        requestedQuality = quality
                    },
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                if (!isImage) {
                    PlayerCenterControls(
                        isPlaying = playerState.playWhenReady,
                        isLoading = playerState.isLoadingOrBuffering,
                        isLandscape = isLandscape,
                        onPlayPause = { player.togglePlayWhenReady() },
                        onSeekBackward = { seekTo(player.currentPositionMillis.value - SEEK_STEP_MILLIS) },
                        onSeekForward = {
                            seekTo((player.currentPositionMillis.value + SEEK_STEP_MILLIS).coerceAtMost(durationMillis))
                        },
                        modifier = Modifier.align(Alignment.Center),
                    )
                    PlayerBottomBar(
                        isLandscape = isLandscape,
                        positionMillis = positionMillis,
                        durationMillis = durationMillis,
                        bufferedPositionMillis = durationMillis * bufferedPercentage / 100,
                        playbackSpeed = playbackSpeed.takeIf { speedFeature != null },
                        onSeek = ::seekTo,
                        onSpeedClick = { showSpeedDialog = true },
                        onToggleFullscreen = { orientationController.toggleOrientation(isLandscape) },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }

        if (showPlaylist) {
            PlayerPlaylistSheet(
                videos = siblingVideos,
                currentFileId = fileId,
                onSelect = { target ->
                    showPlaylist = false
                    if (target.id != fileId) {
                        // 清晰度、重试计数、续播位置都以 playbackKey 为 remember 的键，
                        // 换了文件这些状态自己会重置，这里只换标识
                        scope.launch {
                            // 先把本地验完再切，否则主流程会先跑一遍云端准备再翻回来。
                            val path = completedDownloadPath(target.id)
                                ?: app.downloadManager.findCompletedLocalPath(target)
                            fileId = target.id
                            fileName = target.name
                            localPath = path
                        }
                    }
                },
                onDismiss = { showPlaylist = false },
            )
        }

        if (showSpeedDialog && speedFeature != null) {
            PlaybackSpeedSheet(
                speed = playbackSpeed,
                onSpeedChange = ::applySpeed,
                onDismiss = { showSpeedDialog = false },
            )
        }
    } }
}

/**
 * 这个文件已下载到本地的完整副本，没有则为 null。
 *
 * 与进播放器时那次查找同一套判据：分段下载的片段不算，路径对应的文件也要还在。
 */
private fun completedDownloadPath(fileId: String): String? =
    PikoApplication.instance.downloadManager.tasks.value.values
        .find { it.fileId == fileId && it.status == DownloadStatus.COMPLETED && !it.isSegment }
        ?.destinationPath
        ?.takeIf { File(it).exists() }

/**
 * 本地文件的画面朝向，读不出来返回 null。
 *
 * 竖着拍的片子容器里存的仍是横向分辨率，靠 rotation 摆正，所以 90/270 时宽高要对调，
 * 否则竖屏视频也会被当成横屏去提示全屏。
 */
private fun readVideoIsLandscape(path: String): Boolean? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (width == null || height == null || width <= 0 || height <= 0) {
            null
        } else if (rotation % 180 == 0) {
            width > height
        } else {
            height > width
        }
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun AspectRatioMode.toPlayerAspectRatio(): PlayerAspectRatio = when (this) {
    AspectRatioMode.FIT -> PlayerAspectRatio.Fit
    AspectRatioMode.CROP -> PlayerAspectRatio.Crop
    AspectRatioMode.STRETCH -> PlayerAspectRatio.Stretch
}

private fun PlayerAspectRatio.toAspectRatioMode(): AspectRatioMode = when (this) {
    PlayerAspectRatio.Fit -> AspectRatioMode.FIT
    PlayerAspectRatio.Crop -> AspectRatioMode.CROP
    PlayerAspectRatio.Stretch -> AspectRatioMode.STRETCH
}

private const val ORIGINAL_QUALITY = "Original"
private const val RESUME_THRESHOLD_MILLIS = 3_000L
private const val NEAR_END_MILLIS = 10_000L
private val RECOVERY_DELAYS_MILLIS = longArrayOf(500L, 1_500L, 4_000L)
private const val MIN_PERSIST_MILLIS = 1_500L
private const val CONTROLS_HIDE_DELAY_MILLIS = 4_500L
private const val BOOST_SPEED = 2.0f

/**
 * 清晰度选项。本地文件没有变体可选，直接返回空列表让入口隐藏。
 */
private fun qualityOptionsOf(info: PlayableMediaInfo?, localPath: String?): List<String> {
    if (localPath != null || info == null) return emptyList()
    val variants = info.availableVariants.map { it.mediaName }.filter { it.isNotBlank() }
    if (variants.isEmpty()) return emptyList()
    return (listOf(ORIGINAL_QUALITY) + variants).distinct()
}
