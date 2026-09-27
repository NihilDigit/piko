package dev.piko.ui.screens.drive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.DriveTab
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.fileDropTarget
import dev.piko.ui.components.verticalWheelScrollsRow

/**
 * 网盘页的标签栏，只在宽窗口、开了不止一个标签时出现。一个标签是一个位置，各有各的后退与前进：
 * 整理文件时开两三个，把东西拖到另一个标签上就是移进它停着的文件夹。
 * 点一下切过去，中键点它或点叉关掉；关闭按钮平时只在活动标签与鼠标停着的那个上出现，免得一排叉。
 */
@Composable
internal fun DriveTabBar(
    tabs: List<DriveTab>,
    activeId: Long,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onNewTab: () -> Unit,
    newTabShortcut: String,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    // 新开的标签在右边看不见时滚过去
    LaunchedEffect(activeId) {
        val index = tabs.indexOfFirst { it.id == activeId }
        if (index == tabs.lastIndex) scroll.animateScrollTo(scroll.maxValue)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TabBarHeight)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f, fill = false).verticalWheelScrollsRow(scroll).horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (tab in tabs) {
                TabChip(tab, active = tab.id == activeId, onSelect = { onSelect(tab.id) }, onClose = { onClose(tab.id) })
            }
        }
        TooltipIconButton(Icons.Outlined.Add, "新建标签页", onNewTab, shortcut = newTabShortcut)
    }
}

@Composable
private fun TabChip(tab: DriveTab, active: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val target = tab.stack.lastOrNull()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(tab.stack.joinToString(" › ") { it.name }) } },
        state = rememberTooltipState(),
    ) {
        Row(
            modifier = Modifier
                // 拖到别的标签上：移进它停着的文件夹
                .then(if (!active && target != null) Modifier.fileDropTarget("tab:${tab.id}", target) else Modifier)
                .clip(CircleShape)
                .background(if (active) colors.secondaryContainer else Color.Transparent)
                .hoverable(interaction)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press && event.buttons.isTertiaryPressed) onClose()
                        }
                    }
                }
                .clickable(role = Role.Tab, onClick = onSelect)
                .height(32.dp)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Folder,
                contentDescription = null,
                tint = if (active) colors.onSecondaryContainer else colors.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                tab.title,
                style = MaterialTheme.typography.labelLarge,
                color = if (active) colors.onSecondaryContainer else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).widthIn(max = TabTitleMaxWidth),
            )
            // 叉的位置一直留着，出现与消失时标签不变宽
            Box(Modifier.padding(start = 4.dp).size(24.dp), contentAlignment = Alignment.Center) {
                if (active || hovered) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "关闭标签页",
                        tint = if (active) colors.onSecondaryContainer else colors.onSurfaceVariant,
                        modifier = Modifier.size(24.dp).clip(CircleShape).clickable(onClick = onClose).padding(4.dp),
                    )
                }
            }
        }
    }
}

private val TabBarHeight = 44.dp
private val TabTitleMaxWidth = 180.dp
