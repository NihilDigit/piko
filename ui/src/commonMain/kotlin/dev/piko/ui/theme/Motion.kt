package dev.piko.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 这个平台用哪一套动效，由 PikoPlatform 给出。按平台分，不按输入方式分：按输入方式分时，动画手感会随着
 * 一会儿用触屏、一会儿用鼠标来回变。
 */
enum class MotionStyle {
    /** Android：M3 默认推荐的 expressive，组件形变带回弹。 */
    Expressive,

    /**
     * 桌面：M3 的 standard scheme（原文 "should be used for utilitarian products"），几乎不回弹，同样距离
     * 约 160–220ms 到位，expressive 要 340–400ms。转场时长取 WinUI 的 83、167、250ms 三档。
     */
    Standard,
}

/**
 * 页面转场与局部动效的一组取值，随主题注入（[LocalPikoMotion]），按平台与「减少动画」算出。
 * 组件的物理形变走 MaterialTheme.motionScheme；这里只放 motionScheme 管不到的：页面转场仍用 M3 旧的
 * 缓动加时长体系（transitions 一页原文 "M3 transitions use the legacy easing and duration system"）。
 *
 * 离场一律比进场短，任何平台都不超过 200ms；新旧两页不叠在一起半透明（"Fully fade out content before fading
 * new content in"），旧页淡完新页才开始淡入，横滑两边同时进行。
 */
@Immutable
class PikoMotion internal constructor(
    val style: MotionStyle,
    /**
     * 「减少动画」生效：系统设置或应用内开关任一开着。此时动画时长整体缩放为 0（见 PikoMotionScale），
     * 转场与组件动画都直接跳到终点；这个标志留给缩放管不到的：一直循环的装饰动画（骨架屏、定位脉冲）
     * 在缩放为 0 时停在某一帧，要改画静态的样子。
     */
    val reduced: Boolean,
    private val topLevelExitMillis: Int,
    private val topLevelEnterMillis: Int,
    private val forwardExitMillis: Int,
    private val forwardEnterMillis: Int,
    /** 压栈时旧页淡出所用的时长，比横滑短：它淡完新页才开始淡入。 */
    private val forwardFadeOutMillis: Int,
    /**
     * 右键菜单与命令栏菜单的 motionScheme，为 null 时跟随主题。桌面上只淡入不缩放：DropdownMenu 默认从 0.8 放大到 1，
     * graphicsLayer 的缩放同样作用于点击判定，右键后立刻点、凭肌肉记忆快速点选时，菜单项还没到最终位置，会点偏。
     */
    val menuScheme: MotionScheme?,
) {
    /**
     * 切换根页面（M3 的 top level 模式）：旧页快速淡出，然后新页淡入，不交叉，也不横滑。两页内容无关，交叉淡化时
     * 两页叠在一起读不出是哪一页；横滑暗示能左右划着切，会和可滑动的列表项抢手势。
     */
    fun topLevel(): ContentTransform =
        fadeIn(tween(topLevelEnterMillis, delayMillis = topLevelExitMillis, easing = Easing.StandardDecelerate)) togetherWith
            fadeOut(tween(topLevelExitMillis, easing = Easing.StandardAccelerate))

    /** 压栈：新页从末端滑入五分之一屏，旧页反向让开。走满整屏是 lateral 的做法，规范明说别拿它做层级导航。 */
    fun forward(): ContentTransform = enter(fromEnd = true, afterExit = true) togetherWith exit(toStart = true)

    /** 返回：与 [forward] 反向。 */
    fun backward(): ContentTransform = enter(fromEnd = false, afterExit = true) togetherWith exit(toStart = false)

    /** 盖在页面上的整屏层（全屏信息流）进场。底下的页不动，所以不必等谁淡完。 */
    fun overlayEnter(): EnterTransition = enter(fromEnd = true, afterExit = false)

    fun overlayExit(): ExitTransition = exit(toStart = false)

    /** 首屏三态切换（转圈、错误、列表）的 Crossfade。 */
    val stateCrossfade: FiniteAnimationSpec<Float> = tween(topLevelEnterMillis, easing = Easing.Standard)

    private fun enter(fromEnd: Boolean, afterExit: Boolean): EnterTransition {
        val delay = if (afterExit) forwardFadeOutMillis else 0
        return slideInHorizontally(tween(forwardEnterMillis, easing = Easing.EmphasizedDecelerate)) {
            if (fromEnd) it / SLIDE_FRACTION else -it / SLIDE_FRACTION
        } + fadeIn(tween(forwardEnterMillis - delay, delayMillis = delay, easing = Easing.EmphasizedDecelerate))
    }

    private fun exit(toStart: Boolean): ExitTransition =
        slideOutHorizontally(tween(forwardExitMillis, easing = Easing.EmphasizedAccelerate)) {
            if (toStart) -it / SLIDE_FRACTION else it / SLIDE_FRACTION
        } + fadeOut(tween(forwardFadeOutMillis, easing = Easing.StandardAccelerate))

    object Easing {
        /** md.sys.motion.easing.emphasized.decelerate（页面进入） */
        val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

        /** md.sys.motion.easing.emphasized.accelerate（页面退出） */
        val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

        /** md.sys.motion.easing.standard */
        val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

        /** md.sys.motion.easing.standard.decelerate */
        val StandardDecelerate = CubicBezierEasing(0f, 0f, 0f, 1f)

        /** md.sys.motion.easing.standard.accelerate */
        val StandardAccelerate = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    }

    companion object {
        /** 压栈滑动距离：屏幕宽度的五分之一。 */
        private const val SLIDE_FRACTION = 5

        fun of(style: MotionStyle, reduced: Boolean): PikoMotion = when (style) {
            // M3 的推荐值：进场 400ms，永久离场 200ms；top level 的 quick fade 取 150 + 250
            MotionStyle.Expressive -> PikoMotion(
                style = style,
                reduced = reduced,
                topLevelExitMillis = 150,
                topLevelEnterMillis = 250,
                forwardExitMillis = 200,
                forwardEnterMillis = 400,
                forwardFadeOutMillis = 100,
                menuScheme = null,
            )
            // WinUI 的 83、167、250ms。M3 说生产力应用里打开菜单这类高频操作可以跳切，
            // 菜单仍留 80ms 淡入，免得它凭空冒出来
            MotionStyle.Standard -> PikoMotion(
                style = style,
                reduced = reduced,
                topLevelExitMillis = 83,
                topLevelEnterMillis = 150,
                forwardExitMillis = 150,
                forwardEnterMillis = 250,
                forwardFadeOutMillis = 83,
                menuScheme = DesktopMenuMotionScheme,
            )
        }
    }
}

val LocalPikoMotion = staticCompositionLocalOf { PikoMotion.of(MotionStyle.Expressive, reduced = false) }

/** 只淡入、不缩放的菜单动效，见 [PikoMotion.menuScheme]。只有 fast 两档会被菜单读到，其余照同样的思路填。 */
@Suppress("UNCHECKED_CAST")
private object DesktopMenuMotionScheme : MotionScheme {
    private val spatial = snap<Any>()
    private val effects = tween<Any>(durationMillis = 80, easing = PikoMotion.Easing.StandardDecelerate)

    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = spatial as FiniteAnimationSpec<T>
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = spatial as FiniteAnimationSpec<T>
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = spatial as FiniteAnimationSpec<T>
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = effects as FiniteAnimationSpec<T>
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = effects as FiniteAnimationSpec<T>
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = effects as FiniteAnimationSpec<T>
}
