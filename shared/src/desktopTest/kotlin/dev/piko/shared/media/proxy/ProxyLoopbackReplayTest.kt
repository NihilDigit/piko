package dev.piko.shared.media.proxy

import dev.piko.download.DownloadStatus
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.media.testing.FakePikPakCloud
import dev.piko.shared.media.testing.openRequest
import dev.piko.shared.media.testing.request
import dev.piko.shared.smoke.DirectoryStorage
import dev.piko.shared.smoke.MemoryPreferences
import dev.piko.shared.smoke.awaitUntil
import dev.piko.shared.smoke.smoke
import io.github.nihildigit.pikpak.BufferBudget
import io.github.nihildigit.pikpak.ConsumerIntent
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakFileHandle
import io.github.nihildigit.pikpak.PikPakStreamReader
import io.github.nihildigit.pikpak.ServiceIntent
import io.github.nihildigit.pikpak.TransferDemand
import io.github.nihildigit.pikpak.fileCache
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.availableForRead
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 端到端回环 HTTP 协议回放测试（`PikoMediaProxy` + `ProxySession` + `PikPakByteSource` + `PikPakFileCache` + 真实 TCP socket）：
 * 覆盖慢速小分片远端（4 KiB/chunk）、客户端读数 KiB 后关闭 socket、交错双偏移短 Range 请求与前缀复用、
 * 首尾探测后跳读续播、hr-seek epoch 抢占、socket 背压与断连回收、单工作槽 FinishInFlight 抢占，以及共享缓存池撤回隔离。
 */
class ProxyLoopbackReplayTest {
    private val proxy = PikoMediaProxy()

    @AfterTest
    fun tearDown() = proxy.close()

    @Test
    fun slowChunkedOpenRangeShortReadAndInterleavedMp4ShortRangesReusePrefixWithoutRunawaySpeculation() = runBlocking {
        val payload = FakePikPakCloud.payload(16 * 1024 * 1024)
        val allowBlock0Past32K = CompletableDeferred<Unit>()
        val harness = ChunkedPikPakHarness(
            payload = payload,
            chunkBytes = 4 * 1024,
            chunkDelayMillis = 2,
            connectionBudget = 4,
            beforeChunk = { offset ->
                if (offset == 32L * 1024L) allowBlock0Past32K.await()
            },
        )
        val demand = harness.client.openTransferDemand(
            ConsumerIntent(
                epoch = 0L,
                service = ServiceIntent.WAITING,
                bufferBudget = BufferBudget(maxReadAheadBytes = 2L * 1024 * 1024),
            ),
        )
        val stream = proxy.register(harness.byteSource, "interleaved.mp4", demand)
        try {
            // 1. 客户端打开无上界 Range: bytes=0-，仅读 8 KiB 后立即关闭 socket（复现 libavformat 初探后断连）
            val probe = openRequest(stream.url, range = "bytes=0-")
            assertEquals(206, probe.status)
            val first8K = probe.body.readNBytes(8 * 1024)
            assertContentEquals(payload.copyOfRange(0, 8 * 1024), first8K)
            probe.socket.close()
            delay(30)
            allowBlock0Past32K.complete(Unit)

            // 等待已启动的单个 256 KiB 块作为 FinishInFlight 收尾或稳定，绝不能向外派发第 1 块及后续块
            awaitUntil("首块收尾完成且无在途请求") { harness.activeCdnRequests.get() == 0 }
            delay(80)
            assertEquals(1, harness.cdnRanges.count { it.first == 0L }, "首块只应请求一次: ${harness.cdnRanges}")
            assertTrue(
                harness.cdnRanges.none { it.first >= PikPakStreamReader.DEFAULT_BLOCK_SIZE },
                "短读断连绝不能触发跨块投机预读: ${harness.cdnRanges}",
            )
            assertTrue(
                harness.deliveredCdnBytes.get() <= PikPakStreamReader.DEFAULT_BLOCK_SIZE,
                "delivered=${harness.deliveredCdnBytes.get()}",
            )

            // 2. 交错双偏移 MP4 短 Range 请求（音轨 0 附近 + 视轨 8 MiB 附近）：并发与交替请求互不掐断，且复用已到达/已缓存前缀
            val vBase = 8 * 1024 * 1024
            val firstAudio = async(Dispatchers.IO) { request(stream.url, range = "bytes=0-8191") }
            val firstVideo = async(Dispatchers.IO) { request(stream.url, range = "bytes=$vBase-${vBase + 8191}") }
            val rAudio0 = withTimeout(10_000) { firstAudio.await() }
            val rVideo0 = withTimeout(10_000) { firstVideo.await() }
            assertEquals(206, rAudio0.status)
            assertEquals(206, rVideo0.status)
            assertContentEquals(payload.copyOfRange(0, 8192), rAudio0.body)
            assertContentEquals(payload.copyOfRange(vBase, vBase + 8192), rVideo0.body)

            val rAudio1 = withTimeout(10_000) { request(stream.url, range = "bytes=8192-16383") }
            val rVideo1 = withTimeout(10_000) { request(stream.url, range = "bytes=${vBase + 8192}-${vBase + 16383}") }
            assertEquals(206, rAudio1.status)
            assertEquals(206, rVideo1.status)
            assertContentEquals(payload.copyOfRange(8192, 16384), rAudio1.body)
            assertContentEquals(payload.copyOfRange(vBase + 8192, vBase + 16384), rVideo1.body)

            awaitUntil("交错短请求收尾") { harness.activeCdnRequests.get() == 0 }
            assertEquals(1, harness.cdnRanges.count { it.first == 0L }, "块 0 前缀必须被复用，不得重复拉取: ${harness.cdnRanges}")
            assertEquals(1, harness.cdnRanges.count { it.first == vBase.toLong() }, "8 MiB 处首块前缀必须被复用: ${harness.cdnRanges}")
            assertEquals(2, harness.cdnRanges.size, "全程仅允许块 0 与 8 MiB 两块网络请求: ${harness.cdnRanges}")
        } finally {
            allowBlock0Past32K.complete(Unit)
            stream.close()
            harness.close()
        }
    }

