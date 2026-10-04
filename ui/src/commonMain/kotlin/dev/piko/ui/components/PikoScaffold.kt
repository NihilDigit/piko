package dev.piko.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.piko.ui.platform.rememberCaptionSlot
import dev.piko.ui.platform.windowDragArea
import dev.piko.ui.theme.IslandCorner
import dev.piko.ui.theme.IslandGap
import dev.piko.ui.theme.IslandInnerCorner
import dev.piko.ui.theme.islandHeader
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.piko.ui.theme.FrameCardBottomMargin
import dev.piko.ui.theme.FrameCardShape
import dev.piko.ui.theme.LocalFramed
import dev.piko.ui.theme.frame

/**
 * 各页的骨架。有外框时（大窗口，见 LocalFramed）页头落在外框色上，内容是下面那张上沿圆角的卡片，与网盘页的
 * 页眉与列表一样：外框不动，换页时只有卡片里的内容在变。没有外框时就是普通的 Scaffold。
 *
 * 框架在这里做一次，不在各页各画一遍：页面只管顶栏、底栏与卡片里各放什么。有外框时交给 [content] 的内边距
 * 是零，卡片已经夹在顶栏与底栏之间；各页照旧按它留白即可，两种形态不必分开写。
 */
@Composable
fun PikoScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    /** 有外框时内容卡片的形状。顶上接着标签的页（传输）在第一个标签活动时左上角不圆，见 IslandTab。 */
    cardShape: Shape = FrameCardShape,
    /** 有外框时换成岛的样子，[topBar] 与 [bottomBar] 只在没有外框时用，见 [IslandPage]。 */
    island: IslandPage? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    if (LocalFramed.current && island != null) {
        IslandScaffold(modifier, island, snackbarHost, floatingActionButton, content)
        return
    }
    if (!LocalFramed.current) {
        Scaffold(
            modifier = modifier,
            topBar = topBar,
            bottomBar = bottomBar,
            snackbarHost = snackbarHost,
            floatingActionButton = floatingActionButton,
            content = content,
        )
        return
    }
    val colors = MaterialTheme.colorScheme
    Scaffold(
        modifier = modifier,
        containerColor = colors.frame,
        topBar = topBar,
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
    ) { innerPadding ->
        // 卡片上下都让开：顶栏与底栏都落在外框色上，卡片夹在中间。没有底栏的页，卡片离窗口底边留一截外框色，
        // 不贴着窗口边缘截断；有底栏的不再多留，否则底栏被垫高，与侧边栏底部的账号行对不齐
        val bottom = innerPadding.calculateBottomPadding().takeIf { it > 0.dp } ?: FrameCardBottomMargin
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding(), bottom = bottom)
                .consumeWindowInsets(innerPadding)
                .clip(cardShape)
                .background(colors.surface),
        ) {
            content(PaddingValues())
        }
    }
}

/**
 * 有外框时一页的岛：顶上那一行，下面一块岛，上段是页眉（[header]），下段是内容。网盘页与传输页都是这个样子，各页照它做，
 * 换页时顶上那一行的高度与岛的上沿不跳。
 *
 * 要分类切换的页在顶上那一行放标签（[tabs]，各放 [IslandTab]），类别名在标签上，页眉只放操作。没有分类的页 [tabs] 为 null：
 * 岛直接画到顶，上沿与侧边栏岛对齐，页名写在页眉的开头（[IslandTitle]），窗口按钮画在页眉末尾，标题与操作之间的空白
 * （[IslandHeaderSpace]）拖得动窗口。试过给这种页放一个写着页名的标签，它没有别的标签可切，只是在冒充标题；
 * 也试过顶上空出一行只放窗口按钮，白白占掉一行。
 */
class IslandPage(
    val header: @Composable RowScope.() -> Unit,
    val tabs: (@Composable RowScope.() -> Unit)? = null,
    /** 第一个标签是活动的：它的左边竖直接下来，岛的左上角不圆。 */
    val firstTabActive: Boolean = false,
)

/** 没有标签的页写在页眉开头的页名。 */
@Composable
fun IslandTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, modifier = modifier)
}

@Composable
private fun IslandScaffold(
    modifier: Modifier,
    island: IslandPage,
    snackbarHost: @Composable () -> Unit,
    floatingActionButton: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val tabs = island.tabs
    // 没有标签时岛直接画到顶，上沿与侧边栏岛对齐，窗口按钮画在页眉那一行的末尾；顶上不再空出一行只放按钮
    val caption = rememberCaptionSlot(topInset = IslandGap)
    Scaffold(
        modifier = modifier,
        containerColor = colors.frame,
        topBar = { if (tabs != null) IslandTabRow(tabs) },
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding() + if (tabs == null) IslandGap else 0.dp,
                    bottom = FrameCardBottomMargin,
                )
                .consumeWindowInsets(innerPadding)
                .clip(
                    RoundedCornerShape(
                        topStart = islandTopStart(island.tabs != null && island.firstTabActive),
                        topEnd = IslandCorner,
                        bottomStart = IslandCorner,
                        bottomEnd = IslandCorner,
                    ),
                )
                .background(colors.islandHeader),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (tabs == null) caption.modifier else Modifier)
                    .heightIn(min = 56.dp)
                    .padding(start = 20.dp, end = if (tabs == null && caption.buttons != null) 8.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                island.header(this)
                if (tabs == null) caption.buttons?.invoke()
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // 上沿是小圆角、露出一点页眉的底色，下沿就是岛的下沿，与网盘页相同
                    .clip(RoundedCornerShape(topStart = IslandInnerCorner, topEnd = IslandInnerCorner, bottomStart = IslandCorner, bottomEnd = IslandCorner))
                    .background(colors.surface),
            ) {
                content(PaddingValues())
            }
        }
    }
}

/** 页眉里标题与操作之间的空白，也是拖动窗口的地方（没有标签的页，页眉就是窗口顶上那一行）。 */
@Composable
fun RowScope.IslandHeaderSpace() {
    Spacer(Modifier.weight(1f).height(40.dp).windowDragArea())
}

/** 有标签时顶上那一行：标签贴着下沿，后面的空白是拖动区，窗口按钮在末尾。 */
@Composable
private fun IslandTabRow(tabs: @Composable RowScope.() -> Unit) {
    val caption = rememberCaptionSlot()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(caption.modifier)
            .windowInsetsPadding(TopAppBarDefaults.windowInsets)
            .height(IslandTabBarHeight)
            .padding(end = if (caption.buttons != null) 8.dp else 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Row(Modifier.selectableGroup(), verticalAlignment = Alignment.Bottom, content = tabs)
        Spacer(Modifier.weight(1f).fillMaxHeight().windowDragArea())
        Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) { caption.buttons?.invoke() }
    }
}
