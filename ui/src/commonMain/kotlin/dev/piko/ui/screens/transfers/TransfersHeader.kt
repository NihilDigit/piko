package dev.piko.ui.screens.transfers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.piko.shared.state.TransferKind
import dev.piko.shared.state.TransfersState

/** 类别标签上的图标，与侧边栏、各行用的同一套。 */
private val TransferKind.icon: ImageVector
    get() = when (this) {
        TransferKind.ALL -> Icons.Outlined.SwapVert
        TransferKind.DOWNLOAD -> Icons.Outlined.Download
        TransferKind.UPLOAD -> Icons.Outlined.Upload
        TransferKind.CLOUD -> Icons.Outlined.Cloud
        TransferKind.FILES -> Icons.Outlined.SwapVert
    }

/** 紧凑窗口用类型菜单，避免把所有类别挤在标题旁。空类别保留在菜单中。 */
@Composable
internal fun TaskKindFilter(state: TransfersState) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text("${state.filter.label} ${state.counts[state.filter] ?: 0}", maxLines = 1)
            Icon(Icons.Outlined.ExpandMore, contentDescription = "筛选任务类型", modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TransferKind.entries.forEach { kind ->
                DropdownMenuItem(
                    text = { Text("${kind.label} ${state.counts[kind] ?: 0}") },
                    leadingIcon = { Icon(kind.icon, contentDescription = null) },
                    onClick = { expanded = false; state.changeFilter(kind) },
                )
            }
        }
    }
}
