package dev.piko.shared.log

import dev.piko.shared.data.VaultEntry
import dev.piko.shared.util.PikoLock
import dev.piko.shared.util.withPikoLock
import kotlin.coroutines.cancellation.CancellationException
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
 * 全局诊断日志，写入 [install] 指定目录下的滚动文件，供用户在设置页导出随反馈提交。
 *
 * 采用全局单例而非逐层依赖注入，使任意层级均可直接记录诊断上下文；从 DEBUG 起全量落盘，但统一使用英文记录结构化事实，
 * 严禁写入密码、令牌、明文文件名与完整签名直链。
 *
 * 调用线程仅将日志行推入有界内存缓冲并发出唤醒信号，由后台单协程串行经 [LogWriter] 落盘；[install] 前产生的早期日志
 * 暂存于有界队列，安装完成后按序写入。队列满载时仅丢弃最旧的普通日志行并记数，控制请求（[flush]、[export]、[clear]）永不丢弃。
 */
object PikoLog {
    private const val MAX_BUFFERED_LINES = 2048
    private const val RETENTION_SWEEP_INTERVAL_MILLIS = 6L * 60 * 60 * 1000

    private sealed interface Request {
        class Line(val line: LogLine) : Request
        class Flush(val done: CompletableDeferred<Unit>) : Request
        class Export(val result: CompletableDeferred<String>) : Request
        class Clear(val done: CompletableDeferred<Unit>) : Request
        class Reinstall(
            val directory: Path,
            val extraExport: (() -> String)?,
            val onClear: (() -> Unit)?,
        ) : Request
    }

    private class RedactedThrowable(redactedDump: String) : Throwable(redactedDump) {
        override fun toString(): String = message.orEmpty()
    }

    private val lock = PikoLock()
    private val queue = ArrayDeque<Request>()
    private var bufferedLines = 0
    private var droppedLines = 0
    private var workerStarted = false

    private val signal = Channel<Unit>(Channel.CONFLATED)

    @kotlin.concurrent.Volatile
    private var installed = false

    @kotlin.concurrent.Volatile
    private var echo: ((LogLevel, String, String, Throwable?) -> Unit)? = null

    /**
     * 启动或切换日志落盘目录 [directory]。
     * [extraExport] 在导出日志末尾附加额外诊断段落（如桌面更新历史），[onClear] 在清空日志时同步清理外部附属日志，
     * [echo] 将脱敏后的日志同步输出至平台控制台（如 logcat 或 stderr）。
     */
    fun install(
        directory: String,
        extraExport: (() -> String)? = null,
        onClear: (() -> Unit)? = null,
        echo: ((LogLevel, String, String, Throwable?) -> Unit)? = null,
    ) {
        this.echo = echo
        val targetDir = Path(directory)
        val shouldStart = withPikoLock(lock) {
            installed = true
            if (!workerStarted) {
                workerStarted = true
                true
            } else {
                queue.addLast(Request.Reinstall(targetDir, extraExport, onClear))
                false
            }
        }
        if (shouldStart) {
            startWorker(targetDir, extraExport, onClear)
        }
        signal.trySend(Unit)
    }

    private fun startWorker(
        initialDirectory: Path,
        initialExtraExport: (() -> String)?,
        initialOnClear: (() -> Unit)?,
    ) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var extraExport = initialExtraExport
            var onClear = initialOnClear
            var writer = LogWriter(initialDirectory, TimeZone.currentSystemDefault())
            var lastSweepMillis = Clock.System.now().toEpochMilliseconds()
            runCatching { writer.dropExpired(lastSweepMillis) }

