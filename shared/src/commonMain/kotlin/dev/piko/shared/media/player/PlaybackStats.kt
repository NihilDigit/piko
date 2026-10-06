package dev.piko.shared.media.player

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
        read("video-format")?.let { "编码" to it.uppercase() },
        if (width != null && height != null) "分辨率" to "$width × $height" else null,
        read("container-fps")?.toDoubleOrNull()?.let { "帧率" to formatFps(it) },
        read("video-params/pixelformat")?.let { "像素格式" to it },
        read("video-params/gamma")?.takeIf { it in HDR_TRANSFERS }?.let { "HDR" to it.uppercase() },
        "解码" to (read("hwdec-current")?.takeUnless { it == "no" }?.let { "硬件（$it）" } ?: "软件"),
        read("video-bitrate")?.toDoubleOrNull()?.let { "码率" to formatBitrate(it) },
        frames(read("estimated-frame-number"), read("estimated-frame-count"))?.let { "帧" to it },
        // 两种丢帧分开写：渲染丢帧多是显示跟不上，解码丢帧是解码跟不上，排查方向不同
        read("frame-drop-count")?.let { "渲染丢帧" to it },
        read("decoder-frame-drop-count")?.let { "解码丢帧" to it },
    ).takeIf { width != null }.orEmpty()

    val channels = read("audio-params/channel-count")
    val audio = listOfNotNull(
        read("audio-codec-name")?.let { "编码" to it.uppercase() },
        channels?.let { "声道" to it },
        read("audio-params/samplerate")?.toIntOrNull()?.let { "采样率" to "${it / 1000.0} kHz".replace(".0 ", " ") },
        read("audio-bitrate")?.toDoubleOrNull()?.let { "码率" to formatBitrate(it) },
        read("current-ao")?.let { "输出" to it },
    )

    val stream = listOfNotNull(
        read("file-format")?.let { "容器" to it },
        read("demuxer-cache-duration")?.toDoubleOrNull()?.let { "已缓冲" to "${it.roundToInt()} 秒" },
        read("cache-speed")?.toDoubleOrNull()?.let { "读取速度" to formatByteRate(it) },
    )

    return listOf(
        PlaybackStatsSection("视频", video),
        PlaybackStatsSection("音频", audio),
        PlaybackStatsSection("数据流", stream),
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
