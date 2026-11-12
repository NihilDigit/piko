package dev.piko.cli

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.media.FileClipCache
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.media.cache.PikoFileCachePool
import dev.piko.shared.media.player.ExternalSubtitle
import dev.piko.shared.media.player.MediaTrack
import dev.piko.shared.media.player.PlaybackBackend
import dev.piko.shared.media.player.PlaybackBackendEvent
import dev.piko.shared.media.player.PlaybackTarget
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.shared.media.player.PlayerScreenState
import dev.piko.shared.net.PikoProxySelector
import dev.piko.shared.net.ProxyMode
import dev.piko.shared.net.ProxyProtocol
import dev.piko.shared.net.ProxySetting
import dev.piko.shared.runtime.PikoWorkloadGovernor
import dev.piko.shared.state.Clip
import dev.piko.shared.state.ClipFeedSession
import dev.piko.shared.state.GallerySession
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.StreamRole
import io.github.nihildigit.pikpak.listFiles
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * HTTP 读取实测工具：复用呈现状态、代理、稀疏缓存池、瀑布流与信息流，使用模拟播放后端，
 * 通过本机代理端口（如 mihomo 节点端口）对 /Dramas 下的真实视频执行全场景联调测试。
 */
suspend fun runPlaybackBench(
    path: String,
    proxiesSpec: String,
    casesSpec: String,
    steadySeconds: Int,
    downloadSeconds: Int,
) {
    require(path.trim('/').split('/').firstOrNull() == "Dramas") {
        "playback-bench 仅允许使用 /Dramas 目录下的文件，当前路径：$path"
    }
    val nodes = proxiesSpec.split(',').mapNotNull { token ->
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        val eq = trimmed.indexOf('=')
        val name = if (eq > 0) trimmed.substring(0, eq).trim() else trimmed
        val hostPort = if (eq > 0) trimmed.substring(eq + 1).trim() else trimmed
        val colon = hostPort.lastIndexOf(':')
        require(colon > 0) { "无效的代理地址：$trimmed（格式应为 NAME=host:port 或 host:port）" }
        val host = hostPort.substring(0, colon)
        val port = hostPort.substring(colon + 1).toInt()
        ProxyNode(name, host, port)
    }
    require(nodes.isNotEmpty()) { "至少需要指定一个代理节点" }

    val requestedCases = casesSpec.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    val runAll = "all" in requestedCases || requestedCases.isEmpty()

    // 安装进程级代理选择器（与桌面端 Main.kt 一致，在一切 OkHttpClient 创建前安装）
    PikoProxySelector.install(
        ProxySetting(
            mode = ProxyMode.MANUAL,
            protocol = ProxyProtocol.HTTP,
            host = nodes.first().host,
            port = nodes.first().port,
        ),
    )
    val summaryRows = mutableListOf<NodeBenchSummary>()
    for (node in nodes) {
        println("\n================================================================================")
        println("▶ 节点 [${node.name}] (${node.host}:${node.port}) — 开始端到端实测 (路径: $path)")
        println("================================================================================")
        PikoProxySelector.apply(
            ProxySetting(
                mode = ProxyMode.MANUAL,
                protocol = ProxyProtocol.HTTP,
                host = node.host,
                port = node.port,
            ),
        )
        val summary = runNodeBench(node, path, runAll, requestedCases, steadySeconds, downloadSeconds)
        summaryRows += summary
    }

    println("\n================================================================================")
    println("★ 端到端全场景实测汇总")
    println("================================================================================")
    for (row in summaryRows) {
        println("• [${row.node.name} ${row.node.host}:${row.node.port}]")
        row.metrics.forEach { (k, v) -> println("    - $k: $v") }
    }
}

private data class ProxyNode(val name: String, val host: String, val port: Int)

private data class NodeBenchSummary(
    val node: ProxyNode,
    val metrics: LinkedHashMap<String, String>,
)

