package dev.piko.shared.media

/**
 * [ClipRecord] 落盘用的文本编解码器：iOS 的 `IosClipCache` 用它持久化记录文件，单测也直接校验它
 * （JVM 侧 `FileClipCache` 使用 `DataOutputStream` 二进制格式）。写成文本是为了让 iOS 侧不必自己拼字节序，
 * 也让记录能被直接审阅。
 *
 * 一行一个字段，字符串字段内把 `\`、换行与回车转义成 `\\`、`\n`、`\r`，避免文件名中的特殊字符把行读错位。
 * 行序依次为：首行版本头（`v3`）、5 个字符串字段（`fileId`、`gcid`、`name`、`parentId`、`mediaId`）、
 * 8 个整数字段（`originalBytes`、`streamBytes`、`sliceOffset`、`sliceLength`、`sliceStartMs`、
 * `sliceDurationMs`、`keyframeOffset`、`videoPid`）。增删或重排字段须同步抬升 [VERSION]。
 */
object ClipRecordCodec {
    /** 记录格式版本。读到别的版本一律当作没有这条记录，下次播放时重写。 */
    const val VERSION: Int = 3

    fun encode(record: ClipRecord): String = buildString {
        // 首行是版本号，与字段行分开，将来加字段时旧代码能直接认出「这不是我能读的」
        append("v").append(VERSION)
        for (field in listOf(
            record.fileId,
            record.gcid,
            record.name,
            record.parentId,
            record.mediaId,
        )) {
            append('\n').append(escape(field))
        }
        for (number in listOf(
            record.originalBytes,
            record.streamBytes,
            record.sliceOffset,
            record.sliceLength,
            record.sliceStartMs,
            record.sliceDurationMs,
            record.keyframeOffset,
            record.videoPid.toLong(),
        )) {
            append('\n').append(number)
        }
    }

    fun decode(text: String): ClipRecord? {
        val lines = text.split('\n')
        if (lines.size != FIELD_COUNT) return null
        if (lines[0] != "v$VERSION") return null
        val fields = lines.drop(1)
        val strings = fields.take(STRING_FIELDS).map { unescape(it) ?: return null }
        val numbers = fields.drop(STRING_FIELDS).map { it.toLongOrNull() ?: return null }
        val videoPid = numbers[7]
        if (videoPid < Int.MIN_VALUE || videoPid > Int.MAX_VALUE) return null
        return ClipRecord(
            fileId = strings[0],
            gcid = strings[1],
            name = strings[2],
            parentId = strings[3],
            originalBytes = numbers[0],
            mediaId = strings[4],
            streamBytes = numbers[1],
            sliceOffset = numbers[2],
            sliceLength = numbers[3],
            sliceStartMs = numbers[4],
            sliceDurationMs = numbers[5],
            keyframeOffset = numbers[6],
            videoPid = videoPid.toInt(),
        )
    }

    private fun escape(value: String): String = buildString(value.length) {
        for (char in value) {
            when (char) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(char)
            }
        }
    }

    /** 转义不认识时返回 null：宁可当作没有这条记录，也不要读出半条错的去建 handle。 */
    private fun unescape(value: String): String? = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char != '\\') {
                append(char)
                index++
                continue
            }
            if (index + 1 >= value.length) return null
            when (value[index + 1]) {
                '\\' -> append('\\')
                'n' -> append('\n')
                'r' -> append('\r')
                else -> return null
            }
            index += 2
        }
    }

    // 版本行 + 5 个字符串 + 8 个整数
    private const val STRING_FIELDS = 5
    private const val NUMERIC_FIELDS = 8
    private const val FIELD_COUNT = 1 + STRING_FIELDS + NUMERIC_FIELDS
}

/**
 * 内容哈希、媒体 ID 与分段键只含字母数字与 `-`、`_`，别的字符一律换成 `_`，
 * 免得落成非法文件名。两端共用同一份规则，缓存目录才不会因为平台不同而互相认不出。
 */
fun clipCacheSafeName(name: String): String = buildString(name.length) {
    for (char in name) {
        append(if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '-' || char == '_') char else '_')
    }
}
