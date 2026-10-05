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
        val maxBaseLength = 200 - finalExt.length
        if (cleanBase.length > maxBaseLength) cleanBase = cleanBase.take(maxBaseLength).trimEnd('.', ' ', '-')
        return avoidDeviceName(cleanBase + finalExt)
    }

    /**
     * \u6587\u4EF6\u5939\u540D\u3002\u4E0D\u6309\u6269\u5C55\u540D\u62C6\uFF1Asanitize \u4F1A\u628A\u300CShow.S01\u300D\u7684\u300C.S01\u300D\u5F53\u6269\u5C55\u540D\u6539\u6210\u5C0F\u5199\uFF0C
     * \u540C\u4E00\u90E8\u5267\u7684\u51E0\u5B63\u6587\u4EF6\u5939\u843D\u5230\u672C\u673A\u5C31\u4E0E\u7F51\u76D8\u91CC\u7684\u540D\u5B57\u5BF9\u4E0D\u4E0A\u4E86\u3002
     */
    fun sanitizeFolderName(rawName: String): String =
        avoidDeviceName(clean(rawName).take(200).trimEnd('.', ' ', '-').ifBlank { "unnamed_folder" })

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
