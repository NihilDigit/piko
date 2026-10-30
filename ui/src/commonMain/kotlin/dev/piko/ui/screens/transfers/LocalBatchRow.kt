package dev.piko.ui.screens.transfers

import dev.piko.shared.i18n.enCount

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.piko.download.DownloadStatus
import dev.piko.shared.state.TransferItem
import dev.piko.ui.components.ItemDetailsContent
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.theme.LocalStatusColors

/** 状态词。下载中不写，理由同单个文件的行。 */
internal fun TransferItem.LocalBatch.statusLabel(): String? {
    listing?.let { listing ->
        return when {
            listing.error != null -> tr("列出失败", "Failed to list", "一覧を取得できませんでした", "목록 불러오기 실패", "列出失敗")
            listing.quotaExcess != null -> tr("将超出今日下载额度", "Would exceed today’s download quota", "本日のダウンロード枠を超えます", "오늘의 다운로드 할당량 초과 예정", "將超出今日下載額度")
            else -> tr("正在列出文件夹…", "Listing folder…", "フォルダーの内容を取得中…", "폴더 목록 불러오는 중…", "正在列出資料夾…")
        }
    }
    return when (status) {
        DownloadStatus.COMPLETED -> tr("已完成", "Completed", "完了", "완료", "已完成")
        DownloadStatus.DOWNLOADING -> null
        DownloadStatus.PAUSED -> tr("已暂停", "Paused", "一時停止中", "일시 중지됨", "已暫停")
        DownloadStatus.PENDING -> tr("等待中", "Waiting", "待機中", "대기 중", "等待中")
        DownloadStatus.FAILED -> tr("$failedCount 个文件下载失败", "${enCount(failedCount, "download", "downloads")} failed", "$failedCount ファイルのダウンロードに失敗", "파일 ${failedCount}개 다운로드 실패", "$failedCount 個檔案下載失敗")
    }
}

internal fun TransferItem.LocalBatch.statusDetails(): List<String> {
    listing?.let { listing ->
        val excess = listing.quotaExcess
        return when {
            listing.error != null -> listOf(listing.error.orEmpty().lineSequence().first())
            excess != null -> listOf(tr("待下载 ${excess.neededBytes.toReadableSize()}", "To download ${excess.neededBytes.toReadableSize()}", "ダウンロード予定 ${excess.neededBytes.toReadableSize()}", "다운로드 예정 ${excess.neededBytes.toReadableSize()}", "待下載 ${excess.neededBytes.toReadableSize()}"), tr("今日剩余 ${excess.remainingBytes.toReadableSize()}", "${excess.remainingBytes.toReadableSize()} left today", "本日の残り ${excess.remainingBytes.toReadableSize()}", "오늘 남은 용량 ${excess.remainingBytes.toReadableSize()}", "今日剩餘 ${excess.remainingBytes.toReadableSize()}"))
            else -> listOf(tr("已找到 ${listing.filesFound} 个文件", "Found ${enCount(listing.filesFound, "file", "files")}", "${listing.filesFound} ファイルが見つかりました", "파일 ${listing.filesFound}개 찾음", "已找到 ${listing.filesFound} 個檔案"), listing.bytesFound.toReadableSize())
        }
    }
    val bytes = "${downloadedBytes.toReadableSize()} / ${totalBytes.toReadableSize()}"
    val files = tr("$completedCount / ${tasks.size} 个文件", "$completedCount / ${enCount(tasks.size, "file", "files")}", "$completedCount / ${tasks.size} ファイル", "$completedCount / ${tasks.size}개 파일", "$completedCount / ${tasks.size} 個檔案")
    return when (status) {
        DownloadStatus.COMPLETED -> listOf(tr("${tasks.size} 个文件", "${enCount(tasks.size, "file", "files")}", "${tasks.size} ファイル", "파일 ${tasks.size}개", "${tasks.size} 個檔案"), totalBytes.toReadableSize())
        DownloadStatus.DOWNLOADING -> buildList {
            add(files)
            add(bytes)
            if (speedBytesPerSec > 0) add("${speedBytesPerSec.toReadableSize()}/s")
            remainingTime(totalBytes - downloadedBytes, speedBytesPerSec)?.let { add(tr("剩余 $it", "$it remaining", "残り $it", "$it 남음", "剩餘 $it")) }
        }
        DownloadStatus.PENDING, DownloadStatus.PAUSED -> listOf(files, bytes)
        DownloadStatus.FAILED -> listOf(files, totalBytes.toReadableSize())
    }
}

