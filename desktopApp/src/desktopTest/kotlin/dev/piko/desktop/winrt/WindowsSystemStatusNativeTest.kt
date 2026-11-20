package dev.piko.desktop.winrt

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsSystemStatusNativeTest {

    @Test
    fun snapshotAndObserveReturnValidStatusOnWindows() {
        val snapshot = WindowsSystemStatus.snapshot()
        if (!WinRTSupport.isWindows) {
            assertNull(snapshot)
            return
        }
        assertNotNull(snapshot, "Windows 上应能查询电源与网络状态快照")
        snapshot.batteryPercent?.let { pct ->
            assertTrue(pct in 0..100, "电池百分比应在 0..100 范围内，实际为 $pct")
        }
        val observed = runBlocking {
            withTimeout(5_000L) { WindowsSystemStatus.observe(pollIntervalMs = 60_000L).first() }
        }
        assertTrue(observed.batteryPercent == null || observed.batteryPercent in 0..100)
    }
}
