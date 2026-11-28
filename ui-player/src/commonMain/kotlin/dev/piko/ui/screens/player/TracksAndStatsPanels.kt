package dev.piko.ui.screens.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.SubtitlesOff
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.data.auth.AppLanguage
import dev.piko.shared.i18n.PikoLocale
import dev.piko.shared.i18n.tr
import dev.piko.shared.media.player.AiSubtitleStatus
import dev.piko.shared.media.player.AiSubtitleUiState
import dev.piko.shared.media.player.MediaTrack
import dev.piko.shared.media.player.OriginalSubtitleTrackInfo
import dev.piko.shared.media.player.PlaybackStatsSection
import dev.piko.shared.media.player.trackDisplayNames
import dev.piko.ui.components.ActionIconRow
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.connectedToggleShapes
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.player_copy_diagnostics
import dev.piko.ui.resources.player_diagnostics_copied
import dev.piko.ui.resources.player_hw_decode
import dev.piko.ui.resources.player_sw_decode
import dev.piko.ui.resources.subtitle_style_title
import dev.piko.ui.theme.LocalStatusColors
import dev.piko.ui.theme.tabular
import io.github.nihildigit.pikpak.AiSubtitleLanguage
import kotlinx.coroutines.delay

/**
 * 音轨与字幕面板（Material 3 Expressive 分段容器布局，与 `PlayerSettingsPanel` 共享 `16.dp` 网格与组件语言）：
 * - 音轨仅在多于一条时展示，带图标锚点与选中态圆角形变；
 * - 字幕单选列表与底部工具栏（本机字幕、网盘字幕、字幕样式）分层排布；
 * - AI 实时字幕整合开关、「按需启用」VAD 省额度开关、本月额度进度条与累计节省展示，以及无嵌套描边的连体快捷语言条。
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
    onOpenSubtitleStyle: (() -> Unit)? = null,
    originalSubtitleHints: List<OriginalSubtitleTrackInfo> = emptyList(),
    onSwitchToOriginalQuality: (() -> Unit)? = null,
    aiSubtitleState: AiSubtitleUiState = AiSubtitleUiState(),
    onToggleAiSubtitles: ((Boolean) -> Unit)? = null,
    onSelectAiSubtitleLanguage: ((String) -> Unit)? = null,
    onToggleAiSubtitleOnDemand: ((Boolean) -> Unit)? = null,
    onOpenAiSubtitleLanguages: (() -> Unit)? = null,
) {
    val audioLabels = trackDisplayNames(audioTracks)
    val subtitleLabels = trackDisplayNames(subtitleTracks)
    val showOriginalHint = originalSubtitleHints.isNotEmpty() && onSwitchToOriginalQuality != null
    val subtitleStyleLabel = stringResource(Res.string.subtitle_style_title)
    val subtitleToolActions = remember(onPickLocalSubtitle, onPickDriveSubtitle, onOpenSubtitleStyle, subtitleStyleLabel) {
        buildList {
            if (onPickLocalSubtitle != null) {
                add(
                    SheetAction(
                        icon = Icons.Outlined.FileOpen,
                        label = tr("本机字幕", "From device", "端末から", "기기에서", "本機字幕"),
                        onClick = onPickLocalSubtitle,
                    ),
                )
            }
            if (onPickDriveSubtitle != null) {
                add(
                    SheetAction(
                        icon = Icons.Outlined.Cloud,
                        label = tr("网盘字幕", "From drive", "ドライブから", "드라이브에서", "雲端字幕"),
                        onClick = onPickDriveSubtitle,
                    ),
                )
            }
            if (onOpenSubtitleStyle != null) {
                add(
                    SheetAction(
                        icon = Icons.Outlined.Tune,
                        label = subtitleStyleLabel,
                        onClick = onOpenSubtitleStyle,
                    ),
                )
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (audioTracks.size > 1) {
            PlayerPanelSection(tr("音轨", "Audio", "音声", "오디오", "音軌")) {
                Column(verticalArrangement = Arrangement.spacedBy(SEGMENT_GAP)) {
                    audioTracks.forEachIndexed { index, track ->
                        TrackRow(
                            label = audioLabels[index],
                            selected = track.id == selectedAudioTrackId,
                            index = index,
                            count = audioTracks.size,
                            onClick = { onSelectAudio(track) },
                            leadingIcon = Icons.Outlined.Audiotrack,
                        )
                    }
                }
            }
        }

        PlayerPanelSection(tr("字幕", "Subtitles", "字幕", "자막", "字幕")) {
            Column(verticalArrangement = Arrangement.spacedBy(SEGMENT_GAP)) {
                val trackRowsCount = 1 + subtitleTracks.size
                TrackRow(
                    label = tr("关闭", "Off", "オフ", "끄기", "關閉"),
                    selected = selectedSubtitleTrackId == null && !aiSubtitleState.on,
                    index = 0,
                    count = trackRowsCount,
                    onClick = { onSelectSubtitle(null) },
                    leadingIcon = Icons.Outlined.SubtitlesOff,
                )
                subtitleTracks.forEachIndexed { index, track ->
                    TrackRow(
                        label = subtitleLabels[index],
                        selected = track.id == selectedSubtitleTrackId,
                        index = index + 1,
                        count = trackRowsCount,
                        onClick = { onSelectSubtitle(track) },
                        leadingIcon = Icons.Outlined.Subtitles,
                    )
                }

                if (showOriginalHint && onSwitchToOriginalQuality != null) {
                    Spacer(Modifier.height(6.dp))
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
                        index = 0,
                        count = 1,
                        onClick = onSwitchToOriginalQuality,
                    )
                }

                if (subtitleToolActions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    ActionIconRow(
                        actions = subtitleToolActions,
                        onAction = { it.onClick() },
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    )
                }
            }
        }

        if (aiSubtitleState.available && onToggleAiSubtitles != null) {
            AiSubtitleSection(
                state = aiSubtitleState,
                onToggle = onToggleAiSubtitles,
                onToggleOnDemand = onToggleAiSubtitleOnDemand,
                onSelectLanguage = onSelectAiSubtitleLanguage,
                onOpenAllLanguages = onOpenAiSubtitleLanguages,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AiSubtitleSection(
    state: AiSubtitleUiState,
    onToggle: (Boolean) -> Unit,
    onToggleOnDemand: ((Boolean) -> Unit)?,
    onSelectLanguage: ((String) -> Unit)?,
    onOpenAllLanguages: (() -> Unit)?,
) {
    val quickLanguages = remember(state.supportedLanguages) {
        selectQuickAiLanguages(state.supportedLanguages)
    }
    val quota = state.quota
    val savedMinutes = state.savedSeconds / 60L
    val hasOnDemandRow = onToggleOnDemand != null
    val hasQuotaCard = (quota != null && quota.limitSeconds > 0L) || savedMinutes >= 1L
    val showLanguageControls = state.on && onSelectLanguage != null && state.supportedLanguages.isNotEmpty()
    val showMoreLanguages = showLanguageControls &&
        onOpenAllLanguages != null &&
        state.supportedLanguages.size > quickLanguages.size

    val totalSegments = 1 +
        (if (hasOnDemandRow) 1 else 0) +
        (if (hasQuotaCard) 1 else 0) +
        (if (showLanguageControls) 1 else 0) +
        (if (showMoreLanguages) 1 else 0)

    val colors = MaterialTheme.colorScheme
    val statusColors = LocalStatusColors.current
    val sectionTrailing = when {
        state.status == AiSubtitleStatus.Live -> tr("识别中", "Live", "認識中", "인식 중", "辨識中")
        state.status == AiSubtitleStatus.Connecting -> tr("连接中", "Connecting", "接続中", "연결 중", "連線中")
        else -> null
    }

    PlayerPanelSection(
        title = tr("AI 实时字幕", "AI live subtitles", "AI リアルタイム字幕", "AI 실시간 자막", "AI 即時字幕"),
        trailing = sectionTrailing,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SEGMENT_GAP)) {
            var segIndex = 0
            val errorMessage = state.errorMessage
            val statusText: String = when {
                errorMessage != null -> errorMessage
                state.status == AiSubtitleStatus.Connecting -> tr("正在同步音轨并连接服务", "Syncing audio and connecting", "音声を同期して接続中", "오디오 동기화 및 연결 중", "正在同步音訊並連線")
                state.status == AiSubtitleStatus.Live -> tr("实时识别播放音轨并生成字幕", "Transcribing playback audio in real time", "再生音声をリアルタイムで字幕化", "재생 오디오 실시간 자막 생성", "即時辨識播放音訊並產生字幕")
                else -> tr("无内置字幕时实时生成字幕（需会员）", "Real-time captions for unsubtitled videos (Premium)", "字幕のない動画をリアルタイム字幕化（プレミアム）", "자막 없는 영상 실시간 자막 생성 (프리미엄)", "無內建字幕時即時產生字幕（需會員）")
            }

            // 1. 总开关
            val headerShape = ListItemDefaults.segmentedShapes(index = segIndex++, count = totalSegments).shape
            Surface(
                shape = headerShape,
                color = if (state.on) colors.secondaryContainer else colors.surfaceContainer,
                contentColor = if (state.on) colors.onSecondaryContainer else colors.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(headerShape)
                    .selectable(selected = state.on, role = Role.Switch, onClick = { onToggle(!state.on) })
                    .heightIn(min = 60.dp)
                    .handCursor(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Translate,
                        contentDescription = null,
                        tint = if (state.on) colors.onSecondaryContainer else colors.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = tr("实时识别与翻译", "Live transcription", "リアルタイム認識", "실시간 인식", "即時辨識與翻譯"),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (state.on) FontWeight.Medium else FontWeight.Normal,
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                errorMessage != null -> colors.error
                                state.on -> colors.onSecondaryContainer.copy(alpha = 0.8f)
                                else -> colors.onSurfaceVariant
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(
                        checked = state.on,
                        onCheckedChange = onToggle,
                    )
                }
            }

            // 2. 「按需启用」（本地 Silero VAD 省额度开关，默认开启）
            if (hasOnDemandRow && onToggleOnDemand != null) {
                val onDemandShape = ListItemDefaults.segmentedShapes(index = segIndex++, count = totalSegments).shape
                Surface(
                    shape = onDemandShape,
                    color = colors.surfaceContainer,
                    contentColor = colors.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(onDemandShape)
                        .selectable(selected = state.onDemand, role = Role.Switch, onClick = { onToggleOnDemand(!state.onDemand) })
                        .heightIn(min = 56.dp)
                        .handCursor(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = if (state.onDemand) colors.primary else colors.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = tr("按需启用", "On-demand mode", "オンデマンド有効化", "필요 시 자동 활성화", "按需啟用"),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = tr(
                                    "本地检测人声，无对白片段自动休眠省额度",
                                    "Detects speech locally and pauses during silent stretches",
                                    "ローカルで音声を検出し、無発話区間は自動休止して枠を節約",
                                    "로컬 음성 감지로 대사가 없는 구간에서 자동으로 할당량 절약",
                                    "本機偵測人聲，無對白片段自動休眠節省額度",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Switch(
                            checked = state.onDemand,
                            onCheckedChange = onToggleOnDemand,
                        )
                    }
                }
            }

            // 3. 本月额度进度条 + 累计节省额度展示
            if (hasQuotaCard) {
                val quotaShape = ListItemDefaults.segmentedShapes(index = segIndex++, count = totalSegments).shape
                Surface(
                    shape = quotaShape,
                    color = colors.surfaceContainer,
                    contentColor = colors.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val quota = state.quota
                        if (quota != null && quota.limitSeconds > 0L) {
                            val usedMin = (quota.usageSeconds / 60L).coerceAtLeast(0L)
                            val limitMin = (quota.limitSeconds / 60L).coerceAtLeast(1L)
                            val progress = (quota.usageSeconds.toFloat() / quota.limitSeconds.toFloat()).coerceIn(0f, 1f)
                            val isNearLimit = progress >= 0.9f
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = tr("本月字幕额度", "Monthly subtitle quota", "今月の字幕枠", "이번 달 자막 할당량", "本月字幕額度"),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.onSurfaceVariant,
                                )
                                Text(
                                    text = tr(
                                        "已用 $usedMin / $limitMin 分钟",
                                        "$usedMin / $limitMin min used",
                                        "$usedMin / $limitMin 分使用",
                                        "$usedMin / ${limitMin}분 사용",
                                        "已用 $usedMin / $limitMin 分鐘",
                                    ),
                                    style = MaterialTheme.typography.labelMedium.tabular,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isNearLimit) colors.error else colors.onSurface,
                                )
                            }
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                                color = if (isNearLimit) colors.error else colors.primary,
                                trackColor = colors.surfaceContainerHighest,
                                strokeCap = StrokeCap.Round,
                                drawStopIndicator = {},
                            )
                        }
                        if (savedMinutes >= 1L) {
                            val savedText = formatAiSavedMinutes(savedMinutes)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = statusColors.success,
                                    modifier = Modifier.size(15.dp),
                                )
                                Text(
                                    text = tr(
                                        "Piko 已为您节省 $savedText 额度",
                                        "Piko has saved you $savedText of quota",
                                        "Piko がこれまで $savedText 分の枠を節約しました",
                                        "Piko가 지금까지 $savedText 할당량을 절약했습니다",
                                        "Piko 已為您節省 $savedText 額度",
                                    ),
                                    style = MaterialTheme.typography.labelMedium.tabular,
                                    fontWeight = FontWeight.Medium,
                                    color = statusColors.success,
                                )
                            }
                        }
                    }
                }
            }

            // 4. 目标语言紧凑连体网格：原语言 + 服务端支持的常用语言（各语言使用原生自称，短小直观零截断）
            if (showLanguageControls && onSelectLanguage != null) {
                val langShape = ListItemDefaults.segmentedShapes(index = segIndex++, count = totalSegments).shape
                val quickOptions = remember(quickLanguages) {
                    buildList {
                        add("" to tr("原语言", "Original", "原言語", "원본", "原語言"))
                        quickLanguages.forEach { lang -> add(lang.code to aiLanguageNativeEndonym(lang)) }
                    }
                }
                val buttonColors = ToggleButtonDefaults.colors(
                    containerColor = colors.surfaceContainerHigh,
                    contentColor = colors.onSurface,
                    checkedContainerColor = colors.secondaryContainer,
                    checkedContentColor = colors.onSecondaryContainer,
                )
                Surface(
                    shape = langShape,
                    color = colors.surfaceContainer,
                    contentColor = colors.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = tr("目标语言", "Target language", "翻訳先の言語", "대상 언어", "目標語言"),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            quickOptions.chunked(3).forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                                ) {
                                    row.forEachIndexed { idx, (code, label) ->
                                        val selected = if (code.isEmpty()) {
                                            state.language.isBlank()
                                        } else {
                                            aiLanguageCodesEqual(state.language, code)
                                        }
                                        ToggleButton(
                                            checked = selected,
                                            onCheckedChange = { if (it) onSelectLanguage(code) },
                                            colors = buttonColors,
                                            shapes = connectedToggleShapes(idx, row.size),
                                            contentPadding = PaddingValues(horizontal = 6.dp),
                                            modifier = Modifier.weight(1f).handCursor(),
                                        ) {
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.labelLarge,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 5. 更多语言钻取行
            if (showMoreLanguages && onOpenAllLanguages != null) {
                val selectedOutsideQuick = state.language.takeIf { code ->
                    code.isNotBlank() && quickLanguages.none { aiLanguageCodesEqual(it.code, code) }
                }?.let { code ->
                    state.supportedLanguages.firstOrNull { aiLanguageCodesEqual(it.code, code) }
                        ?.let(::aiLanguageNativeEndonym)
                        ?: aiLanguageNativeEndonym(AiSubtitleLanguage(code = code, name = code))
                }
                val moreLabel = if (selectedOutsideQuick != null) {
                    tr(
                        "更多语言（当前：$selectedOutsideQuick）",
                        "More languages ($selectedOutsideQuick)",
                        "その他の言語（現在：$selectedOutsideQuick）",
                        "더 많은 언어 (현재: $selectedOutsideQuick)",
                        "更多語言（目前：$selectedOutsideQuick）",
                    )
                } else {
                    tr(
                        "更多语言（共 ${state.supportedLanguages.size} 种）",
                        "More languages (${state.supportedLanguages.size})",
                        "その他の言語（全 ${state.supportedLanguages.size} 言語）",
                        "더 많은 언어 (${state.supportedLanguages.size}개)",
                        "更多語言（共 ${state.supportedLanguages.size} 種）",
                    )
                }
                SegmentedPickerRow(
                    icon = Icons.Outlined.Search,
                    label = moreLabel,
                    index = segIndex,
                    count = totalSegments,
                    onClick = onOpenAllLanguages,
                    showChevron = true,
                )
            }
        }
    }
}

private fun formatAiSavedMinutes(minutes: Long): String {
    val mins = minutes.coerceAtLeast(1L)
    return if (mins >= 60L) {
        val hours = mins / 60L
        val remMin = mins % 60L
        if (remMin == 0L) {
            tr("$hours 小时", "$hours hr", "$hours 時間", "${hours}시간", "$hours 小時")
        } else {
            tr("$hours 小时 $remMin 分钟", "${hours}h ${remMin}m", "$hours 時間 $remMin 分", "${hours}시간 ${remMin}분", "$hours 小時 $remMin 分鐘")
        }
    } else {
        tr("$mins 分钟", "$mins min", "$mins 分", "${mins}분", "$mins 分鐘")
    }
}

/**
 * AI 字幕全量语言选择子面板：顶部常驻搜索框，下方用 LazyColumn 分组呈现原语言、常用语言与全部语言。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AiSubtitleLanguagePanel(
    state: AiSubtitleUiState,
    onSelectLanguage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val trimmedQuery = query.trim()
    val colors = MaterialTheme.colorScheme

    val partitioned = remember(state.supportedLanguages, trimmedQuery, PikoLocale.effectiveLanguage) {
        val all = state.supportedLanguages
        val filtered = if (trimmedQuery.isEmpty()) {
            all
        } else {
            all.filter { lang -> matchesLanguageQuery(lang, trimmedQuery) }
        }
        val common = ArrayList<AiSubtitleLanguage>()
        val others = ArrayList<AiSubtitleLanguage>()
        for (lang in filtered) {
            if (commonAiLanguageRank(lang) != Int.MAX_VALUE) common += lang else others += lang
        }
        common.sortBy(::commonAiLanguageRank)
        others.sortBy { aiLanguageNativeEndonym(it).lowercase() }
        val showOriginal = trimmedQuery.isEmpty() ||
            "原语言".contains(trimmedQuery, ignoreCase = true) ||
            "原語言".contains(trimmedQuery, ignoreCase = true) ||
            "original".contains(trimmedQuery, ignoreCase = true) ||
            "元の言語".contains(trimmedQuery, ignoreCase = true) ||
            "원본".contains(trimmedQuery, ignoreCase = true)
        Triple(showOriginal, common, others)
    }
    val (showOriginal, commonLanguages, otherLanguages) = partitioned

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            placeholder = {
                Text(tr("搜索语言名称或代码", "Search language or code", "言語名やコードで検索", "언어 이름 또는 코드 검색", "搜尋語言名稱或代碼"))
            },
            leadingIcon = {
                Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(20.dp))
            },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { query = "" }, modifier = Modifier.handCursor()) {
                        Icon(Icons.Filled.Close, contentDescription = tr("清空", "Clear", "クリア", "지우기", "清除"), modifier = Modifier.size(18.dp))
                    }
                }
            } else {
                null
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = colors.surfaceContainerHigh,
                unfocusedContainerColor = colors.surfaceContainer,
                focusedBorderColor = colors.primary,
                unfocusedBorderColor = colors.outlineVariant.copy(alpha = 0.4f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(SEGMENT_GAP),
        ) {
            if (showOriginal) {
                item(key = "ai_lang_original") {
                    TrackRow(
                        label = tr("原语言（不翻译）", "Original language", "元の言語（翻訳なし）", "원본 언어 (번역 안 함)", "原語言（不翻譯）"),
                        selected = state.language.isBlank(),
                        index = 0,
                        count = 1,
                        onClick = { onSelectLanguage("") },
                    )
                }
            }

            if (commonLanguages.isNotEmpty()) {
                item(key = "ai_lang_common_header") {
                    Text(
                        text = tr("常用语言", "Common languages", "よく使う言語", "자주 쓰는 언어", "常用語言"),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                itemsIndexed(commonLanguages, key = { _, lang -> "common_${lang.code}" }) { idx, lang ->
                    TrackRow(
                        label = aiLanguageNativeEndonym(lang),
                        selected = aiLanguageCodesEqual(state.language, lang.code),
                        index = idx,
                        count = commonLanguages.size,
                        onClick = { onSelectLanguage(lang.code) },
                    )
                }
            }

            if (otherLanguages.isNotEmpty()) {
                item(key = "ai_lang_all_header") {
                    Text(
                        text = tr("全部语言", "All languages", "すべての言語", "모든 언어", "全部語言"),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                itemsIndexed(otherLanguages, key = { _, lang -> "all_${lang.code}" }) { idx, lang ->
                    TrackRow(
                        label = aiLanguageNativeEndonym(lang),
                        selected = aiLanguageCodesEqual(state.language, lang.code),
                        index = idx,
                        count = otherLanguages.size,
                        onClick = { onSelectLanguage(lang.code) },
                    )
                }
            }

            if (!showOriginal && commonLanguages.isEmpty() && otherLanguages.isEmpty()) {
                item(key = "ai_lang_empty") {
                    Text(
                        text = tr("未找到匹配的语言", "No matching languages", "一致する言語が見つかりません", "일치하는 언어가 없습니다", "找不到相符的語言"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 24.dp),
                    )
                }
            }
        }
    }
}

private val COMMON_AI_LANGUAGE_PRIORITY = listOf(
    "zh-cn", "zh-hans", "zh",
    "zh-tw", "zh-hant", "zh-hk", "zh-mo",
    "yue",
    "en", "ja", "ko",
    "fr", "de", "es", "ru", "pt", "it", "vi", "th", "id", "ar",
)

internal fun normalizeAiLanguageCode(code: String): String =
    code.trim().lowercase().replace('_', '-')

internal fun aiLanguageCodesEqual(a: String, b: String): Boolean =
    normalizeAiLanguageCode(a) == normalizeAiLanguageCode(b)

private fun isTraditionalChineseLanguage(lang: AiSubtitleLanguage): Boolean {
    val norm = normalizeAiLanguageCode(lang.code)
    if (norm in TRADITIONAL_CHINESE_CODES ||
        norm.startsWith("zh-hant-") ||
        norm.startsWith("zh-tw-") ||
        norm.startsWith("zh-hk-") ||
        norm.startsWith("zh-mo-")
    ) {
        return true
    }
    if (norm == "zh" || norm.startsWith("zh-")) {
        val nameLower = lang.name.lowercase()
        return "traditional" in nameLower || "繁" in lang.name || "taiwan" in nameLower || "hong kong" in nameLower
    }
    return false
}

private fun isSimplifiedChineseLanguage(lang: AiSubtitleLanguage): Boolean {
    if (isTraditionalChineseLanguage(lang)) return false
    val norm = normalizeAiLanguageCode(lang.code)
    return norm in SIMPLIFIED_CHINESE_CODES || norm.startsWith("zh-hans-") || norm.startsWith("zh-cn-") || norm.startsWith("zh-sg-")
}

private val SIMPLIFIED_CHINESE_CODES = setOf("zh-cn", "zh-hans", "zh-sg", "zh-chs", "chs", "zh")
private val TRADITIONAL_CHINESE_CODES = setOf("zh-tw", "zh-hant", "zh-hk", "zh-mo", "zh-cht", "cht")

private fun commonAiLanguageRank(lang: AiSubtitleLanguage): Int {
    if (isTraditionalChineseLanguage(lang)) return COMMON_AI_LANGUAGE_PRIORITY.indexOf("zh-tw")
    if (isSimplifiedChineseLanguage(lang)) return COMMON_AI_LANGUAGE_PRIORITY.indexOf("zh-cn")
    val norm = normalizeAiLanguageCode(lang.code)
    val idx = COMMON_AI_LANGUAGE_PRIORITY.indexOfFirst { tag ->
        norm == tag || (tag != "zh" && norm.startsWith("$tag-"))
    }
    return if (idx >= 0) idx else Int.MAX_VALUE
}

/**
 * 从服务端真实支持的语言列表中按固定顺序匹配常用快捷语言（简中、繁中、英语、日语、韩语）；
 * 服务端未单独提供繁中（如仅返回 `zh`）时自然保留 4 个目标语言（含「原语言」共 5 格），不伪造服务端不支持的代码。
 */
