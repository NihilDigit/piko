package dev.piko.ui.screens.drive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.data.repository.PathBreadcrumb
import dev.piko.data.repository.isPreviewableImage
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.isDriveFolderId
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.i18n.tr
import dev.piko.shared.naming.av.canonicalAvName
import dev.piko.shared.naming.av.canonicalAvNameOf
import dev.piko.shared.naming.av.parseAvForRename
import dev.piko.shared.naming.av.withAvFlags
import dev.piko.shared.scrape.MetaTubeService
import dev.piko.shared.state.DriveScreenState
import dev.piko.shared.state.FileWriteCommand
import dev.piko.shared.state.FileWriteSession
import dev.piko.shared.state.FolderVaultSession
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.CopyTargetDialog
import dev.piko.ui.components.FileNameField
import dev.piko.ui.components.MoveTargetDialog
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import dev.piko.ui.components.PikoSheet
import dev.piko.ui.components.SegmentDownloadSheet
import dev.piko.ui.components.SegmentSession
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.UnsupportedNameDialog
import dev.piko.ui.components.driveNameHint
import dev.piko.ui.components.isUnfixableDriveName
import dev.piko.ui.components.previewImageCoverUrl
import dev.piko.ui.components.submitDriveName
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.common_cancel
import dev.piko.ui.resources.common_close
import dev.piko.ui.resources.text_close_gallery
import dev.piko.ui.resources.text_closing_exits_the_gallery_and_clears_browsing_progress
import dev.piko.ui.resources.text_enter_a_path_in_the_address_bar_or_choose_a_recent_or_pinned_folder
import dev.piko.ui.resources.text_go_to_path
import dev.piko.ui.resources.text_normalize_names_by_catalog_code
import dev.piko.ui.screens.archive.ArchiveBrowsePasswordDialog
import dev.piko.ui.screens.rename.BatchRenameDialog
import dev.piko.ui.screens.rename.CanonicalFolderRenameDialog
import dev.piko.ui.screens.rename.MetaTubeTitleRow
import dev.piko.ui.screens.share.ShareDialog
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.flow.flowOf

/**
 * 网盘页用户触发对话框宿主：仅当 `dialog != null` 时渲染对应对话框；
 * 输入框草稿文本作为 UI element state 完全内聚在对应弹窗内部，打字不触发网盘主列表重组。
 */
