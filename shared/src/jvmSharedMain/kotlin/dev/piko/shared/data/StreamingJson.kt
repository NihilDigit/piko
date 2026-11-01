package dev.piko.shared.data

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.asInputStream
import kotlinx.io.asOutputStream
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

/** Android and Desktop can inject this adapter into common persistence consumers. */
fun Json.streamingCodec(): StreamingJsonCodec = object : StreamingJsonCodec {
    override fun <T> decode(deserializer: DeserializationStrategy<T>, source: Source): T =
        decodeFromSource(deserializer, source)

    override fun <T> encode(serializer: SerializationStrategy<T>, value: T, sink: Sink) =
        encodeToSink(serializer, value, sink)
}

/** The store owns the stream lifetime; no full JSON String is allocated by this bridge. */
@OptIn(ExperimentalSerializationApi::class)
fun <T> Json.decodeFromSource(deserializer: DeserializationStrategy<T>, source: Source): T =
    decodeFromStream(deserializer, source.asInputStream())

@OptIn(ExperimentalSerializationApi::class)
fun <T> Json.encodeToSink(serializer: SerializationStrategy<T>, value: T, sink: Sink) {
    encodeToStream(serializer, value, sink.asOutputStream())
}
