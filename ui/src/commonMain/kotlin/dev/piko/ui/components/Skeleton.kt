package dev.piko.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * 首屏骨架。M3 transitions 页的 stable layouts：加载时按内容的形状画占位，内容换上来时什么都不挪。
 * 占位与真实的行同尺寸同位置，否则换上来那一刻整列跳一下，比没有骨架还显眼。
 *
 * 只管首载。下拉刷新、续页仍用 loading indicator；形状事先说不准的整屏仍用 FullScreenLoading。
 */

/**
 * 一片骨架共用一个时钟。每块各起一个无限动画的话，块与块的明暗不同步，看起来是一片在闪。
 * 这里只提供透明度，由 [SkeletonGroup] 放进来。
 */
private val LocalSkeletonAlpha = compositionLocalOf<State<Float>> { mutableFloatStateOf(1f) }

/**
 * 一片骨架的外壳：脉动而不是扫光（M3 的原话是 a subtle pulsing animation）。
 * 整片对读屏只念一次「正在加载」，里面的色块不各自成为节点。
 */
@Composable
fun SkeletonGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha = transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(tween(SKELETON_PULSE_MS), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    Box(modifier = modifier.clearAndSetSemantics { }) {
        CompositionLocalProvider(LocalSkeletonAlpha provides alpha, content = content)
    }
}

/** 一块占位色块。颜色取容器色阶里比页面高一档的那一格，深浅两套主题都看得出轮廓又不刺眼。 */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, shape: Shape = MaterialTheme.shapes.extraSmall) {
    val alpha by LocalSkeletonAlpha.current
    Box(
        modifier = modifier
            .graphicsLayer { this.alpha = alpha }
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, shape),
    )
}

/**
 * 一行文字的占位。高度取字形高度而不是行高，上下留出的空白正好落在真实文字的行距里，
 * 于是整行的高度与真实行一致。
 */
@Composable
fun SkeletonTextLine(widthFraction: Float, modifier: Modifier = Modifier, height: Dp = 12.dp) {
    SkeletonBlock(modifier.fillMaxWidth(widthFraction).height(height))
}

/**
 * [FileListItem] 那一行的骨架：56dp 前导图像、一行标题、一行元信息，外边距与最小行高照抄那一行。
 * 网盘列表、传输、星标、播放历史、回收站的行都是它的变体，共用这一份。
 */
@Composable
fun FileRowSkeleton(modifier: Modifier = Modifier, titleFraction: Float = 0.6f) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .heightIn(min = 72.dp)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkeletonBlock(Modifier.size(ListLeadingSize), MaterialTheme.shapes.small)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SkeletonTextLine(titleFraction, height = 14.dp)
            SkeletonTextLine(0.35f)
        }
    }
}

/**
 * 一屏 [FileRowSkeleton]。行数给到够铺满一屏即可，多出来的在屏幕外，不影响观感；
 * 标题宽度逐行错开，一排等长的色块读起来像表格而不像列表。
 */
@Composable
fun FileListSkeleton(modifier: Modifier = Modifier, rows: Int = 10) {
    SkeletonGroup(modifier) {
        Column {
            repeat(rows) { index ->
                FileRowSkeleton(titleFraction = SkeletonTitleWidths[index % SkeletonTitleWidths.size])
            }
        }
    }
}

private val SkeletonTitleWidths = listOf(0.62f, 0.45f, 0.74f, 0.52f, 0.68f, 0.4f)

private const val SKELETON_PULSE_MS = 900