@Composable
private fun TransferItem.LocalBatch.statusColor() = when {
    listing?.quotaExcess != null -> MaterialTheme.colorScheme.error
    status == DownloadStatus.COMPLETED -> LocalStatusColors.current.success
    status == DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 文件夹下载这一组能对它做的事，行尾按钮、详情面板与右键菜单共用。 */
internal class LocalBatchIntents(
    val onToggleExpand: () -> Unit,
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onRetryListing: () -> Unit,
    val onConfirm: () -> Unit,
    val onOpenFolder: () -> Unit,
    val onRevealFolder: () -> Unit,
    val onRemove: () -> Unit,
)

/**
 * 点按做的事：列完了是展开或收起，列出中、列出失败与待确认没有文件可列，不弹详情。
 * 窄行的轻点、宽行的双击共用。
 */
internal fun localBatchPrimaryAction(item: TransferItem.LocalBatch, intents: LocalBatchIntents): (() -> Unit)? =
    if (item.listing != null) null else intents.onToggleExpand


/** 一组的全部操作，详情面板与右键菜单共用。 */
internal fun localBatchActions(item: TransferItem.LocalBatch, expanded: Boolean, intents: LocalBatchIntents): List<SheetAction> = buildList {
    val listing = item.listing
    if (listing != null) {
        when {
            listing.error != null -> add(SheetAction(Icons.Outlined.Refresh, tr("重试", "Retry", "再試行", "다시 시도", "重試"), intents.onRetryListing))
            listing.quotaExcess != null -> add(SheetAction(Icons.Outlined.Download, tr("仍然下载", "Download anyway", "ダウンロードを続行", "계속 다운로드", "仍然下載"), intents.onConfirm))
        }
        val label = if (listing.error != null) tr("移除", "Remove", "削除", "제거", "移除") else tr("取消", "Cancel", "キャンセル", "취소", "取消")
        add(SheetAction(Icons.Outlined.Close, label, intents.onRemove))
        return@buildList
    }
    add(
        SheetAction(
            icon = if (expanded) Icons.Outlined.UnfoldLess else Icons.Outlined.UnfoldMore,
            label = if (expanded) tr("收起", "Collapse", "折りたたむ", "접기", "收合") else tr("展开", "Expand", "展開", "펼치기", "展開"),
            onClick = intents.onToggleExpand,
        ),
    )
    val status = item.status
    when (status) {
        DownloadStatus.DOWNLOADING, DownloadStatus.PENDING -> add(SheetAction(Icons.Outlined.Pause, tr("全部暂停", "Pause all", "すべて一時停止", "모두 일시 중지", "全部暫停"), intents.onPause))
        DownloadStatus.PAUSED -> add(SheetAction(Icons.Outlined.PlayArrow, tr("全部继续", "Resume all", "すべて再開", "모두 계속", "全部繼續"), intents.onResume))
        DownloadStatus.FAILED -> add(SheetAction(Icons.Outlined.Refresh, tr("重试失败的文件", "Retry failed files", "失敗したファイルを再試行", "실패한 파일 다시 시도", "重試失敗的檔案"), intents.onResume))
        DownloadStatus.COMPLETED -> Unit
    }
    // 有一个文件落了盘，文件夹就在了。一组本身就是文件夹，「所在文件夹」说不清是它还是它的上级，后者写成在上级里显示
    if (item.completedCount > 0) {
        add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, tr("打开文件夹", "Open folder", "フォルダーを開く", "폴더 열기", "開啟資料夾"), intents.onOpenFolder))
        add(SheetAction(Icons.Outlined.FolderOpen, tr("在上级文件夹中显示", "Show in parent folder", "上のフォルダーで表示", "상위 폴더에서 보기", "在上層資料夾中顯示"), intents.onRevealFolder))
    }
    val removeLabel = if (status == DownloadStatus.COMPLETED) tr("删除本地文件", "Delete local file", "ローカルファイルを削除", "로컬 파일 삭제", "刪除本機檔案") else tr("取消并删除", "Cancel and delete", "キャンセルして削除", "취소 및 삭제", "取消並刪除")
    add(SheetAction(Icons.Outlined.Delete, removeLabel, intents.onRemove, destructive = true))
}

/** 一组的详情面板：文件夹名、汇总与全部操作。待确认时把额度的事说完整。 */
@Composable
internal fun LocalBatchDetails(item: TransferItem.LocalBatch, actions: List<SheetAction>, modifier: Modifier = Modifier) {
    val statusColor = item.statusColor()
    val listing = item.listing
    ItemDetailsContent(modifier = modifier, showIcon = false,
        title = item.batch.folderName,
        headerIcon = { ListLeadingIcon(Icons.Outlined.Folder) },
        actions = actions,
        metaParts = listOfNotNull(item.statusLabel()) + item.statusDetails(),
        extraLines = {
            when {
                listing?.quotaExcess != null -> Text(text = stringResource(Res.string.text_downloads_over_the_quota_will_fail_retry_tomorrow), color = statusColor)
                listing?.error != null -> Text(text = listing.error.orEmpty(), color = statusColor)
                else -> Unit
            }
        },
    )
}

/** 一组的行尾：快捷按钮、展开与更多，各 48dp；transferClicks 在这一段不截点击。 */
