package dev.piko.ui.screens.drive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.enCount
import dev.piko.shared.i18n.tr
import dev.piko.shared.notice.PikoErrorMapper
import dev.piko.shared.state.CollisionStrategy
import dev.piko.shared.state.FolderStructureMode
import dev.piko.shared.state.FolderStructureOps
import dev.piko.shared.state.FolderStructureOptions
import dev.piko.shared.state.FolderStructureOutcome
import dev.piko.shared.state.FolderStructurePhase
import dev.piko.shared.state.FolderStructurePlan
import dev.piko.shared.state.FolderStructureProgress
import dev.piko.shared.state.asFolderStructureBackend
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.FileListLeadingVisual
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import dev.piko.ui.components.connectedToggleShapes
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.common_cancel
import dev.piko.ui.resources.common_retry
import dev.piko.ui.theme.tabular
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FolderStructureDialog(
    workingFolderId: String,
    workingFolderName: String,
    selectedFolders: List<FileStat>,
    initialMode: FolderStructureMode,
    availabilityCheck: () -> String? = { null },
    onDismiss: () -> Unit,
    onFinished: (message: String) -> Unit,
) {
    val services = LocalPikoServices.current
    val driveRepo = services.driveRepository
    val isSpoilerBlurEnabled by services.preferences.spoilerBlurFlow.collectAsStateWithLifecycle(
        initialValue = services.preferences.currentSnapshot.spoilerBlurEnabled,
    )
    val ops = remember(driveRepo) { FolderStructureOps(driveRepo.asFolderStructureBackend()) }
    val scope = rememberCoroutineScope()

    val canMerge = selectedFolders.size >= 2
    val modes = remember(canMerge) {
        if (canMerge) {
            listOf(FolderStructureMode.UNWRAP, FolderStructureMode.FLATTEN, FolderStructureMode.MERGE)
        } else {
            listOf(FolderStructureMode.UNWRAP, FolderStructureMode.FLATTEN)
        }
    }

    var mode by remember(selectedFolders, initialMode) {
        mutableStateOf(if (initialMode == FolderStructureMode.MERGE && !canMerge) FolderStructureMode.UNWRAP else initialMode)
    }
    var unwrapSingleChildChain by remember { mutableStateOf(true) }
    var mergeTargetId by remember(selectedFolders) { mutableStateOf<String?>(null) }
    var collisionStrategy by remember { mutableStateOf(CollisionStrategy.SKIP) }
    var planRetryTick by remember { mutableIntStateOf(0) }

    var isPlanning by remember { mutableStateOf(true) }
    var plan by remember { mutableStateOf<FolderStructurePlan?>(null) }
    var planError by remember { mutableStateOf<String?>(null) }

    var executeJob by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf<FolderStructureProgress?>(null) }
    val isExecuting = executeJob != null

    val options = FolderStructureOptions(
        mode = mode,
        unwrapSingleChildChain = unwrapSingleChildChain,
        mergeTargetId = mergeTargetId,
        collisionStrategy = collisionStrategy,
    )

    LaunchedEffect(workingFolderId, selectedFolders, options, planRetryTick) {
        isPlanning = true
        planError = null
        val result = ops.plan(
            workingFolderId = workingFolderId,
            workingFolderName = workingFolderName,
            selectedFolders = selectedFolders,
            options = options,
        )
        result.onSuccess { computed ->
            plan = computed
            if (computed.options.mode == FolderStructureMode.MERGE && mergeTargetId == null) {
                mergeTargetId = computed.options.mergeTargetId
            }
        }.onFailure { err ->
            if (err is CancellationException) throw err
            plan = null
            planError = PikoErrorMapper.failure(tr("检查目录结构", "Inspect folder structure", "フォルダー構成の確認", "폴더 구조 확인", "檢查資料夾結構"), err)
        }
        isPlanning = false
    }

    val title = if (canMerge) {
        tr("整理文件夹结构", "Organize folder structure", "フォルダー構成を整理", "폴더 구조 정리", "整理資料夾結構")
    } else {
        tr("拆开 / 铺开", "Unwrap / Flatten", "展開 / フラット化", "풀기 / 평탄화", "拆開 / 鋪平")
    }

    val currentPlan = plan
    val canConfirm = !isPlanning && !isExecuting && planError == null && currentPlan?.canExecute == true

    fun startExecution() {
        val targetPlan = plan ?: return
        if (!targetPlan.canExecute || executeJob != null) return
        val blocked = availabilityCheck()
        if (blocked != null) {
            planError = blocked
            return
        }
        executeJob = scope.launch {
            try {
                val outcome = ops.execute(targetPlan) { p -> progress = p }
                executeJob = null
                progress = null
                onFinished(formatOutcomeMessage(outcome))
            } catch (_: CancellationException) {
                executeJob = null
                progress = null
            }
        }
    }

    PikoDialog(
        onDismissRequest = {
            if (!isExecuting) onDismiss()
        },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Mode selector (M3E connected toggle button group)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                ) {
                    modes.forEachIndexed { index, candidate ->
                        ToggleButton(
                            checked = mode == candidate,
                            onCheckedChange = { if (!isExecuting) mode = candidate },
                            enabled = !isExecuting,
                            shapes = connectedToggleShapes(index, modes.size),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(modeShortLabel(candidate), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                // Concise explanation (non-redundant with button label)
                Text(
                    text = modeDescription(mode, currentPlan?.targetFolderName ?: workingFolderName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Mode-specific configuration
                if (mode == FolderStructureMode.UNWRAP) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = unwrapSingleChildChain,
                                    enabled = !isExecuting,
                                    role = Role.Switch,
                                    onValueChange = { unwrapSingleChildChain = it },
                                )
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = tr(
                                    "自动穿透单层套娃文件夹",
                                    "Auto-unwrap single-child wrapper folders",
                                    "単一子フォルダーの入れ子を自動で展開",
                                    "단일 하위 폴더 중첩 자동 풀기",
                                    "自動穿透單層套娃資料夾",
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = unwrapSingleChildChain,
                                onCheckedChange = null,
                                enabled = !isExecuting,
                                modifier = Modifier.clearAndSetSemantics {},
                            )
                        }
                    }
                } else if (mode == FolderStructureMode.MERGE) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = tr("合并到目标文件夹", "Target folder", "統合先フォルダー", "병합 대상 폴더", "合併至目標資料夾"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val activeTargetId = mergeTargetId ?: currentPlan?.targetFolderId ?: selectedFolders.first().id
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 156.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            selectedFolders.forEachIndexed { idx, folder ->
                                val selected = folder.id == activeTargetId
                                val count = currentPlan?.directChildCounts?.get(folder.id)
                                val shapes = ListItemDefaults.segmentedShapes(idx, selectedFolders.size)
                                Surface(
                                    shape = shapes.shape,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainer
                                    },
                                    contentColor = if (selected) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .selectable(
                                                selected = selected,
                                                enabled = !isExecuting,
                                                role = Role.RadioButton,
                                                onClick = { mergeTargetId = folder.id },
                                            )
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = selected,
                                            onClick = null,
                                            enabled = !isExecuting,
                                            modifier = Modifier.clearAndSetSemantics {},
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        FileListLeadingVisual(
                                            file = folder,
                                            size = 32.dp,
                                            isSpoilerBlurred = isSpoilerBlurEnabled,
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = folder.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (count != null) {
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = tr("$count 项", enCount(count, "item", "items"), "$count 項目", "${count}개", "$count 項"),
                                                style = MaterialTheme.typography.labelMedium.tabular,
                                                color = if (selected) {
                                                    MaterialTheme.colorScheme.onSecondaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Dry-run preview / execution progress surface
                if (isExecuting) {
                    ExecutionProgressCard(progress = progress)
                } else {
                    PreviewSummaryCard(
                        isPlanning = isPlanning,
                        plan = currentPlan,
                        planError = planError,
                        collisionStrategy = collisionStrategy,
                        onCollisionStrategyChange = { collisionStrategy = it },
                        onRetry = { planRetryTick++ },
                    )
                }
            }
        },
        confirmButton = {
            PikoDialogConfirm(
                label = confirmLabel(mode),
                enabled = canConfirm,
                onClick = ::startExecution,
            )
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (isExecuting) {
                        executeJob?.cancel()
                    } else {
                        onDismiss()
                    }
                },
            ) {
                Text(stringResource(Res.string.common_cancel))
            }
        },
    )
}

@Composable
private fun PreviewSummaryCard(
    isPlanning: Boolean,
    plan: FolderStructurePlan?,
    planError: String?,
    collisionStrategy: CollisionStrategy,
    onCollisionStrategyChange: (CollisionStrategy) -> Unit,
    onRetry: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = when {
            planError != null || plan?.blockedReason != null -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
        contentColor = when {
            planError != null || plan?.blockedReason != null -> MaterialTheme.colorScheme.onErrorContainer
            else -> MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                isPlanning -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        InlineLoadingIndicator(modifier = Modifier.size(18.dp))
                        Text(
                            text = tr("正在检查目录结构…", "Inspecting folder structure…", "フォルダー構成を確認中…", "폴더 구조 확인 중…", "正在檢查資料夾結構…"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                planError != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(planError, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = onRetry) { Text(stringResource(Res.string.common_retry)) }
                    }
                }
                plan == null -> Unit
                plan.blockedReason != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(plan.blockedReason.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    }
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            Icons.Outlined.DriveFileMove,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = tr(
                                "将移动 ${plan.moves.size} 项至「${plan.targetFolderName}」",
                                "Move ${enCount(plan.moves.size, "item", "items")} to “${plan.targetFolderName}”",
                                "${plan.moves.size} 項目を「${plan.targetFolderName}」へ移動",
                                "${plan.moves.size}개 항목을 “${plan.targetFolderName}”(으)로 이동",
                                "將移動 ${plan.moves.size} 項至「${plan.targetFolderName}」",
                            ),
                            style = MaterialTheme.typography.bodyMedium.tabular,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = tr(
                                "将移入回收站 ${plan.foldersToTrash.size} 个空文件夹",
                                "Move ${enCount(plan.foldersToTrash.size, "empty folder", "empty folders")} to trash",
                                "空の ${plan.foldersToTrash.size} フォルダーをゴミ箱へ移動",
                                "빈 폴더 ${plan.foldersToTrash.size}개를 휴지통으로 이동",
                                "將移入資源回收筒 ${plan.foldersToTrash.size} 個空資料夾",
                            ),
                            style = MaterialTheme.typography.bodySmall.tabular,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (plan.penetratedWrapperCount > 0) {
                        Text(
                            text = tr(
                                "已穿透 ${plan.penetratedWrapperCount} 层单子文件夹",
                                "Penetrated ${enCount(plan.penetratedWrapperCount, "wrapper layer", "wrapper layers")}",
                                "${plan.penetratedWrapperCount} 階層の単一フォルダーを展開",
                                "단일 하위 폴더 ${plan.penetratedWrapperCount}단계 통과",
                                "已穿透 ${plan.penetratedWrapperCount} 層單子資料夾",
                            ),
                            style = MaterialTheme.typography.bodySmall.tabular,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (plan.totalCollisionCount > 0) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Text(
                                        text = if (collisionStrategy == CollisionStrategy.SKIP) {
                                            tr(
                                                "发现 ${plan.totalCollisionCount} 个同名项，默认安全跳过并保留原文件夹",
                                                "${enCount(plan.totalCollisionCount, "name collision", "name collisions")} will be safely skipped",
                                                "${plan.totalCollisionCount} 件の同名項目は安全のためスキップされます",
                                                "이름 충돌 ${plan.totalCollisionCount}개는 안전하게 건너뜁니다",
                                                "發現 ${plan.totalCollisionCount} 個同名項目，預設安全略過並保留原資料夾",
                                            )
                                        } else {
                                            tr(
                                                "发现 ${plan.totalCollisionCount} 个同名项，将自动追加序号重命名",
                                                "${enCount(plan.totalCollisionCount, "name collision", "name collisions")} will be renamed with a numeric suffix",
                                                "${plan.totalCollisionCount} 件の同名項目は連番を付けて名前変更されます",
                                                "이름 충돌 ${plan.totalCollisionCount}개는 번호를 붙여 이름을 변경합니다",
                                                "發現 ${plan.totalCollisionCount} 個同名項目，將自動附加序號重新命名",
                                            )
                                        },
                                        style = MaterialTheme.typography.bodySmall.tabular,
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .toggleable(
                                            value = collisionStrategy == CollisionStrategy.RENAME_SUFFIX,
                                            role = Role.Switch,
                                            onValueChange = { rename ->
                                                onCollisionStrategyChange(if (rename) CollisionStrategy.RENAME_SUFFIX else CollisionStrategy.SKIP)
                                            },
                                        ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = tr("重命名同名项（追加序号）", "Rename collisions with suffix", "同名項目に連番を付けて移動", "이름 충돌 시 번호 붙여 이동", "重新命名同名項目（附加序號）"),
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Switch(
                                        checked = collisionStrategy == CollisionStrategy.RENAME_SUFFIX,
                                        onCheckedChange = null,
                                        modifier = Modifier.clearAndSetSemantics {},
                                    )
                                }
                            }
                        }
                    }
                    if (plan.moves.isNotEmpty()) {
                        val previewLimit = 4
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            plan.moves.take(previewLimit).forEach { move ->
                                val line = if (move.renamedTo != null) {
                                    "${move.item.name} → ${move.renamedTo}"
                                } else {
                                    move.item.name
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                        modifier = Modifier.size(4.dp),
                                    ) {}
                                    Text(
                                        text = line,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            if (plan.moves.size > previewLimit) {
                                val more = plan.moves.size - previewLimit
                                Text(
                                    text = tr("…以及其他 $more 项", "…and $more more", "…ほか $more 項目", "…외 ${more}개 항목", "…以及其他 $more 項"),
                                    style = MaterialTheme.typography.bodySmall.tabular,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExecutionProgressCard(progress: FolderStructureProgress?) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val label = when (progress?.phase) {
                FolderStructurePhase.RENAMING -> tr(
                    "正在重命名同名项（${progress.completed} / ${progress.total}）…",
                    "Renaming collisions (${progress.completed} / ${progress.total})…",
                    "同名項目の名前を変更中（${progress.completed} / ${progress.total}）…",
                    "이름 충돌 항목 변경 중 (${progress.completed} / ${progress.total})…",
                    "正在重新命名同名項目（${progress.completed} / ${progress.total}）…",
                )
                FolderStructurePhase.MOVING -> tr(
                    "正在移动条目（${progress.completed} / ${progress.total}）…",
                    "Moving items (${progress.completed} / ${progress.total})…",
                    "項目を移動中（${progress.completed} / ${progress.total}）…",
                    "항목 이동 중 (${progress.completed} / ${progress.total})…",
                    "正在移動項目（${progress.completed} / ${progress.total}）…",
                )
                FolderStructurePhase.TRASHING -> tr(
                    "正在清理空文件夹（${progress.completed} / ${progress.total}）…",
                    "Cleaning up empty folders (${progress.completed} / ${progress.total})…",
                    "空のフォルダーを整理中（${progress.completed} / ${progress.total}）…",
                    "빈 폴더 정리 중 (${progress.completed} / ${progress.total})…",
                    "正在清理空資料夾（${progress.completed} / ${progress.total}）…",
                )
                null -> tr("正在准备…", "Preparing…", "準備中…", "준비 중…", "正在準備…")
            }
            Text(label, style = MaterialTheme.typography.bodyMedium.tabular)
            val fraction = if (progress != null && progress.total > 0) {
                (progress.completed.toFloat() / progress.total).coerceIn(0f, 1f)
            } else 0f
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun modeShortLabel(mode: FolderStructureMode): String = when (mode) {
    FolderStructureMode.UNWRAP -> tr("拆开", "Unwrap", "展開", "풀기", "拆開")
    FolderStructureMode.FLATTEN -> tr("全部铺平", "Flatten", "フラット化", "평탄화", "全部鋪平")
    FolderStructureMode.MERGE -> tr("合并", "Merge", "統合", "병합", "合併")
}

private fun modeDescription(mode: FolderStructureMode, targetName: String): String = when (mode) {
    FolderStructureMode.UNWRAP -> tr(
        "移出所选文件夹内容至「$targetName」，清空后的原文件夹移入回收站。",
        "Moves contents up into “$targetName” and moves emptied wrapper folders to trash.",
        "内容を「$targetName」へ取り出し、空になったフォルダーをゴミ箱へ移動します。",
        "내용을 “$targetName”(으)로 꺼내고 비워진 폴더는 휴지통으로 이동합니다.",
        "移出所選資料夾內容至「$targetName」，清空後的原資料夾移入資源回收筒。",
    )
    FolderStructureMode.FLATTEN -> tr(
        "递归提取所有子目录内的文件至「$targetName」，清空后的文件夹移入回收站。",
        "Extracts all nested files recursively into “$targetName” and trashes emptied folders.",
        "すべてのサブフォルダーからファイルを「$targetName」へ取り出し、空フォルダーをゴミ箱へ移動します。",
        "하위 폴더의 모든 파일을 “$targetName”(으)로 꺼내고 비워진 폴더를 휴지통으로 이동합니다.",
        "遞迴提取所有子目錄內的檔案至「$targetName」，清空後的資料夾移入資源回收筒。",
    )
    FolderStructureMode.MERGE -> tr(
        "将其余所选文件夹的直接子项移入目标文件夹，清空后的源文件夹移入回收站。",
        "Moves direct children from the other folders into the target folder and trashes emptied sources.",
        "他の選択フォルダーの直下項目を統合先へ移動し、空になった元フォルダーをゴミ箱へ移動します。",
        "나머지 폴더의 직속 항목을 대상 폴더로 이동하고 비워진 원본 폴더를 휴지통으로 이동합니다.",
        "將其餘所選資料夾的直接子項移入目標資料夾，清空後的來源資料夾移入資源回收筒。",
    )
}

private fun confirmLabel(mode: FolderStructureMode): String = when (mode) {
    FolderStructureMode.UNWRAP -> tr("开始拆开", "Unwrap", "展開する", "풀기", "開始拆開")
    FolderStructureMode.FLATTEN -> tr("开始铺平", "Flatten", "フラット化", "평탄화", "開始鋪平")
    FolderStructureMode.MERGE -> tr("开始合并", "Merge", "統合する", "병합", "開始合併")
}

private fun formatOutcomeMessage(outcome: FolderStructureOutcome): String {
    val parts = buildList {
        if (outcome.movedCount > 0) {
            add(tr("已移动 ${outcome.movedCount} 项", "Moved ${enCount(outcome.movedCount, "item", "items")}", "${outcome.movedCount} 項目を移動しました", "${outcome.movedCount}개 항목 이동됨", "已移動 ${outcome.movedCount} 項"))
        }
        if (outcome.trashedFolderCount > 0) {
            add(tr("清理 ${outcome.trashedFolderCount} 个空文件夹", "trashed ${enCount(outcome.trashedFolderCount, "empty folder", "empty folders")}", "空の ${outcome.trashedFolderCount} フォルダーを整理", "빈 폴더 ${outcome.trashedFolderCount}개 정리", "清理 ${outcome.trashedFolderCount} 個空資料夾"))
        }
        if (outcome.skippedCount > 0) {
            add(tr("跳过 ${outcome.skippedCount} 个同名项", "skipped ${enCount(outcome.skippedCount, "collision", "collisions")}", "同名 ${outcome.skippedCount} 件をスキップ", "이름 충돌 ${outcome.skippedCount}개 건너뜀", "略過 ${outcome.skippedCount} 個同名項目"))
        }
        if (outcome.failedCount > 0) {
            add(tr("${outcome.failedCount} 项失败", "${outcome.failedCount} failed", "${outcome.failedCount} 件失敗", "${outcome.failedCount}개 실패", "${outcome.failedCount} 項失敗"))
        }
    }
    return if (parts.isNotEmpty()) {
        parts.joinToString(tr("，", ", ", "、", ", ", "，"))
    } else {
        outcome.errorMessage ?: tr("无需变更", "No changes needed", "変更はありません", "변경 사항 없음", "無需變更")
    }
}
