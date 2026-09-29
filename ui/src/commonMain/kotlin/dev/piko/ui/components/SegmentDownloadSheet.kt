package dev.piko.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.media.PlayableMediaInfo
import dev.piko.shared.media.player.PlaybackTarget
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.VideoPreviewSupport
import io.github.nihildigit.pikpak.FileStat
import java.util.Locale
import kotlinx.coroutines.delay

fun formatTimeMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0) {
        String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }
}

internal enum class Handle(val label: String) { START("起点"), END("终点") }

// 片段至少这么长：再短抽出来只剩关键帧前后的零头
private const val MIN_CLIP_MS = 500L

/**
 * 一次下载片段，活得比面板长：面板划走、点遮罩或返回只是收起，选好的起点与终点留着，
 * 窄窗口底部留把手、宽窗口收进命令栏右端，点回来接着调；点关闭或下载了才结束。与 DuplicateSession 同一个形状，
 * 挂在 PikoServices 上，网盘页被压栈页盖住、离开组合时也不丢。
 *
 * 对同一个文件再点「下载指定段落」回到这一次；换一个文件就丢掉旧的、开新的。
 */
@Stable
class SegmentSession {
    var file by mutableStateOf<FileStat?>(null)
        private set

    var isSheetOpen by mutableStateOf(false)
        private set

    internal var initialRange: LongRange? = null
    internal var mediaInfo by mutableStateOf<PlayableMediaInfo?>(null)
    internal var loaded by mutableStateOf(false)
    internal var totalDurationMs by mutableLongStateOf(0L)
    var startPosMs by mutableLongStateOf(0L)
        internal set
    var endPosMs by mutableLongStateOf(0L)
        internal set
    internal var editing by mutableStateOf(Handle.START)

    /** [initialRange] 是信息流里「下载这一段」带过来的区间；为 null 时从头起一分钟。 */
    fun open(target: FileStat, initialRange: LongRange? = null) {
        if (file?.id != target.id || initialRange != null) {
            end()
            file = target
            this.initialRange = initialRange
        }
        isSheetOpen = true
    }

    fun reopen() {
        if (file != null) isSheetOpen = true
    }

    fun collapse() {
        isSheetOpen = false
    }

    fun end() {
        file = null
        isSheetOpen = false
        initialRange = null
        mediaInfo = null
        loaded = false
        totalDurationMs = 0L
        startPosMs = 0L
        endPosMs = 0L
        editing = Handle.START
    }
}

