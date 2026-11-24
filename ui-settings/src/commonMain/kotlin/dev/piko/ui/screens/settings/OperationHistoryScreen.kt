package dev.piko.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.i18n.tr
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.LocalRootSnackbar
import dev.piko.ui.adaptive.readableWidth
import dev.piko.ui.components.IslandHeaderSpace
import dev.piko.ui.components.IslandPage
import dev.piko.ui.components.IslandTitle
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoScaffold
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.isEInk
import dev.piko.ui.theme.tabular
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * 操作记录与撤销中心：
 * 集中展示当前账号本次运行期内记入 [DriveChangeJournal] 的可撤销文件变更（移动、重命名、移入回收站、归档），
 * 窄屏入口在「我的」，宽屏自然排在左侧导航栏「库」区，使瞬态 Snackbar 无需永久停留也能随时放心撤销。
 */
@Composable
fun OperationHistoryScreen(
    onBackClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val services = LocalPikoServices.current
    val journal = services.driveRepository.changes
    val state by journal.state.collectAsStateWithLifecycle()
    val snackbarHostState = LocalRootSnackbar.current
    val title = tr("操作记录", "Operation history", "操作履歴", "작업 기록", "操作記錄")

    val availableIds = remember(state.available) { state.available.mapTo(HashSet()) { it.id } }
    val entries = remember(state.history) { state.history.asReversed() }
    val anyRunning = remember(state.undo) {
        state.undo.values.any { it.status == DriveChangeJournal.UndoStatus.RUNNING }
    }

    PikoScaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        island = IslandPage(header = {
            if (onBackClick != null) {
                TooltipIconButton(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    label = tr("返回", "Back", "戻る", "뒤로", "返回"),
                    onClick = onBackClick,
                )
            }
            IslandTitle(title)
            IslandHeaderSpace()
        }),
        topBar = {
            PikoTopBar(
                title = title,
                onBackClick = onBackClick,
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            PikoEmptyState(
                icon = Icons.Outlined.History,
                title = tr("暂无操作记录", "No recent operations", "操作履歴はありません", "최근 작업 기록이 없습니다", "尚無操作記錄"),
                description = tr(
                    "本次运行期内的移动、重命名、移入回收站与归档操作会记录在这里，方便随时撤销",
                    "Moves, renames, trash actions, and archive changes from this session appear here so you can undo them anytime",
                    "このセッションでの移動、名前変更、ゴミ箱への移動、アーカイブ操作がここに記録され、いつでも元に戻せます",
                    "이번 세션의 이동, 이름 변경, 휴지통 이동 및 보관 작업이 여기에 기록되어 언제든 실행 취소할 수 있습니다",
                    "本次執行期間的移動、重新命名、移至資源回收筒與封存操作會記錄於此，方便隨時復原",
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .readableWidth(),
            ) {
                items(entries, key = { it.change.id }) { entry ->
                    val change = entry.change
                    val undo = state.undo[change.id]
                    val canUndo = change.id in availableIds
                    OperationHistoryRow(
                        entry = entry,
                        undo = undo,
                        canUndo = canUndo,
                        anyRunning = anyRunning,
                        onUndo = { journal.undo(change.id) },
                        onStopUndo = { journal.requestStopUndo(change.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OperationHistoryRow(
    entry: DriveChangeJournal.JournalEntry,
    undo: DriveChangeJournal.UndoResult?,
    canUndo: Boolean,
    anyRunning: Boolean,
    onUndo: () -> Unit,
    onStopUndo: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val eInk = LocalAppearance.current.isEInk
    val change = entry.change
    val timeLabel = remember(entry.recordedAtEpochMillis) { formatOperationTime(entry.recordedAtEpochMillis) }
    val kindLabel = changeKindLabel(change)
    val sep = tr("，", ", ", "、", ", ", "，")
    val statusText = when (undo?.status) {
        DriveChangeJournal.UndoStatus.RUNNING -> undo.message
        DriveChangeJournal.UndoStatus.UNDONE -> tr("已撤销", "Undone", "元に戻しました", "실행 취소됨", "已復原")
        DriveChangeJournal.UndoStatus.FAILED -> undo.message
        null -> if (canUndo) {
            tr("可撤销", "Undo available", "元に戻せます", "실행 취소 가능", "可復原")
        } else {
            tr("已超出撤销保留范围", "No longer undoable", "元に戻せる範囲を超えました", "실행 취소 가능 범위 초과", "已超出復原保留範圍")
        }
    }
    val metaLine = "$kindLabel$sep$timeLabel$sep$statusText"
    val statusColor = when (undo?.status) {
        DriveChangeJournal.UndoStatus.FAILED -> colors.error
        DriveChangeJournal.UndoStatus.RUNNING -> colors.primary
        else -> colors.onSurfaceVariant
    }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (eInk) Color.White else colors.surfaceContainerLow,
        border = BorderStroke(
            width = 1.dp,
            color = if (eInk) colors.outline else colors.outlineVariant.copy(alpha = 0.45f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = changeKindIcon(change),
                contentDescription = null,
                tint = when (undo?.status) {
                    DriveChangeJournal.UndoStatus.FAILED -> colors.error
                    DriveChangeJournal.UndoStatus.UNDONE -> colors.onSurfaceVariant
                    else -> colors.primary
                },
                modifier = Modifier.size(22.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = change.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.bodySmall.tabular,
                    color = statusColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when (undo?.status) {
                DriveChangeJournal.UndoStatus.RUNNING -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        TooltipIconButton(
                            icon = Icons.Outlined.StopCircle,
                            label = tr("停止撤销", "Stop undo", "取り消しを停止", "실행 취소 중지", "停止復原"),
                            onClick = onStopUndo,
                        )
                    }
                }
                DriveChangeJournal.UndoStatus.UNDONE -> {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                DriveChangeJournal.UndoStatus.FAILED -> {
                    if (canUndo) {
                        TextButton(
                            onClick = onUndo,
                            enabled = !anyRunning,
                        ) {
                            Text(tr("重试", "Retry", "再試行", "재시도", "重試"))
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = colors.error,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                null -> {
                    if (canUndo) {
                        FilledTonalButton(
                            onClick = onUndo,
                            enabled = !anyRunning,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Undo,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = tr("撤销", "Undo", "元に戻す", "실행 취소", "復原"),
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun changeKindIcon(change: DriveChangeJournal.Change): ImageVector = when (change) {
    is DriveChangeJournal.Change.Move -> Icons.Outlined.DriveFileMove
    is DriveChangeJournal.Change.Trash -> Icons.Outlined.DeleteOutline
    is DriveChangeJournal.Change.Rename -> Icons.Outlined.DriveFileRenameOutline
    is DriveChangeJournal.Change.Vault -> Icons.Outlined.Archive
}

private fun changeKindLabel(change: DriveChangeJournal.Change): String = when (change) {
    is DriveChangeJournal.Change.Move -> tr("移动", "Move", "移動", "이동", "移動")
    is DriveChangeJournal.Change.Trash -> tr("移入回收站", "Trash", "ゴミ箱へ移動", "휴지통으로 이동", "移至資源回收筒")
    is DriveChangeJournal.Change.Rename -> tr("重命名", "Rename", "名前変更", "이름 변경", "重新命名")
    is DriveChangeJournal.Change.Vault -> tr("归档", "Archive", "アーカイブ", "보관", "封存")
}

private fun formatOperationTime(epochMillis: Long): String {
    val local = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    val hh = local.hour.toString().padStart(2, '0')
    val mm = local.minute.toString().padStart(2, '0')
    val ss = local.second.toString().padStart(2, '0')
    return "$hh:$mm:$ss"
}
