package dev.piko.shared.media.proxy

import dev.piko.shared.i18n.tr
import dev.piko.shared.media.PikoMediaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 为外部播放器开一个代理会话，返回 127.0.0.1 的地址。
 *
 * 取原画：外部播放器自己解码，也不会随网速换清晰度。没有 gcid 时建不起代理会话，
 * 这时宁可失败也不交出直链：直链几小时后过期，外部播放器拖动或续播时就断了，代理会重取。
 */
suspend fun PikoMediaRepository.openForExternalPlayer(fileId: String): Result<String> {
    val playback = preparePlayback(fileId).getOrElse { return Result.failure(it) }
    val url = playback.proxyUrl
    if (url == null) {
        playback.close()
        return Result.failure(IllegalStateException(tr("文件缺少内容哈希，无法读取", "File is missing content hash and cannot be read", "ファイルにハッシュ情報がないため読み込めません", "파일에 콘텐츠 해시가 없어 읽을 수 없습니다", "檔案缺少內容雜湊值，無法讀取")))
    }
    try {
        ExternalPlayerStreams.retain(playback)
    } catch (e: CancellationException) {
        playback.close()
        throw e
    }
    return Result.success(url)
}

/**
 * 交给外部播放器的会话。
 *
 * 外部播放器何时停播、何时再拖动，Piko 无从得知，会话只能留到进程结束。每个会话背后是一个 SDK
 * handle，带至多 64 MiB 的块缓存，所以只留最近几个，更早的关掉，那边再请求就是 404。
 * 每次交出都新开会话，不借用应用内播放器的：那个会话随应用内播放器退出而关闭，外部播放器会跟着断。
 */
private object ExternalPlayerStreams {
    private const val CAPACITY = 2

    private val lock = Mutex()
    private val retained = ArrayDeque<AutoCloseable>()

    suspend fun retain(stream: AutoCloseable) {
        val evicted = lock.withLock {
            retained.addLast(stream)
            buildList { while (retained.size > CAPACITY) add(retained.removeFirst()) }
        }
        evicted.forEach { runCatching { it.close() } }
    }
}