/**
 * 下载视频片段：选起点与终点，不转码抽取为 MP4。原片里 MP4 装不下的轨道（内封字幕、部分音轨）会丢，
 * 所以界面上不说「无损」。状态在 [session] 里，收起再打开时原样回来。
 *
 * 只放一个预览，用「起点 | 终点」切换它显示哪一端；拖动区间滑块时自动跟随被拖的那一端。
 * 原先两张半屏宽的预览并排，画面小到看不清，且各开一个代理会话，白占一份账号连接预算。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentDownloadSheet(
    session: SegmentSession,
    onConfirmDownload: (startByte: Long, lengthBytes: Long, timeLabel: String, startMs: Long, endMs: Long, streamUrl: String?) -> Unit,
) {
    val file = session.file ?: return
    val mediaRepo = LocalPikoServices.current.mediaRepository

    // 收起再打开时已经取过就不再取：区间跟着会话留着，重取会把它按初始区间盖掉
    LaunchedEffect(file.id) {
        if (session.loaded) return@LaunchedEffect
        mediaRepo.prepareMedia(file.id).onSuccess { info ->
            session.mediaInfo = info
            val duration = info.durationSeconds * 1000L
            if (duration > 0) {
                val initialRange = session.initialRange
                session.totalDurationMs = duration
                session.startPosMs = initialRange?.first?.coerceIn(0L, duration - MIN_CLIP_MS) ?: 0L
                session.endPosMs = initialRange?.last?.coerceIn(session.startPosMs + MIN_CLIP_MS, duration) ?: minOf(duration, 60_000L)
            }
        }
        session.loaded = true
    }

    var totalDurationMs by session::totalDurationMs
    var startPosMs by session::startPosMs
    var endPosMs by session::endPosMs
    var editing by session::editing
    val mediaInfo = session.mediaInfo
    val isLoading = !session.loaded

    val editingPosition = if (editing == Handle.START) startPosMs else endPosMs
    fun nudge(deltaMs: Long) {
        when (editing) {
            Handle.START -> startPosMs = (startPosMs + deltaMs).coerceIn(0L, endPosMs - MIN_CLIP_MS)
            Handle.END -> endPosMs = (endPosMs + deltaMs).coerceIn(startPosMs + MIN_CLIP_MS, totalDurationMs)
        }
    }

    // 划走、点遮罩、返回都只是收起，见 SegmentSession
    PikoSheet(onDismissRequest = session::collapse) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text("下载片段", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                    contentAlignment = Alignment.Center,
                ) { if (rememberLoadingVisible()) PikoLoadingIndicator() }
                return@Column
            }

            val videoPreview = LocalPikoPlatform.current.videoPreview
            if (videoPreview != null) {
                SegmentPreview(
                    videoPreview = videoPreview,
                    fileId = file.id,
                    positionMs = editingPosition,
                    onDurationKnown = { duration ->
                        if (totalDurationMs <= 0 && duration > 0) {
                            totalDurationMs = duration
                            if (endPosMs == 0L) endPosMs = minOf(duration, 60_000L)
                        }
                    },
                )
            }

            // 选当前调哪一端，右侧是这一端的时间与按秒微调
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConnectedToggle(
                    options = Handle.entries,
                    selected = editing,
                    label = { it.label },
                    onSelect = { editing = it },
                )
                Text(
                    text = formatTimeMs(editingPosition),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                    OutlinedButton(
                        onClick = { nudge(-1000L) },
                        shape = ButtonGroupDefaults.connectedLeadingButtonShape,
                    ) { Text("−1") }
                    OutlinedButton(
                        onClick = { nudge(1000L) },
                        shape = ButtonGroupDefaults.connectedTrailingButtonShape,
                    ) { Text("+1") }
                }
            }

            if (totalDurationMs > 0) {
                Column {
                    RangeSlider(
                        value = startPosMs.toFloat()..endPosMs.toFloat(),
                        onValueChange = { range ->
                            val newStart = range.start.toLong().coerceIn(0L, totalDurationMs)
                            val newEnd = range.endInclusive.toLong().coerceIn(newStart + MIN_CLIP_MS, totalDurationMs)
                            // 预览跟随被拖动的那一端
                            if (newStart != startPosMs) editing = Handle.START
                            else if (newEnd != endPosMs) editing = Handle.END
                            startPosMs = newStart
                            endPosMs = newEnd
                        },
                        valueRange = 0f..totalDurationMs.toFloat(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "${formatTimeMs(startPosMs)} 至 ${formatTimeMs(endPosMs)}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "全长 ${formatTimeMs(totalDurationMs)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            val clipDurationMs = (endPosMs - startPosMs).coerceAtLeast(0L)
            val ratio = if (totalDurationMs > 0) clipDurationMs.toDouble() / totalDurationMs else 0.0
            val estimatedBytes = (file.sizeBytes * ratio).toLong().coerceIn(0L, file.sizeBytes)
            Column {
                MetaRow(
                    parts = listOf("时长 ${formatTimeMs(clipDurationMs)}", "约 ${estimatedBytes.toReadableSize()}"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "起点对齐到前一个关键帧",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = {
                    val startRatio = if (totalDurationMs > 0) startPosMs.toDouble() / totalDurationMs else 0.0
                    val endRatio = if (totalDurationMs > 0) endPosMs.toDouble() / totalDurationMs else 1.0
                    val startByte = (startRatio * file.sizeBytes).toLong().coerceIn(0L, file.sizeBytes)
                    val endByte = (endRatio * file.sizeBytes).toLong().coerceIn(startByte, file.sizeBytes)
                    val lengthBytes = (endByte - startByte).coerceAtLeast(1024L)
                    val label = "${formatTimeMs(startPosMs)}_${formatTimeMs(endPosMs)}"
                    onConfirmDownload(startByte, lengthBytes, label, startPosMs, endPosMs, mediaInfo?.currentUrl)
                },
                enabled = totalDurationMs > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                Text("下载片段", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun <T> ConnectedToggle(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = { onSelect(option) },
                shapes = connectedToggleShapes(index, options.size),
            ) { Text(label(option)) }
        }
    }
}

/**
 * 片段一端的画面预览。经本机代理读：直读 CDN 直链会绕过账号的连接预算，与之后的片段抽取
 * 抢连接。拖动滑块时位置变化很密，停顿片刻再 seek，免得 mpv 被成串的定位请求拖住。
 */
@Composable
private fun SegmentPreview(
    videoPreview: VideoPreviewSupport,
    fileId: String,
    positionMs: Long,
    onDurationKnown: (Long) -> Unit,
) {
    val player = videoPreview.rememberPreviewBackend()
    val mediaRepository = LocalPikoServices.current.mediaRepository
    val latestOnDurationKnown by rememberUpdatedState(onDurationKnown)
    val latestPosition by rememberUpdatedState(positionMs)

    val url by produceState("", fileId) {
        val prepared = mediaRepository.preparePlayback(fileId).getOrNull()
        value = prepared?.let { it.proxyUrl ?: it.info.currentUrl }.orEmpty()
        awaitDispose { prepared?.close() }
    }
    LaunchedEffect(url) {
        if (url.isNotBlank()) player.open(PlaybackTarget.Url(url), startMillis = latestPosition, playWhenReady = false)
    }
    LaunchedEffect(positionMs) {
        delay(SEEK_SETTLE_MS)
        player.seekTo(positionMs)
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player) {
        snapshotFlow { player.durationMillis }.collect { if (it > 0L) latestOnDurationKnown(it) }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNotBlank()) {
            videoPreview.Surface(player, Modifier.fillMaxSize())
            if (player.isBuffering) MediaLoadingIndicator()
        } else {
            MediaLoadingIndicator()
        }
    }
}


private const val SEEK_SETTLE_MS = 120L
