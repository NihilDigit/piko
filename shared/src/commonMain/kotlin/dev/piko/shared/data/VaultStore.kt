package dev.piko.shared.data

import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.FileTag
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.TaskPhase
import io.github.nihildigit.pikpak.videoThumbnailUrlOf
import io.github.nihildigit.pikpak.imageThumbnailUrlOf
import dev.piko.data.repository.isPlayableVideo
import dev.piko.data.repository.isPreviewableImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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
    val duration: String? = null,
    val width: String? = null,
    val height: String? = null,
    val mimeType: String? = null,
    /** 原文件实例的最初存入时间；旧清单回退到 addedAt。 */
    val createdAt: Long? = null,
) {
    val isArchived: Boolean get() = restoredFileId == null

    fun matches(file: FileStat): Boolean = !file.isFolder &&
        file.hash.equals(gcid, ignoreCase = true) && file.sizeBytes == size &&
        (file.id == restoredFileId || file.name == name)

    fun enrich(file: FileStat, preserveStar: Boolean = false): FileStat {
        if (!matches(file)) return file
        val metadata = buildMap {
            if (file.id == restoredFileId) put("piko_original_created_at", (createdAt ?: addedAt).toString())
            if (!source.isNullOrBlank() && file.sourceUrl.isNullOrBlank()) put("url", source)
            if (cid != null) put("piko_vault_cid", cid)
            if (file.params["duration"].isNullOrBlank() && duration != null) put("duration", duration)
            if (file.params["width"].isNullOrBlank() && width != null) put("width", width)
            if (file.params["height"].isNullOrBlank() && height != null) put("height", height)
        }
        val nextTags = if (preserveStar && file.tags.none { it.name.equals("STAR", ignoreCase = true) }) {
            file.tags + FileTag(id = "STAR", name = "STAR")
        } else {
            file.tags
        }
        return file.copy(
            params = file.params + metadata,
            mimeType = file.mimeType.ifBlank { mimeType.orEmpty() },
            tags = nextTags,
        )
    }

    /**
     * 列表里代表这一条的 ID。带前缀，不会与 PikPak 的文件 ID 撞上。条目 ID、gcid、大小与名字都在里面：
     * 播放器、下载这些只拿着 ID 的地方凭它就能借出对象，见 [resolvedFileOf]；改动凭它找回条目，见 [entryIdOf]。
     */
    val virtualId: String get() = "$VIRTUAL_ID_PREFIX$id/$gcid/$size/$name"

    /**
     * 列表里的一行。网盘页的解析、分组、排序与图标都照真实文件走；来源放进 params 的 url，与离线、转存的文件同处。
     * 时间用原文件存入时刻，旧清单回退到记入时刻；[starred] 为真时由 Piko 补上 STAR 标签。
     */
    fun toFileStat(folderId: String, starred: Boolean = false): FileStat {
        val time = Instant.fromEpochMilliseconds(createdAt ?: addedAt).toString()
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
            mimeType = mimeType.orEmpty(),
            tags = if (starred) listOf(FileTag(id = "STAR", name = "STAR")) else emptyList(),
            params = buildMap {
                source?.let { put("url", it) }
                duration?.let { put("duration", it) }
                width?.let { put("width", it) }
                height?.let { put("height", it) }
            },
        )
        // 缩略图只由 gcid 决定、不带签名，不借文件就有；给视频与图片拼上
        return when {
            row.isPlayableVideo() -> row.copy(thumbnailLink = videoThumbnailUrlOf(gcid))
            row.isPreviewableImage() -> row.copy(thumbnailLink = imageThumbnailUrlOf(gcid))
            else -> row
        }
    }

    companion object {
        const val VIRTUAL_ID_PREFIX = "piko-vault:"

        /** 新的一条，ID 随机取。 */
        fun create(
            name: String, size: Long, gcid: String, source: String?, addedAt: Long, cid: String? = null,
            duration: String? = null, width: String? = null, height: String? = null, mimeType: String? = null,
            createdAt: Long? = null,
        ) = VaultEntry(randomToken(), name, size, gcid, cid, source, addedAt,
            duration = duration, width = width, height = height, mimeType = mimeType, createdAt = createdAt)

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
 * 替文件夹当封面的条目：按名字排在最前的、仍在归档中的视频，与条目行的缩略图同出一处（按 gcid 拼出）。
 * 只用 gcid，不需要清单另记缩略图，所以任何版本写下的清单都能补出封面。
 */
internal fun List<VaultEntry>.coverGcid(): String? {
    val entry = filter { it.isArchived }.sortedBy { it.name }.firstOrNull { it.toFileStat("").thumbnailLink.isNotEmpty() } ?: return null
    val stat = entry.toFileStat("")
    return if (stat.isPreviewableImage()) "img:${entry.gcid}" else entry.gcid
}

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

/** 清单提交后失去确认时，保留该次真实写入的基线，供结果核对与具体撤销使用。 */
class VaultWriteUnconfirmed(val attempted: VaultWrite, cause: Throwable) : Exception("归档清单写入结果未确认", cause)

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
/** 清单提交确认后传给本地文件树的目录证据：区分经过真实列举的完整快照与新目录快速通道的增量文件。 */
sealed interface VaultCommitSnapshot {
    /** 真实列举确认的完整目录快照，携带发起该次列举前采样的目录变更序号 [listedAtSerial]。 */
    class FullListing(val listing: List<FileStat>, val listedAtSerial: Long? = null) : VaultCommitSnapshot

    /** 新建专属目录快速通道产生的增量文件（归档来源与清单文件），不覆盖同目录已建好的嵌套子目录。 */
    class IncrementalFiles(val added: List<FileStat>) : VaultCommitSnapshot
}

class VaultStore(
    private val io: VaultFolderIo,
    private val confirmDelay: Duration = 1.seconds,
    private val newToken: () -> String = ::randomToken,
    /** 某个文件夹的条目眼看着变了（改动开始、写成、失败退回），列表该重画。 */
    private val onChanged: (folderId: String) -> Unit = {},
    /** 写成之后这个文件夹里的条目。归档一整棵树时子文件夹没人列过，靠它给文件夹挂上标记。 */
    private val onWritten: (folderId: String, entries: List<VaultEntry>) -> Unit = { _, _ -> },
    /** 清单确认落盘后连同确认时的目录证据一并同步给本地文件树，免去外层盲目标脏重列。 */
    private val onCommitted: (folderId: String, entries: List<VaultEntry>, snapshot: VaultCommitSnapshot?) -> Unit = { _, _, _ -> },
    /** 发起列目录前采样该目录在仓库中的变更序号，供提交完整快照时拦截挂起期间发生的并发文件变更。 */
    private val currentFolderSerial: (folderId: String) -> Long? = { null },
    private val cacheStore: PikoCacheStore? = null,
    private val account: String? = null,
    private val scope: CoroutineScope? = null,
    private val saveDelayMillis: Long = 2_000L,
    initialParsed: Map<String, List<VaultEntry>> = emptyMap(),
    /** Same-key predecessor's close result. Join its termination, independently of commit success. */
    private val predecessor: Job? = null,
) {
    // 各文件夹最近一次读到或写成的条目
    private val known = MutableStateFlow<Map<String, List<VaultEntry>>>(emptyMap())

    // 正在写的改动先套在 known 上的样子。可信写入要列几次目录、确认还要等一会儿，前后两三秒；
    // 改动本身是纯函数，先照它画出来，写成后以网盘上的为准，失败就退回
    private class PendingEdit(val edit: VaultEdit) {
        val done = CompletableDeferred<Unit>()
    }
    private val pending = MutableStateFlow<Map<String, List<PendingEdit>>>(emptyMap())
    internal val editingFolders: Set<String> get() = pending.value.keys
    internal val hasPendingWrites: Boolean get() = pending.value.isNotEmpty()
    internal fun projectedEntries(folderId: String): List<VaultEntry>? = pendingEntries(folderId) ?: known.value[folderId]

    private fun pendingEntries(folderId: String): List<VaultEntry>? {
        val edits = pending.value[folderId] ?: return null
        return edits.fold(known.value[folderId] ?: return null) { entries, pending -> pending.edit(entries) }
    }

    // 同一进程里对同一目录的改动排队：各自读到同一版本再去撞，只会多重试一轮
    private val locks = Array(64) { Mutex() }

    // 清单文件 ID 到其中的条目。一份清单写成后不再改动，改动总是另写一份，所以按 ID 缓存不会过时
    private val parsed = MutableStateFlow(initialParsed)
    private val trustedPendingIds = MutableStateFlow(initialParsed.keys.toSet())
    val parsedFlow: StateFlow<Map<String, List<VaultEntry>>> = parsed.asStateFlow()
    // 本实例各目录最后一次已确认写成的清单（id 与版本号），防止服务端列表可见性滞后时回退到空/旧基线
    private val lastCommittedByFolder = MutableStateFlow<Map<String, Pair<String, Int>>>(emptyMap())
    private val cacheLoaded = CompletableDeferred<Unit>()
    private val cacheRevision = MutableStateFlow(0L)
    private val reading = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val retired = MutableStateFlow<Set<String>>(emptySet())
    private data class Lifetime(val closing: Boolean = false, val operations: Int = 0)
    private val lifetime = MutableStateFlow(Lifetime())
    private val closeResult = CompletableDeferred<Unit>()
    private val storeJob = SupervisorJob(scope?.coroutineContext?.get(Job))
    private val storeScope = CoroutineScope((scope?.coroutineContext ?: Dispatchers.Default) + storeJob)
    private val cacheWriter = if (cacheStore != null && !account.isNullOrEmpty() && scope != null) {
        SnapshotWriter(
            scope = storeScope,
            accountGeneration = 0,
            source = SnapshotSource {
                VersionedSnapshot(PersistVersion(0, cacheRevision.value), CacheSnapshot(parsed.value, lastCommittedByFolder.value))
            },
            debounceMillis = saveDelayMillis,
        ) { snapshot ->
            cacheStore.writeStream(cacheKeyOf(account)) { sink ->
                cacheStore.jsonCodec?.encode(CacheSerializer(), snapshot, sink)
                    ?: sink.writeString(JSON.encodeToString(CacheSerializer(), snapshot))
            }
        }
    } else null

    init {
        if (cacheStore != null && !account.isNullOrEmpty() && scope != null) {
            val loading = storeScope.launch {
                try {
                    predecessor?.join()
                    val stored = runSuspendCatching {
                        cacheStore.readStream(cacheKeyOf(account)) {
                            cacheStore.jsonCodec?.decode(CacheSerializer(), it)
                                ?: JSON.decodeFromString(CacheSerializer(), it.readString())
                        }
                    }.getOrNull()
                    var migratedLegacyFormat = false
                    val storedParsed = stored?.parsed ?: runSuspendCatching {
                        cacheStore.readStream(cacheKeyOf(account)) { cacheStore.jsonCodec?.decode(PARSED_SERIALIZER, it)
                            ?: JSON.decodeFromString(PARSED_SERIALIZER, it.readString()) }
                    }.getOrNull()?.also { if (it.isNotEmpty()) migratedLegacyFormat = true }
                    if (!storedParsed.isNullOrEmpty()) {
                        parsed.update { storedParsed + it }
                        trustedPendingIds.update { it + storedParsed.keys }
                    }
                    val storedCommitted = stored?.committed ?: runSuspendCatching {
                        cacheStore.readStream(committedCacheKeyOf(account)) {
                            (cacheStore.jsonCodec?.decode(COMMITTED_PAIRS, it)
                                ?: JSON.decodeFromString(COMMITTED_PAIRS, it.readString()))
                        }
                    }.getOrNull()?.also { if (it.isNotEmpty()) migratedLegacyFormat = true }
                    if (!storedCommitted.isNullOrEmpty()) lastCommittedByFolder.update { storedCommitted + it }
                    // 旧版 v1 缓存首次回退读取成功后排期升级为 v2 单文件格式，避免后续每次冷启动先试读失败再二次解析
                    if (migratedLegacyFormat) scheduleSave()
                } finally {
                    cacheLoaded.complete(Unit)
                }
            }
            // A cancelled parent can prevent the launch body from ever entering its finally.
            loading.invokeOnCompletion { cacheLoaded.complete(Unit) }
        } else {
            cacheLoaded.complete(Unit)
        }
    }

    /** 唯一缓存加载入口；调用方等待后复用 parsedFlow，不能重复解码。 */
    suspend fun awaitLoaded() = cacheLoaded.await()

    /**
     * Synchronously reject new operations, drain accepted work, flush, then terminate owned jobs.
     * Pass the returned Deferred as the next same-key store's predecessor. Failure is observable
     * by await(), while join() remains a termination barrier. Closing is idempotent.
     */
    fun close(): Deferred<Unit> {
        while (true) {
            val before = lifetime.value
            if (before.closing) return closeResult
            if (lifetime.compareAndSet(before, before.copy(closing = true))) break
        }
        CoroutineScope(storeScope.coroutineContext + NonCancellable).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                lifetime.first { it.operations == 0 }
                awaitLoaded()
                cacheWriter?.close(PersistVersion(0, cacheRevision.value))
            } catch (failure: Throwable) {
                storeJob.cancel()
                storeJob.join()
                closeResult.completeExceptionally(failure)
                return@launch
            }
            storeJob.cancel()
            storeJob.join()
            closeResult.complete(Unit)
        }
        return closeResult
    }

    private suspend fun <T> operation(block: suspend () -> T): T {
        while (true) {
            val before = lifetime.value
            check(!before.closing) { "Vault store is closed" }
            if (lifetime.compareAndSet(before, before.copy(operations = before.operations + 1))) break
        }
        return try { block() } finally { lifetime.update { it.copy(operations = it.operations - 1) } }
    }

    internal fun hasPending(folderId: String): Boolean = folderId in pending.value

    private fun scheduleSave(): PersistVersion {
        val revision = cacheRevision.updateAndGet { it + 1 }
        return PersistVersion(0, revision).also { cacheWriter?.markDirty(it) }
    }

    private suspend fun rememberParsed(id: String, entries: List<VaultEntry>, awaitPersist: Boolean = false) {
        parsed.update { current -> current + (id to entries) }
        val version = scheduleSave()
        if (awaitPersist) cacheWriter?.flushThrough(version)
        trustedPendingIds.update { it + id }
    }

    private suspend fun recordCommittedBaseline(folderId: String, manifestId: String, version: Int) {
        lastCommittedByFolder.update { current ->
            val prev = current[folderId]
            if (prev != null && prev.second > version) current else current + (folderId to (manifestId to version))
        }
        cacheWriter?.flushThrough(scheduleSave())
    }

    private fun lookupParsed(file: FileStat): List<VaultEntry>? = parsed.value[file.id]

    private fun activeWinner(listing: Collection<FileStat>): Candidate? = winner(listing, trustedPendingIds.value)

    /** 挑出目录列表中对本实例生效的归档清单文件（含本机已完整上传并缓存条目的在途转正清单）。 */
    fun winnerOf(listing: Collection<FileStat>): FileStat? = activeWinner(listing)?.file

    /**
     * [folderId] 的条目，[listing] 是它的目录列表。有正在写的改动时是改动之后的样子。
     * 读不出来就失败，不当作空。
     */
    suspend fun read(folderId: String, listing: List<FileStat>): Result<List<VaultEntry>> = operation {
        runSuspendCatching {
            pendingEntries(folderId)?.let { return@runSuspendCatching it }
            // 目录中既无在途乐观改动也无任何 .piko-vault-* 清单文件时，胜出清单必为空，无需阻塞首屏等待全账号缓存加载完毕
            if (listing.none(::looksLikeManifest)) {
                known.update { it + (folderId to emptyList()) }
                return@runSuspendCatching emptyList()
            }
            cacheLoaded.await()
            pendingEntries(folderId)?.let { return@runSuspendCatching it }
            val entries = activeWinner(listing)?.let { entriesOf(it.file) }.orEmpty()
            known.update { it + (folderId to entries) }
            entries
        }
    }

    /** 核对业务结果时只读当前胜出的清单，不把其他待写操作的乐观投影当作已提交结果。 */
    suspend fun committed(folderId: String): Result<List<VaultEntry>> = operation {
        runSuspendCatching {
            cacheLoaded.await()
            activeWinner(io.list(folderId))?.let { entriesOf(it.file) }.orEmpty()
        }
    }

    /** 只用缓存的 [read]：算数的那份没读过时返回 null。 */
    fun cached(folderId: String, listing: List<FileStat>): List<VaultEntry>? {
        pendingEntries(folderId)?.let { return it }
        return cachedCommitted(listing)
    }

    /** 只查已提交清单的缓存（不读 [pending]）：目录中无清单时返回空列表，清单未缓存时返回 null。 */
    fun cachedCommitted(listing: Collection<FileStat>): List<VaultEntry>? {
        val file = activeWinner(listing)?.file ?: return emptyList()
        return lookupParsed(file)
    }

    /** 建索引流水线预热单份清单文件：已缓存则立即返回，未缓存则拉取解析并写入缓存。 */
    suspend fun prefetchManifest(file: FileStat): Result<List<VaultEntry>> = operation {
        runSuspendCatching {
            cacheLoaded.await()
            lookupParsed(file) ?: entriesOf(file)
        }
    }

    /** 查找已知清单中含 [entryId] 的文件夹 ID。 */
    fun folderOfEntry(entryId: String): String? {
        val p = pending.value
        val k = known.value
        return (p.keys + k.keys).firstOrNull { folderId ->
            (pendingEntries(folderId) ?: k[folderId].orEmpty()).any { it.id == entryId }
        }
    }

    /** 从本机已缓存的清单条目中按内容哈希与字节数零网络查找已知媒体与校验元数据（含取样 CID、时长、分辨率与 MIME）。 */
    fun knownMetaOf(gcid: String, size: Long): VaultMediaMeta? {
        if (gcid.isBlank()) return null
        var merged = VaultMediaMeta()
        fun absorb(entriesMap: Map<String, List<VaultEntry>>): Boolean {
            for (entries in entriesMap.values) {
                for (entry in entries) {
                    if (entry.size == size && entry.gcid.equals(gcid, ignoreCase = true)) {
                        merged = merged.mergeWith(VaultMediaMeta.fromVaultEntry(entry))
                        if (merged.cid != null && merged.duration != null && merged.width != null &&
                            merged.height != null && merged.mimeType != null
                        ) {
                            return true
                        }
                    }
                }
            }
            return false
        }
        if (!absorb(known.value)) absorb(parsed.value)
        return merged.takeUnless { it.isEmpty }
    }

    /** 从本机已缓存的清单条目中按内容哈希与字节数查找已知的非空取样 CID，命中时归档免去重复请求 CDN。 */
    fun knownCidOf(gcid: String, size: Long): String? = knownMetaOf(gcid, size)?.cid

    /**
     * 把 [edit] 写进 [folderId] 的清单。现列目录再读，不用调用方手上的列表：它可能已经过时。
     * [freshlyCreatedFolder] 为真时表示该目录刚由本次事务新建（全球尚无其他设备持有其 ID），可跳过空目录基线查询与跨端竞争等待。
     * [requireRemoteComplete] 为真时（例如永久删除原文件的破坏性归档），要求新清单在服务端目录列表中已翻转为 `COMPLETE` 才返回成功。
     * 改完与原来相同就不写。
     */
    suspend fun update(
        folderId: String,
        edit: VaultEdit,
        freshlyCreatedFolder: Boolean = false,
        requireRemoteComplete: Boolean = false,
    ): Result<VaultWrite> = operation {
        val own = PendingEdit(edit)
        var predecessors = emptyList<PendingEdit>()
        pending.update {
            predecessors = it[folderId].orEmpty()
            it + (folderId to (predecessors + own))
        }
        fun removeOwnProjection() {
            pending.update { all ->
                val remaining = all[folderId].orEmpty() - own
                if (remaining.isEmpty()) all - folderId else all + (folderId to remaining)
            }
        }
        var committed = false
        try {
            if (pendingEntries(folderId) != null) onChanged(folderId)
            predecessors.forEach { it.done.await() }
            cacheLoaded.await()
            var attempted: VaultWrite? = null
            var committedSnapshot: VaultCommitSnapshot? = null
            val result = write(
                folderId = folderId,
                edit = edit,
                freshlyCreatedFolder = freshlyCreatedFolder && !requireRemoteComplete,
                requireRemoteComplete = requireRemoteComplete,
                onAttempt = { attempted = it },
                onListingConfirmed = { committedSnapshot = it },
            )
            val outcome = result.exceptionOrNull()?.let { error ->
                attempted?.let { Result.failure<VaultWrite>(VaultWriteUnconfirmed(it, error)) }
            } ?: result
            outcome.onSuccess { write ->
                known.update { it + (folderId to write.after) }
                removeOwnProjection()
                onCommitted(folderId, write.after, committedSnapshot)
                onWritten(folderId, write.after)
                evictRetired()
                committed = true
            }
        } finally {
            removeOwnProjection()
            own.done.complete(Unit)
            if (!committed || hasPending(folderId)) onChanged(folderId)
        }
    }

    private sealed interface ConfirmOutcome {
        class Won(val listing: List<FileStat>, val listedAtSerial: Long?) : ConfirmOutcome
        class Lost(val winner: Candidate) : ConfirmOutcome
        class Unconfirmed(val ownPhase: String?) : ConfirmOutcome
    }

    private suspend fun listWithSerial(folderId: String): Pair<List<FileStat>, Long?> {
        val serial = currentFolderSerial(folderId)
        return io.list(folderId) to serial
    }

    private suspend fun write(
        folderId: String,
        edit: VaultEdit,
        freshlyCreatedFolder: Boolean,
        requireRemoteComplete: Boolean,
        onAttempt: (VaultWrite) -> Unit,
        onListingConfirmed: (VaultCommitSnapshot?) -> Unit,
    ): Result<VaultWrite> =
        lockOf(folderId).withLock {
            runSuspendCatching {
                repeat(MAX_ATTEMPTS) { attempt ->
                    val useFastNewFolderPath = freshlyCreatedFolder && attempt == 0
                    var baseSerial: Long? = null
                    var listing = if (useFastNewFolderPath) {
                        emptyList()
                    } else {
                        val (fetched, serial) = listWithSerial(folderId)
                        baseSerial = serial
                        fetched
                    }
                    if (!useFastNewFolderPath) {
                        val expectedCommitted = lastCommittedByFolder.value[folderId]
                        fun satisfiesCommittedLineage(cand: Candidate?): Boolean {
                            if (expectedCommitted == null) return true
                            val baseVer = cand?.version ?: 0
                            if (baseVer < expectedCommitted.second) return false
                            if (baseVer == expectedCommitted.second && cand?.file?.id != expectedCommitted.first) return false
                            return true
                        }
                        fun baselineReady(files: Collection<FileStat>): Boolean {
                            val cachedIds = if (requireRemoteComplete) emptySet() else trustedPendingIds.value
                            val cand = winner(files, cachedIds)
                            val baseVer = cand?.version ?: 0
                            if (!satisfiesCommittedLineage(cand)) return false
                            if (hasPendingStrangerAbove(files, baseVer, cachedIds)) return false
                            return true
                        }
                        if (!baselineReady(listing)) {
                            val retries = retrySteps(requireRemoteComplete)
                            for (wait in retries) {
                                delay(wait)
                                val (refetched, serial) = listWithSerial(folderId)
                                listing = refetched
                                baseSerial = serial
                                if (baselineReady(listing)) break
                            }
                            val cachedIds = if (requireRemoteComplete) emptySet() else trustedPendingIds.value
                            val cand = winner(listing, cachedIds)
                            val baseVer = cand?.version ?: 0
                            if (!satisfiesCommittedLineage(cand)) {
                                error("上一份已提交清单尚未在目录列表就绪（expected=v${expectedCommitted?.second}，id=${expectedCommitted?.first}），拒绝回退至旧基线")
                            }
                            if (hasPendingStrangerAbove(listing, baseVer, cachedIds)) {
                                error("目录中存在其他设备尚未转正的新版清单，拒绝从旧基线覆盖")
                            }
                        }
                    }
                    val base = if (requireRemoteComplete) winner(listing) else activeWinner(listing)
                    val before = base?.let { entriesOf(it.file) }.orEmpty()
                    val after = edit(before)
                    if (after == before) {
                        if (!useFastNewFolderPath) onListingConfirmed(VaultCommitSnapshot.FullListing(listing, baseSerial))
                        return@runSuspendCatching VaultWrite(before, after)
                    }

                    val version = (base?.version ?: 0) + 1
                    val token = newToken()
                    val text = JSON.encodeToString(Manifest.serializer(), Manifest(after))
                    val manifestBytes = text.encodeToByteArray()
                    // 上传完成即内容已按 gcid 核对过，不读回：刚传完的文件常常一时还没有直链，读回反倒失败。
                    // 自己写的这份直接记进缓存并等待落盘，每次只写这唯一一份清单文件。
                    onAttempt(VaultWrite(before, after))
                    val manifestName = nameOf(version, token)
                    val id = io.upload(folderId, manifestName, manifestBytes)
                    rememberParsed(id, after, awaitPersist = true)

                    if (useFastNewFolderPath) {
                        recordCommittedBaseline(folderId, id, version)
                        val syntheticManifest = FileStat(
                            kind = FileKind.FILE,
                            id = id,
                            parentId = folderId,
                            name = manifestName,
                            size = manifestBytes.size.toString(),
                            phase = TaskPhase.COMPLETE,
                        )
                        onListingConfirmed(VaultCommitSnapshot.IncrementalFiles(listOf(syntheticManifest)))
                        PikoLog.i(TAG, "写成归档清单：新建文件夹 $folderId，v$version，条目 0 → ${after.size}")
                        return@runSuspendCatching VaultWrite(before, after)
                    }

                    val firstCheck = confirmWin(folderId, id, version, token, afterDelay = false, requireRemoteComplete = requireRemoteComplete)
                    val secondCheck = if (firstCheck is ConfirmOutcome.Won) {
                        confirmWin(folderId, id, version, token, afterDelay = true, requireRemoteComplete = requireRemoteComplete)
                    } else firstCheck

                    when (secondCheck) {
                        is ConfirmOutcome.Won -> {
                            recordCommittedBaseline(folderId, id, version)
                            val confirmedListing = secondCheck.listing
                            val ownComplete = confirmedListing.any { it.id == id && it.phase == TaskPhase.COMPLETE }
                            // 新清单尚处服务端异步转正窗口（PENDING）时，暂留旧版 COMPLETE 清单供其他设备兜底读取
                            val stale = if (ownComplete) {
                                confirmedListing.filter { file ->
                                    looksLikeManifest(file) && file.phase == TaskPhase.COMPLETE &&
                                        file.id != id && file.id !in reading.value &&
                                        (versionOf(file)?.let { it.first < version } ?: false)
                                }
                            } else emptyList()
                            val staleIds = stale.mapTo(HashSet()) { it.id }
                            val cleaned = if (stale.isEmpty()) 0 else runSuspendCatching {
                                io.delete(stale.map { it.id })
                                stale.size
                            }.logFailure(TAG, "清单已可信写成，旧版本清理留待下次更新").getOrNull()
                            val finalListing = if (cleaned != null && staleIds.isNotEmpty()) {
                                retired.update { it + staleIds }
                                confirmedListing.filterNot { it.id in staleIds }
                            } else confirmedListing
                            onListingConfirmed(VaultCommitSnapshot.FullListing(finalListing, secondCheck.listedAtSerial))
                            val cleanNote = when {
                                !ownComplete -> "云端转正中，暂留旧清单兜底"
                                cleaned != null -> "清掉旧清单 $cleaned 份"
                                else -> "旧版本清理未完成"
                            }
                            PikoLog.i(TAG, "写成归档清单：文件夹 $folderId，v$version，条目 ${before.size} → ${after.size}，" +
                                "第 ${attempt + 1} 次尝试，$cleanNote")
                            return@runSuspendCatching VaultWrite(before, after)
                        }
                        is ConfirmOutcome.Lost -> {
                            PikoLog.w(
                                TAG,
                                "归档清单写入冲突：文件夹 $folderId，v$version-$token 输给 v${secondCheck.winner.version}-${secondCheck.winner.token}，" +
                                    "第 ${attempt + 1}/$MAX_ATTEMPTS 次，重读重写",
                            )
                            io.delete(listOf(id))
                        }
                        is ConfirmOutcome.Unconfirmed -> {
                            error("清单上传后状态未确认（phase=${secondCheck.ownPhase ?: "不可见"}），保留已传清单避免删己重传")
                        }
                    }
                }
                error("清单写入冲突，重试 $MAX_ATTEMPTS 次仍未写成")
            }.logFailure(TAG, "写归档清单失败：文件夹 $folderId")
        }

    /**
     * 核对 [version]-[token] 是否胜出。
     * OSS 直传完成后服务端把 phase 从 PENDING 翻转为 COMPLETE 可能异步滞后数秒至一分钟，但本机上传成功后的清单已在 [parsed] 中持有完整条目。
     * 若目录中已出现自身清单且无任何优胜竞争者（含陌生的更高优先级 PENDING 清单），无需死等服务端转正即可确认胜出；
     * 若存在陌生优胜 PENDING 或自身清单尚未出现在目录列表中，则退避重列，耗尽后诚实返回 [ConfirmOutcome.Unconfirmed]。
     */
    private suspend fun confirmWin(
        folderId: String,
        uploadedId: String,
        version: Int,
        token: String,
        afterDelay: Boolean,
        requireRemoteComplete: Boolean,
    ): ConfirmOutcome {
        if (afterDelay && confirmDelay > Duration.ZERO) delay(confirmDelay)
        val cachedIds = if (requireRemoteComplete) emptySet() else trustedPendingIds.value
        // 任何同版本或更高版本的陌生非 COMPLETE 清单一律视为未决跨端竞争者（不凭本机时钟或年龄跳过），必须等其转正裁决，禁止提前判赢
        fun hasCompetingPendingStranger(files: Collection<FileStat>): Boolean =
            files.any { file ->
                !file.isFolder && !file.trashed && file.id != uploadedId && file.id !in cachedIds &&
                    file.phase != TaskPhase.COMPLETE &&
                    versionOf(file)?.let { (v, _) -> v >= version } == true
            }
        var (listing, listedAtSerial) = listWithSerial(folderId)
        var rivals = listing.mapNotNull { candidateOf(it, cachedIds) }.filter { it.version >= version }
        val retries = retrySteps(requireRemoteComplete)
        for (wait in retries) {
            val settled = rivals.any { it.version > version || (it.version == version && it.token <= token) } &&
                !hasCompetingPendingStranger(listing)
            if (settled) break
            delay(wait)
            val refetched = listWithSerial(folderId)
            listing = refetched.first
            listedAtSerial = refetched.second
            rivals = listing.mapNotNull { candidateOf(it, cachedIds) }.filter { it.version >= version }
        }
        val ownEntry = listing.firstOrNull { it.id == uploadedId || (it.name == nameOf(version, token) && !it.trashed) }
        if (hasCompetingPendingStranger(listing)) {
            return ConfirmOutcome.Unconfirmed(ownEntry?.phase)
        }
        val best = rivals.minWithOrNull(ORDER)
        if (best != null) {
            return if (best.version == version && best.token == token) {
                ConfirmOutcome.Won(listing, listedAtSerial)
            } else {
                ConfirmOutcome.Lost(best)
            }
        }
        return ConfirmOutcome.Unconfirmed(ownEntry?.phase)
    }

    private suspend fun entriesOf(file: FileStat): List<VaultEntry> {
        reading.update { it + (file.id to ((it[file.id] ?: 0) + 1)) }
        try {
            lookupParsed(file)?.let { return it }
            val entries = JSON.decodeFromString(Manifest.serializer(), io.read(file.id).decodeToString()).entries
            rememberParsed(file.id, entries)
            return entries
        } finally {
            reading.update { current ->
                val remaining = (current[file.id] ?: 1) - 1
                if (remaining == 0) current - file.id else current + (file.id to remaining)
            }
        }
    }

    // 只回收已被本次已确认 COMPLETE 胜者替代且已从远端清掉的历史清单。
    // 未转正自产清单、当前 committed 基线与正在读取的清单永远不按容量淘汰。
    private fun evictRetired() {
        val protected = lastCommittedByFolder.value.values.mapTo(HashSet()) { it.first } + reading.value.keys
        val removable = retired.value - protected
        if (removable.isEmpty()) return
        parsed.update { it - removable }
        trustedPendingIds.update { it - removable }
        retired.update { it - removable }
        scheduleSave()
    }

    private fun lockOf(folderId: String): Mutex = locks[folderId.hashCode() and 63]

    private fun retrySteps(requireRemoteComplete: Boolean): List<Long> {
        val base = if (requireRemoteComplete) REMOTE_COMPLETE_RETRY_MILLIS else VISIBILITY_RETRY_MILLIS
        return if (confirmDelay == Duration.ZERO) List(base.size) { 0L } else base
    }

    @Serializable
    private class Manifest(val entries: List<VaultEntry>)

    @Serializable
    private data class StoredCommitted(val id: String, val version: Int)

    private class CacheSnapshot(
        val parsed: Map<String, List<VaultEntry>>,
        val committed: Map<String, Pair<String, Int>> = emptyMap(),
    )

    /** Only the unique source table is auxiliary; maps/lists decoded here are the final cache. */
    private class CacheSerializer : KSerializer<CacheSnapshot> {
        private val strings = ListSerializer(String.serializer())
        override val descriptor = buildClassSerialDescriptor("VaultCache") {
            element<Int>("version")
            element("sources", strings.descriptor)
            element("manifests", PARSED_SERIALIZER.descriptor)
            element("committed", COMMITTED_SERIALIZER.descriptor, isOptional = true)
        }

        override fun serialize(encoder: Encoder, value: CacheSnapshot) {
            val refs = LinkedHashMap<String, Int>()
            for (entries in value.parsed.values) for (entry in entries) {
                entry.source?.let { refs.getOrPut(it) { refs.size } }
            }
            encoder.encodeStructure(descriptor) {
                encodeIntElement(descriptor, 0, 2)
                encodeSerializableElement(descriptor, 1, strings, refs.keys.toList())
                encodeSerializableElement(descriptor, 2,
                    MapSerializer(String.serializer(), ListSerializer(RowSerializer(refs = refs))), value.parsed)
                encodeSerializableElement(descriptor, 3, COMMITTED_PAIRS, value.committed)
            }
        }

        override fun deserialize(decoder: Decoder): CacheSnapshot {
            var version = -1
            var sources: List<String>? = null
            var unresolved = false
            val row = RowSerializer(resolve = { ref ->
                val table = sources
                if (table == null) {
                    unresolved = true
                    // A temporary reference lives in the final entry slot, never a parallel DTO graph.
                    "\u0000$ref"
                } else {
                    require(ref in table.indices) { "Invalid vault source reference" }
                    table[ref]
                }
            })
            val entries = object : KSerializer<MutableList<VaultEntry>> {
                override val descriptor = ListSerializer(row).descriptor
                override fun serialize(encoder: Encoder, value: MutableList<VaultEntry>) =
                    ListSerializer(row).serialize(encoder, value)
                override fun deserialize(decoder: Decoder): MutableList<VaultEntry> {
                    val result = ArrayList<VaultEntry>()
                    decoder.decodeStructure(descriptor) {
                        while (true) {
                            val index = decodeElementIndex(descriptor)
                            if (index == CompositeDecoder.DECODE_DONE) break
                            require(index == result.size)
                            result.add(decodeSerializableElement(descriptor, index, row))
                        }
                    }
                    return result
                }
            }
            var manifests: Map<String, MutableList<VaultEntry>>? = null
            var committed: Map<String, Pair<String, Int>> = emptyMap()
            decoder.decodeStructure(descriptor) {
                while (true) when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> version = decodeIntElement(descriptor, index)
                    1 -> sources = decodeSerializableElement(descriptor, index, strings)
                    2 -> manifests = decodeSerializableElement(descriptor, index, MapSerializer(String.serializer(), entries))
                    3 -> committed = decodeSerializableElement(descriptor, index, COMMITTED_PAIRS)
                    else -> error("Unknown vault cache field")
                }
            }
            require(version == 2) { "Unsupported vault cache schema" }
            val table = requireNotNull(sources) { "Missing vault source table" }
            val parsed = requireNotNull(manifests) { "Missing vault manifests" }
            if (unresolved) for (list in parsed.values) for (index in list.indices) {
                val entry = list[index]
                val source = entry.source ?: continue
                if (!source.startsWith('\u0000')) continue
                val ref = source.substring(1).toInt()
                require(ref in table.indices) { "Invalid vault source reference" }
                list[index] = entry.copy(source = table[ref])
            }
            return CacheSnapshot(parsed, committed)
        }
    }

    /** A row exists only on the encoder/decoder stack, not in a stored row collection. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private class RowSerializer(
        private val refs: Map<String, Int> = emptyMap(),
        private val resolve: (Int) -> String = { error("No source resolver") },
    ) : KSerializer<VaultEntry> {
        override val descriptor = buildClassSerialDescriptor("VaultCacheRow") {
            element("entry", VaultEntry.serializer().descriptor)
            element<Int>("source", isOptional = true)
        }
        override fun serialize(encoder: Encoder, value: VaultEntry) = encoder.encodeStructure(descriptor) {
            encodeSerializableElement(descriptor, 0, EntryWithoutSourceSerializer, value)
            value.source?.let { encodeIntElement(descriptor, 1, refs.getValue(it)) }
        }
        override fun deserialize(decoder: Decoder): VaultEntry {
            var entry: VaultEntry? = null
            var ref: Int? = null
            decoder.decodeStructure(descriptor) {
                while (true) when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> entry = decodeSerializableElement(descriptor, index, VaultEntry.serializer())
                    1 -> ref = decodeNullableSerializableElement(descriptor, index, Int.serializer())
                    else -> error("Unknown vault cache row field")
                }
            }
            val result = requireNotNull(entry) { "Missing vault entry" }
            return ref?.let { result.copy(source = resolve(it)) } ?: result
        }
    }

    /** Stream the original entry while its source is encoded only as the row's table reference. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private object EntryWithoutSourceSerializer : KSerializer<VaultEntry> by VaultEntry.serializer() {
        private val sourceIndex = descriptor.getElementIndex("source")

        override fun serialize(encoder: Encoder, value: VaultEntry) {
            VaultEntry.serializer().serialize(object : Encoder by encoder {
                override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
                    val composite = encoder.beginStructure(descriptor)
                    return object : CompositeEncoder by composite {
                        override fun <T : Any> encodeNullableSerializableElement(
                            descriptor: SerialDescriptor,
                            index: Int,
                            serializer: kotlinx.serialization.SerializationStrategy<T>,
                            value: T?,
                        ) {
                            if (index != sourceIndex) {
                                composite.encodeNullableSerializableElement(descriptor, index, serializer, value)
                            }
                        }
                    }
                }
            }, value)
        }
    }

    private class Candidate(val file: FileStat, val version: Int, val token: String)

    companion object {
        private const val TAG = "Vault"
        private const val PREFIX = ".piko-vault-"
        private const val SUFFIX = ".json"
        private const val MAX_ATTEMPTS = 5
        private val VISIBILITY_RETRY_MILLIS = listOf(100L, 200L, 400L, 800L, 1600L, 3200L)
        private val REMOTE_COMPLETE_RETRY_MILLIS = VISIBILITY_RETRY_MILLIS + List(17) { 5_000L }
        private val JSON = Json { ignoreUnknownKeys = true }
        private val NAME = Regex("""\.piko-vault-v([0-9]+)-([0-9a-f]+)\.json""")
        private val UNSAFE_KEY_CHARS = Regex("""[^A-Za-z0-9._@-]""")
        private val PARSED_SERIALIZER = MapSerializer(String.serializer(), ListSerializer(VaultEntry.serializer()))
        private val COMMITTED_SERIALIZER = MapSerializer(String.serializer(), StoredCommitted.serializer())
        private val COMMITTED_PAIRS = MapSerializer(String.serializer(), object : KSerializer<Pair<String, Int>> {
            override val descriptor = StoredCommitted.serializer().descriptor
            override fun serialize(encoder: Encoder, value: Pair<String, Int>) =
                StoredCommitted.serializer().serialize(encoder, StoredCommitted(value.first, value.second))
            override fun deserialize(decoder: Decoder): Pair<String, Int> =
                StoredCommitted.serializer().deserialize(decoder).let { it.id to it.version }
        })

        // 版本大的在前，同版本随机串小的在前：排第一的算数
        private val ORDER = compareByDescending<Candidate> { it.version }.thenBy { it.token }

        private fun nameOf(version: Int, token: String) = "${PREFIX}v$version-$token$SUFFIX"

        private fun versionOf(file: FileStat): Pair<Int, String>? =
            NAME.matchEntire(file.name)?.let { match ->
                val v = match.groupValues[1].toIntOrNull() ?: return@let null
                v to match.groupValues[2]
            }

        private fun hasFullRandomToken(name: String): Boolean =
            NAME.matchEntire(name)?.groupValues?.get(2)?.length == 16

        fun cacheKeyOf(account: String): String =
            "vault-manifests-" + account.replace(UNSAFE_KEY_CHARS, "_") + ".json"

        fun committedCacheKeyOf(account: String): String =
            "vault-committed-" + account.replace(UNSAFE_KEY_CHARS, "_") + ".json"

        private val SOURCE_NAME = Regex("""归档来源-[0-9a-f]{16}\.(magnet|txt)""")
        /** 历史版本曾写入同目录的来源旁路文件，现已不再生成，列表里仍过滤隐藏。 */
        fun isRecoveryFile(file: FileStat): Boolean = !file.isFolder && SOURCE_NAME.matches(file.name)

        private fun hasPendingStrangerAbove(
            files: Collection<FileStat>,
            baseVersion: Int,
            locallyCachedIds: Set<String>,
        ): Boolean = files.any { file ->
            !file.isFolder && !file.trashed && file.id !in locallyCachedIds &&
                file.phase != TaskPhase.COMPLETE &&
                (versionOf(file)?.first ?: 0) > baseVersion
        }

        /**
         * 能算数的清单：服务端已 COMPLETE，或属于本机已完整上传并持久缓存解析条目的在途转正清单（[locallyCachedIds]）。
         * 上传中途进程被杀留下的陌生 PENDING 空壳不在 [locallyCachedIds] 中，仍跳过不算。
         */
        private fun candidateOf(file: FileStat, locallyCachedIds: Set<String> = emptySet()): Candidate? {
            if (file.isFolder || file.trashed) return null
            if (file.phase != TaskPhase.COMPLETE && !(file.phase == TaskPhase.PENDING && file.id in locallyCachedIds)) return null
            val (version, token) = versionOf(file) ?: return null
            return Candidate(file, version, token)
        }

        private fun winner(listing: Collection<FileStat>, locallyCachedIds: Set<String> = emptySet()): Candidate? =
            listing.mapNotNull { candidateOf(it, locallyCachedIds) }.minWithOrNull(ORDER)

        /** 挑出目录列表中当前胜出的归档清单文件。 */
        fun winnerOf(listing: Collection<FileStat>, locallyCachedIds: Set<String> = emptySet()): FileStat? =
            winner(listing, locallyCachedIds)?.file

        /** 清单文件，含没写完的空壳与输掉的那几份。列表里一概不显示。 */
        fun looksLikeManifest(file: FileStat): Boolean =
            !file.isFolder && file.name.startsWith(PREFIX) && file.name.endsWith(SUFFIX)
    }
}

private fun randomToken(): String = Random.nextBytes(8).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
