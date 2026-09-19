package dev.piko.util

import kotlinx.coroutines.CancellationException

/**
 * Suspend-safe alternative to standard [runCatching].
 *
 * Catches all [Throwable]s EXCEPT [CancellationException].
 * Rethrowing [CancellationException] is required by Kotlin Coroutines structured concurrency
 * to ensure cancellation signals propagate cleanly to parent scopes and composable lifecycles.
 *
 * Documentation Reference:
 * - Kotlin: kotlin-docs-mirror/pages/docs/coroutines-cancellation.md
 *   "Catching CancellationException can break the cancellation propagation.
 *    If you must catch it, rethrow it to let the cancellation propagate correctly."
 */
inline fun <R> runSuspendCatching(block: () -> R): Result<R> {
    return try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c
    } catch (e: Throwable) {
        Result.failure(e)
    }
}
