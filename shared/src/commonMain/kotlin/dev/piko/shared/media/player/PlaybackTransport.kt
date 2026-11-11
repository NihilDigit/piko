package dev.piko.shared.media.player

interface PlaybackTransport : AutoCloseable {
    fun withdrawTransport()
}

internal fun withdrawPlaybackTransport(resources: List<AutoCloseable>) {
    resources.filterIsInstance<PlaybackTransport>().forEach { it.withdrawTransport() }
}

/** URL 的生命周期覆盖异步输入线程；先向后端下达 stop，再撤回网络需求解锁阻塞读，端点在 idle 确认后才注销。 */
internal suspend fun stopPlayback(backend: PlaybackBackend, resources: List<AutoCloseable>) {
    backend.beginStop()
    withdrawPlaybackTransport(resources)
    backend.stopAndAwaitIdle()
    resources.forEach { it.close() }
}
