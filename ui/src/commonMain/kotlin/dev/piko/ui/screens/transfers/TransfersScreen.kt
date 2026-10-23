package dev.piko.ui.screens.transfers

import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableIntStateOf
import dev.piko.data.repository.isPlayableVideo
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.ListLeadingMedia

import dev.piko.ui.components.DetailHost
import dev.piko.ui.components.DetailPanelSpec
import dev.piko.ui.components.DetailPanelState
import dev.piko.ui.components.itemPanelAnchorArea
import androidx.compose.ui.layout.onPlaced
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.itemsIndexed

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import dev.piko.ui.components.PikoItemGrid
import dev.piko.ui.components.marqueeSelection
import dev.piko.ui.components.fullLineItem
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SyncAlt
import dev.piko.ui.components.ActionGroup
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import dev.piko.ui.components.withSelectAction
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import dev.piko.ui.components.PikoScaffold
import dev.piko.ui.components.IslandPage
import dev.piko.ui.theme.LocalFramed
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.snapshotFlow
import dev.piko.shared.state.TransferSections
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.RefreshBox
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.data.sourceUrl
import dev.piko.shared.state.children
import dev.piko.shared.state.TransferItem
import dev.piko.shared.state.TransferKind
import dev.piko.shared.state.TransfersState
import dev.piko.shared.state.isOutputDeleted
import dev.piko.shared.upload.UploadStatus
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.serverWorkNotices
import dev.piko.ui.LocalRootSnackbar
import dev.piko.ui.adaptive.isDesktopLayout
import dev.piko.ui.components.ContextMenuArea
import dev.piko.ui.components.pageFocusTarget
import dev.piko.ui.components.FileListSkeleton
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.SheetAction
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.ShortcutModifier
import io.github.nihildigit.pikpak.DriveTask
import kotlinx.coroutines.launch

