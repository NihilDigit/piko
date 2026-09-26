package dev.piko.ui.screens.player

import android.os.Handler
import android.os.Looper
import android.view.Choreographer

/**
 * 转屏时让窗口等 mpv 画出新尺寸的一帧再上屏。
 *
 * 转屏那一刻，SurfaceView 先换成新尺寸，屏幕上却还是 mpv 按旧方向画的那张 buffer。mpv 设置 surface
 * 尺寸用的是 ANativeWindow_setBuffersGeometry，它顺带把缩放模式设成 SCALE_TO_WINDOW，于是旧 buffer
 * 被非等比拉伸，填满新的区域；暂停时 mpv 不重画，拉伸的画面会一直留着。SurfaceHolder.Callback2 的
 * surfaceRedrawNeededAsync 允许晚一点再报「画完了」，窗口的同步事务会一直收集 SurfaceView 的 buffer，
 * 等到那时才连同新方向一起提交。这里就负责决定「那时」是什么时候。
 *
 * mpv 在 vo=gpu 下没有逐帧的出图通知，只能取近似：播放中是下一次 time-pos 更新，暂停时由 [forceRedraw]
 * 逼它重画后的 PLAYBACK_RESTART。之后再等一个 vsync，让 buffer 真正进到同步事务里。
 * 等不到就在 [TIMEOUT_MILLIS] 后照常放行：窗口的同步挂着不放，WMS 那边也会超时，代价更大。
 */
internal class SurfaceRedrawGate(private val forceRedraw: () -> Unit) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val waiters = mutableListOf<Waiter>()

    @Volatile
    private var hasWaiters = false

    /** 等下一帧出图后在主线程调用 [onDrawn]。[paused] 时先逼 mpv 重画一次。 */
    fun await(paused: Boolean, onDrawn: () -> Unit) {
        val waiter = Waiter(onDrawn)
        synchronized(waiters) {
            waiters += waiter
            hasWaiters = true
        }
        mainHandler.postDelayed({ waiter.finish() }, TIMEOUT_MILLIS)
        if (paused) forceRedraw()
    }

    /** mpv 出了一帧。任意线程调用。 */
    fun frameShown() {
        if (!hasWaiters) return
        val ready = takeAll()
        // 等一个 vsync：time-pos 更新时新 buffer 刚排进队列，还没到 SurfaceView 的同步事务里
        mainHandler.post { Choreographer.getInstance().postFrameCallback { ready.forEach(Waiter::finish) } }
    }

    /** Surface 没了或播放器释放：不再有新帧，等着的一律放行。 */
    fun releaseAll() {
        takeAll().forEach(Waiter::finish)
    }

    private fun takeAll(): List<Waiter> = synchronized(waiters) {
        waiters.toList().also {
            waiters.clear()
            hasWaiters = false
        }
    }

    private inner class Waiter(private val onDrawn: () -> Unit) {
        private var done = false

        // 超时、出图、释放三条路都会来，只放行一次；都经主线程，不用加锁
        fun finish() {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post(::finish)
                return
            }
            if (done) return
            done = true
            onDrawn()
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 200L
    }
}
