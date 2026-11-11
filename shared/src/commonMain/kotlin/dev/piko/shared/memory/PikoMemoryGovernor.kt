package dev.piko.shared.memory

import dev.piko.shared.log.LogLevel
import dev.piko.shared.log.PikoLog
import dev.piko.shared.util.PikoLock
import dev.piko.shared.util.withPikoLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

enum class MemoryPolicy { Normal, Constrained, Critical, Background }
enum class MemoryTrim { Moderate, Low, Critical, Background }
enum class MemoryWorkKind { Persistence, TreeBuild, SnapshotRead }

interface MemoryPermit {
    fun release()
}

interface MemoryParticipant {
    val id: String
    suspend fun applyPolicy(policy: MemoryPolicy)
}

/**
 * Process-wide admission for temporary object graphs, not a promise that arbitrary allocations fit.
 * Acquire before capturing a snapshot or constructing a builder; never acquire recursively.
 */
class PikoMemoryGovernor(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val lock = PikoLock()
    private val heavy = Semaphore(1)
    private val mutablePolicy = MutableStateFlow(MemoryPolicy.Normal)
    val policy: StateFlow<MemoryPolicy> = mutablePolicy.asStateFlow()
    private var visible = true
    private var pressure = MemoryPolicy.Normal
    private var lowSamples = 0
    private var criticalSamples = 0
    private var availableBytes = Long.MAX_VALUE

    fun onVisibilityChanged(visible: Boolean) {
        val transitioned = withPikoLock(lock) {
            this.visible = visible
            val before = pressure
            if (!visible && pressure == MemoryPolicy.Normal) {
                pressure = MemoryPolicy.Constrained
                lowSamples = 0
            }
            publish()
            before != pressure
        }
        if (transitioned) {
            PikoLog.d(TAG, "Memory pressure transitioned: Normal -> Constrained (app entered background)")
        }
    }

    fun onPlatformTrim(trim: MemoryTrim) {
        var before = MemoryPolicy.Normal
        var after = MemoryPolicy.Normal
        withPikoLock(lock) {
            before = pressure
            lowSamples = 0
            criticalSamples = 0
            when (trim) {
                MemoryTrim.Background -> {
                    visible = false
                    if (pressure == MemoryPolicy.Normal) pressure = MemoryPolicy.Constrained
                }
                MemoryTrim.Critical -> pressure = MemoryPolicy.Critical
                MemoryTrim.Moderate, MemoryTrim.Low -> if (pressure == MemoryPolicy.Normal) {
                    pressure = MemoryPolicy.Constrained
                }
            }
            after = pressure
            publish()
        }
        if (before != after) {
            val level = if (after == MemoryPolicy.Critical) LogLevel.WARN else LogLevel.INFO
            PikoLog.log(level, TAG, "Memory pressure transitioned: $before -> $after (platformTrim=$trim)")
        }
    }

    /** Three low-water samples are required for each recovery step; visibility alone never resets pressure. */
    fun onHeapSample(usedBytes: Long, maxBytes: Long) {
        require(maxBytes > 0 && usedBytes >= 0)
        var before = MemoryPolicy.Normal
        var after = MemoryPolicy.Normal
        withPikoLock(lock) {
            before = pressure
            availableBytes = (maxBytes - usedBytes).coerceAtLeast(0)
            val ratio = usedBytes.toDouble() / maxBytes
            when {
                ratio >= 0.95 -> {
                    if (++criticalSamples >= 2) pressure = MemoryPolicy.Critical
                    else if (pressure == MemoryPolicy.Normal) pressure = MemoryPolicy.Constrained
                    lowSamples = 0
                }
                ratio >= 0.85 -> {
                    if (pressure == MemoryPolicy.Normal) pressure = MemoryPolicy.Constrained
                    lowSamples = 0
                    criticalSamples = 0
                }
                ratio <= 0.65 -> {
                    criticalSamples = 0
                    if (++lowSamples >= 3) {
                        pressure = when (pressure) {
                            MemoryPolicy.Critical -> MemoryPolicy.Constrained
                            else -> MemoryPolicy.Normal
                        }
                        lowSamples = 0
                    }
                }
                else -> {
                    lowSamples = 0
                    criticalSamples = 0
                }
            }
            after = pressure
            publish()
        }
        if (before != after) {
            val usedMiB = usedBytes / (1024L * 1024L)
            val maxMiB = maxBytes / (1024L * 1024L)
            val pct = usedBytes * 100L / maxBytes
            val level = if (after == MemoryPolicy.Critical) LogLevel.WARN else LogLevel.INFO
            PikoLog.log(level, TAG, "Memory pressure transitioned: $before -> $after (heap=${usedMiB}/${maxMiB} MiB, $pct%)")
        }
    }

    /** Each participant is sequential and independent. No participant code runs while [lock] is held. */
    fun register(participant: MemoryParticipant): () -> Unit {
        // Force dispatch even when the caller supplied an Unconfined scope: StateFlow publication
        // must not resume participant code inline inside the governor's synchronized section.
        val job = scope.launch(Dispatchers.Default) {
            policy.collect { participant.applyPolicy(it) }
        }
        return { job.cancel() }
    }

    suspend fun acquire(kind: MemoryWorkKind, estimatedPeakBytes: Long): MemoryPermit {
        require(estimatedPeakBytes >= 0) { "Negative peak estimate for $kind" }
        heavy.acquire()
        var availSnapshot = 0L
        val throttled = withPikoLock(lock) {
            availSnapshot = availableBytes
            if (estimatedPeakBytes > availableBytes && pressure == MemoryPolicy.Normal) {
                pressure = MemoryPolicy.Constrained
                lowSamples = 0
                publish()
                true
            } else {
                false
            }
        }
        if (throttled) {
            PikoLog.i(
                TAG,
                "Memory pressure constrained for heavy work $kind (estimatedPeak=${estimatedPeakBytes / 1024L} KiB, available=${availSnapshot / 1024L} KiB)",
            )
        }
        return object : MemoryPermit {
            private val releaseLock = PikoLock()
            private var released = false
            override fun release() {
                val release = withPikoLock(releaseLock) {
                    if (released) false else {
                        released = true
                        true
                    }
                }
                if (release) heavy.release()
            }
        }
    }

    private fun publish() {
        mutablePolicy.value = if (visible) pressure else MemoryPolicy.Background
    }

    companion object {
        private const val TAG = "Memory"
        val Shared = PikoMemoryGovernor()
    }
}

/** No absolute minimum: a low-heap process must also receive a smaller cache budget. */
fun MemoryPolicy.cacheBudget(baseBytes: Long): Long = when (this) {
    MemoryPolicy.Normal -> baseBytes
    MemoryPolicy.Constrained -> baseBytes / 2
    MemoryPolicy.Critical -> baseBytes / 4
    MemoryPolicy.Background -> baseBytes / 4
}.coerceAtLeast(1)
