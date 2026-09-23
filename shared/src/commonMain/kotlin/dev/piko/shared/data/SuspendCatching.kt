package dev.piko.shared.data

import kotlinx.coroutines.CancellationException

/**
 * 标准库 runCatching 的协程安全版本。
 *
 * runCatching 会把 CancellationException 也包进 Result.failure：离开页面时本该
 * 取消的请求被当作一次普通失败，调用方照常弹出「加载失败」，协程也不会随作用域结束。
 */
internal inline fun <T> runSuspendCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
