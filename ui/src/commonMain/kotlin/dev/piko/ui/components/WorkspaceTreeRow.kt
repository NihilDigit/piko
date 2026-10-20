package dev.piko.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.*

/** Shared tree geometry; selection and file actions are optional capabilities of each instance. */
@Composable
internal fun WorkspaceTreeRow(
    name: String, depth: Int, icon: ImageVector, expanded: Boolean?, onExpand: () -> Unit,
    onOpen: () -> Unit, modifier: Modifier = Modifier, emphasized: Boolean = false,
    leading: @Composable RowScope.() -> Unit = {}, trailing: @Composable RowScope.() -> Unit = {},
    supporting: String? = null,
    item: FilePresentation? = null,
    actions: FileItemActions? = null,
    reserveExpandSpace: Boolean = true,
) {
    Row(modifier.fillMaxWidth().onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) false
        else when {
            event.key == Key.DirectionRight && expanded == false -> { onExpand(); true }
            event.key == Key.DirectionLeft && expanded == true -> { onExpand(); true }
            else -> false
        }
    }.clickable(onClick = actions?.primary ?: onOpen).heightIn(min = 48.dp)
        .padding(start = 4.dp + 16.dp * depth, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (expanded != null) IconButton(onClick = onExpand, modifier = Modifier.size(36.dp)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, if (expanded) "收起 $name" else "展开 $name",
                Modifier.size(18.dp).rotate(if (expanded) 90f else 0f))
        } else if (reserveExpandSpace) Spacer(Modifier.width(36.dp))
        leading()
        if (item != null) FileArtwork(item.visual, FileArtworkFormat.Leading, modifier = Modifier.size(32.dp))
        else Icon(icon, null, Modifier.size(20.dp), tint = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileItemTitle(item?.name ?: FileItemName(name), modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (item?.name?.original != null) 1 else 2,
                    fontWeight = if (emphasized) FontWeight.SemiBold else if (item?.visual?.isFolder == true) FontWeight.Medium else null)
                if (item != null && item.marks.isNotEmpty()) FileItemMarks(item.marks)
            }
            if (item != null) ProvideTextStyle(MaterialTheme.typography.bodySmall) { FileItemInformation(item) }
            else supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing()
        if (actions != null) FileItemActionButtons(actions)
    }
}
