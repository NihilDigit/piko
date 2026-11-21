package dev.piko.ui.screens.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.tr
import dev.piko.shared.media.player.MediaTrack
import dev.piko.shared.media.player.OriginalSubtitleTrackInfo
import dev.piko.shared.media.player.PlaybackStatsSection
import dev.piko.shared.media.player.trackDisplayNames
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.player_copy_diagnostics
import dev.piko.ui.resources.player_diagnostics_copied
import dev.piko.ui.resources.player_hw_decode
import dev.piko.ui.resources.player_sw_decode
import dev.piko.ui.theme.tabular
import kotlinx.coroutines.delay

/**
 * 音轨与字幕。轨道名常带语言、字幕组与「外挂」，长短不一，按钮组放不下，所以一行一条、单选。
 * 音轨只有一条时整节不出现；字幕第一项是关闭，末尾是从本机或网盘另挑一个字幕文件的入口，
 * 平台或后端做不到时为 null，不显示。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TracksPanel(
    audioTracks: List<MediaTrack>,
    selectedAudioTrackId: String?,
    onSelectAudio: (MediaTrack) -> Unit,
    subtitleTracks: List<MediaTrack>,
    selectedSubtitleTrackId: String?,
    onSelectSubtitle: (MediaTrack?) -> Unit,
    onPickLocalSubtitle: (() -> Unit)?,
    onPickDriveSubtitle: (() -> Unit)?,
    modifier: Modifier = Modifier,
    originalSubtitleHints: List<OriginalSubtitleTrackInfo> = emptyList(),
    onSwitchToOriginalQuality: (() -> Unit)? = null,
) {
    val audioLabels = trackDisplayNames(audioTracks)
    val subtitleLabels = trackDisplayNames(subtitleTracks)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (audioTracks.size > 1) {
            TrackSection(tr("音轨", "Audio track", "音声トラック", "오디오 트랙", "音軌")) {
                audioTracks.forEachIndexed { index, track ->
                    TrackRow(
                        label = audioLabels[index],
                        selected = track.id == selectedAudioTrackId,
                        index = index,
                        count = audioTracks.size,
                        onClick = { onSelectAudio(track) },
                    )
                }
            }
        }
        TrackSection(tr("字幕", "Subtitles", "字幕", "자막", "字幕")) {
            val showOriginalHint = originalSubtitleHints.isNotEmpty() && onSwitchToOriginalQuality != null
            val pickerCount = (if (showOriginalHint) 1 else 0) +
                (if (onPickLocalSubtitle != null) 1 else 0) +
                (if (onPickDriveSubtitle != null) 1 else 0)
            val totalSubtitleRows = 1 + subtitleTracks.size + pickerCount
            TrackRow(
                label = tr("关闭", "Close", "閉じる", "닫기", "關閉"),
                selected = selectedSubtitleTrackId == null,
                index = 0,
                count = totalSubtitleRows,
                onClick = { onSelectSubtitle(null) },
            )
            subtitleTracks.forEachIndexed { index, track ->
                TrackRow(
                    label = subtitleLabels[index],
                    selected = track.id == selectedSubtitleTrackId,
                    index = index + 1,
                    count = totalSubtitleRows,
                    onClick = { onSelectSubtitle(track) },
                )
            }
            var nextPickerIndex = 1 + subtitleTracks.size
            if (showOriginalHint && onSwitchToOriginalQuality != null) {
                val summary = originalSubtitleHints
                    .mapNotNull { it.title?.takeIf(String::isNotBlank) ?: it.language?.uppercase() }
                    .distinct()
                    .take(3)
                    .joinToString(" / ")
                val label = if (summary.isNotBlank()) {
                    tr(
                        "切换原画以加载内封字幕（$summary）",
                        "Switch to Original for embedded subtitles ($summary)",
                        "オリジナルに切り替えて内蔵字幕を表示（$summary）",
                        "내장 자막을 보려면 원본 화질로 전환 ($summary)",
                        "切換原畫以載入內嵌字幕（$summary）",
                    )
                } else {
                    tr(
                        "切换原画以加载内封字幕",
                        "Switch to Original for embedded subtitles",
                        "オリジナルに切り替えて内蔵字幕を表示",
                        "내장 자막을 보려면 원본 화질로 전환",
                        "切換原畫以載入內嵌字幕",
                    )
                }
                SegmentedPickerRow(
                    icon = Icons.Outlined.Subtitles,
                    label = label,
                    index = nextPickerIndex++,
                    count = totalSubtitleRows,
                    onClick = onSwitchToOriginalQuality,
                )
            }
            if (onPickLocalSubtitle != null) {
                SegmentedPickerRow(
                    icon = Icons.Outlined.FileOpen,
                    label = tr("从本机选择字幕…", "Choose subtitles from device…", "この端末から字幕を選択…", "기기에서 자막 선택…", "從本機選擇字幕…"),
                    index = nextPickerIndex++,
                    count = totalSubtitleRows,
                    onClick = onPickLocalSubtitle,
                )
            }
            if (onPickDriveSubtitle != null) {
                SegmentedPickerRow(
                    icon = Icons.Outlined.Cloud,
                    label = tr("从网盘选择字幕…", "Choose subtitles from drive…", "ドライブから字幕を選択…", "드라이브에서 자막 선택…", "從雲端硬碟選擇字幕…"),
                    index = nextPickerIndex,
                    count = totalSubtitleRows,
                    onClick = onPickDriveSubtitle,
                )
            }
        }
    }
}

@Composable
private fun TrackSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(SEGMENT_GAP)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
        )
        content()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TrackRow(
    label: String,
    selected: Boolean,
    index: Int,
    count: Int,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = if (selected) {
        RoundedCornerShape(SEGMENT_OUTER_CORNER)
    } else {
        ListItemDefaults.segmentedShapes(index = index, count = count).shape
    }
    Surface(
        shape = shape,
        color = if (selected) colors.secondaryContainer else colors.surfaceContainer,
        contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 52.dp)
            .handCursor(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = if (selected) MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium) else MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = colors.onSecondaryContainer,
                    modifier = Modifier.padding(start = 12.dp).size(20.dp),
                )
            }
        }
    }
}

private data class DecodeBadgeInfo(val isHardware: Boolean, val backend: String?)

private fun extractDecodeBadge(sections: List<PlaybackStatsSection>): DecodeBadgeInfo? {
    val decoderLabel = tr("解码", "Decoder", "デコード", "디코딩", "解碼")
    val hwPrefix = tr("硬件", "Hardware", "ハードウェア", "하드웨어", "硬體")
    for (section in sections) {
        for ((label, value) in section.rows) {
            if (label == decoderLabel) {
                val isHw = value.startsWith(hwPrefix, ignoreCase = true)
                val backend = value.substringAfter('（', "").substringBefore('）', "").ifBlank {
                    value.substringAfter('(', "").substringBefore(')', "")
                }.takeIf { it.isNotBlank() }
                return DecodeBadgeInfo(isHardware = isHw, backend = backend)
            }
        }
    }
    return null
}

private fun formatDiagnosticsText(sections: List<PlaybackStatsSection>): String =
    sections.joinToString("\n\n") { section ->
        buildString {
            append('[').append(section.title).append(']')
            section.rows.forEach { (label, value) ->
                append('\n').append(label).append(": ").append(value)
            }
        }
    }

/**
 * 播放详细信息：顶部展示硬解/软解徽标与一键复制诊断信息按钮，下方分卡列出编码、分辨率、帧率、码率、丢帧与缓冲。
 * 开着时每秒重读一次；解码状态已由顶部徽标呈现，视频卡内不再重复列出同一行。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlaybackStatsPanel(read: () -> List<PlaybackStatsSection>, modifier: Modifier = Modifier) {
    val platform = LocalPikoPlatform.current
    val latestRead by rememberUpdatedState(read)
    var sections by remember { mutableStateOf(read()) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(STATS_REFRESH_MILLIS)
            sections = latestRead()
        }
    }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000L)
            copied = false
        }
    }
    val decodeBadge = remember(sections) { extractDecodeBadge(sections) }
    val decoderLabel = tr("解码", "Decoder", "デコード", "디코딩", "解碼")
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (decodeBadge != null) {
                Surface(
                    shape = CircleShape,
                    color = if (decodeBadge.isHardware) colors.tertiaryContainer else colors.surfaceContainerHigh,
                    contentColor = if (decodeBadge.isHardware) colors.onTertiaryContainer else colors.onSurfaceVariant,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (decodeBadge.isHardware) Icons.Outlined.Memory else Icons.Outlined.DeveloperBoard,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        val base = stringResource(if (decodeBadge.isHardware) Res.string.player_hw_decode else Res.string.player_sw_decode)
                        val text = decodeBadge.backend?.let { "$base ($it)" } ?: base
                        Text(text = text, style = MaterialTheme.typography.labelMedium.tabular, maxLines = 1)
                    }
                }
            } else {
                Spacer(Modifier.width(1.dp))
            }
            FilledTonalButton(
                onClick = {
                    val report = formatDiagnosticsText(sections)
                    if (report.isNotBlank()) {
                        platform.copyToClipboard("Piko Diagnostics", report)
                        copied = true
                    }
                },
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ExtraSmallContentPadding,
                modifier = Modifier.heightIn(min = 34.dp).handCursor(),
            ) {
                Icon(
                    imageVector = if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(if (copied) Res.string.player_diagnostics_copied else Res.string.player_copy_diagnostics),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                sections.forEach { section ->
                    val filteredRows = if (decodeBadge != null) {
                        section.rows.filterNot { it.first == decoderLabel }
                    } else {
                        section.rows
                    }
                    if (filteredRows.isEmpty()) return@forEach
                    Surface(
                        shape = RoundedCornerShape(SEGMENT_OUTER_CORNER),
                        color = colors.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = section.title,
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.primary,
                            )
                            filteredRows.forEach { (label, value) ->
                                Row(verticalAlignment = Alignment.Top) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.onSurfaceVariant,
                                        modifier = Modifier.width(STATS_LABEL_WIDTH),
                                    )
                                    Text(
                                        text = value,
                                        style = MaterialTheme.typography.bodyMedium.tabular,
                                        color = colors.onSurface,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val STATS_REFRESH_MILLIS = 1_000L
private val STATS_LABEL_WIDTH = 88.dp
