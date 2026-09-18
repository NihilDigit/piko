package dev.piko.data.repository

/**
 * PikPak 文件名清洗器
 * 去除云端接口、Windows 及 Linux 不兼容的字符，以防创建文件或重命名失败。
 */
object FileNameSanitizer {
    private val ILLEGAL_CHARS = Regex("""[\\/:*?"<>|\r\n\t\u0000-\u001F\u007F]""")
    private val MULTI_SPACE = Regex("""\s+""")

    val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "ts", "m4v", "rmvb", "asf", "3gp", "iso", "m2ts"
    )

    fun isVideoFileName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTENSIONS
    }

    /**
     * 清洗文件名
     * @param rawName 待清洗名称（如 torrent 外层名称或云端文件名）
     * @param fallbackExtension 缺失后缀时的替补后缀（如 "mp4"），若 rawName 未包含合法后缀则自动追加
     * @param forceExtension 强制使用的后缀（如流抽取 MP4 切片强制使用 "mp4"）
     */
    fun sanitize(
        rawName: String,
        fallbackExtension: String = "",
        forceExtension: String? = null,
    ): String {
        // 1. 分离可能的合法扩展名 (常规扩展名 1-10 字符)
        val (rawBase, rawExt) = if (rawName.contains('.')) {
            val idx = rawName.lastIndexOf('.')
            val ext = rawName.substring(idx + 1).lowercase()
            if (ext.isNotBlank() && ext.length in 1..10 && !ILLEGAL_CHARS.containsMatchIn(ext)) {
                Pair(rawName.substring(0, idx), ".$ext")
            } else {
                Pair(rawName, "")
            }
        } else {
            Pair(rawName, "")
        }

        val cleanFallbackExt = fallbackExtension.trim().let {
            if (it.isNotEmpty() && !it.startsWith(".")) ".$it" else it
        }

        val finalExt = when {
            !forceExtension.isNullOrBlank() -> {
                val fe = forceExtension.trim().lowercase()
                if (fe.startsWith(".")) fe else ".$fe"
            }
            rawExt.isNotEmpty() -> rawExt
            cleanFallbackExt.isNotEmpty() -> cleanFallbackExt
            else -> ""
        }

        // 2. 替换非法或容易引发文件系统及网络传输异常的字符
        var cleanBase = rawBase
            .replace(':', ' ')
            .replace('/', '-')
            .replace('\\', '-')
            .replace('|', '-')
            .replace('?', ' ')
            .replace('*', ' ')
            .replace('"', '\'')
            .replace('<', '[')
            .replace('>', ']')
            .replace(ILLEGAL_CHARS, " ")
            .replace('\u00A0', ' ')
            .replace('\u200B', ' ')
            .replace(MULTI_SPACE, " ")
            .trim()
            .trimEnd('.', ' ', '-')
            .trimStart('.', ' ')

        if (cleanBase.isBlank()) {
            cleanBase = if (isVideoFileName("file$finalExt")) "unnamed_video" else "unnamed_file"
        }

        // 3. 控制文件名长度（PikPak 接口限制 255 字节，保守限制 200 字符，保留后缀）
        val maxBaseLen = 200 - finalExt.length
        if (cleanBase.length > maxBaseLen) {
            cleanBase = cleanBase.take(maxBaseLen).trimEnd('.', ' ', '-')
        }

        return "$cleanBase$finalExt"
    }

    /**
     * 启发式检测主视频下标：
     * 1) 若只有 1 个视频，则返回该视频下标；
     * 2) 若有多个视频，主视频必须严格大于 0 字节且体积至少是次大视频的 10 倍以上（排除附带的 sample 或片头宣传等小片段视频）。
     * 若不满足（如多集动画、连续剧），返回 null。
     */
    fun findDominantVideoIndex(files: List<Pair<String, Long>>): Int? {
        val videoIndices = files.mapIndexedNotNull { index, (name, size) ->
            if (isVideoFileName(name)) Triple(index, name, size) else null
        }
        if (videoIndices.isEmpty()) return null
        if (videoIndices.size == 1) return videoIndices.first().first

        val sorted = videoIndices.sortedByDescending { it.third }
        val largest = sorted[0]
        val secondLargest = sorted[1]

        if (largest.third <= 0L) return null
        if (secondLargest.third == 0L || largest.third >= secondLargest.third * 10L) {
            return largest.first
        }
        return null
    }
}
