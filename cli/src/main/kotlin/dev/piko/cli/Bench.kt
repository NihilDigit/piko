package dev.piko.cli

import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.RangeAttempt
import io.github.nihildigit.pikpak.StreamRole
import io.github.nihildigit.pikpak.VariantPreference
import io.github.nihildigit.pikpak.fileHandle
import io.github.nihildigit.pikpak.getFile
import io.github.nihildigit.pikpak.listFiles
import io.github.nihildigit.pikpak.resolveVariant
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/**
 * 只读实验：把信息流取开头的做法单独拿出来，不经代理、不开播放器，量 SDK 自己能跑多快。
 * 对 [path] 下 [count] 个有 720P 转码的视频，各从转码流的四成处预取 [headSeconds] 秒（按平均码率、1.5 倍余量），
 * 与信息流的 PreparedClip 同一种算法。[sequential] 时一段取完再取下一段，否则一齐发出。
 *
 * 打印每段的耗时，以及全部请求的合计吞吐、平均与最多同时在途：信息流在 app 里只跑出下载的四分之一，
 * 平均在途只有一两路（2026-09-28），这里分清是 SDK 的调度还是 app 那层的节奏。
 */
suspend fun benchClipHeads(
    client: PikPakClient,
    path: String,
    count: Int,
    headSeconds: Int,
    sequential: Boolean,
    withPlayer: Boolean,
    backgroundFirst: Boolean,
    /** 挂上与 app 同一种磁盘块缓存，目录由调用方给，别指向 app 正在用的那一份。 */
    storeDirectory: java.io.File?,
) {
    val store = storeDirectory?.let { dev.piko.shared.media.FileClipCache(it).blocks }
    // 以 / 开头的是路径，否则当作文件夹 ID（信息流存盘的 clip-feed-<ID>）。视频常散在子文件夹里，逐层往下找
    val candidates = mutableListOf<io.github.nihildigit.pikpak.FileStat>()
    var level = listOf(if (path.startsWith("/")) resolvePath(client, path) else path)
    while (level.isNotEmpty() && candidates.size < count * 3) {
        val files = level.flatMap { client.listFiles(parentId = it) }
        candidates += files.filter { !it.isFolder && (it.params["duration"]?.toDoubleOrNull() ?: 0.0) >= 60 }
        level = files.filter { it.isFolder }.map { it.id }
    }
    val attempts = Collections.synchronizedList(mutableListOf<Pair<Long, RangeAttempt>>())
    val clock = TimeSource.Monotonic
    val origin = clock.markNow()
    val heads = mutableListOf<Head>()
    for (file in candidates) {
        if (heads.size >= count) break
        val detail = client.getFile(file.id)
        val variant = detail.resolveVariant(VariantPreference.Resolution("720P"))
        if (variant.isOrigin) continue
        val handle = client.fileHandle(
            detail,
            mediaId = variant.mediaId,
            blockStore = store,
            coroutineContext = Dispatchers.IO,
            onRangeAttempt = { attempts += origin.elapsedNow().inWholeMilliseconds to it },
        )
        val size = handle.streamSize()
        val durationMs = ((file.params["duration"]?.toDoubleOrNull() ?: 0.0) * 1000).toLong()
        val bytesPerMs = size.toDouble() / durationMs
        val start = (size * 0.4).toLong()
        val length = (bytesPerMs * headSeconds * 1000 * 1.5).toLong().coerceAtLeast(512L * 1024)
        heads += Head(file.id, handle, start until (start + length).coerceAtMost(size))
    }
    // 模拟 app 里正在放的那一段：另一个视频开一路前台读者，按片段的码率一秒读一秒的量。
    // SDK 在账号有前台读者时会限制别的文件，这一路决定实验与 app 是不是同一个条件
    val playing = if (withPlayer) {
        val file = candidates.drop(heads.size).firstOrNull() ?: candidates.last()
        val detail = client.getFile(file.id)
        val handle = client.fileHandle(detail, mediaId = detail.resolveVariant(VariantPreference.Resolution("720P")).mediaId, coroutineContext = Dispatchers.IO)
        handle to handle.openStream(StreamRole.FOREGROUND).also { it.seekTo((handle.streamSize() * 0.4).toLong()) }
    } else {
        null
    }
    println(
        "${heads.size} 段，每段 ${heads.sumOf { it.range.last - it.range.first + 1 } / heads.size.coerceAtLeast(1) / 1024} KiB，" +
            "${if (sequential) "逐段" else "一齐"}发出${if (playing != null) "，同时有一路在放" else ""}",
    )
    attempts.clear()
    val started = clock.markNow()
    val startedAtMs = origin.elapsedNow().inWholeMilliseconds
    val player = playing?.let { (_, reader) ->
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(64 * 1024)
            // 约 1 MB/s，高于 720P 转码的码率：读得比实际播放还紧，只会让条件更苛刻
            while (true) {
                val mark = clock.markNow()
                var read = 0
                while (read < 1024 * 1024) {
                    val n = reader.read(buffer, 0, buffer.size)
                    if (n < 0) return@launch
                    read += n
                }
                val left = 1000 - mark.elapsedNow().inWholeMilliseconds
                if (left > 0) kotlinx.coroutines.delay(left)
            }
        }
    }
    suspend fun fetch(head: Head) {
        val t = clock.markNow()
        if (backgroundFirst) {
            // 照 app 的备会话：先以后台身份读开头找关键帧，读到一块就转去预取，后台读留在原处
            val reader = head.handle.openStream(StreamRole.BACKGROUND)
            reader.seekTo(head.range.first)
            reader.read(ByteArray(64 * 1024), 0, 64 * 1024)
            head.handle.prefetch(listOf(head.range), StreamRole.FOREGROUND).await()
            reader.close()
        } else {
            head.handle.prefetch(listOf(head.range), StreamRole.FOREGROUND).await()
        }
        println("  ${head.fileId} 取好：${t.elapsedNow().inWholeMilliseconds} ms（开始后 ${started.elapsedNow().inWholeMilliseconds} ms）")
    }
    if (sequential) {
        heads.forEach { fetch(it) }
    } else {
        coroutineScope { heads.map { async { fetch(it) } }.awaitAll() }
    }
    val wallMs = started.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
    player?.cancel()
    playing?.let { (handle, reader) ->
        reader.close()
        handle.close()
    }
    heads.forEach { it.handle.close() }

    val done = attempts.toList()
    val bytes = done.sumOf { it.second.delivered }
    val busyMs = done.sumOf { it.second.duration.inWholeMilliseconds }
    // 每条请求的起止折算到同一时间轴上，扫一遍得出最多同时在途
    val edges = done.flatMap { (endMs, a) ->
        val end = endMs - startedAtMs
        listOf(end - a.duration.inWholeMilliseconds to 1, end to -1)
    }.sortedWith(compareBy({ it.first }, { it.second }))
    var inFlight = 0
    var peak = 0
    edges.forEach { (_, delta) ->
        inFlight += delta
        peak = maxOf(peak, inFlight)
    }
    val firstBytes = done.mapNotNull { it.second.timeToFirstByte?.inWholeMilliseconds }.sorted()
    println(
        "合计 ${bytes / 1024} KiB / $wallMs ms = ${bytes * 1000 / wallMs / 1024} KiB/s；${done.size} 条请求，" +
            "每条平均 ${if (done.isNotEmpty()) bytes / done.size / 1024 else 0} KiB；" +
            "平均在途 ${"%.1f".format(busyMs.toDouble() / wallMs)}，最多 $peak；" +
            "首字节中位 ${firstBytes.getOrNull(firstBytes.size / 2) ?: 0} ms",
    )
}

private class Head(val fileId: String, val handle: io.github.nihildigit.pikpak.PikPakFileHandle, val range: LongRange)