internal fun selectQuickAiLanguages(
    supported: List<AiSubtitleLanguage>,
): List<AiSubtitleLanguage> {
    if (supported.isEmpty()) return emptyList()
    val picked = ArrayList<AiSubtitleLanguage>(5)
    val seen = HashSet<String>()

    fun addIfNew(match: AiSubtitleLanguage?) {
        if (match != null && seen.add(normalizeAiLanguageCode(match.code))) {
            picked += match
        }
    }

    val simplified = listOf("zh-cn", "zh-hans", "zh-sg", "zh-chs", "zh").firstNotNullOfOrNull { tag ->
        supported.firstOrNull { !isTraditionalChineseLanguage(it) && normalizeAiLanguageCode(it.code) == tag }
    } ?: supported.firstOrNull(::isSimplifiedChineseLanguage)
    addIfNew(simplified)

    val traditional = listOf("zh-tw", "zh-hant", "zh-hk", "zh-mo", "zh-cht").firstNotNullOfOrNull { tag ->
        supported.firstOrNull {
            val norm = normalizeAiLanguageCode(it.code)
            norm == tag || norm.startsWith("$tag-")
        }
    } ?: supported.firstOrNull(::isTraditionalChineseLanguage)
    addIfNew(traditional)

    for (tag in listOf("en", "ja", "ko")) {
        val match = supported.firstOrNull { normalizeAiLanguageCode(it.code) == tag }
            ?: supported.firstOrNull { normalizeAiLanguageCode(it.code).startsWith("$tag-") }
        addIfNew(match)
    }
    return picked
}

