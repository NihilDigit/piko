package dev.piko.shared.media

/**
 * [ClipRecord] 落盘用的文本形式，两端共用：JVM 的 `FileClipCache` 用它落记录文件，iOS 的
 * `IosClipCache` 也用它。写成文本而不是二进制，是为了让 iOS 侧不必自己拼字节序，
 * 也让记录能被单测直接读。
 *
 * 一行一个字段，字段内把 `\`、换行与回车转义成 `\\`、`\n`、`\r`；文件名里的这些字符不能
 * 让记录读错位。字段顺序与 [ClipRecord] 的构造参数一致，末尾追加字段才算兼容变更，
 * 增删字段要抬 [VERSION]。
 */
object ClipRecordCodec {
    /** 记录格式版本。读到别的版本一律当作没有这条记录，下次播放时重写。 */
    const val VERSION: Int = 2

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

    // 版本行 + 5 个字符串 + 5 个数字
    private const val STRING_FIELDS = 5
    private const val FIELD_COUNT = 1 + STRING_FIELDS + 5
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
