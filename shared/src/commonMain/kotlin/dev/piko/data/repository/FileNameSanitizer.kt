package dev.piko.data.repository

object FileNameSanitizer {
    private val ILLEGAL_CHARS = Regex("[\\\\/:*?\"<>|\\r\\n\\t\\u0000-\\u001F\\u007F]")
    private val MULTI_SPACE = Regex("\\s+")
    val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "ts", "m4v", "rmvb", "asf", "3gp", "iso", "m2ts")

    fun isVideoFileName(name: String): Boolean = substringExtension(name) in VIDEO_EXTENSIONS

    fun sanitize(rawName: String, fallbackExtension: String = "", forceExtension: String? = null): String {
        val (rawBase, rawExt) = if (rawName.contains('.')) {
            val idx = rawName.lastIndexOf('.')
            val ext = rawName.substring(idx + 1).lowercase()
            if (ext.isNotBlank() && ext.length in 1..10 && !ILLEGAL_CHARS.containsMatchIn(ext)) {
                rawName.substring(0, idx) to "." + ext
            } else rawName to ""
        } else rawName to ""
        val fallback = fallbackExtension.trim().let { if (it.isNotEmpty() && !it.startsWith('.')) "." + it else it }
        val finalExt = when {
            !forceExtension.isNullOrBlank() -> forceExtension.trim().lowercase().let { if (it.startsWith('.')) it else "." + it }
            rawExt.isNotEmpty() -> rawExt
            fallback.isNotEmpty() -> fallback
            else -> ""
        }
        var cleanBase = clean(rawBase)
        if (cleanBase.isBlank()) cleanBase = if (isVideoFileName("file$finalExt")) "unnamed_video" else "unnamed_file"
        val extBytes = finalExt.encodeToByteArray().size
        val maxBaseChars = (MAX_FILE_CHARS - finalExt.length).coerceAtLeast(16)
        val maxBaseBytes = (MAX_FILE_UTF8_BYTES - extBytes).coerceAtLeast(32)
        cleanBase = truncateMiddle(cleanBase, maxBaseChars, maxBaseBytes).trimEnd('.', ' ', '-')
        if (cleanBase.isBlank()) cleanBase = if (isVideoFileName("file$finalExt")) "unnamed_video" else "unnamed_file"
        return avoidDeviceName(cleanBase + finalExt)
    }

    /**
     * 文件夹名。不按扩展名拆：sanitize 会把「Show.S01」的「.S01」当扩展名改成小写，
     * 同一部剧的几季文件夹落到本机就与网盘里的名字对不上了。
     */
    fun sanitizeFolderName(rawName: String): String =
        avoidDeviceName(
            truncateMiddle(clean(rawName), MAX_FOLDER_CHARS, MAX_FOLDER_UTF8_BYTES)
                .trimEnd('.', ' ', '-')
                .ifBlank { "unnamed_folder" },
        )

    /**
     * 同时按字符数与 UTF-8 字节数截断。Linux/Android (ext4/f2fs) 与 macOS/iOS (APFS) 的单级文件名上限是
     * 255 UTF-8 字节（约 85 个汉字），Windows 还受路径总长制约，且下载导出/转封装/重名去重还会追加
     * `.part`、`.piko-export`、` [1080P]`、` (2)` 等后缀。超长时保留首尾、省略中段，防止长标题末尾的集数被切掉。
     */
    internal fun truncateMiddle(text: String, maxChars: Int, maxUtf8Bytes: Int): String {
        if (text.length <= maxChars && utf8Length(text) <= maxUtf8Bytes) return text
        val ellipsis = "…"
        val ellipsisBytes = 3
        val budgetChars = (maxChars - ellipsis.length).coerceAtLeast(2)
        val budgetBytes = (maxUtf8Bytes - ellipsisBytes).coerceAtLeast(2)
        val headChars = (budgetChars * 2 / 3).coerceAtLeast(1)
        val tailChars = (budgetChars - headChars).coerceAtLeast(1)
        val headBytes = (budgetBytes * 2 / 3).coerceAtLeast(1)
        val tailBytes = (budgetBytes - headBytes).coerceAtLeast(1)
        val head = takePrefix(text, headChars, headBytes).trimEnd('.', ' ', '-')
        val tail = takeSuffix(text, tailChars, tailBytes).trimStart('.', ' ', '-')
        return if (tail.isEmpty() || head.length + tail.length >= text.length) {
            takePrefix(text, maxChars, maxUtf8Bytes)
        } else {
            "$head$ellipsis$tail"
        }
    }

    private fun takePrefix(text: String, maxChars: Int, maxBytes: Int): String {
        var chars = 0
        var bytes = 0
        while (chars < text.length) {
            val step = if (text[chars].isHighSurrogate() && chars + 1 < text.length && text[chars + 1].isLowSurrogate()) 2 else 1
            val cpBytes = if (step == 2) 4 else charUtf8Bytes(text[chars])
            if (chars + step > maxChars || bytes + cpBytes > maxBytes) break
            chars += step
            bytes += cpBytes
        }
        return text.substring(0, chars)
    }

    private fun takeSuffix(text: String, maxChars: Int, maxBytes: Int): String {
        var index = text.length
        var chars = 0
        var bytes = 0
        while (index > 0) {
            val step = if (index >= 2 && text[index - 1].isLowSurrogate() && text[index - 2].isHighSurrogate()) 2 else 1
            val cpBytes = if (step == 2) 4 else charUtf8Bytes(text[index - 1])
            if (chars + step > maxChars || bytes + cpBytes > maxBytes) break
            index -= step
            chars += step
            bytes += cpBytes
        }
        return text.substring(index)
    }

    private fun utf8Length(text: String): Int {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            if (text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                bytes += 4
                i += 2
            } else {
                bytes += charUtf8Bytes(text[i])
                i++
            }
        }
        return bytes
    }

    private fun charUtf8Bytes(ch: Char): Int = when {
        ch.code <= 0x7F -> 1
        ch.code <= 0x7FF -> 2
        else -> 3
    }

    private const val MAX_FILE_CHARS = 120
    private const val MAX_FILE_UTF8_BYTES = 180
    private const val MAX_FOLDER_CHARS = 80
    private const val MAX_FOLDER_UTF8_BYTES = 120

    /**
     * Windows 的保留设备名，不分大小写，带扩展名也算（NUL.tar.gz 等同 NUL），前面加下划线。
     * 判断第一个点之前、去掉末尾空格的部分：旧版 Windows 解析路径时会去掉它们，「CON .txt」也指向 CON。
     * 必须在去掉末尾的点与空格之后判断，「CON. 」清理后才露出 CON。
     *
     * 名单取 Microsoft Learn「Naming Files, Paths, and Namespaces」（含上标数字 ¹²³）。Windows 11 起带扩展名或
     * 位于子目录的名字已不再当作设备，但 Windows 10 与 SMB 服务端仍然保留，所以照旧处理。文档未列的
     * COM0、LPT0、CONIN$、CONOUT$ 也一并避开：多一个下划线无害，撞上了却是下载失败。
     *
     * 各平台都处理：Android 与 macOS 本地写得下，但文件迟早会拷到 Windows 或经 SMB 共享。
     */
    private fun avoidDeviceName(name: String): String {
        val stem = name.substringBefore('.').trimEnd(' ')
        return if (WINDOWS_DEVICE_NAME.matches(stem)) "_$name" else name
    }

    private val WINDOWS_DEVICE_NAME =
        Regex("CON|PRN|AUX|NUL|CONIN\\$|CONOUT\\$|(COM|LPT)[0-9¹²³]", RegexOption.IGNORE_CASE)

    private fun clean(raw: String): String = raw
        .replace(':', ' ').replace('/', '-').replace('\\', '-').replace('|', '-')
        .replace('?', ' ').replace('*', ' ').replace('"', '\'')
        .replace('<', '[').replace('>', ']').replace(ILLEGAL_CHARS, " ")
        .replace('\u00A0', ' ').replace('\u200B', ' ').replace(MULTI_SPACE, " ")
        .trim().trimEnd('.', ' ', '-').trimStart('.', ' ')

    fun findDominantVideoIndex(files: List<Pair<String, Long>>): Int? {
        val videos = files.mapIndexedNotNull { index, (name, size) -> if (isVideoFileName(name)) Triple(index, name, size) else null }
        if (videos.isEmpty()) return null
        if (videos.size == 1) return videos.first().first
        val sorted = videos.sortedByDescending { it.third }
        val largest = sorted[0]
        val second = sorted[1]
        return if (largest.third > 0L && (second.third == 0L || largest.third >= second.third * 10L)) largest.first else null
    }

    private fun substringExtension(name: String): String = name.substringAfterLast('.', "").lowercase()
}
