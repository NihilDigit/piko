package dev.piko.shared.state

import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.logFile
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.media.PreparedClip
import io.github.nihildigit.pikpak.StreamRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * 队列里各段的代理会话与预取。取流慢：查详情、取直链、连上 CDN、读到容器头与起点附近的数据，
 * 前后要好几秒，所以队列里的每一段都提前备好并预取，轮到播放器装它时读缓存；出了队列的关掉。
 *
 * 候补里的段各自备会话、预取，取好的交给 [onReady] 接进翻页器，取不好的交给 [onDead]，见 [ripen]。
 * 翻页器里因此只有取好的段，翻到哪一段都有得放。
 *
 * 归 [ClipFeedSession] 所有，与进程同寿，换文件夹或关掉信息流时才清空，不随页面走：Android 上「看完整」是压在
 * 信息流之上的一页，信息流离开组合，原先挂在页面上的这份跟着关掉，回来时每段重备会话、重取开头，
 * 当前那段五秒多才重新走起来（2026-09-28）。播放器仍随页面，压栈页要用画面。
 *
 * 每段各自一个协程，不挂在会随翻页取消的任务上：连着快翻时每翻一页任务就重来，挂在上面的永远等不到取完。
 * 备会话要查详情，同时至多 [PREPARE_LANES] 个，几十段一起查容易被限流。
 * 空闲的会话不占连接，只占内存：每段的 reader 缓存着预取的与播过的部分。
 *
 * 主线程上的作用域，映射只在主线程上改，不必加锁。
 */
