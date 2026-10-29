package dev.piko.shared.media.player

import dev.piko.shared.i18n.tr
import kotlin.math.roundToInt

/** 播放详细信息面板的一节：标题与若干「名称：值」行。读不到的项不列。 */
data class PlaybackStatsSection(val title: String, val rows: List<Pair<String, String>>)

/**
 * 从 mpv 属性读出播放详细信息，两端共用。[property] 是后端的 mpvProperty，读不到返回 null。
 * 属性名见 mpv 手册的 Property List；数值属性按字符串读回，在这里换成读得懂的单位。
 */
fun readPlaybackStats(property: (String) -> String?): List<PlaybackStatsSection> {
    fun read(name: String) = property(name)?.takeIf { it.isNotBlank() }

    val width = read("video-params/w")
    val height = read("video-params/h")
    val video = listOfNotNull(
        read("video-format")?.let { tr("编码", "Codec", "コーデック", "코덱") to it.uppercase() },
        if (width != null && height != null) tr("分辨率", "Resolution", "解像度", "해상도") to "$width × $height" else null,
        read("container-fps")?.toDoubleOrNull()?.let { tr("帧率", "Frame rate", "フレームレート", "프레임 레이트") to formatFps(it) },
        read("video-params/pixelformat")?.let { tr("像素格式", "Pixel format", "ピクセル形式", "픽셀 형식") to it },
        read("video-params/gamma")?.takeIf { it in HDR_TRANSFERS }?.let { "HDR" to it.uppercase() },
        tr("解码", "Decoder", "デコード", "디코딩") to (read("hwdec-current")?.takeUnless { it == "no" }?.let { tr("硬件（$it）", "Hardware ($it)", "ハードウェア（$it）", "하드웨어($it)") } ?: tr("软件", "Software", "ソフトウェア", "소프트웨어")),
        read("video-bitrate")?.toDoubleOrNull()?.let { tr("码率", "Bitrate", "ビットレート", "비트레이트") to formatBitrate(it) },
        frames(read("estimated-frame-number"), read("estimated-frame-count"))?.let { tr("帧", "Frames", "フレーム", "프레임") to it },
        // 两种丢帧分开写：渲染丢帧多是显示跟不上，解码丢帧是解码跟不上，排查方向不同
        read("frame-drop-count")?.let { tr("渲染丢帧", "Dropped frames (VO)", "描画ドロップフレーム", "렌더링 드롭 프레임") to it },
        read("decoder-frame-drop-count")?.let { tr("解码丢帧", "Dropped frames (decoder)", "デコードドロップフレーム", "디코더 드롭 프레임") to it },
    ).takeIf { width != null }.orEmpty()

    val channels = read("audio-params/channel-count")
    val audio = listOfNotNull(
        read("audio-codec-name")?.let { tr("编码", "Codec", "コーデック", "코덱") to it.uppercase() },
        channels?.let { tr("声道", "Channels", "チャンネル", "채널") to it },
        read("audio-params/samplerate")?.toIntOrNull()?.let { tr("采样率", "Sample rate", "サンプルレート", "샘플 레이트") to "${it / 1000.0} kHz".replace(".0 ", " ") },
        read("audio-bitrate")?.toDoubleOrNull()?.let { tr("码率", "Bitrate", "ビットレート", "비트레이트") to formatBitrate(it) },
        read("current-ao")?.let { tr("输出", "Output", "出力", "출력") to it },
    )

    val stream = listOfNotNull(
        read("file-format")?.let { tr("容器", "Container", "コンテナ", "컨테이너") to it },
        read("demuxer-cache-duration")?.toDoubleOrNull()?.let { tr("已缓冲", "Buffered", "バッファ済み", "버퍼링됨") to tr("${it.roundToInt()} 秒", "${it.roundToInt()} s", "${it.roundToInt()} 秒", "${it.roundToInt()}초") },
        read("cache-speed")?.toDoubleOrNull()?.let { tr("读取速度", "Read speed", "読み込み速度", "읽기 속도") to formatByteRate(it) },
    )

    return listOf(
        PlaybackStatsSection(tr("视频", "Video", "映像", "비디오"), video),
        PlaybackStatsSection(tr("音频", "Audio", "音声", "오디오"), audio),
        PlaybackStatsSection(tr("数据流", "Stream", "ストリーム", "스트림"), stream),
    ).filter { it.rows.isNotEmpty() }
}

// mpv 的 gamma 是传输特性：pq 与 hlg 才是 HDR，bt.1886、srgb 这类是 SDR
private val HDR_TRANSFERS = setOf("pq", "hlg")

private fun formatFps(fps: Double): String {
    val rounded = (fps * 1000).roundToInt() / 1000.0
    return "${rounded.toString().removeSuffix(".0")} fps"
}

private fun formatBitrate(bitsPerSecond: Double): String = when {
    bitsPerSecond >= 1_000_000 -> "${(bitsPerSecond / 100_000).roundToInt() / 10.0} Mbps"
    else -> "${(bitsPerSecond / 1000).roundToInt()} kbps"
}

private fun formatByteRate(bytesPerSecond: Double): String = when {
    bytesPerSecond >= 1024 * 1024 -> "${(bytesPerSecond / (1024 * 1024) * 10).roundToInt() / 10.0} MB/s"
    else -> "${(bytesPerSecond / 1024).roundToInt()} KB/s"
}

// 当前第几帧与全片约多少帧，给丢帧数一个参照。两者都是 mpv 按时间与帧率估的，不是逐帧计数
private fun frames(current: String?, total: String?): String? {
    val number = current?.toLongOrNull() ?: return null
    val count = total?.toLongOrNull()
    return if (count != null && count > 0) "$number / $count" else "$number"
}
