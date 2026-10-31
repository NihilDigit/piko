package dev.piko.ui.components

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.TextButton
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
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFile
import dev.piko.shared.media.DownloadQuality
import dev.piko.shared.media.chooseDownloadQuality
import dev.piko.shared.media.ORIGINAL_QUALITY
import dev.piko.shared.media.PlayableMediaInfo
import dev.piko.shared.media.player.PlaybackTarget
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.VideoPreviewSupport
import io.github.nihildigit.pikpak.FileStat
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

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

internal enum class Handle {
    START, END;

    val label: String get() = when (this) {
        START -> tr("起点", "Start point", "開始点", "시작점", "起點")
        END -> tr("终点", "End point", "終了点", "끝점", "終點")
    }
}

// 片段至少这么长：再短抽出来只剩关键帧前后的零头
private const val MIN_CLIP_MS = 500L

internal data class NormalizedSegmentRange(val startMs: Long, val endMs: Long, val canAdjust: Boolean)

internal fun normalizeSegmentRange(
    totalDurationMs: Long,
    startMs: Long,
    endMs: Long,
    minSpanMs: Long = MIN_CLIP_MS,
): NormalizedSegmentRange? {
    require(minSpanMs > 0L)
    if (totalDurationMs <= 0L) return null
    if (totalDurationMs <= minSpanMs) return NormalizedSegmentRange(0L, totalDurationMs, false)
    val start = startMs.coerceIn(0L, totalDurationMs - minSpanMs)
    return NormalizedSegmentRange(start, endMs.coerceIn(start + minSpanMs, totalDurationMs), true)
}

internal fun nudgeSegmentRange(
    totalDurationMs: Long,
    startMs: Long,
    endMs: Long,
    editingStart: Boolean,
    deltaMs: Long,
    minSpanMs: Long = MIN_CLIP_MS,
): NormalizedSegmentRange? {
    val range = normalizeSegmentRange(totalDurationMs, startMs, endMs, minSpanMs) ?: return null
    if (!range.canAdjust) return range
    // Saturate before addition, including callers passing Long.MIN_VALUE/MAX_VALUE.
    val position = if (editingStart) range.startMs else range.endMs
    val moved = position + deltaMs.coerceIn(-position, totalDurationMs - position)
    return if (editingStart) range.copy(startMs = moved.coerceIn(0L, range.endMs - minSpanMs))
    else range.copy(endMs = moved.coerceIn(range.startMs + minSpanMs, totalDurationMs))
}

internal fun dragSegmentRange(
    totalDurationMs: Long,
    startMs: Long,
    endMs: Long,
    previousStartMs: Long? = null,
    previousEndMs: Long? = null,
    minSpanMs: Long = MIN_CLIP_MS,
): NormalizedSegmentRange? {
    val base = normalizeSegmentRange(totalDurationMs, startMs, endMs, minSpanMs) ?: return null
    if (!base.canAdjust || previousStartMs == null || previousEndMs == null) return base
    val prev = normalizeSegmentRange(totalDurationMs, previousStartMs, previousEndMs, minSpanMs) ?: return base
    val startMoved = startMs != prev.startMs
    val endMoved = endMs != prev.endMs
    return when {
        startMoved && !endMoved -> prev.copy(startMs = startMs.coerceIn(0L, prev.endMs - minSpanMs))
        endMoved && !startMoved -> prev.copy(endMs = endMs.coerceIn(prev.startMs + minSpanMs, totalDurationMs))
        else -> base
    }
}

/**
 * 一次下载片段，挂在 PikoServices 上，网盘页被压栈页盖住、离开组合时也不丢。面板关掉就是放弃，下载了也结束。
 * 曾经关掉只是收起、选好的区间留着，窄窗口底部留把手、宽窗口收进命令栏右端的菜单：选一段通常几十秒就完，
 * 收起再回来的需求少，为它常驻一个入口不值；改过区间的关掉前确认一次，免得误点遮罩丢掉。
 *
 * 换一个文件就丢掉旧的、开新的。
 */
@Stable
class SegmentSession {
    var file by mutableStateOf<FileStat?>(null)
        private set

    internal var initialRange: LongRange? = null

    /** 取到时长后定下的起点与终点。区间与它不同就是人调过，关掉前要确认。 */
    internal var defaultRange: Pair<Long, Long>? = null

