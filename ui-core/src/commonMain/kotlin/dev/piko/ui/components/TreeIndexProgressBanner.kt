package dev.piko.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.i18n.tr
import dev.piko.ui.theme.LocalPikoMotion
import dev.piko.ui.theme.LocalWindowVisible
import dev.piko.ui.theme.tabular

fun formatTreeIndexProgressLabel(
    progress: PikoDriveRepository.TreeBuildProgress,
): String {
    val phase = formatTreeIndexPhaseTitle(progress)
    val metrics = formatTreeIndexMetrics(progress)
    return if (metrics != null) {
        tr("$phase（$metrics）", "$phase ($metrics)", "$phase（$metrics）", "$phase ($metrics)", "$phase（$metrics）")
    } else {
        "$phase…"
    }
}

private fun formatTreeIndexPhaseTitle(
    progress: PikoDriveRepository.TreeBuildProgress,
): String = when {
    progress.isBuilding && progress.isInitialBlocking -> tr(
        "正在建立本地索引",
        "Building local index",
        "ローカル索引を構築中",
        "로컬 색인 구축 중",
        "正在建立本機索引",
    )
    progress.isBuilding -> tr(
        "正在更新本地索引",
        "Updating local index",
        "ローカル索引を更新中",
        "로컬 색인 업데이트 중",
        "正在更新本機索引",
    )
    else -> tr(
        "正在校验云端变更",
        "Checking cloud changes",
        "クラウド変更を確認中",
        "클라우드 변경 확인 중",
        "正在校驗雲端變更",
    )
}

private fun formatTreeIndexMetrics(
    progress: PikoDriveRepository.TreeBuildProgress,
): String? {
    val hasCounts = progress.foldersRead > 0 || progress.filesRead > 0
    val hasManifests = progress.manifestsTotal > 0
    if (hasManifests) {
        val done = progress.manifestsRead.coerceAtMost(progress.manifestsTotal)
        val total = progress.manifestsTotal
        return if (hasCounts) {
            tr(
                "${progress.foldersRead} 文件夹，${progress.filesRead} 文件，归档 $done/$total",
                "${progress.foldersRead} folders, ${progress.filesRead} files, $done/$total",
                "${progress.foldersRead} フォルダ、${progress.filesRead} 件、$done/$total",
                "폴더 ${progress.foldersRead}, 파일 ${progress.filesRead}, $done/$total",
                "${progress.foldersRead} 資料夾，${progress.filesRead} 檔案，歸檔 $done/$total",
            )
        } else {
            tr(
                "归档清单 $done / $total",
                "Archives $done / $total",
                "アーカイブ $done / $total",
                "보관 목록 $done / $total",
                "歸檔清單 $done / $total",
            )
        }
    }
    if (hasCounts) {
        return tr(
            "${progress.foldersRead} 文件夹，${progress.filesRead} 文件",
            "${progress.foldersRead} folders, ${progress.filesRead} files",
            "${progress.foldersRead} フォルダ、${progress.filesRead} 件",
            "폴더 ${progress.foldersRead}, 파일 ${progress.filesRead}",
            "${progress.foldersRead} 資料夾，${progress.filesRead} 檔案",
        )
    }
    return null
}

/**
 * 本地全盘树索引构建状态条（M3 Expressive 紧凑圆角状态条）：
 * 左侧固定图标与简短阶段标题，右侧保留永不被截断的等宽实时计数（`.tabular`），底边贴附 2dp 进度条，
 * 减少动画开启或窗口不可见时自动降级为静态轨道，不堆叠冗余副文案。
 */
@Composable
fun TreeIndexProgressBanner(
    progress: PikoDriveRepository.TreeBuildProgress,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val phaseTitle = formatTreeIndexPhaseTitle(progress)
    val metrics = formatTreeIndexMetrics(progress)
    val determinateFraction = if (progress.manifestsTotal > 0) {
        (progress.manifestsRead.toFloat() / progress.manifestsTotal.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }
    val staticMode = LocalPikoMotion.current.reduced || !LocalWindowVisible.current

    Surface(
        color = colors.surfaceContainerLow,
        contentColor = colors.onSurface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.55f)),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Sync,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = phaseTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (metrics != null) {
                    Text(
                        text = metrics,
                        style = MaterialTheme.typography.labelMedium.tabular,
                        color = colors.primary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            when {
                determinateFraction != null -> LinearProgressIndicator(
                    progress = { determinateFraction },
                    color = colors.primary,
                    trackColor = colors.surfaceContainerHighest,
                    strokeCap = StrokeCap.Butt,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
                staticMode -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(colors.primary.copy(alpha = 0.35f)),
                )
                else -> LinearProgressIndicator(
                    color = colors.primary,
                    trackColor = colors.surfaceContainerHighest,
                    strokeCap = StrokeCap.Butt,
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
            }
        }
    }
}
