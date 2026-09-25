package dev.piko.shared.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionCompareTest {

    @Test
    fun segmentsCompareAsNumbersNotStrings() {
        // 按字符串比会得出 "0.10.0" < "0.9.0"，把新版本当成旧版本
        assertTrue(isNewerVersion("0.10.0", "0.9.0"))
        assertFalse(isNewerVersion("0.9.0", "0.10.0"))
    }

    @Test
    fun debugSuffixAndMissingSegmentsAreIgnored() {
        assertFalse(isNewerVersion("0.6.0", "0.6.0-debug"))
        assertFalse(isNewerVersion("0.6", "0.6.0"))
        assertTrue(isNewerVersion("0.6.1", "0.6"))
    }
}