    internal val isEdited: Boolean get() = defaultRange?.let { it != (startPosMs to endPosMs) } == true
    internal var mediaInfo by mutableStateOf<PlayableMediaInfo?>(null)
    internal var loaded by mutableStateOf(false)
    internal var totalDurationMs by mutableLongStateOf(0L)
    var startPosMs by mutableLongStateOf(0L)
        internal set
    var endPosMs by mutableLongStateOf(0L)
        internal set
    internal var editing by mutableStateOf(Handle.START)

    /** 可截的各档，原画在前；取到之前为 null。转码档的大小随后补上。 */
    internal var qualities by mutableStateOf<List<DownloadQuality>?>(null)

    /** 用户点过的档名，原画为 [ORIGINAL_QUALITY]；没点过为 null，按设置里的下载画质预选。 */
    internal var pickedQuality by mutableStateOf<String?>(null)

    /** [initialRange] 是信息流里「下载这一段」带过来的区间；为 null 时从头起一分钟。 */
    fun open(target: FileStat, initialRange: LongRange? = null) {
        if (file?.id != target.id || initialRange != null) {
            end()
            file = target
            this.initialRange = initialRange
        }
    }

    fun end() {
        file = null
        initialRange = null
        defaultRange = null
        mediaInfo = null
        loaded = false
        totalDurationMs = 0L
        startPosMs = 0L
        endPosMs = 0L
        editing = Handle.START
        qualities = null
        pickedQuality = null
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
    /** [quality] 是从哪一档截，原画的 name 为 null；档位还没列出来时为 null，由下载调度按设置挑。 */
    onConfirmDownload: (startByte: Long, lengthBytes: Long, timeLabel: String, startMs: Long, endMs: Long, streamUrl: String?, quality: DownloadQuality?) -> Unit,
    /** 标题下写的是哪一项：网盘页给卡片上的那个名字（见 DriveItemName），为 null 时写文件名。 */
    itemName: String? = null,
) {
    val file = session.file ?: return
    val services = LocalPikoServices.current
    val mediaRepo = services.mediaRepository

    // 档位与大小单独取，取不到只是不给选，照样能按设置截。收起再打开时照样取：仓库留着上次的探测，
    // 探完的立即回来，收起时还没探完的接着等，不会停在一半
    LaunchedEffect(file.id) {
        try {
            mediaRepo.downloadQualities(file.id).collect { session.qualities = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            PikoLog.w("Download", "片段面板查询画质失败：${logFile(file.id, file.name)}", e)
        }
    }
    val defaultCap by produceState(0) { value = services.preferences.downloadMaxHeightFlow.first() ?: 0 }
    val qualities = session.qualities
    val pickedOption = qualities?.let { all -> session.pickedQuality?.let { picked -> all.firstOrNull { (it.name ?: ORIGINAL_QUALITY) == picked } } }
    // 点过的档随后探出读不出：不替用户换一档，清掉选中、停用下载按钮，并说明原因
    val pickedUnreadable = pickedOption?.unreadable == true
    val selectedQuality = if (pickedUnreadable) null else pickedOption ?: qualities?.let { chooseDownloadQuality(it, defaultCap) ?: it.first() }

    // 收起再打开时已经取过就不再取：区间跟着会话留着，重取会把它按初始区间盖掉
    LaunchedEffect(file.id) {
        if (session.loaded) return@LaunchedEffect
        mediaRepo.prepareMedia(file.id).onSuccess { info ->
            session.mediaInfo = info
            val duration = info.durationSeconds * 1000L
            if (duration > 0) {
                val initialRange = session.initialRange
                session.totalDurationMs = duration
                val range = normalizeSegmentRange(duration, initialRange?.first ?: 0L, initialRange?.last ?: minOf(duration, 60_000L))!!
                session.startPosMs = range.startMs
                session.endPosMs = range.endMs
                session.defaultRange = session.startPosMs to session.endPosMs
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

    val normalized = normalizeSegmentRange(totalDurationMs, startPosMs, endPosMs)
    val editingPosition = if (editing == Handle.START) normalized?.startMs ?: 0L else normalized?.endMs ?: 0L
    fun applyRange(range: NormalizedSegmentRange?) {
        if (range == null) return
        startPosMs = range.startMs
        endPosMs = range.endMs
    }
    fun nudged(deltaMs: Long) = nudgeSegmentRange(totalDurationMs, startPosMs, endPosMs, editing == Handle.START, deltaMs)
    fun nudge(deltaMs: Long) = applyRange(nudged(deltaMs))

    // 划走、点遮罩、返回都是放弃，调过区间的先确认，见 SegmentSession
    var confirmDiscard by remember { mutableStateOf(false) }
    if (confirmDiscard) {
        PikoDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(Res.string.text_discard_clip_download)) },
            text = { Text(stringResource(Res.string.text_the_selected_start_and_end_points_will_be_lost)) },
            confirmButton = { PikoDialogConfirm(tr("放弃", "Discard", "破棄", "버리기", "放棄"), onClick = { confirmDiscard = false; session.end() }) },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(Res.string.text_keep_editing)) } },
        )
    }
    PikoSheet(onDismissRequest = { if (session.isEdited) confirmDiscard = true else session.end() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text(stringResource(Res.string.text_download_clip), style = MaterialTheme.typography.titleLarge)
                Text(
                    text = itemName ?: file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val videoPreview = LocalPikoPlatform.current.videoPreview
            if (isLoading) {
                SegmentSheetSkeleton(showPreview = videoPreview != null)
                return@Column
            }

            if (videoPreview != null) {
                SegmentPreview(
                    videoPreview = videoPreview,
                    fileId = file.id,
                    positionMs = editingPosition,
                    onDurationKnown = { duration ->
                        if (totalDurationMs <= 0 && duration > 0) {
                            totalDurationMs = duration
                            val initial = session.initialRange
                            applyRange(normalizeSegmentRange(duration, initial?.first ?: startPosMs,
                                initial?.last ?: endPosMs.takeIf { it > 0L } ?: minOf(duration, 60_000L)))
                            session.defaultRange = startPosMs to endPosMs
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
                        enabled = normalized?.canAdjust == true && nudged(-1000L) != normalized,
                        shape = ButtonGroupDefaults.connectedLeadingButtonShape,
                    ) { Text("−1") }
                    OutlinedButton(
                        onClick = { nudge(1000L) },
                        enabled = normalized?.canAdjust == true && nudged(1000L) != normalized,
                        shape = ButtonGroupDefaults.connectedTrailingButtonShape,
                    ) { Text("+1") }
                }
            }

            if (normalized != null) {
                Column {
                    RangeSlider(
                        value = normalized.startMs.toFloat()..normalized.endMs.toFloat(),
                        enabled = normalized.canAdjust,
                        onValueChange = { range ->
                            val next = dragSegmentRange(
                                totalDurationMs = totalDurationMs,
                                startMs = range.start.toLong(),
                                endMs = range.endInclusive.toLong(),
                                previousStartMs = normalized.startMs,
                                previousEndMs = normalized.endMs,
                            ) ?: return@RangeSlider
                            val newStart = next.startMs
                            val newEnd = next.endMs
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
                            text = tr("${formatTimeMs(startPosMs)} 至 ${formatTimeMs(endPosMs)}", "${formatTimeMs(startPosMs)} to ${formatTimeMs(endPosMs)}", "${formatTimeMs(startPosMs)} ～ ${formatTimeMs(endPosMs)}", "${formatTimeMs(startPosMs)}~${formatTimeMs(endPosMs)}", "${formatTimeMs(startPosMs)} 至 ${formatTimeMs(endPosMs)}"),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = tr("全长 ${formatTimeMs(totalDurationMs)}", "Total ${formatTimeMs(totalDurationMs)}", "全長 ${formatTimeMs(totalDurationMs)}", "전체 길이 ${formatTimeMs(totalDurationMs)}", "全長 ${formatTimeMs(totalDurationMs)}"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 只有原画时不给选；转码档截出来同样存为 MP4
            if (qualities != null && qualities.size > 1) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.text_quality), style = MaterialTheme.typography.bodyMedium)
                    // 读不出的档留在原位、停用，理由同画质对话框的 QualityRow
                    ConnectedToggle(
                        options = qualities,
                        selected = selectedQuality?.let { chosen -> qualities.first { it.name == chosen.name } },
                        label = { it.name ?: tr("原画", "Original", "オリジナル", "원본", "原畫") },
                        enabled = { !it.unreadable },
                        onSelect = { session.pickedQuality = it.name ?: ORIGINAL_QUALITY },
                    )
                }
                if (pickedUnreadable) {
                    Text(
                        tr("${pickedOption?.name} 转码流暂不可用，请选择其他画质。", "${pickedOption?.name} transcoded stream is unavailable. Choose another quality.", "${pickedOption?.name} の変換ストリームを利用できません。別の画質を選択してください。", "${pickedOption?.name} 변환 스트림을 사용할 수 없습니다. 다른 화질을 선택해 주세요.", "${pickedOption?.name} 轉碼串流暫時無法使用，請選擇其他畫質。"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            val clipDurationMs = (endPosMs - startPosMs).coerceAtLeast(0L)
            val ratio = if (totalDurationMs > 0) clipDurationMs.toDouble() / totalDurationMs else 0.0
            // 按所选那一档的大小折算；转码档的大小还没探到时没法估
            val sourceBytes = selectedQuality?.sizeBytes ?: file.sizeBytes.takeIf { selectedQuality?.mediaId == null }
            val estimatedBytes = sourceBytes?.let { (it * ratio).toLong().coerceIn(0L, it) }
            Column {
                MetaRow(
                    parts = listOfNotNull(tr("时长 ${formatTimeMs(clipDurationMs)}", "Duration ${formatTimeMs(clipDurationMs)}", "長さ ${formatTimeMs(clipDurationMs)}", "길이 ${formatTimeMs(clipDurationMs)}", "時長 ${formatTimeMs(clipDurationMs)}"), estimatedBytes?.let { tr("约 ${it.toReadableSize()}", "About ${it.toReadableSize()}", "約 ${it.toReadableSize()}", "약 ${it.toReadableSize()}", "約 ${it.toReadableSize()}") }),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(Res.string.text_align_start_to_previous_keyframe),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = {
                    val range = normalizeSegmentRange(totalDurationMs, startPosMs, endPosMs) ?: return@Button
                    applyRange(range)
                    val startRatio = range.startMs.toDouble() / totalDurationMs
                    val endRatio = range.endMs.toDouble() / totalDurationMs
                    val startByte = (startRatio * file.sizeBytes).toLong().coerceIn(0L, file.sizeBytes)
                    val endByte = (endRatio * file.sizeBytes).toLong().coerceIn(startByte, file.sizeBytes)
                    val remainingBytes = (file.sizeBytes - startByte).coerceAtLeast(0L)
                    val lengthBytes = if (remainingBytes in 1L..1023L) remainingBytes else (endByte - startByte).coerceAtLeast(1024L)
                    val label = "${formatTimeMs(startPosMs)}_${formatTimeMs(endPosMs)}"
                    onConfirmDownload(startByte, lengthBytes, label, startPosMs, endPosMs, mediaInfo?.currentUrl, selectedQuality)
                },
                // 选中的档读不出时没有可下的档：放行的话 null 会被当成「按设置挑」，悄悄换了画质
                enabled = normalized != null && !pickedUnreadable,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(stringResource(Res.string.text_download_clip), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/**
 * 取到时长之前的骨架，自上而下照抄加载后的各段：预览、起点终点那一行、区间滑块与其下的时间、时长与说明、下载按钮。
 * 底部 sheet 按内容定高，没有骨架时内容一换上来整张 sheet 往上长一截。
 * 按钮与滑块可见的高度是 40dp 与 44dp，所在那一行却按最小触摸尺寸占 48dp，这里照占位的算。
 * 画质那一行另行异步取，加载后也可能还没有，不画；两组按钮的宽度按两字标签估算。
 */
@Composable
private fun SegmentSheetSkeleton(showPreview: Boolean) {
    SkeletonGroup {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (showPreview) {
                SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(16f / 9f), MaterialTheme.shapes.large)
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBlock(Modifier.size(width = 122.dp, height = 40.dp), CircleShape)
                SkeletonText(MaterialTheme.typography.titleLarge, 0.6f, Modifier.weight(1f).padding(horizontal = 12.dp))
                SkeletonBlock(Modifier.size(width = 118.dp, height = 40.dp), CircleShape)
            }
            Column {
                Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                    SkeletonBlock(Modifier.fillMaxWidth().height(16.dp), CircleShape)
                }
                SkeletonText(MaterialTheme.typography.labelMedium, 0.4f)
            }
            Column {
                SkeletonText(MaterialTheme.typography.bodyMedium, 0.35f)
                SkeletonText(MaterialTheme.typography.bodySmall, 0.3f)
            }
            SkeletonBlock(Modifier.fillMaxWidth().height(56.dp), CircleShape)
        }
    }
}

@Composable
private fun <T> ConnectedToggle(
    options: List<T>,
    /** null 时一项都不选中。 */
    selected: T?,
    label: (T) -> String,
    enabled: (T) -> Boolean = { true },
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = { onSelect(option) },
                enabled = enabled(option),
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
