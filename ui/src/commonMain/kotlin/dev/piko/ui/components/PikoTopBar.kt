package dev.piko.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piko.ui.adaptive.readableSidePadding

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
    TopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLargeEmphasized,
            )
        },
        modifier = modifier,
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
        actions = actions,
        windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = sideInset, right = sideInset)),
        colors = colors,
        scrollBehavior = scrollBehavior,
    )
}
