package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.TaskPhase
import io.github.nihildigit.pikpak.thumbnailUrlOf
import dev.piko.data.repository.isPlayableVideo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * 归档条目：网盘里不占空间、只留引用的一个文件。打开时按 [gcid] 借一个对象取直链，取完就删。
 * 代码里叫 vault，与压缩包（ArchiveRepository、服务端解压）区分；界面上叫「归档」。
 *
 * gcid 是 PikPak 全局索引里的内容哈希，与账号无关，所以只要还有人存着这份内容就能造回来。
 * 造不回来时凭 [source] 重新离线。
 */
@Serializable
data class VaultEntry(
    /** 这一条的身份。改名、换位置都不变，改动按它找条目。 */
    val id: String,
    val name: String,
    val size: Long,
    val gcid: String,
    /**
     * 取样 CID。体检只能经 gcidByCid 查，那个接口不收 gcid（SDK 的 FreeAccountProbeTest），
     * 所以归档时文件还在就先算好。保存时文件从未落盘，拿不到内容，为 null。
     */
    val cid: String? = null,
    /** 磁力或分享链接。 */
    val source: String? = null,
    /** 记进清单的时刻，毫秒。 */
    val addedAt: Long,
    /** 已取回时关联真实文件，保留来源元数据，不再作为虚拟条目显示。 */
    val restoredFileId: String? = null,
) {
    val isArchived: Boolean get() = restoredFileId == null

    fun matches(file: FileStat): Boolean = !file.isFolder &&
        file.hash.equals(gcid, ignoreCase = true) && file.sizeBytes == size &&
        (file.id == restoredFileId || file.name == name)

    fun enrich(file: FileStat): FileStat {
        if (!matches(file)) return file
        val metadata = buildMap {
            if (!source.isNullOrBlank() && file.sourceUrl.isNullOrBlank()) put("url", source)
            if (cid != null) put("piko_vault_cid", cid)
        }
        return file.copy(params = file.params + metadata)
    }

    /**
     * 列表里代表这一条的 ID。带前缀，不会与 PikPak 的文件 ID 撞上。条目 ID、gcid、大小与名字都在里面：
     * 播放器、下载这些只拿着 ID 的地方凭它就能借出对象，见 [resolvedFileOf]；改动凭它找回条目，见 [entryIdOf]。
     */
    val virtualId: String get() = "$VIRTUAL_ID_PREFIX$id/$gcid/$size/$name"

    /**
     * 列表里的一行。网盘页的解析、分组、排序与图标都照真实文件走；来源放进 params 的 url，与离线、转存的文件同处。
     * 时间用记进清单的时刻。
     */
    fun toFileStat(folderId: String): FileStat {
        val time = Instant.fromEpochMilliseconds(addedAt).toString()
        val row = FileStat(
            kind = FileKind.FILE,
            id = virtualId,
            parentId = folderId,
            name = name,
            size = size.toString(),
            fileExtension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" },
            createdTime = time,
            modifiedTime = time,
            hash = gcid,
            phase = TaskPhase.COMPLETE,
            params = source?.let { mapOf("url" to it) }.orEmpty(),
        )
        // 缩略图只由 gcid 决定、不带签名，不借文件就有；只给视频拼，别的类型 PikPak 没有截图
        return if (row.isPlayableVideo()) row.copy(thumbnailLink = thumbnailUrlOf(gcid)) else row
    }

    companion object {
        const val VIRTUAL_ID_PREFIX = "piko-vault:"

        /** 新的一条，ID 随机取。 */
        fun create(name: String, size: Long, gcid: String, source: String?, addedAt: Long, cid: String? = null) =
            VaultEntry(randomToken(), name, size, gcid, cid, source, addedAt)

        fun isVaulted(fileId: String): Boolean = fileId.startsWith(VIRTUAL_ID_PREFIX)

        /** [virtualId] 里的条目 ID，不是归档条目的 ID 时为 null。 */
        fun entryIdOf(fileId: String): String? = parts(fileId)?.get(0)

        /** [virtualId] 还原成秒传要的三样，不是归档条目的 ID 时为 null。名字里可以有斜杠以外的任何字符。 */
        fun resolvedFileOf(fileId: String): ResolvedFile? {
            val parts = parts(fileId) ?: return null
            val size = parts[2].toLongOrNull() ?: return null
            return ResolvedFile(path = parts[3], size = size, gcid = parts[1])
        }

        private fun parts(fileId: String): List<String>? {
            if (!isVaulted(fileId)) return null
            return fileId.removePrefix(VIRTUAL_ID_PREFIX).split('/', limit = 4).takeIf { it.size == 4 }
        }
    }
}

