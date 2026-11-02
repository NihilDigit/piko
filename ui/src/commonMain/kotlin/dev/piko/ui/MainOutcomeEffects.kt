package dev.piko.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.i18n.enCount
import dev.piko.shared.i18n.tr
import dev.piko.shared.presentation.FileCommandResult
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.FileWriteCompletion
import dev.piko.shared.state.InstantSaveOutcome
import dev.piko.shared.state.OfflineSubmitStatus
import dev.piko.shared.state.ShareSaveCompletion
import dev.piko.shared.state.VaultEntryEditCompletion
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.workbench.TaskSlot
import dev.piko.ui.workbench.WorkspaceViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 主壳层后台会话结果与剪贴板监听副作用：
 * 独立于主导航树组合作用域，订阅各账号级执行会话的完成通知并驱动根 Snackbar。
 */
@Composable
internal fun MainOutcomeEffects(
    account: String,
    services: PikoServices,
    workspaceModel: WorkspaceViewModel,
    taskSlot: TaskSlot,
    rootSnackbar: SnackbarHostState,
    onTransfersTab: Boolean,
    isViewingActiveImport: () -> Boolean,
    isViewingDuplicateReport: (Long) -> Boolean,
    onOpenTransfers: () -> Unit,
    onOpenAddLink: (String) -> Unit,
    onRevealInstantSaved: (target: PikoPathBreadcrumb, createdIds: Set<String>) -> Unit,
) {
    val latestOnTransfersTab by rememberUpdatedState(onTransfersTab)
    val latestViewingImport by rememberUpdatedState(isViewingActiveImport)
    val latestViewingDuplicate by rememberUpdatedState(isViewingDuplicateReport)
    val latestOpenTransfers by rememberUpdatedState(onOpenTransfers)
    val latestOpenAddLink by rememberUpdatedState(onOpenAddLink)
    val latestRevealInstantSaved by rememberUpdatedState(onRevealInstantSaved)

    val clipboardPlatform = LocalPikoPlatform.current
    val detectClipboard by services.preferences.autoDetectClipboardLinksFlow.collectAsStateWithLifecycle(initialValue = false)
    val clipboardWindowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(detectClipboard, clipboardWindowFocused, clipboardPlatform) {
        if (detectClipboard && clipboardWindowFocused) {
            val links = ClipboardLinkDetection.session.detect(clipboardPlatform.readClipboardText())
            if (links != null) {
                val result = rootSnackbar.showSnackbar(
                    tr("检测到剪贴板中的链接", "Link detected in clipboard", "クリップボードにリンクがあります", "클립보드에서 링크 감지됨", "偵測到剪貼簿中的連結"),
                    actionLabel = tr("解析", "Resolve", "解析", "분석", "解析"),
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed) latestOpenAddLink(links)
            }
        }
    }

    LaunchedEffect(services.uploadManager) {
        services.uploadManager.enqueued.collect { event ->
            if (event.account == services.driveRepository.currentAccount && !latestOnTransfersTab) {
                launch {
                    val result = rootSnackbar.showSnackbar(
                        tr("已加入上传队列（${event.count} 项）", "Added to upload queue (${enCount(event.count, "item", "items")})", "アップロードキューに追加しました（${event.count} 項目）", "업로드 대기열에 추가했습니다 (${event.count}개 항목)", "已加入上傳佇列（${event.count} 項）"),
                        actionLabel = tr("查看", "View", "表示", "보기", "檢視"),
                        withDismissAction = true,
                    )
                    if (result == SnackbarResult.ActionPerformed && event.account == services.driveRepository.currentAccount) {
                        latestOpenTransfers()
                    }
                }
            }
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    workspaceModel.duplicateSessions.forEach { (id, session) ->
        key(id) {
            val finder = session.state
            LaunchedEffect(id, finder) {
                if (finder == null || !finder.isScanning) return@LaunchedEffect
                snapshotFlow { finder.isScanning }.first { !it }
                if (workspaceModel.duplicateSessions[id]?.state !== finder) return@LaunchedEffect
                if (latestViewingDuplicate(id)) return@LaunchedEffect
                val groups = finder.report.identical.size + finder.report.versions.size
                val message = when {
                    finder.phase == DuplicateFinderState.Phase.FAILED -> tr("查找重复失败", "Duplicate search failed", "重複検索に失敗しました", "중복 검색 실패", "尋找重複檔案失敗")
                    finder.scanStop != null || finder.failedFolders > 0 -> tr("部分扫描结果，发现 $groups 组重复文件", "Partial results: $groups duplicate groups found", "検索の途中結果：$groups 組の重複ファイル", "부분 검색 결과: 중복 파일 ${groups}개 그룹", "部分掃描結果，發現 $groups 組重複檔案")
                    groups > 0 -> tr("发现 $groups 组重复文件", "Found $groups duplicate groups", "$groups 組の重複ファイルが見つかりました", "중복 파일 ${groups}개 그룹 찾음", "發現 $groups 組重複檔案")
                    else -> tr("未发现重复文件", "No duplicate files found", "重複ファイルはありません", "중복 파일이 없습니다", "未發現重複檔案")
                }
                val result = rootSnackbar.showSnackbar(
                    message,
                    actionLabel = tr("查看报告", "View report", "レポートを表示", "보고서 보기", "檢視報告"),
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed && workspaceModel.duplicateSessions[id]?.state === finder) {
                    workspaceModel.activateContext(id, rememberOrigin = true)
                }
            }
        }
    }

    LaunchedEffect(services.instantSession, lifecycle, account) {
        val session = services.instantSession
        session.pendingCompletions
            .map { it.firstOrNull() }
            .distinctUntilChanged()
            .collect { completion ->
                if (completion == null) return@collect
                lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
                if (session.pendingCompletions.value.none { it.id == completion.id }) return@collect
                val outcome = completion.outcome
                val message = when (outcome) {
                    is InstantSaveOutcome.InstantSaved -> tr("已保存 ${outcome.createdIds.size} 个文件", "Saved ${enCount(outcome.createdIds.size, "file", "files")}", "${outcome.createdIds.size} ファイルを保存しました", "파일 ${outcome.createdIds.size}개 저장 완료", "已儲存 ${outcome.createdIds.size} 個檔案")
                    is InstantSaveOutcome.OfflineTaskCreated -> tr("已提交云端保存", "Cloud save submitted", "クラウドへの保存を送信しました", "클라우드 저장 제출 완료", "已提交雲端儲存")
                }
                val result = rootSnackbar.showSnackbar(message, actionLabel = tr("查看", "View", "表示", "보기", "檢視"), withDismissAction = true)
                if (session.pendingCompletions.value.none { it.id == completion.id }) return@collect
                session.acknowledgeCompletion(completion.id)
                if (result == SnackbarResult.ActionPerformed) {
                    when (outcome) {
                        is InstantSaveOutcome.InstantSaved -> latestRevealInstantSaved(outcome.target, outcome.createdIds.toSet())
                        is InstantSaveOutcome.OfflineTaskCreated -> latestOpenTransfers()
                    }
                }
            }
    }

    LaunchedEffect(services.historyEdits, lifecycle, account) {
        services.historyEdits.outcomes.collect { outcome ->
            if (outcome.account != services.driveRepository.currentAccount) return@collect
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
            if (outcome.account != services.driveRepository.currentAccount || services.historyEdits.works.value.none { it.id == outcome.id }) return@collect
            val answer = rootSnackbar.showSnackbar("${outcome.title}：${outcome.result.message}", actionLabel = tr("查看任务", "View tasks", "タスクを表示", "작업 보기", "檢視任務"), withDismissAction = true)
            if (answer == SnackbarResult.ActionPerformed && outcome.account == services.driveRepository.currentAccount) latestOpenTransfers()
        }
    }

    LaunchedEffect(services.instantSaveRecords, lifecycle, account) {
        services.instantSaveRecords.outcomes.collect { outcome ->
            if (outcome.account != services.driveRepository.currentAccount) return@collect
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
            if (outcome.account != services.driveRepository.currentAccount || services.instantSaveRecords.attempts.value.none { it.id == outcome.id } || latestViewingImport()) return@collect
            val answer = rootSnackbar.showSnackbar(outcome.message, actionLabel = tr("查看任务", "View tasks", "タスクを表示", "작업 보기", "檢視任務"), withDismissAction = true)
            if (answer == SnackbarResult.ActionPerformed && outcome.account == services.driveRepository.currentAccount) latestOpenTransfers()
        }
    }

    LaunchedEffect(services.uploadManager, lifecycle, account) {
        services.uploadManager.preparationOutcomes.collect { outcome ->
            if (outcome.account != services.driveRepository.currentAccount) return@collect
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
            if (outcome.account != services.driveRepository.currentAccount || services.uploadManager.preparations.value.none { it.id == outcome.id }) return@collect
            val answer = rootSnackbar.showSnackbar(outcome.result?.message.orEmpty(), actionLabel = tr("查看任务", "View tasks", "タスクを表示", "작업 보기", "檢視任務"), withDismissAction = true)
            if (answer == SnackbarResult.ActionPerformed && outcome.account == services.driveRepository.currentAccount) latestOpenTransfers()
        }
    }

    LaunchedEffect(services.instantMagnetRepository, lifecycle, account) {
        val submissions = services.instantMagnetRepository.offlineSubmissions
        submissions.outcomes.collect { outcome ->
            if (outcome.account != services.driveRepository.currentAccount || outcome.status == OfflineSubmitStatus.COMPLETE) return@collect
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
            if (outcome.account != services.driveRepository.currentAccount || submissions.works.value.none { it.id == outcome.id } || latestViewingImport()) return@collect
            val answer = rootSnackbar.showSnackbar(outcome.message, actionLabel = tr("查看任务", "View tasks", "タスクを表示", "작업 보기", "檢視任務"), withDismissAction = true)
            if (answer == SnackbarResult.ActionPerformed && outcome.account == services.driveRepository.currentAccount) latestOpenTransfers()
        }
    }

    LaunchedEffect(services.shareSaves, lifecycle, account) {
        services.shareSaves.outcomes.collect { outcome ->
            if (outcome.account != services.driveRepository.currentAccount) return@collect
            if (outcome.result.completion == ShareSaveCompletion.SUCCEEDED && services.shareSaves.works.value.firstOrNull { it.id == outcome.id }?.undoChangeIds?.isNotEmpty() == true) return@collect
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
            if (outcome.account != services.driveRepository.currentAccount || services.shareSaves.works.value.none { it.id == outcome.id } || latestViewingImport()) return@collect
            val answer = rootSnackbar.showSnackbar(outcome.result.message, actionLabel = tr("查看任务", "View tasks", "タスクを表示", "작업 보기", "檢視任務"), withDismissAction = true)
            if (answer == SnackbarResult.ActionPerformed && outcome.account == services.driveRepository.currentAccount) latestOpenTransfers()
        }
    }

    LaunchedEffect(services.vaultEntryEdits, lifecycle, account) {
        services.vaultEntryEdits.outcomes.collect { outcome ->
            if (outcome.account != services.driveRepository.currentAccount) return@collect
            if (outcome.result.completion == VaultEntryEditCompletion.SUCCEEDED) return@collect
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
            val work = services.vaultEntryEdits.works.value.firstOrNull { it.id == outcome.id } ?: return@collect
            if (outcome.account != services.driveRepository.currentAccount) return@collect
            val answer = rootSnackbar.showSnackbar(
                "${outcome.title}：${outcome.result.message}",
                actionLabel = tr("查看任务", "View tasks", "タスクを表示", "작업 보기", "檢視任務").takeIf { work.showsInTaskPage },
                withDismissAction = true,
            )
            if (answer == SnackbarResult.ActionPerformed && outcome.account == services.driveRepository.currentAccount) latestOpenTransfers()
        }
    }

    LaunchedEffect(services.fileWrites, lifecycle, account) {
        services.fileWrites.outcomes.collect { outcome ->
            if (outcome.account != services.driveRepository.currentAccount) return@collect
            val initialWork = services.fileWrites.works.value.firstOrNull { it.id == outcome.workId } ?: return@collect
            if (outcome.result.completion == FileWriteCompletion.SUCCEEDED &&
                (!initialWork.showsInTaskPage || outcome.result.undoChangeIds.isNotEmpty())
            ) return@collect
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
            val work = services.fileWrites.works.value.firstOrNull { it.id == outcome.workId } ?: return@collect
            if (outcome.account != services.driveRepository.currentAccount) return@collect
            val answer = rootSnackbar.showSnackbar(
                "${outcome.title}：${outcome.result.message}",
                actionLabel = tr("查看任务", "View tasks", "タスクを表示", "작업 보기", "檢視任務").takeIf { work.showsInTaskPage },
                withDismissAction = true,
            )
            if (answer == SnackbarResult.ActionPerformed && outcome.account == services.driveRepository.currentAccount) latestOpenTransfers()
        }
    }

    LaunchedEffect(services.driveRepository, lifecycle, account) {
        val changes = services.driveRepository.changes
        showChangeNotices(
            changes.events.filter { it.change !is DriveChangeJournal.Change.Trash },
            rootSnackbar,
            awaitVisible = { lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) } },
            undo = { change ->
                val result = workspaceModel.fileCommands.undo(change.id)
                if (result is FileCommandResult.Rejected) {
                    taskSlot.showBlocked(tr("撤销", "Undo", "元に戻す", "실행 취소", "復原"), result.reason)
                }
            },
            isCurrent = { event ->
                services.driveRepository.currentAccount == account &&
                    (event.generation == null || event.generation == changes.state.value.generation)
            },
        )
    }
}
