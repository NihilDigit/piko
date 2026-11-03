package dev.piko.shared.data

import io.github.nihildigit.pikpak.ResolvedFile

/**
 * 压缩包里的一层，当作网盘页的一个位置：点开压缩包就是把它压进路径栈，包里的子目录接着往下压。
 * 后退、地址栏、面包屑与标签因此照常工作，与 [DriveLibrary] 同理。
 *
 * ID 里带着列目录要的全部：压缩包的文件 ID、gcid（listArchive 两样都要）与包内路径（顶层为空，子目录以 / 结尾）。
 * PikPak 的文件 ID 与 gcid 都只有字母与数字，包内路径放在最后，里面有冒号也不影响拆分。
 */
data class ArchiveLocation(val archiveId: String, val gcid: String, val path: String) {
    val id: String get() = "$PREFIX$archiveId:$gcid:$path"

    companion object {
        private const val PREFIX = "piko-archive:"

        fun of(id: String): ArchiveLocation? {
            if (!id.startsWith(PREFIX)) return null
            val parts = id.removePrefix(PREFIX).split(':', limit = 3)
            return if (parts.size == 3) ArchiveLocation(parts[0], parts[1], parts[2]) else null
        }
    }
}

/** 路径栈停在压缩包里，压缩包本身与包里的子目录都算。 */
val List<PikoPathBreadcrumb>.archive: ArchiveLocation? get() = lastOrNull()?.let { ArchiveLocation.of(it.id) }

/**
 * 存盘与记进最近去过的路径：截到压缩包与待保存虚拟目录之前。重启后回到包里要先问密码，为这个弹框不值得，
 * 停在压缩包所在的文件夹即可；待保存虚拟目录在关闭添加链接后也不复存在。
 */
fun List<PikoPathBreadcrumb>.outsideArchives(): List<PikoPathBreadcrumb> {
    val first = indexOfFirst { ArchiveLocation.of(it.id) != null || ImportPreviewLocation.of(it.id) != null }
    return if (first < 0) this else subList(0, first)
}

/** 能放进东西、能列出网盘内容的真实文件夹，库、压缩包与待保存虚拟目录都不是。 */
fun isDriveFolderId(id: String): Boolean =
    !LocalLocation.isLocal(id) && DriveLibrary.of(id) == null && ArchiveLocation.of(id) == null &&
        ImportPreviewLocation.of(id) == null && !id.startsWith("import-dir:")

/**
 * 添加链接时混入网盘浏览页的虚拟文件夹位置。
 * [targetFolderId] 是保存目标所在的真实网盘目录 ID，[path] 是相对于保存目标的虚拟子路径（不以 `/` 开头或结尾）。
 */
data class ImportPreviewLocation(val targetFolderId: String, val path: String) {
    val id: String get() = "$PREFIX$targetFolderId:${path.trim('/')}"

    companion object {
        private const val PREFIX = "piko-import-dir:"

        fun of(id: String): ImportPreviewLocation? {
            if (!id.startsWith(PREFIX)) return null
            val parts = id.removePrefix(PREFIX).split(':', limit = 2)
            return if (parts.size == 2) ImportPreviewLocation(parts[0], parts[1].trim('/')) else null
        }
    }
}

/**
 * 添加链接时混入网盘浏览页的虚拟文件条目。
 * 打开或下载时与归档条目一样按 [gcid] 借用对象（见 [LeasedFile]）；在网盘页删除它即取消右侧对应的勾选（[sourceIndex]）。
 */
object ImportPreviewEntryId {
    private const val PREFIX = "piko-import-entry:"

    fun of(sourceIndex: Int, gcid: String?, size: Long, name: String): String =
        "$PREFIX$sourceIndex/${gcid.orEmpty()}/$size/$name"

    fun isEntry(fileId: String): Boolean = fileId.startsWith(PREFIX)

    fun sourceIndexOf(fileId: String): Int? = parts(fileId)?.get(0)?.toIntOrNull()

    fun resolvedFileOf(fileId: String): ResolvedFile? {
        val parts = parts(fileId) ?: return null
        val gcid = parts[1].takeIf { it.isNotEmpty() } ?: return null
        val size = parts[2].toLongOrNull() ?: return null
        return ResolvedFile(path = parts[3], size = size, gcid = gcid)
    }

    private fun parts(fileId: String): List<String>? {
        if (!isEntry(fileId)) return null
        return fileId.removePrefix(PREFIX).split('/', limit = 4).takeIf { it.size == 4 }
    }
}

val io.github.nihildigit.pikpak.FileStat.isImportPreview: Boolean
    get() = ImportPreviewEntryId.isEntry(id) || ImportPreviewLocation.of(id) != null

/**
 * 压缩包里的一个文件。网盘里没有它，打开时与归档条目一样按 gcid 借一个对象（见 [LeasedFile]），
 * 所以 ID 里带着秒传要的 gcid、大小与包内路径，前面再加压缩包的文件 ID 以免两个包里的同一份内容撞上。
 *
 * 从没解压过的包，服务端列目录时不给 gcid，这时 [gcid] 为空、还借不出来，要先经 ArchiveBrowser.prepare。
 */
object ArchiveEntryId {
    private const val PREFIX = "piko-archive-entry:"

    fun of(archiveId: String, gcid: String, size: Long, path: String): String = "$PREFIX$archiveId/$gcid/$size/$path"

    fun isEntry(fileId: String): Boolean = fileId.startsWith(PREFIX)

    /** 所在压缩包的文件 ID 与包内路径，不是包内文件时为 null。 */
    fun pathOf(fileId: String): Pair<String, String>? = parts(fileId)?.let { it[0] to it[3] }

    fun resolvedFileOf(fileId: String): ResolvedFile? {
        val parts = parts(fileId) ?: return null
        val gcid = parts[1].takeIf { it.isNotEmpty() } ?: return null
        val size = parts[2].toLongOrNull() ?: return null
        return ResolvedFile(path = parts[3], size = size, gcid = gcid)
    }

    private fun parts(fileId: String): List<String>? {
        if (!isEntry(fileId)) return null
        return fileId.removePrefix(PREFIX).split('/', limit = 4).takeIf { it.size == 4 }
    }
}

/**
 * 网盘里没有常驻文件、打开时按 gcid 借一个对象的条目：归档条目、压缩包里的文件与添加链接的虚拟预览文件。
 * 播放、下载与信息流只认这里，不分它从哪来。
 */
object LeasedFile {
    private const val CONTENT_PREFIX = "piko-content:"

    fun isLeased(fileId: String): Boolean =
        VaultEntry.isVaulted(fileId) || ArchiveEntryId.isEntry(fileId) ||
            ImportPreviewEntryId.isEntry(fileId) || fileId.startsWith(CONTENT_PREFIX)

    fun resolvedFileOf(fileId: String): ResolvedFile? =
        VaultEntry.resolvedFileOf(fileId)
            ?: ArchiveEntryId.resolvedFileOf(fileId)
            ?: ImportPreviewEntryId.resolvedFileOf(fileId)
            ?: contentResolvedFileOf(fileId)

    private fun contentResolvedFileOf(fileId: String): ResolvedFile? {
        if (!fileId.startsWith(CONTENT_PREFIX)) return null
        val parts = fileId.removePrefix(CONTENT_PREFIX).split(':', limit = 2)
        if (parts.size != 2) return null
        val gcid = parts[0].takeIf { it.isNotBlank() } ?: return null
        val size = parts[1].toLongOrNull() ?: return null
        return ResolvedFile(path = gcid, size = size, gcid = gcid)
    }
}