/** 列表里的这一行是归档条目，网盘里没有它的文件。 */
val FileStat.isVaulted: Boolean get() = VaultEntry.isVaulted(id)

/**
 * 对一个文件夹里归档条目的改动。都是纯函数，不存进清单：写入输了要重做时，原样套到赢家的状态上再算一次。
 * 作用在已经不在的条目上什么也不做，所以重做不会出错，重复的改动也在套用时自然消失。
 */
typealias VaultEdit = (List<VaultEntry>) -> List<VaultEntry>

object VaultEdits {
    /** 记进 [entries]。同一文件夹里同名、同内容的已有一条就换成新的：同一集存两次不多出一行。 */
    fun add(entries: List<VaultEntry>): VaultEdit = { current ->
        val ids = entries.mapTo(HashSet()) { it.id }
        val same = entries.mapTo(HashSet()) { it.gcid to it.name }
        current.filterNot { it.id in ids || (it.gcid to it.name) in same } + entries
    }

    fun rename(id: String, name: String): VaultEdit = { current ->
        current.map { if (it.id == id) it.copy(name = name) else it }
    }

    fun remove(ids: Set<String>): VaultEdit = { current -> current.filterNot { it.id in ids } }

    /** 取回后保留来源及 CID，同时跟随真实文件的新名称。 */
    fun restore(files: Map<String, FileStat>): VaultEdit = { current ->
        current.map { entry -> files[entry.id]?.let { entry.copy(name = it.name, restoredFileId = it.id) } ?: entry }
    }
}

/** 一次改写前后的条目，撤销要用改之前的。 */
class VaultWrite(val before: List<VaultEntry>, val after: List<VaultEntry>)

/** [VaultStore] 对网盘的全部要求。测试里换成内存里的，好模拟两台设备同时写。 */
interface VaultFolderIo {
    suspend fun list(folderId: String): List<FileStat>
    suspend fun read(fileId: String): ByteArray
    suspend fun upload(folderId: String, name: String, bytes: ByteArray): String
    suspend fun delete(ids: List<String>)
}

/**
 * 每个目录里的 `.piko-vault-v<版本>-<随机串>.json`，记着直接放在这一层的归档条目；子目录照旧是真实的文件夹，
 * 文件夹不占空间。清单跟着文件夹走，移动、改名都不用另外维护，官方客户端里看是一个只有 json 的文件夹。
 *
 * 文件里只有当前状态，不存历史。PikPak 没有「版本没变才写」的条件写入，这里用文件名模拟：
 * 读到 v7 就写 v8，写完再列一次目录；同一版本有几份时随机串最小的算数（读的时候也按这条挑），
 * 输的一方删掉自己那份，拿同一个 [VaultEdit] 套到赢家上重写。两台设备同时改同一个文件夹，双方的改动都在。
 *
 * 剩下的风险在列目录的延迟：两边都没看到对方的文件，就都以为自己赢了。读的时候只认一个，状态不会分叉，
 * 但输的那次改动会丢。赢了之后隔 [confirmDelay] 再列一次确认，确认完才算写成；这要两台设备在同一秒改同一个文件夹。
 */
