package dev.piko.ui.components

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SlowMotionVideo
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.data.auth.SnailMode
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.screens.settings.SnailModeDialog
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.isEInk
import kotlinx.coroutines.launch

/**
 * 蜗牛模式的开关，照 FDM 放在速度旁边：单击开关，右边紧贴的调节按钮改上限（右键开关同样可以）。
 * 开着时换成 tertiary 容器色、写明上限，与侧边栏「传输」那一项的限速色是同一种，一眼看得出速度是被压着的。
 *
 * 采用 M3 Expressive [SplitButtonLayout] 组合主开关与上限设置入口，自带外圆内收与按压形变。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SnailModeToggle(modifier: Modifier = Modifier) {
    val preferences = LocalPikoServices.current.preferences
    val scope = rememberCoroutineScope()
    val mode by preferences.snailModeFlow.collectAsStateWithLifecycle(initialValue = SnailMode())
    var editing by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val isEInk = LocalAppearance.current.isEInk
    val height = 36.dp
    val segmentColors = when {
        isEInk && mode.enabled -> ButtonDefaults.filledTonalButtonColors(
            containerColor = Color.Black,
            contentColor = Color.White,
        )
        isEInk -> ButtonDefaults.filledTonalButtonColors(
            containerColor = Color.White,
            contentColor = Color.Black,
        )
        mode.enabled -> ButtonDefaults.filledTonalButtonColors(
            containerColor = colors.tertiaryContainer,
            contentColor = colors.onTertiaryContainer,
        )
        else -> ButtonDefaults.filledTonalButtonColors()
    }
    val segmentBorder = if (isEInk) BorderStroke(1.dp, colors.outline) else null
    val segmentElevation = if (isEInk) null else ButtonDefaults.filledTonalButtonElevation()

    SplitButtonLayout(
        modifier = modifier,
        leadingButton = {
            ContextMenuArea(
                actions = {
                    listOf(
                        SheetAction(
                            Icons.Outlined.Tune,
                            tr("设置上限", "Set limit", "上限を設定", "한도 설정", "設定上限"),
                            { editing = true },
                        ),
                    )
                },
            ) {
                SplitButtonDefaults.TonalLeadingButton(
                    onClick = { scope.launch { preferences.setSnailMode(mode.copy(enabled = !mode.enabled)) } },
                    shapes = SplitButtonDefaults.leadingButtonShapesFor(height),
                    colors = segmentColors,
                    elevation = segmentElevation,
                    border = segmentBorder,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(height),
                ) {
                    Icon(
                        Icons.Outlined.SlowMotionVideo,
                        contentDescription = tr("蜗牛模式", "Snail mode", "低速モード", "저속 모드", "蝸牛模式"),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (mode.enabled) {
                            tr("限速 ${mode.limitLabel()}", "Limit ${mode.limitLabel()}", "速度制限 ${mode.limitLabel()}", "속도 제한 ${mode.limitLabel()}", "限速 ${mode.limitLabel()}")
                        } else {
                            tr("蜗牛模式", "Snail mode", "低速モード", "저속 모드", "蝸牛模式")
                        },
                        maxLines = 1,
                    )
                }
            }
        },
        trailingButton = {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                tooltip = { PlainTooltip { Text(stringResource(Res.string.text_set_limit)) } },
                state = rememberTooltipState(),
            ) {
                SplitButtonDefaults.TonalTrailingButton(
                    checked = editing,
                    onCheckedChange = { editing = true },
                    shapes = SplitButtonDefaults.trailingButtonShapesFor(height),
                    colors = segmentColors,
                    elevation = segmentElevation,
                    border = segmentBorder,
                    contentPadding = SplitButtonDefaults.trailingButtonContentPaddingFor(height),
                    modifier = Modifier.height(height),
                ) {
                    Icon(
                        Icons.Outlined.Tune,
                        contentDescription = tr("设置上限", "Set limit", "上限を設定", "한도 설정", "設定上限"),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
    )
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
