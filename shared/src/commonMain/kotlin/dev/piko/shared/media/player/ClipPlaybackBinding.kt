package dev.piko.shared.media.player

import dev.piko.shared.media.PreparedClip
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

/** 串行管理一个预览播放器的换片与退休；清理不依赖已销毁页面的协程。 */
class ClipPlaybackBinding(
    private val backend: PlaybackBackend,
    context: CoroutineContext = Dispatchers.Main.immediate,
) {
    private val scope = CoroutineScope(context.minusKey(Job) + SupervisorJob())
    private val transition = Mutex()
    private var retained: AutoCloseable? = null
    private var currentKey: Any? = null
    private var opening: Job? = null
    private var retiring: Job? = null

    suspend fun open(clip: PreparedClip, target: PlaybackTarget, startMillis: Long, playWhenReady: Boolean) {
        transition.withLock {
            if (retiring != null) throw CancellationException("preview retired")
            withContext(NonCancellable) { stopLocked() }
            currentCoroutineContext().ensureActive()
            val key = Any()
            retained = clip.retainPlayback {
                scope.launch {
                    if (currentKey === key) opening?.cancel(CancellationException("clip retired during open"))
                    transition.withLock {
                        if (currentKey === key) stopLocked()
                    }
                }
            }
            currentKey = key
            opening = currentCoroutineContext().job
            try {
                backend.open(target, startMillis, playWhenReady)
            } catch (failure: Throwable) {
                withContext(NonCancellable) { stopLocked() }
                throw failure
            } finally {
                opening = null
            }
        }
    }

    fun retire(onStopped: () -> Unit = {}): Job {
        retiring?.let { return it }
        return scope.launch(start = CoroutineStart.LAZY) {
            try {
                transition.withLock { stopLocked() }
            } finally {
                try { onStopped() } finally { scope.cancel() }
            }
        }.also {
            retiring = it
            opening?.cancel(CancellationException("preview retired during open"))
            it.start()
        }
    }

    private suspend fun stopLocked() {
        if (retained == null) return
        backend.stopAndAwaitIdle()
        retained?.close()
        retained = null
        currentKey = null
    }
}
