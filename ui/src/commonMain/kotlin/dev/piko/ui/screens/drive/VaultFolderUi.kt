package dev.piko.ui.screens.drive

import dev.piko.shared.i18n.enCount

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import dev.piko.shared.state.VaultArchiveOptions
import dev.piko.shared.state.VaultScope
import dev.piko.ui.LocalPikoServices
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.presentation.localizedName
import dev.piko.shared.state.FolderVaultSession
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.toReadableSize

internal fun offersVaultSizeChoice(survey: FolderVaultSession.Survey?): Boolean {
    val largeFiles = survey?.largeFiles?.files ?: return false
    return largeFiles in 1 until survey.files
}

/**
 * 归档之前的确认，文件夹与单独选的文件共用。先清点：多少文件、多大，其中没有来源记录的（自己上传、秒传）单独计，
 * 默认不带上：它们没有磁力或分享链接可凭，PikPak 不再保存时就找不回来。
 *
 * 大小全在门槛同一侧时隐藏大小选项且不应用过滤，避免仅剩小文件时被上次的偏好全部排除。
 * 只有文件时，全有来源或全无来源也隐藏来源选项。全无来源时改为直接说明，归不归档由人按确认或取消决定。
 *
 * 丢失的风险只在这里讲清楚。考虑过给回收站里的原件打标记、清空时再提醒，否决了：官方客户端照样能清空回收站，
 * Piko 拦不住，拦一半只会让人以为有保护。
 */