/**
 * 传输页：本地下载、上传与云端离线任务合为一个列表，按「进行中」「需要处理」「已完成」分段，可按类型筛选。
 * 分段、筛选与选中在 [TransfersState]，这里只负责渲染与输入。
 *
 * 宽窗口一行一项、各列对齐，窄窗口上下排列。普通点击查看详情，勾选框、主修饰键和长按发起选择，
 * Shift 连选，鼠标双击执行主操作；查看对象与批量选择独立。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TransfersScreen(
    state: TransfersState,
    onNavigateToVideoPlayer: (fileId: String, fileName: String, localPath: String?) -> Unit,
    onCreateImport: (String) -> Unit = {},
    /** 跳到网盘里该文件所在目录。找不到（已移动或删除）时返回 false，由本页提示。 */
    onOpenCloudFile: suspend (fileId: String, fileName: String) -> Boolean = { _, _ -> false },
    /** 再点一次底栏的「传输」时加一：回到列表顶部。 */
    scrollToTopRequests: Int = 0,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val services = LocalPikoServices.current
    val platform = LocalPikoPlatform.current
    val desktop = isDesktopLayout()
    val vaultSession = services.folderVaultSession
    val snackbarHostState = LocalRootSnackbar.current
    // 找不到文件的提示走本页的 Snackbar，不用系统 Toast：Toast 不跟随 M3 主题与配色
    val openCloudFileById = { fileId: String, fileName: String ->
        scope.launch {
            if (!onOpenCloudFile(fileId, fileName)) {
                snackbarHostState.showSnackbar("文件已不存在", withDismissAction = true)
            }
        }
        Unit
    }
    val openCloudFile = { task: DriveTask -> openCloudFileById(task.fileId, task.fileName) }
    // 下载与网盘同受防窥开关约束。逐项揭示只在本次查看内有效，与网盘页的做法一致
    val isSpoilerBlurEnabled by services.preferences.spoilerBlurFlow
        .collectAsStateWithLifecycle(initialValue = true)
    val revealedKeys = rememberSaveable(saver = listSaver({ it.toList() }, { it.toMutableStateList() })) {
        mutableStateListOf<String>()
    }
    fun hasPreview(task: DownloadTask) = task.thumbnailLink.isNotEmpty() || task.destinationPath.isNotEmpty()
    fun blurred(item: TransferItem) = isSpoilerBlurEnabled && item.key !in revealedKeys

    // 只在本页可见期间轮询。挂在 STARTED 上：应用退到后台时 LaunchedEffect
    // 并不会取消，只靠它的话后台每 4 秒照样发一次请求
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(state, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            state.whileVisible()
        }
    }
    LaunchedEffect(state) {
        state.messages.collect { snackbarHostState.showSnackbar(it, withDismissAction = true) }
    }
    // 移动端的即时结局提示只在可见时收集；解压结局同时保留为可查询记录。
    // 记进改动日志的由主界面带「撤销」提示，这里不再说一遍。
    if (!desktop) {
        LaunchedEffect(services, lifecycleOwner) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                services.serverWorkNotices(withUndoable = false).collect { snackbarHostState.showSnackbar("${it.title}\n${it.message}", withDismissAction = true) }
            }
        }
    }
    LaunchedEffect(services) {
        services.transfersNotice.collect { snackbarHostState.showSnackbar(it, withDismissAction = true) }
    }

    // 只记本次查看：这一组是低价值的历史，默认收起
    var deletedExpanded by rememberSaveable { mutableStateOf(false) }
    // 展开了的文件夹下载，按组的 key。默认收起：一组可能有上千个文件
    val expandedBatches = rememberSaveable(saver = listSaver({ it.toList() }, { it.toMutableStateList() })) {
        mutableStateListOf<String>()
    }
    fun toggleBatch(key: String) {
        if (!expandedBatches.remove(key)) expandedBatches.add(key)
    }

    fun copySourceUrl(url: String) {
        platform.copyToClipboard("来源链接", url)
        scope.launch {
            snackbarHostState.showSnackbar(
                if (url.startsWith("magnet:", true)) "已复制磁力链接" else "已复制来源链接",
                withDismissAction = true,
            )
        }
    }

    // 片段的 fileId 是源视频的，播放器找不到本地文件时会按它退回云端，放出来的是整段原片。
    // 片段只该播本地文件，不给 fileId，打不开就报错
    val playLocal = { task: DownloadTask ->
        onNavigateToVideoPlayer(if (task.isSegment) "" else task.fileId, task.fileName,
            task.destinationPath.takeIf { task.status == dev.piko.download.DownloadStatus.COMPLETED })
    }
    val playCloud = { fileId: String, fileName: String ->
        onNavigateToVideoPlayer(fileId, fileName, null)
    }
    val resubmitAction = { task: DriveTask -> task.sourceUrl?.let { { state.resubmitCloud(task) } } }

    // 记 key 而不是条目本身：进度每半秒刷新，面板要跟着显示最新状态；条目被移除时面板随之关闭
    val detailState = dev.piko.ui.workbench.LocalWorkspaceContexts.current?.detailPanels?.stateFor(state, TaskDetailKey)
        ?: rememberSaveable(saver = DetailPanelState.keySaver(TaskDetailSpec)) { DetailPanelState<String>(TaskDetailSpec) }
    val detailsKey = detailState.target
    fun showDetails(key: String) {
        val anchor = detailState.anchors.beside(key)
        val at = if (anchor is dev.piko.ui.components.PanelAnchor.Beside)
            dev.piko.ui.components.PanelAnchor.AtPoint(androidx.compose.ui.unit.DpOffset((anchor.rect.right - TaskDetailSpec.maxWidth).coerceAtLeast(12.dp), anchor.rect.top))
        else anchor
        detailState.show(key, at)
    }
    var confirmingDeleteKeys by remember { mutableStateOf<Set<String>?>(null) }

    val localFiles = platform.localFiles

    fun openBatchFolder(batch: dev.piko.download.DownloadBatch, open: (String) -> Unit) {
        scope.launch { state.batchFolderPath(batch)?.let(open) }
    }

    fun batchIntents(item: TransferItem.LocalBatch): LocalBatchIntents {
        val id = item.batch.id
        return LocalBatchIntents(
            onToggleExpand = { toggleBatch(item.key) },
            onPause = { state.pauseBatch(id) },
            onResume = { state.resumeBatch(id) },
            onRetryListing = { state.retryListing(id) },
            onConfirm = { state.confirmListing(id) },
            onOpenFolder = { openBatchFolder(item.batch) { localFiles.openExternally(it, isMedia = false) } },
            onRevealFolder = { openBatchFolder(item.batch) { localFiles.openContainingFolder(it) } },
            onRemove = { state.removeBatch(id) },
        )
    }

    fun locateWriteId(work: dev.piko.shared.state.FileWriteWork): String? = when (work.command) {
        is dev.piko.shared.state.FileWriteCommand.Delete -> null
        is dev.piko.shared.state.FileWriteCommand.Copy -> work.command.target?.id?.takeIf { it.isNotEmpty() }
        else -> work.result?.createdId ?: work.result?.confirmed?.firstOrNull()
    }

    fun playActionFor(item: TransferItem): (() -> Unit)? = when (item) {
        is TransferItem.Local -> if (item.task.canPlayNow()) ({ playLocal(item.task) }) else null
        is TransferItem.Cloud -> if (item.task.canPlayVideo) ({ playCloud(item.task.fileId, item.task.displayName) }) else null
        is TransferItem.Instant -> if (item.record.canPlayVideo) ({ playCloud(item.record.locateId, item.record.name) }) else null
        is TransferItem.Upload -> if (item.task.canPlayVideo) ({ playCloud(requireNotNull(item.task.fileId), item.task.fileName) }) else null
        is TransferItem.Pack -> if (item.job.canOpen && item.job.folderName.isPlayableVideo()) ({ playCloud(item.job.outputId, item.job.folderName) }) else null
        else -> null
    }

    // 一项的全部操作，详情面板与右键菜单共用：两处给的总是同一组
    fun actionsFor(item: TransferItem): List<SheetAction> {
        val base = when (item) {
            is TransferItem.UploadPreparing -> uploadPreparationActions(item.work,
                item.work.parentId.takeIf { it.isNotEmpty() }?.let { { openCloudFileById(it, item.work.parentName) } }, {
                    if (item.work.hasSideEffects) { detailState.close(); state.selectOnly(item.key); confirmingDeleteKeys = setOf(item.key) }
                    else state.remove(item)
                })
            is TransferItem.UploadBatch -> uploadBatchActions(item, item.key in expandedBatches,
                { toggleBatch(item.key) }, { state.pauseUploadBatch(item.batchId) },
                { state.resumeUploadBatch(item.batchId) }, {
                    detailState.close()
                    if (item.key !in state.selectedKeys) state.selectOnly(item.key)
                    confirmingDeleteKeys = setOf(item.key)
                },
                onShowInDrive = item.parentId.takeIf { it.isNotEmpty() }?.let { { openCloudFileById(it, item.parentName) } },
            )
            is TransferItem.LocalBatch -> localBatchActions(item, item.key in expandedBatches, batchIntents(item))
            is TransferItem.Local -> localTransferActions(
                task = item.task,
                files = localFiles,
                onPlay = { playLocal(item.task) },
                onStart = { state.resumeLocal(item.task.taskId) },
                onPause = { state.pauseLocal(item.task.taskId) },
                onRemove = { state.removeLocal(item.task.taskId) },
                previewHidden = if (isSpoilerBlurEnabled && hasPreview(item.task)) item.key !in revealedKeys else null,
                onTogglePreview = { if (!revealedKeys.remove(item.key)) revealedKeys.add(item.key) },
                onShowInDrive = item.task.fileId.takeIf { it.isNotEmpty() && !item.task.isSegment }?.let { { openCloudFileById(it, item.task.fileName) } },
            )
            is TransferItem.Upload -> uploadTransferActions(
                task = item.task,
                onPlay = playActionFor(item),
                onOpen = { item.task.fileId?.let { openCloudFileById(it, item.task.fileName) } },
                onOpenParent = item.task.parentId.takeIf { it.isNotEmpty() }?.let { { openCloudFileById(it, item.task.parentName) } },
                onResume = { state.resumeUpload(item.task.taskId) },
                onPause = { state.pauseUpload(item.task.taskId) },
                onRemove = { state.removeUpload(item.task.taskId) },
                onRevealLocal = item.task.sourceUri.takeIf { it.isNotEmpty() && !it.startsWith("content:") }?.let { { localFiles.openContainingFolder(it) } },
            )
            is TransferItem.Cloud -> cloudTransferActions(
                task = item.task,
                onPlay = playActionFor(item),
                onResubmit = resubmitAction(item.task),
                onDelete = { state.deleteCloud(item.task.id) },
                onOpen = { openCloudFile(item.task) },
                onCopySource = item.task.sourceUrl?.let { url -> { copySourceUrl(url) } },
            )
            is TransferItem.Pack -> packTransferActions(
                item = item,
                onOpen = { openCloudFileById(item.job.outputId, item.job.folderName) },
                onRetry = { state.retryPack(item.job.taskId) },
                onDiscard = { state.discardPack(item.job.taskId) },
                onCopySource = item.job.url.takeIf { it.isNotEmpty() }?.let { url -> { copySourceUrl(url) } },
            )
            is TransferItem.Instant -> instantTransferActions(
                record = item.record,
                onPlay = playActionFor(item),
                onOpen = { openCloudFileById(item.record.locateId, item.record.name) },
                onRemove = { state.removeInstant(item.record.id) },
            )
            is TransferItem.InstantAttempt -> instantAttemptActions(item.work,
                item.work.locateId?.let { { openCloudFileById(it, item.work.title) } },
                {
                    if (item.work.receipt?.requiresInspection == true) {
                        detailState.close(); state.selectOnly(item.key); confirmingDeleteKeys = setOf(item.key)
                    } else state.remove(item)
                },
                onRecheck = if (services.instantSaveRecords.canRecheck(item.work.id)) ({ services.instantSaveRecords.recheck(item.work.id, services.driveRepository); Unit }) else null,
            )
            is TransferItem.Share -> shareTransferActions(item.work, state.shareUndoStatus(item.work), state.canUndoShare(item.work), services.shareSaves.canRecheck(item.work.id),
                { state.undoShare(item.work) }, { services.shareSaves.recheck(item.work.id) }, {
                    if (item.work.result?.unconfirmedSources?.isNotEmpty() == true || item.work.pendingRenames.isNotEmpty()) {
                        detailState.close(); state.selectOnly(item.key); confirmingDeleteKeys = setOf(item.key)
                    } else state.remove(item)
                },
                (item.work.associatedOutputs.values.firstOrNull() ?: item.work.command.target.id.takeIf { it.isNotEmpty() })?.let { { openCloudFileById(it, item.work.title) } },
            )
            is TransferItem.OfflineSubmit -> offlineSubmitActions(item.work,
                (item.work.outputId ?: item.work.target.id.takeIf { it.isNotEmpty() })?.let { { openCloudFileById(it, item.work.title) } },
                {
                    if (item.work.status == dev.piko.shared.state.OfflineSubmitStatus.UNCONFIRMED) {
                        detailState.close(); state.selectOnly(item.key); confirmingDeleteKeys = setOf(item.key)
                    } else state.remove(item)
                },
            )
            is TransferItem.Extract -> extractTransferActions(
                item = item,
                onShowInDrive = { openCloudFileById(item.job.file.id, item.job.file.name) },
                onSkip = { state.skipExtract(item.job.id) },
                onRecheck = { state.recheckExtract(item.job.id) },
                onInputPassword = if (item.job.status is dev.piko.shared.state.ArchiveJobStatus.NeedsPassword) ({ state.promptExtractPassword(item.job.id); Unit }) else null,
            )
            is TransferItem.Vault -> vaultTransferActions(
                item, state.isVaultStopping, { state.stopVault(item.work.id) }, { state.dismissVault(item.work.id) },
                item.record?.let(state::canUndoVault) == true, item.record?.let(state::vaultUndoStatus),
                { item.record?.let(state::undoVault) },
                onCreateImport,
            )
            is TransferItem.Write -> {
                val work = item.work
                fileWriteActions(work, state.writeUndoStatus(work), state.canUndoWrite(work), services.fileWrites.canRecheck(work.id),
                    { state.undoWrite(work) }, { services.fileWrites.recheck(work.id) },
                    { if (work.result?.unconfirmed?.isNotEmpty() == true) { detailState.close(); state.selectOnly(item.key); confirmingDeleteKeys = setOf(item.key) } else state.remove(item) },
                    locateWriteId(work)?.let { { openCloudFileById(it, work.command.title) } },
                )
            }
            is TransferItem.VaultEdit -> vaultEditActions(item.work, state.vaultEditUndoStatus(item.work), state.canUndoVaultEdit(item.work), services.vaultEntryEdits.canRecheck(item.work.id),
                { state.undoVaultEdit(item.work) }, { services.vaultEntryEdits.recheck(item.work.id) }, {
                    if (item.work.result?.unconfirmed?.isNotEmpty() == true) {
                        detailState.close(); state.selectOnly(item.key); confirmingDeleteKeys = setOf(item.key)
                    } else state.remove(item)
                })
            is TransferItem.History -> historyTransferActions(item.work, services.historyEdits.canRecheck(item.work.id),
                { services.historyEdits.recheck(item.work.id) }, {
                    if (item.work.result?.completion in setOf(dev.piko.shared.state.HistoryEditCompletion.PARTIAL, dev.piko.shared.state.HistoryEditCompletion.UNCONFIRMED)) {
                        detailState.close(); state.selectOnly(item.key); confirmingDeleteKeys = setOf(item.key)
                    } else state.remove(item)
                })
        }
        return base + SheetAction(Icons.Outlined.Info, "详情", { showDetails(item.key) }, group = ActionGroup.Properties)
    }

    // 点按一项做的事，轻点与鼠标双击共用；不弹详情面板，详情在右键菜单与触屏长按里
    fun primaryActionFor(item: TransferItem): (() -> Unit)? = when (item) {
        is TransferItem.UploadPreparing -> item.work.parentId.takeIf { it.isNotEmpty() }?.let { { openCloudFileById(it, item.work.parentName) } }
        is TransferItem.UploadBatch -> ({ toggleBatch(item.key) })
        is TransferItem.LocalBatch -> localBatchPrimaryAction(item, batchIntents(item))
        is TransferItem.Local -> localPrimaryAction(
            task = item.task,
            files = localFiles,
            onPlay = { playLocal(item.task) },
            onStart = { state.resumeLocal(item.task.taskId) },
            onPause = { state.pauseLocal(item.task.taskId) },
        )
        is TransferItem.Upload -> uploadPrimaryAction(
            task = item.task,
            onPlay = playActionFor(item),
            onOpen = { item.task.fileId?.let { openCloudFileById(it, item.task.fileName) } },
            onResume = { state.resumeUpload(item.task.taskId) },
            onPause = { state.pauseUpload(item.task.taskId) },
        )
        is TransferItem.Cloud -> cloudPrimaryAction(item.task, playActionFor(item), resubmitAction(item.task)) { openCloudFile(item.task) }
        is TransferItem.Pack -> playActionFor(item) ?: packPrimaryAction(
            item = item,
            onOpen = { openCloudFileById(item.job.outputId, item.job.folderName) },
            onRetry = { state.retryPack(item.job.taskId) },
        )
        is TransferItem.Instant -> playActionFor(item) ?: item.record.locateId.takeIf { it.isNotEmpty() }?.let { { openCloudFileById(it, item.record.name) } }
        is TransferItem.InstantAttempt -> item.work.locateId?.let { { openCloudFileById(it, item.work.title) } }
        is TransferItem.Share -> (item.work.associatedOutputs.values.firstOrNull() ?: item.work.command.target.id.takeIf { it.isNotEmpty() })?.let { { openCloudFileById(it, item.work.title) } }
        is TransferItem.OfflineSubmit -> (item.work.outputId ?: item.work.target.id.takeIf { it.isNotEmpty() })?.let { { openCloudFileById(it, item.work.title) } }
        is TransferItem.Extract -> if (item.job.status is dev.piko.shared.state.ArchiveJobStatus.NeedsPassword) {
            { state.promptExtractPassword(item.job.id); Unit }
        } else {
            { openCloudFileById(item.job.file.id, item.job.file.name) }
        }
        is TransferItem.Vault -> null
        is TransferItem.Write -> locateWriteId(item.work)?.let { { openCloudFileById(it, item.work.command.title) } }
        is TransferItem.History -> null
        is TransferItem.VaultEdit -> null
    }

    // 眼前列出的先后，连选与全选按它。收起的「文件已删除」组不在其中，展开的文件夹下载连同其中的文件
    val selectionActive = state.selectedKeys.isNotEmpty()
    val checkboxActive = state.checkboxMode
    val liveSections = state.sectionsOf(state.filter)
    val frozenOrder = remember(state.filter, selectionActive) { if (selectionActive) TaskListOrder(liveSections) else null }
    val currentSections = frozenOrder?.apply(liveSections) ?: liveSections
    // 未手动切换时，有待处理或在途工作就收起历史；每个类别保留自己的展开选择。
    val completedExpansion = remember { androidx.compose.runtime.mutableStateMapOf<TransferKind, Boolean>() }
    fun completedExpanded(kind: TransferKind, sections: TransferSections) = completedExpansion[kind]
        ?: (sections.needsAttention.isEmpty() && sections.inProgress.isEmpty())
    val visibleOrder = (currentSections.needsAttention + currentSections.inProgress +
        (if (completedExpanded(state.filter, currentSections)) currentSections.completed else emptyList()) +
        if (deletedExpanded) currentSections.outputDeleted else emptyList()).withBatchChildren(expandedBatches).map { it.key }
    // 列表的键盘焦点：鼠标按进列表时取得（pageFocusTarget），Delete、Esc 与全选才有处可去
    val listFocus = remember { FocusRequester() }
    fun focusList() = runCatching { listFocus.requestFocus() }

    val selected = state.selectedItems
    val bulkSelected = selected
    val pauseSelected = bulkSelected.filter(state::isPausable).takeIf { it.isNotEmpty() }?.let { items -> { items.forEach(state::pause) } }
    val resumeSelected = bulkSelected.filter(state::isResumable).takeIf { it.isNotEmpty() }?.let { items -> { items.forEach(state::resume) } }

    fun contextActionsFor(item: TransferItem): List<SheetAction> {
        if (item.key in state.selectedKeys && bulkSelected.size > 1) {
            val pausableCount = bulkSelected.count(state::isPausable)
            val resumableCount = bulkSelected.count(state::isResumable)
            return buildList {
                pauseSelected?.let { add(SheetAction(Icons.Outlined.Pause, "暂停所选 $pausableCount 项", it)) }
                resumeSelected?.let { add(SheetAction(Icons.Outlined.PlayArrow, "继续所选 $resumableCount 项", it)) }
                if (bulkSelected.size < visibleOrder.size) {
                    add(SheetAction(Icons.Outlined.SelectAll, "全选", { state.selectAll(visibleOrder) }, group = ActionGroup.Select))
                }
                add(SheetAction(Icons.Outlined.Close, "取消选择", state::clearSelection, group = ActionGroup.Select))
                add(SheetAction(Icons.Outlined.Delete, "删除所选 ${bulkSelected.size} 项", { confirmingDeleteKeys = state.selectedKeys.toSet() }, destructive = true))
            }
        }
        return actionsFor(item)
    }

    val haptic = LocalHapticFeedback.current
    fun rowSelection(item: TransferItem) = RowSelection(
        active = checkboxActive,
        selected = item.key in state.selectedKeys,
        onToggle = { state.toggleSelected(item.key) },
        onLongClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            showDetails(item.key)
        },
    )

    val taskPresenter = remember(state, vaultSession) { TaskPresenter(state, vaultSession) }
    val density = LocalDensity.current
    var taskContentWidth by remember { mutableIntStateOf(0) }
    val taskLayout = TaskRowLayout.forWidth(with(density) { taskContentWidth.toDp() })
    // 相邻分页也会参与组合；行状态不能从当前筛选读取。
    val rowSections = state.sectionsOf(TransferKind.ALL)
    val completedKeys = (rowSections.completed + rowSections.outputDeleted).mapTo(HashSet()) { it.key }
    val attentionKeys = rowSections.needsAttention.mapTo(HashSet()) { it.key }

    fun pauseOrResumeAction(item: TransferItem): SheetAction? = when {
        state.isPausable(item) -> SheetAction(Icons.Outlined.Pause, "暂停", { state.pause(item) })
        state.isResumable(item) -> {
            val isFailed = (item is TransferItem.Local && item.task.status == DownloadStatus.FAILED) ||
                (item is TransferItem.Upload && item.task.status == UploadStatus.FAILED)
            SheetAction(if (isFailed) Icons.Outlined.Refresh else Icons.Outlined.PlayArrow, if (isFailed) "重试" else "继续", { state.resume(item) })
        }
        else -> actionsFor(item).firstOrNull { it.label.startsWith("停止") || it.label == "重新查询结果" || it.label == "解析来源链接" }
    }

    fun quickAction(item: TransferItem): SheetAction? = when (item) {
        is TransferItem.Local -> when {
            item.task.status == DownloadStatus.COMPLETED -> if (item.task.isMedia()) {
                SheetAction(Icons.Outlined.PlayArrow, "播放", { playLocal(item.task) })
            } else {
                SheetAction(Icons.Outlined.FolderOpen, "打开所在文件夹", { localFiles.openContainingFolder(item.task.destinationPath) })
            }
            item.task.canPlayWhileDownloading() -> SheetAction(Icons.Outlined.PlayArrow, "播放", { playLocal(item.task) })
            else -> pauseOrResumeAction(item)
        }
        is TransferItem.Cloud -> when {
            item.task.canPlayVideo -> SheetAction(Icons.Outlined.PlayArrow, "播放", { playCloud(item.task.fileId, item.task.displayName) })
            item.task.canOpen -> SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "在网盘中显示", { openCloudFile(item.task) })
            item.task.phase == io.github.nihildigit.pikpak.TaskPhase.ERROR && !item.task.isOutputDeleted ->
                resubmitAction(item.task)?.let { SheetAction(Icons.Outlined.Refresh, "重试", it) }
            else -> null
        }
        is TransferItem.Instant -> when {
            item.record.canPlayVideo -> SheetAction(Icons.Outlined.PlayArrow, "播放", { playCloud(item.record.locateId, item.record.name) })
            item.record.locateId.isNotEmpty() -> SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "在网盘中显示", { openCloudFileById(item.record.locateId, item.record.name) })
            else -> null
        }
        is TransferItem.Upload -> when {
            item.task.canPlayVideo -> SheetAction(Icons.Outlined.PlayArrow, "播放", { playCloud(requireNotNull(item.task.fileId), item.task.fileName) })
            item.task.canOpen -> SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "在网盘中显示", { openCloudFileById(requireNotNull(item.task.fileId), item.task.fileName) })
            else -> pauseOrResumeAction(item)
        }
        is TransferItem.Pack -> when {
            item.job.canOpen && item.job.folderName.isPlayableVideo() -> SheetAction(Icons.Outlined.PlayArrow, "播放", { playCloud(item.job.outputId, item.job.folderName) })
            item.job.canOpen -> SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "在网盘中显示", { openCloudFileById(item.job.outputId, item.job.folderName) })
            !item.isBusy && item.job.stage == dev.piko.shared.data.OfflinePackStage.FAILED -> SheetAction(Icons.Outlined.Refresh, "重试", { state.retryPack(item.job.taskId) })
            else -> null
        }
        else -> pauseOrResumeAction(item) ?: actionsFor(item).firstOrNull {
            it.label == "输入解压密码" || it.label.startsWith("停止") || it.label == "重新查询结果" || it.label == "重试" || it.label == "仍然下载"
        }
    }

    fun extraAction(item: TransferItem): SheetAction? {
        val expanded = item.key in expandedBatches
        return when (item) {
            is TransferItem.LocalBatch, is TransferItem.UploadBatch -> if (item.children.isNotEmpty()) SheetAction(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                if (expanded) "收起文件" else "展开文件",
                { toggleBatch(item.key) },
            ) else null
            is TransferItem.Local -> when {
                item.task.status == DownloadStatus.COMPLETED && item.task.isMedia() && taskLayout.wide ->
                    SheetAction(Icons.Outlined.FolderOpen, "打开所在文件夹", { localFiles.openContainingFolder(item.task.destinationPath) })
                item.task.canPlayWhileDownloading() -> pauseOrResumeAction(item)
                else -> null
            }
            is TransferItem.Cloud -> if (item.task.canPlayVideo) {
                SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "在网盘中显示", { openCloudFile(item.task) })
            } else null
            is TransferItem.Instant -> if (item.record.canPlayVideo) {
                SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "在网盘中显示", { openCloudFileById(item.record.locateId, item.record.name) })
            } else null
            is TransferItem.Upload -> if (item.task.canPlayVideo) {
                SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "在网盘中显示", { openCloudFileById(requireNotNull(item.task.fileId), item.task.fileName) })
            } else null
            is TransferItem.Pack -> if (item.job.canOpen && item.job.folderName.isPlayableVideo()) {
                SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "在网盘中显示", { openCloudFileById(item.job.outputId, item.job.folderName) })
            } else null
            else -> null
        }
    }

    @Composable
    fun TransferLeadingVisual(item: TransferItem) {
        when (item) {
            is TransferItem.Local -> LocalTransferVisual(item.task, blurred(item))
            is TransferItem.LocalBatch -> TaskFolderVisual(item.batch.folderName)
            is TransferItem.UploadBatch -> if (item.isFolder) TaskFolderVisual(item.title) else ListLeadingIcon(Icons.Outlined.Upload)
            is TransferItem.Upload -> {
                val isVisualFile = item.task.fileName.isPlayableVideo() ||
                    item.task.fileName.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
                val uploadThumb = remember(item.task.sourceUri, item.task.fileName) {
                    localFiles.thumbnailModel(item.task.sourceUri).takeIf { isVisualFile }
                } ?: item.task.fileId?.let(state::thumbnailOf)
                ListLeadingMedia(
                    thumbnail = uploadThumb,
                    fallback = { TaskFileTypeIcon(item.task.fileName, Icons.Outlined.Upload) },
                    isSpoilerBlurred = blurred(item),
                    showPlayOverlay = item.task.canPlayVideo,
                )
            }
            is TransferItem.Cloud -> ListLeadingMedia(
                thumbnail = state.thumbnailOf(item.task.fileId),
                fallback = { TaskFileTypeIcon(item.task.fileName.ifEmpty { item.task.displayName }, Icons.Outlined.CloudDownload) },
                isSpoilerBlurred = blurred(item),
                showPlayOverlay = item.task.canPlayVideo,
            )
            is TransferItem.Pack -> ListLeadingMedia(
                thumbnail = state.thumbnailOf(item.job.outputId),
                fallback = { TaskFolderVisual(item.job.folderName) },
                isSpoilerBlurred = blurred(item),
                showPlayOverlay = item.job.canOpen && item.job.folderName.isPlayableVideo(),
            )
            is TransferItem.Instant -> ListLeadingMedia(
                thumbnail = state.thumbnailOf(item.record.locateId),
                fallback = {
                    if (item.record.fileCount > 1 && !item.record.name.contains('.')) {
                        TaskFolderVisual(item.record.name)
                    } else {
                        TaskFileTypeIcon(item.record.name, Icons.Outlined.Bolt)
                    }
                },
                isSpoilerBlurred = blurred(item),
                showPlayOverlay = item.record.canPlayVideo,
            )
            is TransferItem.Extract -> TaskFileTypeIcon(item.job.file.name, Icons.Outlined.Unarchive)
            is TransferItem.Vault -> TaskFolderVisual(item.folderName)
            else -> ListLeadingIcon(taskPresenter.present(item).icon)
        }
    }

    @Composable
    fun TransferRow(item: TransferItem, index: Int, count: Int) {
        TaskRow(
            taskPresenter.present(item), rowSelection(item), taskLayout, index, count,
            item = item,
            completed = when (item) {
                is TransferItem.Local -> item.task.status == DownloadStatus.COMPLETED
                is TransferItem.Upload -> item.task.status == UploadStatus.COMPLETED
                else -> item.key in completedKeys
            },
            attention = item.key in attentionKeys || when (item) {
                is TransferItem.Local -> item.task.status == DownloadStatus.FAILED
                is TransferItem.Upload -> item.task.status == UploadStatus.FAILED
                else -> false
            },
            onOpen = { primaryActionFor(item)?.invoke() ?: showDetails(item.key) }, onToggle = { state.toggleSelected(item.key) },
            quickAction = quickAction(item),
            extraAction = extraAction(item),
            onLeadingClick = playActionFor(item),
            leadingVisual = { TransferLeadingVisual(item) },
        )
    }

    // 右键弹出操作与详情项。animateItem 这类条目修饰挂在外层，菜单锚点才跟着条目走
    val renderItem: @Composable (TransferItem, Modifier, Int, Int) -> Unit = { item, itemModifier, index, count ->
        val rowQuick = quickAction(item)
        val rowExtra = extraAction(item)
        val trailingCount = (if (rowQuick != null) 1 else 0) + (if (rowExtra != null) 1 else 0)
        val hasLeadingClick = !checkboxActive && playActionFor(item) != null
        ContextMenuArea(
            actions = { contextActionsFor(item) },
            modifier = itemModifier,
        ) {
            Box(
                Modifier
                    .onPlaced { detailState.anchors.placed(item.key, it) }
                    .transferClicks(
                        // 多选态下单击照网盘页是勾选；平时只选中这一项
                        onSelect = {
                            if (checkboxActive) state.toggleSelected(item.key) else state.selectOnly(item.key)
                            focusList()
                        },
                        onToggle = {
                            state.toggleSelected(item.key)
                            focusList()
                        },
                        onExtend = {
                            state.selectRange(item.key, visibleOrder)
                            focusList()
                        },
                        onOpen = { primaryActionFor(item)?.invoke() ?: showDetails(item.key) },
                        trailingPassThrough = if (trailingCount == 0) 0.dp else (trailingCount * 48 + 12).dp,
                        leadingPassThrough = if (hasLeadingClick) 68.dp else 0.dp,
                    ),
            ) {
                TransferRow(item, index, count)
            }
        }
    }

    // 每一类一页、各有滚动位置：横划时相邻那一页跟着手指进来，切回来还停在原处
    val kinds = TransferKind.entries
    val gridStates = remember { kinds.associateWith { LazyGridState() } }
    // 只响应进页之后的变化：计数器由主界面持有，切回本页时它已是旧值，不该再滚一次
    val initialScrollRequests = remember { scrollToTopRequests }
    LaunchedEffect(scrollToTopRequests) {
        if (scrollToTopRequests != initialScrollRequests) gridStates.getValue(state.filter).animateScrollToItem(0)
    }

    // 页与筛选双向同步：划停在哪一页就筛哪一类，点页头的筛选则滑到那一页。筛选取 settledPage 而不是
    // currentPage：划到一半就换筛选会清掉选中项，手指退回原页时选中已经没了
    val pagerState = rememberPagerState(initialPage = kinds.indexOf(state.filter)) { kinds.size }
    LaunchedEffect(state, pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (kinds[page] != state.filter) state.changeFilter(kinds[page])
        }
    }
    LaunchedEffect(state, state.filter) {
        val page = kinds.indexOf(state.filter)
        if (pagerState.targetPage != page) pagerState.animateScrollToPage(page)
    }

    // 触屏上退出多选靠返回键；键盘的 Esc 在下面的 onKeyEvent 里先接住
    BackHandler(enabled = selectionActive) { state.clearSelection() }

    // 文件夹下载里的文件不在各段里，要到各组里找
    val detailsItem = detailsKey?.let { key ->
        sequenceOf(state.inProgress, state.needsAttention, state.completed, state.outputDeleted)
            .flatten()
            .flatMap { item -> sequenceOf(item) + item.children }
            .firstOrNull { it.key == key }
    }
    val closeDetails = detailState::close
    @Composable
    fun TaskDetails(modifier: Modifier) {
        val current = detailsItem ?: return
        val actions = withSelectAction(
            actionsFor(current).filterNot { it.group == ActionGroup.Properties },
            if (LocalPointerSource.current.isTouchLike && !checkboxActive) ({ closeDetails(); state.enterSelection(current.key) }) else null,
        )
        when (current) {
            is TransferItem.UploadBatch -> UploadBatchDetails(current, actions, modifier = modifier)
            is TransferItem.LocalBatch -> LocalBatchDetails(current, actions, modifier = modifier)
            is TransferItem.Local -> LocalTransferDetails(current.task, actions, modifier = modifier)
            is TransferItem.Upload -> UploadTransferDetails(current.task, actions, modifier = modifier)
            is TransferItem.UploadPreparing -> UploadPreparingDetails(current.work, actions, modifier = modifier)
            is TransferItem.Cloud -> CloudTransferDetails(current.task, actions, modifier = modifier)
            is TransferItem.Pack -> PackTransferDetails(current, actions, modifier = modifier)
            is TransferItem.Instant -> InstantTransferDetails(current.record, actions, modifier = modifier)
            is TransferItem.InstantAttempt -> InstantAttemptDetails(current.work, actions, modifier = modifier)
            is TransferItem.Share -> ShareTransferDetails(current.work, state.shareUndoStatus(current.work), actions, modifier = modifier)
            is TransferItem.OfflineSubmit -> OfflineSubmitDetails(current.work, actions, modifier = modifier)
            is TransferItem.Extract -> ExtractTransferDetails(current, actions, modifier = modifier)
            is TransferItem.Vault -> VaultTransferDetails(current, vaultSession, actions, current.record?.let(state::vaultUndoStatus), modifier = modifier)
            is TransferItem.Write -> FileWriteTransferDetails(current.work, state.writeUndoStatus(current.work), actions, modifier = modifier)
            is TransferItem.History -> HistoryTransferDetails(current.work, actions, modifier = modifier)
            is TransferItem.VaultEdit -> VaultEditTransferDetails(current.work, state.vaultEditUndoStatus(current.work), actions, modifier = modifier)
        }
    }

    val transfersListBody: @Composable (PaddingValues) -> Unit = { innerPadding ->
        // 下拉刷新包住三种样子：空状态与骨架也要能拉，刚装好、一项传输也没有时正想看看云端有没有
        RefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = state::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val sidePadding = SidePadding
            Column(Modifier.fillMaxSize()) {
                val phase = when {
                    !state.isEmpty -> TransfersPhase.CONTENT
                    // 云端列表首次取回之前不下结论，免得空状态一闪而过
                    state.isLoading -> TransfersPhase.LOADING
                    else -> TransfersPhase.EMPTY
                }
                Crossfade(
                    targetState = phase,
                    animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                    modifier = Modifier.fillMaxSize(),
                    label = "transfersPhase",
                ) { current ->
                    when (current) {
                        // 横划切换类别，照 M3 tabs 的内容区横划：相邻一类跟着手指进来。只认手指：鼠标在列表上
                        // 按住拖动是框选（marqueeSelection），分页不能抢
                        TransfersPhase.CONTENT -> HorizontalPager(
                            state = pagerState,
                            userScrollEnabled = LocalPointerSource.current.isTouchLike,
                            key = { kinds[it] },
                            modifier = Modifier
                                .fillMaxSize()
                                .pageFocusTarget(listFocus)
                                .onKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                    val primary = platform.shortcutModifier.isPressed(event)
                                    val deleteKey = event.key == Key.Delete ||
                                        (platform.shortcutModifier == ShortcutModifier.Command && primary && event.key == Key.Backspace)
                                    when {
                                        deleteKey && selectionActive -> confirmingDeleteKeys = state.selectedKeys.toSet()
                                        event.key == Key.Escape && selectionActive -> state.clearSelection()
                                        primary && event.key == Key.A -> state.selectAll(visibleOrder)
                                        else -> return@onKeyEvent false
                                    }
                                    true
                                },
                        ) { page ->
                            val kind = kinds[page]
                            val sections = if (kind == state.filter) currentSections else state.sectionsOf(kind)
                            TransfersList(
                                state = state,
                                kind = kind,
                                sections = sections,
                                completedExpanded = completedExpanded(kind, sections),
                                onToggleCompleted = { completedExpansion[kind] = !completedExpanded(kind, sections) },
                                sidePadding = sidePadding,
                                deletedExpanded = deletedExpanded,
                                onToggleDeleted = { deletedExpanded = !deletedExpanded },
                                expandedBatches = expandedBatches,
                                renderItem = renderItem,
                                gridState = gridStates.getValue(kind),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        // 骨架与真实列表对齐：同样的两侧留白，头一格让出分段标题那一行
                        TransfersPhase.LOADING -> FileListSkeleton(
                            modifier = Modifier.padding(start = sidePadding, end = sidePadding, top = 8.dp + SectionHeaderHeight),
                        )
                        TransfersPhase.EMPTY -> PullableCentered {
                            PikoEmptyState(
                                title = "暂无任务",
                                description = "已提交的传输和文件处理将显示于此",
                                icon = Icons.Outlined.SyncAlt,
                                actionText = "添加链接",
                                onActionClick = { onCreateImport("") },
                            )
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(detailsKey, detailsItem) { if (detailsKey != null && detailsItem == null) detailState.close() }
    var confirmingClearCompleted by remember { mutableStateOf(false) }
    val headerActions = taskPageActions(
        state = state, selectedCount = bulkSelected.size,
        onSelectAll = { state.selectAll(visibleOrder) },
        onDelete = { confirmingDeleteKeys = state.selectedKeys.toSet() },
        onPause = pauseSelected, onResume = resumeSelected,
        pauseCount = bulkSelected.count(state::isPausable), resumeCount = bulkSelected.count(state::isResumable),
        onRequestClearCompleted = { confirmingClearCompleted = true },
    )
    if (confirmingClearCompleted) {
        PikoDialog(
            onDismissRequest = { confirmingClearCompleted = false },
            title = { Text("清除已完成任务？") },
            text = { Text("将清除所有类别的已完成任务记录，已下载到本机或保存到网盘的文件不会被删除。") },
            confirmButton = {
                PikoDialogConfirm(
                    label = "清除",
                    destructive = true,
                    onClick = {
                        confirmingClearCompleted = false
                        state.clearCompleted()
                    },
                )
            },
            dismissButton = { TextButton(onClick = { confirmingClearCompleted = false }) { Text("取消") } },
        )
    }
    val inlineIslandFilter = with(density) { taskContentWidth.toDp() } >= 820.dp
    PikoScaffold(
        modifier = modifier.fillMaxSize().onPreviewKeyEvent(detailState::handleKey),
        topBar = { TaskPageHeader(state, bulkSelected.size, headerActions) },
        island = IslandPage(
            header = { TaskIslandHeader(state, bulkSelected.size, headerActions, inlineFilter = inlineIslandFilter) },
            belowHeader = if (!inlineIslandFilter) ({ TaskIslandSubHeader(state) }) else null,
        ),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!LocalFramed.current) TaskRateBar(state)
            Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { taskContentWidth = it.width }
                .itemPanelAnchorArea(detailState.anchors)) {
                Box(Modifier.fillMaxSize().semantics { contentDescription = "任务列表" }) { transfersListBody(PaddingValues()) }
                DetailHost(detailState) { _, contentModifier -> TaskDetails(contentModifier) }
            }
        }
    }

    confirmingDeleteKeys?.let { keys ->
        val confirmingItems = state.itemsForKeys(keys)
        DeleteSelectedDialog(
            items = confirmingItems,
            onConfirm = {
                confirmingDeleteKeys = null
                state.removeKeys(keys)
            },
            onDismiss = { confirmingDeleteKeys = null },
        )
    }


}

/**
 * 居中放一段说明，整块可以竖着拉动。下拉刷新只认嵌套滚动传上来的量，不能滚的内容拉不动；
 * 在 verticalScroll 里高度不设上限，按外面量到的高度撑满才居中得了。
 */
@Composable
private fun PullableCentered(content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .height(maxHeight),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

@Composable
private fun TransfersList(
    state: TransfersState,
    kind: TransferKind,
    sections: TransferSections,
    completedExpanded: Boolean,
    onToggleCompleted: () -> Unit,
    sidePadding: Dp,
    deletedExpanded: Boolean,
    onToggleDeleted: () -> Unit,
    expandedBatches: List<String>,
    renderItem: @Composable (TransferItem, Modifier, Int, Int) -> Unit,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        if (sections.isEmpty && state.cleanupCount(kind) == 0) {
            // 筛到一项不剩时说清是这一类没有，而不是整页空白像没加载出来
            PullableCentered {
                Text(
                    text = "没有${kind.label}任务",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Box
        }
        // 能框选的只有条目，分组标题的 key 带 header: 前缀，不算
        val expanded = expandedBatches.toSet()
        val selectable = remember(sections, completedExpanded, deletedExpanded, expanded) {
            (sections.needsAttention + sections.inProgress +
                (if (completedExpanded) sections.completed else emptyList()) +
                if (deletedExpanded) sections.outputDeleted else emptyList())
                .withBatchChildren(expanded)
                .mapTo(HashSet()) { it.key }
        }
        PikoItemGrid(
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = sidePadding, vertical = 8.dp),
            // 照网盘页的框选：在空白或条目上按住拖动拉框。传输没有拖放，按在条目上拖也是框选
            gridModifier = Modifier.marqueeSelection(
                gridState = gridState,
                selectedIds = state.selectedKeys,
                boxedKey = { key -> (key as? String)?.takeIf { it in selectable } },
                onSelect = state::selectBoxed,
                onBackgroundClick = state::clearSelection,
                movable = { false },
            ),
        ) {
            if (state.cleanupCount(kind) > 0) fullLineItem(key = "cleanup") {
                dev.piko.ui.workbench.CleanupTaskCard(requireNotNull(state.cleanup), Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp))
            }
            transferSection("需要处理", sections.needsAttention, expanded, renderItem,
                attention = true, onClearCloud = state::clearFailedCloud)
            transferSection("进行中", sections.inProgress, expanded, renderItem)
            transferSection("已完成", sections.completed, expanded, renderItem,
                sectionExpanded = completedExpanded, onToggle = onToggleCompleted)
            deletedOutputSection(
                items = sections.outputDeleted,
                expanded = deletedExpanded,
                onToggle = onToggleDeleted,
                renderItem = renderItem,
            )
        }
    }
}

/**
 * 删除所选之前确认一次。批量删除一次动的不止一项，本地下载还会连文件从本机删掉，
 * 这一步又没有撤销；逐项删除仍在各自的菜单里，不经这一问。
 */
@Composable
private fun DeleteSelectedDialog(items: List<TransferItem>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val deletesFiles = items.any {
        (it is TransferItem.Local && it.task.status == DownloadStatus.COMPLETED) || (it is TransferItem.LocalBatch && it.completedCount > 0)
    }
    val cancelsUploads = items.any { (it is TransferItem.Upload && it.task.status != UploadStatus.COMPLETED) ||
        (it is TransferItem.UploadBatch && it.tasks.any { task -> task.status != UploadStatus.COMPLETED }) }
    val notes = listOfNotNull(
        "已下载的文件会从本机删除。".takeIf { deletesFiles },
        "未传完的上传会取消，网盘里上传到一半的文件随之删除。".takeIf { cancelsUploads },
        "云端任务只删记录，已保存到网盘的文件不受影响。".takeIf { items.any { TransferKind.CLOUD.matches(it) } },
        "待确认的文件操作仍可能已在服务端执行。移除记录不会取消操作，也不证明可以重新提交，请先检查实际文件。".takeIf {
            items.any { it is TransferItem.Write && it.work.result?.unconfirmed?.isNotEmpty() == true }
        },
        "历史记录的处理结果尚未确认。移除这里的结果不会取消原操作，也不会恢复续播位置。".takeIf {
            items.any { it is TransferItem.History && it.work.result?.completion in setOf(dev.piko.shared.state.HistoryEditCompletion.PARTIAL, dev.piko.shared.state.HistoryEditCompletion.UNCONFIRMED) }
        },
        "归档清单编辑结果尚未确认。移除记录不会取消已经提交的编辑，请先检查实际清单。".takeIf {
            items.any { it is TransferItem.VaultEdit && it.work.result?.unconfirmed?.isNotEmpty() == true }
        },
        "部分秒传请求可能已执行。移除结果不会取消请求，也不证明可以再次保存。".takeIf {
            items.any { it is TransferItem.InstantAttempt && it.work.receipt?.requiresInspection == true }
        },
        "分享转存或名称修改的结果尚未确认。移除记录不会取消原请求，也不证明可以重新转存。".takeIf {
            items.any { it is TransferItem.Share && (it.work.result?.unconfirmedSources?.isNotEmpty() == true || it.work.pendingRenames.isNotEmpty()) }
        },
        "离线请求可能已经执行。移除记录不会取消远端任务，也不证明可以再次提交。".takeIf {
            items.any { it is TransferItem.OfflineSubmit && it.work.status == dev.piko.shared.state.OfflineSubmitStatus.UNCONFIRMED }
        },
        "上传准备可能已创建目录或将部分文件入队。移除准备记录不会删除目录或取消上传，也不证明可以重新创建。".takeIf {
            items.any { it is TransferItem.UploadPreparing && it.work.hasSideEffects }
        },
    )
    PikoDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除所选的 ${items.size} 项？") },
        text = if (notes.isEmpty()) null else ({ Text(notes.joinToString("\n")) }),
        confirmButton = { PikoDialogConfirm("删除", onClick = onConfirm, destructive = true) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private enum class TransfersPhase { LOADING, EMPTY, CONTENT }

/** 分段标题一行的高度：上边距 8 加最小高度 40。骨架要让出同样的位置，内容换上来时才不跳。 */
private val SectionHeaderHeight = 48.dp

/**
 * 页头、列表与底栏两侧的留白，三处取同一个值，页头的按钮与列表的列对齐。列表是多栏网格（PikoItemGrid），
 * 与网盘页的列表一样铺满卡片，只留一点边距；不再收在居中的阅读宽度里。
 */
private val SidePadding = 8.dp

/** 展开的文件夹下载后面接上其中的各文件，其余照旧。 */
private fun List<TransferItem>.withBatchChildren(expanded: Collection<String>): List<TransferItem> = flatMap { item ->
    if (item.key in expanded) listOf(item) + item.children else listOf(item)
}

/**
 * 一段的条目。展开的文件夹下载占满一行作组头，其中的文件接在下面，末尾再用一个占满一行的空项断开：
 * 网格是多栏的，不断开的话段里下一项会挤进最后一个文件旁边的空格，看着像是组里的。
 */
private fun LazyGridScope.transferItems(
    items: List<TransferItem>,
    expanded: Set<String>,
    renderItem: @Composable (TransferItem, Modifier, Int, Int) -> Unit,
) {
    items.forEachIndexed { index, item ->
        if (item.key in expanded && item.children.isNotEmpty()) {
            fullLineItem(key = item.key) { renderItem(item, Modifier.animateItem(), index, items.size) }
            itemsIndexed(item.children, key = { _, child -> child.key }) { childIndex, child ->
                renderItem(child, Modifier.animateItem().padding(start = 16.dp), childIndex, item.children.size)
            }
            fullLineItem(key = "${item.key}:end") { Spacer(Modifier.height(4.dp)) }
        } else {
            item(key = item.key) { renderItem(item, Modifier.animateItem(), index, items.size) }
        }
    }
}

private fun LazyGridScope.transferSection(
    title: String,
    items: List<TransferItem>,
    expanded: Set<String>,
    renderItem: @Composable (TransferItem, Modifier, Int, Int) -> Unit,
    attention: Boolean = false,
    sectionExpanded: Boolean = true,
    onToggle: (() -> Unit)? = null,
    onClearCompleted: (() -> Unit)? = null,
    canClearCompleted: Boolean = false,
    /** 清除这一段的云端任务记录。只在段内有云端任务时给出，本地下载不受影响。 */
    onClearCloud: (() -> Unit)? = null,
) {
    if (items.isEmpty()) return
    val hasCloud = items.any { it is TransferItem.Cloud || it is TransferItem.Pack }
    fullLineItem(key = "header:$title", contentType = "header") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .animateItem()
                .padding(start = 16.dp, end = 8.dp)
                .padding(top = 8.dp)
                .heightIn(min = SectionHeaderHeight - 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f).then(
                    if (onToggle != null) Modifier.clickable(
                        onClickLabel = if (sectionExpanded) "收起已完成" else "展开已完成",
                        onClick = onToggle,
                    ) else Modifier,
                ).heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onToggle != null) {
                    Icon(if (sectionExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                    Spacer(Modifier.width(8.dp))
                }
                Surface(
                    color = if (attention) MaterialTheme.colorScheme.errorContainer else androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = if (attention) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = "$title（${items.size}）",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = if (attention) 10.dp else 0.dp, vertical = 6.dp),
                    )
                }
            }
            if (onClearCompleted != null) {
                TextButton(onClick = onClearCompleted, enabled = canClearCompleted,
                    modifier = Modifier.semantics { contentDescription = "清空所有类别的已完成记录，保留本地下载" },
                ) { Text("清空已完成") }
            }
            if (onClearCloud != null && hasCloud) {
                TextButton(onClick = onClearCloud) { Text("清除云端记录") }
            }
        }
    }
    if (sectionExpanded) transferItems(items, expanded, renderItem)
}

