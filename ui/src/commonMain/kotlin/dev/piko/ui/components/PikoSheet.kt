package dev.piko.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.piko.ui.adaptive.WidthClass
import dev.piko.ui.adaptive.currentWidthClass
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.theme.LocalFramed

/** [PikoSheet] 内容所在的作用域。 */
interface PikoSheetScope : ColumnScope {
    /** 此刻是宽窗口的侧边面板，高度是整个窗口；为 false 时是底部 sheet，高度随内容。 */
    val isSideSheet: Boolean

    /**
     * 关掉面板并当场执行 [action]：先通知 onDismissRequest，再执行 [action]，面板随调用方移出组合，不播收起动画。
     *
     * 原来等收起动画走完才执行，选了一项要白等三四百毫秒。也试过先执行、动画走完再通知关闭，否决了：
     * [action] 若又改了调用方用来开关面板的那个状态，稍后的关闭通知会把它清掉；[action] 若让调用方离开组合
     * （跳去别的页），关闭通知就再也发不出来，状态留在「开着」，回来时面板又冒出来。
     */
    fun hideThen(action: () -> Unit)
}

/**
 * 模态面板。宽窗口（expanded）里是从末端边缘滑入的侧边面板，其余是只有展开一档的底部 sheet。
 *
 * bottom-sheets.md 的 Adaptive design 一节："On larger expanded breakpoints, like desktop, a bottom
 * sheet can be swapped for a side sheet that shows similar content." 底部 sheet 在宽窗口里只能在正中
 * 升起一块最宽 640dp 的板子，离打开它的那一行隔着半个窗口；侧边面板贴着窗口一侧，不挡列表中部。
 *
 * 侧边面板取 side-sheets.md 的 modal 款：surfaceContainerLow，圆角 16dp，最宽 400dp，四周离窗口边
 * 16dp，关闭按钮常驻。遮罩用平台对话框自带的那一层；点遮罩、关闭按钮、Esc 与返回都先划出去再通知。
 *
 * 底部 sheet 一律跳过半开：用鼠标时 sheet 把滚轮当成拖它自己，半开时往下滚先被 sheet 拿去往上挪，
 * 滚轮又没有松手那一下，sheet 不会吸附到哪一档。内容列表滚到头剩下的滚轮位移也不再交给 sheet
 * （[wheelStaysInSheet]）。
 *
 * @param bottomSheetInsets 底部 sheet 形态替内容让的系统栏。内容自己铺到手势横条下面时传空。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PikoSheet(
    onDismissRequest: () -> Unit,
    bottomSheetInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    /** 侧边面板顶上与关闭按钮同一行的标题（side-sheets.md 的 headline）。底部 sheet 形态不画，内容自带标题。 */
    sideSheetTitle: String? = null,
    content: @Composable PikoSheetScope.() -> Unit,
) {
    // 有外框时停进右侧那一栏，见 SidePanelHost
    val host = LocalSidePanelHost.current
    if (host != null && LocalFramed.current) {
        HostedSheet(host, sideSheetTitle, onDismissRequest, content)
        return
    }
    if (currentWidthClass() == WidthClass.Expanded) {
        ModalSideSheet(onDismissRequest, sideSheetTitle, content)
        return
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        contentWindowInsets = bottomSheetInsets,
    ) {
        Column(Modifier.wheelStaysInSheet()) {
            val sheetScope = remember(this) {
                SheetScopeImpl(this, isSideSheet = false) { action ->
                    latestDismiss()
                    action()
                }
            }
            sheetScope.content()
        }
    }
}

@Composable
private fun ModalSideSheet(onDismissRequest: () -> Unit, title: String?, content: @Composable PikoSheetScope.() -> Unit) {
    val visibility = remember { MutableTransitionState(false).apply { targetState = true } }
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    val dismiss = { visibility.targetState = false }
    // 人关掉时划出去的动画走完才真正关：此刻才通知调用方把它移出组合。经 hideThen 关掉的不走这里
    LaunchedEffect(visibility.isIdle, visibility.currentState) {
        if (visibility.isIdle && !visibility.currentState && !visibility.targetState) latestDismiss()
    }
    LocalPikoPlatform.current.FullscreenDialog(onDismiss = dismiss, immersive = false, systemBarsVisible = true) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 点在面板外面就是关掉。不画按下的波纹：这一整片是遮罩，不是一个按钮。
            // 也不进无障碍树：否则读屏先停在一个没有名字的可点控件上，才轮到面板里的内容（M3 side sheets 的
            // Initial focus 要求焦点进到面板里）。读屏用户关面板走返回或面板顶上的关闭按钮
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(dismiss) { detectTapGestures { dismiss() } },
            )
            AnimatedVisibility(
                visibleState = visibility,
                enter = slideInHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec()) { it } + fadeIn(),
                exit = slideOutHorizontally(MaterialTheme.motionScheme.fastSpatialSpec()) { it } + fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(16.dp)
                        .width(ModalSideSheetWidth)
                        .fillMaxHeight(),
                ) {
                    Column(modifier = Modifier.fillMaxHeight().wheelStaysInSheet()) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (title != null) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.weight(1f).padding(start = 24.dp),
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            TooltipIconButton(Icons.Outlined.Close, "关闭", dismiss, shortcut = "Esc")
                        }
                        val sheetScope = remember(this) {
                            SheetScopeImpl(this, isSideSheet = true) { action ->
                                latestDismiss()
                                action()
                            }
                        }
                        sheetScope.content()
                    }
                }
            }
        }
    }
}

internal class SheetScopeImpl(
    column: ColumnScope,
    override val isSideSheet: Boolean,
    private val hide: (() -> Unit) -> Unit,
) : PikoSheetScope, ColumnScope by column {
    override fun hideThen(action: () -> Unit) = hide(action)
}

/** modal side sheet 的宽度，取 side-sheets.md measurements 表的最大值。expanded 窗口至少 840dp，放得下。 */
private val ModalSideSheetWidth = 400.dp
