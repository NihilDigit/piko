package dev.piko.shared.data

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
    }
}

private val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z")

// 新式 RAR 分卷：name.part1.rar、name.part01.rar ……
private val RAR_VOLUME = Regex("""\.part(\d+)\.rar$""", RegexOption.IGNORE_CASE)

/**
 * 能交给服务端解压的压缩包：zip、rar、7z。
 *
 * RAR 分卷只认第一卷。后续卷的扩展名同样是 .rar，却不是独立的压缩包，单独提交只会失败。
 * 服务端能否顺着第一卷读到同目录的其余分卷未经实测；读不到时按失败显示原因。
 * 7z 与 zip 的分卷（.7z.001、.z01）扩展名是数字，不在此列。tar 不认：服务端读不了
 * packFolder 产出的 tar（HTTP 500），其他来源的 tar 也未验证。
 */
fun isExtractableArchive(name: String): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase()
    if (extension !in ARCHIVE_EXTENSIONS) return false
    val volume = RAR_VOLUME.find(name) ?: return true
    return volume.groupValues[1].toIntOrNull() == 1
}

val FileStat.isExtractableArchive: Boolean get() = !isFolder && isExtractableArchive(name)