    @Test
    fun headTailProbesResumeLookAheadAndHrSeekEpochPreemption() = runBlocking {
        val size = 16 * 1024 * 1024
        val payload = FakePikPakCloud.payload(size)
        val blockBytes = PikPakStreamReader.DEFAULT_BLOCK_SIZE
        val resumeOffset = 4 * 1024 * 1024
        val seekOffset = 12 * 1024 * 1024
        val stallLookAheadGate = CompletableDeferred<Unit>()
        val lookAheadEntered = CompletableDeferred<Unit>()

        val harness = ChunkedPikPakHarness(
            payload = payload,
            chunkBytes = 4 * 1024,
            connectionBudget = 3,
            beforeChunk = { offset ->
                // 挂起续播阶段的第 3 个块（4.5 MiB），用来验证 hr-seek 推进 epoch 时 0ms 撤销旧投机请求
                if (offset == resumeOffset.toLong() + 2L * blockBytes) {
                    lookAheadEntered.complete(Unit)
                    stallLookAheadGate.await()
                }
            },
        )
        val demand = harness.client.openTransferDemand(
            ConsumerIntent(
                epoch = 0L,
                service = ServiceIntent.WAITING,
                bufferBudget = BufferBudget(maxReadAheadBytes = 512L * 1024),
            ),
        )
        val stream = proxy.register(harness.byteSource, "movie.mp4", demand)
        try {
            // 1. 有界头部探测 + 尾部 moov 索引探测：各自严格受 endExclusive 钳制，绝不外溢多块投机
            val headProbe = withTimeout(10_000) { request(stream.url, range = "bytes=0-4095") }
            val tailStart = size - 65_536
            val tailProbe = withTimeout(10_000) { request(stream.url, range = "bytes=$tailStart-${size - 1}") }
            assertContentEquals(payload.copyOfRange(0, 4096), headProbe.body)
            assertContentEquals(payload.copyOfRange(tailStart, size), tailProbe.body)
            awaitUntil("首尾有界探测收尾") { harness.activeCdnRequests.get() == 0 }
            assertEquals(2, harness.cdnRanges.size, "有界首尾探测仅允许各触发 1 个块: ${harness.cdnRanges}")

            // 2. 切入 PLAYING 并在 4 MiB 发起流式续播：跨过首块边界（300 KiB > 256 KiB）后触发受控 LookAhead
            demand.submitIntent(
                ConsumerIntent(
                    epoch = 1L,
                    service = ServiceIntent.PLAYING,
                    bufferBudget = BufferBudget(maxReadAheadBytes = 512L * 1024),
                ),
            )
            val resumeConn = openRequest(stream.url, range = "bytes=$resumeOffset-")
            val resumedBytes = withTimeout(10_000) { resumeConn.body.readNBytes(300_000) }
            assertContentEquals(payload.copyOfRange(resumeOffset, resumeOffset + 300_000), resumedBytes)
            withTimeout(10_000) { lookAheadEntered.await() }
            val tailBlockFloor = (tailStart.toLong() / blockBytes) * blockBytes
            assertTrue(
                harness.cdnRanges.none { it.first in (resumeOffset + 5L * blockBytes) until tailBlockFloor },
                "续播预读不得越过 BufferBudget 边界: ${harness.cdnRanges}",
            )

            // 3. hr-seek 推进 epoch=2 并跳至 12 MiB：旧 LookAhead 立即被取消，无需解开 stallLookAheadGate 即可秒回新落点
            demand.submitIntent(
                ConsumerIntent(
                    epoch = 2L,
                    service = ServiceIntent.WAITING,
                    bufferBudget = BufferBudget(maxReadAheadBytes = 512L * 1024),
                ),
            )
            resumeConn.socket.close()
            awaitUntil("epoch 推进立即取消挂起的旧 LookAhead") { harness.activeCdnRequests.get() == 0 }

            val afterSeek = withTimeout(10_000) { request(stream.url, range = "bytes=$seekOffset-${seekOffset + 8191}") }
            assertEquals(206, afterSeek.status)
            assertContentEquals(payload.copyOfRange(seekOffset, seekOffset + 8192), afterSeek.body)

            // 4. 先前 4 MiB 已落入缓存的块保持完好，回读不产生额外 CDN 请求
            val resumeBlockCallsBefore = harness.cdnRanges.count { it.first == resumeOffset.toLong() }
            val cachedCheck = withTimeout(10_000) { request(stream.url, range = "bytes=$resumeOffset-${resumeOffset + 4095}") }
            assertContentEquals(payload.copyOfRange(resumeOffset, resumeOffset + 4096), cachedCheck.body)
            assertEquals(resumeBlockCallsBefore, harness.cdnRanges.count { it.first == resumeOffset.toLong() })
        } finally {
            stallLookAheadGate.complete(Unit)
            stream.close()
            harness.close()
        }
    }

