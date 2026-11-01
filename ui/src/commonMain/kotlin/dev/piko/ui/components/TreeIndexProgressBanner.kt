package dev.piko.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.i18n.tr

internal fun formatTreeIndexProgressLabel(
    progress: PikoDriveRepository.TreeBuildProgress,
): String {
    val initial = progress.isInitialBlocking
    val hasCounts = progress.foldersRead > 0 || progress.filesRead > 0
    val hasManifests = progress.manifestsTotal > 0
    if (hasCounts && hasManifests) {
        val folders = progress.foldersRead
        val files = progress.filesRead
        val done = progress.manifestsRead.coerceAtMost(progress.manifestsTotal)
        val total = progress.manifestsTotal
        return if (initial) {
            tr(
                "正在建立本地索引：已扫描 $folders 个文件夹、$files 个文件（归档清单 $done / $total）",
                "Building local index: $folders folders, $files files (archives $done / $total)",
                "ローカル索引を構築中：$folders フォルダー、$files ファイル（アーカイブ $done / $total）",
                "로컬 색인 구축 중: 폴더 ${folders}개, 파일 ${files}개 (보관 $done / $total)",
                "正在建立本機索引：已掃描 $folders 個資料夾、$files 個檔案（歸檔清單 $done / $total）",
            )
        } else {
            tr(
                "正在增量更新本地索引：已扫描 $folders 个文件夹、$files 个文件（归档清单 $done / $total）",
                "Updating local index: $folders folders, $files files (archives $done / $total)",
                "ローカル索引を更新中：$folders フォルダー、$files ファイル（アーカイブ $done / $total）",
                "로컬 색인 업데이트 중: 폴더 ${folders}개, 파일 ${files}개 (보관 $done / $total)",
                "正在增量更新本機索引：已掃描 $folders 個資料夾、$files 個檔案（歸檔清單 $done / $total）",
            )
        }
    }
    if (hasManifests) {
        val done = progress.manifestsRead.coerceAtMost(progress.manifestsTotal)
        val total = progress.manifestsTotal
        return if (initial) {
            tr(
                "正在建立本地索引：读取归档清单 $done / $total",
                "Building local index: reading archive manifests $done / $total",
                "ローカル索引を構築中：アーカイブ一覧を読み込み $done / $total",
                "로컬 색인 구축 중: 보관 목록 읽는 중 $done / $total",
                "正在建立本機索引：讀取歸檔清單 $done / $total",
            )
        } else {
            tr(
                "正在增量更新本地索引：读取归档清单 $done / $total",
                "Updating local index: reading archive manifests $done / $total",
                "ローカル索引を更新中：アーカイブ一覧を読み込み $done / $total",
                "로컬 색인 업데이트 중: 보관 목록 읽는 중 $done / $total",
                "正在增量更新本機索引：讀取歸檔清單 $done / $total",
            )
        }
    }
    if (hasCounts) {
        val folders = progress.foldersRead
        val files = progress.filesRead
        return if (initial) {
            tr(
                "正在建立本地索引：已扫描 $folders 个文件夹，$files 个文件",
                "Building local index: scanned $folders folders, $files files",
                "ローカル索引を構築中：$folders フォルダー、$files ファイルを走査済み",
                "로컬 색인 구축 중: 폴더 ${folders}개, 파일 ${files}개 스캔됨",
                "正在建立本機索引：已掃描 $folders 個資料夾，$files 個檔案",
            )
        } else {
            tr(
                "正在增量更新本地索引：已扫描 $folders 个文件夹，$files 个文件",
                "Updating local index: scanned $folders folders, $files files",
                "ローカル索引を更新中：$folders フォルダー、$files ファイルを走査済み",
                "로컬 색인 업데이트 중: 폴더 ${folders}개, 파일 ${files}개 스캔됨",
                "正在增量更新本機索引：已掃描 $folders 個資料夾，$files 個檔案",
            )
        }
    }
    if (progress.isBuilding) {
        return if (initial) {
            tr(
                "正在建立本地索引…",
                "Building local index…",
                "ローカル索引を構築しています…",
                "로컬 색인을 구축하는 중…",
                "正在建立本機索引…",
            )
        } else {
            tr(
                "正在增量更新本地索引…",
                "Updating local index…",
                "ローカル索引を更新しています…",
                "로컬 색인을 업데이트하는 중…",
                "正在增量更新本機索引…",
            )
        }
    }
    return tr(
        "正在增量校验云端变更…",
        "Checking cloud changes…",
        "クラウドの変更を確認中…",
        "클라우드 변경 사항 확인 중…",
        "正在增量校驗雲端變更…",
    )
}

@Composable
internal fun TreeIndexProgressBanner(
    progress: PikoDriveRepository.TreeBuildProgress,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            InlineLoadingIndicator()
            Text(
                text = formatTreeIndexProgressLabel(progress),
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (progress.isInitialBlocking) {
                Text(
                    text = tr(
                        "完成后将持久复用",
                        "Persisted for instant reuse",
                        "完了後は次回から即時利用可能",
                        "완료 후 즉시 재사용 가능",
                        "完成後將持久複用",
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
                    maxLines = 1,
                )
            }
        }
    }
}
