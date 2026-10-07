package dev.piko.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.piko.shared.search.fuzzyScore

/**
 * 命令面板的一项：跳到一个文件夹，或做一件事。[keywords] 是标题之外也能搜到它的词（英文名、别称），
 * [detail] 是右侧的灰字：文件夹的路径、命令的快捷键。
 * [disabledReason] 非 null 时这一项置灰、执行不了，右侧改写这个原因：与界面上同一个入口一致，置灰而不藏起来。
 */
class PaletteItem(
    val title: String,
    val icon: ImageVector,
    val group: String,
    val detail: String? = null,
    val keywords: String = "",
    val disabledReason: String? = null,
    val run: () -> Unit,
)

/**
 * 各页往命令面板里放的命令。页面在组合里时经 [ContributePaletteItems] 登记一个取命令的函数，离开时撤掉；
 * 面板打开时才去取，命令里读到的是那一刻的状态。
 */
class PaletteRegistry {
    internal val providers = mutableStateMapOf<Any, () -> List<PaletteItem>>()

    fun items(): List<PaletteItem> = providers.values.flatMap { it() }
}

val LocalPaletteRegistry = staticCompositionLocalOf<PaletteRegistry?> { null }

/** 当前页在组合里时，命令面板里有 [items] 给的命令。 */
@Composable
fun ContributePaletteItems(key: Any, items: () -> List<PaletteItem>) {
    val registry = LocalPaletteRegistry.current ?: return
    val current by rememberUpdatedState(items)
    DisposableEffect(registry, key) {
        registry.providers[key] = { current() }
        onDispose { registry.providers.remove(key) }
    }
}

/**
 * 命令面板，IDE 的做法：一个输入框，模糊搜索文件夹与命令，方向键挑、回车执行、Esc 关掉。
 * 没输入时列出 [items] 的前面一些（调用方把最近去过的文件夹与常用命令排在前面）。
 */
@Composable
fun CommandPalette(items: List<PaletteItem>, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableIntStateOf(0) }
    val results = remember(items, query) {
        if (query.isBlank()) {
            items.take(EMPTY_QUERY_LIMIT)
        } else {
            items.mapNotNull { item ->
                val byTitle = fuzzyScore(query, item.title)
                // 关键词与分组只认连着的一段：拆开找齐在一串英文别名里太容易碰上，「sp」会命中「details inspector」。
                // 命中的排在标题命中之后
                val needle = query.trim().lowercase()
                val byKeywords = KEYWORD_SCORE.takeIf {
                    needle.isNotEmpty() && (item.keywords.lowercase().contains(needle) || item.group.lowercase().contains(needle))
                }
                listOfNotNull(byTitle, byKeywords).maxOrNull()?.let { item to it }
            }.sortedByDescending { it.second }.map { it.first }.take(RESULT_LIMIT)
        }
    }
    LaunchedEffect(results) { selected = 0 }
    val listState = rememberLazyListState()
    // 照 IDE 的列表：选中项露在外面就不动，出界了只挪到刚好露出，也不做动画。原来每按一次都把它滚到顶上，
    // 按住方向键连发时整个列表跟着一格一格地跳
    LaunchedEffect(selected) { if (results.isNotEmpty()) listState.revealItem(selected) }
    val focus = remember { FocusRequester() }

    fun runAt(index: Int) {
        val item = results.getOrNull(index) ?: return
        // 面板留着：原因就写在这一行上
        if (item.disabledReason != null) return
        onDismiss()
        item.run()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // 在对话框自己的组合里要焦点：放在外面时对话框的内容还没挂上，输入框接不到键盘
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        // 贴着窗口上部，不居中：结果变多变少时输入框不跟着上下跳
        Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null, onClick = onDismiss)) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp, start = 16.dp, end = 16.dp)
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    // 吃掉面板里的点击，免得落到后面关掉它
                    .clickable(interactionSource = null, indication = null) {},
            ) {
                Column {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(Modifier.weight(1f).padding(start = 12.dp)) {
                            if (query.isEmpty()) {
                                Text(
                                    "跳到文件夹，或输入命令",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focus)
                                    .onPreviewKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        when (event.key) {
                                            Key.DirectionDown -> selected = (selected + 1).coerceAtMost(results.lastIndex.coerceAtLeast(0))
                                            Key.DirectionUp -> selected = (selected - 1).coerceAtLeast(0)
                                            Key.Enter, Key.NumPadEnter -> runAt(selected)
                                            Key.Escape -> onDismiss()
                                            else -> return@onPreviewKeyEvent false
                                        }
                                        true
                                    },
                            )
                        }
                        // 与搜索框同一条规矩：右端一直有个取消，有字时清空，没字时关掉面板。触屏上没有 Esc
                        TooltipIconButton(
                            icon = Icons.Outlined.Close,
                            label = if (query.isNotEmpty()) "清除" else "关闭",
                            onClick = { if (query.isNotEmpty()) query = "" else onDismiss() },
                            shortcut = if (query.isNotEmpty()) null else "Esc",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (results.isEmpty()) {
                        Text(
                            "没有匹配的文件夹或命令",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp),
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.heightIn(max = 420.dp).padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            itemsIndexed(results) { index, item ->
                                // 分组名只在一组的第一项上出现
                                if (index == 0 || results[index - 1].group != item.group) {
                                    Text(
                                        item.group,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 20.dp, top = if (index == 0) 4.dp else 12.dp, bottom = 4.dp),
                                    )
                                }
                                PaletteRow(item, selected = index == selected, onClick = { runAt(index) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteRow(item: PaletteItem, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val enabled = item.disabledReason == null
    // M3 的停用态：内容 38% 不透明
    val iconColor = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant
    val titleColor = if (selected) colors.onSecondaryContainer else colors.onSurface
    val detail = item.disabledReason ?: item.detail
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) colors.secondaryContainer else colors.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            item.icon,
            contentDescription = null,
            tint = if (enabled) iconColor else iconColor.copy(alpha = DisabledAlpha),
            modifier = Modifier.size(20.dp),
        )
        Text(
            item.title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) titleColor else titleColor.copy(alpha = DisabledAlpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.padding(start = 12.dp).widthIn(max = 240.dp),
            )
        }
    }
}

/** 让第 [index] 项整个露出来，挪得最少：在上沿之外就贴上沿，在下沿之外就贴下沿。 */
private suspend fun LazyListState.revealItem(index: Int) {
    val layout = layoutInfo
    val viewportHeight = layout.viewportEndOffset - layout.viewportStartOffset
    val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
    when {
        item == null -> {
            // 不在可见范围里时没有它的位置可比，先把它放到顶上；在下方的再往回挪到贴着下沿
            val below = index > (layout.visibleItemsInfo.lastOrNull()?.index ?: 0)
            scrollToItem(index)
            if (below) {
                val size = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: return
                scrollBy(-(viewportHeight - size).toFloat())
            }
        }
        item.offset < layout.viewportStartOffset -> scrollBy((item.offset - layout.viewportStartOffset).toFloat())
        item.offset + item.size > layout.viewportEndOffset -> scrollBy((item.offset + item.size - layout.viewportEndOffset).toFloat())
    }
}

private const val DisabledAlpha = 0.38f
private const val EMPTY_QUERY_LIMIT = 12
private const val RESULT_LIMIT = 50
// 比标题里连着出现（约 1000）低，比标题里拆开找齐高
private const val KEYWORD_SCORE = 500
