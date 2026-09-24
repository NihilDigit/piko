package dev.piko.shared.state

import kotlin.test.Test
import kotlin.test.assertEquals

class DistinctNameTest {

    @Test
    fun `span points into the full name even when extensions differ`() {
        val names = listOf("[DBD-Raws][Show][01][1080P][FLAC].mkv", "[DBD-Raws][Show][SP][1080P][FLAC].mp4")
        val spans = distinctSpans(names)
        assertEquals(listOf("[01]", "[SP]"), names.zip(spans) { name, span -> name.substring(span!!) })
    }
}
