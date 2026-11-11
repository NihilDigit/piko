@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.piko.shared.download

import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.cinterop.CValue
import platform.AVFoundation.*
import platform.CoreMedia.*
import platform.Foundation.*

/**
 * iOS 的片段截取与转封装：都交给 `AVAssetExportSession` 的直通档（`AVAssetExportPresetPassthrough`），
 * 与桌面/Android 同语义——不转码，只把需要的范围重新封进 MP4，起点对齐到之前的关键帧。
 *
 * 与另外两端的差别写在明处：
 * - 桌面用 FFmpeg 的流复制、Android 用 MediaExtractor + MediaMuxer，两者都能控制 moov 位置；AVFoundation
 *   的直通导出把 moov 写在文件尾（`optimizeForNetworkUse` 只能让它更快被读到，不能前置）。播放要等
 *   文件完整才不会退回到全文件读，但下载完成即完整，因此不影响本应用的使用方式。
 * - 进度只有 `progress` 一个 0 到 1 的比例，没有按时间算的细粒度；失败时在诊断原因中附带 `NSError` 域与错误码（路径经 `PikoLog` 统一脱敏）。
 */
class IosPikoSegmentDownloader : PikoSegmentDownloader {
    override suspend fun extract(
        request: PikoSegmentRequest,
        onProgress: suspend (Float) -> Unit,
    ): Result<String> {
        if (request.endMillis <= request.startMillis) {
            return Result.failure(
                IllegalArgumentException(
                    tr("结束时间必须晚于开始时间", "End time must be after start time", "終了時刻は開始時刻より後にしてください", "종료 시간은 시작 시간보다 뒤여야 합니다", "結束時間必須晚於開始時間"),
                ),
            )
        }
        // 源是本机文件时直接读；是回环代理的 http 地址时 AVURLAsset 也能读（Android 的 MediaExtractor 不能，
        // 所以那边才要 openRandomAccess，这里不需要）
        val url = urlOf(request.sourceUrl) ?: return Result.failure(
            IllegalArgumentException(
                tr("找不到源文件", "Source file not found", "元ファイルが見つかりません", "원본 파일을 찾을 수 없습니다", "找不到來源檔案"),
            ),
        )
        val start = CMTimeMakeWithSeconds(request.startMillis / 1000.0, PREFERRED_TIMESCALE)
        val duration = CMTimeMakeWithSeconds((request.endMillis - request.startMillis) / 1000.0, PREFERRED_TIMESCALE)
        return export(url, request.destinationPath, CMTimeRangeMake(start, duration), operation = "extract", onProgress = onProgress)
    }

    override suspend fun remux(
        request: PikoRemuxRequest,
        onProgress: suspend (Float) -> Unit,
    ): Result<String> {
        val url = urlOf(request.sourcePath) ?: return Result.failure(
            IllegalArgumentException(
                tr("找不到源文件", "Source file not found", "元ファイルが見つかりません", "원본 파일을 찾을 수 없습니다", "找不到來源檔案"),
            ),
        )
        // 不设 timeRange 就是整段直通转封装：TS 里的 H.264/HEVC 与 AAC 原样搬进 MP4
        return export(url, request.destinationPath, range = null, operation = "remux", onProgress = onProgress)
    }