@Composable
internal fun VaultDialog(
    target: VaultScope,
    session: FolderVaultSession,
    onArchive: () -> Unit,
    onDismiss: () -> Unit,
) {
    val preferences = LocalPikoServices.current.preferences
    val survey by produceState<Result<FolderVaultSession.Survey>?>(null, target) { value = session.survey(target) }
    // 读出上次的选择之前不画勾选，免得先按默认值画出来再跳
    var options by remember { mutableStateOf<VaultArchiveOptions?>(null) }
    LaunchedEffect(Unit) { options = VaultArchiveOptions.load(preferences) }
    val scope = rememberCoroutineScope()
    val chosen = options ?: VaultArchiveOptions()
    val current = options
    if (current != null && !current.riskNoticeAcknowledged) {
        VaultRiskWarningDialog(
            onAcknowledge = {
                val next = current.copy(riskNoticeAcknowledged = true)
                options = next
                scope.launch { VaultArchiveOptions.save(preferences, next) }
            },
            onDismiss = onDismiss,
        )
        return
    }
    val all = survey?.getOrNull()
    val offersSizeChoice = offersVaultSizeChoice(all)
    val skipSmallFiles = offersSizeChoice && chosen.skipSmallFiles
    val counted = all?.let { if (skipSmallFiles) it.largeFiles else it }
    val offersSourceChoice = !target.filesOnly || (counted != null && counted.unsourcedFiles in 1 until counted.files)
    val sourcedOnly = offersSourceChoice && chosen.sourcedOnly
    val files = counted?.let { if (sourcedOnly) it.files - it.unsourcedFiles else it.files } ?: 0
    val bytes = counted?.let { if (sourcedOnly) it.bytes - it.unsourcedBytes else it.bytes } ?: 0L
    val blocked = session.unavailableReason(target.targets)
    PikoDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    target.filesOnly -> tr("归档文件", "Archive files", "ファイルをアーカイブ", "파일 보관")
                    target.files.isEmpty() -> tr("归档文件夹", "Archive folders", "フォルダーをアーカイブ", "폴더 보관")
                    else -> tr("归档", "Archive", "アーカイブ", "보관")
                },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                VaultDialogHeading(target.name, survey, counted?.files, files, bytes, emptyText = tr("无可归档的文件", "No files to archive", "アーカイブできるファイルがありません", "보관할 파일이 없습니다"))
                blocked?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (current != null) {
                    // 是偏好而不是这一次的参数：一改就存，取消归档也记住，与设置页的开关相同
                    fun update(next: VaultArchiveOptions) {
                        options = next
                        scope.launch { VaultArchiveOptions.save(preferences, next) }
                    }
                    val unsourced = all?.unsourcedFiles ?: 0
                    if (offersSourceChoice) {
                        VaultCheckboxOption(
                            checked = current.sourcedOnly,
                            onCheckedChange = { update(current.copy(sourcedOnly = it)) },
                            label = tr("仅归档有来源记录的文件", "Only archive files with recorded sources", "ソースの記録があるファイルのみアーカイブ", "출처 기록이 있는 파일만 보관"),
                            supporting = when {
                                !current.sourcedOnly -> tr("自己上传或秒传的文件失效后无法重新添加", "Your uploads or instant saves cannot be added again if unavailable", "自分でアップロードまたはインスタント保存したファイルは、利用不可になると再追加できません", "직접 업로드하거나 즉시 저장한 파일은 사용 불가 상태가 되면 다시 추가할 수 없습니다")
                                unsourced > 0 -> tr("$unsourced 个无来源记录的文件留在网盘", "${enCount(unsourced, "file", "files")} without sources stay in drive", "ソースの記録がない $unsourced ファイルをドライブに保持", "출처 기록 없는 파일 ${unsourced}개를 드라이브에 유지")
                                else -> tr("无来源记录的文件留在网盘", "Keep files without source records in drive", "ソースの記録がないファイルはドライブに保持", "출처 기록 없는 파일은 드라이브에 유지")
                            },
                        )
                    } else if (counted != null && counted.files > 0 && counted.unsourcedFiles == counted.files) {
                        Text(
                            if (counted.files == 1) tr("此文件无来源记录，失效后无法重新添加", "This file has no source record and cannot be re-added if unavailable", "このファイルはソースの記録がなく、利用不可になると再追加できません", "이 파일은 출처 기록이 없어 사용 불가 상태가 되면 다시 추가할 수 없습니다") else tr("这些文件均无来源记录，失效后无法重新添加", "These files have no source records and cannot be re-added if unavailable", "これらのファイルはソースの記録がなく、利用不可になると再追加できません", "이 파일들은 출처 기록이 없어 사용 불가 상태가 되면 다시 추가할 수 없습니다"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    VaultCheckboxOption(
                        checked = current.toTrash,
                        onCheckedChange = { update(current.copy(toTrash = it)) },
                        label = tr("原文件放入回收站", "Move originals to trash", "元ファイルをゴミ箱に移動", "원본 파일을 휴지통으로 이동"),
                        supporting = if (current.toTrash) tr("清空回收站前仍可找回原文件", "Originals can be recovered before emptying trash", "ゴミ箱を空にするまでは元ファイルを復元できます", "휴지통을 비우기 전에는 원본을 복구할 수 있습니다") else tr("原文件将永久删除", "Original files will be permanently deleted", "元ファイルは完全に削除されます", "원본 파일이 영구 삭제됩니다"),
                    )
                    if (offersSizeChoice) {
                        VaultCheckboxOption(
                            checked = current.skipSmallFiles,
                            onCheckedChange = { update(current.copy(skipSmallFiles = it)) },
                            label = tr("不归档小文件", "Skip small files", "小さいファイルはアーカイブしない", "작은 파일 보관 안 함"),
                            supporting = tr("50 MiB 以下的文件留在网盘", "Keep files under 50 MiB in drive", "50 MiB未満のファイルはドライブに保持", "50 MiB 미만의 파일은 드라이브에 유지"),
                        )
                    }
                }
            }
        },
        confirmButton = {
            // 永久删除原文件时按钮换成错误色。复选框下的说明不染红：M3 的错误色只给校验出错，取消勾选不是错；
            // 在警告框里另加一行红字也试过，像补丁
            PikoDialogConfirm(
                label = tr("归档", "Archive", "アーカイブ", "보관"),
                destructive = !chosen.toTrash,
                enabled = files > 0 && options != null && blocked == null,
                onClick = {
                    session.archive(target, includeUnsourced = !sourcedOnly, onlyLargeFiles = skipSmallFiles, moveToTrash = chosen.toTrash)
                    onArchive()
                    onDismiss()
                },
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )
}

/** 两个对话框的开头：文件夹名单行截断，下面是统计结果。名字放进标题的话，长名字一换行，标题成了最吵的东西。 */
@Composable
private fun VaultDialogHeading(name: String, survey: Result<*>?, total: Int?, files: Int, bytes: Long, emptyText: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        when {
            survey == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                InlineLoadingIndicator()
                Spacer(Modifier.width(12.dp))
                Text(stringResource(Res.string.text_calculating_alt), style = MaterialTheme.typography.titleMedium)
            }
            survey.isFailure -> Text(stringResource(Res.string.text_calculation_failed_retry), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            total == 0 -> Text(emptyText, style = MaterialTheme.typography.titleMedium)
            else -> Text(tr("$files 个文件，${bytes.toReadableSize()}", "${enCount(files, "file", "files")}, ${bytes.toReadableSize()}", "$files ファイル、${bytes.toReadableSize()}", "파일 ${files}개, ${bytes.toReadableSize()}"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/**
 * 取消一个文件夹的归档之前的确认。先清点整棵树：多少条，其中几条能从回收站取回原文件，其余要占多少新空间；
 * 放不下时直接说明，不让人点了再失败。
 */
@Composable
internal fun RestoreVaultFolderDialog(
    folder: PikoPathBreadcrumb,
    session: FolderVaultSession,
    onDismiss: () -> Unit,
) {
    val survey by produceState<Result<FolderVaultSession.RestoreSurvey>?>(null, folder.id) { value = session.surveyRestore(folder) }
    val counted = survey?.getOrNull()
    val blocked = session.unavailableReason(dev.piko.shared.state.VaultWorkTargets(roots = setOf(folder.id)))
    PikoDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.text_undo_archive)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                VaultDialogHeading(folder.localizedName, survey, counted?.entries, counted?.entries ?: 0, counted?.bytes ?: 0L, emptyText = tr("没有归档的文件", "No archived files", "アーカイブ済みファイルはありません", "보관된 파일이 없습니다"))
                blocked?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (counted != null && counted.entries > 0) {
                    val parts = buildList {
                        if (counted.fromTrash > 0) add(tr("${counted.fromTrash} 个从回收站取回", "${counted.fromTrash} recovered from trash", "${counted.fromTrash} 件をゴミ箱から復元", "${counted.fromTrash}개 휴지통에서 복구"))
                        if (counted.neededBytes > 0) {
                            add(tr("需占用 ${counted.neededBytes.toReadableSize()}", "Requires ${counted.neededBytes.toReadableSize()}", "必要な容量 ${counted.neededBytes.toReadableSize()}", "필요 공간 ${counted.neededBytes.toReadableSize()}") + counted.remainingBytes?.let { tr("，剩余 ${it.toReadableSize()}", ", ${it.toReadableSize()} available", "、空き容量 ${it.toReadableSize()}", ", 남은 공간 ${it.toReadableSize()}") }.orEmpty())
                        }
                    }
                    if (parts.isNotEmpty()) {
                        Text(
                            parts.joinToString("；"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (counted.fits) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        )
                    }
                    if (!counted.fits) Text(stringResource(Res.string.text_not_enough_space), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            PikoDialogConfirm(
                label = tr("恢复到网盘", "Restore to drive", "ドライブに復元", "드라이브로 복원"),
                enabled = counted != null && counted.entries > 0 && counted.fits && blocked == null,
                onClick = {
                    session.restoreFolder(folder)
                    onDismiss()
                },
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )
}

@Composable
private fun VaultCheckboxOption(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    supporting: String?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            // 说明写的是勾或不勾的后果，做决定要看，与标签同一档字号，不缩成附注
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * 首次使用「归档」或「以引用保存」之前弹出的一次性风险提示弹窗。
 * 相比常驻在对话框里的文字说明，首次触发时的独立警告更能让用户明确意识到引用机制的丢失风险。
 */
@Composable
internal fun VaultRiskWarningDialog(
    onAcknowledge: () -> Unit,
    onDismiss: () -> Unit,
) {
    PikoDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(stringResource(Res.string.text_archive_and_reference_storage_risks))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(Res.string.text_archiving_and_saving_as_references_keep_only_a_few_kb_of_content_hashes_gcid),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(Res.string.text_future_playback_downloads_and_restoration_depend_entirely_on_pikpak_retainin),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    stringResource(Res.string.text_do_not_archive_unique_personal_files_sole_copies_of_uploads_or_important_fil),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            PikoDialogConfirm(
                label = tr("我已知晓风险，继续", "I understand the risks. Continue", "リスクを理解して続行", "위험을 이해했으며 계속"),
                destructive = true,
                onClick = onAcknowledge,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}