@Composable
internal fun DriveDialogHost(
    dialog: DriveDialog?,
    state: DriveScreenState,
    vaultSession: FolderVaultSession,
    metaTube: MetaTubeService?,
    activeFolderId: String,
    displayedFiles: List<FileStat>,
    onDismiss: () -> Unit,
    onConfirmNewFolder: (String) -> Unit,
    onConfirmSingleRename: (id: String, newName: String) -> Unit,
    onBatchRenameFinished: (String) -> Unit,
    onShareCopied: () -> Unit,
    onConfirmMove: (ids: List<String>, targetId: String, targetName: String) -> Boolean,
    onConfirmCopy: (ids: List<String>, targetId: String, targetName: String) -> Boolean,
    onConfirmLibraryAction: (LibraryConfirm) -> Unit,
    onConfirmCloseGallery: (DriveViewMode?) -> Unit,
) {
    when (dialog) {
        null -> return
        DriveDialog.NewFolder -> {
            var folderName by rememberSaveable { mutableStateOf("") }
            NameInputDialog(
                title = tr("新建文件夹", "New folder", "新規フォルダー", "새 폴더", "新增資料夾"),
                label = tr("文件夹名称", "Folder name", "フォルダー名", "폴더 이름", "資料夾名稱"),
                value = folderName,
                onValueChange = { folderName = it },
                confirmLabel = tr("创建", "Create", "作成", "만들기", "建立"),
                confirmEnabled = folderName.isNotBlank(),
                onDismiss = onDismiss,
                onConfirm = onConfirmNewFolder,
            )
        }
        is DriveDialog.SingleRename -> {
            val metaTubeEnabled by remember(metaTube) { metaTube?.enabled ?: flowOf(false) }
                .collectAsStateWithLifecycle(initialValue = false)
            DriveSingleRenameDialog(
                dialog = dialog,
                metaTubeEnabled = metaTubeEnabled,
                metaTube = metaTube,
                onDismiss = onDismiss,
                onConfirmRename = onConfirmSingleRename,
            )
        }
        is DriveDialog.BatchRename -> {
            BatchRenameDialog(
                files = dialog.files,
                onDismiss = onDismiss,
                onFinished = onBatchRenameFinished,
                startWithAvNaming = dialog.startWithAvNaming,
            )
        }
        is DriveDialog.CanonicalFolder -> {
            CanonicalFolderRenameDialog(
                root = dialog.root,
                onDismiss = onDismiss,
                onFinished = onBatchRenameFinished,
            )
        }
        is DriveDialog.Share -> {
            ShareDialog(
                files = dialog.files,
                onDismiss = onDismiss,
                onCopied = onShareCopied,
            )
        }
        is DriveDialog.Move -> {
            val pendingIds = dialog.targetIds
            MoveTargetDialog(
                itemCount = pendingIds.size,
                movingIds = pendingIds,
                sourceParentId = activeFolderId,
                onDismiss = onDismiss,
                onConfirm = { targetId, targetName -> onConfirmMove(pendingIds.toList(), targetId, targetName) },
            )
        }
        is DriveDialog.Copy -> {
            val pendingIds = dialog.targetIds
            CopyTargetDialog(
                itemCount = pendingIds.size,
                copyingIds = pendingIds,
                onDismiss = onDismiss,
                onConfirm = { targetId, targetName -> onConfirmCopy(pendingIds.toList(), targetId, targetName) },
            )
        }
        is DriveDialog.Vault -> {
            val targetIds = dialog.scope.folders.map { it.id } + dialog.scope.files.map { it.id }
            VaultDialog(
                dialog.scope,
                vaultSession,
                onArchive = { if (targetIds.any { it in state.selectedFileIds }) state.exitSelection() },
                onDismiss = onDismiss,
            )
        }
        is DriveDialog.RestoreVault -> {
            RestoreVaultFolderDialog(
                PathBreadcrumb(dialog.folder.id, state.itemName(dialog.folder).headingText),
                vaultSession,
                onDismiss = onDismiss,
            )
        }
        is DriveDialog.LibraryAction -> {
            LibraryConfirmDialog(
                request = dialog.confirm,
                onConfirm = { onConfirmLibraryAction(dialog.confirm) },
                onDismiss = onDismiss,
            )
        }
        is DriveDialog.CloseGalleryConfirm -> {
            PikoDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(Res.string.text_close_gallery)) },
                text = { Text(stringResource(Res.string.text_closing_exits_the_gallery_and_clears_browsing_progress)) },
                confirmButton = {
                    PikoDialogConfirm(
                        tr("关闭", "Close", "閉じる", "닫기", "關閉"),
                        destructive = true,
                        onClick = { onConfirmCloseGallery(dialog.nextViewMode) },
                    )
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
                },
            )
        }
        is DriveDialog.ImagePreview -> {
            val previewables = remember(displayedFiles) {
                displayedFiles.filter { it.isPreviewableImage() && it.previewImageCoverUrl().isNotBlank() }
            }
            val startIndex = previewables.indexOfFirst { it.id == dialog.initialFile.id }
            if (startIndex < 0) {
                onDismiss()
            } else {
                ImageViewer(
                    images = previewables,
                    initialIndex = startIndex,
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

/**
 * 网盘页操作面板宿主（单条目面板、多选面板、窄屏跳转路径面板与窄屏目录图面板）。
 */
@Composable
internal fun DriveSheetHost(
    sheet: DriveSheet?,
    state: DriveScreenState,
    driveRepo: PikoDriveRepository,
    inTrash: Boolean,
    pathInTopBar: Boolean,
    displayedFiles: List<FileStat>,
    rowNotes: Map<String, String>,
    folderStack: List<PathBreadcrumb>,
    folderMap: FolderMapState,
    addressBar: @Composable () -> Unit,
    itemActionsOf: (FileStat) -> List<SheetAction>,
    selectionActionsOf: (List<FileStat>) -> List<SheetAction>,
    onCopySource: (FileStat) -> Unit,
    onUpdateFolderStack: (List<PathBreadcrumb>) -> Unit,
    onOpenFromMap: (List<PathBreadcrumb>, FileStat) -> Unit,
    onDismissSheet: () -> Unit,
) {
    when (sheet) {
        is DriveSheet.SelectionActions -> {
            val targets = remember(displayedFiles, sheet.targetIds) {
                displayedFiles.filter { it.id in sheet.targetIds }
            }
            if (targets.isEmpty() || !state.isSelectionMode) {
                onDismissSheet()
            } else {
                val target = PropertiesTarget.Selection(
                    targets,
                    targets.map { locationLabelOf(it, rowNotes, folderStack) }.distinct().singleOrNull()
                        ?: tr("多个位置", "Multiple locations", "複数の場所", "여러 위치", "多個位置"),
                )
                FileActionsSheet(
                    target = target,
                    actions = selectionActionsOf(targets),
                    onDismiss = onDismissSheet,
                )
            }
        }
        is DriveSheet.SingleItemActions -> {
            val target = sheet.file
            FileActionsSheet(
                name = state.itemName(target),
                locationLabel = rowNotes[target.id],
                folderUsage = remember(target.id, inTrash) {
                    if (target.isFolder && !inTrash && isDriveFolderId(target.id)) driveRepo.folderUsage(target.id) else null
                },
                actions = itemActionsOf(target),
                onDismiss = onDismissSheet,
                onCopySource = {
                    onDismissSheet()
                    onCopySource(target)
                },
            )
        }
        DriveSheet.MobileGoToPath -> {
            if (!pathInTopBar) {
                PikoSheet(
                    onDismissRequest = onDismissSheet,
                    sideSheetTitle = tr("转到路径", "Go to path", "パスへ移動", "경로로 이동", "前往路徑"),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(Res.string.text_go_to_path), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            TextButton(onClick = onDismissSheet) { Text(stringResource(Res.string.common_close)) }
                        }
                        addressBar()
                        Text(stringResource(Res.string.text_enter_a_path_in_the_address_bar_or_choose_a_recent_or_pinned_folder), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        null -> Unit
    }

    if (!pathInTopBar && folderMap.presence != FolderMapPresence.Closed && folderMap.presence != FolderMapPresence.Collapsed) {
        FolderMapSheet(folderMap, folderStack, onUpdateFolderStack, onOpenFromMap)
    }
}

/**
 * 后台会话驱动的全局浮层（压缩包密码请求、批量写操作进度、片段截取面板）：
 * `fileWrites.works` 仅在本组件内订阅，不影响网盘列表重组。
 */
@Composable
internal fun DriveSystemOverlays(
    state: DriveScreenState,
    isWorkspaceBrowser: Boolean,
    currentAccount: String?,
    fileWrites: FileWriteSession,
    segmentSession: SegmentSession,
    downloadManager: PikoDownloadCoordinator,
    onOpenTransfers: () -> Unit,
) {
    state.archivePasswordRequest?.let { request ->
        ArchiveBrowsePasswordDialog(
            archiveName = request.archiveName,
            incorrect = request.incorrect,
            onSubmit = state::submitArchivePassword,
            onDismiss = state::dismissArchivePassword,
        )
    }

    if (isWorkspaceBrowser) {
        val fileWriteWorks by fileWrites.works.collectAsStateWithLifecycle()
        BatchOperationDialog(
            work = fileWriteWorks.firstOrNull {
                it.account == currentAccount && it.isRunning &&
                    (it.totalSteps > 1 || it.command is FileWriteCommand.Copy)
            },
            trash = state.trashSession,
            onStopWrite = fileWrites::requestStop,
        )
    }

    val segmentTarget = segmentSession.file
    if (segmentTarget != null) {
        SegmentDownloadSheet(
            session = segmentSession,
            onConfirmDownload = { startByte, lengthBytes, timeLabel, startMs, endMs, streamUrl, quality ->
                downloadManager.enqueueSegment(
                    file = segmentTarget,
                    startMs = startMs,
                    endMs = endMs,
                    timeRangeLabel = timeLabel,
                    streamUrl = streamUrl,
                    startByte = startByte,
                    lengthBytes = lengthBytes,
                    quality = quality,
                )
                segmentSession.end()
                onOpenTransfers()
            },
            itemName = state.itemName(segmentTarget).headingText,
        )
    }
}

@Composable
private fun DriveSingleRenameDialog(
    dialog: DriveDialog.SingleRename,
    metaTubeEnabled: Boolean,
    metaTube: MetaTubeService?,
    onDismiss: () -> Unit,
    onConfirmRename: (String, String) -> Unit,
) {
    val target = dialog.target
    var renameNewName by rememberSaveable(target.id, dialog.initialName) { mutableStateOf(dialog.initialName) }
    var canonicalRename by rememberSaveable(target.id, dialog.startCanonical) { mutableStateOf(dialog.startCanonical) }

    val initialAv = remember(target.id, target.name, target.isFolder) {
        parseAvForRename(target.name, target.isFolder)
    }
    val canonicalName = remember(target.id, target.name, target.isFolder, initialAv) {
        when {
            target.isFolder -> initialAv?.let { canonicalAvName(target.name, it, isFolder = true) }
            else -> canonicalAvNameOf(target.name)
        }
    }
    val liveAv = remember(renameNewName, target.isFolder) {
        parseAvForRename(renameNewName, target.isFolder)
    }
    val normalizedWithCurrentFlags = remember(canonicalName, target.isFolder, liveAv?.uncensored, liveAv?.chineseSubtitles) {
        when {
            canonicalName == null -> null
            liveAv == null -> canonicalName
            else -> withAvFlags(canonicalName, isFolder = target.isFolder, uncensored = liveAv.uncensored, chineseSubtitles = liveAv.chineseSubtitles) ?: canonicalName
        }
    }
    val isAlreadyCanonical = normalizedWithCurrentFlags != null && renameNewName == normalizedWithCurrentFlags
    val showAvControls = canonicalName != null || initialAv != null || liveAv != null
    val showMetaTubeRow = canonicalName != null && (canonicalRename || isAlreadyCanonical) && metaTubeEnabled && metaTube != null

    NameInputDialog(
        title = tr("重命名", "Rename", "名前を変更", "이름 변경", "重新命名"),
        label = tr("新名称", "New name", "新しい名前", "새 이름", "新名稱"),
        value = renameNewName,
        onValueChange = { renameNewName = it },
        confirmLabel = tr("确定", "Confirm", "確認", "확인", "確定"),
        confirmEnabled = renameNewName.isNotBlank() && renameNewName != target.name,
        initialSelection = TextRange(0, if (target.isFolder) target.name.length else target.name.lastIndexOf('.').takeIf { it > 0 } ?: target.name.length),
        onDismiss = onDismiss,
        onConfirm = { name -> onConfirmRename(target.id, name) },
        extra = if (!showAvControls) null else {
            {
                val hasU = liveAv?.uncensored == true
                val hasC = liveAv?.chineseSubtitles == true
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = hasU,
                            enabled = liveAv != null,
                            onClick = {
                                withAvFlags(
                                    name = renameNewName,
                                    isFolder = target.isFolder,
                                    uncensored = !hasU,
                                    chineseSubtitles = hasC,
                                )?.let { renameNewName = it }
                            },
                            label = { Text("-U") },
                        )
                        FilterChip(
                            selected = hasC,
                            enabled = liveAv != null,
                            onClick = {
                                withAvFlags(
                                    name = renameNewName,
                                    isFolder = target.isFolder,
                                    uncensored = hasU,
                                    chineseSubtitles = !hasC,
                                )?.let { renameNewName = it }
                            },
                            label = { Text("-C") },
                        )
                        Spacer(Modifier.weight(1f))
                        if (normalizedWithCurrentFlags != null && !showMetaTubeRow) {
                            TextButton(
                                enabled = !isAlreadyCanonical,
                                onClick = {
                                    renameNewName = normalizedWithCurrentFlags
                                    canonicalRename = true
                                },
                            ) { Text(stringResource(Res.string.text_normalize_names_by_catalog_code)) }
                        }
                    }
                    if (showMetaTubeRow && metaTube != null) {
                        MetaTubeTitleRow(renameNewName, metaTube, isFolder = target.isFolder) { foundName ->
                            renameNewName = if (liveAv != null) {
                                withAvFlags(foundName, isFolder = target.isFolder, uncensored = liveAv.uncensored, chineseSubtitles = liveAv.chineseSubtitles) ?: foundName
                            } else {
                                foundName
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun NameInputDialog(
    title: String,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    confirmLabel: String,
    confirmEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    initialSelection: TextRange? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    val autoClean by LocalPikoServices.current.preferences.autoCleanNamesFlow.collectAsStateWithLifecycle(initialValue = false)
    var pendingName by remember { mutableStateOf<String?>(null) }
    val unfixable = isUnfixableDriveName(value)
    val canConfirm = confirmEnabled && !unfixable
    fun confirm() {
        pendingName = submitDriveName(value, autoClean, onConfirm)
    }
    PikoDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                FileNameField(
                    value = value,
                    onValueChange = onValueChange,
                    label = label,
                    modifier = Modifier.fillMaxWidth(),
                    isError = unfixable,
                    supportingText = driveNameHint(value, autoClean),
                    onDone = { if (canConfirm) confirm() },
                    autoFocus = true,
                    initialSelection = initialSelection,
                )
                extra?.invoke()
            }
        },
        confirmButton = {
            PikoDialogConfirm(confirmLabel, onClick = ::confirm, enabled = canConfirm)
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
    pendingName?.let { name ->
        UnsupportedNameDialog(
            name = name,
            onUseCleaned = { cleaned ->
                pendingName = null
                onConfirm(cleaned)
            },
            onDismiss = { pendingName = null },
        )
    }
}