class VaultStore(
    private val io: VaultFolderIo,
    private val confirmDelay: Duration = 1.seconds,
    private val newToken: () -> String = ::randomToken,
    /** 某个文件夹的条目眼看着变了（改动开始、写成、失败退回），列表该重画。 */
    private val onChanged: (folderId: String) -> Unit = {},
    /** 写成之后这个文件夹里的条目。归档一整棵树时子文件夹没人列过，靠它给文件夹挂上标记。 */
    private val onWritten: (folderId: String, entries: List<VaultEntry>) -> Unit = { _, _ -> },
) {
    constructor(driveRepo: PikoDriveRepository) : this(
        DriveVaultIo(driveRepo),
        onChanged = { driveRepo.requestRefresh() },
        onWritten = driveRepo::vaultEntriesKnown,
    )

    // 各文件夹最近一次读到或写成的条目
    private val known = MutableStateFlow<Map<String, List<VaultEntry>>>(emptyMap())

    // 正在写的改动先套在 known 上的样子。可信写入要列几次目录、确认还要等一会儿，前后两三秒；
    // 改动本身是纯函数，先照它画出来，写成后以网盘上的为准，失败就退回
    private val pending = MutableStateFlow<Map<String, List<VaultEntry>>>(emptyMap())

    // 同一进程里对同一目录的改动排队：各自读到同一版本再去撞，只会多重试一轮
    private val locks = HashMap<String, Mutex>()
    private val locksGuard = Mutex()

    // 清单文件 ID 到其中的条目。一份清单写成后不再改动，改动总是另写一份，所以按 ID 缓存不会过时
    private val parsed = MutableStateFlow<Map<String, List<VaultEntry>>>(emptyMap())

    /**
     * [folderId] 的条目，[listing] 是它的目录列表。有正在写的改动时是改动之后的样子。
     * 读不出来就失败，不当作空。
     */
    suspend fun read(folderId: String, listing: List<FileStat>): Result<List<VaultEntry>> = runSuspendCatching {
        pending.value[folderId]?.let { return@runSuspendCatching it }
        val entries = winner(listing)?.let { entriesOf(it.file) }.orEmpty()
        known.update { it + (folderId to entries) }
        entries
    }

    /** 只用缓存的 [read]：算数的那份没读过时返回 null。 */
    fun cached(folderId: String, listing: List<FileStat>): List<VaultEntry>? {
        pending.value[folderId]?.let { return it }
        val file = winner(listing)?.file ?: return emptyList()
        return parsed.value[file.id]
    }

    /**
     * 把 [edit] 写进 [folderId] 的清单。现列目录再读，不用调用方手上的列表：它可能已经过时。
     * 改完与原来相同就不写。
     */
    suspend fun update(folderId: String, edit: VaultEdit): Result<VaultWrite> {
        // 前一个改动还没写完时接着它的样子画，免得第二下把第一下暂时盖回去
        (pending.value[folderId] ?: known.value[folderId])?.let { before ->
            pending.update { it + (folderId to edit(before)) }
            onChanged(folderId)
        }
        return try {
            write(folderId, edit).onSuccess { write ->
                known.update { it + (folderId to write.after) }
                onWritten(folderId, write.after)
            }
        } finally {
            pending.update { it - folderId }
            onChanged(folderId)
        }
    }

    private suspend fun write(folderId: String, edit: VaultEdit): Result<VaultWrite> =
        lockOf(folderId).withLock {
            runSuspendCatching {
                repeat(MAX_ATTEMPTS) {
                    val listing = io.list(folderId)
                    val base = winner(listing)
                    val before = base?.let { entriesOf(it.file) }.orEmpty()
                    val after = edit(before)
                    if (after == before) return@runSuspendCatching VaultWrite(before, after)

                    val version = (base?.version ?: 0) + 1
                    val token = newToken()
                    val text = JSON.encodeToString(Manifest.serializer(), Manifest(after))
                    // 上传完成即内容已按 gcid 核对过，不读回：刚传完的文件常常一时还没有直链，读回反倒失败。
                    // 自己写的这份直接记进缓存，确认输赢时不必下载
                    writeRecoveryFiles(folderId, after)
                    val id = io.upload(folderId, nameOf(version, token), text.encodeToByteArray())
                    parsed.update { it + (id to after) }

                    if (won(folderId, version, token) && won(folderId, version, token, afterDelay = true)) {
                        // 旧版本连同同版本里输掉又没来得及删的一并清掉；同版本别人的那份留给它自己
                        val stale = io.list(folderId).filter { file ->
                            looksLikeManifest(file) && file.id != id && (versionOf(file)?.let { it.first < version } ?: true)
                        }
                        if (stale.isNotEmpty()) io.delete(stale.map { it.id })
                        return@runSuspendCatching VaultWrite(before, after)
                    }
                    io.delete(listOf(id))
                }
                error("清单写入冲突，重试 $MAX_ATTEMPTS 次仍未写成")
            }
        }

    /** 可读文件与来源链接先落盘，原文件之后才能处置。每个来源只保存一份链接。 */
    private suspend fun writeRecoveryFiles(folderId: String, entries: List<VaultEntry>) {
        val sources = entries.mapNotNull { it.source?.takeIf(String::isNotBlank) }.distinct()
        if (sources.isEmpty()) return
        val listing = io.list(folderId)
        for (source in sources) {
            val magnet = source.startsWith("magnet:", ignoreCase = true)
            val name = "归档来源-${sourceToken(source)}.${if (magnet) "magnet" else "txt"}"
            val bytes = (source + "\n").encodeToByteArray()
            val existing = listing.firstOrNull { !it.isFolder && it.name == name }
            if (existing == null) io.upload(folderId, name, bytes)
            else check(io.read(existing.id).contentEquals(bytes)) { "来源文件同名但内容不同，未处置原文件：$name" }
        }
    }

    /** 眼下 [version] 这一版里算数的是不是 [token]。 */
    private suspend fun won(folderId: String, version: Int, token: String, afterDelay: Boolean = false): Boolean {
        if (afterDelay) delay(confirmDelay)
        var rivals = io.list(folderId).mapNotNull(::candidateOf).filter { it.version >= version }
        // 上传成功与目录列表可见并非同时发生，后续列表也可能暂时回退。等待自己的版本出现，避免误判冲突。
        for (wait in listOf(100L, 200L, 400L, 800L, 1600L)) {
            if (rivals.any { it.version > version || (it.version == version && it.token == token) }) break
            delay(wait)
            rivals = io.list(folderId).mapNotNull(::candidateOf).filter { it.version >= version }
        }
        return rivals.minWithOrNull(ORDER)?.let { it.version == version && it.token == token } ?: false
    }

    private suspend fun entriesOf(file: FileStat): List<VaultEntry> {
        parsed.value[file.id]?.let { return it }
        val entries = JSON.decodeFromString(Manifest.serializer(), io.read(file.id).decodeToString()).entries
        parsed.update { it + (file.id to entries) }
        return entries
    }

    private suspend fun lockOf(folderId: String): Mutex = locksGuard.withLock { locks.getOrPut(folderId) { Mutex() } }

    @Serializable
    private class Manifest(val entries: List<VaultEntry>)

    private class Candidate(val file: FileStat, val version: Int, val token: String)

    companion object {
        private const val PREFIX = ".piko-vault-"
        private const val SUFFIX = ".json"
        private const val MAX_ATTEMPTS = 5
        private val JSON = Json { ignoreUnknownKeys = true }
        private val NAME = Regex("""\.piko-vault-v([0-9]+)-([0-9a-f]+)\.json""")

        // 版本大的在前，同版本随机串小的在前：排第一的算数
        private val ORDER = compareByDescending<Candidate> { it.version }.thenBy { it.token }

        private fun nameOf(version: Int, token: String) = "${PREFIX}v$version-$token$SUFFIX"

        private fun versionOf(file: FileStat): Pair<Int, String>? =
            NAME.matchEntire(file.name)?.let { it.groupValues[1].toInt() to it.groupValues[2] }

        private val SOURCE_NAME = Regex("""归档来源-[0-9a-f]{16}\.(magnet|txt)""")
        fun isRecoveryFile(file: FileStat): Boolean = !file.isFolder && SOURCE_NAME.matches(file.name)

        private fun sourceToken(source: String): String {
            var hash = 0xcbf29ce484222325uL
            for (byte in source.encodeToByteArray()) hash = (hash xor byte.toUByte().toULong()) * 0x100000001b3uL
            return hash.toString(16).padStart(16, '0')
        }

        /**
         * 能算数的清单。上传中途进程被杀会留下 PENDING 的空壳，读不出内容，不算：
         * 否则它的版本最新，次次读它失败，这个目录再也改不动。
         */
        private fun candidateOf(file: FileStat): Candidate? {
            if (file.isFolder || file.phase != TaskPhase.COMPLETE) return null
            val (version, token) = versionOf(file) ?: return null
            return Candidate(file, version, token)
        }

        private fun winner(listing: List<FileStat>): Candidate? = listing.mapNotNull(::candidateOf).minWithOrNull(ORDER)

        /** 清单文件，含没写完的空壳与输掉的那几份。列表里一概不显示。 */
        fun looksLikeManifest(file: FileStat): Boolean =
            !file.isFolder && file.name.startsWith(PREFIX) && file.name.endsWith(SUFFIX)
    }
}

private class DriveVaultIo(private val driveRepo: PikoDriveRepository) : VaultFolderIo {
    override suspend fun list(folderId: String) = driveRepo.listAllFiles(folderId).getOrThrow()
    override suspend fun read(fileId: String) = driveRepo.readBytes(fileId).getOrThrow()
    override suspend fun upload(folderId: String, name: String, bytes: ByteArray) = driveRepo.uploadBytes(folderId, name, bytes).getOrThrow()
    override suspend fun delete(ids: List<String>) = driveRepo.delete(ids).getOrThrow()
}

private fun randomToken(): String = Random.nextBytes(8).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
