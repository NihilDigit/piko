package dev.piko.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.unit.dp

/**
 * 外框右侧那一栏的宿主。有外框时（大窗口）[PikoSheet] 不再弹带遮罩的模态面板，而是停进这里，由各页的侧栏画出来：
 * 网盘页是详情栏与信息流所在的那一栏，其余页由 [PikoScaffold] 在卡片右边给出同样的一栏。
 *
 * 为什么停进侧栏：侧栏的宽度与手机差不多，面板的内容本是照手机写的，原样放得进去；它不挡列表、没有遮罩，
 * 开着「添加链接」照样能在左边翻文件夹。模态面板在宽窗口里另起一块浮层，与外框里的侧栏长得不一样，
 * 同一个位置先后出现两种东西。
 *
 * 同时只画最后停进来的一个；它关掉后，底下的那个（或原来的详情栏、信息流）回来。
 * 各面板与详情栏共用一个宽度（偏好 inspectorPanelFlow），拖宽一次处处生效。
 */
class SidePanelHost {
    internal val entries = mutableStateListOf<HostedPanel>()

    /** 眼下该画的那一个，没有时为 null。 */
    val top: HostedPanel? get() = entries.lastOrNull()

    /** 全部关掉，信息流要回到这一栏时用。 */
    fun closeAll() = entries.toList().forEach { it.close() }
}

/** 停在侧栏里的一个面板。内容、标题与关闭随调用方每次重组更新。 */
class HostedPanel internal constructor() {
    var title by mutableStateOf<String?>(null)
        internal set
    internal var content by mutableStateOf<@Composable PikoSheetScope.() -> Unit>({})
    internal var dismiss by mutableStateOf({})

    /** 收起它，与点侧栏顶上的收起按钮相同。 */
    fun close() = dismiss()
}

/** 有外框时的侧栏宿主，由主界面提供；没有外框时为 null，[PikoSheet] 照旧弹面板。 */
val LocalSidePanelHost = staticCompositionLocalOf<SidePanelHost?> { null }

/** [PikoSheet] 停进侧栏：登记进宿主，离开组合时撤下。Esc 与返回键关掉最上面的那个。 */
@Composable
internal fun HostedSheet(
    host: SidePanelHost,
    title: String?,
    onDismissRequest: () -> Unit,
    content: @Composable PikoSheetScope.() -> Unit,
) {
    val entry = remember(host) { HostedPanel() }
    SideEffect {
        entry.title = title
        entry.content = content
        entry.dismiss = onDismissRequest
    }
    DisposableEffect(host, entry) {
        host.entries.add(entry)
        onDispose { host.entries.remove(entry) }
    }
    BackHandler(enabled = host.top === entry) { onDismissRequest() }
}

/** 画出宿主眼下的那个面板，放在侧栏卡片里。 */
@Composable
fun HostedPanelContent(panel: HostedPanel) {
    // 换了一个面板就是换了一块内容，里面记住的状态不该串过去
    key(panel) {
        Column(Modifier.fillMaxHeight().wheelStaysInSheet()) {
            val scope = remember(this, panel) {
                // 侧栏没有收起的动画要等，关掉后当场做接下来的事
                SheetScopeImpl(this, isSideSheet = true) { action ->
                    panel.close()
                    action()
                }
            }
            scope.(panel.content)()
        }
    }
}

/** 侧栏的默认宽度与下限，详情栏与停进来的面板共用。 */
val HostedPanelDefaultWidth = 320.dp
val HostedPanelMinWidth = 280.dp