            for (unit in signal) {
                while (true) {
                    var overflowDropped = 0
                    val batch = withPikoLock(lock) {
                        if (droppedLines == 0 && queue.isEmpty()) {
                            emptyList()
                        } else {
                            overflowDropped = droppedLines
                            droppedLines = 0
                            bufferedLines = 0
                            val drained = queue.toList()
                            queue.clear()
                            drained
                        }
                    }
                    if (overflowDropped == 0 && batch.isEmpty()) break

                    if (overflowDropped > 0) {
                        runCatching {
                            writer.write(
                                LogLine(
                                    Clock.System.now().toEpochMilliseconds(),
                                    LogLevel.WARN,
                                    "Log",
                                    "Dropped $overflowDropped buffered log entries due to queue overflow",
                                    null,
                                ),
                            )
                        }
                    }

                    for (request in batch) {
                        when (request) {
                            is Request.Line -> runCatching { writer.write(request.line) }
                            is Request.Flush -> {
                                try {
                                    writer.settle()
                                } catch (_: Throwable) {
                                } finally {
                                    request.done.complete(Unit)
                                }
                            }
                            is Request.Export -> {
                                val exported = try {
                                    writer.export(extraExport)
                                } catch (_: Throwable) {
                                    ""
                                }
                                request.result.complete(exported)
                            }
                            is Request.Clear -> {
                                try {
                                    writer.clear()
                                    onClear?.invoke()
                                } catch (_: Throwable) {
                                } finally {
                                    request.done.complete(Unit)
                                }
                            }
                            is Request.Reinstall -> {
                                runCatching {
                                    writer.close()
                                    extraExport = request.extraExport
                                    onClear = request.onClear
                                    writer = LogWriter(request.directory, TimeZone.currentSystemDefault())
                                    val now = Clock.System.now().toEpochMilliseconds()
                                    writer.dropExpired(now)
                                    lastSweepMillis = now
                                }
                            }
                        }
                    }

                    runCatching { writer.flush() }
                    val now = Clock.System.now().toEpochMilliseconds()
                    if (now - lastSweepMillis >= RETENTION_SWEEP_INTERVAL_MILLIS) {
                        runCatching { writer.dropExpired(now) }
                        lastSweepMillis = now
                    }
                }
            }
        }
    }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message, null)

    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) = log(LogLevel.WARN, tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) = log(LogLevel.ERROR, tag, message, error)

    /**
     * 调用方仅读取时钟并入队，格式化与落盘在后台写入协程完成。
     * 若配置了 [echo]，回显前先对消息与异常堆栈脱敏，防止平台控制台泄露直链签名、凭据或本机路径。
     */
    fun log(level: LogLevel, tag: String, message: String, error: Throwable? = null) {
        echo?.let { callback ->
            runCatching {
                callback(level, tag, redact(message), sanitizeThrowable(error))
            }
        }
        val entry = Request.Line(LogLine(Clock.System.now().toEpochMilliseconds(), level, tag, message, error))
        val shouldWake = withPikoLock(lock) {
            if (bufferedLines >= MAX_BUFFERED_LINES) {
                val oldestLineIdx = queue.indexOfFirst { it is Request.Line }
                if (oldestLineIdx >= 0) {
                    queue.removeAt(oldestLineIdx)
                    bufferedLines--
                    droppedLines++
                }
            }
            queue.addLast(entry)
            bufferedLines++
            workerStarted
        }
        if (shouldWake) signal.trySend(Unit)
    }

    private fun sanitizeThrowable(error: Throwable?): Throwable? {
        if (error == null) return null
        val raw = runCatching { error.stackTraceToString().trimEnd() }.getOrElse { return RedactedThrowable(error::class.simpleName ?: "Throwable") }
        val safe = redact(raw)
        return if (safe == raw) error else RedactedThrowable(safe)
    }

    /**
     * 落盘与控制台回显前的兜底脱敏，保证幂等（`redact(redact(s)) == redact(s)`）：
     * - 本地挂载定位符还原为仅含挂载槽位的 `local <mountId>`；
     * - 磁力链接隐去 `dn=` 等明文名称与来源参数；
     * - JSON / Header / 键值对中的令牌、密码与 Bearer 凭据替换为 `<redacted>`；
     * - 远端 URL 剥离 Basic Auth 凭据并隐去路径与查询签名参数（豁免无凭据的本机回环地址）；
     * - 邮箱与手机号分别替换为 `<email>` 与 `<phone>`（排除文件字节数、区间偏移等大整数）；
     * - 本机绝对路径、UNC 路径与 `content://` / `file://` URI 替换为 `<path>`（保留合法扩展名如 `<path>.mkv`）。
     */
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var result = if ("piko-local-" in text) {
            text.replace(LOCAL_LOCATION) { dev.piko.shared.data.LocalLocation.logIdentity(it.value) ?: "local ?" }
        } else {
            text
        }
        if (result.contains("magnet:", ignoreCase = true)) {
            result = result.replace(MAGNET_URI) { match ->
                match.value.replace(MAGNET_SENSITIVE_PARAM) { param -> "${param.groupValues[1]}<redacted>" }
            }
        }
        if (containsSecretKeyword(result)) {
            result = result
                .replace(JSON_SECRET) { "${it.groupValues[1]}<redacted>${it.groupValues[2]}" }
                .replace(KV_SECRET) { "${it.groupValues[1]}<redacted>" }
                .replace(BEARER_SECRET) { "${it.groupValues[1]}<redacted>" }
        }
        if (result.contains("http://", ignoreCase = true) || result.contains("https://", ignoreCase = true)) {
            result = redactUrls(result)
        }
        if ('@' in result) {
            result = result.replace(EMAIL, "<email>")
        }
        if (hasDigitRun(result)) {
            result = redactPhones(result)
        }
        if (':' in result || '/' in result || '\\' in result) {
            result = redactLocalPaths(result)
        }
        return result
    }

    private fun containsSecretKeyword(s: String): Boolean =
        s.contains("token", ignoreCase = true) ||
            s.contains("password", ignoreCase = true) ||
            s.contains("bearer", ignoreCase = true)

    private fun hasDigitRun(s: String): Boolean {
        var digits = 0
        for (c in s) {
            if (c in '0'..'9') {
                if (++digits >= 3) return true
            } else if (c != '-' && c != ' ') {
                digits = 0
            }
        }
        return false
    }

    private fun redactUrls(input: String): String {
        val matches = HTTP_SCHEME.findAll(input).toList()
        if (matches.isEmpty()) return input
        val out = StringBuilder(input.length)
        var cursor = 0
        for (match in matches) {
            val start = match.range.first
            if (start < cursor) continue
            out.append(input, cursor, start)

            var rawEnd = match.range.last + 1
            while (rawEnd < input.length && !isUrlStopChar(input[rawEnd])) {
                rawEnd++
            }
            // 若紧接已脱敏占位符 `/<redacted>`，将该占位符整体纳入审视以保持幂等
            val alreadyRedactedSuffix = input.startsWith("/<redacted>", rawEnd)
            if (alreadyRedactedSuffix) {
                rawEnd += "/<redacted>".length
            } else {
                rawEnd = trimUnbalancedUrlTail(input, start, rawEnd)
            }

            val token = input.substring(start, rawEnd)
            val schemeSep = token.indexOf("://")
            val scheme = token.substring(0, schemeSep)
            val afterScheme = schemeSep + 3
            var authorityEnd = afterScheme
            while (authorityEnd < token.length && token[authorityEnd] != '/' && token[authorityEnd] != '?' && token[authorityEnd] != '#') {
                authorityEnd++
            }
            val authority = token.substring(afterScheme, authorityEnd)
            val rest = token.substring(authorityEnd)
            val hasUserInfo = '@' in authority
            val hostPort = if (hasUserInfo) authority.substringAfterLast('@') else authority
            val host = extractHost(hostPort)
            val isLoopback = host == "127.0.0.1" || host.equals("localhost", ignoreCase = true) || host == "[::1]"

            when {
                hostPort.isEmpty() -> out.append(token)
                rest == "/<redacted>" && !hasUserInfo -> out.append(token)
                isLoopback && !hasUserInfo -> out.append(token)
                isLoopback && (rest.isEmpty() || rest == "/") -> out.append(scheme).append("://").append(hostPort).append(rest)
                !hasUserInfo && (rest.isEmpty() || rest == "/") -> out.append(token)
                else -> out.append(scheme).append("://").append(hostPort).append("/<redacted>")
            }
            cursor = rawEnd
        }
        out.append(input, cursor, input.length)
        return out.toString()
    }

    private fun isUrlStopChar(c: Char): Boolean =
        c.isWhitespace() || c == '"' || c == '\'' || c == '<' || c == '>' || c == '|'

    private fun trimUnbalancedUrlTail(input: String, start: Int, initialEnd: Int): Int {
        var end = initialEnd
        while (end > start) {
            val tail = input[end - 1]
            if (tail == ',' || tail == ';' || tail == '.') {
                end--
                continue
            }
            if (tail == ')') {
                var balance = 0
                for (i in start until end) {
                    if (input[i] == '(') balance++ else if (input[i] == ')') balance--
                }
                if (balance < 0) {
                    end--
                    continue
                }
            }
            if (tail == ']') {
                var balance = 0
                for (i in start until end) {
                    if (input[i] == '[') balance++ else if (input[i] == ']') balance--
                }
                if (balance < 0) {
                    end--
                    continue
                }
            }
            break
        }
        return end
    }

    private fun extractHost(hostPort: String): String =
        if (hostPort.startsWith('[')) {
            val close = hostPort.indexOf(']')
            if (close >= 0) hostPort.substring(0, close + 1) else hostPort
        } else {
            hostPort.substringBefore(':')
        }

    private fun redactPhones(input: String): String {
        val matches = PHONE_CANDIDATE.findAll(input).toList()
        if (matches.isEmpty()) return input
        val out = StringBuilder(input.length)
        var cursor = 0
        for (match in matches) {
            val start = match.range.first
            val endExclusive = match.range.last + 1
            if (start < cursor) continue
            if (shouldRedactPhone(input, start, endExclusive, match.value)) {
                out.append(input, cursor, start)
                out.append("<phone>")
                cursor = endExclusive
            }
        }
        if (cursor == 0) return input
        out.append(input, cursor, input.length)
        return out.toString()
    }

    private fun shouldRedactPhone(input: String, start: Int, endExclusive: Int, raw: String): Boolean {
        val prev = if (start > 0) input[start - 1] else '\u0000'
        val next = if (endExclusive < input.length) input[endExclusive] else '\u0000'
        // 前后紧邻字母、数字、路径分隔符、十六进制或范围加号（如 range=0+14973770608）时绝不视作手机号
        if (prev.isAsciiAlnum() || prev == '_' || prev == '.' || prev == '/' || prev == '\\' || prev == '@' || prev == '#' || prev == '-' || prev == '+') {
            return false
        }
        if (next.isAsciiAlnum() || next == '_' || next == '.' || next == '/' || next == '\\' || next == '-' || next == '+') {
            return false
        }

        val digitsOnly = raw.filter { it in '0'..'9' }
        val hasExplicitPrefix = raw.startsWith('+') || raw.startsWith("0086") || raw.startsWith("86-") || raw.startsWith("86 ")
        if (raw.startsWith('+')) {
            if (digitsOnly.length !in 10..15) return false
            if (digitsOnly.startsWith("86") && (digitsOnly.length != 13 || digitsOnly[2] != '1' || digitsOnly[3] !in '3'..'9')) {
                return false
            }
        }

        // 后接字节、时长或速率单位（如 14973770608 B / bytes / KiB / ms）时排除
        val afterTrimmed = skipHorizontalSpaces(input, endExclusive)
        if (startsWithMeasurementUnit(input, afterTrimmed)) return false

        // 纯 11 位数字（无 +86 / 86- / 分隔横线）须额外核查前文，排除文件大小、偏移量、配额等数值字段
        val isBareElevenDigits = !hasExplicitPrefix && '-' !in raw && ' ' !in raw
        if (isBareElevenDigits && hasNumericContextPrefix(input, start)) {
            return false
        }
        return true
    }

    private fun skipHorizontalSpaces(s: String, from: Int): Int {
        var i = from
        while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
        return i
    }

    private fun startsWithMeasurementUnit(s: String, index: Int): Boolean {
        if (index >= s.length) return false
        for (unit in MEASUREMENT_UNITS) {
            if (s.regionMatches(index, unit, 0, unit.length, ignoreCase = false)) {
                val after = index + unit.length
                if (after >= s.length || !s[after].isAsciiLetter()) return true
            }
        }
        return false
    }

    private fun hasNumericContextPrefix(s: String, numberStart: Int): Boolean {
        var i = numberStart - 1
        while (i >= 0 && (s[i] == ' ' || s[i] == '\t' || s[i] == '=' || s[i] == ':' || s[i] == '：')) {
            i--
        }
        if (i < 0) return false
        val windowStart = (i - 20).coerceAtLeast(0)
        val prefix = s.substring(windowStart, i + 1).lowercase()
        return NUMERIC_PREFIX_KEYWORDS.any { prefix.endsWith(it) }
    }

    private fun redactLocalPaths(input: String): String {
        val out = StringBuilder(input.length)
        var cursor = 0
        while (cursor < input.length) {
            val match = LOCAL_PATH_START.find(input, cursor) ?: break
            val start = match.range.first
            val prefixEnd = match.range.last + 1
            val pathEnd = findLocalPathEnd(input, start, prefixEnd)
            out.append(input, cursor, start)
            val matchedPath = input.substring(start, pathEnd)
            val ext = extractPathExtension(matchedPath)
            out.append("<path>")
            if (ext != null) out.append('.').append(ext.lowercase())
            cursor = pathEnd
        }
        if (cursor == 0) return input
        out.append(input, cursor, input.length)
        return out.toString()
    }

    private fun findLocalPathEnd(input: String, start: Int, prefixEnd: Int): Int {
        val allowsBackslash = input[start] == '\\' || (start + 2 < input.length && input[start + 1] == ':' && input[start + 2] == '\\')
        var parenDepth = 0
        var bracketDepth = 0
        var hardEnd = start

        var i = start
        while (i < input.length) {
            val c = input[i]
            if (c == '\r' || c == '\n' || c == '\t' || c == '"' || c == '\'' || c == '<' || c == '>' || c == '|') break
            if (i >= prefixEnd && (c == ':' || c == ',' || c == ';') && i + 1 < input.length && input[i + 1] == ' ') break
            if (i >= prefixEnd && c == ' ' && i + 1 < input.length) {
                // 若空格后紧接另一条本地路径或 URL，在此断开，分别独立脱敏
                val nextMatch = LOCAL_PATH_START.find(input, i + 1)
                if (nextMatch != null && nextMatch.range.first == i + 1) break
                if (input.regionMatches(i + 1, "http://", 0, 7, ignoreCase = true) ||
                    input.regionMatches(i + 1, "https://", 0, 8, ignoreCase = true) ||
                    input.startsWith("-> ", i + 1)
                ) {
                    break
                }
                // 识别行尾 Java 异常附带的「 (原因)」子句（闭合括号后不再出现路径分隔符或文件扩展名）
                if (input[i + 1] == '(' && isTrailingReasonClause(input, i + 1, allowsBackslash)) {
                    break
                }
            }
            when (c) {
                '(' -> parenDepth++
                ')' -> {
                    if (parenDepth == 0) break
                    parenDepth--
                }
                '[' -> bracketDepth++
                ']' -> {
                    if (bracketDepth == 0) break
                    bracketDepth--
                }
            }
            i++
            hardEnd = i
        }

        // 在末段候选区间内定位文件名终点：若末段包含合法扩展名则并入扩展名前的文件名内部空格，否则在末段首个空格处截断，防止吞掉说明后缀
        var lastSep = -1
        for (idx in hardEnd - 1 downTo start) {
            val ch = input[idx]
            if (ch == '/' || (allowsBackslash && ch == '\\')) {
                lastSep = idx
                break
            }
        }
        val segmentStart = if (lastSep >= 0) maxOf(prefixEnd, lastSep + 1) else prefixEnd
        var effectiveEnd = findLastExtensionEnd(input, segmentStart, hardEnd) ?: run {
            var stop = hardEnd
            for (idx in segmentStart until hardEnd) {
                if (input[idx] == ' ') {
                    stop = idx
                    break
                }
            }
            stop
        }
        while (effectiveEnd > prefixEnd && (input[effectiveEnd - 1] == ' ' || input[effectiveEnd - 1] == ',' || input[effectiveEnd - 1] == ';')) {
            effectiveEnd--
        }
        return effectiveEnd
    }

    private fun isTrailingReasonClause(input: String, openParenIndex: Int, allowsBackslash: Boolean): Boolean {
        var depth = 0
        var i = openParenIndex
        while (i < input.length) {
            val c = input[i]
            if (c == '\r' || c == '\n') return false
            if (c == '(') depth++
            if (c == ')') {
                depth--
                if (depth == 0) {
                    val afterParen = i + 1
                    var lineEnd = afterParen
                    while (lineEnd < input.length && input[lineEnd] != '\r' && input[lineEnd] != '\n' && input[lineEnd] != '"' && input[lineEnd] != '\'') {
                        lineEnd++
                    }
                    return !hasPathContinuation(input, afterParen, lineEnd, allowsBackslash)
                }
            }
            i++
        }
        return false
    }

    private fun hasPathContinuation(input: String, from: Int, to: Int, allowsBackslash: Boolean): Boolean {
        if (from >= to) return false
        for (idx in from until to) {
            val ch = input[idx]
            if (ch == '/' || (allowsBackslash && ch == '\\')) return true
        }
        return findLastExtensionEnd(input, from, to) != null
    }

    private fun findLastExtensionEnd(input: String, from: Int, to: Int): Int? {
        var lastEnd: Int? = null
        var i = from
        while (i < to) {
            if (input[i] == '.' && i > from) {
                val prev = input[i - 1]
                if (!prev.isWhitespace() && prev != '/' && prev != '\\') {
                    val extStart = i + 1
                    var extEnd = extStart
                    var hasLetter = false
                    while (extEnd < to && input[extEnd].isAsciiAlnum()) {
                        if (input[extEnd].isAsciiLetter()) hasLetter = true
                        extEnd++
                    }
                    val len = extEnd - extStart
                    if (len in 1..8 && hasLetter && (extEnd == to || isExtensionBoundary(input[extEnd]))) {
                        lastEnd = extEnd
                    }
                    i = extEnd
                    continue
                }
            }
            i++
        }
        return lastEnd
    }

    private fun isExtensionBoundary(c: Char): Boolean =
        c == ' ' || c == ',' || c == ';' || c == ':' || c == ')' || c == ']' ||
            c == '，' || c == '；' || c == '：' || c == '。' || c == '）'

    private fun extractPathExtension(path: String): String? {
        val clean = path.substringBefore('?').substringBefore('#')
        val lastSegment = clean.substringAfterLast('/').substringAfterLast('\\')
        val ext = lastSegment.substringAfterLast('.', "")
        return ext.takeIf { it.length in 1..8 && it.all { ch -> ch.isAsciiAlnum() } }
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
    private fun Char.isAsciiAlnum(): Boolean = isAsciiLetter() || this in '0'..'9'

    private val MEASUREMENT_UNITS = arrayOf(
        "KiB", "MiB", "GiB", "TiB", "KB", "MB", "GB", "TB",
        "bytes", "byte", "B", "ms", "us", "ns", "fps", "bps", "s",
    )

    private val NUMERIC_PREFIX_KEYWORDS = arrayOf(
        "size", "bytes", "byte", "total", "used", "free", "available", "quota", "usage", "limit",
        "length", "len", "offset", "pos", "position", "range", "start", "end", "delivered",
        "downloaded", "uploaded", "peak", "max", "min", "heap", "memory", "duration", "elapsed",
        "cost", "seed", "hash", "crc", "inode", "pid", "tid", "uid", "gid", "code", "status",
        "port", "version", "build", "count", "大小", "字节", "容量", "配额", "已用", "剩余", "偏移", "长度", "耗时",
    )

    private val LOCAL_LOCATION = Regex("""piko-local-[a-z-]+:[A-Za-z0-9_:/%+=.\\-]*""")
    private val MAGNET_URI = Regex("""(?i)magnet:\?(?:<redacted>|[^\s"'<>])+""")
    private val MAGNET_SENSITIVE_PARAM = Regex("""(?i)([?&](?:dn|as|xs|ws)=)(?!<redacted>)[^&#\s"'<>]+""")

    private val JSON_SECRET = Regex(
        """(?i)("(?:access_token|refresh_token|accessToken|refreshToken|password)"\s*:\s*")(?!<redacted>(?:"|$))(?:\\.|[^"\\\r\n])*("?)""",
    )
    private val KV_SECRET = Regex(
        """(?i)((?<![A-Za-z0-9_])(?:access_token|refresh_token|accessToken|refreshToken|password)\s*[=:]\s*)(?!<redacted>)(?!")[^\s"',;})\]&<>]+""",
    )
    private val BEARER_SECRET = Regex(
        """(?i)((?<![A-Za-z0-9_])bearer\s+)(?!<redacted>)[A-Za-z0-9._~+/=-]+""",
    )

    private val HTTP_SCHEME = Regex("""(?i)(?<![A-Za-z0-9+.-])https?://""")
    private val EMAIL = Regex("""(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

    // 严禁使用 \d（参见 AndroidRegexGuardTest），统一使用 [0-9]
    private val PHONE_CANDIDATE = Regex(
        """\+[1-9][0-9]{0,2}(?:[\s-]?[0-9]{2,4}){2,4}|""" +
            """(?:00)?86[\s-]?1[3-9][0-9](?:[\s-]?[0-9]{4}){2}|""" +
            """1[3-9][0-9]-[0-9]{4}-[0-9]{4}|""" +
            """1[3-9][0-9]{9}""",
    )

    private val LOCAL_PATH_START = Regex(
        """(?<![A-Za-z0-9_.-])[A-Za-z]:[\\/]|""" +
            """(?<!\\)\\\\\?\\[A-Za-z]:\\|""" +
            """(?<!\\)\\\\(?:\?\\(?:UNC\\)?)?[A-Za-z0-9_.$%-]+\\[A-Za-z0-9_.$%-]+|""" +
            """(?<![A-Za-z0-9_:/\\.-])/(?:storage|sdcard|data|Users|home|mnt|media|run/user|root|Volumes|private|var|tmp)/|""" +
            """(?<![A-Za-z0-9+.-])content://|""" +
            """(?<![A-Za-z0-9+.-])file:/+""",
    )

    /** 等待此前排队的日志全部落盘。在未捕获异常退出前调用，确保崩溃现场完整写入磁盘。 */
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        val enqueued = withPikoLock(lock) {
            if (!installed) {
                false
            } else {
                queue.addLast(Request.Flush(done))
                true
            }
        }
        if (!enqueued) return
        signal.trySend(Unit)
        done.await()
    }

    /** 导出警告与错误侧文件、完整主日志及可选附属日志段落。在写入协程内串行读取，避免与文件滚动竞争。 */
    suspend fun export(): String {
        val result = CompletableDeferred<String>()
        val enqueued = withPikoLock(lock) {
            if (!installed) {
                false
            } else {
                queue.addLast(Request.Export(result))
                true
            }
        }
        if (!enqueued) return ""
        signal.trySend(Unit)
        return result.await()
    }

    /** 清空全部日志文件与挂起重复计数，并触发外部清理回调（如清空更新历史日志）。 */
    suspend fun clear() {
        val done = CompletableDeferred<Unit>()
        val enqueued = withPikoLock(lock) {
            if (!installed) {
                queue.clear()
                bufferedLines = 0
                droppedLines = 0
                false
            } else {
                queue.addLast(Request.Clear(done))
                true
            }
        }
        if (!enqueued) return
        signal.trySend(Unit)
        done.await()
    }
}

