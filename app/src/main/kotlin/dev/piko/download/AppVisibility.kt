package dev.piko.download

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 应用有没有界面在前台，按整个进程算。进度通知、结局通知与前台服务都看它：前台时界面上就有进度与提示，不再发系统通知。
 *
 * 用 ProcessLifecycleOwner 而不是自己数 Activity 的 onStart、onStop：转屏重建活动时计数会短暂归零，
 * 它对 ON_STOP 延后 700ms 发出，正是为了不把这一下当成退到后台。700ms 仍在系统允许「刚离开前台」
 * 拉起前台服务的宽限之内（restrictions-bg-start.md 的第一条豁免）。
 */
internal object AppVisibility {
    private val lifecycle: Lifecycle get() = ProcessLifecycleOwner.get().lifecycle

    val isVisible: Boolean get() = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /** 立即给出当前值，之后每次变化给一次。 */
    val visible: Flow<Boolean>
        get() = lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) }.distinctUntilChanged()
}
