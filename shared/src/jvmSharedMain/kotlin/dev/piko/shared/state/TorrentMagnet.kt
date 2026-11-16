package dev.piko.shared.state

import dev.piko.shared.log.PikoLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 把 .torrent 文件换成磁力链接。离线任务只吃磁力 URL，SDK 也没有上传种子的接口，但 infohash
 * 就是 info 字典原始字节的 SHA-1，本地算出来即可，PikPak 按 hash 解析文件列表。
 *
 * 必须对文件里原样的字节求哈希：解码再编码回去，键序或整数写法稍有不同 hash 就对不上，
 * 所以这里只扫描出 info 值的字节区间，不构建对象。
 */
object TorrentMagnet {
    private const val TAG = "TorrentMagnet"
    const val MAX_TORRENT_BYTES: Int = 16 * 1024 * 1024
    private const val MAX_BENCODE_DEPTH: Int = 64
    private const val MAX_LENGTH_DIGITS: Int = 9

    fun fromFile(file: File): String? {
        if (!file.isFile) return null
        val fileLength = file.length()
        if (fileLength <= 0L || fileLength > MAX_TORRENT_BYTES) {
            PikoLog.w(TAG, "Rejected torrent file outside size limit ($fileLength bytes)")
            return null
        }
        val bytes = runCatching {
            file.inputStream().use { readBoundedBytes(it, MAX_TORRENT_BYTES) }
        }.onFailure {
            PikoLog.w(TAG, "Failed to read torrent file (${it::class.simpleName})", it)
        }.getOrNull()
        if (bytes == null) {
            PikoLog.w(TAG, "Rejected torrent file exceeding $MAX_TORRENT_BYTES bytes while reading")
            return null
        }
        return fromBytes(bytes)
    }

    /** 从流中有界读取最多 [maxBytes] 字节；若输入超出上限则立即中止并返回 null。 */
    fun readBoundedBytes(input: InputStream, maxBytes: Int = MAX_TORRENT_BYTES): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            if (read > maxBytes - total) return null
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    /**
     * 解析 bencode 种子字节并返回 BitTorrent v1 `urn:btih` 磁力链接；非种子、内容截断或不含 v1 `info` 块时返回 null：
     * - [Scanner] 校验边界、长度整数与最大嵌套深度 [MAX_BENCODE_DEPTH]，畸形数据抛出的异常由外层 `runCatching` 捕获并转为 null；
     * - 仅对 `info` 原始字节求 SHA-1（v1 协议），纯 v2 种子无传统 `info` 块时返回 null（v1+v2 混合种子按其 v1 `info` 计算）。
     */
    fun fromBytes(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        if (bytes.size > MAX_TORRENT_BYTES) {
            PikoLog.d(TAG, "Rejected oversized torrent payload (${bytes.size} bytes)")
            return null
        }
        return runCatching { parse(bytes) }
            .onFailure { PikoLog.d(TAG, "Failed to parse torrent (${bytes.size} bytes, ${it::class.simpleName}): ${it.message}") }
            .getOrNull()
            .also { if (it == null) PikoLog.d(TAG, "Torrent did not yield a valid v1 infohash (${bytes.size} bytes)") }
    }

    private fun parse(bytes: ByteArray): String? {
        val scanner = Scanner(bytes)
        if (bytes.firstOrNull() != 'd'.code.toByte()) return null
        scanner.pos = 1
        var infoRange: IntRange? = null
        var name: String? = null
        while (scanner.pos < bytes.size && bytes[scanner.pos] != 'e'.code.toByte()) {
            val key = String(scanner.readString(), Charsets.UTF_8)
            val start = scanner.pos
            if (key == "info") {
                name = scanner.findNameInInfo()
                scanner.pos = start
            }
            scanner.skipValue(depth = 1)
            if (key == "info") infoRange = start until scanner.pos
        }
        require(scanner.pos < bytes.size && bytes[scanner.pos] == 'e'.code.toByte()) {
            "Unterminated top-level torrent dictionary"
        }
        val range = infoRange ?: return null
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(bytes, range.first, range.last - range.first + 1)
        val hash = sha1.digest()
        val hex = hash.joinToString("") { "%02x".format(it) }
        // 按字符集名的重载：取 Charset 的那个 Android 到 API 33 才有，minSdk 是 26
        val displayName = name?.let { "&dn=" + URLEncoder.encode(it, "UTF-8").replace("+", "%20") }.orEmpty()
        return "magnet:?xt=urn:btih:$hex$displayName"
    }

