package dev.piko.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 竖屏放横屏片子时的全屏入口，落在画面下方的黑边里。
 *
 * 全屏入口在底栏那排图标里太远，这里在拇指位置再给一个。用 Small 规格：Medium 在黑边里
 * 比中央的播放键还抢眼，而它只是个次要入口。位置由 [PlayerBottomStack] 统一安排。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FullscreenPromptButton(
    visible: Boolean,
    onClick: () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motion.defaultEffectsSpec()) + slideInVertically(motion.defaultSpatialSpec()) { it / 2 },
        exit = fadeOut(motion.fastEffectsSpec()) + slideOutVertically(motion.fastSpatialSpec()) { it / 2 },
    ) {
        Button(onClick = onClick, shapes = ButtonDefaults.shapes()) {
            Icon(
                imageVector = Icons.Filled.Fullscreen,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("全屏播放")
        }
    }
}

/**
 * 续播提示：「从 xx 继续播放」，附带「从头播放」。
 *
 * 形式上就是一条带操作的 Snackbar：短暂、不打断播放、只有一个操作，
 * 用 Snackbar 组件本身而不是自己拼一个胶囊。位置由 [PlayerBottomStack] 统一安排。
 */
@Composable
internal fun ResumeTipCapsule(
    visible: Boolean,
    resumedPositionMillis: Long,
    onRestart: () -> Unit,
    onDismiss: () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motion.defaultEffectsSpec()) + slideInVertically(motion.defaultSpatialSpec()) { it },
        exit = fadeOut(motion.fastEffectsSpec()) + slideOutVertically(motion.fastSpatialSpec()) { it },
    ) {
        // 与 SnackbarHost 里 withDismissAction 的提示保持同一形态：操作之外再给一个关闭
        Snackbar(
            action = {
                // TextButton 自带的内容色是 primary，会盖掉 Snackbar 给操作区的 inversePrimary；
                // 在反色的提示条上要用后者，与 SnackbarHost 默认的写法一致
                TextButton(
                    onClick = onRestart,
                    colors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor),
                ) { Text("从头播放") }
            },
            dismissAction = {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            },
            modifier = Modifier.widthIn(max = 480.dp),
        ) {
            Text("从 ${formatTime(resumedPositionMillis)} 继续播放")
        }
    }
}

/**
 * 画面底部的提示区：消息提示、续播提示、全屏入口自上而下排成一列。
 *
 * 三者原先各自按固定的底部间距定位，同时出现就会叠在一起。放进同一列后由布局
 * 负责让位；整列在控件栏出现时抬到底栏上方，收起后落回贴近底边的位置。
 */
@Composable
internal fun BoxScope.PlayerBottomStack(
    controlsVisible: Boolean,
    isLandscape: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    val clearance by animateDpAsState(
        targetValue = when {
            controlsVisible && isLandscape -> BOTTOM_BAR_CLEARANCE_LANDSCAPE
            controlsVisible -> BOTTOM_BAR_CLEARANCE_PORTRAIT
            else -> 16.dp
        },
        animationSpec = motion.defaultSpatialSpec(),
    )
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(start = 16.dp, end = 16.dp, bottom = clearance),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/**
 * 锁定手势与控件的开关。锁定后它是唯一可点的控件。
 *
 * 用可切换图标按钮：锁定态换成方角并填充，和未锁定一眼能分开。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LockToggle(
    isLocked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalIconToggleButton(
        checked = isLocked,
        onCheckedChange = { onToggle() },
        shapes = IconButtonDefaults.toggleableShapes(),
        modifier = modifier,
    ) {
        Icon(
            imageVector = if (isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
            contentDescription = if (isLocked) "解锁屏幕" else "锁定屏幕",
        )
    }
}

/**
 * 播放失败提示：原因、重试，可选返回。
 *
 * 错误卡片出现时控件栏保持展开，返回键始终可达；卡片里的返回是给竖屏单手操作的近路。
 */
@Composable
internal fun PlaybackErrorCard(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier
            .padding(24.dp)
            .widthIn(max = 400.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text("无法播放", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            // 卡片是不透明的 surfaceContainerHigh，取自 PlayerTheme 的深色配色，按钮用默认色即可，
            // 与底下的画面无关。重试用 tonal：一次播放失败不该渲染成需要下决心的主按钮
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onBack != null) {
                    TextButton(onClick = onBack) { Text("返回") }
                }
                FilledTonalButton(onClick = onRetry) { Text("重试") }
            }
        }
    }
}

private val BOTTOM_BAR_CLEARANCE_PORTRAIT = 120.dp
private val BOTTOM_BAR_CLEARANCE_LANDSCAPE = 104.dp
