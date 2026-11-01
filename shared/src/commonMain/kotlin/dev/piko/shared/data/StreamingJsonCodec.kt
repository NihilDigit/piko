package dev.piko.shared.data

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy

/** Inject a platform codec into common consumers; the caller owns Source/Sink. */
interface StreamingJsonCodec {
    fun <T> decode(deserializer: DeserializationStrategy<T>, source: Source): T
    fun <T> encode(serializer: SerializationStrategy<T>, value: T, sink: Sink)
}
