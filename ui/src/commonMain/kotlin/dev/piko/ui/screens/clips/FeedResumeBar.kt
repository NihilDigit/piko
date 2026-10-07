package dev.piko.ui.screens.clips

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SwipeVertical
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.ui.components.TooltipIconButton

/**
 * 信息流挂起时网盘页底部的一条：继续刷，或者就此关掉。挂起见 PikoMainScaffold 的 locateFromFeed。
 *
 * 不做成侧栏收起后贴边的一条窄条：窄窗口与手机上信息流是全屏形态，没有侧栏可收，
 * 两种形态都浮在网盘页底部、FAB 的左边，位置由 PikoMainScaffold 安排。
 */
@Composable
internal fun FeedResumeBar(
    visible: Boolean,
    folderName: String?,
    onResume: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { it } + fadeIn(),
        exit = slideOutVertically(MaterialTheme.motionScheme.fastSpatialSpec()) { it } + fadeOut(),
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(16.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp,
            tonalElevation = 3.dp,
        ) {
            Row(
                modifier = Modifier.padding(start = 6.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 带权重的最后量：关闭按钮先占住位置，长目录名在剩下的宽度里截断。按先后量的话，
                // 窄屏上继续按钮先把宽度吃满，关闭按钮被挤出条外
                Button(
                    onClick = onResume,
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    Icon(Icons.Outlined.SwipeVertical, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = folderName?.let { "继续刷「$it」" } ?: "继续刷",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp).widthIn(max = 280.dp),
                    )
                }
                TooltipIconButton(Icons.Outlined.Close, "关闭信息流", onClose)
            }
        }
    }
}
