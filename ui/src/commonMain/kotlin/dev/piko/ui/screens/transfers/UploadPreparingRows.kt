package dev.piko.ui.screens.transfers

import androidx.compose.ui.Modifier

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.piko.shared.upload.UploadPreparation
import dev.piko.shared.upload.UploadPreparationIssueKind
import dev.piko.ui.components.ItemDetailsContent
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.SheetAction
import dev.piko.ui.workProgress

internal fun uploadPreparationActions(work: UploadPreparation, onShow: (() -> Unit)?, onForget: () -> Unit): List<SheetAction> = buildList {
    if (onShow != null) add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "在网盘中查看", onShow))
    if (!work.isRunning) add(SheetAction(Icons.Outlined.Close, "移除准备记录", onForget))
}

@Composable
internal fun UploadPreparingDetails(work: UploadPreparation, actions: List<SheetAction>, modifier: Modifier = Modifier) {
    val sources = work.sources.associateBy { it.key }
    fun label(key: String, path: String): String {
        val source = sources[key] ?: return path
        return if (source.folder && path.isNotEmpty()) "${source.title}/$path" else path.ifEmpty { source.title }
    }
    val progress = work.workProgress()
    ItemDetailsContent(modifier = modifier, showIcon = false, title = "上传到「${work.parentName}」", headerIcon = { ListLeadingIcon(Icons.Outlined.Upload) },
        metaParts = listOf("上传准备", progress.status), actions = actions, extraLines = {
            progress.detail?.let { Text(it) }
            if (work.files.isNotEmpty()) Text("已入队文件：\n${work.files.joinToString("\n") { label(it.sourceKey, it.path) }}")
            if (work.directories.isNotEmpty()) Text("已创建目录：\n${work.directories.joinToString("\n") { label(it.sourceKey, it.path) }}")
            for (kind in UploadPreparationIssueKind.entries) {
                val issues = work.issues.filter { it.kind == kind }
                if (issues.isNotEmpty()) Text(when (kind) {
                    UploadPreparationIssueKind.SOURCE_UNREADABLE -> "来源读取失败"
                    UploadPreparationIssueKind.EMPTY_FOLDER -> "文件夹中没有可上传文件"
                    UploadPreparationIssueKind.DIRECTORY_REFUSED -> "目录创建被拒绝"
                    UploadPreparationIssueKind.DIRECTORY_UNCONFIRMED -> "目录创建结果待确认"
                    UploadPreparationIssueKind.NOT_ATTEMPTED -> "未入队"
                } + ":\n${issues.joinToString("\n") { label(it.sourceKey, it.path) }}")
            }
            if (work.hasSideEffects && !work.isRunning) Text("请先检查实际目录和已入队文件。准备记录不会自动重新创建目录；已入队文件可从各自的上传任务继续。")
            Text("移除准备记录不取消文件上传、不删除目录，也不证明原请求没有执行。")
        })
}
