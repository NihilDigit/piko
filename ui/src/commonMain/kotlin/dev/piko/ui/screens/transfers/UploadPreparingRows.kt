package dev.piko.ui.screens.transfers

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

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
    if (onShow != null) add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기"), onShow))
    if (!work.isRunning) add(SheetAction(Icons.Outlined.Close, tr("移除准备记录", "Remove preparation record", "準備の記録を削除", "준비 기록 삭제"), onForget))
}

@Composable
internal fun UploadPreparingDetails(work: UploadPreparation, actions: List<SheetAction>, modifier: Modifier = Modifier) {
    val sources = work.sources.associateBy { it.key }
    fun label(key: String, path: String): String {
        val source = sources[key] ?: return path
        return if (source.folder && path.isNotEmpty()) "${source.title}/$path" else path.ifEmpty { source.title }
    }
    val progress = work.workProgress()
    ItemDetailsContent(modifier = modifier, showIcon = false, title = tr("上传到「${work.parentName}」", "Upload to “${work.parentName}”", "「${work.parentName}」にアップロード", "“${work.parentName}”에 업로드"), headerIcon = { ListLeadingIcon(Icons.Outlined.Upload) },
        metaParts = listOf(tr("上传准备", "Upload preparation", "アップロードの準備", "업로드 준비"), progress.status), actions = actions, extraLines = {
            progress.detail?.let { Text(it) }
            if (work.files.isNotEmpty()) Text(tr("已入队文件：\n${work.files.joinToString("\n") { label(it.sourceKey, it.path) }}", "Queued files:\n${work.files.joinToString("\n") { label(it.sourceKey, it.path) }}", "キューに追加済み：\n${work.files.joinToString("\n") { label(it.sourceKey, it.path) }}", "대기열 파일:\n${work.files.joinToString("\n") { label(it.sourceKey, it.path) }}"))
            if (work.directories.isNotEmpty()) Text(tr("已创建目录：\n${work.directories.joinToString("\n") { label(it.sourceKey, it.path) }}", "Created folders:\n${work.directories.joinToString("\n") { label(it.sourceKey, it.path) }}", "作成済みフォルダー：\n${work.directories.joinToString("\n") { label(it.sourceKey, it.path) }}", "만든 폴더:\n${work.directories.joinToString("\n") { label(it.sourceKey, it.path) }}"))
            for (kind in UploadPreparationIssueKind.entries) {
                val issues = work.issues.filter { it.kind == kind }
                if (issues.isNotEmpty()) Text(when (kind) {
                    UploadPreparationIssueKind.SOURCE_UNREADABLE -> tr("来源读取失败", "Failed to read source", "ソースを読み込めませんでした", "원본 읽기 실패")
                    UploadPreparationIssueKind.EMPTY_FOLDER -> tr("文件夹中没有可上传文件", "No uploadable files in folder", "フォルダーにアップロードできるファイルがありません", "폴더에 업로드할 파일이 없습니다")
                    UploadPreparationIssueKind.DIRECTORY_REFUSED -> tr("目录创建被拒绝", "Folder creation rejected", "フォルダーの作成を拒否されました", "폴더 생성이 거부되었습니다")
                    UploadPreparationIssueKind.DIRECTORY_UNCONFIRMED -> tr("目录创建结果待确认", "Folder creation unconfirmed", "フォルダーの作成結果は未確認です", "폴더 생성 결과 미확인")
                    UploadPreparationIssueKind.NOT_ATTEMPTED -> tr("未入队", "Not queued", "キューに未追加", "대기열에 추가 안 됨")
                } + ":\n${issues.joinToString("\n") { label(it.sourceKey, it.path) }}")
            }
            if (work.hasSideEffects && !work.isRunning) Text(stringResource(Res.string.text_inspect_folders_and_queued_files_first_preparation_will_not_recreate_folders))
            Text(stringResource(Res.string.text_removing_preparation_records_does_not_cancel_uploads_delete_folders_or_estab))
        })
}
