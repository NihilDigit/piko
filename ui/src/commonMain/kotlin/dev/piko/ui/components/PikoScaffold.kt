package dev.piko.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.ui.LocalPikoServices
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    content: @Composable (PaddingValues) -> Unit,
) {
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
        val card: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize().clip(FrameCardShape).background(colors.surface)) {
                content(PaddingValues())
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding(), bottom = bottom)
                .consumeWindowInsets(innerPadding),
        ) {
            HostedSidePanel(card)
        }
    }
}

/** 卡片右边停进来的面板（见 SidePanelHost），与网盘页的详情栏同一种侧栏、同一个宽度。没有面板时只有卡片。 */
@Composable
private fun HostedSidePanel(main: @Composable () -> Unit) {
    val hosted = LocalSidePanelHost.current?.top
    val preferences = LocalPikoServices.current.preferences
    val prefs by preferences.inspectorPanelFlow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    SidePanelLayout(
        open = hosted != null,
        savedWidthDp = prefs?.widthDp,
        title = hosted?.title.orEmpty(),
        closeDescription = "收起侧栏",
        onClose = { hosted?.close() },
        onWidthChange = { scope.launch { preferences.setInspectorPanelWidth(it) } },
        defaultWidth = HostedPanelDefaultWidth,
        minWidth = HostedPanelMinWidth,
        main = main,
        panel = { hosted?.let { HostedPanelContent(it) } },
    )
}
