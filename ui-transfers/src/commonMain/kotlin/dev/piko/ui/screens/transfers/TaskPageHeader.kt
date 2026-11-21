package dev.piko.ui.screens.transfers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.enCount
import dev.piko.shared.i18n.tr
import dev.piko.shared.state.TransfersState
import dev.piko.ui.adaptive.isDesktopLayout
import dev.piko.ui.components.BarItem
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.PinnedPriority
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.SnailModeToggle
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.iconBarItem
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.LocalFramed
import dev.piko.ui.theme.isEInk

internal fun taskPageActions(
    state: TransfersState,
    selectedCount: Int,
    allSelected: Boolean,
    onToggleSelectAll: () -> Unit,
    onRequestClearCompleted: () -> Unit = state::clearCompleted,
): List<SheetAction> = buildList {
    if (selectedCount > 0) {
        add(
            SheetAction(
                icon = if (allSelected) Icons.Outlined.Deselect else Icons.Outlined.SelectAll,
                label = if (allSelected) {
                    tr("取消全选", "Deselect all", "すべて選択解除", "모두 선택 해제", "取消全選")
                } else {
                    tr("全选当前筛选结果", "Select all filtered items", "絞り込み結果をすべて選択", "현재 필터 결과 모두 선택", "全選目前篩選結果")
                },
                onClick = onToggleSelectAll,
            ),
        )
    } else {
        if (state.canPauseAll) add(SheetAction(Icons.Outlined.Pause, tr("全部暂停", "Pause all", "すべて一時停止", "모두 일시 중지", "全部暫停"), state::pauseAll))
        if (state.canResumeAll) add(SheetAction(Icons.Outlined.PlayArrow, tr("全部继续", "Resume all", "すべて再開", "모두 계속", "全部繼續"), state::resumeAll))
        if (state.canClearCompleted) add(SheetAction(Icons.Outlined.ClearAll, tr("清除已完成", "Clear completed", "完了済みを消去", "완료된 항목 지우기", "清除已完成"), onRequestClearCompleted))
        add(SheetAction(Icons.Outlined.Refresh, tr("刷新", "Refresh", "更新", "새로고침", "重新整理"), state::refresh))
    }
}