internal class LogLine(
    val epochMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val error: Throwable?,
) {
    /** 连续重复行判等：同时比对异常自身及其直接根因（cause）的类型与消息，避免吞掉不同根因。 */
    fun sameAs(other: LogLine): Boolean =
        level == other.level &&
            tag == other.tag &&
            message == other.message &&
            errorSignature(error) == errorSignature(other.error)

    private fun errorSignature(err: Throwable?) = err?.let {
        Triple(it::class, it.message, it.cause?.let { cause -> cause::class to cause.message })
    }
}

/**
 * 写入协程独占的状态持有者：维护主日志（`piko.log`）与警告错误侧文件（`piko-errors.log`）。
 *
 * 两条日志流各自维护独立的重复行折叠状态（[DedupStream]），确保穿插的 DEBUG 日志不会打断
 * `piko-errors.log` 中连续重复 WARN/ERROR 的合并。
 */
internal class LogWriter(directory: Path, private val zone: TimeZone) {
    private val all = RollingLogFiles(directory, "piko", maxFileBytes = 1L shl 20, keptFiles = 3)
    private val problems = RollingLogFiles(directory, "piko-errors", maxFileBytes = 128L shl 10, keptFiles = 1)

    private val allStream = DedupStream(all)
    private val problemStream = DedupStream(problems)

