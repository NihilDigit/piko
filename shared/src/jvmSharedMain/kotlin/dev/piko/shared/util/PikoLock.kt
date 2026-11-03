package dev.piko.shared.util

import java.util.concurrent.locks.ReentrantLock

actual class PikoLock actual constructor() {
    private val delegate = ReentrantLock()
    actual fun lock() = delegate.lock()
    actual fun unlock() = delegate.unlock()
}
