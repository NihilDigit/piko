package dev.piko.shared.state

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.repository.NaturalOrder
import dev.piko.data.repository.isPlayableVideo
import dev.piko.shared.data.ArchiveEntryId
import dev.piko.shared.data.ArchiveLocation
import dev.piko.shared.data.ArchivePasswordVault
import dev.piko.shared.data.ArchiveRepository
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.log.PikoLog
import io.github.nihildigit.pikpak.ArchiveEntry
import io.github.nihildigit.pikpak.ArchiveListing
import io.github.nihildigit.pikpak.ArchivePasswordException
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.TaskPhase
import io.github.nihildigit.pikpak.thumbnailUrlOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 把压缩包当文件夹看（见 [ArchiveLocation]）：列包里的一层、记住这次输过的密码、为打开包里的文件做准备。
 *
 * 进程级，挂在 PikoServices 上：网盘页随压栈页离开组合后重建，密码与列过的层不该跟着丢，
 * 否则从包里播完一个视频回来又要输一遍密码。换账号时 [clear]。
 *
 * 只在主线程上调用；网络请求由 [ArchiveRepository] 切到后台。
 */
class ArchiveBrowser(
    clientProvider: PikoClientProvider,
    preferences: PikoUserPreferences,
    /** 引导解压与借出对象放在哪个目录，即 Piko-Temp。 */
    private val scratchFolder: suspend () -> Result<String>,
) {
    private val repository = ArchiveRepository(clientProvider)
    private val vault = ArchivePasswordVault(preferences)

    val savedPasswords: Flow<List<String>> = vault.passwords

    // 按压缩包的文件 ID。没要过密码的包记的是空串
    private val passwords = mutableMapOf<String, String>()
    private val listings = mutableMapOf<String, List<FileStat>>()
    private val primed = mutableSetOf<String>()
    private val primeLock = Mutex()

    fun password(archiveId: String): String = passwords[archiveId].orEmpty()

    /** 列过的这一层，进来时先显示它再刷新，与网盘里的目录相同。 */
    fun cached(location: ArchiveLocation): List<FileStat>? = listings[location.id]

    /**
     * 列出包里的一层。[password] 为 null 时用这个包上次验证过的；给了就是用户刚输的，通过后记下，
     * 并与解压一样存进密码表。密码缺失或错误时以 ArchivePasswordException 失败。
     */
    suspend fun list(location: ArchiveLocation, password: String? = null): Result<List<FileStat>> {
        val known = password ?: passwords[location.archiveId]
        val result = if (known != null) {
            repository.list(location, known).map { known to it }
        } else {
            firstAccepted(location)
        }
        return result.map { (used, listing) ->
            passwords[location.archiveId] = used
            if (password != null && password.isNotEmpty()) vault.remember(password)
            listing.files.map { it.toFileStat(location) }
                .sortedWith(compareBy<FileStat> { !it.isFolder }.thenBy(NaturalOrder) { it.name })
                .also { listings[location.id] = it }
        }
    }

    /**
     * 头一回进这个包：先不带密码列，要密码时把存过的逐个试一遍，都不对才让界面弹框。
     * 解压不这样做（见 ArchivePasswordVault），那边每试一次是一个真实的解压任务；列目录只是一次查询。
     */
    private suspend fun firstAccepted(location: ArchiveLocation): Result<Pair<String, ArchiveListing>> {
        val plain = repository.list(location, "")
        val missing = plain.exceptionOrNull() as? ArchivePasswordException ?: return plain.map { "" to it }
        if (missing.incorrect) return plain.map { "" to it }
        val candidates = savedPasswords.first().take(MAX_SAVED_TRIES)
        // 只记第几个存过的密码对上，密码本身不记
        for ((index, saved) in candidates.withIndex()) {
            val attempt = repository.list(location, saved)
            if (attempt.isSuccess) {
                PikoLog.d(TAG, "压缩包 ${location.archiveId} 要密码，存过的第 ${index + 1} 个对上")
                return attempt.map { saved to it }
            }
            if (attempt.exceptionOrNull() !is ArchivePasswordException) {
                PikoLog.w(TAG, "压缩包 ${location.archiveId} 试存过的密码时出错，停止尝试", attempt.exceptionOrNull())
                return attempt.map { saved to it }
            }
        }
        PikoLog.d(TAG, "压缩包 ${location.archiveId} 要密码，存过的 ${candidates.size} 个都不对，等用户输入")
        return plain.map { "" to it }
    }

    /**
     * 把包里的 [entry] 变成借得出来的样子：带着 gcid，所在目录改成 Piko-Temp，播放器与下载按 gcid 借对象时放在那里。
     * 从没解压过的包先引导解压一次（见 [ArchiveRepository.prime]），挑 [siblings] 里最小的文件，几秒即完成。
     * 之后这个包的各层都要重列才有 gcid，列过的一并作废。
     */
    suspend fun prepare(entry: FileStat, siblings: List<FileStat>): Result<FileStat> = runSuspendCatching {
        val location = ArchiveLocation.of(entry.parentId) ?: error("不是压缩包里的文件")
        val path = ArchiveEntryId.pathOf(entry.id)?.second ?: error("不是压缩包里的文件")
        val scratch = scratchFolder().getOrThrow()
        val relisted = listings[location.id]?.firstOrNull {
            ArchiveEntryId.pathOf(it.id)?.second == path && ArchiveEntryId.resolvedFileOf(it.id) != null
        }
        val ready = if (ArchiveEntryId.resolvedFileOf(entry.id) != null) {
            entry
        } else if (relisted != null) {
            // 同一批里的前一个已经引导过、重列过这一层
            relisted
        } else {
            val smallest = siblings.filter { !it.isFolder }.minByOrNull { it.sizeBytes } ?: entry
            prime(location, ArchiveEntryId.pathOf(smallest.id)?.second ?: path, scratch)
            listings.keys.removeAll { ArchiveLocation.of(it)?.archiveId == location.archiveId }
            list(location).getOrThrow().firstOrNull { ArchiveEntryId.pathOf(it.id)?.second == path }
                ?: error("压缩包里已没有这个文件")
        }
        checkNotNull(ArchiveEntryId.resolvedFileOf(ready.id)) { "服务端未给出这个文件的内容标识" }
        ready.copy(parentId = scratch)
    }

    // 同一个包只引导一次：连点两个文件、或播放列表与点开的那一项同时要，不该各解压一遍
    private suspend fun prime(location: ArchiveLocation, entryPath: String, scratch: String) = primeLock.withLock {
        if (location.archiveId in primed) return@withLock
        repository.prime(location, entryPath, password(location.archiveId), scratch).getOrThrow()
        primed += location.archiveId
    }

    fun clear() {
        passwords.clear()
        listings.clear()
        primed.clear()
    }

    private companion object {
        const val TAG = "Archive"

        // 密码表最近用过的在前，存得多的人试到后面也多半是旧包的，不值得一直试下去
        const val MAX_SAVED_TRIES = 10
    }
}