    private inner class DedupStream(private val target: RollingLogFiles) {
        private var last: LogLine? = null
        private var repeats = 0
        private var lastRepeatMillis = 0L

        fun write(line: LogLine) {
            val previous = last
            if (previous != null && previous.sameAs(line)) {
                repeats++
                lastRepeatMillis = line.epochMillis
                return
            }
            endRepeats()
            last = line
            target.append(format(line))
        }

        fun endRepeats() {
            val previous = last ?: return
            last = null
            if (repeats == 0) return
            val count = repeats
            repeats = 0
            target.append(
                format(
                    LogLine(
                        lastRepeatMillis,
                        previous.level,
                        previous.tag,
                        "Previous entry repeated $count more times",
                        null,
                    ),
                ),
            )
        }

        fun reset() {
            last = null
            repeats = 0
            lastRepeatMillis = 0L
        }
    }

    fun dropExpired(nowMillis: Long) {
        settle()
        all.dropBefore(timestamp(nowMillis - RETENTION_DAYS * DAY_MILLIS))
        problems.dropBefore(timestamp(nowMillis - PROBLEM_RETENTION_DAYS * DAY_MILLIS))
    }

    fun write(line: LogLine) {
        allStream.write(line)
        if (line.level >= LogLevel.WARN) {
            problemStream.write(line)
        }
    }

