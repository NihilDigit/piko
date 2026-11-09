package dev.piko.shared.media.player

interface PlaybackTransport : AutoCloseable {
    fun withdrawTransport()
}

internal fun withdrawPlaybackTransport(resources: List<AutoCloseable>) {
    resources.filterIsInstance<PlaybackTransport>().forEach { it.withdrawTransport() }
}

/** URL 的生命周期覆盖异步输入线程；网络需求先撤回，端点在 idle 确认后才注销。 */
internal suspend fun stopPlayback(backend: PlaybackBackend, resources: List<AutoCloseable>) {
    withdrawPlaybackTransport(resources)
    backend.stopAndAwaitIdle()
    resources.forEach { it.close() }
}
