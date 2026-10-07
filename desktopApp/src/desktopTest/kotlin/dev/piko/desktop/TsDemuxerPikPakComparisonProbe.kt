package dev.piko.desktop

import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.media.ts.TsDemuxer
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.MediaVariant
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.getFile
import io.github.nihildigit.pikpak.listFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 只在本机跑：拿真实的 PikPak 转码档（1080P、720P、480P 各取一段开头）与 ffprobe 逐样本对拍——视频每帧的 PTS、DTS、
 * 关键帧标记与字节摘要，音频每帧的 PTS 与长度。网盘只读；样本落在 PIKO_PROBE_DIR（缺省为临时目录），跑完删掉，不进仓库。
 *
 * 要 PIKO_REAL_PROBE=1，凭据取 ../pikpak-kotlin/.env（SDK 实测用的那一份），本机要有 ffprobe。
 */
class TsDemuxerPikPakComparisonProbe {

    @Test
    fun matchesFfprobeOnRealTranscodes() = runBlocking {
        assumeTrue("设 PIKO_REAL_PROBE=1 才跑", System.getenv("PIKO_REAL_PROBE") == "1")
        // 在别处的工作树里跑时由 PIKO_PROBE_ENV 指过去
        val env = System.getenv("PIKO_PROBE_ENV")?.let(::File)
            ?: generateSequence(File("").absoluteFile) { it.parentFile }.map { it.resolve("pikpak-kotlin/.env") }.first { it.isFile }
        val values = env.readLines().mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
        val client = PikPakClient(account = values.getValue("PIKPAK_USERNAME"), password = values.getValue("PIKPAK_PASSWORD"), sessionStore = InMemorySessionStore())
        client.login()
        val directory = (System.getenv("PIKO_PROBE_DIR")?.let(::File) ?: Files.createTempDirectory("piko-ts-probe").toFile()).resolve("ts-probe")
        directory.mkdirs()
        try {
            val variants = findVariants(client, setOf("1080P", "720P", "480P"))
            println("[ts] 取到的档：${variants.keys}")
            assertEquals(setOf("1080P", "720P", "480P"), variants.keys, "三档没找齐")
            val media = PikoMediaRepository(object : PikoClientProvider {
                override val currentClient: StateFlow<PikPakClient?> = MutableStateFlow(client)
            })
            for ((name, variant) in variants) {
                val file = directory.resolve("$name.ts")
                download(media, variant.first.id, variant.second.mediaId, file, HEAD_BYTES)
                compare(name, file)
            }
        } finally {
            // PIKO_PROBE_KEEP=1 时留下样本，对不上时拿去逐字节看
            if (System.getenv("PIKO_PROBE_KEEP") != "1") directory.deleteRecursively()
        }
    }

    private suspend fun compare(name: String, file: File) {
        val mine = RandomAccessFile(file, "r").use { input ->
            val demuxer = TsDemuxer(input.length() / 188 * 188) { position, buffer, offset, length ->
                input.seek(position)
                input.read(buffer, offset, length)
            }
            val info = demuxer.probe() ?: error("$name：认不出")
            println("[ts] $name：${info.video.width}x${info.video.height}，音频 ${info.audio?.sampleRate} Hz ${info.audio?.channels} 声道")
            buildList { demuxer.samples(info, 0L) { add(it); true } }
        }
        val reference = ffprobe(file)
        val myVideo = mine.filter { it.video }
        val theirVideo = reference.filter { it.type == "video" }
        // 截下的这一段末尾那个 PES 不完整，两边对它的处理不同，比到倒数第二帧
        val count = minOf(myVideo.size, theirVideo.size) - 1
        assertTrue(count > 100, "$name：视频帧太少（$count）")
        for (i in 0 until count) {
            val a = myVideo[i]
            val b = theirVideo[i]
            assertEquals(b.ptsUs, a.ptsUs, "$name 第 $i 帧视频 PTS")
            assertEquals(b.dtsUs, a.dtsUs, "$name 第 $i 帧视频 DTS")
            assertEquals(b.keyframe, a.keyframe, "$name 第 $i 帧关键帧标记")
            assertEquals(b.hash, md5(a.data), "$name 第 $i 帧视频字节")
        }
        val myAudio = mine.filter { !it.video }
        // 转码档还带一路 bin_data（codec_type 为 data），不算音频
        val theirAudio = reference.filter { it.type == "audio" }
        val audioCount = minOf(myAudio.size, theirAudio.size) - 1
        for (i in 0 until audioCount) {
            assertTrue(kotlin.math.abs(myAudio[i].ptsUs - theirAudio[i].ptsUs) <= 100, "$name 第 $i 帧音频 PTS：${myAudio[i].ptsUs} 对 ${theirAudio[i].ptsUs}")
            assertEquals(theirAudio[i].size - ADTS_HEADER, myAudio[i].data.size, "$name 第 $i 帧音频长度")
        }
        val bFrames = myVideo.count { it.ptsUs != it.dtsUs }
        val keyframes = myVideo.count { it.keyframe }
        println(
            "[ts] $name：对上 $count 帧视频、$audioCount 帧音频；B 帧 $bFrames，关键帧 $keyframes；" +
                "总数 视频 ${myVideo.size} 对 ${theirVideo.size}，音频 ${myAudio.size} 对 ${theirAudio.size}",
        )
    }

