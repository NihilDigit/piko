package dev.piko.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import dev.piko.ui.platform.windowDragArea
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piko.ui.adaptive.readableSidePadding
import dev.piko.ui.platform.rememberCaptionSlot
import dev.piko.ui.theme.LocalFramed
import dev.piko.ui.theme.FrameTopRowHeight
import androidx.compose.ui.graphics.Color

/**
 * 列表离开顶端时顶栏换成滚动态的填充色（M3 top app bar 的 on scroll），按列表眼下的位置判断：[atTop]
 * 读列表状态，例如 `{ state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0 }`。
 *
 * 不用 pinnedScrollBehavior 自带的那一套：它经 nestedScroll 把滚动量累加进 contentOffset，只增减不校正，
 * 换了一份内容也不清零。网盘页换文件夹、恢复上次的滚动位置、我的分享取消几项后变短，列表明明在顶端、
 * 甚至根本滚不动，顶栏仍是滚动态的颜色。这里每逢 [atTop] 变化直接写 contentOffset：在顶端为 0，
 * 否则写到最小，overlappedFraction 即为 1。调用方不要再挂它的 nestedScrollConnection，两边会互相改写。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberListScrollTint(atTop: () -> Boolean): TopAppBarScrollBehavior {
    val behavior = TopAppBarDefaults.pinnedScrollBehavior()
    val latestAtTop by rememberUpdatedState(atTop)
    LaunchedEffect(behavior) {
        snapshotFlow { latestAtTop() }.collect { top ->
            behavior.state.contentOffset = if (top) 0f else -Float.MAX_VALUE
        }
    }
    return behavior
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PikoTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    onBackClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.surface,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
    ),
    /**
     * 页面内容按 [readableSidePadding] 收在居中的一栏时为 true：返回、标题与动作一起缩进同样的量，
     * 与下面的内容对齐；底色仍铺满。否则宽窗口里标题贴在最左、内容在正中，两者对不上。
     */
    alignToReadableWidth: Boolean = false,
) {
    if (alignToReadableWidth) {
        BoxWithConstraints(modifier) {
            PikoTopBarContent(title, Modifier, navigationIcon, onBackClick, actions, scrollBehavior, colors, readableSidePadding(maxWidth))
        }
    } else {
        PikoTopBarContent(title, modifier, navigationIcon, onBackClick, actions, scrollBehavior, colors, 0.dp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PikoTopBarContent(
    title: String,
    modifier: Modifier,
    navigationIcon: (@Composable () -> Unit)?,
    onBackClick: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
    scrollBehavior: TopAppBarScrollBehavior?,
    colors: TopAppBarColors,
    sideInset: Dp,
) {
    // 贴着窗口右上角时（标题栏并进内容），窗口按钮接在动作按钮后面
    val caption = rememberCaptionSlot()
    // 有外框时顶栏落在外框色上（见 PikoScaffold），滚动后也不换色：页头与内容已由下面的卡片分开，
    // 再给顶栏换一层底色就又多出一个长方形
    val barColors = if (LocalFramed.current) {
        TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        )
    } else {
        colors
    }
    val barHeight = if (LocalFramed.current) FrameTopRowHeight else TopAppBarDefaults.TopAppBarExpandedHeight
    TopAppBar(
        title = {
            // 在窗口顶上时标题这一格铺满返回与动作之间的空白，整块是拖动区：原来只有窗口按钮前那一截能拖，
            // 设置这类只有标题的页，顶上一大片空白按住不动。高度写死为顶栏高：标题格的高度不设上限，
            // fillMaxHeight 会把整条顶栏撑到窗口那么高（实测）
            Box(
                modifier = if (caption.atTop) Modifier.fillMaxWidth().height(barHeight).windowDragArea() else Modifier,
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleLargeEmphasized,
                )
            }
        },
        modifier = modifier.then(caption.modifier),
        navigationIcon = {
            if (navigationIcon != null) {
                navigationIcon()
            } else if (onBackClick != null) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                    )
                }
            }
        },
        actions = {
            actions()
            caption.buttons?.invoke()
        },
        windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = sideInset, right = sideInset)),
        colors = barColors,
        // 外框里与网盘页地址栏那一行、侧边栏的图标行同高（56dp），换页时卡片的上沿不跳；M3 默认的 64dp 会低出一截
        expandedHeight = barHeight,
        scrollBehavior = scrollBehavior,
    )
}
