package dev.piko.shared.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MapCompatTest {
    @Test fun absentAndNullValuesAreReplacedButExistingValuesWin() {
        val values = mutableMapOf<String, Int?>("null" to null, "existing" to 2)
        assertNull(values.putIfAbsentCompat("missing", 1))
        assertNull(values.putIfAbsentCompat("null", 3))
        assertEquals(2, values.putIfAbsentCompat("existing", 4))
        assertEquals(mapOf<String, Int?>("missing" to 1, "null" to 3, "existing" to 2), values)
    }
}