    /** 仅将当前缓冲区刷入磁盘，保留尚未打断的重复行计数，使跨批次到达的相同日志仍能合并。 */
    fun flush() {
        all.flush()
        problems.flush()
    }

    /** 结算挂起的重复行汇总并落盘，供崩溃退出与导出日志前调用。 */
    fun settle() {
        allStream.endRepeats()
        problemStream.endRepeats()
        flush()
    }

    fun export(extraExport: (() -> String)? = null): String {
        settle()
        return buildString {
            append("==== Warnings & Errors (retained ${PROBLEM_RETENTION_DAYS}d) ====\n")
            append(problems.readAll())
            append("\n==== Full Log (retained ${RETENTION_DAYS}d) ====\n")
            append(all.readAll())
            val extra = extraExport?.let { callback ->
                runCatching { callback() }.getOrNull()?.let(PikoLog::redact).orEmpty()
            }.orEmpty()
            if (extra.isNotBlank()) {
                if (!endsWith('\n')) append('\n')
                if (!extra.startsWith('\n')) append('\n')
                append(extra)
                if (!endsWith('\n')) append('\n')
            }
        }
    }

    fun clear() {
        allStream.reset()
        problemStream.reset()
        all.clear()
        problems.clear()
    }

    fun close() {
        settle()
        all.close()
        problems.close()
    }

