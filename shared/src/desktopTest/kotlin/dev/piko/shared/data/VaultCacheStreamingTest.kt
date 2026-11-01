package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.*
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.serialization.json.*
import java.lang.management.ManagementFactory
import java.nio.file.Files
import kotlin.test.*

class VaultCacheStreamingTest {
    @Test
    fun v2ReadsReorderedFieldsAndRowsWithoutAnIntermediateCacheGraph() {
        val source = "magnet:?xt=urn:btih:shared"
        val entries = listOf(
            VaultEntry("one", "one.mkv", 10, "HASH", source = source, addedAt = 1),
            VaultEntry("two", "two.mkv", 20, "HASH2", source = source, addedAt = 2),
            VaultEntry("three", "three.mkv", 30, "HASH3", addedAt = 3),
        )
        val encoded = Json.parseToJsonElement(VaultStore.encodeParsed(mapOf("manifest" to entries))).jsonObject
        val manifests = encoded.getValue("manifests").jsonObject.mapValues { (_, value) ->
            JsonArray(value.jsonArray.map { row -> JsonObject(row.jsonObject.toList().reversed().toMap()) })
        }
        val reordered = JsonObject(linkedMapOf(
            "committed" to encoded.getValue("committed"),
            "manifests" to JsonObject(manifests),
            "sources" to encoded.getValue("sources"),
            "version" to encoded.getValue("version"),
        )).toString()
        val decoded = assertNotNull(VaultStore.decodeParsed(reordered)).getValue("manifest")
        assertEquals(entries, decoded)
        assertSame(decoded[0].source, decoded[1].source)
        assertNull(VaultStore.decodeParsed(reordered.replace("\"source\":0", "\"source\":99")))
    }

    /** Run with a 256 MiB test-worker heap and -Dpiko.test.lowheap=true for the constrained check. */
    @Test
    fun persistAndRestore42000EntriesWith15000LongSourcesUsingOnlyStreams() = runBlocking {
        val lowHeap = java.lang.Boolean.getBoolean("piko.test.lowheap")
        val maxHeap = Runtime.getRuntime().maxMemory()
        if (lowHeap) assertTrue(maxHeap <= 256L * 1024 * 1024, "test worker must use -Xmx256m")
        val directory = Files.createTempDirectory("piko-vault-stream-scale")
        val owner = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + owner)
        val files = FilePikoCacheStore(directory.toString(), Json { ignoreUnknownKeys = true }.streamingCodec())
        val cache = object : PikoCacheStore by files {
            override suspend fun read(key: String): String? = error("whole-document reads are forbidden")
            override suspend fun write(key: String, value: String) = error("whole-document writes are forbidden")
            override suspend fun <T> readStream(key: String, decode: (Source) -> T): T? = files.readStream(key, decode)
            override suspend fun writeStream(key: String, encode: (Sink) -> Unit) = files.writeStream(key, encode)
        }
        try {
            writeFixture(cache, scope)
            val restored = VaultStore(NoIo, cacheStore = cache, account = "scale", scope = scope, saveDelayMillis = 0)
            withTimeout(30_000) { restored.awaitLoaded() }
            val parsed = restored.parsedFlow.value
            assertEquals(42_000, parsed.values.sumOf { it.size })
            val entries = parsed.getValue("manifest")
            assertSame(entries[0].source, entries[15_000].source)
            assertEquals(41_999L, entries.last().createdAt)
            withTimeout(30_000) { restored.close().await() }
            assertTrue(owner.children.none())
            val fileBytes = Files.size(directory.resolve(VaultStore.cacheKeyOf("scale")))
            val peak = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == java.lang.management.MemoryType.HEAP }
                .sumOf { it.peakUsage.used }
            println("VaultCacheStreamingTest entries=42000 distinctSources=15000 fileBytes=$fileBytes maxHeap=$maxHeap heapPoolPeakBytes=$peak")
        } finally {
            owner.cancelAndJoin()
            Files.list(directory).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }

    private suspend fun writeFixture(cache: PikoCacheStore, scope: CoroutineScope) {
        val trackers = "&tr=udp%3A%2F%2Fexample.invalid%3A6969%2Fannounce".repeat(30)
        val sources = List(15_000) { "magnet:?xt=urn:btih:$it$trackers" }
        val entries = List(42_000) { index -> VaultEntry("$index", "file$index.mkv", 1000, "HASH$index",
            source = sources[index % sources.size], addedAt = 1, createdAt = index.toLong()) }
        val store = VaultStore(NoIo, cacheStore = cache, account = "scale", scope = scope,
            saveDelayMillis = 0, initialParsed = mapOf("manifest" to entries))
        withTimeout(30_000) { store.close().await() }
    }

    private object NoIo : VaultFolderIo {
        override suspend fun list(folderId: String): List<FileStat> = error("unexpected remote list")
        override suspend fun read(fileId: String): ByteArray = error("unexpected remote read")
        override suspend fun upload(folderId: String, name: String, bytes: ByteArray): String = error("unexpected upload")
        override suspend fun delete(ids: List<String>) = error("unexpected delete")
    }
}