/** 使用各语言原生自称（Endonym），遵循 i18n 原则让每种语言以自身文字显示自己。 */
internal fun aiLanguageNativeEndonym(lang: AiSubtitleLanguage): String {
    if (isTraditionalChineseLanguage(lang)) return "繁體中文"
    if (isSimplifiedChineseLanguage(lang)) return "简体中文"
    val norm = normalizeAiLanguageCode(lang.code)
    val primary = norm.substringBefore('-')
    return AI_LANGUAGE_ENDONYMS[norm]
        ?: AI_LANGUAGE_ENDONYMS[primary]
        ?: lang.name.ifBlank { lang.code }
}

private val AI_LANGUAGE_ENDONYMS = mapOf(
    "yue" to "粵語",
    "en" to "English",
    "ja" to "日本語",
    "ko" to "한국어",
    "fr" to "Français",
    "de" to "Deutsch",
    "es" to "Español",
    "ru" to "Русский",
    "pt" to "Português",
    "it" to "Italiano",
    "vi" to "Tiếng Việt",
    "th" to "ไทย",
    "id" to "Bahasa Indonesia",
    "ms" to "Bahasa Melayu",
    "ar" to "العربية",
    "ast" to "Asturianu",
    "ceb" to "Cebuano",
    "jv" to "Basa Jawa",
    "ky" to "Кыргызча",
    "tg" to "Тоҷикӣ",
    "nl" to "Nederlands",
    "pl" to "Polski",
    "tr" to "Türkçe",
    "uk" to "Українська",
    "hi" to "हिन्दी",
    "sv" to "Svenska",
    "da" to "Dansk",
    "fi" to "Suomi",
    "no" to "Norsk",
    "nb" to "Norsk",
    "nn" to "Nynorsk",
    "cs" to "Čeština",
    "ro" to "Română",
    "hu" to "Magyar",
    "el" to "Ελληνικά",
    "he" to "עברית",
    "iw" to "עברית",
    "fa" to "فارسی",
    "tl" to "Filipino",
    "fil" to "Filipino",
    "ca" to "Català",
    "bg" to "Български",
    "hr" to "Hrvatski",
    "sk" to "Slovenčina",
    "sl" to "Slovenščina",
    "sr" to "Српски",
    "et" to "Eesti",
    "lv" to "Latviešu",
    "lt" to "Lietuvių",
    "bn" to "বাংলা",
    "ta" to "தமிழ்",
    "te" to "తెలుగు",
    "ur" to "اردو",
    "sw" to "Kiswahili",
    "af" to "Afrikaans",
    "gu" to "ગુજરાતી",
    "kn" to "ಕನ್ನಡ",
    "ml" to "മലയാളം",
    "mr" to "मराठी",
    "pa" to "ਪੰਜਾਬੀ",
    "ne" to "नेपाली",
    "si" to "සිංහල",
    "my" to "မြန်မာ",
    "km" to "ខ្មែរ",
    "lo" to "ລາວ",
    "ka" to "ქართული",
    "hy" to "Հայերեն",
    "az" to "Azərbaycan",
    "kk" to "Қазақша",
    "uz" to "Oʻzbekcha",
    "mn" to "Монгол",
    "ga" to "Gaeilge",
    "cy" to "Cymraeg",
    "eu" to "Euskara",
    "gl" to "Galego",
    "is" to "Íslenska",
    "sq" to "Shqip",
    "mk" to "Македонски",
    "bs" to "Bosanski",
    "be" to "Беларуская",
)

