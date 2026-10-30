package dev.piko.ui.components

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
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
 * 蜗牛模式的开关，照 FDM 放在速度旁边：单击开关，右边紧贴的调节按钮改上限（右键开关同样可以）。
 * 开着时换成 tertiary 容器色、写明上限，与侧边栏「传输」那一项的限速色是同一种，一眼看得出速度是被压着的。
 *
 * 两块连成 M3 Expressive 的分体按钮（split button）：外侧全圆、相邻处小圆角，中间隔 2dp。
 * 调节按钮与开关同色：开关开着时一个 tertiary、一个 secondary，看着像两个不相干的按钮。
 * 原来改上限只有右键，触屏上没有右键，手机上根本进不去，只能绕到设置页。
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
    val full = CornerSize(50)
    val inner = CornerSize(4.dp)
    val leading = RoundedCornerShape(topStart = full, topEnd = inner, bottomEnd = inner, bottomStart = full)
    val trailing = RoundedCornerShape(topStart = inner, topEnd = full, bottomEnd = full, bottomStart = inner)
    val height = 36.dp
    // 按中线对齐、两块同高：两个按钮外面各包着一圈高度不同的最小触控区，按顶边排就一高一低
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContextMenuArea(actions = { listOf(SheetAction(Icons.Outlined.Tune, tr("设置上限", "Set limit", "上限を設定", "한도 설정", "設定上限"), { editing = true })) }) {
            TonalToggleButton(
                checked = mode.enabled,
                onCheckedChange = { enabled -> scope.launch { preferences.setSnailMode(mode.copy(enabled = enabled)) } },
                shapes = ToggleButtonShapes(shape = leading, pressedShape = leading, checkedShape = leading),
                colors = ToggleButtonDefaults.tonalToggleButtonColors(
                    checkedContainerColor = colors.tertiaryContainer,
                    checkedContentColor = colors.onTertiaryContainer,
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(height),
            ) {
                Icon(Icons.Outlined.SlowMotionVideo, contentDescription = tr("蜗牛模式", "Snail mode", "低速モード", "저속 모드", "蝸牛模式"), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (mode.enabled) tr("限速 ${mode.limitLabel()}", "Limit ${mode.limitLabel()}", "速度制限 ${mode.limitLabel()}", "속도 제한 ${mode.limitLabel()}", "限速 ${mode.limitLabel()}") else tr("蜗牛模式", "Snail mode", "低速モード", "저속 모드", "蝸牛模式"),
                    maxLines = 1,
                )
            }
        }
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(stringResource(Res.string.text_set_limit)) } },
            state = rememberTooltipState(),
        ) {
            FilledTonalIconButton(
                onClick = { editing = true },
                shape = trailing,
                colors = if (mode.enabled) {
                    IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = colors.tertiaryContainer,
                        contentColor = colors.onTertiaryContainer,
                    )
                } else {
                    IconButtonDefaults.filledTonalIconButtonColors()
                },
                modifier = Modifier.size(width = 36.dp, height = height),
            ) {
                Icon(Icons.Outlined.Tune, contentDescription = tr("设置上限", "Set limit", "上限を設定", "한도 설정", "設定上限"), modifier = Modifier.size(18.dp))
            }
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
