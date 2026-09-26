package dev.piko.shared.log

import kotlin.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

enum class LogLevel(val letter: Char) { DEBUG('D'), INFO('I'), WARN('W'), ERROR('E') }

/**
 * 全局日志，写进 [install] 给的目录下的滚动文件，用户在设置里导出后随反馈交回。
 *
 * 是全局对象而不是注入的依赖：日志要能从任何一层打，逐层传一个 logger 进每个类不划算。
 * DEBUG 起全部落盘：日志只在用户反馈时才被读到，那时缺的那一条补不回来，而几 MB 的上限足够装下
 * 出问题前后的经过。不要写入密码、令牌与完整的直链（签名参数即凭据）。
 *
 * 打日志的线程只把一行投进无界 channel，由一个协程串行写文件，不在调用方做 IO，也不用锁；
 * [install] 之前打的日志留在 channel 里，装上后照常写入。
 */
object PikoLog {
    private sealed interface Request {
        class Line(val epochMillis: Long, val level: LogLevel, val tag: String, val message: String, val error: Throwable?) : Request
        class Flush(val done: CompletableDeferred<Unit>) : Request
        class Export(val result: CompletableDeferred<String>) : Request
    }

    private val requests = Channel<Request>(Channel.UNLIMITED)

    @kotlin.concurrent.Volatile
    private var echo: ((LogLevel, String, String, Throwable?) -> Unit)? = null

    @kotlin.concurrent.Volatile
    private var installed = false

    /**
     * 开始写入 [directory]。[echo] 把每条同时交给平台自己的输出（logcat、标准错误），开发时看得到。
     * 只生效一次。
     */
    fun install(directory: String, echo: ((LogLevel, String, String, Throwable?) -> Unit)? = null) {
        if (installed) return
        installed = true
        this.echo = echo
        val files = RollingLogFiles(Path(directory))
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val zone = TimeZone.currentSystemDefault()
            fun handle(request: Request) = when (request) {
                is Request.Line -> files.append(format(request, zone))
                is Request.Flush -> request.done.complete(files.flush()).let {}
                is Request.Export -> request.result.complete(files.readAll()).let {}
            }
            while (true) {
                handle(requests.receive())
                // 攒着的一批写完再落盘：连续打日志时不必每行一次系统调用，停下来时文件已是完整的
                while (true) handle(requests.tryReceive().getOrNull() ?: break)
                files.flush()
            }
        }
    }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message, null)

    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) = log(LogLevel.WARN, tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) = log(LogLevel.ERROR, tag, message, error)

    /**
     * 调用方只取一次时钟、投一个对象，时区换算、拼行与展开堆栈都在写入协程里做，不占打日志的线程。
     * 即便如此也不要在逐帧、逐块读写这类热路径上打日志：几 MB 的上限会被刷掉，真正有用的那几行跟着滚没了。
     */
    fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
        echo?.invoke(level, tag, message, error)
        requests.trySend(Request.Line(Clock.System.now().toEpochMilliseconds(), level, tag, message, error))
    }

    private fun format(line: Request.Line, zone: TimeZone): String {
        val time = kotlin.time.Instant.fromEpochMilliseconds(line.epochMillis).toLocalDateTime(zone)
        return buildString {
            append(time.date).append(' ')
            append(time.hour.pad(2)).append(':').append(time.minute.pad(2)).append(':').append(time.second.pad(2))
            append('.').append((time.nanosecond / 1_000_000).pad(3))
            append(' ').append(line.level.letter).append(' ').append(line.tag).append(": ").append(line.message).append('\n')
            if (line.error != null) append(line.error.stackTraceToString().trimEnd()).append('\n')
        }
    }

    /** 等此前的日志都写进文件。崩溃处理在进程结束前调用，否则最后那几行、包括崩溃本身，还在内存里。 */
    suspend fun flush() {
        if (!installed) return
        val done = CompletableDeferred<Unit>()
        requests.send(Request.Flush(done))
        done.await()
    }

    /** 全部日志文件按时间先后接成一段文本。在写入协程里读，读的时候不会正好赶上换文件。 */
    suspend fun export(): String {
        if (!installed) return ""
        val result = CompletableDeferred<String>()
        requests.send(Request.Export(result))
        return result.await()
    }

    private fun Int.pad(width: Int) = toString().padStart(width, '0')
}

/** 失败时记一条警告并原样返回，接在给用户看的 onFailure 前面：提示只有一句话，异常本身留在日志里。 */
fun <T> Result<T>.logFailure(tag: String, message: String): Result<T> = onFailure { PikoLog.w(tag, message, it) }

/**
 * piko.log 写满 [MAX_FILE_BYTES] 就改名为 piko.1.log，旧的依次后移，最多留 [KEPT_FILES] 份旧文件。
 * 写失败一律忽略：日志本身出错时无处可报，也不能因此影响应用。
 */
private class RollingLogFiles(private val directory: Path) {
    private var sink: Sink? = null
    private var size = 0L

    fun flush() {
        runCatching { sink?.flush() }
    }

    fun append(text: String) {
        runCatching {
            val bytes = text.encodeToByteArray()
            if (size > 0 && size + bytes.size > MAX_FILE_BYTES) rotate()
            val out = sink ?: open()
            out.write(bytes)
            size += bytes.size
        }
    }

    fun readAll(): String = buildString {
        flush()
        for (index in KEPT_FILES downTo 0) {
            val file = fileAt(index)
            runCatching {
                if (SystemFileSystem.exists(file)) append(SystemFileSystem.source(file).buffered().use { it.readString() })
            }
        }
    }

    private fun open(): Sink {
        SystemFileSystem.createDirectories(directory)
        val file = fileAt(0)
        size = SystemFileSystem.metadataOrNull(file)?.size ?: 0L
        return SystemFileSystem.sink(file, append = true).buffered().also { sink = it }
    }

    private fun rotate() {
        sink?.close()
        sink = null
        size = 0
        val oldest = fileAt(KEPT_FILES)
        if (SystemFileSystem.exists(oldest)) SystemFileSystem.delete(oldest)
        for (index in KEPT_FILES - 1 downTo 0) {
            val file = fileAt(index)
            if (SystemFileSystem.exists(file)) SystemFileSystem.atomicMove(file, fileAt(index + 1))
        }
    }

    private fun fileAt(index: Int) = Path(directory, if (index == 0) "piko.log" else "piko.$index.log")

    private companion object {
        const val MAX_FILE_BYTES = 1L shl 20
        const val KEPT_FILES = 3
    }
}
