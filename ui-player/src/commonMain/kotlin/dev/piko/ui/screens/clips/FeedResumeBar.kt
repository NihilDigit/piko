package dev.piko.ui.screens.clips

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SwipeVertical
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.tr
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.isEInk

/**
 * 信息流挂起时网盘页底部的一条：继续刷，或者就此关掉。挂起见 PikoMainScaffold 的 locateFromFeed。
 *
 * 不做成侧栏收起后贴边的一条窄条：窄窗口与手机上信息流是全屏形态，没有侧栏可收，
 * 两种形态都浮在网盘页底部、FAB 的左边，位置由 PikoMainScaffold 安排。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FeedResumeBar(
    visible: Boolean,
    folderName: String?,
    onResume: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    val isEInk = LocalAppearance.current.isEInk
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(motion.defaultSpatialSpec()) { it } + fadeIn(motion.defaultEffectsSpec()),
        exit = slideOutVertically(motion.fastSpatialSpec()) { it } + fadeOut(motion.fastEffectsSpec()),
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(16.dp),
    ) {
        HorizontalFloatingToolbar(
            expanded = true,
            colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
                toolbarContainerColor = if (isEInk) Color.White else MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            contentPadding = PaddingValues(start = 6.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
            expandedShadowElevation = if (isEInk) 0.dp else FloatingToolbarDefaults.ContainerExpandedElevation,
            collapsedShadowElevation = if (isEInk) 0.dp else FloatingToolbarDefaults.ContainerCollapsedElevation,
            modifier = if (isEInk) {
                Modifier.border(1.dp, MaterialTheme.colorScheme.outline, FloatingToolbarDefaults.ContainerShape)
            } else {
                Modifier
            },
        ) {
            // 带权重的最后量：关闭按钮先占住位置，长目录名在剩下的宽度里截断。按先后量的话，
            // 窄屏上继续按钮先把宽度吃满，关闭按钮被挤出条外
            Button(
                onClick = onResume,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Icon(Icons.Outlined.SwipeVertical, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    text = folderName?.let { tr("继续浏览「$it」", "Resume “$it”", "「$it」を再開", "“$it” 계속 보기", "繼續瀏覽「$it」") } ?: tr("继续浏览", "Resume", "再開", "계속 보기", "繼續瀏覽"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp).widthIn(max = 280.dp),
                )
            }
            TooltipIconButton(Icons.Outlined.Close, tr("关闭信息流", "Close feed", "フィードを閉じる", "피드 닫기", "關閉資訊流"), onClose)
        }
    }
}
