package dev.piko.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 空态。[actionText] 给的是这一屏真正的下一步（如「新建离线任务」），用 tonal 按钮：
 * 它是空屏上唯一的动作，但不是整个应用的主行动号召，填充按钮太重。
 */
@Composable
fun PikoEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector = Icons.Outlined.Inbox,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    StateLayout(title = title, description = description, icon = icon, modifier = modifier) {
        if (!actionText.isNullOrEmpty() && onActionClick != null) {
            Spacer(modifier = Modifier.height(20.dp))
            FilledTonalButton(onClick = onActionClick) { Text(text = actionText) }
        }
    }
}

/**
 * 首屏读取失败。与空态分开写，因为两者的按钮不是一个强调档：重试是这一屏唯一能做的事，
 * 却不该渲染成需要下决心的按钮，所以用 text button。图标换成 ErrorOutline，与空态的图标成对，
 * 读者一眼分得出「没读到」和「本来就没有」。
 */
@Composable
fun PikoErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "加载失败",
) {
    StateLayout(title = title, description = message, icon = Icons.Outlined.ErrorOutline, modifier = modifier) {
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun StateLayout(
    title: String,
    description: String?,
    icon: ImageVector,
    modifier: Modifier,
    action: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 图标是状态标识，不是插图：比标题大一档即可。原先 64dp 配 outlineVariant 是插画的做法，
        // outline 系是描边角色，拿来染图标在浅色主题下对比度不够
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (!description.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        action()
    }
}

/**
 * 列表页首屏的三态：骨架、失败、内容。加载与失败只在列表为空时占整屏；已有内容时刷新走下拉，
 * 失败只发提示，已经读到的东西不清掉。空列表的空态仍归 [content] 自己画，它常要放进可下拉的列表里。
 *
 * 切换的键是哪一态而不是 loadError 本身：淡出没走完时旧分支仍在组合，那一刻 loadError 可能已被清空，
 * 所以失败文案随态带进 target。用 Crossfade 而不是 AnimatedContent：同一块区域换三种填充，
 * 没有方向，也不需要尺寸过渡。
 */
@Composable
fun FirstScreenState(
    isLoading: Boolean,
    error: String?,
    isEmpty: Boolean,
    onRetry: () -> Unit,
    skeleton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val phase = when {
        isLoading && isEmpty -> FirstScreenPhase.Loading
        error != null && isEmpty -> FirstScreenPhase.Failed(error)
        else -> FirstScreenPhase.Content
    }
    Crossfade(
        targetState = phase,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        modifier = modifier.fillMaxSize(),
        label = "first_screen",
    ) { current ->
        when (current) {
            FirstScreenPhase.Loading -> skeleton()
            is FirstScreenPhase.Failed -> PikoErrorState(
                message = current.message,
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize(),
            )
            FirstScreenPhase.Content -> content()
        }
    }
}

private sealed interface FirstScreenPhase {
    data object Loading : FirstScreenPhase

    data class Failed(val message: String) : FirstScreenPhase

    data object Content : FirstScreenPhase
}