    private fun format(line: LogLine): String = buildString {
        append(timestamp(line.epochMillis))
        append(' ').append(line.level.letter).append(' ').append(line.tag).append(": ").append(PikoLog.redact(line.message)).append('\n')
        if (line.error != null) append(PikoLog.redact(line.error.stackTraceToString().trimEnd())).append('\n')
    }

    /** 定宽字典序时间戳（`yyyy-MM-dd HH:mm:ss.SSS`），可直接按字符串比较执行过期裁剪。 */
    private fun timestamp(epochMillis: Long): String {
        val time = kotlin.time.Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)
        return "${time.date} ${time.hour.pad(2)}:${time.minute.pad(2)}:${time.second.pad(2)}.${(time.nanosecond / 1_000_000).pad(3)}"
    }

    private fun Int.pad(width: Int) = toString().padStart(width, '0')

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
        const val RETENTION_DAYS = 7L
        const val PROBLEM_RETENTION_DAYS = 30L
    }
}

/**
 * 诊断日志中的文件标识：仅输出 ID 与小写扩展名，隐去明文文件名与归档条目的内容哈希。
 */
fun logFile(id: String?, name: String): String {
    val ext = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 8 }
    val shown = id?.takeIf { it.isNotEmpty() }?.let {
        dev.piko.shared.data.LocalLocation.logIdentity(it)
            ?: VaultEntry.entryIdOf(it)?.let { entryId -> "vault $entryId" }
            ?: it
    } ?: "?"
    return "file $shown" + (ext?.let { " (.${it.lowercase()})" } ?: "")
}

