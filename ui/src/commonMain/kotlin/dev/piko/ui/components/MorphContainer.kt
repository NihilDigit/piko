package dev.piko.ui.components

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 形变容器：一块圆角容器在几种状态间变换宽度、圆角与颜色，里面的内容随状态换掉，即 Material 的 container transform。
 *
 * 显隐、变形与换内容在这里一处编排，排成先后：
 * - 从无到有：没有可以变形的起点，直接摆成目标的形状与颜色，整体淡入。
 * - 显示中换一种内容（[contentKey] 变了）：旧内容先淡出，空着的容器变形到新尺寸，接近到位才淡入新内容。
 * - 同一种内容（[contentKey] 不变）：内容就地更新，形状跟着走。例如读数的数值变了、播放键在播放与暂停间切换。
 * - 退场：整体淡出，期间不换内容、不变形，保持退场那一刻的样子。
 *
 * 不拿 AnimatedVisibility、AnimatedContent 与各自的尺寸动画分头做：三者互不知道对方走到哪一步，内容换好了容器还没张开、
 * 容器开始收了内容还没撤，都会露出宽内容被窄容器裁掉的中间一截，补了一处另一处又冒出来。
 * 也不用 sharedBounds 的 container transform：它把内容缩放进变形中的边界，不裁剪，但退场时照样一边淡出一边变形，
 * 圆角要另做，还要为一个按钮套上实验性的 SharedTransitionLayout。
 *
 * 外框按 [anchorSize] 占位，容器变宽时用 requiredSize 向两侧溢出，旁边的布局不随之挪动。
 * 完全淡出后容器离开组合，不再接点击。
 */
@Composable
fun <S> MorphContainer(
    targetState: S,
    visible: Boolean,
    contentKey: (S) -> Any?,
    width: (S) -> Dp,
    corner: (S) -> Dp,
    containerColor: (S) -> Color,
    contentColor: (S) -> Color,
    anchorSize: DpSize,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerModifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable (S) -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    // 正在画的那一种。换一种内容时，旧的淡出之后才换成目标；同一种里数值随时在变，画的是眼前那一份
    var shown by remember { mutableStateOf(targetState) }
    val live = if (contentKey(targetState) == contentKey(shown)) targetState else shown

    val widthGoal = width(live)
    val cornerGoal = corner(live)
    val colorGoal = containerColor(live)
    val presence = remember { Animatable(if (visible) 1f else 0f) }
    val contentAlpha = remember { Animatable(1f) }
    val animatedWidth = remember { Animatable(widthGoal, Dp.VectorConverter) }
    val animatedCorner = remember { Animatable(cornerGoal, Dp.VectorConverter) }
    val animatedColor = remember { Animatable(colorGoal) }

    // 形状跟着正在画的那一种走。看不见时没有可看的变形，直接到位
    LaunchedEffect(widthGoal) {
        if (presence.value > 0f) animatedWidth.animateTo(widthGoal, morphSpring(MORPH_DEFAULT_STIFFNESS)) else animatedWidth.snapTo(widthGoal)
    }
    LaunchedEffect(cornerGoal) {
        if (presence.value > 0f) animatedCorner.animateTo(cornerGoal, morphSpring(MORPH_FAST_STIFFNESS)) else animatedCorner.snapTo(cornerGoal)
    }
    LaunchedEffect(colorGoal) {
        if (presence.value > 0f) animatedColor.animateTo(colorGoal, motion.defaultEffectsSpec()) else animatedColor.snapTo(colorGoal)
    }

    val latestTarget by rememberUpdatedState(targetState)
    val latestWidth by rememberUpdatedState(width)
    val latestCorner by rememberUpdatedState(corner)
    val latestColor by rememberUpdatedState(containerColor)
    LaunchedEffect(visible, contentKey(targetState)) {
        if (!visible) {
            presence.animateTo(0f, motion.fastEffectsSpec())
            return@LaunchedEffect
        }
        val next = latestTarget
        if (presence.value == 0f) {
            shown = next
            animatedWidth.snapTo(latestWidth(next))
            animatedCorner.snapTo(latestCorner(next))
            animatedColor.snapTo(latestColor(next))
            contentAlpha.snapTo(1f)
            presence.animateTo(1f, motion.defaultEffectsSpec())
            return@LaunchedEffect
        }
        // 退场到一半又要显示：从眼前的样子接着来
        launch { presence.animateTo(1f, motion.defaultEffectsSpec()) }
        if (contentKey(next) != contentKey(shown)) {
            contentAlpha.animateTo(0f, motion.fastEffectsSpec())
            shown = next
            // 容器接近到位才显出新内容，否则宽内容在窄容器里先露出被裁掉的一截。弹簧的尾巴很长，不等它停稳
            val goal = latestWidth(next)
            withTimeoutOrNull(SETTLE_TIMEOUT_MILLIS) {
                snapshotFlow { abs((animatedWidth.value - goal).value) <= SETTLE_SLACK_DP }.first { it }
            }
        }
        // 被打断过的一轮可能停在淡出的半路，无论哪条路都收在完全显出
        contentAlpha.animateTo(1f, motion.defaultEffectsSpec())
    }

    val drawn by remember { derivedStateOf { presence.value > 0f } }
    Box(modifier.size(anchorSize), contentAlignment = Alignment.Center) {
        if (drawn || visible) {
            Surface(
                onClick = onClick,
                // 弹簧会冲过头；负的圆角让 CornerBasedShape 当场抛异常，见 ConnectedShapes.kt
                shape = RoundedCornerShape(animatedCorner.value.coerceAtLeast(0.dp)),
                color = animatedColor.value,
                contentColor = contentColor(live),
                interactionSource = interactionSource,
                modifier = Modifier
                    .requiredSize(animatedWidth.value.coerceAtLeast(0.dp), anchorSize.height)
                    .graphicsLayer {
                        alpha = presence.value
                        val scale = lerp(ENTER_SCALE, 1f, presence.value)
                        scaleX = scale
                        scaleY = scale
                    }
                    .then(containerModifier),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = contentAlpha.value
                            val scale = lerp(CONTENT_ENTER_SCALE, 1f, contentAlpha.value)
                            scaleX = scale
                            scaleY = scale
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    content(live)
                }
            }
        }
    }
}

// 变形的弹簧。刚度沿用 expressive 的快、默认两档，阻尼取 standard 的 0.9：expressive 的 0.6 到 0.8
// 在频繁出现的读数上每变一次都来回晃
private fun <T> morphSpring(stiffness: Float) = spring<T>(dampingRatio = 0.9f, stiffness = stiffness)
private const val MORPH_FAST_STIFFNESS = 800f
private const val MORPH_DEFAULT_STIFFNESS = 380f

// 整体进出时从略小一点放大，内容换入时从更小放大，两者区分得开
private const val ENTER_SCALE = 0.9f
private const val CONTENT_ENTER_SCALE = 0.6f

private const val SETTLE_SLACK_DP = 8f
private const val SETTLE_TIMEOUT_MILLIS = 400L
