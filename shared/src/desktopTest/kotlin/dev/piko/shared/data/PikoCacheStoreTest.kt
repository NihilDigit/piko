package dev.piko.shared.data

import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PikoCacheStoreTest {
    @Test
    fun legacyStoreSupportsStreamCallbacks() = runBlocking {
        val values = mutableMapOf<String, String>()
        val store = object : PikoCacheStore {
            override suspend fun read(key: String) = values[key]
            override suspend fun write(key: String, value: String) { values[key] = value }
        }
        assertNull(store.readStream("missing") { it.readString() })
        store.writeStream("key") { it.writeString("中文") }
        assertEquals("中文", store.readStream("key") { it.readString() })
    }

    @Test
    fun failedAndCancelledWritesPreserveCommittedFileAndRemoveTemps() = runBlocking {
        val directory = Files.createTempDirectory("piko-cache-test")
        try {
            val store = FilePikoCacheStore(directory.toString())
            store.write("key", "old")
            assertFailsWith<IllegalStateException> {
                store.writeStream("key") { sink -> sink.writeString("partial"); error("disk failure") }
            }
            assertEquals("old", store.read("key"))
            val writing = launch {
                store.writeStream("key") { sink ->
                    sink.writeString("cancelled")
                    cancel()
                }
            }
            writing.join()
            assertEquals("old", store.read("key"))
            Files.list(directory).use { files -> assertEquals(listOf("key"), files.map { it.fileName.toString() }.toList()) }
            store.delete("key")
            assertNull(store.read("key"))
        } finally {
            Files.walk(directory).use { files -> files.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test
    fun cancellationFromDecoderIsNotSwallowed() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("piko-cache-cancel")
        try {
            val store = FilePikoCacheStore(directory.toString())
            store.write("key", "value")
            assertFailsWith<CancellationException> {
                store.readStream<Unit>("key") { throw CancellationException("cancel") }
            }
        } finally {
            Files.walk(directory).use { files -> files.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