class ClipStreams internal constructor(
    private val repository: PikoMediaRepository,
    private val onReady: (Clip) -> Unit,
    private val onDead: (Clip) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val streams = mutableMapOf<Clip, Deferred<PreparedClip?>>()
    private val ripening = mutableSetOf<Clip>()
    private var foreground: Set<Clip> = emptySet()
    private val preparing = Semaphore(PREPARE_LANES)

    /**
     * 备好的会话一律以后台建起，装进播放器的由 [bringToFront] 升为前台。来源见 PikoMediaRepository.prepareClip。
     *
     * [forPlayer] 是播放器马上要装的，不跟候补排队备会话：接着上次看的那一段要现备，排在候补的二十几段后面
     * 实测等了七八秒。这种至多三段，不至于招来限流。
     */
    fun get(clip: Clip, forPlayer: Boolean = false, urgent: Boolean = false): Deferred<PreparedClip?> =
        streams.getOrPut(clip) {
            // 要去取开头的段也以前台身份备会话：找关键帧读的就是开头，以后台身份读，槽满时排在所有预取之后
            val role = if (clip in foreground || urgent) StreamRole.FOREGROUND else StreamRole.BACKGROUND
            scope.async {
                val started = TimeSource.Monotonic.markNow()
                suspend fun prepare() = repository.prepareClip(clip.fileId, clip.startMs, clip.videoDurationMs, role)
                    .logFailure(TAG, "备会话失败 ${logFile(clip.fileId, clip.name)}，${started.elapsedNow().inWholeMilliseconds} ms").getOrNull()
                val prepared = (if (forPlayer) prepare() else preparing.withPermit { prepare() }) ?: return@async null
                prepared.holdReadAhead()
                PikoLog.d(
                    TAG,
                    "备好会话 ${logFile(clip.fileId, clip.name)}（${if (prepared.fromDisk) "磁盘切片" else if (prepared.sliced) "转码切片" else "原画"}，" +
                        "${(prepared.streamBytes ?: 0) / 1024} KiB）：${started.elapsedNow().inWholeMilliseconds} ms，$role",
                )
                prepared
            }
        }

    /**
     * 让候补里还没开始的段各自去取：备会话、预取开头，取好了交给 [onReady]。
     *
     * 预取以前台身份发出，会话本身仍是后台：以后台身份，有段在放时每段只有两路在途，实测取好一段要五六秒，
     * 连着翻就供不上。前台预取排在正在放的段之后，同一档里先提出的先取完（SDK 按需求先后排），
     * 所以按队列顺序调用，最近要翻到的最先好，不必再自己限同时取几段。
     * 取不到流的交给 [onDead]：会话打不开，或者 SDK 换过主机、重试过仍取不下来。
     */
    fun ripen(upcoming: List<Clip>) {
        for (clip in upcoming) {
            if (!ripening.add(clip)) continue
            scope.launch {
                val prepared = get(clip, urgent = true).await()
                val fetched = prepared != null && fetchOnce(clip, prepared)
                ripening -= clip
                if (fetched) {
                    onReady(clip)
                } else {
                    // 会话一并关掉：重取时重新备，拿新的直链。扔不扔由会话定，见 ClipFeedSession.drop
                    PikoLog.d(TAG, "取不到 ${logFile(clip.fileId, clip.name)}")
                    streams.remove(clip)?.let(::close)
                    onDead(clip)
                }
            }
        }
    }

    private suspend fun fetchOnce(clip: Clip, prepared: PreparedClip): Boolean {
        val started = TimeSource.Monotonic.markNow()
        val fetched = try {
            withTimeoutOrNull(RIPEN_TIMEOUT) { withContext(Dispatchers.Default) { prepared.prefetch(StreamRole.FOREGROUND) } } != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PikoLog.w(TAG, "预取 ${logFile(clip.fileId, clip.name)} 失败", e)
            false
        }
        if (fetched) PikoLog.d(TAG, "取好 ${logFile(clip.fileId, clip.name)}：预取 ${started.elapsedNow().inWholeMilliseconds} ms")
        else PikoLog.d(TAG, "预取 ${logFile(clip.fileId, clip.name)} 未完成：${started.elapsedNow().inWholeMilliseconds} ms，上限 ${RIPEN_TIMEOUT}")
        return fetched
    }

    /**
     * 装在播放器里的几段升为前台，其余降为后台。都不丢缓存。
     *
     * 不只当前段：下一段要在翻过去之前预渲染好第一帧，mpv 为此要依次读文件头、文件尾的索引与起点，
     * 每一处都是一次新请求。以后台身份，它们与远处的预取同排在最后，只有两路在途，
     * 实测一处要等 0.6 到 1.5 秒，几处串起来就赶不上翻页。
     */
    fun bringToFront(clips: Set<Clip>) {
        val demoted = foreground - clips
        foreground = clips
        for ((clip, role) in demoted.map { it to StreamRole.BACKGROUND } + clips.map { it to StreamRole.FOREGROUND }) {
            streams[clip]?.let { stream -> scope.launch { runCatching { stream.await() }.getOrNull()?.role = role } }
        }
    }

    /** [clip] 真在看了，代理往后预读 [seconds] 秒，见 PreparedClip.extendReadAhead。 */
    fun boost(clip: Clip, seconds: Int) {
        streams[clip]?.let { stream -> scope.launch { runCatching { stream.await() }.getOrNull()?.extendReadAhead(seconds) } }
    }

    /** 除 [clip] 外都压回只读开头，也不再算有人在等；升过档的段翻走后不该再往后取。 */
    fun holdAllBut(clip: Clip) {
        for ((other, stream) in streams) {
            if (other != clip) {
                scope.launch {
                    runCatching { stream.await() }.getOrNull()?.let {
                        it.holdReadAhead()
                        it.urgent = false
                    }
                }
            }
        }
    }

    /** [clip] 是否有人正等着，见 PreparedClip.urgent。 */
    fun setUrgent(clip: Clip, urgent: Boolean) {
        streams[clip]?.let { stream -> scope.launch { runCatching { stream.await() }.getOrNull()?.urgent = urgent } }
    }

    /** 翻出 [window] 的段关掉会话；看过的在窗口里保留缓存，不再为它发请求。 */
    fun sync(window: Set<Clip>) {
        (streams.keys - window).forEach { clip -> close(streams.remove(clip)!!) }
    }

    fun closeAll() {
        scope.coroutineContext[Job]?.cancelChildren()
        streams.values.forEach(::close)
        streams.clear()
        ripening.clear()
        foreground = emptySet()
    }

    // 还没备好的先取消；取消前刚好备好的，照样关掉
    private fun close(stream: Deferred<PreparedClip?>) {
        stream.cancel()
        scope.launch { runCatching { stream.await() }.getOrNull()?.close() }
    }

    private companion object {
        const val TAG = "Clips"

        /** 同时备会话的段数。每段要查一次详情，再探一次转码流的长度。 */
        const val PREPARE_LANES = 4

        /**
         * 候补的一段这么久还没取完开头，当它取不到，见 [ripen]。只兜住一直在慢慢出字节、不报错的主机；
         * 坏主机与断流 SDK 自己会换、会报错。计时从请求起，而请求一齐发出、按先后取，排在第八段的要等前七段，
         * 在 1 MB/s 的线路上约 16 秒，所以留得宽。
         */
        val RIPEN_TIMEOUT = 60.seconds
    }
}
