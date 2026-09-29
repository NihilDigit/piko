package dev.piko.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale

/**
 * 整个进程的动画时长缩放，入口放进 Recomposer 的协程上下文，所有动画都从那里读（SuspendAnimation 与 Transition
 * 每一帧读 coroutineContext[MotionDurationScale]，为 0 时直接跳到终点）。进程里只有一份，两端的入口建好后
 * 交给 PikoPlatform，主题从它读出「减少动画」。
 *
 * 读的是快照状态，改了之后下一帧即生效，已在进行的动画也立即跳到终点。
 */
class PikoMotionScale : MotionDurationScale {
    /**
     * 系统给的缩放。Android 是开发者选项里的「动画时长缩放」（0 到 10），桌面上系统只有开关，
     * 开了「减少动画」为 0，否则为 1。
     */
    var systemScale by mutableFloatStateOf(1f)

    /** 设置里的「减少动画」。与系统设置取或：任一开着就减少。 */
    var appReduced by mutableStateOf(false)

    override val scaleFactor: Float
        get() = if (appReduced) 0f else systemScale

    val reduced: Boolean
        get() = scaleFactor == 0f
}
