package dev.piko.shared.data

import dev.piko.shared.memory.MemoryWorkKind
import dev.piko.shared.memory.PikoMemoryGovernor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A generation identifies a captured account lifetime, not just its account name. */
data class PersistVersion(val accountGeneration: Long, val revision: Long)
data class VersionedSnapshot<S>(val version: PersistVersion, val value: S)

/** Must retain the original account's state even after the active account changes. */
fun interface SnapshotSource<S> {
    suspend fun capture(): VersionedSnapshot<S>
}

/**
 * One serial commit loop per account/key. Capture happens only after memory admission.
 * [persist] must return only after the atomic commit and must not acquire another persistence permit.
 */
class SnapshotWriter<S>(
    scope: CoroutineScope,
    private val accountGeneration: Long,
    private val source: SnapshotSource<S>,
    private val governor: PikoMemoryGovernor = PikoMemoryGovernor.Shared,
    private val debounceMillis: Long = 250,
    private val estimatedPeakBytes: Long = 0,
    private val persist: suspend (S) -> Unit,
) {
    private data class Progress(
        val dirty: Long = -1,
        val persisted: Long = -1,
        val failure: Throwable? = null,
        val closing: Boolean = false,
    )

    private val progress = MutableStateFlow(Progress())
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val worker = scope.launch {
        try {
            for (ignored in wake) {
                delay(debounceMillis)
                while (progress.value.dirty > progress.value.persisted) {
                    try {
                        val permit = governor.acquire(MemoryWorkKind.Persistence, estimatedPeakBytes)
                        try {
                            val requested = progress.value.dirty
                            val snapshot = source.capture()
                            require(snapshot.version.accountGeneration == accountGeneration) {
                                "Snapshot source changed account generation"
                            }
                            check(snapshot.version.revision >= requested) {
                                "Snapshot source is older than the requested revision"
                            }
                            persist(snapshot.value)
                            update { it.copy(persisted = maxOf(it.persisted, snapshot.version.revision), failure = null) }
                        } finally {
                            permit.release()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        update { it.copy(failure = failure) }
                        break
                    }
                }
            }
        } finally {
            wake.close()
        }
    }

    init {
        require(debounceMillis >= 0)
        require(estimatedPeakBytes >= 0)
        worker.invokeOnCompletion { cause ->
            // A cancelled scope may prevent the launch body (and its finally) from ever running.
            wake.close()
            if (cause != null) update { it.copy(failure = cause) }
        }
    }

    fun markDirty(version: PersistVersion) {
        checkGeneration(version)
        check(worker.isActive) { "Snapshot writer is closed" }
        update {
            check(!it.closing) { "Snapshot writer is closed" }
            it.copy(dirty = maxOf(it.dirty, version.revision))
        }
        check(wake.trySend(Unit).isSuccess) { "Snapshot writer is closed" }
    }

    /** Retries a previous failure; never starts a second encoder alongside the writer. */
    suspend fun flushThrough(version: PersistVersion) = flush(version, allowClosing = false)

    private suspend fun flush(version: PersistVersion, allowClosing: Boolean) {
        checkGeneration(version)
        check(allowClosing || !progress.value.closing) { "Snapshot writer is closed" }
        if (progress.value.persisted >= version.revision) return
        check(worker.isActive) { "Snapshot writer is closed" }
        update {
            check(allowClosing || !it.closing) { "Snapshot writer is closed" }
            it.copy(dirty = maxOf(it.dirty, version.revision), failure = null)
        }
        check(wake.trySend(Unit).isSuccess) { "Snapshot writer is closed" }
        val result = progress.first { it.persisted >= version.revision || it.failure != null }
        if (result.persisted < version.revision) throw checkNotNull(result.failure)
    }

    /** Seal requests, commit the final revision, and release the source even if persistence fails. */
    suspend fun close(finalVersion: PersistVersion? = null) = withContext(NonCancellable) {
        finalVersion?.let(::checkGeneration)
        update { it.copy(closing = true) }
        try {
            val revision = maxOf(progress.value.dirty, finalVersion?.revision ?: -1)
            if (revision >= 0) flush(PersistVersion(accountGeneration, revision), allowClosing = true)
        } finally {
            wake.close()
            worker.join()
        }
    }

    private fun checkGeneration(version: PersistVersion) {
        require(version.accountGeneration == accountGeneration) { "Wrong snapshot account generation" }
        require(version.revision >= 0)
    }

    private inline fun update(transform: (Progress) -> Progress) {
        while (true) {
            val before = progress.value
            if (progress.compareAndSet(before, transform(before))) return
        }
    }
}