/**
 * 诊断日志中的目录标识：空字符串代表根目录 `root`，`null` 代表未知 `?`。
 */
fun logFolder(id: String?): String {
    val shown = when {
        id == null -> "?"
        id.isEmpty() -> "root"
        else -> dev.piko.shared.data.LocalLocation.logIdentity(id) ?: id
    }
    return "folder $shown"
}

/** 按条目是否为文件夹选择 [logFolder] 或 [logFile]。 */
fun logEntry(id: String?, name: String, isFolder: Boolean): String =
    if (isFolder) logFolder(id) else logFile(id, name)

/** 失败时记录英文警告并原样返回（自动跳过协程取消异常）。 */
fun <T> Result<T>.logFailure(tag: String, message: String): Result<T> = onFailure {
    if (it !is CancellationException) PikoLog.w(tag, message, it)
}

/**
 * 面向用户的本地化失败提示文案，见 [dev.piko.shared.notice.PikoErrorMapper.failure]。
 */
fun failureText(action: String, error: Throwable): String = dev.piko.shared.notice.PikoErrorMapper.failure(action, error)

/**
 * 失败时以纯英文 [logMessage] 写入诊断日志，再以本地化的 [action] 生成用户提示交给 [show]（自动跳过协程取消异常）。
 */
fun <T> Result<T>.reportFailure(
    tag: String,
    action: String,
    logMessage: String,
    show: (String) -> Unit,
): Result<T> = onFailure {
    if (it is CancellationException) return@onFailure
    PikoLog.w(tag, logMessage, it)
    show(failureText(action, it))
}