private suspend fun runNodeBench(
    node: ProxyNode,
    path: String,
    runAll: Boolean,
    requestedCases: Set<String>,
    steadySeconds: Int,
    downloadSeconds: Int,
): NodeBenchSummary {
    val metrics = LinkedHashMap<String, String>()
    val tempRoot = Files.createTempDirectory("piko-e2e-bench-${node.name}-").toFile()
    val fileCacheDir = File(tempRoot, "file-cache").apply { mkdirs() }
    val clipCacheDir = File(tempRoot, "clip-cache").apply { mkdirs() }
    val benchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val transportBudget = 256L * 1024 * 1024
    val client = appClient(transportMemoryBudgetBytes = transportBudget)
    val clientProvider = object : PikoClientProvider {
        private val flow = MutableStateFlow<PikPakClient?>(client)
        override val currentClient: StateFlow<PikPakClient?> = flow.asStateFlow()
    }
    val driveRepository = PikoDriveRepository(clientProvider)
    val fileCachePool = PikoFileCachePool(benchScope) { name -> File(fileCacheDir, name).absolutePath }
    val mediaRepository = PikoMediaRepository(
        clientManager = clientProvider,
        preferences = null,
        clipCache = FileClipCache(clipCacheDir),
    ).also {
        it.fileCachePool = fileCachePool
    }

    try {
        val clock = TimeSource.Monotonic
        val folderId = resolvePath(client, path)
        val rootEntries = driveRepository.listBrowsable(folderId, PikoFileSortOrder.NAME_ASC).getOrThrow()
        val directVideos = rootEntries.filter { !it.isFolder && it.sizeBytes > 50L * 1024 * 1024 }
            .sortedByDescending { it.sizeBytes }
        require(directVideos.isNotEmpty()) { "$path 下未找到可用于测试的视频文件" }
        val primaryVideo = directVideos.first()
        val secondaryVideo = directVideos.getOrElse(1) { primaryVideo }
        val primaryDurationMs = ((primaryVideo.params["duration"]?.toDoubleOrNull() ?: 1420.0) * 1000.0).toLong()
        println("  主测视频: ${primaryVideo.name} (${primaryVideo.sizeBytes / (1024 * 1024)} MiB, duration=${primaryDurationMs / 1000}s)")
        println("  副测视频: ${secondaryVideo.name} (${secondaryVideo.sizeBytes / (1024 * 1024)} MiB)")

        // Case 1: 冷启动播放 (Cold Start Playback)
        if (runAll || "cold" in requestedCases || "case1" in requestedCases) {
            println("\n  [Case 1] 冷启动播放 (Cold Start) -> ${primaryVideo.name}")
            val result = runPlayerScenario(
                mediaRepository = mediaRepository,
                fileCachePool = fileCachePool,
                file = primaryVideo,
                durationMs = primaryDurationMs,
                startMillis = 0L,
                steadySeconds = steadySeconds,
                seeksMillis = emptyList(),
            )
            val summary = "prepare=${result.prepareMs}ms, 首字节=${result.firstByteMs}ms, 读取就绪（模拟）(768KiB)=${result.readReadyMs}ms, " +
                "稳态吞吐=${"%.2f".format(result.steadyMiBs)} MiB/s (网络接收=${result.networkDeliveredMiB} MiB, 代理读取=${result.proxyReadMiB} MiB)"
            println("    ✓ $summary")
            metrics["Case 1 (冷启动)"] = summary
        }

        // Case 2: 从已有进度启动 (Resume from Saved Position)
        if (runAll || "resume" in requestedCases || "case2" in requestedCases) {
            val resumeMs = (primaryDurationMs * 0.42).toLong()
            println("\n  [Case 2] 从已有进度启动 (Resume @ ${resumeMs / 1000}s) -> ${primaryVideo.name}")
            val result = runPlayerScenario(
                mediaRepository = mediaRepository,
                fileCachePool = fileCachePool,
                file = primaryVideo,
                durationMs = primaryDurationMs,
                startMillis = resumeMs,
                steadySeconds = steadySeconds,
                seeksMillis = emptyList(),
            )
            val summary = "prepare=${result.prepareMs}ms, 首尾探测+定位首字节=${result.firstByteMs}ms, 定位读取就绪（模拟）(768KiB)=${result.readReadyMs}ms, " +
                "稳态吞吐=${"%.2f".format(result.steadyMiBs)} MiB/s (网络新增=${result.networkDeliveredMiB} MiB)"
            println("    ✓ $summary")
            metrics["Case 2 (进度续播)"] = summary
        }

        // Case 3: 交互式连续 Seek (Interactive Seek x3)
        if (runAll || "seek" in requestedCases || "case3" in requestedCases) {
            val seeks = listOf(
                (primaryDurationMs * 0.20).toLong(),
                (primaryDurationMs * 0.65).toLong(),
                (primaryDurationMs * 0.85).toLong(),
            )
            println("\n  [Case 3] 连续交互式 Seek (${seeks.joinToString(" -> ") { "${it / 1000}s" }}) -> ${primaryVideo.name}")
            val result = runPlayerScenario(
                mediaRepository = mediaRepository,
                fileCachePool = fileCachePool,
                file = primaryVideo,
                durationMs = primaryDurationMs,
                startMillis = 0L,
                steadySeconds = 3,
                seeksMillis = seeks,
            )
            val seekDetails = result.seekResults.joinToString("; ") { s ->
                "@${s.targetMs / 1000}s: 首字节=${s.firstByteMs}ms, 读取就绪（模拟）(512KiB)=${s.readyMs}ms, 吞吐=${"%.2f".format(s.postSeekMiBs)} MiB/s"
            }
            println("    ✓ $seekDetails")
            metrics["Case 3 (交互Seek)"] = seekDetails
        }

        // Case 4 & Case 5: 瀑布流 + 信息流，以及从信息流打开视频弹窗
        if (runAll || "clips" in requestedCases || "case4" in requestedCases || "popup" in requestedCases || "case5" in requestedCases) {
            println("\n  [Case 4] 瀑布流 (GallerySession) + 信息流 (ClipFeedSession) 并发打开与连刷 -> $path")
            val breadcrumbs = listOf(PikoPathBreadcrumb(folderId, path.trim('/')))
            val galleryScope = CoroutineScope(benchScope.coroutineContext + SupervisorJob())
            val clipScope = CoroutineScope(benchScope.coroutineContext + SupervisorJob())
            val gallery = GallerySession(driveRepository, galleryScope)
            val clipFeed = ClipFeedSession(
                clients = clientProvider,
                driveRepo = driveRepository,
                media = mediaRepository,
                cacheStore = null,
                scope = clipScope,
            )
            try {
                val case4Start = clock.markNow()
                gallery.open(breadcrumbs, showImages = true)
                clipFeed.open(breadcrumbs)

                // 等待瀑布流首批候选与信息流首段就绪，同时驱动 ClipStreams 预取循环（与 ClipFeedScreen LaunchedEffect 一致）
                var started = false
                val feederJob = clipScope.launch {
                    while (isActive) {
                        val ready = clipFeed.clips.size - 1 - clipFeed.furthestIndex
                        val prepareAhead = if (started) 6 else 1
                        val readyAhead = if (started) 2 else 1
                        val ahead = clipFeed.upcoming.take((prepareAhead - ready).coerceAtLeast(0))
                        val fetchCount = (readyAhead - ready).coerceAtLeast(0)
                        if (ahead.isNotEmpty()) {
                            clipFeed.streams.ripen(ahead.take(fetchCount))
                            ahead.forEach { clipFeed.streams.get(it) }
                        }
                        if (gallery.hasMore && !gallery.loading && gallery.files.size < 60) {
                            gallery.requestMore()
                        }
                        delay(100)
                    }
                }

                // 等待第一条 Clip 就绪
                withTimeout(30_000) {
                    while (clipFeed.clips.isEmpty()) {
                        delay(50)
                    }
                }
                val clip0PromotedMs = case4Start.elapsedNow().inWholeMilliseconds
                val clip0 = clipFeed.clips[0]
                val prep0Start = clock.markNow()
                val prepared0 = clipFeed.streams.get(clip0, forPlayer = true).await()
                    ?: error("Clip #0 prepare 返回 null")
                val clip0FrameMs = probeProxyUrlFirstBytes(prepared0.url!!, 512 * 1024)
                val clip0TotalMs = case4Start.elapsedNow().inWholeMilliseconds
                started = true
                clipFeed.streams.bringToFront(setOf(clip0) + clipFeed.clips.drop(1).take(1))

                // 等待第 2、3 条 Clip 就绪并模拟上滑切换
                withTimeoutOrNull(20_000) {
                    while (clipFeed.clips.size < 3) {
                        delay(50)
                    }
                }
                val clipSwitchMetrics = mutableListOf<String>()
                for (idx in 1 until minOf(3, clipFeed.clips.size)) {
                    val swStart = clock.markNow()
                    clipFeed.moveTo(idx)
                    val c = clipFeed.clips[idx]
                    clipFeed.streams.bringToFront(setOf(c) + listOfNotNull(clipFeed.clips.getOrNull(idx + 1)))
                    val prep = clipFeed.streams.get(c, forPlayer = true).await()
                    val prepUrl = prep?.url
                    val firstBytesMs = if (prepUrl != null) probeProxyUrlFirstBytes(prepUrl, 512 * 1024) else -1L
                    val totalSwitchMs = swStart.elapsedNow().inWholeMilliseconds
                    clipSwitchMetrics += "Clip#$idx(${if (prep?.sliced == true) "TS切片" else "原画"}): 切换耗时=${totalSwitchMs}ms(流读取=${firstBytesMs}ms)"
                }

                val galleryCount = gallery.files.size
                val case4Summary = "瀑布流已发现=${galleryCount}项, 首段提升=${clip0PromotedMs}ms, 首段出帧=${clip0TotalMs}ms(代理读512KiB=${clip0FrameMs}ms), " +
                    clipSwitchMetrics.joinToString(", ")
                println("    ✓ $case4Summary")
                metrics["Case 4 (瀑布流+信息流)"] = case4Summary

                // Case 5: 从信息流打开视频弹窗 (Open Video Popup from ClipFeed)
                if (runAll || "popup" in requestedCases || "case5" in requestedCases) {
                    val activeClipIndex = clipFeed.currentIndex.coerceIn(0, clipFeed.clips.lastIndex)
                    val activeClip: Clip = clipFeed.clips[activeClipIndex]
                    val activePrepared = clipFeed.streams.get(activeClip, forPlayer = true).await()
                    val popupStartMs = activePrepared?.clipStartMs ?: activeClip.startMs
                    val fullStat = clipFeed.listedFile(activeClip.fileId) ?: activeClip.file
                    val fullDurationMs = activeClip.videoDurationMs.takeIf { it > 0 } ?: 1420_000L
                    println("\n  [Case 5] 从信息流打开视频弹窗 (Popup Player from ClipFeed @ ${popupStartMs / 1000}s) -> ${activeClip.name}")

                    // 与 ClipFeedScreen.playFull 完全一致：先挂起 ClipStreams，再启动 PlayerScreenState
                    clipFeed.streams.setSuspended(true)
                    val popupResult = runPlayerScenario(
                        mediaRepository = mediaRepository,
                        fileCachePool = fileCachePool,
                        file = fullStat,
                        durationMs = fullDurationMs,
                        startMillis = popupStartMs,
                        steadySeconds = steadySeconds,
                        seeksMillis = emptyList(),
                    )
                    clipFeed.streams.setSuspended(false)
                    val case5Summary = "prepare=${popupResult.prepareMs}ms, 首字节=${popupResult.firstByteMs}ms, " +
                        "弹窗读取就绪（模拟）(768KiB)=${popupResult.readReadyMs}ms, 稳态吞吐=${"%.2f".format(popupResult.steadyMiBs)} MiB/s"
                    println("    ✓ $case5Summary")
                    metrics["Case 5 (信息流弹窗播放)"] = case5Summary
                }

                feederJob.cancelAndJoin()
            } finally {
                clipFeed.close()
                gallery.close()
                clipScope.cancel()
                galleryScope.cancel()
            }
        }

        // Case 6: 真实文件下载 (Sparse Cache Download)
        if (runAll || "download" in requestedCases || "case6" in requestedCases) {
            println("\n  [Case 6] 真实文件下载 (Sparse Cache Download, ${downloadSeconds}s) -> ${secondaryVideo.name}")
            val dlResult = runDownloadScenario(
                client = client,
                fileCachePool = fileCachePool,
                file = secondaryVideo,
                durationSeconds = downloadSeconds,
            )
            val summary = "吞吐=${"%.2f".format(dlResult.throughputMiBs)} MiB/s (${dlResult.deliveredMiB} MiB / ${dlResult.elapsedMs}ms)"
            println("    ✓ $summary")
            metrics["Case 6 (文件下载)"] = summary
        }
    } finally {
        benchScope.cancel()
        client.close()
        runCatching { tempRoot.deleteRecursively() }
    }
    return NodeBenchSummary(node, metrics)
}

