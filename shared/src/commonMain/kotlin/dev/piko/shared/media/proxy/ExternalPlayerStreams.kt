package dev.piko.shared.media.proxy

import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFile
import dev.piko.shared.media.PikoMediaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "ExternalPlayer"

/**
 * 为外部播放器开一个代理会话，返回 127.0.0.1 的地址。
 *
 * 取原画：外部播放器自己解码，也不会随网速换清晰度。没有 gcid 或代理不可用时建不起代理会话，
 * 这时宁可失败也不交出直链：直链几小时后过期，外部播放器拖动或续播时就断了，代理会重取。
 */
suspend fun PikoMediaRepository.openForExternalPlayer(fileId: String): Result<String> {
    val playback = preparePlayback(fileId).getOrElse { return Result.failure(it) }
    val fileLabel = logFile(fileId, playback.info.name)
    val url = playback.proxyUrl
    if (url == null) {
        val missingHash = playback.info.gcid.isBlank()
        PikoLog.w(TAG, "Failed to open external player stream: $fileLabel, reason=${if (missingHash) "missing_gcid" else "proxy_unavailable"}")
        playback.close()
        val message = if (missingHash) {
            tr("文件缺少内容哈希，无法读取", "File is missing content hash and cannot be read", "ファイルにハッシュ情報がないため読み込めません", "파일에 콘텐츠 해시가 없어 읽을 수 없습니다", "檔案缺少內容雜湊值，無法讀取")
        } else {
            tr("无法创建本地播放会话", "Unable to create local playback session", "ローカル再生セッションを作成できません", "로컬 재생 세션을 만들 수 없습니다", "無法建立本機播放工作階段")
        }
        return Result.failure(IllegalStateException(message))
    }
    try {
        ExternalPlayerStreams.retain(fileLabel, playback)
    } catch (e: CancellationException) {
        playback.close()
        throw e
    }
    PikoLog.i(TAG, "Opened external player stream: $fileLabel")
    return Result.success(url)
}

/**
 * 交给外部播放器的会话。
 *
 * 外部播放器何时停播、何时再拖动，Piko 无从得知，会话只能留到进程结束或被后续会话顶掉。
 * 每个原画会话持有本机代理会话、SDK handle 与共享稀疏缓存池租约，所以只留最近几个，更早的关掉，那边再请求就是 404。
 * 每次交出都新开会话，不借用应用内播放器的：那个会话随应用内播放器退出而关闭，外部播放器会跟着断。
 */
private object ExternalPlayerStreams {
    private const val CAPACITY = 2

    private class RetainedStream(val label: String, val stream: AutoCloseable)

    private val lock = Mutex()
    private val retained = ArrayDeque<RetainedStream>()

    suspend fun retain(label: String, stream: AutoCloseable) {
        val evicted = lock.withLock {
            retained.addLast(RetainedStream(label, stream))
            buildList { while (retained.size > CAPACITY) add(retained.removeFirst()) }
        }
        evicted.forEach { old ->
            PikoLog.i(TAG, "Closing evicted external player stream (capacity=$CAPACITY): ${old.label}")
            runCatching { old.stream.close() }
                .onFailure { PikoLog.w(TAG, "Failed to close evicted external player stream: ${old.label}", it) }
        }
    }
}