private fun aiLanguageLocalizedSearchAlias(lang: AiSubtitleLanguage): String {
    if (isTraditionalChineseLanguage(lang)) return "繁体中文 繁體中文 Traditional Chinese"
    if (isSimplifiedChineseLanguage(lang)) return "简体中文 簡體中文 Simplified Chinese"
    val primary = normalizeAiLanguageCode(lang.code).substringBefore('-')
    return when (primary) {
        "yue" -> "粤语 粵語 广东话 廣東話 Cantonese"
        "en" -> "英语 英語 英文"
        "ja" -> "日语 日語 日文"
        "ko" -> "韩语 韓語 朝鲜语"
        "fr" -> "法语 法語"
        "de" -> "德语 德語"
        "es" -> "西班牙语 西班牙語"
        "ru" -> "俄语 俄語"
        "pt" -> "葡萄牙语 葡萄牙語"
        "it" -> "意大利语 義大利語"
        "vi" -> "越南语 越南語"
        "th" -> "泰语 泰語"
        "id" -> "印尼语 印尼語 印度尼西亚语"
        "ms" -> "马来语 馬來語"
        "ar" -> "阿拉伯语 阿拉伯語"
        "nl" -> "荷兰语 荷蘭語"
        "pl" -> "波兰语 波蘭語"
        "tr" -> "土耳其语 土耳其語"
        "uk" -> "乌克兰语 烏克蘭語"
        "hi" -> "印地语 印地語"
        else -> ""
    }
}

internal fun matchesLanguageQuery(lang: AiSubtitleLanguage, query: String): Boolean {
    if (lang.code.contains(query, ignoreCase = true)) return true
    if (normalizeAiLanguageCode(lang.code).contains(query.trim().replace('_', '-'), ignoreCase = true)) return true
    if (lang.name.contains(query, ignoreCase = true)) return true
    if (aiLanguageNativeEndonym(lang).contains(query, ignoreCase = true)) return true
    if (aiLanguageLocalizedSearchAlias(lang).contains(query, ignoreCase = true)) return true
    return false
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TrackRow(
    label: String,
    selected: Boolean,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    leadingIcon: ImageVector? = null,
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
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp).size(20.dp),
                )
            }
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
