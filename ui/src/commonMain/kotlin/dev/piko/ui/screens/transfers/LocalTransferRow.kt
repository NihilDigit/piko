package dev.piko.ui.screens.transfers

import dev.piko.shared.i18n.tr

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import dev.piko.data.repository.isPlayableVideo
import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.ui.components.ItemDetailsContent
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.ListLeadingMedia
import dev.piko.ui.components.ListLeadingSize
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.platform.LocalFileActions
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.theme.LocalStatusColors

private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav", "m4a", "aac")

internal val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "avif")

internal fun DownloadTask.isMedia(): Boolean =
    fileName.isPlayableVideo() || fileName.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

/** 原画稀疏暂存已有已下载字节时，支持边下边播。 */
internal fun DownloadTask.canPlayWhileDownloading(): Boolean =
    status != DownloadStatus.COMPLETED && !isSegment && mediaId == null && fileName.isPlayableVideo() && sparseCache && downloadedBytes > 0

internal fun DownloadTask.canPlayNow(): Boolean =
    (status == DownloadStatus.COMPLETED && isMedia()) || canPlayWhileDownloading()

@Composable
internal fun LocalTransferVisual(task: DownloadTask, isSpoilerBlurred: Boolean) {
    val files = LocalPikoPlatform.current.localFiles
    val isMedia = task.isMedia()
    val imageModel = remember(task.thumbnailLink, task.destinationPath) {
        task.thumbnailLink.ifEmpty { null } ?: files.thumbnailModel(task.destinationPath)
            .takeIf { isMedia || task.fileName.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
    }
    ListLeadingMedia(
        thumbnail = imageModel,
        fallback = { TaskFileTypeIcon(task.fileName, task.typeIcon()) },
        isSpoilerBlurred = isSpoilerBlurred,
        showPlayOverlay = task.canPlayNow(),
        size = ListLeadingSize,
    )
}

private fun DownloadTask.typeIcon(): ImageVector = when {
    fileName.isPlayableVideo() -> Icons.Outlined.Movie
    fileName.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS -> Icons.Outlined.AudioFile
    else -> Icons.Outlined.Description
}

/** 状态词。下载中没有状态词：进度条与速度已经说明它在下载。 */
internal fun DownloadTask.statusLabel(): String? = when (status) {
    DownloadStatus.COMPLETED -> tr("已完成", "Completed", "完了", "완료", "已完成")
    // 转码档下完后在本机转为 MP4，这一段不走网络，速度一栏是空的，要写明在做什么
    DownloadStatus.DOWNLOADING -> if (converting) tr("转换中", "Converting", "変換中", "변환 중", "轉換中") else null
    DownloadStatus.PAUSED -> tr("已暂停", "Paused", "一時停止中", "일시 중지됨", "已暫停")
    DownloadStatus.PENDING -> tr("等待中", "Waiting", "待機中", "대기 중", "等待中")
    DownloadStatus.FAILED -> tr("下载失败", "Download failed", "ダウンロード失敗", "다운로드 실패", "下載失敗")
}

/** 状态词之后的各段数据，片段任务以时间段打头。失败原因由 failureReason 提供。 */
internal fun DownloadTask.statusDetails(): List<String> {
    // 片段事先不知道产物大小，totalBytes 为 0，字节进度只会是「0 B / 0 B」，改报抽取比例。
    // 比例为 0 时抽取器还在从头逐簇扫到起点（系统 MKV 解析器用不上 Cues），这一段可能要几分钟
    val progress = when {
        converting -> "${(this.progress * 100).toInt()}%"
        !isSegment -> "${downloadedBytes.toReadableSize()} / ${totalBytes.toReadableSize()}"
        this.progress <= 0f -> tr("正在定位起点", "Locating start", "開始点を特定中", "시작점 찾는 중", "正在定位起點")
        else -> "${(this.progress * 100).toInt()}%"
    }
    val segment = listOfNotNull(timeRangeLabel?.takeIf { isSegment }?.let { tr("段落 $it", "Segment $it", "区間 $it", "구간 $it", "段落 $it") })
    return segment + when (status) {
        DownloadStatus.COMPLETED -> listOf(totalBytes.toReadableSize())
        DownloadStatus.PENDING -> if (isSegment) emptyList() else listOf(totalBytes.toReadableSize())
        DownloadStatus.DOWNLOADING -> buildList {
            add(progress)
            if (speedBytesPerSec > 0) add("${speedBytesPerSec.toReadableSize()}/s")
            // 片段事先不知道产物大小，剩余时间无从算起
            if (!isSegment && !converting) remainingTime(totalBytes - downloadedBytes, speedBytesPerSec)?.let { add(tr("剩余 $it", "$it remaining", "残り $it", "$it 남음", "剩餘 $it")) }
        }
        DownloadStatus.PAUSED -> listOf(progress)
        DownloadStatus.FAILED -> emptyList()
    }
}

internal fun DownloadTask.failureReason(): String = errorMessage?.takeIf { it.isNotBlank() } ?: tr("网络中断", "Network interrupted", "ネットワークが切断されました", "네트워크 연결이 끊어졌습니다", "網路中斷")

/** 列表与面板共用的外部动作：用其他应用打开、分享、打开所在文件夹。 */
private class LocalFileIntents(private val files: LocalFileActions, private val task: DownloadTask, private val isMedia: Boolean) {
    fun openExternal() = files.openExternally(task.destinationPath, isMedia)

    fun share() = files.share(task.destinationPath, isMedia)

    fun openContainingFolder() = files.openContainingFolder(task.destinationPath)
}

@Composable
private fun DownloadTask.statusColor() = when (status) {
    DownloadStatus.COMPLETED -> LocalStatusColors.current.success
    DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}


/**
 * 点按一项做的事：完成或可边下边播的视频直接播放，其余完成的用其他应用打开，暂停与失败的继续，下载中的暂停。
 * 窄行的轻点、宽行的双击共用。等待中的没有可做的，为 null。
 */
internal fun localPrimaryAction(
    task: DownloadTask,
    files: LocalFileActions,
    onPlay: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
): (() -> Unit)? {
    if (task.canPlayNow()) return onPlay
    val isMedia = task.isMedia()
    return when (task.status) {
        DownloadStatus.COMPLETED -> LocalFileIntents(files, task, isMedia)::openExternal
        DownloadStatus.PAUSED, DownloadStatus.FAILED -> onStart
        DownloadStatus.DOWNLOADING -> onPause
        DownloadStatus.PENDING -> null
    }
}

/** 本地下载项的全部操作，详情面板与右键菜单共用。 */
internal fun localTransferActions(
    task: DownloadTask,
    files: LocalFileActions,
    onPlay: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onRemove: () -> Unit,
    /** 为 null 表示没有可切换的预览（防窥关闭或没有缩略图），不显示该项。 */
    previewHidden: Boolean?,
    onTogglePreview: () -> Unit,
    onShowInDrive: (() -> Unit)? = null,
): List<SheetAction> {
    val isMedia = task.isMedia()
    val intents = LocalFileIntents(files, task, isMedia)
    return buildList {
        // 边下边播只认原画的暂存；转码档的暂存是转封装前的 TS，播放走不到它
        if (task.canPlayWhileDownloading()) {
            add(SheetAction(Icons.Outlined.PlayArrow, tr("播放", "Play", "再生", "재생", "播放"), onPlay))
        }
        if (previewHidden != null) {
            add(
                SheetAction(
                    icon = if (previewHidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                    label = if (previewHidden) tr("显示预览", "Show preview", "プレビューを表示", "미리 보기 표시", "顯示預覽") else tr("隐藏预览", "Hide preview", "プレビューを隠す", "미리 보기 숨기기", "隱藏預覽"),
                    onClick = onTogglePreview,
                ),
            )
        }
        when (task.status) {
            DownloadStatus.COMPLETED -> {
                if (isMedia) add(SheetAction(Icons.Outlined.PlayArrow, tr("播放", "Play", "再生", "재생", "播放"), onPlay))
                add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, tr("用其他应用打开", "Open with another app", "ほかのアプリで開く", "다른 앱으로 열기", "使用其他應用程式開啟"), intents::openExternal))
                add(SheetAction(Icons.Outlined.FolderOpen, tr("打开所在文件夹", "Open containing folder", "保存先フォルダーを開く", "파일 위치 열기", "開啟所在資料夾"), intents::openContainingFolder))
                if (files.canShare) add(SheetAction(Icons.Outlined.Share, tr("分享", "Share", "共有", "공유", "分享"), intents::share))
            }
            DownloadStatus.DOWNLOADING -> {
                add(SheetAction(Icons.Outlined.Pause, tr("暂停", "Pause", "一時停止", "일시 중지", "暫停"), onPause))
                if (task.destinationPath.isNotEmpty()) {
                    add(SheetAction(Icons.Outlined.FolderOpen, tr("打开所在文件夹", "Open containing folder", "保存先フォルダーを開く", "파일 위치 열기", "開啟所在資料夾"), intents::openContainingFolder))
                }
            }
            DownloadStatus.PAUSED, DownloadStatus.PENDING -> {
                add(SheetAction(Icons.Outlined.PlayArrow, tr("继续下载", "Resume download", "ダウンロードを再開", "다운로드 계속", "繼續下載"), onStart))
                if (task.destinationPath.isNotEmpty() && task.downloadedBytes > 0) {
                    add(SheetAction(Icons.Outlined.FolderOpen, tr("打开所在文件夹", "Open containing folder", "保存先フォルダーを開く", "파일 위치 열기", "開啟所在資料夾"), intents::openContainingFolder))
                }
            }
            DownloadStatus.FAILED -> add(SheetAction(Icons.Outlined.Refresh, tr("重试", "Retry", "再試行", "다시 시도", "重試"), onStart))
        }
        if (onShowInDrive != null) {
            add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기", "在雲端硬碟中顯示"), onShowInDrive))
        }
        val removeLabel = when (task.status) {
            DownloadStatus.COMPLETED -> tr("删除本地文件", "Delete local file", "ローカルファイルを削除", "로컬 파일 삭제", "刪除本機檔案")
            DownloadStatus.FAILED -> tr("移除记录", "Remove record", "記録を削除", "기록 삭제", "移除記錄")
            else -> tr("取消并删除", "Cancel and delete", "キャンセルして削除", "취소 및 삭제", "取消並刪除")
        }
        add(SheetAction(Icons.Outlined.Delete, removeLabel, onRemove, destructive = true))
    }
}

/** 本地下载项的详情面板：完整文件名、状态与全部操作。 */
@Composable
internal fun LocalTransferDetails(task: DownloadTask, actions: List<SheetAction>, modifier: Modifier = Modifier) {
    val statusColor = task.statusColor()
    ItemDetailsContent(modifier = modifier, showIcon = false,
        title = task.displayName,
        headerIcon = { TaskFileTypeIcon(task.fileName, task.typeIcon()) },
        actions = actions,
        metaParts = listOfNotNull(task.statusLabel()) + task.statusDetails(),
        extraLines = {
            if (task.status == DownloadStatus.FAILED) Text(text = task.failureReason(), color = statusColor)
        },
    )
}