fun <T> Result<T>.reportFailure(tag: String, action: String, show: (String) -> Unit): Result<T> =
    reportFailure(tag, action, "Operation failed", show)

/**
 * `<baseName>.log` 写满 [maxFileBytes] 后滚动为 `<baseName>.1.log`，最多保留 [keptFiles] 份历史文件。
 * 文件 IO 异常静默隔离，确保日志基础设施故障不影响宿主业务。
 */
private class RollingLogFiles(
    private val directory: Path,
    private val baseName: String,
    private val maxFileBytes: Long,
    private val keptFiles: Int,
) {
    private var sink: Sink? = null
    private var size = 0L

    fun flush() {
        runCatching { sink?.flush() }
    }

    fun close() {
        runCatching { sink?.flush() }
        runCatching { sink?.close() }
        sink = null
        size = 0L
    }

    fun append(text: String) {
        runCatching {
            val bytes = text.encodeToByteArray()
            if (sink == null) {
                size = SystemFileSystem.metadataOrNull(fileAt(0))?.size ?: 0L
            }
            if (size > 0 && size + bytes.size > maxFileBytes) rotate()
            val out = sink ?: open()
            out.write(bytes)
            size += bytes.size
        }
    }

    fun readAll(): String = buildString {
        flush()
        for (index in keptFiles downTo 0) {
            val file = fileAt(index)
            runCatching {
                if (SystemFileSystem.exists(file)) append(SystemFileSystem.source(file).buffered().use { it.readString() })
            }
        }
    }

    fun clear() {
        close()
        for (index in 0..keptFiles) runCatching { SystemFileSystem.delete(fileAt(index), mustExist = false) }
    }

    /**
     * 裁剪时间早于 [cutoff] 的记录（无时间戳的异常堆栈续行随所属记录一并去留）。
     * 执行前先刷盘并关闭活动句柄，使其既可在冷启动时调用，也可在桌面长驻运行期间按周期安全触发。
     */
    fun dropBefore(cutoff: String) {
        close()
        for (index in 0..keptFiles) {
            val file = fileAt(index)
            runCatching {
                if (!SystemFileSystem.exists(file)) return@runCatching
                val text = SystemFileSystem.source(file).buffered().use { it.readString() }
                when (val keepFrom = firstEntryNotBefore(text, cutoff)) {
                    null -> SystemFileSystem.delete(file, mustExist = false)
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
        close()
        runCatching { SystemFileSystem.delete(fileAt(keptFiles), mustExist = false) }
        for (index in keptFiles - 1 downTo 0) {
            val src = fileAt(index)
            val dst = fileAt(index + 1)
            runCatching {
                if (SystemFileSystem.exists(src)) {
                    SystemFileSystem.delete(dst, mustExist = false)
                    SystemFileSystem.atomicMove(src, dst)
                }
            }
        }
    }

    private fun fileAt(index: Int) = Path(directory, if (index == 0) "$baseName.log" else "$baseName.$index.log")
}

private val ENTRY_START = Regex("""^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}""", RegexOption.MULTILINE)

private fun firstEntryNotBefore(text: String, cutoff: String): Int? =
    ENTRY_START.findAll(text).firstOrNull { it.value >= cutoff }?.range?.first
