package dev.piko.ui.screens.player

import android.os.Handler
import android.os.Looper
import dev.jdtech.mpv.MPVLib.MpvEvent

/**
 * 判断 mpv 改尺寸之后是否已经按新尺寸交出一帧。
 *
 * 转屏时 SurfaceView 换成新尺寸，屏幕上却还是 mpv 按旧尺寸画的那张 buffer。mpv 设置 surface 尺寸用的是
 * ANativeWindow_setBuffersGeometry，它顺带把缩放模式设成 SCALE_TO_WINDOW，SurfaceView 的新目标区域一生效，
 * 旧 buffer 就被非等比拉伸，直到 mpv 交出新尺寸的一帧；暂停时 mpv 要靠下面的 seek 才出新帧。MpvVideoSurface
 * 在尺寸变化的同一帧盖上黑色，等这里确认之后再揭开。
 *
 * 不用 SurfaceHolder.Callback2 推迟「画完了」：真机上（Android 16）转屏那次遍历没有走 relayoutWindow，
 * SurfaceView 走的是 handleSyncNoBuffer，mpv 的 buffer 不进窗口的同步事务，推迟只会把窗口的同步拖到超时。
 *
 * mpv 在 vo=gpu 下没有逐帧的出图通知，下面两条信号都由 mpv 的流水线保证，不靠数时间：
 * 设置 android-surface-size 要等 vo 线程处理完才返回，此后交出的 buffer 都是新尺寸，起点取在这里。
 * 播放中，time-pos 是最近一帧送进 vo 的 pts，而 vo 只有一格队列，core 要等 vo 取走上一帧才送下一帧，
 * vo 又要画完、交出一帧才取下一帧。所以起点之后出现第三个更大的 time-pos 时，其中第一帧已经交出；
 * vo 每丢一帧就多等一帧。暂停时 mpv 不出新帧，原地精确 seek 一次：seek 之后 core 要等首帧真正交出才发
 * PLAYBACK_RESTART。早先一次 seek 的 PLAYBACK_RESTART 可能晚到，所以 seek 之前先发一条 client-message，
 * 只认它之后的 SEEK 所对应的那一次。
 *
 * 等待中途可能暂停或继续，原先认的信号就不会再来。所以每隔 [REARM_MILLIS] 由 [arm] 看一次 mpv 的状态，
 * 换了状态就重新设起点；新起点只会更晚，不会提前揭开。缓冲中两条信号都不来，盖着直到出帧。
 */
internal class SurfaceRedrawGate(
    private val dropCount: () -> Long,
    private val arm: (Waiter) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val waiters = mutableListOf<Waiter>()

    /** 主线程调用：等到之后在主线程调用 [onShown]。 */
    fun await(onShown: () -> Unit) {
        val waiter = Waiter(onShown)
        synchronized(waiters) { waiters += waiter }
        arm(waiter)
        scheduleRearm(waiter)
    }

    private fun scheduleRearm(waiter: Waiter) {
        mainHandler.postDelayed({
            if (synchronized(waiters) { waiter in waiters }) {
                arm(waiter)
                scheduleRearm(waiter)
            }
        }, REARM_MILLIS)
    }

    /** mpv 的 time-pos 变了。事件线程调用。 */
    fun positionChanged(seconds: Double) = advance { it.framePositioned(seconds) }

    /** mpv 发来事件。事件线程调用。 */
    fun eventReceived(eventId: Int) = advance { it.eventReceived(eventId) }

    /** Surface 没了或播放器释放：不再有新帧，等着的一律放行。 */
    fun releaseAll() {
        val all = synchronized(waiters) { waiters.toList() }
        all.forEach(::finish)
    }

    private inline fun advance(step: (Waiter) -> Boolean) {
        val ready = synchronized(waiters) {
            if (waiters.isEmpty()) return
            waiters.filter(step)
        }
        ready.forEach(::finish)
    }

    // 出图与释放两条路都可能来，只放行一次
    private fun finish(waiter: Waiter) {
        val removed = synchronized(waiters) { waiters.remove(waiter) }
        if (!removed) return
        if (Looper.myLooper() == Looper.getMainLooper()) waiter.onShown() else mainHandler.post(waiter.onShown)
    }

    /** 一次等待。状态只在持有 waiters 锁时读写。 */
    inner class Waiter(val onShown: () -> Unit) {
        private var stage = Stage.NotStarted
        private var lastPosition = Double.NEGATIVE_INFINITY
        private var startDrops = 0L
        private var framesQueued = 0

        /** 正在等播放中的帧。 */
        val awaitingFrames: Boolean
            get() = synchronized(waiters) { stage == Stage.Frames }

        /** 正在等暂停时那次 seek 的结果。 */
        val awaitingRestart: Boolean
            get() = synchronized(waiters) { stage == Stage.Marker || stage == Stage.Seek || stage == Stage.Restart }

        /** 播放中。[position] 与 [drops] 须在 android-surface-size 设置返回之后读取；没有 time-pos 时传 null。 */
        fun awaitFrames(position: Double?, drops: Long) = synchronized(waiters) {
            lastPosition = position ?: Double.NEGATIVE_INFINITY
            startDrops = drops
            framesQueued = 0
            stage = Stage.Frames
        }

        /** 暂停中。须在发出标记与 seek 之前调用，否则标记可能先到。 */
        fun awaitRestart() = synchronized(waiters) { stage = Stage.Marker }

        fun framePositioned(seconds: Double): Boolean {
            if (stage != Stage.Frames || seconds <= lastPosition) return false
            lastPosition = seconds
            framesQueued++
            if (framesQueued < FRAMES_TO_FIRST_SWAP) return false
            return framesQueued >= FRAMES_TO_FIRST_SWAP + (dropCount() - startDrops)
        }

        fun eventReceived(eventId: Int): Boolean {
            when {
                stage == Stage.Marker && eventId == MpvEvent.MPV_EVENT_CLIENT_MESSAGE -> stage = Stage.Seek
                stage == Stage.Seek && eventId == MpvEvent.MPV_EVENT_SEEK -> stage = Stage.Restart
                stage == Stage.Restart && eventId == MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> return true
            }
            return false
        }
    }

    private enum class Stage { NotStarted, Frames, Marker, Seek, Restart }

    private companion object {
        // 正常转屏播放中约两三帧、暂停时一次 seek 就等到了，这个间隔只为中途暂停、缓冲这类信号不再来的情况
        const val REARM_MILLIS = 400L
        const val FRAMES_TO_FIRST_SWAP = 3
    }
}
