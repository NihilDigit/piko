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
 * 存盘与记进最近去过的路径：截到压缩包之前。重启后回到包里要先问密码，为这个弹框不值得，
 * 停在压缩包所在的文件夹即可。
 */
fun List<PikoPathBreadcrumb>.outsideArchives(): List<PikoPathBreadcrumb> {
    val first = indexOfFirst { ArchiveLocation.of(it.id) != null }
    return if (first < 0) this else subList(0, first)
}

/** 能放进东西、能列出网盘内容的真实文件夹，库与压缩包都不是。 */
fun isDriveFolderId(id: String): Boolean = DriveLibrary.of(id) == null && ArchiveLocation.of(id) == null

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
 * 网盘里没有常驻文件、打开时按 gcid 借一个对象的条目：归档条目与压缩包里的文件。
 * 播放、下载与信息流只认这里，不分它从哪来。
 */
object LeasedFile {
    fun isLeased(fileId: String): Boolean = VaultEntry.isVaulted(fileId) || ArchiveEntryId.isEntry(fileId)

    fun resolvedFileOf(fileId: String): ResolvedFile? = VaultEntry.resolvedFileOf(fileId) ?: ArchiveEntryId.resolvedFileOf(fileId)
}
