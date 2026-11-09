package dev.piko.shared.util

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/** Reentrant lock for short, non-suspending critical sections. */
expect class PikoLock() {
    fun lock()
    fun unlock()
}

@OptIn(ExperimentalContracts::class)
inline fun <T> withPikoLock(lock: PikoLock, action: () -> T): T {
    contract { callsInPlace(action, InvocationKind.EXACTLY_ONCE) }
    lock.lock()
    try { return action() } finally { lock.unlock() }
}