    private suspend fun export(
        source: NSURL,
        destinationPath: String,
        range: CValue<CMTimeRange>?,
        operation: String,
        onProgress: suspend (Float) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        val partPath = "$destinationPath$PART_SUFFIX"
        val files = NSFileManager.defaultManager
        files.removeItemAtPath(partPath, error = null)
        try {
            val asset = AVURLAsset.URLAssetWithURL(source, options = null)
            val session = AVAssetExportSession(asset = asset, presetName = AVAssetExportPresetPassthrough)
                ?: return@withContext Result.failure(
                    IllegalStateException(
                        tr("当前视频格式不支持直通导出", "This video format does not support passthrough export", "この動画形式はパススルー書き出しに対応していません", "이 동영상 형식은 패스스루 내보내기를 지원하지 않습니다", "目前影片格式不支援直通匯出"),
                    ),
                )
            range?.let { session.timeRange = it }
            session.outputURL = NSURL.fileURLWithPath(partPath)
            session.outputFileType = AVFileTypeMPEG4
            session.shouldOptimizeForNetworkUse = false

            // 导出是异步的：一边等完成回调，一边按 progress 报进度
            val completed = CompletableDeferred<Unit>()
            session.exportAsynchronouslyWithCompletionHandler { completed.complete(Unit) }
            val reporter = CoroutineScope(currentCoroutineContext()).launch {
                while (true) {
                    onProgress(session.progress.coerceIn(0f, 1f))
                    delay(PROGRESS_INTERVAL_MS)
                }
            }
            try {
                completed.await()
                when (val status = session.status) {
                    AVAssetExportSessionStatusCompleted -> Unit
                    AVAssetExportSessionStatusCancelled -> throw CancellationException("AVAssetExportSession $operation cancelled")
                    else -> {
                        val nsError = session.error
                        val errorDetail = nsError?.let {
                            "domain=${it.domain}, code=${it.code}, desc=${it.localizedDescription}"
                        } ?: "no NSError"
                        throw IllegalStateException(
                            tr("导出失败（状态 $status）", "Export failed (status $status)", "書き出しに失敗しました（状態 $status）", "내보내기 실패(상태 $status)", "匯出失敗（狀態 $status）"),
                            IllegalStateException("AVAssetExportSession($operation) failed: status=$status, $errorDetail"),
                        )
                    }
                }
            } finally {
                // cancelExport 只是发出请求。等完成回调确认原生写入停止后，外层才能删除 .part。
                withContext(NonCancellable) {
                    if (!completed.isCompleted) session.cancelExport()
                    completed.await()
                    reporter.cancelAndJoin()
                }
            }

            onProgress(1f)
            val size = fileSize(partPath)
            check(size > 0) {
                tr("导出文件为空，源文件可能已损坏", "Exported file is empty; source file may be corrupted", "書き出しファイルが空です。元ファイルが破損している可能性があります", "내보낸 파일이 비어 있습니다. 원본 파일이 손상되었을 수 있습니다", "匯出檔案為空，來源檔案可能已損壞")
            }
            // 先删旧目标再替换：替换失败时目标已经没了，但成功路径上这一步只会成功
            if (files.fileExistsAtPath(destinationPath)) files.removeItemAtPath(destinationPath, error = null)
            check(files.moveItemAtPath(partPath, destinationPath, error = null)) {
                tr("替换目标文件失败", "Failed to replace destination file", "出力先ファイルの置換に失敗しました", "대상 파일 교체에 실패했습니다", "取代目標檔案失敗")
            }
            Result.success(destinationPath)
        } catch (e: CancellationException) {
            files.removeItemAtPath(partPath, error = null)
            throw e
        } catch (e: Exception) {
            // 上层 PikoDownloadCoordinator 统一以 WARN 记录完整异常栈，此处仅以 DEBUG 留存原生导出层摘要，避免双倍堆栈刷屏
            PikoLog.d(TAG, "iOS media $operation failed: ${e.cause?.message ?: e.message}")
            files.removeItemAtPath(partPath, error = null)
            Result.failure(e)
        }
    }

    private fun urlOf(path: String): NSURL? = when {
        path.startsWith("http://") || path.startsWith("https://") -> NSURL.URLWithString(path)
        NSFileManager.defaultManager.fileExistsAtPath(path) -> NSURL.fileURLWithPath(path)
        else -> null
    }

    private fun fileSize(path: String): Long {
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null) ?: return 0
        return (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: 0
    }

    private companion object {
        const val TAG = "SegmentDownload"
        const val PART_SUFFIX = ".part"
        // 与另外两端一致：目标旁边先写 .part，成功再替换
        const val PREFERRED_TIMESCALE = 600
        const val PROGRESS_INTERVAL_MS = 200L
    }
}
