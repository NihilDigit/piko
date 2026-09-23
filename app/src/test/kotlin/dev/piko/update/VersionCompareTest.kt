package dev.piko.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCompareTest {

    @Test
    fun `segments compare as numbers, not strings`() {
        // 按字符串比会得出 "0.10.0" < "0.9.0"，把新版本当成旧版本
        assertTrue(AppUpdater.isNewer("0.10.0", "0.9.0"))
        assertFalse(AppUpdater.isNewer("0.9.0", "0.10.0"))
    }

    @Test
    fun `debug suffix and missing segments are ignored`() {
        assertFalse(AppUpdater.isNewer("0.6.0", "0.6.0-debug"))
        assertFalse(AppUpdater.isNewer("0.6", "0.6.0"))
        assertTrue(AppUpdater.isNewer("0.6.1", "0.6"))
    }
}