/**
 * 包里的一项换成网盘页的一行，列表、网格与详情栏照常画。文件夹的 ID 是包里那一层的位置，点开即压栈；
 * 文件的 ID 带着借出要的 gcid，没解压过的包里 gcid 为空。没有时间：服务端不给包内条目的时间。
 */
private fun ArchiveEntry.toFileStat(location: ArchiveLocation): FileStat {
    val displayName = name.ifEmpty { path.trimEnd('/').substringAfterLast('/') }
    if (isFolder) {
        return FileStat(
            kind = FileKind.FOLDER,
            id = location.copy(path = path).id,
            parentId = location.id,
            name = displayName,
            phase = TaskPhase.COMPLETE,
        )
    }
    val row = FileStat(
        kind = FileKind.FILE,
        id = ArchiveEntryId.of(location.archiveId, gcid, sizeBytes, path),
        parentId = location.id,
        name = displayName,
        size = sizeBytes.toString(),
        fileExtension = displayName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" },
        mimeType = mimeType,
        hash = gcid,
        phase = TaskPhase.COMPLETE,
    )
    // 与归档条目相同：缩略图只由 gcid 决定，不借文件就有，只给视频拼
    return if (gcid.isNotEmpty() && row.isPlayableVideo()) row.copy(thumbnailLink = thumbnailUrlOf(gcid)) else row
}