    private class Scanner(val bytes: ByteArray) {
        var pos = 0

        private fun parseLength(): Pair<Int, Int> {
            var colon = -1
            val limit = minOf(bytes.size, pos + MAX_LENGTH_DIGITS + 1)
            for (i in pos until limit) {
                if (bytes[i] == ':'.code.toByte()) {
                    colon = i
                    break
                }
            }
            require(colon > pos) { "Missing or empty bencode string length" }
            if (colon - pos > 1 && bytes[pos] == '0'.code.toByte()) {
                throw IllegalArgumentException("Leading zero in bencode string length")
            }
            var length = 0
            for (i in pos until colon) {
                val digit = bytes[i].toInt() - '0'.code
                require(digit in 0..9) { "Invalid bencode string length digit" }
                length = length * 10 + digit
            }
            val start = colon + 1
            require(length >= 0 && length <= bytes.size - start) { "String length exceeds remaining torrent bytes" }
            return start to length
        }

        fun readString(): ByteArray {
            val (start, length) = parseLength()
            pos = start + length
            return bytes.copyOfRange(start, pos)
        }

        fun skipString() {
            val (start, length) = parseLength()
            pos = start + length
        }

        fun skipValue(depth: Int = 0) {
            require(depth < MAX_BENCODE_DEPTH) { "Bencode nesting depth exceeds $MAX_BENCODE_DEPTH" }
            require(pos < bytes.size) { "Unexpected end of torrent while skipping value" }
            when (bytes[pos].toInt().toChar()) {
                'i' -> skipInteger()
                'l', 'd' -> {
                    pos++
                    while (pos < bytes.size && bytes[pos] != 'e'.code.toByte()) {
                        skipValue(depth + 1)
                    }
                    require(pos < bytes.size && bytes[pos] == 'e'.code.toByte()) { "Unterminated bencode container" }
                    pos++
                }
                else -> skipString()
            }
        }

        private fun skipInteger() {
            pos++
            val start = pos
            if (pos < bytes.size && bytes[pos] == '-'.code.toByte()) pos++
            val digitStart = pos
            while (pos < bytes.size && bytes[pos] != 'e'.code.toByte()) {
                val ch = bytes[pos].toInt().toChar()
                require(ch in '0'..'9') { "Invalid character in bencode integer" }
                pos++
            }
            require(pos > digitStart && pos < bytes.size && bytes[pos] == 'e'.code.toByte()) {
                "Malformed or unterminated bencode integer"
            }
            if (bytes[start] == '-'.code.toByte()) {
                require(bytes[digitStart] != '0'.code.toByte()) { "Negative zero in bencode integer" }
            } else if (pos - digitStart > 1) {
                require(bytes[digitStart] != '0'.code.toByte()) { "Leading zero in bencode integer" }
            }
            pos++
        }

        /** 当前位置是 info 字典，取它的 name 作显示名，读完位置不复原，由调用方复原。 */
        fun findNameInInfo(): String? {
            if (pos >= bytes.size || bytes[pos] != 'd'.code.toByte()) return null
            pos++
            while (pos < bytes.size && bytes[pos] != 'e'.code.toByte()) {
                val key = String(readString(), Charsets.UTF_8)
                require(pos < bytes.size) { "Unexpected end of info dictionary" }
                if (key == "name" && bytes[pos].toInt().toChar() in '0'..'9') {
                    return String(readString(), Charsets.UTF_8)
                }
                skipValue(depth = 2)
            }
            return null
        }
    }
}
