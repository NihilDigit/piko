package dev.piko.ui.screens.player

import android.content.res.Configuration
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import dev.piko.shared.media.PlayableMediaInfo
import dev.piko.shared.media.PlayableMediaKind
import dev.piko.ui.components.FullScreenLoading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.errorOrNull
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.compose.rememberMediampPlayer
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
    fileId: String,
    fileName: String,
    localPath: String? = null,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = PikoApplication.instance
    val context = LocalContext.current
    val repository = app.mediampMediaRepository
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
                        val dataResult = repository.createMediaData(fileId, requestedQuality)
                        if (dataResult.isSuccess) {
                            player.setMediaData(
                                dataResult.getOrThrow().second,
                                playWhenReady = true,
                                startPositionMillis = startMillis,
                            )
                        } else {
                            // Direct URL remains a recovery path for files the range reader cannot open.
                            player.playUri(info.currentUrl, startPositionMillis = startMillis)
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

    Box(modifier.fillMaxSize().background(Color.Black)) {
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
                onDoubleTapSeek = { forward ->
                    val delta = if (forward) SEEK_STEP_MILLIS else -SEEK_STEP_MILLIS
                    seekTo((player.currentPositionMillis.value + delta).coerceAtMost(durationMillis))
                    doubleTapForward = forward
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

        doubleTapForward?.let { DoubleTapIndicator(it) }

        SpeedBoostCapsule(
            visible = isSpeedBoosting,
            isLandscape = isLandscape,
            speed = BOOST_SPEED,
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
                    .padding(start = if (isLandscape) 36.dp else 16.dp),
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
                    isLandscape = isLandscape,
                    isLocalPlayback = localPath != null,
                    aspectRatioMode = if (isImage) null else aspectRatioMode,
                    qualityOptions = if (isImage) emptyList() else qualityOptionsOf(mediaInfo, localPath),
                    currentQuality = requestedQuality ?: ORIGINAL_QUALITY,
                    showSpeedEntry = !isImage && speedFeature != null,
                    onBackClick = {
                        orientationController.resetOrientation()
                        onBackClick()
                    },
                    onAspectRatioChange = { aspectRatioFeature?.setMode(it) },
                    onQualityChange = { quality ->
                        pendingStartMillis = player.currentPositionMillis.value
                        // 切清晰度是用户动作，不是故障，退避次数给新流重新算
                        retryAttempt = 0
                        requestedQuality = quality
                    },
                    onSpeedClick = { showSpeedDialog = true },
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                if (!isImage) {
                    PlayerBottomBar(
                        isPlaying = playerState.playWhenReady,
                        isLandscape = isLandscape,
                        positionMillis = positionMillis,
                        durationMillis = durationMillis,
                        bufferedFraction = bufferedPercentage / 100f,
                        playbackSpeed = playbackSpeed,
                        speedSupported = speedFeature != null,
                        onPlayPause = { player.togglePlayWhenReady() },
                        onSeekTo = ::seekTo,
                        onSpeedClick = { showSpeedDialog = true },
                        onToggleFullscreen = { orientationController.toggleOrientation(isLandscape) },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }

        if (showSpeedDialog && speedFeature != null) {
            PlaybackSpeedDialog(
                speed = playbackSpeed,
                isLandscape = isLandscape,
                onSpeedChange = ::applySpeed,
                onDismiss = { showSpeedDialog = false },
            )
        }
    }
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
