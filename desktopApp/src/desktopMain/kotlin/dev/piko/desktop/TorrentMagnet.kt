package dev.piko.desktop

import java.io.File
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
    fun fromFile(file: File): String? = runCatching { fromBytes(file.readBytes()) }.getOrNull()

    /** 不是种子或内容残缺时返回 null。 */
    fun fromBytes(bytes: ByteArray): String? = runCatching { parse(bytes) }.getOrNull()

    private fun parse(bytes: ByteArray): String? {
        val scanner = Scanner(bytes)
        if (bytes.firstOrNull() != 'd'.code.toByte()) return null
        scanner.pos = 1
        var infoRange: IntRange? = null
        var name: String? = null
        while (bytes[scanner.pos] != 'e'.code.toByte()) {
            val key = String(scanner.readString(), Charsets.UTF_8)
            val start = scanner.pos
            if (key == "info") {
                name = scanner.findNameInInfo()
                scanner.pos = start
            }
            scanner.skipValue()
            if (key == "info") infoRange = start until scanner.pos
        }
        val range = infoRange ?: return null
        val hash = MessageDigest.getInstance("SHA-1").digest(bytes.copyOfRange(range.first, range.last + 1))
        val hex = hash.joinToString("") { "%02x".format(it) }
        val displayName = name?.let { "&dn=" + URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }.orEmpty()
        return "magnet:?xt=urn:btih:$hex$displayName"
    }

    private class Scanner(val bytes: ByteArray) {
        var pos = 0

        fun readString(): ByteArray {
            val colon = (pos until bytes.size).first { bytes[it] == ':'.code.toByte() }
            val length = String(bytes, pos, colon - pos, Charsets.US_ASCII).toInt()
            val start = colon + 1
            pos = start + length
            return bytes.copyOfRange(start, pos)
        }

        fun skipValue() {
            when (bytes[pos].toInt().toChar()) {
                'i' -> pos = (pos until bytes.size).first { bytes[it] == 'e'.code.toByte() } + 1
                'l', 'd' -> {
                    pos++
                    while (bytes[pos] != 'e'.code.toByte()) skipValue()
                    pos++
                }
                else -> readString()
            }
        }

        /** 当前位置是 info 字典，取它的 name 作显示名，读完位置不复原，由调用方复原。 */
        fun findNameInInfo(): String? {
            if (bytes[pos] != 'd'.code.toByte()) return null
            pos++
            while (bytes[pos] != 'e'.code.toByte()) {
                val key = String(readString(), Charsets.UTF_8)
                if (key == "name" && bytes[pos].toInt().toChar().isDigit()) return String(readString(), Charsets.UTF_8)
                skipValue()
            }
            return null
        }
    }
}
