package dev.piko.shared.data

import dev.piko.shared.upload.isUploading
import io.github.nihildigit.pikpak.DecompressProgress
import io.github.nihildigit.pikpak.DecompressTask
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.decompressArchive
import io.github.nihildigit.pikpak.getDecompressProgress
import io.github.nihildigit.pikpak.getFile
import io.github.nihildigit.pikpak.getTask
import io.github.nihildigit.pikpak.listArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 服务端解压。压缩包的解析与写入都在 PikPak 那边完成，本机不下载任何内容。 */
class ArchiveRepository(private val clientManager: PikoClientProvider) {
    // 消息会原样显示在失败的那一行上
    private val client get() = clientManager.currentClient.value ?: error("未登录")

    /**
     * 把 [file] 解压到它旁边的新文件夹里，文件夹名取压缩包去掉扩展名。
     * 密码缺失或错误时以 ArchivePasswordException 失败。
     */
    suspend fun start(file: FileStat, password: String): Result<DecompressTask> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            // 接口要求 gcid。列表里的文件通常带着，刚上传完的可能还没算出来，此时取一次详情
            val gcid = file.hash.ifEmpty { client.getFile(file.id).hash }
            check(gcid.isNotEmpty()) { "文件尚未完成校验，稍后再试" }
            // 先列一层再提交。解压接口对从没解析过的加密包照单全收，要等任务失败才知道缺密码
            // （2026-09-25 实测，新传的 ZipCrypto 包连提交两次都是如此）；列目录则当场报缺密码或密码错误
            client.listArchive(file.id, gcid, password = password)
            client.decompressArchive(file.id, gcid, toParentId = null, password = password)
        }
    }

    suspend fun progress(taskId: String): Result<DecompressProgress> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.getDecompressProgress(taskId) }
    }

    /**
     * 失败任务的原因。解压进度接口的 error_description 是空的，原因只在同一 id 的网盘任务
     * params 里（media_center_result）。密码错误是 [INVALID_PASSWORD]；查不到时为 null。
     */
    suspend fun failureCause(taskId: String): String? = withContext(Dispatchers.Default) {
        runSuspendCatching { client.getTask(taskId).params["media_center_result"] }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    companion object {
        const val INVALID_PASSWORD = "E_INVALID_PASSWORD"

        /** 列得出目录、解压时才发现读不了：zip span 的最后一卷就是这样。 */
        const val INVALID_FORMAT = "E_INVALID_FORMAT"
    }
}

private val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z")

private val VOLUME_PATTERNS = listOf(
    // 新式 RAR 分卷：name.part1.rar、name.part01.rar
    Regex("""\.part\d+\.rar$""", RegexOption.IGNORE_CASE),
    // 7-Zip 按字节切的分卷：name.7z.001、name.zip.001
    Regex("""\.(7z|zip|rar)\.\d{3}$""", RegexOption.IGNORE_CASE),
    // zip 的 span 分卷 name.z01 与旧式 RAR 分卷 name.r00
    Regex("""\.[zr]\d{2}$""", RegexOption.IGNORE_CASE),
)

/**
 * 分卷压缩包的一卷。服务端解不了任何分卷：它只读交给它的那一个文件，不去同目录找其余分卷，
 * 各卷都放在同一目录里与只传第一卷的结果相同（2026-09-25 实测 7z、zip、RAR5 分卷）。
 * zip span 的最后一卷就叫 name.zip，单看名字认不出来；它能列出目录，解压任务却以
 * E_INVALID_FORMAT 失败，由 [ArchiveRepository.INVALID_FORMAT] 兜住。
 */
fun isArchiveVolume(name: String): Boolean = VOLUME_PATTERNS.any { it.containsMatchIn(name) }

/**
 * 能交给服务端解压的压缩包：单文件的 zip、rar、7z。tar 不认：服务端读不了 packFolder 产出的
 * tar（HTTP 500），其他来源的 tar 也未验证。
 */
fun isExtractableArchive(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in ARCHIVE_EXTENSIONS && !isArchiveVolume(name)

/** 上传中的不算：内容还不完整，解压服务回 file not complete。 */
val FileStat.isExtractableArchive: Boolean get() = !isFolder && !isUploading && isExtractableArchive(name)

/** 解压入口对它照样给出，点了说明为什么不行：只是隐藏的话，用户找不到原因。 */
val FileStat.isArchiveVolume: Boolean get() = !isFolder && isArchiveVolume(name)
