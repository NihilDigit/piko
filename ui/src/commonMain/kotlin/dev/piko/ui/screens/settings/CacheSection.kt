package dev.piko.ui.screens.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoDelete
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SwipeVertical
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.data.auth.CachePolicyDefaults
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.i18n.enCount
import dev.piko.shared.i18n.tr
import dev.piko.shared.media.cache.CacheCategory
import dev.piko.shared.media.cache.CacheSummary
import dev.piko.shared.media.cache.PikoCacheManager
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.common_cancel
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.LocalPikoMotion
import dev.piko.ui.theme.LocalWindowVisible
import dev.piko.ui.theme.isEInk
import dev.piko.ui.theme.tabular
import kotlinx.coroutines.launch

/**
 * Telegram 风格「存储与缓存」管理分区：
 * - 多段比例进度条直观呈现 5 类本地缓存占比与总占用；
 * - 逐类展示精确体积与文件数，支持按类清理（播放缓存带二次确认）与一键全清；
 * - 配置设备本地容量上限与自动过期清理天数，联动 [PikoCacheManager.applyPolicy]。
 */
@Composable
internal fun CacheSectionContent(
    cacheManager: PikoCacheManager,
    preferences: PikoUserPreferences,
    snackbarHostState: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    val summary by cacheManager.summary.collectAsStateWithLifecycle()
    val maxBytes by preferences.cacheMaxBytesFlow.collectAsStateWithLifecycle(
        initialValue = preferences.currentSnapshot.cacheMaxBytes,
    )
    val maxAgeDays by preferences.cacheMaxAgeDaysFlow.collectAsStateWithLifecycle(
        initialValue = preferences.currentSnapshot.cacheMaxAgeDays,
    )

    var showClearAllDialog by remember { mutableStateOf(false) }
    var confirmClearPlayback by remember { mutableStateOf(false) }
    var showCapDialog by remember { mutableStateOf(false) }
    var showTtlDialog by remember { mutableStateOf(false) }

    LaunchedEffect(cacheManager) {
        cacheManager.refresh()
    }

    val colors = MaterialTheme.colorScheme
    val eInk = LocalAppearance.current.isEInk
    val categoryColors = remember(colors, eInk) {
        if (eInk) {
            mapOf(
                CacheCategory.PLAYBACK to Color(0xFF000000),
                CacheCategory.IMAGES to Color(0xFF3A3A3A),
                CacheCategory.STORYBOARD to Color(0xFF6B6B6B),
                CacheCategory.CLIPS to Color(0xFF9A9A9A),
                CacheCategory.TREE_INDEX to Color(0xFFC8C8C8),
            )
        } else {
            mapOf(
                CacheCategory.PLAYBACK to colors.primary,
                CacheCategory.IMAGES to colors.tertiary,
                CacheCategory.STORYBOARD to colors.secondary,
                CacheCategory.CLIPS to colors.primary.copy(alpha = 0.55f),
                CacheCategory.TREE_INDEX to colors.outlineVariant,
            )
        }
    }

    val busy = summary.isScanning || summary.isClearing

    SettingsGroup(null) {
        CacheOverviewCard(
            summary = summary,
            categoryColors = categoryColors,
            busy = busy,
            onRefresh = { scope.launch { cacheManager.refresh() } },
            onRequestClearAll = { showClearAllDialog = true },
        )
    }

    SettingsGroup(tr("缓存明细", "Breakdown", "キャッシュ内訳", "캐시 세부 내역", "快取明細")) {
        CacheCategory.entries.forEach { category ->
            val stats = summary.statsOf(category)
            val color = categoryColors[category] ?: colors.outlineVariant
            val supportingText = formatCacheCategorySupporting(category, stats)

            SettingsRow(
                title = cacheCategoryLabel(category),
                supporting = supportingText,
                leading = {
                    CategoryBadgeLeading(
                        icon = cacheCategoryIcon(category),
                        dotColor = color,
                    )
                },
                trailing = if (stats.clearable) {
                    {
                        SettingsRowButton(
                            label = tr("清理", "Clear", "消去", "지우기", "清理"),
                            onClick = {
                                if (category == CacheCategory.PLAYBACK) {
                                    confirmClearPlayback = true
                                } else {
                                    scope.launch {
                                        val freed = cacheManager.clearCategory(category)
                                        snackbarHostState.showSnackbar(freedNoticeText(freed))
                                    }
                                }
                            },
                            enabled = !busy && stats.bytes > 0L,
                            primary = false,
                        )
                    }
                } else {
                    null
                },
            )
        }
    }

    SettingsGroup(tr("自动清理策略", "Auto-cleanup policy", "自動クリーンアップ", "자동 정리 정책", "自動清理策略")) {
        SettingsNavigationRow(
            icon = Icons.Outlined.DataUsage,
            title = tr("缓存容量上限", "Maximum cache size", "キャッシュ容量の上限", "최대 캐시 용량", "快取容量上限"),
            supporting = formatCacheCapLabel(maxBytes),
            onClick = { showCapDialog = true },
            trailingIcon = null,
        )
        SettingsNavigationRow(
            icon = Icons.Outlined.AutoDelete,
            title = tr("自动清理过期缓存", "Keep unused cache", "未使用キャッシュの保持期間", "미사용 캐시 보관 기간", "自動清理過期快取"),
            supporting = formatCacheTtlLabel(maxAgeDays),
            onClick = { showTtlDialog = true },
            trailingIcon = null,
        )
    }

    if (showClearAllDialog) {
        PikoDialog(
            onDismissRequest = { showClearAllDialog = false },
            icon = { androidx.compose.material3.Icon(Icons.Outlined.CleaningServices, contentDescription = null) },
            title = { Text(tr("清理全部缓存？", "Clear all cache?", "すべてのキャッシュを消去しますか？", "모든 캐시를 지울까요?", "清理全部快取？")) },
            text = {
                Text(
                    tr(
                        "将清理缩略图、播放缓存、进度条预览图与信息流缓存（约 ${summary.clearableBytes.toReadableSize()}）。本地目录树索引、正在进行的下载任务与当前播放流不会受影响。",
                        "Clears thumbnails, playback cache, seekbar previews, and clip feed cache (${summary.clearableBytes.toReadableSize()}). The local tree index, active downloads, and current playback are preserved.",
                        "サムネイル、再生キャッシュ、シークバープレビュー、フィードキャッシュ（約 ${summary.clearableBytes.toReadableSize()}）を消去します。ローカルインデックス、進行中のダウンロード、再生中のストリームは保持されます。",
                        "미리 보기, 재생 캐시, 탐색 미리보기, 피드 캐시(약 ${summary.clearableBytes.toReadableSize()})를 지웁니다. 로컬 인덱스, 진행 중인 다운로드 및 현재 재생은 유지됩니다.",
                        "將清理縮圖、播放快取、進度條預覽圖與資訊流快取（約 ${summary.clearableBytes.toReadableSize()}）。本機目錄樹索引、進行中的下載任務與目前播放串流不受影響。",
                    ),
                )
            },
            confirmButton = {
                PikoDialogConfirm(
                    label = tr("清理", "Clear", "消去", "지우기", "清理"),
                    destructive = true,
                    onClick = {
                        showClearAllDialog = false
                        scope.launch {
                            val freed = cacheManager.clearAll()
                            snackbarHostState.showSnackbar(freedNoticeText(freed))
                        }
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text(stringResource(Res.string.common_cancel))
                }
            },
        )
    }

    if (confirmClearPlayback) {
        val playbackBytes = summary.statsOf(CacheCategory.PLAYBACK).bytes
        PikoDialog(
            onDismissRequest = { confirmClearPlayback = false },
            icon = { androidx.compose.material3.Icon(Icons.Outlined.PlayCircle, contentDescription = null) },
            title = { Text(tr("清理视频播放缓存？", "Clear playback cache?", "動画再生キャッシュを消去しますか？", "동영상 재생 캐시를 지울까요?", "清理影片播放快取？")) },
            text = {
                Text(
                    tr(
                        "将释放约 ${playbackBytes.toReadableSize()} 的已缓存视频片段。正在进行的下载任务与当前播放流不受影响。",
                        "Frees about ${playbackBytes.toReadableSize()} of cached playback chunks. Active downloads and current playback are not affected.",
                        "約 ${playbackBytes.toReadableSize()} の再生キャッシュを解放します。進行中のダウンロードと再生中のストリームには影響しません。",
                        "약 ${playbackBytes.toReadableSize()}의 재생 캐시를 확보합니다. 진행 중인 다운로드와 현재 재생에는 영향을 주지 않습니다.",
                        "將釋放約 ${playbackBytes.toReadableSize()} 的已快取影片片段。進行中的下載任務與目前播放串流不受影響。",
                    ),
                )
            },
            confirmButton = {
                PikoDialogConfirm(
                    label = tr("清理", "Clear", "消去", "지우기", "清理"),
                    destructive = true,
                    onClick = {
                        confirmClearPlayback = false
                        scope.launch {
                            val freed = cacheManager.clearCategory(CacheCategory.PLAYBACK)
                            snackbarHostState.showSnackbar(freedNoticeText(freed))
                        }
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmClearPlayback = false }) {
                    Text(stringResource(Res.string.common_cancel))
                }
            },
        )
    }

    if (showCapDialog) {
        SettingsChoiceDialog(
            icon = Icons.Outlined.DataUsage,
            title = tr("缓存容量上限", "Maximum cache size", "キャッシュ容量の上限", "최대 캐시 용량", "快取容量上限"),
            description = tr(
                "仅应用于当前设备；超出上限时自动优先淘汰最久未访问的播放与切片缓存，不影响正在进行的下载。",
                "Applies to this device only. When exceeded, least-recently-used playback and clip caches are pruned automatically without touching active downloads.",
                "この端末のみに適用されます。上限を超えると古い再生やクリップのキャッシュから自動削除され、進行中のダウンロードには影響しません。",
                "이 기기에만 적용됩니다. 한도를 초과하면 오래된 재생 및 클립 캐시부터 자동 정리되며 진행 중인 다운로드는 유지됩니다.",
                "僅套用於目前裝置；超出上限時自動優先淘汰最久未存取的播放與切片快取，不影響進行中的下載。",
            ),
            onDismiss = { showCapDialog = false },
        ) {
            CachePolicyDefaults.MaxBytesChoices.forEach { bytes ->
                SettingsChoiceOption(
                    title = formatCacheCapLabel(bytes),
                    selected = maxBytes == bytes,
                    onClick = {
                        showCapDialog = false
                        scope.launch {
                            preferences.setCacheMaxBytes(bytes)
                            cacheManager.applyPolicy(bytes, maxAgeDays)
                        }
                    },
                )
            }
        }
    }

    if (showTtlDialog) {
        SettingsChoiceDialog(
            icon = Icons.Outlined.AutoDelete,
            title = tr("自动清理过期缓存", "Keep unused cache", "未使用キャッシュの保持期間", "미사용 캐시 보관 기간", "自動清理過期快取"),
            description = tr(
                "仅应用于当前设备；超过指定天数未访问的缓存条目会在后台维护时自动回收。",
                "Applies to this device only. Cached entries unused longer than this duration are pruned during background maintenance.",
                "この端末のみに適用されます。指定日数を超えて未使用のキャッシュはバックグラウンドで自動的に解放されます。",
                "이 기기에만 적용됩니다. 지정한 기간 동안 사용하지 않은 캐시는 백그라운드에서 자동 정리됩니다.",
                "僅套用於目前裝置；超過指定天數未存取的快取項目會在背景維護時自動回收。",
            ),
            onDismiss = { showTtlDialog = false },
        ) {
            CachePolicyDefaults.MaxAgeDaysChoices.forEach { days ->
                SettingsChoiceOption(
                    title = formatCacheTtlLabel(days),
                    selected = maxAgeDays == days,
                    onClick = {
                        showTtlDialog = false
                        scope.launch {
                            preferences.setCacheMaxAgeDays(days)
                            cacheManager.applyPolicy(maxBytes, days)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun CacheOverviewCard(
    summary: CacheSummary,
    categoryColors: Map<CacheCategory, Color>,
    busy: Boolean,
    onRefresh: () -> Unit,
    onRequestClearAll: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val eInk = LocalAppearance.current.isEInk
    val animateProgress = !LocalPikoMotion.current.reduced && LocalWindowVisible.current
    val style = settingsStyle()

    SettingsCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = style.horizontalPadding, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = summary.totalBytes.toReadableSize(),
                        style = MaterialTheme.typography.headlineMedium.tabular,
                        color = colors.onSurface,
                    )
                    Text(
                        text = formatCacheClearableSubtitle(summary.clearableBytes),
                        style = style.supportingStyle.tabular,
                        color = colors.onSurfaceVariant,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TooltipIconButton(
                        icon = Icons.Outlined.Refresh,
                        label = tr("重新统计", "Refresh", "再計測", "새로 고침", "重新統計"),
                        onClick = onRefresh,
                        enabled = !busy,
                    )
                    val clearAllEnabled = !busy && summary.clearableBytes > 0L
                    FilledTonalButton(
                        onClick = onRequestClearAll,
                        enabled = clearAllEnabled,
                        colors = if (eInk) {
                            ButtonDefaults.filledTonalButtonColors(
                                containerColor = Color.White,
                                contentColor = Color.Black,
                                disabledContainerColor = Color.White,
                                disabledContentColor = Color(0xFF555555),
                            )
                        } else {
                            ButtonDefaults.filledTonalButtonColors()
                        },
                        border = if (eInk) {
                            BorderStroke(1.dp, if (clearAllEnabled) Color.Black else Color(0xFF555555))
                        } else {
                            null
                        },
                    ) {
                        Text(tr("清理全部缓存", "Clear all", "すべて消去", "모두 지우기", "清理全部快取"), maxLines = 1)
                    }
                }
            }

            if (busy && animateProgress) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp).clip(CircleShape),
                )
            }

            // Telegram 风格多段横向占比条
            CacheProportionBar(
                summary = summary,
                categoryColors = categoryColors,
                eInk = eInk,
                modifier = Modifier.fillMaxWidth().height(10.dp),
            )

            val spaceLine = formatCacheSpaceLine(summary)
            if (spaceLine.isNotEmpty()) {
                Text(
                    text = spaceLine,
                    style = style.supportingStyle.tabular,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CacheProportionBar(
    summary: CacheSummary,
    categoryColors: Map<CacheCategory, Color>,
    eInk: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val trackColor = if (eInk) Color.White else colors.surfaceContainerHighest
    val segments = remember(summary, categoryColors) {
        val total = summary.totalBytes
        if (total <= 0L) {
            emptyList()
        } else {
            CacheCategory.entries.mapNotNull { category ->
                val bytes = summary.statsOf(category).bytes
                if (bytes <= 0L) null else (bytes.toFloat() / total.toFloat()) to (categoryColors[category] ?: colors.primary)
            }
        }
    }
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(trackColor)
            .then(if (eInk) Modifier.border(1.dp, colors.outline, CircleShape) else Modifier),
    ) {
        if (segments.isNotEmpty()) {
            Canvas(modifier = Modifier.fillMaxWidth().height(10.dp)) {
                val gapPx = if (segments.size > 1) 2.dp.toPx() else 0f
                val totalGap = gapPx * (segments.size - 1)
                val usableWidth = (size.width - totalGap).coerceAtLeast(1f)
                val minSegmentPx = 3.dp.toPx().coerceAtMost(usableWidth / segments.size)
                val rawWidths = segments.map { (fraction, _) -> (usableWidth * fraction).coerceAtLeast(minSegmentPx) }
                val rawSum = rawWidths.sum().coerceAtLeast(1f)
                val scale = usableWidth / rawSum
                var x = 0f
                val radius = CornerRadius(size.height / 2f, size.height / 2f)
                segments.forEachIndexed { index, (_, color) ->
                    val segWidth = rawWidths[index] * scale
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x, 0f),
                        size = Size(segWidth, size.height),
                        cornerRadius = radius,
                    )
                    x += segWidth + gapPx
                }
            }
        }
    }
}

@Composable
private fun CategoryBadgeLeading(
    icon: ImageVector,
    dotColor: Color,
) {
    val style = settingsStyle()
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(style.iconSize),
        )
    }
}

internal fun cacheCategoryLabel(category: CacheCategory): String = when (category) {
    CacheCategory.IMAGES -> tr("图片缩略图", "Thumbnails & images", "サムネイルと画像", "미리 보기 이미지", "圖片縮圖")
    CacheCategory.PLAYBACK -> tr("视频播放缓存", "Playback cache", "動画再生キャッシュ", "동영상 재생 캐시", "影片播放快取")
    CacheCategory.STORYBOARD -> tr("进度条预览图", "Seekbar previews", "シークバープレビュー", "탐색 미리보기", "進度條預覽圖")
    CacheCategory.CLIPS -> tr("信息流缓存", "Clips feed cache", "フィードキャッシュ", "피드 캐시", "資訊流快取")
    CacheCategory.TREE_INDEX -> tr("本地目录树索引", "Local tree index", "ローカルツリーインデックス", "로컬 디렉터리 인덱스", "本機目錄樹索引")
}

internal fun formatCacheClearableSubtitle(clearableBytes: Long): String = tr(
    "可清理 ${clearableBytes.toReadableSize()}，正在进行的下载不受影响",
    "${clearableBytes.toReadableSize()} clearable, active downloads untouched",
    "消去可能 ${clearableBytes.toReadableSize()}、ダウンロード中の項目は保護",
    "정리 가능 ${clearableBytes.toReadableSize()}, 진행 중인 다운로드 제외",
    "可清理 ${clearableBytes.toReadableSize()}，進行中的下載不受影響",
)

internal fun formatCacheCategorySupporting(
    category: CacheCategory,
    stats: dev.piko.shared.media.cache.CacheCategoryStats,
): String = when (category) {
    CacheCategory.TREE_INDEX -> tr(
        "${stats.bytes.toReadableSize()}，本地索引，不随缓存清理",
        "${stats.bytes.toReadableSize()}, local index (preserved on clear)",
        "${stats.bytes.toReadableSize()}、ローカルインデックス（クリア対象外）",
        "${stats.bytes.toReadableSize()}, 로컬 인덱스 (정리 대상 제외)",
        "${stats.bytes.toReadableSize()}，本機索引，不隨快取清理",
    )
    CacheCategory.STORYBOARD -> {
        val desc = tr(
            "480P 视频进度条悬停与速览缩略图",
            "480P seekbar hover & scrub thumbnails",
            "480P シークバーホバーと早見サムネイル",
            "480P 탐색 막대 호버 및 미리보기 썸네일",
            "480P 影片進度條懸停與速覽縮圖",
        )
        if (stats.fileCount > 0) {
            tr(
                "${stats.bytes.toReadableSize()}，${stats.fileCount} 个文件，$desc",
                "${stats.bytes.toReadableSize()}, ${enCount(stats.fileCount, "file", "files")}, $desc",
                "${stats.bytes.toReadableSize()}、${stats.fileCount} 件、$desc",
                "${stats.bytes.toReadableSize()}, 파일 ${stats.fileCount}개, $desc",
                "${stats.bytes.toReadableSize()}，${stats.fileCount} 個檔案，$desc",
            )
        } else {
            tr(
                "${stats.bytes.toReadableSize()}，$desc",
                "${stats.bytes.toReadableSize()}, $desc",
                "${stats.bytes.toReadableSize()}、$desc",
                "${stats.bytes.toReadableSize()}, $desc",
                "${stats.bytes.toReadableSize()}，$desc",
            )
        }
    }
    else -> if (stats.fileCount > 0) {
        tr(
            "${stats.bytes.toReadableSize()}，${stats.fileCount} 个文件",
            "${stats.bytes.toReadableSize()}, ${enCount(stats.fileCount, "file", "files")}",
            "${stats.bytes.toReadableSize()}、${stats.fileCount} 件",
            "${stats.bytes.toReadableSize()}, 파일 ${stats.fileCount}개",
            "${stats.bytes.toReadableSize()}，${stats.fileCount} 個檔案",
        )
    } else {
        stats.bytes.toReadableSize()
    }
}

internal fun formatCacheSpaceLine(summary: CacheSummary): String {
    val remainingQuota = summary.remainingQuotaBytes
    val deviceFree = summary.deviceFreeBytes
    return when {
        remainingQuota != null && deviceFree > 0L -> tr(
            "缓存剩余 ${remainingQuota.toReadableSize()}，设备可用 ${deviceFree.toReadableSize()}",
            "${remainingQuota.toReadableSize()} cache left, ${deviceFree.toReadableSize()} device free",
            "キャッシュ残り ${remainingQuota.toReadableSize()}、端末空き ${deviceFree.toReadableSize()}",
            "캐시 여유 ${remainingQuota.toReadableSize()}, 기기 여유 ${deviceFree.toReadableSize()}",
            "快取剩餘 ${remainingQuota.toReadableSize()}，裝置可用 ${deviceFree.toReadableSize()}",
        )
        remainingQuota != null -> tr(
            "缓存剩余 ${remainingQuota.toReadableSize()}",
            "${remainingQuota.toReadableSize()} cache left",
            "キャッシュ残り ${remainingQuota.toReadableSize()}",
            "캐시 여유 ${remainingQuota.toReadableSize()}",
            "快取剩餘 ${remainingQuota.toReadableSize()}",
        )
        deviceFree > 0L -> tr(
            "设备可用 ${deviceFree.toReadableSize()}",
            "${deviceFree.toReadableSize()} device free",
            "端末空き ${deviceFree.toReadableSize()}",
            "기기 여유 ${deviceFree.toReadableSize()}",
            "裝置可用 ${deviceFree.toReadableSize()}",
        )
        else -> ""
    }
}

private fun cacheCategoryIcon(category: CacheCategory): ImageVector = when (category) {
    CacheCategory.IMAGES -> Icons.Outlined.Image
    CacheCategory.PLAYBACK -> Icons.Outlined.PlayCircle
    CacheCategory.STORYBOARD -> Icons.Outlined.ViewCarousel
    CacheCategory.CLIPS -> Icons.Outlined.SwipeVertical
    CacheCategory.TREE_INDEX -> Icons.Outlined.AccountTree
}

internal fun formatCacheCapLabel(bytes: Long): String = when {
    bytes <= 0L -> tr("无限制", "Unlimited", "無制限", "무제한", "無限制")
    bytes < 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L)} MiB"
    else -> "${bytes / (1024L * 1024L * 1024L)} GiB"
}

internal fun formatCacheTtlLabel(days: Int): String = when {
    days <= 0 -> tr("永不自动清理", "Never", "自動消去しない", "자동 정리 안 함", "永不自動清理")
    else -> tr("$days 天", enCount(days, "day", "days"), "${days}日", "${days}일", "$days 天")
}

private fun freedNoticeText(freedBytes: Long): String = if (freedBytes > 0L) {
    tr(
        "已释放 ${freedBytes.toReadableSize()}",
        "Freed ${freedBytes.toReadableSize()}",
        "${freedBytes.toReadableSize()} を解放しました",
        "${freedBytes.toReadableSize()} 확보됨",
        "已釋放 ${freedBytes.toReadableSize()}",
    )
} else {
    tr("没有可清理的缓存", "No cache to clear", "消去できるキャッシュはありません", "정리할 캐시가 없습니다", "沒有可清理的快取")
}
