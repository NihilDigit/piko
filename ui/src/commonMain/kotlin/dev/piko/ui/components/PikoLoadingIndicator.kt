package dev.piko.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piko.shared.media.player.observeLoadingSpeed
import dev.piko.ui.adaptive.isDesktopLayout
import kotlinx.coroutines.delay

/*
 * 进度不可知的等待只有这几档，别处不直接调 material3 的指示器，也不用 CircularProgressIndicator：
 * - 列表首屏：骨架屏（Skeleton.kt），形状照内容画
 * - 形状说不准的整屏首载：FullScreenLoading
 * - 行内（按钮里、一行字旁边、对话框里）：InlineLoadingIndicator
 * - 压在画面或图片上：MediaLoadingIndicator
 * - 下拉刷新：RefreshBox
 * 报得出百分比的等待用 LinearProgressIndicator。M3 按进度可不可知分这两类，不许从前者过渡到后者。
 */

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PikoLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        LoadingIndicator(
            modifier = Modifier.size(size),
            color = color,
        )
    }
}

/**
 * 行内的等待：按钮里替掉图标或文字、一行说明旁边、对话框的内容位。
 *
 * 只有 24dp 一档。它是 loading indicator 规格的下限，再小那个形变的形状只剩几个像素，看不出在动；
 * 放进按钮时比它替掉的 18dp 图标大一点，按钮高度由最小触摸尺寸撑着，不会跟着变。
 */
@Composable
fun InlineLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    PikoLoadingIndicator(modifier = modifier, size = InlineLoadingSize, color = color)
}

val InlineLoadingSize = 24.dp

/**
 * 等待是否已久到该画指示器。M3 给 loading indicator 定的范围从 200ms 起：它一出现先跑一段欠阻尼的
 * 弹簧形变，只活两三帧的话看着是猛地抽一下。状态常以「加载中」开局而数据已在内存里，第二帧就换成
 * 内容，所以补的是晚一点出现，不是出现了多留一会，后者会把快的加载也拖慢。
 */
@Composable
fun rememberLoadingVisible(): Boolean {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LOADING_APPEAR_DELAY_MS)
        visible = true
    }
    return visible
}

internal const val LOADING_APPEAR_DELAY_MS = 200L

private val MediaLoadingSubtitleShadow = Shadow(color = Color.Black.copy(alpha = 0.6f), offset = Offset(0f, 1f), blurRadius = 6f)

/**
 * 播放器与信息流统一的缓冲网速读数状态钩子，底层复用 [observeLoadingSpeed]。
 */
@Composable
fun rememberMediaLoadingSpeedText(
    active: Boolean,
    token: Any? = Unit,
    sampleBytes: () -> Long?,
    isReactivating: () -> Boolean = { false },
): String? {
    val latestToken by rememberUpdatedState(token)
    val latestSampleBytes by rememberUpdatedState(sampleBytes)
    val latestIsReactivating by rememberUpdatedState(isReactivating)
    var speedText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(active) {
        if (!active) {
            speedText = null
            return@LaunchedEffect
        }
        observeLoadingSpeed(
            token = { latestToken },
            sampleBytes = { latestSampleBytes() },
            isReactivating = { latestIsReactivating() },
            onUpdate = { speedText = it },
        )
    }
    return speedText
}

/**
 * 悬挂在圆形加载指示器正下方的零尺寸说明与实时网速读数层（圆心保持与父容器严格居中对齐，文字显隐不推动指示器跳动）。
 * 播放器中央变形加载按钮与信息流 [MediaLoadingIndicator] 共用此布局与排版样式。
 */
@Composable
fun MediaLoadingCaption(
    anchorHalfHeight: Dp,
    title: String? = null,
    subtitle: String? = null,
    gap: Dp = 8.dp,
) {
    val hasTitle = !title.isNullOrBlank()
    val hasSubtitle = !subtitle.isNullOrBlank()
    if (!hasTitle && !hasSubtitle) return
    val density = LocalDensity.current
    val gapPx = with(density) { gap.roundToPx() }
    val anchorHalfPx = with(density) { anchorHalfHeight.roundToPx() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.layout { measurable, _ ->
            val placeable = measurable.measure(Constraints())
            layout(0, 0) {
                placeable.place(-placeable.width / 2, anchorHalfPx + gapPx)
            }
        },
    ) {
        if (hasTitle) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium.copy(shadow = MediaLoadingSubtitleShadow),
                maxLines = 1,
                softWrap = false,
            )
        }
        if (hasSubtitle) {
            Text(
                text = subtitle,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFeatureSettings = "tnum",
                    shadow = MediaLoadingSubtitleShadow,
                ),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * 压在画面或图片上的等待。底下是什么颜色说不准，裸的指示器会被画面吞掉，所以用带容器的那一档。
 * 拖动视频时每次 seek 都有一小段缓冲，延后出现免得画面中央一直在闪（外部已自行去抖时可传 [appearImmediate] = true）。
 * [title] 与 [subtitle] 不为空时在圆容器正下方悬挂单行说明及等宽网速读数，圆心仍保持与父布局严格对齐。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MediaLoadingIndicator(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    appearImmediate: Boolean = false,
) {
    if (!appearImmediate && !rememberLoadingVisible()) return
    if (title.isNullOrBlank() && subtitle.isNullOrBlank()) {
        ContainedLoadingIndicator(modifier = modifier)
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            ContainedLoadingIndicator()
            MediaLoadingCaption(
                anchorHalfHeight = 24.dp,
                title = title,
                subtitle = subtitle,
            )
        }
    }
}

/** 形状说不准的整屏首载。占位的 Box 无条件铺满，指示器出现前这一屏也不让下面的东西顶上来。 */
@Composable
fun FullScreenLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        if (rememberLoadingVisible()) PikoLoadingIndicator()
    }
}

/**
 * 下拉刷新。不直接用 PullToRefreshBox：它默认的指示器仍是 M3 Expressive 之前的箭头圈，
 * 而 M3E 把 loading indicator 定为下拉刷新的组件。
 *
 * 指示器与刷新框共用同一个 state，分成两个的话指示器收不到拖拽距离，下拉时不跟手。
 * 用带容器的那一档：指示器压在列表内容上，没有容器托底时会撞上正文。
 *
 * 只有手指能拉，见 [PointerSource]。鼠标没有「拉住再松手」的动作，滚轮滚到顶再多滚一格就会被
 * 当成一次下拉。最近一次输入是鼠标时整个关掉刷新手势，而不是吃掉下拉量：关掉之后到顶多出来的
 * 那一截照常往外传。刷新入口此时在顶栏，见 [showsRefreshButton]。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        enabled = LocalPointerSource.current.isTouchLike,
        indicator = {
            PullToRefreshDefaults.LoadingIndicator(
                state = state,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
        content = content,
    )
}

/**
 * 带 [RefreshBox] 的页面是否在顶栏另给刷新按钮。最近一次输入是鼠标时下拉已关掉，必须给；
 * 桌面上手指也能下拉，仍然给，因为桌面多半接着鼠标，只是还没动过。
 */
@Composable
fun showsRefreshButton(): Boolean =
    !LocalPointerSource.current.isTouchLike || isDesktopLayout()
