package dev.piko.shared.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LogRetentionTest {
    private val cutoff = "2026-09-24 12:00:00.000"

    // 堆栈没有时间戳，属于上一条：旧记录的堆栈要随它一起丢，不能被当成新记录的开头留下
    @Test
    fun `stack traces go with the entry they belong to`() {
        val old = "2026-09-24 11:59:59.999 W Proxy: 读取失败\njava.io.IOException: x\n\tat a.b(C.kt:1)\n"
        val new = "2026-09-24 12:00:00.000 I App: 启动 1.0.0\n"
        assertEquals(old.length, firstEntryNotBefore(old + new, cutoff))
    }

    // 消息里引用的时间不在行首，不算记录的开头
    @Test
    fun `timestamps inside a message are not entry starts`() {
        val text = "2026-09-20 08:00:00.000 I Auth: 会话在 2026-09-30 10:00:00.000 过期\n"
        assertNull(firstEntryNotBefore(text, cutoff))
    }
}
