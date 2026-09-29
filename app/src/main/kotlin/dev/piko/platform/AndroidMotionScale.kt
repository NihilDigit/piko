package dev.piko.platform

import android.app.Activity
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.ui.platform.compositionContext
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.lifecycle.Lifecycle
import dev.piko.ui.theme.PikoMotionScale

/**
 * 把系统的「动画时长缩放」（开发者选项，「移除动画」即 0）接到 [PikoMotionScale.systemScale]。
 *
 * Compose 自己也读这个值（WindowRecomposer 里的 MotionDurationScaleImpl），但只在 Recomposer 的上下文里还没有
 * MotionDurationScale 时才建那一份；[installMotionScale] 注入了我们的，它就不再建，系统这一半只能自己接。
 * 注册一次跟着进程走，不注销。
 */
fun PikoMotionScale.followSystemAnimatorScale(context: Context) {
    val resolver = context.contentResolver
    fun read() {
        systemScale = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }
    read()
    resolver.registerContentObserver(
        Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
        false,
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = read()
        },
    )
}

/**
 * 给这个 Activity 的窗口换一个带 [scale] 的 Recomposer，须在 setContent 之前调用。ComposeView 沿着父视图找
 * compositionContext，找到装饰视图上的这一个就不再自建；Dialog 与 Popup 的组合挂在调用处的组合下面，
 * 用的是同一个 Recomposer，所以对话框、菜单里的动画一起缩放。其余行为（随生命周期暂停出帧、销毁时取消）
 * 与默认的 WindowRecomposer 相同，用的就是同一个工厂函数。
 */
fun Activity.installMotionScale(scale: PikoMotionScale, lifecycle: Lifecycle) {
    val root = window.decorView
    root.compositionContext = root.createLifecycleAwareWindowRecomposer(scale, lifecycle)
}