    @Test
    fun socketBackpressureSuspendsProxyWithoutRunawayAndDisconnectCleansUpPromptly() = runBlocking {
        val payload = FakePikPakCloud.payload(16 * 1024 * 1024)
        val harness = ChunkedPikPakHarness(
            payload = payload,
            chunkBytes = 4 * 1024,
            chunkDelayMillis = 1,
            connectionBudget = 4,
        )
        val demand = harness.client.openTransferDemand(
            ConsumerIntent(
                epoch = 0L,
                service = ServiceIntent.PLAYING,
                bufferBudget = BufferBudget(maxReadAheadBytes = 512L * 1024),
            ),
        )
        val stream = proxy.register(harness.byteSource, "backpressure.mp4", demand)
        val open = openRequest(stream.url, range = "bytes=0-12582911", receiveBufferSize = 16 * 1024)
        try {
            val initialRead = 300_000
            val firstChunk = withTimeout(10_000) { open.body.readNBytes(initialRead) }
            assertContentEquals(payload.copyOfRange(0, initialRead), firstChunk)

            // 1. 客户端暂停读取 socket：等待套接口发送缓冲与 512 KiB 预读窗口真正进入零窗口稳态平台
            var plateauBytes = 0L
            withTimeout(10_000) {
                while (true) {
                    val snap = harness.deliveredCdnBytes.get()
                    if (snap > initialRead && harness.activeCdnRequests.get() == 0) {
                        delay(100)
                        if (harness.activeCdnRequests.get() == 0 && harness.deliveredCdnBytes.get() == snap) {
                            plateauBytes = snap
                            break
                        }
                    } else {
                        delay(20)
                    }
                }
            }
            assertTrue(
                plateauBytes < 8L * 1024 * 1024,
                "socket 背压下 CDN 流量失控: plateauBytes=$plateauBytes",
            )
            delay(200)
            assertEquals(0, harness.activeCdnRequests.get())
            assertEquals(
                plateauBytes,
                harness.deliveredCdnBytes.get(),
                "客户端暂停读 socket 期间严禁继续产生任何额外 CDN 字节",
            )

            // 2. 客户端恢复读取跨过已缓冲边界后，上游流控自动恢复推进且字节严格一致
            val resumeReadLen = (plateauBytes - initialRead + 512L * 1024L).toInt()
            val resumedBytes = withTimeout(10_000) { open.body.readNBytes(resumeReadLen) }
            assertContentEquals(payload.copyOfRange(initialRead, initialRead + resumeReadLen), resumedBytes)
            assertTrue(
                harness.deliveredCdnBytes.get() > plateauBytes,
                "恢复读取 socket 后应继续推进网络拉取: before=$plateauBytes, after=${harness.deliveredCdnBytes.get()}",
            )
            open.socket.close()
            awaitUntil("首条流关闭后在途请求排空") { harness.activeCdnRequests.get() == 0 }

            // 3. 不手动调用 withdrawSpeculation()，仅在未缓存偏移（12 MiB）读 16 KiB 后直接断开 TCP socket：
            //    ReadTicket 随响应协程取消立即结束，仅允许当前单块收尾，绝不向后续块失控投机
            val uncachedOffset = 12 * 1024 * 1024
            val dropped = openRequest(stream.url, range = "bytes=$uncachedOffset-")
            try {
                val shortBytes = withTimeout(10_000) { dropped.body.readNBytes(16 * 1024) }
                assertContentEquals(payload.copyOfRange(uncachedOffset, uncachedOffset + 16 * 1024), shortBytes)
            } finally {
                dropped.socket.close()
            }
            awaitUntil("纯 TCP 断连后在途 CDN 请求自动归零") { harness.activeCdnRequests.get() == 0 }
            delay(80)
            assertTrue(
                harness.cdnRanges.none { it.first >= uncachedOffset.toLong() + 2L * PikPakStreamReader.DEFAULT_BLOCK_SIZE },
                "纯 TCP 短读断连后不得继续向远端派发多块预读: ${harness.cdnRanges}",
            )

            val verify = withTimeout(10_000) { request(stream.url, range = "bytes=0-4095") }
            assertEquals(206, verify.status)
            assertContentEquals(payload.copyOfRange(0, 4096), verify.body)
        } finally {
            runCatching { open.socket.close() }
            stream.close()
            harness.close()
        }
    }

