package dev.piko.shared.log

import dev.piko.shared.data.VaultEntry
import io.github.nihildigit.pikpak.InstantContentUnavailableException
import io.github.nihildigit.pikpak.PikPakException
import kotlin.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.io.IOException
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
        class Clear(val done: CompletableDeferred<Unit>) : Request
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
            // 过期的记录在写入第一行之前删：此时还没打开文件，改写不会与追加相撞
            files.dropBefore(timestamp(Clock.System.now().toEpochMilliseconds() - RETENTION_MILLIS, zone))
            fun handle(request: Request) = when (request) {
                is Request.Line -> files.append(format(request, zone))
                is Request.Flush -> request.done.complete(files.flush()).let {}
                is Request.Export -> request.result.complete(files.readAll()).let {}
                is Request.Clear -> request.done.complete(files.clear()).let {}
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

    private fun format(line: Request.Line, zone: TimeZone): String = buildString {
        append(timestamp(line.epochMillis, zone))
        append(' ').append(line.level.letter).append(' ').append(line.tag).append(": ").append(redact(line.message)).append('\n')
        if (line.error != null) append(redact(line.error.stackTraceToString().trimEnd())).append('\n')
    }

    /** 每行开头的时间，定宽且按年月日时分秒排列，按字符串比较即按时间先后，过期清理靠这一点。 */
    private fun timestamp(epochMillis: Long, zone: TimeZone): String {
        val time = kotlin.time.Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)
        return "${time.date} ${time.hour.pad(2)}:${time.minute.pad(2)}:${time.second.pad(2)}.${(time.nanosecond / 1_000_000).pad(3)}"
    }

    /**
     * 只留最近两天。按大小滚动的文件在用得少的机器上能存好几个月，导出时旧版本的崩溃与早已删掉的
     * 调试输出混在里面，读的人分不清哪些与这次的问题有关。
     */
    private const val RETENTION_MILLIS = 2L * 24 * 60 * 60 * 1000

    /**
     * 写进文件之前的兜底脱敏。打日志的地方已经不写账号与文件名，可异常信息与堆栈不归我们措辞：
     * 读写失败的异常带着本机路径（含用户名与文件名），服务端的错误说明偶尔带着邮箱。
     * 路径只留扩展名，播放与解压走哪条路要看它。
     */
    private fun redact(text: String): String =
        text.replace(REMOTE_URL) { "${it.groupValues[1]}/<略>" }
            .replace(EMAIL, "<邮箱>")
            .replace(PHONE, "<手机号>")
            .replace(LOCAL_PATH) { match ->
                val extension = match.value.substringAfterLast('.', "").takeIf { it.length in 1..8 && it.all(Char::isLetterOrDigit) }
                "<路径>" + (extension?.let { ".$it" } ?: "")
            }

    // 网络异常的信息里常带着请求地址。直链的签名参数在有效期内就是下载凭据，路径里还可能有文件名，
    // 只留协议与域名，排查时知道是哪台服务器就够了。本机回环代理的地址不含这些，照原样留着
    private val REMOTE_URL = Regex("""(https?://(?!127\.0\.0\.1|localhost)[^/\s"'<>]+)/[^\s"'<>]*""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val PHONE = Regex("""(?<![0-9])1[3-9][0-9]{9}(?![0-9])""")

    // Windows 盘符路径、Android 与桌面的常见绝对路径、content: 与 file: URI。文件名里可以有空格，
    // 所以一直吃到行尾、引号或 Java 异常惯用的「 (原因)」之前
    private val LOCAL_PATH = Regex(
        """(?:[A-Za-z]:[\\/]|/(?:storage|sdcard|data|Users|home|mnt|Volumes|private|var|tmp)/|content://|file:/)""" +
            """[^"'<>|\r\n]*?(?=\s\(|["'<>|]|$)""",
        RegexOption.MULTILINE,
    )

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

    /** 删掉全部日志，之后的记录从空文件开始。用户在复现问题之前清一次，导出的就只有这一次的经过。 */
    suspend fun clear() {
        if (!installed) return
        val done = CompletableDeferred<Unit>()
        requests.send(Request.Clear(done))
        done.await()
    }

    private fun Int.pad(width: Int) = toString().padStart(width, '0')
}