@Composable
internal fun TaskPageHeader(state: TransfersState, selectedCount: Int, actions: List<SheetAction>) {
    val shortcutModifier = LocalPikoPlatform.current.shortcutModifier
    Column(Modifier.fillMaxWidth()) {
        PikoTopBar(
            title = if (selectedCount > 0) tr("已选 $selectedCount 项", "${enCount(selectedCount, "item", "items")} selected", "$selectedCount 項目を選択中", "${selectedCount}개 선택됨", "已選 $selectedCount 項") else tr("任务", "Tasks", "タスク", "작업", "任務"),
            navigationIcon = if (selectedCount > 0) ({ TooltipIconButton(Icons.Outlined.Close, tr("取消选择", "Deselect", "選択を解除", "선택 해제", "取消選取"), state::clearSelection) }) else null,
            actions = buildList {
                addAll(actions.mapIndexed { index, action ->
                    iconBarItem(
                        icon = action.icon,
                        label = action.label,
                        onClick = action.onClick,
                        priority = if (selectedCount > 0) PinnedPriority else 50 - index,
                        shortcut = if (selectedCount > 0) shortcutModifier.label("A") else null,
                        destructive = action.destructive,
                        key = "${action.label}:$index",
                    )
                })
                if (selectedCount == 0) {
                    add(BarItem(key = "snail", priority = PinnedPriority) { SnailModeToggle() })
                }
            },
        )
        TaskKindFilter(
            state = state,
            compact = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 任务页多选底部居中浮动工具栏（对齐 `DriveSelectionFloatingToolbar`）：
 * 顶栏仅保留退出多选、已选计数与全选切换，批量暂停、批量继续与删除收纳于底部胶囊栏。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun TaskSelectionFloatingToolbar(
    visible: Boolean,
    selectedCount: Int,
    pauseCount: Int,
    resumeCount: Int,
    onPause: (() -> Unit)?,
    onResume: (() -> Unit)?,
    onDelete: () -> Unit,
    applyNavBarsPadding: Boolean,
    modifier: Modifier = Modifier,
) {
    var retainedSelected by remember { mutableIntStateOf(1) }
    var retainedPauseCount by remember { mutableIntStateOf(0) }
    var retainedResumeCount by remember { mutableIntStateOf(0) }
    var retainedPause by remember { mutableStateOf<(() -> Unit)?>(null) }
    var retainedResume by remember { mutableStateOf<(() -> Unit)?>(null) }
    if (selectedCount > 0) {
        retainedSelected = selectedCount
        retainedPauseCount = pauseCount
        retainedResumeCount = resumeCount
        retainedPause = onPause
        retainedResume = onResume
    }
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    val isEInk = LocalAppearance.current.isEInk
    val toolbarColors = if (isEInk) {
        FloatingToolbarDefaults.standardFloatingToolbarColors(
            toolbarContainerColor = Color.White,
            toolbarContentColor = Color.Black,
        )
    } else {
        FloatingToolbarDefaults.standardFloatingToolbarColors()
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(motion.fastSpatialSpec()) { it / 2 } + fadeIn(motion.fastEffectsSpec()),
        exit = slideOutVertically(motion.fastSpatialSpec()) { it / 2 } + fadeOut(motion.fastEffectsSpec()),
        modifier = modifier
            .then(if (applyNavBarsPadding) Modifier.navigationBarsPadding() else Modifier)
            .padding(
                horizontal = FloatingToolbarDefaults.ScreenOffset,
                vertical = FloatingToolbarDefaults.ScreenOffset,
            ),
    ) {
        HorizontalFloatingToolbar(
            expanded = true,
            colors = toolbarColors,
            expandedShadowElevation = if (isEInk) 0.dp else 3.dp,
            collapsedShadowElevation = if (isEInk) 0.dp else 2.dp,
            modifier = if (isEInk) Modifier.border(1.dp, Color.Black, FloatingToolbarDefaults.ContainerShape) else Modifier,
        ) {
            val contentTint = LocalContentColor.current
            val pauseAction = retainedPause
            val resumeAction = retainedResume
            if (pauseAction != null && retainedPauseCount > 0) {
                TooltipIconButton(
                    icon = Icons.Outlined.Pause,
                    label = tr("暂停 $retainedPauseCount 项", "Pause ${enCount(retainedPauseCount, "item", "items")}", "$retainedPauseCount 項目を一時停止", "${retainedPauseCount}개 항목 일시 중지", "暫停 $retainedPauseCount 項"),
                    onClick = pauseAction,
                    tint = contentTint,
                )
            }
            if (resumeAction != null && retainedResumeCount > 0) {
                TooltipIconButton(
                    icon = Icons.Outlined.PlayArrow,
                    label = tr("继续 $retainedResumeCount 项", "Resume ${enCount(retainedResumeCount, "item", "items")}", "$retainedResumeCount 項目を再開", "${retainedResumeCount}개 항목 계속", "繼續 $retainedResumeCount 項"),
                    onClick = resumeAction,
                    tint = contentTint,
                )
            }
            if ((pauseAction != null && retainedPauseCount > 0) || (resumeAction != null && retainedResumeCount > 0)) {
                VerticalDivider(
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .padding(horizontal = 2.dp)
                        .height(20.dp),
                    color = if (isEInk) Color.Black else colors.outlineVariant.copy(alpha = 0.7f),
                )
            }
            TooltipIconButton(
                icon = Icons.Outlined.Delete,
                label = tr("删除所选 $retainedSelected 项", "Delete $retainedSelected selected items", "選択した $retainedSelected 項目を削除", "선택한 ${retainedSelected}개 항목 삭제", "刪除所選 $retainedSelected 項"),
                onClick = onDelete,
                tint = if (isEInk) Color.Black else colors.error,
            )
        }
    }
}

@Composable
internal fun TaskRateBar(state: TransfersState) {
    val framed = LocalFramed.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(start = if (framed) 20.dp else 12.dp, end = if (framed) 16.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Rates(state, compact = !framed)
        if (isDesktopLayout()) DownloadEta(state, Modifier.weight(1f))
    }
}