    @Test
    fun epochBumpKeepsOpen206StreamAliveWhileCancellingSpeculationAndWithdrawSeversCleanly() = runBlocking {
        val payload = FakePikPakCloud.payload(16 * 1024 * 1024)
        val blockBytes = PikPakStreamReader.DEFAULT_BLOCK_SIZE
        val oldOffset = 2L * 1024 * 1024
        val newEpochOffset = 10 * 1024 * 1024
        val withdrawPreHeaderOffset = 12 * 1024 * 1024
        val withdrawMidBodyOffset = 14 * 1024 * 1024

        val block9ParkedAt64K = CompletableDeferred<Unit>()
        val holdBlock9At64K = CompletableDeferred<Unit>()
        val lookAheadEntered = CompletableDeferred<Unit>()
        val lookAheadGate = CompletableDeferred<Unit>()
        val staleBlock10ParkedAt64K = CompletableDeferred<Unit>()
        val staleBlock10TailGate = CompletableDeferred<Unit>()
        val preHeaderEntered = CompletableDeferred<Unit>()
        val preHeaderGate = CompletableDeferred<Unit>()
        val midBodyParked = CompletableDeferred<Unit>()
        val midBodyGate = CompletableDeferred<Unit>()
        val speculativeInFlight = AtomicInteger(0)

        val harness = ChunkedPikPakHarness(
            payload = payload,
            chunkBytes = 4 * 1024,
            connectionBudget = 4,
            beforeChunk = { offset ->
                when {
                    offset == oldOffset + blockBytes + 64L * 1024L -> {
                        block9ParkedAt64K.complete(Unit)
                        holdBlock9At64K.await()
                    }
                    !lookAheadGate.isCompleted &&
                        offset >= oldOffset + 2L * blockBytes &&
                        offset < oldOffset + 5L * blockBytes -> {
                        speculativeInFlight.incrementAndGet()
                        try {
                            lookAheadEntered.complete(Unit)
                            lookAheadGate.await()
                        } finally {
                            speculativeInFlight.decrementAndGet()
                        }
                    }
                    offset == oldOffset + 2L * blockBytes + 64L * 1024L -> {
                        staleBlock10ParkedAt64K.complete(Unit)
                        staleBlock10TailGate.await()
                    }
                    offset == withdrawPreHeaderOffset.toLong() -> {
                        preHeaderEntered.complete(Unit)
                        preHeaderGate.await()
                    }
                    offset == withdrawMidBodyOffset.toLong() + 8192L -> {
                        midBodyParked.complete(Unit)
                        midBodyGate.await()
                    }
                }
            },
        )
        val demand = harness.client.openTransferDemand(
            ConsumerIntent(
                epoch = 0L,
                service = ServiceIntent.PLAYING,
                bufferBudget = BufferBudget(maxReadAheadBytes = 512L * 1024),
            ),
        )
        val stream = proxy.register(harness.byteSource, "epoch-preempt.mp4", demand)
        val uri = URI(stream.url)
        var oldConn: dev.piko.shared.media.testing.OpenResponse? = null
        var preHeaderSocket: Socket? = null
        var midBodyConn: dev.piko.shared.media.testing.OpenResponse? = null
        try {
            // 1. libmpv (reconnect=0) 在 epoch=0 建立开放 206 流并跨过首块边界，触发后续块的投机 LookAhead
            val openedOld = openRequest(stream.url, range = "bytes=$oldOffset-").also {
                it.socket.soTimeout = 5_000
                oldConn = it
            }
            assertEquals(206, openedOld.status)
            val initial300K = withTimeout(5_000) { withContext(Dispatchers.IO) { openedOld.body.readNBytes(300_000) } }
            assertContentEquals(payload.copyOfRange(oldOffset.toInt(), oldOffset.toInt() + 300_000), initial300K)
            withTimeout(5_000) {
                block9ParkedAt64K.await()
                lookAheadEntered.await()
            }
            assertTrue(speculativeInFlight.get() >= 1)

            // 2. 会话内 hr-seek 在客户端关旧 socket 之前抢先推进 epoch=1：
            //    0ms 撤销旧世代投机预读，但绝不能掐断在途 206 流（防止 libmpv Stream ends prematurely 卡死）
            demand.submitIntent(
                ConsumerIntent(
                    epoch = 1L,
                    service = ServiceIntent.WAITING,
                    bufferBudget = BufferBudget(maxReadAheadBytes = 512L * 1024),
                ),
            )
            awaitUntil("epoch 推进在 0ms 内取消旧世代投机预读") { speculativeInFlight.get() == 0 }
            assertFalse(lookAheadGate.isCompleted)
            assertEquals(1, harness.activeCdnRequests.get(), "旧连接当前正在读的单块 Required 不得被误杀")

            // 放行旧流继续跨块读取：旧 206 连接平滑送达后续字节（零提前 EOF），且跨入下一块时不再派生任何新 LookAhead
            lookAheadGate.complete(Unit)
            val lookAheadCountBeforeContinue = harness.cdnRanges.count {
                it.first in (oldOffset + 3L * blockBytes) until (oldOffset + 6L * blockBytes)
            }
            holdBlock9At64K.complete(Unit)
            val continued250K = withTimeout(5_000) { withContext(Dispatchers.IO) { openedOld.body.readNBytes(250_000) } }
            assertContentEquals(
                payload.copyOfRange(oldOffset.toInt() + 300_000, oldOffset.toInt() + 550_000),
                continued250K,
            )
            withTimeout(5_000) { staleBlock10ParkedAt64K.await() }
            delay(50)
            assertEquals(
                lookAheadCountBeforeContinue,
                harness.cdnRanges.count { it.first in (oldOffset + 3L * blockBytes) until (oldOffset + 6L * blockBytes) },
                "过期 epoch 的残余连接仅允许单块 Required，严禁再触发后续块 LookAhead: ${harness.cdnRanges}",
            )

            // 3. 新世代 hr-seek 落点请求并发秒回 206；随后客户端主动关闭旧 socket 时，allowFinishInFlight=false 立即取消旧尾块
            val fresh = withTimeout(5_000) {
                request(stream.url, range = "bytes=$newEpochOffset-${newEpochOffset + 8191}")
            }
            assertEquals(206, fresh.status)
            assertContentEquals(payload.copyOfRange(newEpochOffset, newEpochOffset + 8192), fresh.body)

            openedOld.socket.close()
            awaitUntil("旧世代客户端断开后不保留 FinishInFlight，在途尾块立即归零") {
                harness.activeCdnRequests.get() == 0
            }
            assertFalse(staleBlock10TailGate.isCompleted)
            assertFalse(stream.session.isWithdrawn)
            assertFalse(stream.session.isClosed)

            // 4. 真正的会话撤回 withdrawTransport()（owner.isClosed）则必须立即切断未发头与半包连接，且绝不回 502 Bad Gateway
            val rawPreHeader = Socket(uri.host, uri.port).apply {
                soTimeout = 5_000
                preHeaderSocket = this
            }
            val rawGet = buildString {
                append("GET ${uri.rawPath} HTTP/1.1\r\n")
                append("Host: ${uri.host}\r\n")
                append("Connection: close\r\n")
                append("Range: bytes=$withdrawPreHeaderOffset-${withdrawPreHeaderOffset + 65535}\r\n\r\n")
            }
            withContext(Dispatchers.IO) {
                rawPreHeader.getOutputStream().apply {
                    write(rawGet.toByteArray())
                    flush()
                }
            }
            withTimeout(5_000) { preHeaderEntered.await() }

            val openedMid = openRequest(
                stream.url,
                range = "bytes=$withdrawMidBodyOffset-${withdrawMidBodyOffset + 65535}",
            ).also {
                it.socket.soTimeout = 5_000
                midBodyConn = it
            }
            assertEquals(206, openedMid.status)
            val first8K = withTimeout(5_000) { withContext(Dispatchers.IO) { openedMid.body.readNBytes(8192) } }
            assertContentEquals(payload.copyOfRange(withdrawMidBodyOffset, withdrawMidBodyOffset + 8192), first8K)
            withTimeout(5_000) { midBodyParked.await() }

            stream.withdrawTransport()

            val preHeaderBytes = withTimeout(5_000) {
                withContext(Dispatchers.IO) { rawPreHeader.getInputStream().readAllBytes() }
            }
            val preHeaderText = preHeaderBytes.decodeToString()
            assertFalse("502" in preHeaderText, "withdrawTransport 切断未发头连接时绝不能回 502 Bad Gateway: $preHeaderText")
            assertEquals(0, preHeaderBytes.size, "withdrawTransport 未发头连接应直接干净 EOF 结束: $preHeaderText")

            val midBodyNext = withTimeout(5_000) {
                withContext(Dispatchers.IO) { openedMid.body.read() }
            }
            assertEquals(-1, midBodyNext, "withdrawTransport 半包连接应立即收到 EOF")
            awaitUntil("withdrawTransport 后所有在途 CDN 请求立即归零") { harness.activeCdnRequests.get() == 0 }
            assertEquals(200, request(stream.url, "HEAD").status)
            assertEquals(503, request(stream.url, range = "bytes=0-1023").status)
        } finally {
            holdBlock9At64K.complete(Unit)
            lookAheadGate.complete(Unit)
            staleBlock10TailGate.complete(Unit)
            preHeaderGate.complete(Unit)
            midBodyGate.complete(Unit)
            runCatching { oldConn?.socket?.close() }
            runCatching { preHeaderSocket?.close() }
            runCatching { midBodyConn?.socket?.close() }
            stream.close()
            harness.close()
        }
    }

