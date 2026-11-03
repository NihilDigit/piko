package dev.piko.shared.search

import kotlin.test.Test
import kotlin.test.assertEquals

class CjkNormalizationTest {
    @Test
    fun normalizesCompatibilityFormsBeforeSearch() {
        assertEquals("abc123", normalizeCjk("ＡＢＣ１２３"))
        assertEquals(normalizeCjk("かたかな"), normalizeCjk("ｶﾀｶﾅ"))
        assertEquals(normalizeCjk("樱"), normalizeCjk("櫻"))
    }
}