/**
 * 日志里指代一个文件：只写 ID 与扩展名，不写文件名。网盘里的文件名是用户的隐私，而导出的日志要发给
 * 别人；ID 足以在网盘里对上是哪一个，扩展名决定走哪条播放与解压路径，排查时要看。
 */
fun logFile(id: String?, name: String): String {
    val extension = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 8 }
    // 归档条目的 ID 里带着 gcid、大小与文件名，只留条目自己的 ID
    val shown = id?.let { VaultEntry.entryIdOf(it)?.let { entryId -> "归档 $entryId" } ?: it } ?: "?"
    return "文件 $shown" + (extension?.let { "（.${it.lowercase()}）" } ?: "")
}

/** 失败时记一条警告并原样返回，接在给用户看的 onFailure 前面：提示只有一句话，异常本身留在日志里。 */
fun <T> Result<T>.logFailure(tag: String, message: String): Result<T> = onFailure { PikoLog.w(tag, message, it) }

/**
 * 用户做 [action]（「保存」「移动」）失败时看到的一句。能说清原因、用户能照着处理的写原因，其余只说失败了：
 * 服务端的错误名、HTTP 状态与英文描述用户读不懂，也做不了什么，只进日志，见 [reportFailure]。
 */
fun failureText(action: String, error: Throwable): String = when {
    error is InstantContentUnavailableException -> "云端暂无该文件内容，需离线下载"
    error is PikPakException && error.errorMessage == "file_duplicated_name" -> "${action}失败：目标位置已有同名文件"
    error is IOException -> "${action}失败：网络连接中断，请重试"
    else -> "${action}失败，请重试"
}

/** 失败时记一条日志，再把 [failureText] 交给 [show]。日志与提示出自同一处，两边说的是同一件事。 */
fun <T> Result<T>.reportFailure(tag: String, action: String, show: (String) -> Unit): Result<T> = onFailure {
    PikoLog.w(tag, "${action}失败", it)
    show(failureText(action, it))
}

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

    fun clear() {
        runCatching { sink?.close() }
        sink = null
        size = 0
        for (index in 0..KEPT_FILES) runCatching { SystemFileSystem.delete(fileAt(index), mustExist = false) }
    }

    /**
     * 删掉时间早于 [cutoff] 的记录，[cutoff] 与行首时间同一格式。只在打开文件写入之前调用。
     * 没有时间戳的续行（异常堆栈）属于上一条，随它去留。文件之间与文件之内都按时间先后排列，
     * 所以每个文件只需找到第一条不早于 [cutoff] 的记录，之前的整段丢掉，一条都不剩的整个删掉。
     */
    fun dropBefore(cutoff: String) {
        for (index in 0..KEPT_FILES) {
            val file = fileAt(index)
            runCatching {
                if (!SystemFileSystem.exists(file)) return@runCatching
                val text = SystemFileSystem.source(file).buffered().use { it.readString() }
                when (val keepFrom = firstEntryNotBefore(text, cutoff)) {
                    null -> SystemFileSystem.delete(file)
                    0 -> Unit
                    else -> SystemFileSystem.sink(file).buffered().use { it.writeString(text.substring(keepFrom)) }
                }
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

/** 一条记录的开头：行首的时间戳，取到时间戳本身为止。 */
private val ENTRY_START = Regex("""^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}""", RegexOption.MULTILINE)

/** [text] 里第一条时间不早于 [cutoff] 的记录从哪个字符开始；一条都没有时为 null。 */
internal fun firstEntryNotBefore(text: String, cutoff: String): Int? =
    ENTRY_START.findAll(text).firstOrNull { it.value >= cutoff }?.range?.first