    @Test
    fun singleWorkerRequiredRangePreemptsSlowFinishInFlightImmediatelyAndPreservesArrivedPrefix() = runBlocking {
        val payload = FakePikPakCloud.payload(8 * 1024 * 1024)
        val block0TailGate = CompletableDeferred<Unit>()
        val block0ParkedAt16K = CompletableDeferred<Unit>()

        // 单工作槽（connectionBudget = 1）：若 FinishInFlight 不支持 0ms 抢占，后续 4 MiB 请求会被死死卡在 block0TailGate 上
        val harness = ChunkedPikPakHarness(
            payload = payload,
            chunkBytes = 4 * 1024,
            connectionBudget = 1,
            beforeChunk = { offset ->
                if (offset == 16L * 1024L) {
                    block0ParkedAt16K.complete(Unit)
                    block0TailGate.await()
                }
            },
        )
        val demand = harness.client.openTransferDemand(
            ConsumerIntent(
                epoch = 0L,
                service = ServiceIntent.WAITING,
                bufferBudget = BufferBudget.NONE,
            ),
        )
        val stream = proxy.register(harness.byteSource, "single-worker.mp4", demand)
        try {
            val shortConn = openRequest(stream.url, range = "bytes=0-")
            val head8K = withTimeout(10_000) { shortConn.body.readNBytes(8 * 1024) }
            assertContentEquals(payload.copyOfRange(0, 8 * 1024), head8K)
            withTimeout(10_000) { block0ParkedAt16K.await() }
            shortConn.socket.close()

            // 块 0 转为低优先级 FinishInFlight 并停在 16 KiB 门控处；此时发起 4 MiB 必需读，必须立即抢占唯一工作槽完成响应！
            val targetOffset = 4 * 1024 * 1024
            val urgentRead = withTimeout(5_000) {
                request(stream.url, range = "bytes=$targetOffset-${targetOffset + 4095}")
            }
            assertEquals(206, urgentRead.status)
            assertContentEquals(payload.copyOfRange(targetOffset, targetOffset + 4096), urgentRead.body)

            // 块 0 被抢占前已收到的前 8 KiB 前缀留在 partials 中，仍可直接命中返回而无需解开 16 KiB 门控
            val cachedPrefix = withTimeout(5_000) { request(stream.url, range = "bytes=0-8191") }
            assertEquals(206, cachedPrefix.status)
            assertContentEquals(payload.copyOfRange(0, 8192), cachedPrefix.body)
        } finally {
            block0TailGate.complete(Unit)
            stream.close()
            harness.close()
        }
    }

