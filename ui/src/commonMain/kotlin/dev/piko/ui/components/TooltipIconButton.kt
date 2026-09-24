package dev.piko.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 带文字提示的图标按钮。桌面上鼠标悬停即显示，触屏上长按显示；快捷键写在提示里，
 * 这是它唯一能被发现的地方。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TooltipIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shortcut: String? = null,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(if (shortcut != null) "$label ($shortcut)" else label) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            if (tint == Color.Unspecified) {
                Icon(icon, contentDescription = label)
            } else {
                Icon(icon, contentDescription = label, tint = tint)
            }
        }
    }
}
