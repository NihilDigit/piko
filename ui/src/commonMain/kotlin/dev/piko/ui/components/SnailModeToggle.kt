package dev.piko.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SlowMotionVideo
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TonalToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.data.auth.SnailMode
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.screens.settings.SnailModeDialog
import kotlinx.coroutines.launch

/**
 * 蜗牛模式的开关，照 FDM 放在速度旁边：单击开关，右键「设置上限」改上限。开着时换成 tertiary 容器色、写明上限，
 * 与侧边栏「传输」那一项的限速色是同一种，一眼看得出速度是被压着的。
 *
 * 形状不随按下与选中变，理由见 connectedToggleShapes。
 */
@Composable
fun SnailModeToggle(modifier: Modifier = Modifier) {
    val preferences = LocalPikoServices.current.preferences
    val scope = rememberCoroutineScope()
    val mode by preferences.snailModeFlow.collectAsStateWithLifecycle(initialValue = SnailMode())
    var editing by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    ContextMenuArea(actions = { listOf(SheetAction(Icons.Outlined.Tune, "设置上限", { editing = true })) }) {
        TonalToggleButton(
            checked = mode.enabled,
            onCheckedChange = { enabled -> scope.launch { preferences.setSnailMode(mode.copy(enabled = enabled)) } },
            shapes = ToggleButtonShapes(shape = CircleShape, pressedShape = CircleShape, checkedShape = CircleShape),
            colors = ToggleButtonDefaults.tonalToggleButtonColors(
                checkedContainerColor = colors.tertiaryContainer,
                checkedContentColor = colors.onTertiaryContainer,
            ),
            modifier = modifier.heightIn(min = 40.dp),
        ) {
            Icon(Icons.Outlined.SlowMotionVideo, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (mode.enabled) "限速 ${mode.limitLabel()}" else "蜗牛模式", maxLines = 1)
        }
    }
    if (editing) {
        SnailModeDialog(
            current = mode,
            onSave = { next ->
                editing = false
                scope.launch { preferences.setSnailMode(next) }
            },
            onDismiss = { editing = false },
        )
    }
}

/** 按钮上写得下的上限：只写下行，上行在对话框与设置页里。 */
private fun SnailMode.limitLabel(): String = "${(downloadKiBps.toLong() * 1024).toReadableSize()}/s"
