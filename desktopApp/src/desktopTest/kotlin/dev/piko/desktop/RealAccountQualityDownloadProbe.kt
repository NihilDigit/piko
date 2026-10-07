package dev.piko.desktop

import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.auth.PlainFileVault
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.log.PikoLog
import dev.piko.shared.media.DownloadQuality
import dev.piko.shared.media.PikoMediaRepository
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.getFile
import io.github.nihildigit.pikpak.listFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 只在本机跑的真实账号冒烟：从网盘里找一个有转码档的视频（只读，不改网盘内容），走下载调度的完整流程——
 * 从转码档截片段（中途暂停再继续）、整段下载转码档并转封装——再用 ffprobe 看产物。
 *
 * 要 PIKO_REAL_PROBE=1，凭据取 ../pikpak-kotlin/.env（SDK 实测用的那一份），本机要有 ffprobe。CI 上没有这些，跳过。
 */
class RealAccountQualityDownloadProbe {

    @Test
    fun transcodeSegmentAndFullDownload() = runBlocking {
        assumeTrue("设 PIKO_REAL_PROBE=1 才跑", System.getenv("PIKO_REAL_PROBE") == "1")
        // 在别处的工作树里跑时由 PIKO_PROBE_ENV 指过去
        val env = System.getenv("PIKO_PROBE_ENV")?.let(::File)
            ?: generateSequence(File("").absoluteFile) { it.parentFile }.map { it.resolve("pikpak-kotlin/.env") }.first { it.isFile }
        val values = env.readLines().mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
        val client = PikPakClient(account = values.getValue("PIKPAK_USERNAME"), password = values.getValue("PIKPAK_PASSWORD"), sessionStore = InMemorySessionStore())
        client.login()
        // 任务失败时 errorMessage 只有最外层一句，取流的每次失败与换主机要看日志
        PikoLog.install(Files.createTempDirectory("piko-probe-log").toString()) { level, tag, message, error ->
            println("[log] $level $tag $message${error?.let { "：${it.stackTraceToString()}" } ?: ""}")
        }
        val provider = object : PikoClientProvider {
            override val currentClient: StateFlow<PikPakClient?> = MutableStateFlow(client)
        }
        // 整段下载有几十到几百 MB，不放系统临时目录；跑完删掉
        val root = (System.getenv("PIKO_PROBE_DIR")?.let { File(it) } ?: Files.createTempDirectory("piko-real-probe").toFile())
            .resolve("quality-probe").also { it.mkdirs() }
        val prefs = DesktopPikoPreferences(DesktopSettingsStore(root.resolve("settings.properties"))) { PlainFileVault(root.resolve("secrets").toPath()) }
        val storage = DesktopPikoDownloadStorage(root.resolve("cache")) { root.resolve("out").also { it.mkdirs() } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val media = PikoMediaRepository(provider, prefs)
        val coordinator = PikoDownloadCoordinator(provider, prefs, storage, scope, DesktopPikoSegmentDownloader(::testFfmpeg), media)
        try {
            val file = findTranscodedVideo(client) ?: error("网盘里没找到有转码档的视频")
            val qualities = media.downloadQualities(file.id).toList().last()
            println("[probe] 视频 ${file.id}，原画 ${file.sizeBytes} 字节；各档：" + qualities.joinToString { "${it.name ?: "原画"}=${it.sizeBytes}" })
            val transcodes = qualities.filter { it.mediaId != null }
            // 整段下载挑最小的一档，少下一些
            val chosen = transcodes.minBy { it.height }
            val durationMs = chosen.durationMs.takeIf { it > 0 } ?: error("取不到时长")

            // 片段：每一档从中间截 20 秒，第一个开始后暂停一次再继续
            val start = durationMs / 2
            transcodes.forEachIndexed { index, quality ->
                coordinator.enqueueSegment(file, start, start + 20_000, "probe", "", quality)
                val segment = awaitTask(coordinator) { it.isSegment && it.quality == quality.name }
                if (index == 0) {
                    awaitUntil("片段开始读") { (coordinator.tasks.value[segment.taskId]?.progressFraction ?: 0f) > 0f }
                    coordinator.pauseDownload(segment.taskId)
                    delay(1_000)
                    coordinator.startDownload(segment.taskId)
                }
                val doneSegment = awaitStatus(coordinator, segment.taskId)
                val probe = ffprobe(File(doneSegment.destinationPath))
                println("[probe] ${quality.name} 片段 ${doneSegment.fileName}\n$probe")
                assertTrue("codec_name=hevc" in probe && "codec_tag_string=hvc1" in probe, "${quality.name} 片段没有 hvc1 视频轨")
            }

            // 整段：下转码档、转封装
            coordinator.enqueueQuality(file, chosen)
            val full = awaitTask(coordinator) { !it.isSegment && it.quality == chosen.name }
            val doneFull = awaitStatus(coordinator, full.taskId, timeoutMs = 30 * 60_000)
            val probe = ffprobe(File(doneFull.destinationPath))
            println("[probe] 整段 ${doneFull.fileName}，${doneFull.totalBytes} 字节\n$probe")
            assertTrue(doneFull.fileName.endsWith(" [${chosen.name}].mp4"), "文件名：${doneFull.fileName}")
            assertTrue("codec_name=hevc" in probe && "codec_tag_string=hvc1" in probe, "整段没有 hvc1 视频轨")
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    private suspend fun findTranscodedVideo(client: PikPakClient): FileStat? {
        val queue = ArrayDeque(listOf(""))
        var folders = 0
        while (queue.isNotEmpty() && folders < 200) {
            val children = runCatching { client.listFiles(parentId = queue.removeFirst()) }.getOrDefault(emptyList())
            folders++
            for (file in children) {
                if (file.isFolder) {
                    queue.addLast(file.id)
                    continue
                }
                if (!file.mimeType.startsWith("video/") || file.sizeBytes !in 50L * 1024 * 1024..1024L * 1024 * 1024) continue
                val detail = runCatching { client.getFile(file.id) }.getOrNull() ?: continue
                // 要三档转码齐全的，三档的片段都要测到
                val transcodes = detail.medias.filter { !it.isOrigin && it.video != null && it.link.url.isNotBlank() }
                if (transcodes.size >= 3 && transcodes.all { servesBytes(it.link.url) }) return file
            }
        }
        return null
    }

    /**
     * 实测有的转码档服务端是坏的：Range 请求回 206、Content-Range 写着全长，正文却是空的，换主机、隔几分钟都一样
     * （2026-10-07，同一个视频 1080P 正常，720P 与 480P 如此）。那是网盘的问题，不是要测的东西，选片时跳过。
     */
    private fun servesBytes(url: String): Boolean = runCatching {
        val request = HttpRequest.newBuilder(URI(url)).header("Range", "bytes=0-0").timeout(Duration.ofSeconds(20)).build()
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build()
            .send(request, HttpResponse.BodyHandlers.ofByteArray()).body().size == 1
    }.getOrDefault(false)

    private suspend fun awaitTask(coordinator: PikoDownloadCoordinator, match: (DownloadTask) -> Boolean): DownloadTask {
        awaitUntil("任务入队") { coordinator.tasks.value.values.any(match) }
        return coordinator.tasks.value.values.first(match)
    }

    private suspend fun awaitStatus(coordinator: PikoDownloadCoordinator, taskId: String, timeoutMs: Long = 10 * 60_000): DownloadTask {
        var last = -1
        awaitUntil("任务完成", timeoutMs) {
            val task = coordinator.tasks.value.getValue(taskId)
            val percent = (task.progress * 100).toInt()
            if (percent / 10 != last / 10) println("[probe] ${task.status} ${if (task.converting) "转换中 " else ""}$percent%")
            last = percent
            check(task.status != DownloadStatus.FAILED) { "任务失败：${task.errorMessage}" }
            task.status == DownloadStatus.COMPLETED
        }
        return coordinator.tasks.value.getValue(taskId)
    }

    private suspend fun awaitUntil(what: String, timeoutMs: Long = 120_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(200)
        }
        println("[probe] $what")
    }

    private fun ffprobe(file: File): String = ProcessBuilder(
        "ffprobe", "-v", "error", "-show_entries",
        "format=duration,start_time:stream=codec_name,codec_tag_string,width,height,duration", "-of", "compact", file.path,
    ).redirectErrorStream(true).start().inputStream.bufferedReader().readText()
}
