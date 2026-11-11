package dev.piko.desktop.ui.player

import dev.piko.shared.log.LogLevel
import dev.piko.shared.log.PikoLog
import org.openani.mediamp.mpv.MPVHandle
import org.openani.mediamp.mpv.MPVLog

/**
 * 把 mediamp 的日志接进应用日志：mpv 自己的、mediamp 原生层（前缀 mediampv）的、它 Kotlin 层（mediamp）的都走这一个出口。
 * 不接的话它只把警告以上 println 到标准输出，安装版没人看，GPU 设备失效这类线索（wait_for_gpu、flush query 失败）就丢了。
 *
 * 默认保留警告以上，外加 mediamp 首帧渲染就绪行（`rendering ...`）；表面尚未就绪前的互操作提示降为 DEBUG。
 * 坏流会逐包刷同几句，最近记过的不再记，做法同 Android 的 MpvPlaybackBackend.logMessage：
 * 只比上一句不够，HEVC 从切片开头解到第一个关键帧前是两句交替刷。
 * handler 是进程级的，几个播放器（主播放器、信息流里预备的几段）的 mpv 日志线程与界面线程都会调进来，所以加锁。
 */
internal object MpvLogBridge {
    private val recent = LinkedHashSet<String>()
    private val stoppingHandles = LinkedHashSet<Long>()
    private val FFMPEG_NUMBER_PATTERN = Regex("-?[0-9]+")

    fun markStopping(instanceHandle: Long) {
        synchronized(recent) {
            stoppingHandles.remove(instanceHandle)
            stoppingHandles.add(instanceHandle)
            if (stoppingHandles.size > RECENT_LINES) {
                stoppingHandles.remove(stoppingHandles.first())
            }
        }
    }

    fun clearHandle(instanceHandle: Long) {
        val marker = "[${instanceHandle.toString(16)}]: "
        synchronized(recent) {
            stoppingHandles.remove(instanceHandle)
            recent.removeIf { it.contains(marker) }
        }
    }

    fun install() {
        MPVHandle.setLogHandler { message ->
            val renderingReady = message.prefix == "mediamp" && message.line.startsWith("rendering ")
            val preSurfaceNoise = (message.prefix == "mediamp" && message.line.contains("skia interop unavailable")) ||
                ((message.prefix == "mediamp" || message.prefix == "mediampv") && message.line.contains("has no consumer-side texture")) ||
                (message.prefix == "ao/wasapi" && message.line.contains("0x88890005"))
            if (message.level > MPVLog.WARN && !renderingReady) return@setLogHandler
            val handleTag = "${message.prefix}[${message.instanceHandle.toString(16)}]: "
            val line = "$handleTag${message.line}"
            val dedupKey = if (message.prefix.startsWith("ffmpeg")) {
                "$handleTag${FFMPEG_NUMBER_PATTERN.replace(message.line, "#")}"
            } else {
                line
            }
            val fresh = synchronized(recent) {
                if (message.prefix.startsWith("ffmpeg") && message.instanceHandle in stoppingHandles) {
                    false
                } else {
                    recent.add(dedupKey).also { added ->
                        if (added && recent.size > RECENT_LINES) recent.remove(recent.first())
                    }
                }
            }
            if (fresh) PikoLog.log(when {
                preSurfaceNoise -> LogLevel.DEBUG
                message.isError -> LogLevel.ERROR
                message.level <= MPVLog.WARN -> LogLevel.WARN
                else -> LogLevel.DEBUG
            }, "Mpv", line, null)
        }
    }

    // 够盖住一组交替刷的句子，又不至于把隔了很久再出现的同一个问题也吞掉
    private const val RECENT_LINES = 16
}
