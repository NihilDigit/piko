package dev.piko.ui.screens.transfers

import dev.piko.shared.i18n.enCount

import dev.piko.shared.i18n.tr

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.piko.shared.state.TransferItem
import dev.piko.shared.upload.UploadStatus
import dev.piko.ui.components.ItemDetailsContent
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.SheetAction

internal fun TransferItem.UploadBatch.statusLabel(): String = when (status) {
    UploadStatus.QUEUED -> tr("等待中", "Waiting", "待機中", "대기 중", "等待中")
    UploadStatus.HASHING -> tr("校验中", "Verifying", "検証中", "확인 중", "驗證中")
    UploadStatus.UPLOADING -> tr("正在上传", "Uploading", "アップロード中", "업로드 중", "正在上傳")
    UploadStatus.PAUSED -> tr("已暂停", "Paused", "一時停止中", "일시 중지됨", "已暫停")
    UploadStatus.COMPLETED -> tr("已上传", "Uploaded", "アップロード済み", "업로드됨", "已上傳")
    UploadStatus.FAILED -> tr("部分文件上传失败", "Some uploads failed", "一部のアップロードに失敗しました", "일부 파일 업로드 실패", "部分檔案上傳失敗")
}

internal fun uploadBatchActions(item: TransferItem.UploadBatch, expanded: Boolean, onToggle: () -> Unit,
    onPause: () -> Unit, onResume: () -> Unit, onRemove: () -> Unit, onShowInDrive: (() -> Unit)? = null): List<SheetAction> = buildList {
    add(SheetAction(if (expanded) Icons.Outlined.UnfoldLess else Icons.Outlined.UnfoldMore, if (expanded) tr("收起", "Collapse", "折りたたむ", "접기", "收合") else tr("展开", "Expand", "展開", "펼치기", "展開"), onToggle))
    if (onShowInDrive != null) add(SheetAction(androidx.compose.material.icons.Icons.AutoMirrored.Outlined.OpenInNew, tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기", "在雲端硬碟中顯示"), onShowInDrive))
    if (item.tasks.any { it.status.isActive }) add(SheetAction(Icons.Outlined.Pause, tr("全部暂停", "Pause all", "すべて一時停止", "모두 일시 중지", "全部暫停"), onPause))
    if (item.tasks.any { it.status == UploadStatus.PAUSED || it.status == UploadStatus.FAILED }) {
        add(SheetAction(Icons.Outlined.PlayArrow, tr("继续未完成的文件", "Resume unfinished files", "未完了のファイルを再開", "미완료 파일 계속", "繼續未完成的檔案"), onResume))
    }
    add(SheetAction(Icons.Outlined.Delete, if (item.status == UploadStatus.COMPLETED) tr("移除记录", "Remove record", "記録を削除", "기록 삭제", "移除記錄") else tr("取消并移除", "Cancel and remove", "キャンセルして削除", "취소 및 삭제", "取消並移除"), onRemove, destructive = true))
}

@Composable
internal fun UploadBatchDetails(item: TransferItem.UploadBatch, actions: List<SheetAction>, modifier: Modifier = Modifier) {
    ItemDetailsContent(
        title = item.title, modifier = modifier, showIcon = false,
        headerIcon = { if (item.isFolder) TaskFolderVisual(item.title) else ListLeadingIcon(Icons.Outlined.Upload) },
        actions = actions,
        metaParts = listOf(item.statusLabel(), tr("已完成 ${item.completedCount} / ${item.tasks.size} 项", "Completed ${item.completedCount} / ${enCount(item.tasks.size, "item", "items")}", "完了 ${item.completedCount} / ${item.tasks.size} 項目", "${item.completedCount} / ${item.tasks.size}개 항목 완료", "已完成 ${item.completedCount} / ${item.tasks.size} 項"), tr("保存到 ${item.parentName}", "Saved to ${item.parentName}", "保存先 ${item.parentName}", "저장 위치 ${item.parentName}", "儲存至 ${item.parentName}")),
    )
}