    private class Reference(val type: String,val ptsUs: Long, val dtsUs: Long, val keyframe: Boolean, val size: Int, val hash: String)

    private fun ffprobe(file: File): List<Reference> {
        val process = ProcessBuilder(
            "ffprobe", "-v", "error", "-show_data_hash", "MD5", "-show_entries",
            "packet=codec_type,pts,dts,flags,size,data_hash", "-of", "csv=p=0", file.path,
        ).start()
        return process.inputStream.bufferedReader().readLines().mapNotNull { line ->
            val parts = line.split(',')
            if (parts.size < 6) return@mapNotNull null
            val (type, pts, dts, size, flags, hash) = parts
            Reference(
                type = type,
                ptsUs = (pts.toLongOrNull() ?: return@mapNotNull null) * 100 / 9,
                dtsUs = (dts.toLongOrNull() ?: pts.toLong()) * 100 / 9,
                keyframe = 'K' in flags,
                size = size.toInt(),
                hash = hash.removePrefix("MD5:"),
            )
        }
    }

    private operator fun <T> List<T>.component6(): T = this[5]

    private fun md5(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * 经 SDK 的 handle 读，与截片段、整段下载同一条路。否决过直接对详情里的直链发一个 Range 请求：
     * 单条连接实测会停在几 MB 不动，没有超时也不换主机，测试就一直挂着。
     */
    private suspend fun download(media: PikoMediaRepository, fileId: String, mediaId: String, file: File, bytes: Long) {
        media.openRandomAccess(fileId, mediaId = mediaId).getOrThrow().use { source ->
            val buffer = ByteArray(1 shl 20)
            file.outputStream().use { output ->
                var position = 0L
                val end = minOf(bytes, source.size)
                while (position < end) {
                    val n = source.readAt(position, buffer, 0, minOf(buffer.size.toLong(), end - position).toInt())
                    if (n <= 0) break
                    output.write(buffer, 0, n)
                    position += n
                }
            }
        }
    }

    /** 每档在网盘里找一个视频的那一档转码。 */
    private suspend fun findVariants(client: PikPakClient, wanted: Set<String>): Map<String, Pair<FileStat, MediaVariant>> {
        val found = LinkedHashMap<String, Pair<FileStat, MediaVariant>>()
        val queue = ArrayDeque(listOf(""))
        var folders = 0
        while (queue.isNotEmpty() && folders < 300 && found.keys != wanted) {
            val children = runCatching { client.listFiles(parentId = queue.removeFirst()) }.getOrDefault(emptyList())
            folders++
            for (file in children) {
                if (file.isFolder) {
                    queue.addLast(file.id)
                    continue
                }
                if (!file.mimeType.startsWith("video/")) continue
                val detail = runCatching { client.getFile(file.id) }.getOrNull() ?: continue
                for (media in detail.medias) {
                    val name = media.mediaName.ifBlank { media.resolutionName }
                    if (!media.isOrigin && name in wanted && name !in found && media.link.url.isNotBlank()) found[name] = file to media
                }
                if (found.keys == wanted) break
            }
        }
        return found
    }

    private companion object {
        const val HEAD_BYTES = 24L * 1024 * 1024
        const val ADTS_HEADER = 7
    }
}
