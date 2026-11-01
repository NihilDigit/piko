package dev.piko.shared.data

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import java.nio.file.Files
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Run this class alone with a 256 MiB test-worker heap, not just a 256 MiB Gradle daemon. */
class TreeSnapshotScaleTest {
    @Test fun migrate42000NodesWith15000LongMagnetsWithoutWholeDocumentBuffers() {
        val lowHeap = java.lang.Boolean.getBoolean("piko.test.lowheap")
        val maxHeap = Runtime.getRuntime().maxMemory()
        if (lowHeap) assertTrue(maxHeap <= 256L * 1024 * 1024, "The test worker itself must use -Xmx256m")
        val sampling = AtomicBoolean(true)
        val peak = AtomicLong()
        val memory = ManagementFactory.getMemoryMXBean()
        val sampler = Thread({
            while (sampling.get()) {
                peak.accumulateAndGet(memory.heapMemoryUsage.used, ::maxOf)
                Thread.sleep(2)
            }
        }, "tree-scale-heap-sampler").apply { isDaemon = true; start() }
        val directory = Files.createTempDirectory("piko-tree-stream-scale")
        val legacy = directory.resolve("v1.json")
        val modern = directory.resolve("v2.json")
        try {
            // No fixture DTO graph, StringBuilder, JsonElement, or complete JSON String is allocated.
            SystemFileSystem.sink(Path(legacy.toString())).buffered().use { sink ->
                sink.writeString("{\"schemaVersion\":1,\"complete\":true,\"entries\":[")
                val trackers = "&tr=udp%3A%2F%2Fexample.invalid%3A6969%2Fannounce".repeat(30)
                repeat(42000) { index ->
                    if (index != 0) sink.writeString(",")
                    val source = index % 15000
                    sink.writeString("{\"id\":\"f$index\",\"n\":\"file$index.mp4\",\"s\":\"1000\",\"h\":\"HASH$source\",\"t\":\"video/mp4\",\"pa\":{\"url\":\"magnet:?xt=urn:btih:$source$trackers\",\"duration\":\"10\"}}")
                }
                sink.writeString("]}")
            }
            assertTrue(Files.size(legacy) > 50_000_000)
            migrate(legacy.toString(), modern.toString())
            assertTrue(Files.size(modern) < Files.size(legacy) / 2)
            val decoded = SystemFileSystem.source(Path(modern.toString())).buffered().use { source ->
                assertNotNull(DriveTreeIndex.readSnapshot(source))
            }
            assertTrue(decoded.entries.isEmpty(), "stream decode must not retain a parallel StoredTreeEntry graph")
            val physical = assertNotNull(decoded.physicalEntries)
            assertEquals(42000, physical.size)
            assertSame(physical.getValue("f0").params["url"], physical.getValue("f15000").params["url"])
            val restored = DriveTreeIndex()
            assertTrue(restored.restoreSnapshot(decoded, emptyMap()))
            val stats = restored.sortContext().subtreeStats.getValue("").physical
            assertEquals(42000L, stats.fileCount)
            assertEquals(42_000_000L, stats.bytes)
            assertEquals(420_000_000L, stats.durationMillis)
            peak.accumulateAndGet(memory.heapMemoryUsage.used, ::maxOf)
            println("TreeSnapshotScaleTest nodes=42000 distinctSources=15000 v1Bytes=${Files.size(legacy)} v2Bytes=${Files.size(modern)} maxHeap=$maxHeap sampledPeakBytes=${peak.get()}")
            if (lowHeap) assertTrue(peak.get() < 240L * 1024 * 1024, "Keep at least 16 MiB heap headroom; sampled peak=${peak.get()}")
        } finally {
            sampling.set(false)
            sampler.join()
            Files.deleteIfExists(legacy)
            Files.deleteIfExists(modern)
            Files.deleteIfExists(directory)
        }
    }

    private fun migrate(legacy: String, modern: String) {
        val snapshot = SystemFileSystem.source(Path(legacy)).buffered().use { assertNotNull(DriveTreeIndex.readSnapshot(it)) }
        val index = DriveTreeIndex()
        assertTrue(index.restoreSnapshot(snapshot, emptyMap()))
        SystemFileSystem.sink(Path(modern)).buffered().use { DriveTreeIndex.writeSnapshot(it, index.flow.value) }
    }
}
