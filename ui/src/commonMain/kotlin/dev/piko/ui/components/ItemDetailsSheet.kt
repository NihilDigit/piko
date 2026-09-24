package dev.piko.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** 详情面板里的一项操作。[destructive] 的操作单独成组排在最后，并用错误色。 */
class SheetAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
)

/**
 * 单个条目的详情与操作面板，网盘列表、瀑布流与传输列表共用。
 *
 * 用模态 BottomSheet 而不是锚在更多按钮上的 DropdownMenu：面板顶部能放下完整、
 * 可选中复制的标题与元信息，列表里被截成两行的长名字在这里总能看全；操作项的
 * 触控区也按列表项给足。
 *
 * 头部 [headerIcon] 填满 40dp 的圆角方块，调用方应只放类型图标，不放缩略图：
 * 从这里绕过防窥遮蔽看到画面不符合用户预期。[metaParts] 由 [MetaRow] 排成一行，
 * [extraLines] 放在其下，默认是 bodyMedium，调用方自定颜色。
 *
 * 操作用 M3 Expressive 的分段列表，与设置页一致；删除一类操作单独成组，
 * 与其余操作隔开，免得误触。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailsSheet(
    title: String,
    headerIcon: @Composable () -> Unit,
    actions: List<SheetAction>,
    onDismiss: () -> Unit,
    metaParts: List<String> = emptyList(),
    extraLines: @Composable ColumnScope.() -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // 先播完收起动画再执行动作：直接移出组合会让面板瞬间消失，
    // 接着弹出的对话框也少了一个视觉上的因果。
    fun dismissThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.small),
            ) {
                headerIcon()
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                SelectionContainer {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                if (metaParts.isNotEmpty()) {
                    MetaRow(
                        parts = metaParts,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                    extraLines()
                }
            }
        }
        val (regular, destructive) = actions.partition { !it.destructive }
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            if (regular.isNotEmpty()) SheetActionGroup(regular, onAction = ::dismissThen)
            if (regular.isNotEmpty() && destructive.isNotEmpty()) Spacer(modifier = Modifier.height(12.dp))
            if (destructive.isNotEmpty()) SheetActionGroup(destructive, onAction = ::dismissThen)
        }
    }
}

@Composable
private fun SheetActionGroup(actions: List<SheetAction>, onAction: (() -> Unit) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        actions.forEachIndexed { index, action ->
            val color = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            SegmentedListItem(
                onClick = { onAction(action.onClick) },
                shapes = ListItemDefaults.segmentedShapes(index = index, count = actions.size),
                // 面板是 surfaceContainerLow，段取高两级才看得出分段
                colors = ListItemDefaults.segmentedColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = color,
                    leadingContentColor = if (action.destructive) color else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                leadingContent = { Icon(action.icon, contentDescription = null) },
                content = { Text(action.label) },
            )
        }
    }
}