private data class SeekBenchResult(
    val targetMs: Long,
    val firstByteMs: Long,
    val readyMs: Long,
    val postSeekMiBs: Double,
)

private data class PlayerScenarioResult(
    val prepareMs: Long,
    val firstByteMs: Long,
    val readReadyMs: Long,
    val steadyMiBs: Double,
    val networkDeliveredMiB: String,
    val proxyReadMiB: String,
    val seekResults: List<SeekBenchResult>,
)

/**
 * 完整走 PlayerScreenState -> PikoMediaRepository -> PikoFileCachePool -> PikoMediaProxy -> ProxySession -> BlockCache -> RangeReader。
 * 模拟 HTTP Range 访问模式（不执行解复用、解码或帧呈现）：
 * 1) 先读 0..256KiB 头部（不触发额外头尾预取）；
 * 2) 若文件 > 1MiB，读末尾 64KiB 索引（MKV Cues / MP4 moov）；
 * 3) 若 startMillis > 0，跳到对应字节偏移发起 Range 请求；否则从 256KiB 处继续顺序流式读取；
 * 4) 按读取阈值模拟位置与 Ready 事件；缓冲余量未知，不向 SDK 伪造播放器缓存。
 */
private suspend fun runPlayerScenario(
    mediaRepository: PikoMediaRepository,
    fileCachePool: PikoFileCachePool,
    file: FileStat,
    durationMs: Long,
    startMillis: Long,
    steadySeconds: Int,
    seeksMillis: List<Long>,
): PlayerScenarioResult {
    val uiDispatcher = Dispatchers.Default.limitedParallelism(1)
    val playerScope = CoroutineScope(SupervisorJob() + uiDispatcher)
    val backend = HttpProxyPlaybackBackend(
        scope = playerScope,
        videoDurationMs = durationMs,
    )
    val clock = TimeSource.Monotonic
    val scenarioStart = clock.markNow()
    val netBefore = fileCachePool.progressSnapshot().values.sumOf { it.deliveredBytes }

    val state = PlayerScreenState(
        repository = mediaRepository,
        backend = backend,
        scope = playerScope,
        initialFileId = file.id,
        initialFileName = file.name,
        initialStartMillis = startMillis,
        defaultMaxHeight = { 0 }, // 原画直链流
    ).also {
        it.playlist = emptyList()
    }

    try {
        // 等待 PlayerScreenState 完成 preparePlayback 并调用 backend.open(proxyUrl, startMillis)
        withTimeout(30_000) {
            backend.openedSignal.await()
        }
        val prepareMs = backend.openCalledAtMs - scenarioStart.elapsedNow().inWholeMilliseconds.let { 0L }
        withTimeout(30_000) {
            backend.readReadySignal.await()
        }
        val firstByteMs = backend.firstByteAtMs
        val readReadyMs = backend.readReadyAtMs

        // 稳态流式读取阶段
        val steadyStartBytes = backend.totalProxyBytesRead.get()
        val steadyStartNet = fileCachePool.progressSnapshot().values.sumOf { it.deliveredBytes }
        val steadyMark = clock.markNow()
        delay(steadySeconds.seconds)
        val steadyElapsedSec = (steadyMark.elapsedNow().inWholeMilliseconds / 1000.0).coerceAtLeast(0.1)
        val steadyProxyDelta = backend.totalProxyBytesRead.get() - steadyStartBytes
        val steadyNetDelta = (fileCachePool.progressSnapshot().values.sumOf { it.deliveredBytes } - steadyNetStart(steadyStartNet)).coerceAtLeast(steadyProxyDelta)
        val steadyMiBs = (steadyNetDelta.toDouble() / (1024.0 * 1024.0)) / steadyElapsedSec

        // 连续交互式 Seek 阶段
        val seekResults = mutableListOf<SeekBenchResult>()
        for (targetMs in seeksMillis) {
            backend.prepareForSeekMeasurement()
            state.seekTo(targetMs)
            withTimeout(30_000) {
                backend.seekReadySignal.await()
            }
            val sFirstByte = backend.lastSeekFirstByteMs
            val sReady = backend.lastSeekReadyMs
            val postNet0 = fileCachePool.progressSnapshot().values.sumOf { it.deliveredBytes }
            val postProxy0 = backend.totalProxyBytesRead.get()
            val postMark = clock.markNow()
            delay(3.seconds)
            val postSec = (postMark.elapsedNow().inWholeMilliseconds / 1000.0).coerceAtLeast(0.1)
            val postDelta = maxOf(
                fileCachePool.progressSnapshot().values.sumOf { it.deliveredBytes } - postNet0,
                backend.totalProxyBytesRead.get() - postProxy0,
            )
            val postMiBs = (postDelta.toDouble() / (1024.0 * 1024.0)) / postSec
            seekResults += SeekBenchResult(
                targetMs = targetMs,
                firstByteMs = sFirstByte,
                readyMs = sReady,
                postSeekMiBs = postMiBs,
            )
        }

        val netAfter = fileCachePool.progressSnapshot().values.sumOf { it.deliveredBytes }
        val totalNetMiB = (netAfter - netBefore).coerceAtLeast(0L).toDouble() / (1024.0 * 1024.0)
        val totalProxyMiB = backend.totalProxyBytesRead.get().toDouble() / (1024.0 * 1024.0)

        return PlayerScenarioResult(
            prepareMs = backend.openCalledAtMs,
            firstByteMs = firstByteMs,
            readReadyMs = readReadyMs,
            steadyMiBs = steadyMiBs,
            networkDeliveredMiB = "%.2f".format(totalNetMiB),
            proxyReadMiB = "%.2f".format(totalProxyMiB),
            seekResults = seekResults,
        )
    } finally {
        withContext(NonCancellable) {
            val closed = CompletableDeferred<Unit>()
            state.release { closed.complete(Unit) }
            withTimeoutOrNull(2_000) { closed.await() }
            withTimeoutOrNull(2_000) {
                while (fileCachePool.progressSnapshot().isNotEmpty()) delay(20)
            }
        }
        playerScope.cancel()
    }
}

