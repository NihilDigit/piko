package dev.piko.shared.media.proxy

import dev.piko.shared.media.testing.request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SlicedByteSourceTest {

    private companion object {
        const val TS_PACKET = 188
        const val VIDEO_PID = 0x0100
        // Audio PID deliberately shares the upper 5 PID bits (0x01) with VIDEO_PID (0x0100)
        // so a 2-byte split read (0x47, 0x41) cannot guess the PID without reading byte 2!
        const val AUDIO_PID = 0x0101
        const val PAT_PID = 0x0000
        const val PMT_PID = 0x1000
    }

    private fun buildTsPacket(
        pid: Int,
        pusi: Boolean = false,
        cc: Int = 0,
        syncByte: Byte = 0x47,
        seed: Int = 0,
    ): ByteArray {
        val pkt = ByteArray(TS_PACKET)
        pkt[0] = syncByte
        pkt[1] = ((if (pusi) 0x40 else 0x00) or ((pid ushr 8) and 0x1F)).toByte()
        pkt[2] = (pid and 0xFF).toByte()
        pkt[3] = (0x10 or (cc and 0x0F)).toByte()
        for (i in 4 until TS_PACKET) {
            pkt[i] = ((seed * 37 + i * 13 + 7) and 0xFF).toByte()
        }
        return pkt
    }

    private fun buildNullPacket(): ByteArray = ByteArray(TS_PACKET).apply {
        this[0] = 0x47
        this[1] = 0x1F
        this[2] = 0xFF.toByte()
        this[3] = 0x10
        for (i in 4 until TS_PACKET) {
            this[i] = 0xFF.toByte()
        }
    }

    private inner class Fixture {
        val prefixPackets = 7
        val sliceOffset = prefixPackets * TS_PACKET.toLong()
        // 12 packets inside slice; mask first 7 packets [0, 7 * 188), keep packets 7..11 untouched
        val maskPackets = 7
        val totalSlicePackets = 12
        val sliceSize = totalSlicePackets * TS_PACKET.toLong()
        val maskLimit = maskPackets * TS_PACKET.toLong()

        val rawBacking: ByteArray
        val originalBackingSnapshot: ByteArray
        val expectedSlice: ByteArray

        init {
            val allRaw = ArrayList<ByteArray>()
            val expected = ArrayList<ByteArray>()

            // Prefix outside slice (contains video PID packets that sit before sliceOffset)
            repeat(prefixPackets) { idx ->
                allRaw += buildTsPacket(VIDEO_PID, pusi = idx % 2 == 0, cc = idx, seed = 100 + idx)
            }

            // Slice packets 0..6 (inside nullVideoBeforeOffset):
            // 0: PAT (keep)
            // 1: PMT (keep)
            // 2: Orphan video PUSI=1 (MASK -> 0x1FFF)
            // 3: Audio 0x0101 sharing high PID byte with 0x0100 (keep!)
            // 4: Orphan video PUSI=0 (MASK -> 0x1FFF)
            // 5: Non-0x47 sync byte carrying 0x0100 in bytes 1..2 (keep!)
            // 6: Orphan video right before keyframe boundary (MASK -> 0x1FFF)
            val preKeyframe = listOf(
                buildTsPacket(PAT_PID, pusi = true, cc = 1, seed = 1) to false,
                buildTsPacket(PMT_PID, pusi = true, cc = 2, seed = 2) to false,
                buildTsPacket(VIDEO_PID, pusi = true, cc = 3, seed = 3) to true,
                buildTsPacket(AUDIO_PID, pusi = true, cc = 4, seed = 4) to false,
                buildTsPacket(VIDEO_PID, pusi = false, cc = 5, seed = 5) to true,
                buildTsPacket(VIDEO_PID, pusi = true, cc = 6, syncByte = 0x12, seed = 6) to false,
                buildTsPacket(VIDEO_PID, pusi = false, cc = 7, seed = 7) to true,
            )
            check(preKeyframe.size == maskPackets)
            for ((pkt, masked) in preKeyframe) {
                allRaw += pkt
                expected += if (masked) buildNullPacket() else pkt.copyOf()
            }

            // Slice packets 7..11 (at and after keyframe boundary -> NEVER masked even for VIDEO_PID!)
            val postKeyframe = listOf(
                buildTsPacket(VIDEO_PID, pusi = true, cc = 8, seed = 8),
                buildTsPacket(AUDIO_PID, pusi = false, cc = 9, seed = 9),
                buildTsPacket(VIDEO_PID, pusi = false, cc = 10, seed = 10),
                buildTsPacket(PAT_PID, pusi = true, cc = 11, seed = 11),
                buildTsPacket(VIDEO_PID, pusi = true, cc = 12, seed = 12),
            )
            for (pkt in postKeyframe) {
                allRaw += pkt
                expected += pkt.copyOf()
            }

            // Trailing packets after slice end
            repeat(3) { idx ->
                allRaw += buildTsPacket(VIDEO_PID, pusi = true, cc = idx, seed = 200 + idx)
            }

            rawBacking = ByteArray(allRaw.sumOf { it.size }).also { out ->
                var pos = 0
                for (arr in allRaw) {
                    arr.copyInto(out, pos)
                    pos += arr.size
                }
            }
            originalBackingSnapshot = rawBacking.copyOf()
            expectedSlice = ByteArray(expected.sumOf { it.size }).also { out ->
                var pos = 0
                for (arr in expected) {
                    arr.copyInto(out, pos)
                    pos += arr.size
                }
            }
        }
    }

    private class ArrayByteSource(
        private val data: ByteArray,
        private val maxChunkPerRead: Int = Int.MAX_VALUE,
    ) : ProxyByteSource {
        val concurrencyViolations = AtomicInteger(0)
        override val size: Long get() = data.size.toLong()

        override suspend fun openReader(): ProxyReader = object : ProxyReader {
            private val active = AtomicInteger(0)
            override var position: Long = 0L
                private set

            override suspend fun seekTo(position: Long) {
                if (active.incrementAndGet() > 1) concurrencyViolations.incrementAndGet()
                try {
                    this.position = position
                } finally {
                    active.decrementAndGet()
                }
            }

            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (active.incrementAndGet() > 1) concurrencyViolations.incrementAndGet()
                try {
                    if (position >= data.size) return -1
                    if (length == 0) return 0
                    val count = minOf(
                        length.toLong(),
                        data.size - position,
                        maxChunkPerRead.toLong(),
                    ).toInt()
                    data.copyInto(buffer, destinationOffset = offset, startIndex = position.toInt(), endIndex = (position + count).toInt())
                    position += count
                    return count
                } finally {
                    active.decrementAndGet()
                }
            }

            override fun close() = Unit
        }

        override fun close() = Unit
    }

    private suspend fun readEntireSliceInChunks(source: ProxyByteSource, chunkSizes: Iterator<Int>): ByteArray {
        val out = ByteArray(source.size.toInt())
        source.openReader().use { reader ->
            assertEquals(0L, reader.position)
            var written = 0
            val scratch = ByteArray(source.size.toInt() + 32)
            while (true) {
                val req = chunkSizes.next().coerceAtLeast(1)
                val off = (written * 7) % 13
                val n = reader.read(scratch, off, minOf(req, scratch.size - off))
                if (n < 0) break
                scratch.copyInto(out, destinationOffset = written, startIndex = off, endIndex = off + n)
                written += n
                assertEquals(written.toLong(), reader.position)
            }
            assertEquals(source.size.toInt(), written)
            assertEquals(-1, reader.read(scratch, 0, 16))
        }
        return out
    }

    @Test
    fun `2 plus 186 byte split and all fixed chunk sizes match whole-slice masked view byte-for-byte`() = runBlocking {
        val fx = Fixture()
        val inner = ArrayByteSource(fx.rawBacking)
        fun newColdSliced() = SlicedByteSource(
            inner = inner,
            offset = fx.sliceOffset,
            size = fx.sliceSize,
            nullVideoBeforeOffset = fx.maskLimit,
            nullVideoPid = VIDEO_PID,
        )

        // Whole slice at once (cold)
        val whole = readEntireSliceInChunks(newColdSliced(), generateSequence { 64 * 1024 }.iterator())
        assertContentEquals(fx.expectedSlice, whole)

        // Classic 2 + 186 split across every 188B TS packet header on a fresh cold instance!
        val twoThen186 = sequence {
            while (true) {
                yield(2)
                yield(186)
            }
        }
        assertContentEquals(fx.expectedSlice, readEntireSliceInChunks(newColdSliced(), twoThen186.iterator()))

        // 1 + 1 + 1 + 185 split across every 188B TS packet header on a fresh cold instance!
        val singleHeaderBytes = sequence {
            while (true) {
                yield(1); yield(1); yield(1); yield(185)
            }
        }
        assertContentEquals(fx.expectedSlice, readEntireSliceInChunks(newColdSliced(), singleHeaderBytes.iterator()))

        // Exhaustive fixed chunk sizes including 1..7, primes, and 188 +/- 1 — each on its own cold SlicedByteSource
        for (chunk in listOf(1, 2, 3, 4, 5, 6, 7, 13, 31, 64, 185, 186, 187, 188, 189, 190, 191, 375, 376, 377, 512)) {
            val actual = readEntireSliceInChunks(newColdSliced(), generateSequence { chunk }.iterator())
            assertContentEquals(fx.expectedSlice, actual, "Mismatch for caller chunk size=$chunk")
        }

        // Underlying backing array must NEVER be mutated
        assertContentEquals(fx.originalBackingSnapshot, fx.rawBacking)
        assertFalse(fx.rawBacking.sliceArray(fx.sliceOffset.toInt() until (fx.sliceOffset + fx.sliceSize).toInt()).contentEquals(fx.expectedSlice))
        assertEquals(0, inner.concurrencyViolations.get())
    }

    @Test
    fun `partial upstream chunks across TS headers produce identical bytes`() = runBlocking {
        val fx = Fixture()
        for (upstreamChunk in listOf(1, 2, 3, 5, 17, 187, 189, 190)) {
            val inner = ArrayByteSource(fx.rawBacking, maxChunkPerRead = upstreamChunk)
            for (callerChunk in listOf(1, 2, 3, 7, 188, 190, 1024)) {
                val coldSliced = SlicedByteSource(
                    inner = inner,
                    offset = fx.sliceOffset,
                    size = fx.sliceSize,
                    nullVideoBeforeOffset = fx.maskLimit,
                    nullVideoPid = VIDEO_PID,
                )
                val actual = readEntireSliceInChunks(coldSliced, generateSequence { callerChunk }.iterator())
                assertContentEquals(
                    fx.expectedSlice,
                    actual,
                    "Mismatch for upstreamChunk=$upstreamChunk, callerChunk=$callerChunk",
                )
            }
            assertContentEquals(fx.originalBackingSnapshot, fx.rawBacking)
            assertEquals(0, inner.concurrencyViolations.get())
        }
    }

    @Test
    fun `cold and warm random seekTo at arbitrary mid-packet and header offsets match expected bytes`() = runBlocking {
        val fx = Fixture()
        val inner = ArrayByteSource(fx.rawBacking, maxChunkPerRead = 7)
        val rng = Random(0x51CE_2026L)

        // 1. Cold seeks on brand-new SlicedByteSource instances (uncached packet decisions!)
        val criticalPositions = buildList {
            for (pkt in 0 until fx.totalSlicePackets) {
                val base = pkt * TS_PACKET
                add(base)
                add(base + 1)
                add(base + 2)
                add(base + 3)
                add(base + 4)
                add(base + 93)
                add(base + 187)
            }
        }
        for (startPos in criticalPositions) {
            for (len in listOf(1, 2, 3, 5, 190, 400)) {
                val fresh = SlicedByteSource(
                    inner = inner,
                    offset = fx.sliceOffset,
                    size = fx.sliceSize,
                    nullVideoBeforeOffset = fx.maskLimit,
                    nullVideoPid = VIDEO_PID,
                )
                fresh.openReader().use { reader ->
                    reader.seekTo(startPos.toLong())
                    assertEquals(startPos.toLong(), reader.position)
                    val want = minOf(len, fx.expectedSlice.size - startPos)
                    val got = ByteArray(want)
                    var filled = 0
                    while (filled < want) {
                        val n = reader.read(got, filled, want - filled)
                        check(n > 0) { "Unexpected EOF at $startPos+$filled" }
                        filled += n
                    }
                    assertContentEquals(
                        fx.expectedSlice.copyOfRange(startPos, startPos + want),
                        got,
                        "Cold seek mismatch at startPos=$startPos, len=$len",
                    )
                }
            }
        }

        // 2. Interleaved 1-byte read + seek inside/beyond peeked header + random jumps on one shared SlicedByteSource
        val shared = SlicedByteSource(
            inner = inner,
            offset = fx.sliceOffset,
            size = fx.sliceSize,
            nullVideoBeforeOffset = fx.maskLimit,
            nullVideoPid = VIDEO_PID,
        )
        shared.openReader().use { reader ->
            val buf = ByteArray(512)
            // Read 1 byte at start of masked video packet #2 (triggers 2-byte header lookahead), then seek to +1, +2, +3, +0
            val videoPktStart = 2L * TS_PACKET
            for (jumpDelta in listOf(1L, 2L, 3L, 0L, 187L)) {
                reader.seekTo(videoPktStart)
                assertEquals(1, reader.read(buf, 0, 1))
                assertEquals(fx.expectedSlice[videoPktStart.toInt()], buf[0])
                val target = videoPktStart + jumpDelta
                reader.seekTo(target)
                assertEquals(target, reader.position)
                val len = 12
                var filled = 0
                while (filled < len) {
                    val n = reader.read(buf, filled, len - filled)
                    check(n > 0)
                    filled += n
                }
                assertContentEquals(
                    fx.expectedSlice.copyOfRange(target.toInt(), target.toInt() + len),
                    buf.copyOfRange(0, len),
                    "Peek-window seek mismatch for jumpDelta=$jumpDelta",
                )
            }

            repeat(300) { iter ->
                val start = rng.nextInt(fx.expectedSlice.size)
                val len = rng.nextInt(1, minOf(600, fx.expectedSlice.size - start) + 1)
                reader.seekTo(start.toLong())
                val out = ByteArray(len)
                var filled = 0
                while (filled < len) {
                    val req = rng.nextInt(1, len - filled + 1)
                    val n = reader.read(out, filled, req)
                    check(n > 0)
                    filled += n
                }
                assertContentEquals(
                    fx.expectedSlice.copyOfRange(start, start + len),
                    out,
                    "Random seek mismatch at iter=$iter, start=$start, len=$len",
                )
            }
        }
        assertContentEquals(fx.originalBackingSnapshot, fx.rawBacking)
        assertEquals(0, inner.concurrencyViolations.get())
    }

    @Test
    fun `concurrent readers and loopback HTTP split Range requests return deterministic masked bytes`() = runBlocking {
        val fx = Fixture()
        val inner = ArrayByteSource(fx.rawBacking, maxChunkPerRead = 11)
        val sliced = SlicedByteSource(
            inner = inner,
            offset = fx.sliceOffset,
            size = fx.sliceSize,
            nullVideoBeforeOffset = fx.maskLimit,
            nullVideoPid = VIDEO_PID,
        )

        // 8 concurrent readers with randomized chunk sizes & seeks
        coroutineScope {
            (0 until 8).map { workerId ->
                async(Dispatchers.Default) {
                    val rng = Random(1000L + workerId)
                    val full = readEntireSliceInChunks(sliced, generateSequence { rng.nextInt(1, 25) }.iterator())
                    assertContentEquals(fx.expectedSlice, full, "Worker $workerId full read mismatch")

                    sliced.openReader().use { reader ->
                        repeat(60) {
                            val start = rng.nextInt(fx.expectedSlice.size)
                            val len = rng.nextInt(1, minOf(400, fx.expectedSlice.size - start) + 1)
                            reader.seekTo(start.toLong())
                            val got = ByteArray(len)
                            var filled = 0
                            while (filled < len) {
                                val n = reader.read(got, filled, rng.nextInt(1, len - filled + 1))
                                check(n > 0)
                                filled += n
                            }
                            assertContentEquals(fx.expectedSlice.copyOfRange(start, start + len), got)
                        }
                    }
                }
            }.awaitAll()
        }

        // Loopback HTTP Range requests splitting right across masked packet #2 (bytes 376..563)
        PikoMediaProxy().use { proxy ->
            val stream = proxy.register(sliced, "clip.ts")
            val p2 = 2 * TS_PACKET
            val firstTwo = request(stream.url, range = "bytes=$p2-${p2 + 1}")
            val restOfPacket = request(stream.url, range = "bytes=${p2 + 2}-${p2 + TS_PACKET - 1}")
            assertEquals(206, firstTwo.status)
            assertEquals(206, restOfPacket.status)
            assertContentEquals(fx.expectedSlice.copyOfRange(p2, p2 + 2), firstTwo.body)
            assertContentEquals(fx.expectedSlice.copyOfRange(p2 + 2, p2 + TS_PACKET), restOfPacket.body)

            // Audio packet #3 sharing upper PID bits must remain unmasked over HTTP Range split too
            val p3 = 3 * TS_PACKET
            val audioTwo = request(stream.url, range = "bytes=$p3-${p3 + 1}")
            val audioRest = request(stream.url, range = "bytes=${p3 + 2}-${p3 + TS_PACKET - 1}")
            assertContentEquals(fx.expectedSlice.copyOfRange(p3, p3 + 2), audioTwo.body)
            assertContentEquals(fx.expectedSlice.copyOfRange(p3 + 2, p3 + TS_PACKET), audioRest.body)
        }

        assertContentEquals(fx.originalBackingSnapshot, fx.rawBacking)
        assertEquals(0, inner.concurrencyViolations.get())
    }

    @Test
    fun `beginRead tickets respect bounded endExclusive inside TS headers and mid-packet startOffsets`() = runBlocking {
        val fx = Fixture()
        val inner = ArrayByteSource(fx.rawBacking, maxChunkPerRead = 3)
        val sliced = SlicedByteSource(
            inner = inner,
            offset = fx.sliceOffset,
            size = fx.sliceSize,
            nullVideoBeforeOffset = fx.maskLimit,
            nullVideoPid = VIDEO_PID,
        )
        val owner = io.github.nihildigit.pikpak.TransferDemand()
        try {
            // 1. Read entire slice through beginRead with 2 + 186 byte readSome chunks
            val ticketAll = sliced.beginRead(owner, 0L, fx.sliceSize)
            val fullOut = ByteArray(fx.sliceSize.toInt())
            var pos = 0
            var toggle = true
            while (pos < fullOut.size) {
                val req = if (toggle) 2 else 186
                toggle = !toggle
                val got = ticketAll.readSome(fullOut, pos, minOf(req, fullOut.size - pos))
                check(got > 0)
                pos += got
                assertEquals(pos.toLong(), ticketAll.position)
            }
            assertEquals(-1, ticketAll.readSome(ByteArray(8), 0, 8))
            ticketAll.endRead()
            assertContentEquals(fx.expectedSlice, fullOut)

            // 2. Bounded ticket ending at byte 2 of masked video packet #2 (endExclusive < pktStart + 3)
            val p2 = 2L * TS_PACKET
            val headerSplit = sliced.beginRead(owner, p2, p2 + 2L)
            val twoBytes = ByteArray(2)
            var filled = 0
            while (filled < 2) {
                val n = headerSplit.readSome(twoBytes, filled, 2 - filled)
                check(n > 0)
                filled += n
            }
            assertEquals(-1, headerSplit.readSome(ByteArray(4), 0, 4))
            headerSplit.endRead(allowFinishInFlight = false)
            assertContentEquals(fx.expectedSlice.copyOfRange(p2.toInt(), p2.toInt() + 2), twoBytes)

            // 3. Mid-packet ticket starting at byte +1 and byte +77 of masked video packet #4 on a fresh cold slice
            val coldSliced = SlicedByteSource(
                inner = inner,
                offset = fx.sliceOffset,
                size = fx.sliceSize,
                nullVideoBeforeOffset = fx.maskLimit,
                nullVideoPid = VIDEO_PID,
            )
            for (subOffset in listOf(1L, 2L, 3L, 77L, 187L)) {
                val start = 4L * TS_PACKET + subOffset
                val end = minOf(fx.sliceSize, start + 195L)
                val ticket = coldSliced.beginRead(owner, start, end)
                val out = ByteArray((end - start).toInt())
                var read = 0
                while (read < out.size) {
                    val n = ticket.readSome(out, read, minOf(5, out.size - read))
                    check(n > 0)
                    read += n
                }
                assertEquals(-1, ticket.readSome(ByteArray(4), 0, 4))
                ticket.endRead()
                assertContentEquals(fx.expectedSlice.copyOfRange(start.toInt(), end.toInt()), out)
            }
        } finally {
            owner.close()
        }
        assertContentEquals(fx.originalBackingSnapshot, fx.rawBacking)
        assertEquals(0, inner.concurrencyViolations.get())
    }
}
