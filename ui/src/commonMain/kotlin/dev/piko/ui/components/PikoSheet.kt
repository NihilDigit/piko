package dev.piko.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.piko.ui.adaptive.WidthClass
import dev.piko.ui.adaptive.currentWidthClass
import dev.piko.ui.platform.LocalPikoPlatform
import kotlinx.coroutines.launch

/** [PikoSheet] 内容所在的作用域。 */
interface PikoSheetScope : ColumnScope {
    /** 此刻是宽窗口的侧边面板，高度是整个窗口；为 false 时是底部 sheet，高度随内容。 */
    val isSideSheet: Boolean

    /**
     * 先播完收起的动画再执行 [action]。直接移出组合会让面板瞬间消失，
     * 接着弹出的对话框也少了一个视觉上的因果。[action] 之前已经通知过 onDismissRequest。
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
    if (currentWidthClass() == WidthClass.Expanded) {
        ModalSideSheet(onDismissRequest, sideSheetTitle, content)
        return
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        contentWindowInsets = bottomSheetInsets,
    ) {
        Column(Modifier.wheelStaysInSheet()) {
            val sheetScope = remember(this) {
                SheetScopeImpl(this, isSideSheet = false) { action ->
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        onDismissRequest()
                        action()
                    }
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
    // 划出去之后要做的事：人关掉时是空的，经 hideThen 关掉时是那个动作
    var afterHide by remember { mutableStateOf<(() -> Unit)?>(null) }
    val dismiss = { visibility.targetState = false }
    // 划出去的动画走完才真正关：此刻才通知调用方把它移出组合
    LaunchedEffect(visibility.isIdle, visibility.currentState) {
        if (visibility.isIdle && !visibility.currentState && !visibility.targetState) {
            latestDismiss()
            afterHide?.invoke()
        }
    }
    LocalPikoPlatform.current.FullscreenDialog(onDismiss = dismiss, immersive = false, systemBarsVisible = true) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 点在面板外面就是关掉。不画按下的波纹：这一整片是遮罩，不是一个按钮
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = null, indication = null, onClick = dismiss),
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
                                afterHide = action
                                dismiss()
                            }
                        }
                        sheetScope.content()
                    }
                }
            }
        }
    }
}

private class SheetScopeImpl(
    column: ColumnScope,
    override val isSideSheet: Boolean,
    private val hide: (() -> Unit) -> Unit,
) : PikoSheetScope, ColumnScope by column {
    override fun hideThen(action: () -> Unit) = hide(action)
}

/** modal side sheet 的宽度，取 side-sheets.md measurements 表的最大值。expanded 窗口至少 840dp，放得下。 */
private val ModalSideSheetWidth = 400.dp
