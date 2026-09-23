package dev.piko.smoke

import android.os.SystemClock

/** 轮询等待条件成立。前台服务与 Activity 的状态变化都是异步的，没有可以挂起等待的回调。 */
fun awaitCondition(description: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (!condition()) {
        if (SystemClock.uptimeMillis() > deadline) throw AssertionError("超时仍未满足：$description")
        SystemClock.sleep(50)
    }
}