private fun steadyNetStart(v: Long): Long = v

/**
 * 通过真实回环代理模拟 HTTP 读取；不包含 libmpv，读取阈值不代表帧呈现。
 */
private class HttpProxyPlaybackBackend(
    private val scope: CoroutineScope,
    private val videoDurationMs: Long,
) : PlaybackBackend {
    private val _events = MutableSharedFlow<PlaybackBackendEvent>(extraBufferCapacity = 16)
    override val events: Flow<PlaybackBackendEvent> = _events.asSharedFlow()

    override var positionMillis: Long by mutableLongStateOf(0L)
        private set
    override var bufferedPositionMillis: Long by mutableLongStateOf(0L)
        private set
    override var durationMillis: Long by mutableLongStateOf(videoDurationMs)
        private set
    override var isPlaying: Boolean by mutableStateOf(false)
        private set
    override var isBuffering: Boolean by mutableStateOf(true)
        private set
    override var speed: Float = 1f
        private set
    override val supportsSpeed: Boolean = true
    override var aspectRatio: PlayerAspectRatio? = PlayerAspectRatio.Fit
        private set
    override val videoAspect: Float? = 16f / 9f
    override var volume: Float? = 1f
        private set
    override var audioTracks: List<MediaTrack> = emptyList()
    override var selectedAudioTrackId: String? = null
    override var subtitleTracks: List<MediaTrack> = emptyList()
    override var selectedSubtitleTrackId: String? = null
    override val canAddSubtitle: Boolean = true

    private val originMark = TimeSource.Monotonic.markNow()
    val openedSignal = CompletableDeferred<Unit>()
    val readReadySignal = CompletableDeferred<Unit>()
    @Volatile var openCalledAtMs: Long = 0L
    @Volatile var firstByteAtMs: Long = 0L
    @Volatile var readReadyAtMs: Long = 0L

    @Volatile var seekReadySignal = CompletableDeferred<Unit>()
    @Volatile var lastSeekFirstByteMs: Long = 0L
    @Volatile var lastSeekReadyMs: Long = 0L

    val totalProxyBytesRead = AtomicLong(0L)
    @Volatile private var currentUrl: String = ""
    @Volatile private var totalFileSize: Long = 0L
    @Volatile private var activeSocket: java.net.Socket? = null
    private var streamJob: Job? = null

    fun prepareForSeekMeasurement() {
        lastSeekFirstByteMs = -1L
        lastSeekReadyMs = -1L
        seekReadySignal = CompletableDeferred()
    }

    override suspend fun open(
        target: PlaybackTarget,
        startMillis: Long,
        playWhenReady: Boolean,
        subtitles: List<ExternalSubtitle>,
    ) {
        val url = when (target) {
            is PlaybackTarget.Url -> target.url
            is PlaybackTarget.LocalFile -> File(target.path).toURI().toString()
        }
        currentUrl = url
        Snapshot.withMutableSnapshot {
            positionMillis = startMillis
            bufferedPositionMillis = 0L
            isBuffering = true
            isPlaying = playWhenReady
        }
        openCalledAtMs = originMark.elapsedNow().inWholeMilliseconds
        openedSignal.complete(Unit)

        streamJob?.cancel()
        streamJob = scope.launch(Dispatchers.IO) {
            try {
                // 1. 读头部 256 KiB（触发 ProxySession.requestTail()）
                val headerInfo = readRangeBytes(url, 0L, 256 * 1024L, onFirstByte = {
                    if (startMillis <= 0L && firstByteAtMs == 0L) {
                        firstByteAtMs = originMark.elapsedNow().inWholeMilliseconds
                    }
                })
                totalFileSize = headerInfo.totalSize
                val bytesPerMs = if (videoDurationMs > 0 && totalFileSize > 0) {
                    totalFileSize.toDouble() / videoDurationMs.toDouble()
                } else {
                    400.0
                }

                // 2. 模拟 mpv 探测容器尾部 Cues / moov（最后 64 KiB）
                if (totalFileSize > 2L * 1024 * 1024) {
                    readRangeBytes(url, totalFileSize - 65536L, 65536L)
                }

                // 3. 计算正片起播偏移并发起主 Range 流
                val startOffset = if (startMillis > 0L && totalFileSize > 0L) {
                    ((totalFileSize.toDouble() * startMillis.toDouble() / videoDurationMs.toDouble()).toLong())
                        .coerceIn(0L, (totalFileSize - 1024 * 1024).coerceAtLeast(0L))
                } else {
                    256 * 1024L
                }

                streamFromOffset(
                    url = url,
                    startOffset = startOffset,
                    initialMillis = startMillis,
                    bytesPerMs = bytesPerMs,
                    isInitialOpen = true,
                    seekMark = null,
                    seekSignal = null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _events.tryEmit(PlaybackBackendEvent.Error(e.message ?: e.javaClass.simpleName))
                if (!readReadySignal.isCompleted) readReadySignal.completeExceptionally(e)
            }
        }
    }

    override fun stop() {
        val s = activeSocket
        activeSocket = null
        runCatching { s?.close() }
        streamJob?.cancel()
        streamJob = null
        Snapshot.withMutableSnapshot {
            isPlaying = false
        }
    }

    override fun play() {
        Snapshot.withMutableSnapshot {
            isPlaying = true
        }
    }

    override fun pause() {
        Snapshot.withMutableSnapshot {
            isPlaying = false
        }
    }

    override fun seekTo(positionMillis: Long) {
        val millis = positionMillis
        val url = currentUrl
        if (url.isBlank() || totalFileSize <= 0L) return
        val seekMark = TimeSource.Monotonic.markNow()
        Snapshot.withMutableSnapshot {
            this.positionMillis = millis
            bufferedPositionMillis = 0L
            isBuffering = true
        }
        val bytesPerMs = if (videoDurationMs > 0 && totalFileSize > 0) {
            totalFileSize.toDouble() / videoDurationMs.toDouble()
        } else {
            400.0
        }
        val targetOffset = ((totalFileSize.toDouble() * millis.toDouble() / videoDurationMs.toDouble()).toLong())
            .coerceIn(0L, (totalFileSize - 512 * 1024).coerceAtLeast(0L))

        val previousJob = streamJob
        val s = activeSocket
        activeSocket = null
        runCatching { s?.close() }
        previousJob?.cancel()
        val mySeekSignal = seekReadySignal
        streamJob = scope.launch(Dispatchers.IO) {
            try {
                withTimeoutOrNull(200) { previousJob?.join() }
                streamFromOffset(
                    url = url,
                    startOffset = targetOffset,
                    initialMillis = millis,
                    bytesPerMs = bytesPerMs,
                    isInitialOpen = false,
                    seekMark = seekMark,
                    seekSignal = mySeekSignal,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!mySeekSignal.isCompleted) mySeekSignal.completeExceptionally(e)
            }
        }
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed
    }

    override fun setAspectRatio(mode: PlayerAspectRatio) {
        this.aspectRatio = mode
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
    }

    override fun selectAudioTrack(id: String) = Unit
    override fun selectSubtitleTrack(id: String?) = Unit
    override fun addSubtitle(subtitle: ExternalSubtitle) = Unit

    private suspend fun streamFromOffset(
        url: String,
        startOffset: Long,
        initialMillis: Long,
        bytesPerMs: Double,
        isInitialOpen: Boolean,
        seekMark: TimeSource.Monotonic.ValueTimeMark?,
        seekSignal: CompletableDeferred<Unit>?,
    ) {
        val uri = URI(url)
        val socket = java.net.Socket()
        activeSocket = socket
        try {
            socket.soTimeout = 30_000
            socket.connect(java.net.InetSocketAddress(uri.host, uri.port), 15_000)
            val req = "GET ${uri.rawPath} HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\nRange: bytes=$startOffset-\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(req.toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()
            val input = socket.getInputStream()
            val statusLine = readAsciiLine(input)
            check(statusLine.contains(" 200 ") || statusLine.contains(" 206 ")) { "Proxy HTTP status=$statusLine" }
            while (true) {
                val h = readAsciiLine(input)
                if (h.isEmpty()) break
            }
            val buf = ByteArray(64 * 1024)
            var bytesReadInStream = 0L
            val readyThreshold = if (isInitialOpen) 768 * 1024L else 512 * 1024L
            var readyMark: TimeSource.Monotonic.ValueTimeMark? = null
            var lastStateSyncMs = -100L

            while (currentCoroutineContext().isActive) {
                val n = try {
                    input.read(buf)
                } catch (e: java.net.SocketException) {
                    if (!currentCoroutineContext().isActive || activeSocket !== socket) break
                    throw e
                }
                if (n < 0) break
                if (bytesReadInStream == 0L) {
                    if (isInitialOpen && firstByteAtMs == 0L) {
                        firstByteAtMs = originMark.elapsedNow().inWholeMilliseconds
                    }
                    if (!isInitialOpen && seekMark != null && seekSignal === seekReadySignal) {
                        lastSeekFirstByteMs = seekMark.elapsedNow().inWholeMilliseconds
                    }
                }
                bytesReadInStream += n
                totalProxyBytesRead.addAndGet(n.toLong())

                val reachedReady = bytesReadInStream >= readyThreshold
                if (reachedReady && readyMark == null) {
                    readyMark = TimeSource.Monotonic.markNow()
                }
                val playElapsedMs = readyMark?.elapsedNow()?.inWholeMilliseconds ?: 0L
                if (playElapsedMs - lastStateSyncMs >= 100L || (reachedReady && isBuffering)) {
                    lastStateSyncMs = playElapsedMs
                    val newPos = (initialMillis + playElapsedMs).coerceAtMost(videoDurationMs)
                    scope.launch {
                        Snapshot.withMutableSnapshot {
                            positionMillis = newPos
                            bufferedPositionMillis = 0L
                            if (reachedReady && isBuffering) {
                                isBuffering = false
                                isPlaying = true
                                _events.tryEmit(PlaybackBackendEvent.Ready)
                            }
                        }
                    }
                }

                if (reachedReady) {
                    if (isInitialOpen && !readReadySignal.isCompleted) {
                        readReadyAtMs = originMark.elapsedNow().inWholeMilliseconds
                        readReadySignal.complete(Unit)
                    }
                    if (!isInitialOpen && seekMark != null && seekSignal != null && !seekSignal.isCompleted) {
                        if (seekSignal === seekReadySignal) {
                            lastSeekReadyMs = seekMark.elapsedNow().inWholeMilliseconds
                        }
                        seekSignal.complete(Unit)
                    }
                }
            }
        } finally {
            if (activeSocket === socket) activeSocket = null
            runCatching { socket.close() }
        }
    }

    private fun readAsciiLine(input: java.io.InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) break
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private data class RangeProbeInfo(val bytesRead: Long, val totalSize: Long)

    private fun readRangeBytes(
        url: String,
        start: Long,
        length: Long,
        onFirstByte: (() -> Unit)? = null,
    ): RangeProbeInfo {
        val endInclusive = start + length - 1
        val conn = (URI(url).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Range", "bytes=$start-$endInclusive")
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            val code = conn.responseCode
            check(code == 200 || code == 206) { "Proxy HTTP status=$code" }
            val contentRange = conn.getHeaderField("Content-Range").orEmpty()
            val slash = contentRange.lastIndexOf('/')
            val totalSize = if (slash >= 0) {
                contentRange.substring(slash + 1).trim().toLongOrNull() ?: conn.contentLengthLong
            } else {
                conn.contentLengthLong
            }
            val input = conn.inputStream
            val buf = ByteArray(64 * 1024)
            var readTotal = 0L
            while (readTotal < length) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), length - readTotal).toInt())
                if (n < 0) break
                if (readTotal == 0L) onFirstByte?.invoke()
                readTotal += n
                totalProxyBytesRead.addAndGet(n.toLong())
            }
            return RangeProbeInfo(readTotal, totalSize)
        } finally {
            runCatching { conn.inputStream.close() }
            runCatching { conn.disconnect() }
        }
    }
}

