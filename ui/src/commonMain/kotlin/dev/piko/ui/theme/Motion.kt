package dev.piko.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

/**
 * Piko 动效系统规范。
 * - 页面转场（滑动 1/5 屏 + 淡入淡出）使用缓动 + 时长系统。
 * - 组件内物理形变使用 MaterialExpressiveTheme 注入的 MotionScheme.expressive()。
 *
 * Documentation references:
 * - Material 3 Motion System & Easing: `m3-material-mirror/pages/styles/motion.md`
 * - Android Compose Animation: `android-docs-mirror/pages/develop/ui/compose/animation.md`
 */
object PikoMotion {
    object Easing {
        /** md.sys.motion.easing.emphasized.decelerate (页面进入) */
        val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

        /** md.sys.motion.easing.emphasized.accelerate (页面退出) */
        val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

        /** md.sys.motion.easing.standard */
        val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

        /** md.sys.motion.easing.standard.decelerate */
        val StandardDecelerate = CubicBezierEasing(0f, 0f, 0f, 1f)

        /** md.sys.motion.easing.standard.accelerate */
        val StandardAccelerate = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    }

    object Duration {
        const val Short3 = 150
        const val Medium1 = 250
        const val Medium4 = 400
    }

    // 转场规范：滑动 + 淡入淡出 (Android 平台默认标准)
    val ForwardEnterSlide: FiniteAnimationSpec<IntOffset> =
        tween(Duration.Medium4, easing = Easing.EmphasizedDecelerate)
    val ForwardExitSlide: FiniteAnimationSpec<IntOffset> =
        tween(Duration.Medium4, easing = Easing.EmphasizedAccelerate)

    val ForwardEnterFade: FiniteAnimationSpec<Float> =
        tween(Duration.Medium4, easing = Easing.EmphasizedDecelerate)
    val ForwardExitFade: FiniteAnimationSpec<Float> =
        tween(Duration.Medium4, easing = Easing.EmphasizedAccelerate)

    /** 压栈滑动距离：屏幕宽度的五分之一 */
    const val ForwardSlideFraction = 5

    /**
     * 切换底栏的根页面。M3 的 top level 模式：旧页快速淡出，然后新页淡入，不交叉，也不横滑。
     * 两页内容无关，交叉淡化时两页叠在一起读不出是哪一页；横滑暗示能左右划着切，会和可滑动的
     * 列表项抢手势。所以进入那一档延后一个退出时长，合起来仍在规范说的 quick fade 之内。
     */
    val TopLevelExitFade: FiniteAnimationSpec<Float> =
        tween(Duration.Short3, easing = Easing.StandardAccelerate)
    val TopLevelEnterFade: FiniteAnimationSpec<Float> =
        tween(Duration.Medium1, delayMillis = Duration.Short3, easing = Easing.StandardDecelerate)

    /** 首屏三态切换 (转圈 / 错误 / 列表) 的 Crossfade 时长 */
    val StateCrossfadeSpec: FiniteAnimationSpec<Float> =
        tween(Duration.Medium1, easing = Easing.Standard)
}