    @Test
    fun sharedCachePoolWithdrawTransportCancelsOnlyPlaybackOwnerWhileDownloadCompletes() = smoke { scope ->
        val payload = FakePikPakCloud.payload(6 * 1024 * 1024 + 333)
        val cloud = FakePikPakCloud(origin = payload, blockDelayMillis = 8)
        val root = Files.createTempDirectory("piko-shared-withdraw").toFile()
        val repository = PikoMediaRepository(cloud.provider)
        val coordinator = PikoDownloadCoordinator(
            cloud.provider,
            MemoryPreferences(),
            DirectoryStorage(root),
            scope,
            mediaRepository = repository,
        )
        coordinator.enqueue(FileStat(id = "f1", name = "movie.mkv", size = payload.size.toString(), hash = "GCIDFAKE0001"))
        awaitUntil("后台下载开始拉取") { cloud.activeCdnRequests > 0 || cloud.deliveredCdnBytes.get() > 0L }

        val playback = repository.preparePlayback("f1").getOrThrow()
        try {
            val url = requireNotNull(playback.proxyUrl)
            playback.submitIntent(
                ConsumerIntent(
                    epoch = 1L,
                    service = ServiceIntent.PLAYING,
                    bufferBudget = playback.defaultBufferBudget,
                ),
            )
            val slice = withTimeout(10_000) { request(url, range = "bytes=0-16383") }
            assertEquals(206, slice.status)
            assertContentEquals(payload.copyOfRange(0, 16384), slice.body)

            // 撤回播放传输会话：代理 URL 对 GET 返回 503、HEAD 仍返回 200，且绝不能伤及共享同一缓存实例的后台下载
            playback.withdrawTransport()
            assertEquals(200, request(url, "HEAD").status)
            assertEquals(503, request(url, range = "bytes=0-1023").status)

            awaitUntil("共享缓存后台下载完整收尾", 25_000) {
                coordinator.tasks.value["f1"]?.status == DownloadStatus.COMPLETED
            }
            assertContentEquals(payload, root.resolve("movie.mkv").readBytes())

            playback.close()
            assertEquals(404, request(url, "HEAD").status)
        } finally {
            playback.close()
            cloud.provider.currentClient.value?.close()
        }
    }