/**
 * 「文件已删除」组，排在最后，标题弱化且可收起。
 *
 * 展开与收起靠条目进出列表，由各项的 animateItem 做淡入淡出与位移。没有把条目包进
 * AnimatedVisibility：那样收起后条目仍留在列表里，只是高度为零。
 */
private fun LazyGridScope.deletedOutputSection(
    items: List<TransferItem>,
    expanded: Boolean,
    onToggle: () -> Unit,
    renderItem: @Composable (TransferItem, Modifier, Int, Int) -> Unit,
) {
    if (items.isEmpty()) return
    fullLineItem(key = "header:deleted", contentType = "header") {
        val chevronRotation by animateFloatAsState(
            targetValue = if (expanded) 180f else 0f,
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            label = "chevron",
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .animateItem()
                .padding(top = 8.dp)
                .clickable(onClickLabel = if (expanded) "收起" else "展开", onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "文件已删除",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${items.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(chevronRotation),
            )
        }
    }
    if (expanded) {
        items(items, key = { it.key }) { item ->
            renderItem(item, Modifier.animateItem(), items.indexOf(item), items.size)
        }
    }
}

private val TaskDetailSpec = DetailPanelSpec("任务详情", "关闭任务详情", 320.dp, 560.dp)
private val TaskDetailKey = dev.piko.ui.components.DetailPanelKey<String>(TaskDetailSpec)
