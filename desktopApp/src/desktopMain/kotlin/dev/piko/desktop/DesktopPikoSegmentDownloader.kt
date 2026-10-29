package dev.piko.desktop

import dev.piko.shared.i18n.tr

import dev.piko.desktop.media.ClipRange
import dev.piko.desktop.media.Ffmpeg
import dev.piko.desktop.media.FfmpegRemuxer
import dev.piko.shared.download.PikoRemuxRequest
import dev.piko.shared.download.PikoSegmentDownloader
import dev.piko.shared.download.PikoSegmentRequest
import dev.piko.shared.log.PikoLog
import dev.piko.shared.media.RandomAccessMediaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 桌面端的片段截取与转码档转封装，都是 FFmpeg 的流复制（见 FfmpegRemuxer）。与 Android（MediaExtractor + MediaMuxer）
 * 同语义：不转码，片段起点对齐到之前的视频关键帧，输出 moov 前置的 MP4。源经 [PikoSegmentRequest.openRandomAccess]
 * 按偏移读，读过的块留在稀疏缓存里，中断后再来不重下。
 */
class DesktopPikoSegmentDownloader internal constructor(private val ffmpeg: () -> Ffmpeg) : PikoSegmentDownloader {
    constructor() : this(Ffmpeg::bundled)

    override suspend fun extract(
        request: PikoSegmentRequest,
        onProgress: suspend (Float) -> Unit,
    ): Result<String> {
        if (request.endMillis <= request.startMillis) return Result.failure(IllegalArgumentException(tr("结束时间必须晚于开始时间", "End time must be after start time", "終了時刻は開始時刻より後にしてください", "종료 시간은 시작 시간보다 뒤여야 합니다")))
        val open = request.openRandomAccess ?: { localSource(request.sourceUrl) }
        return produce(request.destinationPath, ClipRange(request.startMillis, request.endMillis), open, onProgress)
    }

    override suspend fun remux(
        request: PikoRemuxRequest,
        onProgress: suspend (Float) -> Unit,
    ): Result<String> = produce(request.destinationPath, range = null, open = { localSource(request.sourcePath) }, onProgress)

    private suspend fun produce(
        destinationPath: String,
        range: ClipRange?,
        open: suspend () -> RandomAccessMediaSource,
        onProgress: suspend (Float) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // 转封装在这一个线程上阻塞做完，中间不挂起：它的原生内存与回调存根属于这个线程
            val job = currentCoroutineContext().job
            val remuxer = FfmpegRemuxer(ffmpeg())
            val partFile = File(destinationPath + PART_SUFFIX)
            partFile.parentFile?.mkdirs()
            try {
                open().use { source ->
                    remuxer.remux(source, partFile, range, job) { fraction -> runBlocking { onProgress(fraction) } }
                }
                check(partFile.length() > 0) { tr("输出为空，源文件可能已损坏", "Empty output; source file may be corrupted", "出力が空です。元ファイルが破損している可能性があります", "출력이 비어 있습니다. 원본 파일이 손상되었을 수 있습니다") }
                // 先删旧目标再改名的话，改名失败时旧文件已经没了；直接替换，失败时它原样留着
                replace(partFile.toPath(), Path.of(destinationPath))
            } finally {
                if (partFile.exists()) deleteOrLog(partFile)
            }
            Result.success(destinationPath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 没有按偏移读的来源时只认本机文件：http 直读绕过 SDK 的缓存与连接预算，见 FfmpegRemuxer
    private fun localSource(path: String): RandomAccessMediaSource {
        val file = File(path)
        require(file.isFile) { tr("找不到源文件", "Source file not found", "元ファイルが見つかりません", "원본 파일을 찾을 수 없습니다") }
        return FileSource(file)
    }

    private fun replace(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun deleteOrLog(file: File) {
        if (!file.delete() && file.exists()) PikoLog.w(TAG, "转封装的临时文件删不掉")
    }

    private class FileSource(file: File) : RandomAccessMediaSource {
        private val input = RandomAccessFile(file, "r")
        override val size: Long = input.length()

        override suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            input.seek(position)
            return input.read(buffer, offset, length)
        }

        override fun close() = input.close()
    }

    private companion object {
        const val TAG = "segment"
        const val PART_SUFFIX = ".part"
    }
}