private suspend fun probeProxyUrlFirstBytes(url: String, targetBytes: Int): Long = withContext(Dispatchers.IO) {
    val mark = TimeSource.Monotonic.markNow()
    val conn = (URI(url).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
        requestMethod = "GET"
        setRequestProperty("Range", "bytes=0-${targetBytes - 1}")
        connectTimeout = 15_000
        readTimeout = 30_000
    }
    try {
        conn.inputStream.use { input ->
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (total < targetBytes) {
                val n = input.read(buf, 0, minOf(buf.size, targetBytes - total))
                if (n < 0) break
                total += n
            }
        }
        mark.elapsedNow().inWholeMilliseconds
    } finally {
        runCatching { conn.disconnect() }
    }
}

private data class DownloadScenarioResult(
    val elapsedMs: Long,
    val deliveredMiB: String,
    val throughputMiBs: Double,
)

/**
 * 与 PikoDownloadCoordinator.runDownload 完全一致的稀疏缓存下载链路：
 * PikoFileCachePool.acquire -> entry.store.missing -> entry.cache.download(missingAll, StreamRole.BACKGROUND)
 */
private suspend fun runDownloadScenario(
    client: PikPakClient,
    fileCachePool: PikoFileCachePool,
    file: FileStat,
    durationSeconds: Int,
): DownloadScenarioResult {
    val clock = TimeSource.Monotonic
    val lease = fileCachePool.acquire(
        client = client,
        fileId = file.id,
        gcid = file.hash,
        size = file.sizeBytes,
        name = file.name,
        parentId = file.parentId,
        retained = false,
        owner = "bench-dl-${file.id}",
    )
    try {
        val entry = lease.entry
        val beforeBytes = entry.cache.deliveredBytes
        val mark = clock.markNow()
        val missingAll = entry.store.missing(entry.handle.contentKey, listOf(0L until file.sizeBytes))
        val download = entry.cache.download(missingAll, StreamRole.BACKGROUND)
        try {
            withTimeoutOrNull(durationSeconds.seconds) {
                download.await()
            }
        } finally {
            download.cancel()
        }
        val elapsedMs = mark.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
        val delivered = (entry.cache.deliveredBytes - beforeBytes).coerceAtLeast(0L)
        val mib = delivered.toDouble() / (1024.0 * 1024.0)
        val rate = mib / (elapsedMs / 1000.0)
        return DownloadScenarioResult(
            elapsedMs = elapsedMs,
            deliveredMiB = "%.2f".format(mib),
            throughputMiBs = rate,
        )
    } finally {
        lease.release()
    }
}
