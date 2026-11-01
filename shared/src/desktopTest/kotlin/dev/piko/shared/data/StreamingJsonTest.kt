package dev.piko.shared.data

import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamingJsonTest {
    @Test
    fun codecRoundTripsUnicodeWithoutClosingBorrowedStreams() {
        val codec = Json.streamingCodec()
        val serializer = ListSerializer(String.serializer())
        val values = listOf("中文", "quote\"", "line\n", "😀")
        Buffer().use { buffer ->
            codec.encode(serializer, values, buffer)
            assertEquals(values, codec.decode(serializer, buffer))
            buffer.writeString("still open")
            assertEquals("still open", buffer.readString())
        }
    }
}
