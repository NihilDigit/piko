package dev.piko.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

/**
 * Piko 动效系统规范。
 * - 页面转场（滑动 1/5 屏 + 淡入淡出）使用缓动 + 时长系统。
 * - 组件内物理形变使用 MaterialExpressiveTheme 注入的 MotionScheme.expressive()。
 */
object PikoMotion {
    object Easing {
        /** md.sys.motion.easing.emphasized.decelerate (页面进入) */
        val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

        /** md.sys.motion.easing.emphasized.accelerate (页面退出) */
        val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

        /** md.sys.motion.easing.standard */
        val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
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

    /** 首屏三态切换 (转圈 / 错误 / 列表) 的 Crossfade 时长 */
    val StateCrossfadeSpec: FiniteAnimationSpec<Float> =
        tween(Duration.Medium1, easing = Easing.Standard)
}
