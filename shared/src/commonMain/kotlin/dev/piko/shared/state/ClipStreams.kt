package dev.piko.shared.state

import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.media.PreparedClip
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFile
import io.github.nihildigit.pikpak.PikPakStreamReader
import io.github.nihildigit.pikpak.StreamRole
import io.github.nihildigit.pikpak.TransferDemand
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** 每个片段保留同一个准备结果和可提升的需求；挂起只撤回候补预取，不销毁准备好的会话。 */
class ClipStreams internal constructor(
    private val repository: PikoMediaRepository,
    private val onReady: (Clip) -> Unit,
    private val onDead: (Clip) -> Unit,
    coroutineContext: kotlin.coroutines.CoroutineContext = Dispatchers.Main.immediate,
    private val prepare: suspend (Clip, StreamRole, TransferDemand) -> Result<PreparedClip> = { clip, role, owner ->
        repository.prepareClip(clip.fileId, clip.startMs, clip.videoDurationMs, role, owner)
    },
) {
    private class Entry(val demand: TransferDemand) {
        val priority = MutableStateFlow(demand.priority)
        lateinit var result: Deferred<PreparedClip?>
        var prepared: PreparedClip? = null
        var fetch: Job? = null
        var wanted = false
        var ready = false
        var closed = false
        var foreground = false
        var urgent = false
        var playing = true
        var readAheadSeconds: Int? = null
        var readAheadMinBytes = PreparedClip.MIN_AHEAD_BYTES
        var readAheadEnabled = false
        var feedbackOwner: Any? = null
        var nativeGeneration = Long.MIN_VALUE
        var feedbackGeneration = 0L
        fun prioritize(value: Int) {
            priority.value = value
            if (!demand.isClosed) demand.updatePriority(value)
            prepared?.role = if (value >= PikPakStreamReader.STREAMING_PRIORITY) StreamRole.FOREGROUND else StreamRole.BACKGROUND
        }
    }
    private val scope = CoroutineScope(coroutineContext.minusKey(Job) + SupervisorJob())
    private val preparing = PreparationQueue(backgroundSlots = 4)
    private val prefetching = PreparationQueue(backgroundSlots = 2)
    private val streams = mutableMapOf<Clip, Entry>()
    private var foreground: Set<Clip> = emptySet()
    private val suspended = MutableStateFlow(false)

    fun setSuspended(value: Boolean) {
        if (suspended.value == value) return
        suspended.value = value
        for ((clip, entry) in streams) {
            updateDemand(entry)
            if (value) {
                entry.fetch?.cancel(); entry.fetch = null
                entry.prepared?.holdReadAhead()
                entry.prepared?.urgent = false
            }
        }
        if (!value) ripen(streams.filterValues { it.wanted && !it.ready }.keys.toList())
    }

    fun get(clip: Clip, forPlayer: Boolean = false, urgent: Boolean = false): Deferred<PreparedClip?> {
        val existing = streams[clip]
        val needsUrgent = urgent || (forPlayer && existing?.result?.isCompleted != true)
        existing?.let {
            if (forPlayer || clip in foreground) it.foreground = true
            if (forPlayer) it.playing = true
            if (needsUrgent) it.urgent = true
            updateDemand(it)
            return it.result
        }
        val entry = Entry(repository.openTransferDemand(PikPakStreamReader.WARM_PRIORITY))
        entry.foreground = forPlayer || clip in foreground
        entry.urgent = needsUrgent
        updateDemand(entry)
        streams[clip] = entry
        entry.result = scope.async(start = CoroutineStart.LAZY) {
            preparing.run(entry.priority) {
                val prepared = prepare(clip,
                    if (entry.priority.value >= 50) StreamRole.FOREGROUND else StreamRole.BACKGROUND, entry.demand)
                    .getOrElse { error ->
                        PikoLog.w("Clips", "备会话失败 ${logFile(clip.fileId, clip.name)}", error)
                        return@run null
                    }
                if (entry.closed) { prepared.close(); return@run null }
                entry.prepared = prepared
                prepared.holdReadAhead()
                updateDemand(entry)
                prepared
            }
        }
        entry.result.start()
        return entry.result
    }

    fun ripen(upcoming: List<Clip>) {
        for (clip in upcoming) {
            get(clip)
            val entry = streams.getValue(clip)
            entry.wanted = true
            if (entry.ready || entry.fetch?.isActive == true || suspended.value) continue
            entry.fetch = scope.launch {
                try {
                    val prepared = entry.result.await()
                    if (prepared == null) {
                        if (!entry.closed) { streams.remove(clip); close(entry); onDead(clip) }
                        return@launch
                    }
                    suspended.first { !it }
                    prefetching.run(entry.priority) {
                        suspended.first { !it }
                        withContext(entry.demand + Dispatchers.Default) { prepared.prefetch(StreamRole.BACKGROUND) }
                    }
                    entry.ready = true
                    if (!entry.closed) onReady(clip)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (!entry.closed) {
                        PikoLog.w("Clips", "预取失败 ${logFile(clip.fileId, clip.name)}", e)
                        streams.remove(clip); close(entry); onDead(clip)
                    }
                }
            }
        }
    }

    fun bringToFront(clips: Set<Clip>) {
        foreground = clips
        for ((clip, entry) in streams) {
            entry.foreground = clip in clips
            if (!entry.foreground) entry.urgent = false
            updateDemand(entry)
        }
    }

    fun boost(clip: Clip, seconds: Int, minBytes: Long = PreparedClip.MIN_AHEAD_BYTES) {
        get(clip)
        val entry = streams.getValue(clip)
        entry.readAheadSeconds = seconds
        entry.readAheadMinBytes = minBytes
        entry.readAheadEnabled = true
        updateDemand(entry)
    }

    fun holdAllBut(clip: Clip) {
        for ((other, entry) in streams) if (other != clip) {
            entry.readAheadEnabled = false
            entry.urgent = false
            updateDemand(entry)
        }
    }

    fun bindPlayback(clip: Clip, owner: Any) {
        get(clip, forPlayer = true)
        val entry = streams.getValue(clip)
        if (entry.feedbackOwner === owner) return
        entry.feedbackOwner = owner
        entry.nativeGeneration = Long.MIN_VALUE
        entry.feedbackGeneration++
        entry.prepared?.reportPlaybackBuffer(io.github.nihildigit.pikpak.PlaybackBufferSnapshot(
            entry.feedbackGeneration, null, kotlin.time.TimeSource.Monotonic.markNow()))
    }

    fun reportPlaybackBuffer(clip: Clip, owner: Any, snapshot: io.github.nihildigit.pikpak.PlaybackBufferSnapshot) {
        val entry = streams[clip] ?: return
        if (entry.feedbackOwner !== owner || snapshot.generation < entry.nativeGeneration) return
        if (snapshot.generation > entry.nativeGeneration) {
            entry.nativeGeneration = snapshot.generation
            entry.feedbackGeneration++
        }
        entry.prepared?.reportPlaybackBuffer(snapshot.copy(generation = entry.feedbackGeneration))
    }

    fun beginSeek(clip: Clip, backend: dev.piko.shared.media.player.PlaybackBackend) {
        bindPlayback(clip, backend)
        setUrgent(clip, true)
        reportPlaybackBuffer(clip, backend, io.github.nihildigit.pikpak.PlaybackBufferSnapshot(
            backend.playbackGeneration + 1, null, kotlin.time.TimeSource.Monotonic.markNow()))
    }

    fun setUrgent(clip: Clip, urgent: Boolean, playing: Boolean = true) {
        val entry = streams[clip] ?: return
        entry.urgent = urgent
        entry.playing = playing
        updateDemand(entry)
    }

    private fun updateDemand(entry: Entry) {
        val active = entry.foreground && !suspended.value
        val urgent = active && entry.urgent
        val steady = active && entry.playing && !urgent
        entry.prioritize(when {
            urgent -> PikPakStreamReader.BLOCKING_PRIORITY
            steady -> PikPakStreamReader.STREAMING_PRIORITY
            else -> PikPakStreamReader.WARM_PRIORITY
        })
        entry.prepared?.let { prepared ->
            val seconds = entry.readAheadSeconds
            if (active && (urgent || entry.playing) && entry.readAheadEnabled && seconds != null) {
                prepared.extendReadAhead(seconds, entry.readAheadMinBytes)
            } else prepared.holdReadAhead()
            prepared.updatePhase(urgent, steady)
        }
    }

    fun sync(window: Set<Clip>) {
        (streams.keys - window).forEach { clip -> streams.remove(clip)?.let(::close) }
    }

    fun closeAll() {
        streams.values.forEach(::close)
        streams.clear()
        foreground = emptySet()
    }

    private fun close(entry: Entry) {
        entry.closed = true
        entry.fetch?.cancel()
        entry.result.cancel()
        entry.demand.close()
        entry.prepared?.close()
    }
}
