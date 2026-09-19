package dev.piko.ui.screens.player

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.piko.PikoApplication
import dev.piko.shared.media.PlayableMediaInfo
import dev.piko.shared.media.PlayableMediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.compose.rememberMediampPlayer
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
    val player = rememberMediampPlayer()
    val playerState by player.state.collectAsState()
    var mediaInfo by remember { mutableStateOf<PlayableMediaInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isPreparing by remember { mutableStateOf(true) }
    val playbackKey = localPath ?: fileId

    BackHandler(onBack = onBackClick)

    LaunchedEffect(playbackKey) {
        isPreparing = true
        error = null
        player.stopPlayback()
        try {
            val resumePosition = app.mediampMediaRepository.getPlaybackPosition(playbackKey)
            val localFile = localPath?.let(::File)?.takeIf { it.exists() }
            if (localFile != null) {
                player.playUri(Uri.fromFile(localFile).toString(), startPositionMillis = resumePosition)
                isPreparing = false
            } else {
                val info = app.mediampMediaRepository.prepareMedia(fileId).getOrThrow()
                mediaInfo = info
                if (info.kind == PlayableMediaKind.Image) {
                    isPreparing = false
                } else {
                    val dataResult = app.mediampMediaRepository.createMediaData(fileId)
                    if (dataResult.isSuccess) {
                        player.setMediaData(
                            dataResult.getOrThrow().second,
                            playWhenReady = true,
                            startPositionMillis = resumePosition,
                        )
                    } else {
                        // Direct URL remains a recovery path for files the range reader cannot open.
                        player.playUri(info.currentUrl, startPositionMillis = resumePosition)
                    }
                    isPreparing = false
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.localizedMessage ?: "无法打开媒体"
            isPreparing = false
        }
    }

    LaunchedEffect(player, playbackKey) {
        try {
            while (isActive) {
                delay(5_000)
                val position = player.currentPositionMillis.value
                if (position > 1_500L) {
                    app.mediampMediaRepository.savePlaybackPosition(playbackKey, position)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Playback state must not be cancelled by a persistence failure.
        }
    }

    DisposableEffect(player) {
        onDispose { player.close() }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        when {
            mediaInfo?.kind == PlayableMediaKind.Image -> {
                AsyncImage(
                    model = mediaInfo?.currentUrl,
                    contentDescription = fileName,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> MediampPlayerSurface(player, Modifier.fillMaxSize())
        }

        if (isPreparing || playerState.isLoadingOrBuffering) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }

        error?.let {
            Text(
                text = it,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(fileName, color = Color.White, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { player.togglePlayWhenReady() }) {
                    Text(if (playerState.playWhenReady) "暂停" else "播放")
                }
                Button(onClick = { player.skip(-10_000L) }) { Text("后退 10 秒") }
                Button(onClick = { player.skip(10_000L) }) { Text("前进 10 秒") }
                Button(onClick = onBackClick) { Text("返回") }
            }
        }
    }
}
