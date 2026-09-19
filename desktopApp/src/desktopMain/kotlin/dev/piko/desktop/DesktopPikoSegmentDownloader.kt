package dev.piko.desktop

import dev.piko.shared.download.PikoSegmentDownloader
import dev.piko.shared.download.PikoSegmentRequest

class DesktopPikoSegmentDownloader : PikoSegmentDownloader {
    override suspend fun extract(
        request: PikoSegmentRequest,
        onProgress: suspend (Float) -> Unit,
    ): Result<String> = Result.failure(
        UnsupportedOperationException("桌面端分段抽取需要 MediaMP/FFmpeg 时间轴实现"),
    )
}