    private class ChunkedPikPakHarness(
        private val payload: ByteArray,
        private val chunkBytes: Int = 4 * 1024,
        private val chunkDelayMillis: Long = 0L,
        connectionBudget: Int = 4,
        private val beforeChunk: (suspend (Long) -> Unit)? = null,
    ) : AutoCloseable {
        val cdnRanges = CopyOnWriteArrayList<LongRange>()
        val activeCdnRequests = AtomicInteger(0)
        val deliveredCdnBytes = AtomicLong(0L)

        private val streamScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val engine = MockEngine { req -> handleRequest(req) }

        val client = PikPakClient(
            account = "replay@example.com",
            password = "pw",
            sessionStore = InMemorySessionStore(),
            httpClient = HttpClient(engine),
            cdnHttpClient = HttpClient(engine),
            connectionBudget = connectionBudget,
        )

        val provider: PikoClientProvider = object : PikoClientProvider {
            override val currentClient: StateFlow<PikPakClient?> = MutableStateFlow(client)
        }

        private val handle = PikPakFileHandle(
            client = client,
            gcid = "GCIDREPLAY0001",
            size = payload.size.toLong(),
            name = "replay.mp4",
            initialFileId = "f1",
            connectionBudget = connectionBudget,
            streamSize = payload.size.toLong(),
        )

        private val cache = client.fileCache(
            source = handle,
            size = payload.size.toLong(),
            storeKey = handle.contentKey,
            connectionBudget = connectionBudget,
            coroutineContext = Dispatchers.IO,
        )

        val byteSource: ProxyByteSource = PikPakByteSource(handle, cache)

        private suspend fun MockRequestHandleScope.handleRequest(request: HttpRequestData): HttpResponseData {
            val path = request.url.encodedPath
            return when {
                request.url.host == CDN_HOST -> serveChunkedCdn(request)
                path.endsWith("/v1/shield/captcha/init") ->
                    json("""{"captcha_token":"C","expires_in":300,"url":""}""")
                path.endsWith("/v1/auth/signin") ->
                    json("""{"access_token":"AT","refresh_token":"RT","sub":"U","expires_in":3600}""")
                request.method == HttpMethod.Get && path.contains("/drive/v1/files/") -> {
                    val url = "https://$CDN_HOST/origin/f1"
                    json(
                        """{"kind":"drive#file","id":"f1","parent_id":"root","name":"replay.mp4","size":"${payload.size}",
                            "phase":"PHASE_TYPE_COMPLETE","hash":"GCIDREPLAY0001","mime_type":"video/mp4",
                            "links":{"application/octet-stream":{"url":"$url","expire":""}},
                            "medias":[{"media_id":"morigin","media_name":"Original","is_origin":true,
                                "video":{"width":1920,"height":1080,"duration":600,"video_type":"mov,mp4,m4a,3gp,3g2,mj2"},
                                "link":{"url":"$url","expire":""}}]}""",
                    )
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        private fun MockRequestHandleScope.serveChunkedCdn(request: HttpRequestData): HttpResponseData {
            val spec = request.headers[HttpHeaders.Range]?.removePrefix("bytes=")
            val start = spec?.substringBefore('-')?.toLong() ?: 0L
            val endInclusive = spec?.substringAfter('-')?.toLongOrNull()?.coerceAtMost(payload.size - 1L)
                ?: (payload.size - 1L)
            cdnRanges.add(start..endInclusive)
            val length = (endInclusive - start + 1L).toInt()
            val channel = ByteChannel(autoFlush = true)
            activeCdnRequests.incrementAndGet()
            val producerJob = streamScope.launch {
                try {
                    var offset = start.toInt()
                    val endExclusive = endInclusive.toInt() + 1
                    while (offset < endExclusive && !channel.isClosedForWrite && !channel.isClosedForRead) {
                        while (channel.availableForRead > 0 && !channel.isClosedForWrite && !channel.isClosedForRead) {
                            yield()
                        }
                        if (channel.isClosedForWrite || channel.isClosedForRead) break
                        beforeChunk?.invoke(offset.toLong())
                        if (chunkDelayMillis > 0L) delay(chunkDelayMillis)
                        if (channel.isClosedForWrite || channel.isClosedForRead) break
                        val n = minOf(chunkBytes, endExclusive - offset)
                        channel.writeFully(payload, offset, offset + n)
                        deliveredCdnBytes.addAndGet(n.toLong())
                        offset += n
                    }
                    channel.close()
                } catch (t: Throwable) {
                    channel.close(t)
                } finally {
                    activeCdnRequests.decrementAndGet()
                }
            }
            val completionHandle = request.executionContext.invokeOnCompletion { cause ->
                if (cause != null) {
                    channel.cancel(cause)
                    producerJob.cancel()
                }
            }
            producerJob.invokeOnCompletion { completionHandle.dispose() }
            streamScope.launch {
                while (producerJob.isActive && !channel.isClosedForWrite && !channel.isClosedForRead) {
                    delay(5)
                }
                if (producerJob.isActive && (channel.isClosedForWrite || channel.isClosedForRead)) {
                    producerJob.cancel()
                }
            }
            return respond(
                content = channel,
                status = HttpStatusCode.PartialContent,
                headers = headersOf(
                    HttpHeaders.ContentRange to listOf("bytes $start-$endInclusive/${payload.size}"),
                    HttpHeaders.ContentLength to listOf(length.toString()),
                    HttpHeaders.ContentType to listOf("application/octet-stream"),
                ),
            )
        }

        private fun MockRequestHandleScope.json(body: String) = respond(
            content = ByteReadChannel(body),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )

        override fun close() {
            runCatching { byteSource.close() }
            runCatching { client.close() }
            streamScope.cancel()
        }

        private companion object {
            const val CDN_HOST = "cdn.replay.test"
        }
    }
}
