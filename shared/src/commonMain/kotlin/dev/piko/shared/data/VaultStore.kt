package dev.piko.shared.data

import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.logFolder
import kotlin.time.Clock
import kotlinx.serialization.SerializationException
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
    /** 最近一次修改（改名、取回关联变更）时刻；旧清单为空时回退到 [addedAt]。 */
    val updatedAt: Long? = null,
) {
    val isArchived: Boolean get() = restoredFileId == null
    val effectiveUpdatedAt: Long get() = updatedAt ?: addedAt

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
        val nowMs = Clock.System.now().toEpochMilliseconds()
        current.map {
            if (it.id == id && it.name != name) {
                it.copy(name = name, updatedAt = maxOf(it.effectiveUpdatedAt + 1L, nowMs))
            } else {
                it
            }
        }
    }

    fun renameAll(renames: Map<String, String>): VaultEdit = { current ->
        if (renames.isEmpty()) current
        else {
            val nowMs = Clock.System.now().toEpochMilliseconds()
            current.map { entry ->
                val targetName = renames[entry.id]
                if (targetName != null && targetName != entry.name) {
                    entry.copy(name = targetName, updatedAt = maxOf(entry.effectiveUpdatedAt + 1L, nowMs))
                } else {
                    entry
                }
            }
        }
    }

    fun remove(ids: Set<String>): VaultEdit = { current -> current.filterNot { it.id in ids } }

    /** 取回后保留来源及 CID，同时跟随真实文件的新名称。 */
    fun restore(files: Map<String, FileStat>): VaultEdit = { current ->
        val nowMs = Clock.System.now().toEpochMilliseconds()
        current.map { entry ->
            files[entry.id]?.let {
                entry.copy(
                    name = it.name,
                    restoredFileId = it.id,
                    updatedAt = maxOf(entry.effectiveUpdatedAt + 1L, nowMs),
                )
            } ?: entry
        }
    }
}

/** 一次改写前后的条目，撤销要用改之前的。 */
class VaultWrite(
    val before: List<VaultEntry>,
    val after: List<VaultEntry>,
    val ledgerRevertOps: List<VaultOp> = emptyList(),
    val restarEntries: List<VaultEntry> = emptyList(),
)

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
 * 写入路径采用「上传拿到 fileId 即成功 + 读时自动合并收敛」：
 * - 读到当前最高版本 vN（含同版本多分支合并与本机已提交基线），计算新条目后直接上传 v(N+1)-<随机串>；
 * - 只要上传成功拿到文件 ID，即持久化本机缓存与目录基线并立即返回成功，不再前台阻塞等待目录列表刷新；
 * - 若两台设备极小概率在同一时刻写出多份同版本清单，读路径与下次写入会自动合并所有同版本分支并在后台收敛为单一新版本，
 *   旧版清单在后台异步清理（始终保留最近 1 份历史 COMPLETE 清单供他端在新清单异步转正窗口内兜底读取）。
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
    private val causalMeta = MutableStateFlow<Map<String, ManifestCausalMeta>>(emptyMap())
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
                VersionedSnapshot(
                    PersistVersion(0, cacheRevision.value),
                    CacheSnapshot(parsed.value, lastCommittedByFolder.value, causalMeta.value),
                )
            },
            debounceMillis = saveDelayMillis,
        ) { snapshot ->
            cacheStore.writeStream(cacheKeyOf(account)) { sink ->
                cacheStore.jsonCodec?.encode(CacheSerializer(), snapshot, sink)
                    ?: sink.writeString(JSON.encodeToString(CacheSerializer(), snapshot))
            }
        }
    } else null

    private suspend fun <T> readCachedStream(
        store: PikoCacheStore,
        key: String,
        decode: (kotlinx.io.Source) -> T,
    ): Result<T>? = runSuspendCatching {
        store.readStream(key) { source ->
            runCatching { decode(source) }
                .onFailure { if (it is kotlin.coroutines.cancellation.CancellationException) throw it }
        }
    }.fold(
        onSuccess = { it },
        onFailure = { Result.failure(it) },
    )

    init {
        if (cacheStore != null && !account.isNullOrEmpty() && scope != null) {
            val loading = storeScope.launch {
                try {
                    predecessor?.join()
                    val slot = logAccountSlot(account)
                    val v2Attempt = readCachedStream(cacheStore, cacheKeyOf(account)) {
                        cacheStore.jsonCodec?.decode(CacheSerializer(), it)
                            ?: JSON.decodeFromString(CacheSerializer(), it.readString())
                    }
                    val stored = v2Attempt?.getOrNull()
                    var migratedLegacyFormat = false
                    val storedParsed = if (v2Attempt == null || v2Attempt.isSuccess) {
                        stored?.parsed
                    } else {
                        readCachedStream(cacheStore, cacheKeyOf(account)) {
                            cacheStore.jsonCodec?.decode(PARSED_SERIALIZER, it)
                                ?: JSON.decodeFromString(PARSED_SERIALIZER, it.readString())
                        }?.onFailure { err ->
                            PikoLog.w(
                                TAG,
                                "Corrupted local vault manifest cache; resetting cached manifests (acct=$slot, error=${err::class.simpleName})",
                                err.takeIf { it !is SerializationException },
                            )
                        }?.getOrNull()?.also { if (it.isNotEmpty()) migratedLegacyFormat = true }
                    }
                    if (!storedParsed.isNullOrEmpty()) {
                        parsed.update { storedParsed + it }
                        trustedPendingIds.update { it + storedParsed.keys }
                    }
                    val storedCommitted = stored?.committed ?: readCachedStream(cacheStore, committedCacheKeyOf(account)) {
                        cacheStore.jsonCodec?.decode(COMMITTED_PAIRS, it)
                            ?: JSON.decodeFromString(COMMITTED_PAIRS, it.readString())
                    }?.onFailure { err ->
                        PikoLog.w(
                            TAG,
                            "Corrupted legacy vault committed cache; ignoring baseline (acct=$slot, error=${err::class.simpleName})",
                            err.takeIf { it !is SerializationException },
                        )
                    }?.getOrNull()?.also { if (it.isNotEmpty()) migratedLegacyFormat = true }
                    if (!storedCommitted.isNullOrEmpty()) lastCommittedByFolder.update { storedCommitted + it }
                    val storedMeta = stored?.meta
                    if (!storedMeta.isNullOrEmpty()) causalMeta.update { storedMeta + it }
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

    private suspend fun rememberParsed(
        id: String,
        entries: List<VaultEntry>,
        meta: ManifestCausalMeta? = null,
        awaitPersist: Boolean = false,
    ) {
        parsed.update { current -> current + (id to entries) }
        if (meta != null && !meta.isEmpty) {
            causalMeta.update { current -> current + (id to meta) }
        }
        val version = scheduleSave()
        if (awaitPersist) cacheWriter?.flushThrough(version)
        trustedPendingIds.update { it + id }
    }

    private suspend fun recordCommittedBaseline(
        folderId: String,
        manifestId: String,
        version: Int,
        grantPendingTrustOnCommit: Boolean = false,
    ) {
        val before = lastCommittedByFolder.value[folderId]
        lastCommittedByFolder.update { current ->
            val prev = current[folderId]
            if (prev != null && prev.second > version) current else current + (folderId to (manifestId to version))
        }
        try {
            cacheWriter?.flushThrough(scheduleSave())
        } catch (failure: Throwable) {
            lastCommittedByFolder.update { current ->
                if (current[folderId] != (manifestId to version)) {
                    current
                } else if (before == null) {
                    current - folderId
                } else {
                    current + (folderId to before)
                }
            }
            throw failure
        }
        if (grantPendingTrustOnCommit) {
            trustedPendingIds.update { it + manifestId }
        }
    }

    private fun lookupParsed(file: FileStat): List<VaultEntry>? = parsed.value[file.id]

    private fun activeWinners(listing: Collection<FileStat>): List<Candidate> =
        topVersionCandidates(listing, trustedPendingIds.value)

    private fun activeWinner(listing: Collection<FileStat>): Candidate? = activeWinners(listing).firstOrNull()

    /** 挑出目录列表中对本实例生效的归档清单文件（含本机已完整上传并缓存条目的在途转正清单）。 */
    fun winnerOf(listing: Collection<FileStat>): FileStat? = activeWinner(listing)?.file

    private class LoadedBranch(
        val id: String,
        val entries: List<VaultEntry>,
        val meta: ManifestCausalMeta,
    )

    private class EffectiveVaultState(
        val entries: List<VaultEntry>,
        val deleted: Map<String, Long>,
        val coveredIds: Set<String>,
        val maxRemoteVersion: Int,
        val branchCount: Int,
        val needsReconcile: Boolean,
    )

    private suspend fun resolveEffectiveState(
        folderId: String?,
        listing: Collection<FileStat>,
        includeLocalBaseline: Boolean,
        trustLocalPending: Boolean = true,
    ): EffectiveVaultState {
        val trustedIds = if (trustLocalPending) trustedPendingIds.value else emptySet()
        val all = allCandidates(listing, trustedIds)
        val maxRemoteVersion = all.firstOrNull()?.version ?: 0
        val topWinners = if (maxRemoteVersion == 0) emptyList() else all.filter { it.version == maxRemoteVersion }
        val committed = if (includeLocalBaseline && folderId != null) lastCommittedByFolder.value[folderId] else null

        if (committed != null && committed.second > maxRemoteVersion) {
            val localEntries = parsed.value[committed.first]
            if (localEntries != null) {
                val localMeta = causalMeta.value[committed.first] ?: ManifestCausalMeta()
                val strictlySupersedes = all.isEmpty() || (
                    localMeta.isCausal && all.all {
                        it.file.id == committed.first || isCoveredManifest(it.file, localMeta.parents.toSet())
                    }
                )
                if (strictlySupersedes) {
                    return EffectiveVaultState(
                        entries = localEntries,
                        deleted = localMeta.deleted,
                        coveredIds = (localMeta.parents + committed.first).toSet(),
                        maxRemoteVersion = maxRemoteVersion,
                        branchCount = 1,
                        needsReconcile = false,
                    )
                }
            }
        }

        val branches = ArrayList<LoadedBranch>()
        val coveredIds = LinkedHashSet<String>()

        if (committed != null && committed.second > maxRemoteVersion) {
            parsed.value[committed.first]?.let { localEntries ->
                val localMeta = causalMeta.value[committed.first] ?: ManifestCausalMeta()
                branches += LoadedBranch(committed.first, localEntries, localMeta)
                coveredIds += committed.first
                coveredIds += localMeta.parents
            }
        }

        for (winner in topWinners) {
            val (entries, meta) = manifestOf(winner.file)
            branches += LoadedBranch(winner.file.id, entries, meta)
            coveredIds += manifestKey(winner.file.id, winner.file.name)
            coveredIds += winner.file.id
            coveredIds += meta.parents
        }

        val hasCausalLineage = branches.any { it.meta.isCausal }
        if (hasCausalLineage) {
            for (cand in all) {
                val candKey = manifestKey(cand.file.id, cand.file.name)
                if (cand.version == maxRemoteVersion || isCoveredManifest(cand.file, coveredIds)) continue
                val (entries, meta) = manifestOf(cand.file)
                if (!sharesCausalOrigin(meta, coveredIds)) continue
                branches += LoadedBranch(cand.file.id, entries, meta)
                coveredIds += candKey
                coveredIds += cand.file.id
                coveredIds += meta.parents
            }
        }

        if (committed != null && committed.first !in coveredIds) {
            parsed.value[committed.first]?.let { localEntries ->
                val localMeta = causalMeta.value[committed.first] ?: ManifestCausalMeta()
                branches.add(0, LoadedBranch(committed.first, localEntries, localMeta))
                coveredIds += committed.first
                coveredIds += localMeta.parents
            }
        }

        if (branches.isEmpty()) {
            return EffectiveVaultState(emptyList(), emptyMap(), coveredIds, maxRemoteVersion, 0, false)
        }
        if (branches.size == 1) {
            val single = branches[0]
            return EffectiveVaultState(single.entries, single.meta.deleted, coveredIds, maxRemoteVersion, 1, false)
        }
        val mergedDeleted = mergeDeletedMaps(branches.map { it.meta.deleted })
        val mergedEntries = mergeBranches(branches.map { it.entries }, mergedDeleted)
        return EffectiveVaultState(
            entries = mergedEntries,
            deleted = mergedDeleted,
            coveredIds = coveredIds,
            maxRemoteVersion = maxRemoteVersion,
            branchCount = branches.size,
            needsReconcile = true,
        )
    }

    private fun resolveCachedState(
        folderId: String?,
        listing: Collection<FileStat>,
        includeLocalBaseline: Boolean,
    ): List<VaultEntry>? {
        val all = allCandidates(listing, trustedPendingIds.value)
        val maxRemoteVersion = all.firstOrNull()?.version ?: 0
        val topWinners = if (maxRemoteVersion == 0) emptyList() else all.filter { it.version == maxRemoteVersion }
        val committed = if (includeLocalBaseline && folderId != null) lastCommittedByFolder.value[folderId] else null

        if (committed != null && committed.second > maxRemoteVersion) {
            val localEntries = parsed.value[committed.first]
            if (localEntries != null) {
                val localMeta = causalMeta.value[committed.first] ?: ManifestCausalMeta()
                val strictlySupersedes = all.isEmpty() || (
                    localMeta.isCausal && all.all {
                        it.file.id == committed.first || isCoveredManifest(it.file, localMeta.parents.toSet())
                    }
                )
                if (strictlySupersedes) return localEntries
            }
        }

        val branches = ArrayList<LoadedBranch>()
        val coveredIds = LinkedHashSet<String>()

        if (committed != null && committed.second > maxRemoteVersion) {
            val localEntries = parsed.value[committed.first] ?: return null
            val localMeta = causalMeta.value[committed.first] ?: ManifestCausalMeta()
            branches += LoadedBranch(committed.first, localEntries, localMeta)
            coveredIds += committed.first
            coveredIds += localMeta.parents
        }

        for (winner in topWinners) {
            val entries = lookupParsed(winner.file) ?: return null
            val meta = causalMeta.value[winner.file.id] ?: ManifestCausalMeta()
            branches += LoadedBranch(winner.file.id, entries, meta)
            coveredIds += manifestKey(winner.file.id, winner.file.name)
            coveredIds += winner.file.id
            coveredIds += meta.parents
        }

        if (branches.any { it.meta.isCausal }) {
            for (cand in all) {
                val candKey = manifestKey(cand.file.id, cand.file.name)
                if (cand.version == maxRemoteVersion || isCoveredManifest(cand.file, coveredIds)) continue
                val entries = lookupParsed(cand.file) ?: return null
                val meta = causalMeta.value[cand.file.id] ?: ManifestCausalMeta()
                if (!sharesCausalOrigin(meta, coveredIds)) continue
                branches += LoadedBranch(cand.file.id, entries, meta)
                coveredIds += candKey
                coveredIds += cand.file.id
                coveredIds += meta.parents
            }
        }

        if (committed != null && committed.first !in coveredIds) {
            val localEntries = parsed.value[committed.first]
            if (localEntries != null) {
                val localMeta = causalMeta.value[committed.first] ?: ManifestCausalMeta()
                branches.add(0, LoadedBranch(committed.first, localEntries, localMeta))
                coveredIds += committed.first
                coveredIds += localMeta.parents
            }
        }

        if (branches.isEmpty()) return emptyList()
        if (branches.size == 1) return branches[0].entries
        val mergedDeleted = mergeDeletedMaps(branches.map { it.meta.deleted })
        return mergeBranches(branches.map { it.entries }, mergedDeleted)
    }

    private fun hasHigherPendingManifest(listing: Collection<FileStat>): Boolean {
        var maxComplete = 0
        var maxPending = 0
        for (file in listing) {
            if (!looksLikeManifest(file) || file.trashed) continue
            val (version, _) = versionOf(file) ?: continue
            if (file.phase == TaskPhase.COMPLETE) {
                if (version > maxComplete) maxComplete = version
            } else if (file.phase == TaskPhase.PENDING) {
                if (version > maxPending) maxPending = version
            }
        }
        return maxPending > maxComplete
    }

    private suspend fun readEffectiveEntries(folderId: String, listing: Collection<FileStat>): List<VaultEntry> {
        val state = resolveEffectiveState(folderId, listing, includeLocalBaseline = true)
        if (state.needsReconcile && !hasHigherPendingManifest(listing)) {
            scheduleReconcile(folderId)
        }
        return state.entries
    }

    /**
     * [folderId] 的条目，[listing] 是它的目录列表。有正在写的改动时是改动之后的样子。
     * 读不出来就失败，不当作空。
     */
    suspend fun read(folderId: String, listing: List<FileStat>): Result<List<VaultEntry>> = operation {
        runSuspendCatching {
            pendingEntries(folderId)?.let { return@runSuspendCatching it }
            // 目录中既无在途乐观改动、无本地已提交基线、也无任何 .piko-vault-* 清单文件时，胜出清单必为空，无需阻塞首屏等待全账号缓存加载完毕
            if (listing.none(::looksLikeManifest) && folderId !in lastCommittedByFolder.value) {
                known.update { it + (folderId to emptyList()) }
                return@runSuspendCatching emptyList()
            }
            cacheLoaded.await()
            pendingEntries(folderId)?.let { return@runSuspendCatching it }
            val entries = readEffectiveEntries(folderId, listing)
            known.update { it + (folderId to entries) }
            entries
        }
    }

    /** 索引回填专用读取：仅读取并合并已提交清单，绝不把正在编辑中的乐观 [pending] 投影写进持久化树索引。 */
    suspend fun readCommitted(listing: Collection<FileStat>): Result<List<VaultEntry>> = operation {
        runSuspendCatching {
            if (listing.none(::looksLikeManifest)) return@runSuspendCatching emptyList()
            cacheLoaded.await()
            resolveEffectiveState(folderId = null, listing = listing, includeLocalBaseline = false).entries
        }
    }

    /** 核对业务结果时只读当前胜出的清单，不把其他待写操作的乐观投影当作已提交结果。 */
    suspend fun committed(folderId: String): Result<List<VaultEntry>> = operation {
        runSuspendCatching {
            cacheLoaded.await()
            readEffectiveEntries(folderId, io.list(folderId))
        }
    }

    /**
     * 永久删除原文件归档前的远端可见屏障：不借助本机 [trustedPendingIds]，
     * 确认远端目录列表中的 `COMPLETE` 清单已完整承载 [requiredEntryIds] 后才放行物理销毁原文件。
     */
    suspend fun awaitRemoteComplete(
        folderId: String,
        requiredEntryIds: Set<String>,
        maxAttempts: Int = 20,
        retryDelayMillis: Long = 350L,
    ): Result<Unit> = operation {
        runSuspendCatching {
            if (requiredEntryIds.isEmpty()) return@runSuspendCatching
            cacheLoaded.await()
            repeat(maxAttempts) { attempt ->
                val remoteListing = io.list(folderId)
                val state = resolveEffectiveState(
                    folderId = null,
                    listing = remoteListing,
                    includeLocalBaseline = false,
                    trustLocalPending = false,
                )
                val remoteIds = state.entries.mapTo(HashSet()) { it.id }
                if (remoteIds.containsAll(requiredEntryIds)) {
                    scheduleConfirmedCleanup(folderId, remoteListing)
                    return@runSuspendCatching
                }
                if (attempt < maxAttempts - 1) {
                    kotlinx.coroutines.delay(retryDelayMillis)
                }
            }
            error("归档清单尚未在服务端完成落盘确认，已中止永久删除原文件")
        }
    }

    /** 只用缓存的 [read]：算数的那份没读过时返回 null。 */
    fun cached(folderId: String, listing: List<FileStat>): List<VaultEntry>? {
        pendingEntries(folderId)?.let { return it }
        return resolveCachedState(folderId = folderId, listing = listing, includeLocalBaseline = true)
    }

    /** 等待本地持久化的归档清单缓存反序列化完成，避免启动初期误判全量未缓存。 */
    suspend fun awaitCacheLoaded() = awaitLoaded()

    /** 只查已提交清单的缓存（不读 [pending]）：目录中无清单时返回空列表，清单未缓存时返回 null。 */
    fun cachedCommitted(listing: Collection<FileStat>): List<VaultEntry>? =
        resolveCachedState(folderId = null, listing = listing, includeLocalBaseline = false)

    /** 建索引流水线预热单份清单文件：已缓存则立即返回，未缓存则拉取解析并写入缓存。 */
    suspend fun prefetchManifest(file: FileStat): Result<List<VaultEntry>> = operation {
        runSuspendCatching {
            cacheLoaded.await()
            lookupParsed(file) ?: manifestOf(file).first
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

    private class MetaIndexCache(
        val knownRef: Map<String, List<VaultEntry>>,
        val parsedRef: Map<String, List<VaultEntry>>,
        val byKey: Map<ContentKey, VaultMediaMeta>,
    )

    @kotlin.concurrent.Volatile
    private var metaIndexCache: MetaIndexCache? = null

    /** 从本机已缓存的清单条目中按内容哈希与字节数零网络查找已知媒体与校验元数据（含取样 CID、时长、分辨率与 MIME）。 */
    fun knownMetaOf(gcid: String, size: Long): VaultMediaMeta? {
        val key = ContentKey.of(gcid, size) ?: return null
        val k = known.value
        val p = parsed.value
        val cached = metaIndexCache
        val index = if (cached != null && cached.knownRef === k && cached.parsedRef === p) {
            cached.byKey
        } else {
            val built = HashMap<ContentKey, VaultMediaMeta>(maxOf(16, (k.size + p.size) * 4))
            fun absorbAll(entriesMap: Map<String, List<VaultEntry>>) {
                for (entries in entriesMap.values) {
                    for (entry in entries) {
                        val entryKey = ContentKey.of(entry.gcid, entry.size) ?: continue
                        val meta = VaultMediaMeta.fromVaultEntry(entry)
                        if (meta.isEmpty) continue
                        val prev = built[entryKey]
                        built[entryKey] = if (prev == null) meta else prev.mergeWith(meta)
                    }
                }
            }
            absorbAll(k)
            absorbAll(p)
            metaIndexCache = MetaIndexCache(k, p, built)
            built
        }
        return index[key]?.takeUnless { it.isEmpty }
    }

    /** 从本机已缓存的清单条目中按内容哈希与字节数查找已知的非空取样 CID，命中时归档免去重复请求 CDN。 */
    fun knownCidOf(gcid: String, size: Long): String? = knownMetaOf(gcid, size)?.cid

    /**
     * 把 [edit] 写进 [folderId] 的清单。现列目录再读，不用调用方手上的列表：它可能已经过时。
     * [freshlyCreatedFolder] 为真时表示该目录刚由本次事务新建（全球尚无其他设备持有其 ID），可跳过空目录基线查询。
     * 只要清单文件上传成功拿到文件 ID 即视为提交成功，旧清单清理在后台异步完成；
     * 上传失败时直接向上返回原始错误，不引入「结果待确认」状态。
     * 改完与原来相同且无多分支需合并时直接返回，不重复上传。
     */
    suspend fun update(
        folderId: String,
        edit: VaultEdit,
        freshlyCreatedFolder: Boolean = false,
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
            var committedSnapshot: VaultCommitSnapshot? = null
            val outcome = write(
                folderId = folderId,
                edit = edit,
                freshlyCreatedFolder = freshlyCreatedFolder,
                onListingConfirmed = { committedSnapshot = it },
            )
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

    private val reconcilingFolders = MutableStateFlow<Set<String>>(emptySet())

    private fun scheduleReconcile(folderId: String) {
        if (lifetime.value.closing) return
        val added = run {
            var inserted = false
            reconcilingFolders.update { current ->
                if (folderId in current) {
                    current
                } else {
                    inserted = true
                    current + folderId
                }
            }
            inserted
        }
        if (!added) return
        storeScope.launch {
            try {
                update(folderId, edit = { it })
            } catch (_: Throwable) {
                // 后台合并收敛失败静默忽略，下次读取或写入会再次合并
            } finally {
                reconcilingFolders.update { it - folderId }
            }
        }
    }

    private fun scheduleConfirmedCleanup(
        folderId: String,
        listing: List<FileStat>,
    ) {
        // 仅当远端目录列表已实际观测到更高版本的 COMPLETE 替代清单、且其因果祖先集合完整覆盖待清理清单时才执行删除，
        // 绝不在新合并清单仍处于 PENDING 转正期间提前删除任何历史 COMPLETE 分支。
        val completeCandidates = listing.mapNotNull { file ->
            if (!looksLikeManifest(file) || file.phase != TaskPhase.COMPLETE) return@mapNotNull null
            candidateOf(file)
        }.sortedWith(ORDER)
        if (completeCandidates.size <= 1) return
        val metaSnapshot = causalMeta.value
        val completeWithMeta = completeCandidates.map { cand ->
            cand to (metaSnapshot[cand.file.id] ?: ManifestCausalMeta())
        }
        val staleIds = completeWithMeta.mapNotNullTo(HashSet()) { (cand, _) ->
            if (cand.file.id in reading.value) return@mapNotNullTo null
            val candKey = manifestKey(cand.file.id, cand.file.name)
            val supersededByComplete = completeWithMeta.any { (newer, newerMeta) ->
                if (newer.file.id == cand.file.id || newer.version <= cand.version || !newerMeta.isCausal) {
                    false
                } else {
                    candKey in newerMeta.parents ||
                        (cand.file.id in newerMeta.parents && newerMeta.parents.none { it.startsWith("${cand.file.id}|") })
                }
            }
            cand.file.id.takeIf { supersededByComplete }
        }
        if (staleIds.isEmpty() || lifetime.value.closing) return
        storeScope.launch {
            val safeToDelete = staleIds.filterNot { it in reading.value }
            if (safeToDelete.isEmpty()) return@launch
            runSuspendCatching {
                io.delete(safeToDelete)
            }.onSuccess {
                retired.update { it + safeToDelete }
                evictRetired()
            }.logFailure(TAG, "Deferred stale vault manifest cleanup failed in ${logFolder(folderId)} (count=${safeToDelete.size})")
        }
    }

    private suspend fun listWithSerial(folderId: String): Pair<List<FileStat>, Long?> {
        val serial = currentFolderSerial(folderId)
        return io.list(folderId) to serial
    }

    private suspend fun write(
        folderId: String,
        edit: VaultEdit,
        freshlyCreatedFolder: Boolean,
        onListingConfirmed: (VaultCommitSnapshot?) -> Unit,
    ): Result<VaultWrite> =
        lockOf(folderId).withLock {
            runSuspendCatching {
                var baseSerial: Long? = null
                val listing = if (freshlyCreatedFolder) {
                    emptyList()
                } else {
                    val (fetched, serial) = listWithSerial(folderId)
                    baseSerial = serial
                    fetched
                }
                val state = if (freshlyCreatedFolder) {
                    EffectiveVaultState(emptyList(), emptyMap(), emptySet(), 0, 0, false)
                } else {
                    resolveEffectiveState(folderId, listing, includeLocalBaseline = true)
                }
                val localCommitted = if (freshlyCreatedFolder) null else lastCommittedByFolder.value[folderId]
                val baseVersion = maxOf(state.maxRemoteVersion, localCommitted?.second ?: 0)
                val before = state.entries
                val after = edit(before)
                val needsMergeWrite = state.needsReconcile
                if (after == before && !needsMergeWrite) {
                    if (!freshlyCreatedFolder) onListingConfirmed(VaultCommitSnapshot.FullListing(listing, baseSerial))
                    return@runSuspendCatching VaultWrite(before, after)
                }

                val nowMs = Clock.System.now().toEpochMilliseconds()
                val afterIds = after.mapTo(HashSet()) { it.id }
                val nextDeleted = pruneTombstones(state.deleted, nowMs).toMutableMap()
                for (prev in before) {
                    if (prev.id !in afterIds) {
                        val tombTs = maxOf(prev.effectiveUpdatedAt + 1L, nowMs)
                        val existingTs = nextDeleted[prev.id] ?: 0L
                        if (tombTs > existingTs) nextDeleted[prev.id] = tombTs
                    }
                }
                for (alive in after) {
                    nextDeleted.remove(alive.id)
                }

                val incorporatedLiveRefs = if (freshlyCreatedFolder) {
                    emptyList()
                } else {
                    allCandidates(listing, trustedPendingIds.value).flatMap { cand ->
                        val candKey = manifestKey(cand.file.id, cand.file.name)
                        if (cand.file.id in state.coveredIds || candKey in state.coveredIds) {
                            listOf(candKey, cand.file.id)
                        } else {
                            emptyList()
                        }
                    }
                }
                val nextParents = buildList {
                    add(CAUSAL_SENTINEL)
                    add("$ORIGIN_PREFIX$folderId")
                    localCommitted?.first?.takeIf { it in state.coveredIds }?.let(::add)
                    addAll(incorporatedLiveRefs)
                    for (covered in state.coveredIds) {
                        if (covered != CAUSAL_SENTINEL) add(covered)
                    }
                }.distinct().take(MAX_CAUSAL_PARENTS)
                val nextMeta = ManifestCausalMeta(parents = nextParents, deleted = nextDeleted)

                val version = baseVersion + 1
                val token = newToken()
                val text = JSON.encodeToString(
                    Manifest.serializer(),
                    Manifest(entries = after, parents = nextParents, deleted = nextDeleted),
                )
                val manifestBytes = text.encodeToByteArray()
                val manifestName = nameOf(version, token)
                val id = io.upload(folderId, manifestName, manifestBytes)
                rememberParsed(id, after, meta = nextMeta, awaitPersist = true)
                recordCommittedBaseline(folderId, id, version, grantPendingTrustOnCommit = true)

                val syntheticManifest = FileStat(
                    kind = FileKind.FILE,
                    id = id,
                    parentId = folderId,
                    name = manifestName,
                    size = manifestBytes.size.toString(),
                    phase = TaskPhase.COMPLETE,
                )
                if (freshlyCreatedFolder) {
                    onListingConfirmed(VaultCommitSnapshot.IncrementalFiles(listOf(syntheticManifest)))
                    PikoLog.i(TAG, "Vault manifest written for new ${logFolder(folderId)} (v$version, entries=0->${after.size})")
                } else {
                    val updatedListing = listing.filterNot { it.id == id } + syntheticManifest
                    onListingConfirmed(VaultCommitSnapshot.FullListing(updatedListing, baseSerial))
                    scheduleConfirmedCleanup(folderId, listing)
                    PikoLog.i(
                        TAG,
                        "Vault manifest written in ${logFolder(folderId)} (v$version, entries=${before.size}->${after.size}" +
                            if (needsMergeWrite) ", mergedBranches=${state.branchCount})" else ")",
                    )
                }
                VaultWrite(before, after)
            }.logFailure(TAG, "Failed to write vault manifest in ${logFolder(folderId)}")
        }

    private suspend fun manifestOf(file: FileStat): Pair<List<VaultEntry>, ManifestCausalMeta> {
        reading.update { it + (file.id to ((it[file.id] ?: 0) + 1)) }
        try {
            lookupParsed(file)?.let { cachedEntries ->
                return cachedEntries to (causalMeta.value[file.id] ?: ManifestCausalMeta())
            }
            val decoded = JSON.decodeFromString(Manifest.serializer(), io.read(file.id).decodeToString())
            val meta = ManifestCausalMeta(parents = decoded.parents, deleted = decoded.deleted)
            rememberParsed(file.id, decoded.entries, meta = meta)
            return decoded.entries to meta
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
        causalMeta.update { it - removable }
        trustedPendingIds.update { it - removable }
        retired.update { it - removable }
        scheduleSave()
    }

    private fun lockOf(folderId: String): Mutex = locks[folderId.hashCode() and 63]

    @Serializable
    private class Manifest(
        val entries: List<VaultEntry>,
        val parents: List<String> = emptyList(),
        val deleted: Map<String, Long> = emptyMap(),
    )

    @Serializable
    private data class ManifestCausalMeta(
        val parents: List<String> = emptyList(),
        val deleted: Map<String, Long> = emptyMap(),
    ) {
        val isEmpty: Boolean get() = parents.isEmpty() && deleted.isEmpty()
        val isCausal: Boolean get() = CAUSAL_SENTINEL in parents
    }

    @Serializable
    private data class StoredCommitted(val id: String, val version: Int)

    private class CacheSnapshot(
        val parsed: Map<String, List<VaultEntry>>,
        val committed: Map<String, Pair<String, Int>> = emptyMap(),
        val meta: Map<String, ManifestCausalMeta> = emptyMap(),
    )

    /** Only the unique source table is auxiliary; maps/lists decoded here are the final cache. */
    private class CacheSerializer : KSerializer<CacheSnapshot> {
        private val strings = ListSerializer(String.serializer())
        override val descriptor = buildClassSerialDescriptor("VaultCache") {
            element<Int>("version")
            element("sources", strings.descriptor)
            element("manifests", PARSED_SERIALIZER.descriptor)
            element("committed", COMMITTED_SERIALIZER.descriptor, isOptional = true)
            element("meta", META_SERIALIZER.descriptor, isOptional = true)
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
                if (value.meta.isNotEmpty()) {
                    encodeSerializableElement(descriptor, 4, META_SERIALIZER, value.meta)
                }
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
            var meta: Map<String, ManifestCausalMeta> = emptyMap()
            decoder.decodeStructure(descriptor) {
                while (true) when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> version = decodeIntElement(descriptor, index)
                    1 -> sources = decodeSerializableElement(descriptor, index, strings)
                    2 -> manifests = decodeSerializableElement(descriptor, index, MapSerializer(String.serializer(), entries))
                    3 -> committed = decodeSerializableElement(descriptor, index, COMMITTED_PAIRS)
                    4 -> meta = decodeSerializableElement(descriptor, index, META_SERIALIZER)
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
            return CacheSnapshot(parsed, committed, meta)
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
        private const val CAUSAL_SENTINEL = "causal-v1"
        private const val ORIGIN_PREFIX = "origin:"
        private const val MAX_CAUSAL_PARENTS = 96
        private const val TOMBSTONE_RETENTION_MS = 90L * 24 * 60 * 60 * 1000

        private fun manifestKey(id: String, name: String): String = "$id|$name"

        private fun isCoveredManifest(file: FileStat, coveredIds: Set<String>): Boolean {
            val key = manifestKey(file.id, file.name)
            if (key in coveredIds) return true
            return file.id in coveredIds && coveredIds.none { it.startsWith("${file.id}|") }
        }

        private fun sharesCausalOrigin(meta: ManifestCausalMeta, coveredIds: Set<String>): Boolean {
            if (!meta.isCausal) return false
            for (parent in meta.parents) {
                if (parent == CAUSAL_SENTINEL) continue
                if (parent in coveredIds) return true
            }
            return false
        }
        private val JSON = Json { ignoreUnknownKeys = true }
        private val NAME = Regex("""\.piko-vault-v([0-9]+)-([0-9a-f]+)\.json""")
        private val UNSAFE_KEY_CHARS = Regex("""[^A-Za-z0-9._@-]""")
        private val PARSED_SERIALIZER = MapSerializer(String.serializer(), ListSerializer(VaultEntry.serializer()))
        private val COMMITTED_SERIALIZER = MapSerializer(String.serializer(), StoredCommitted.serializer())
        private val META_SERIALIZER = MapSerializer(String.serializer(), ManifestCausalMeta.serializer())
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

        fun cacheKeyOf(account: String): String =
            "vault-manifests-" + account.replace(UNSAFE_KEY_CHARS, "_") + ".json"

        fun committedCacheKeyOf(account: String): String =
            "vault-committed-" + account.replace(UNSAFE_KEY_CHARS, "_") + ".json"

        private val SOURCE_NAME = Regex("""归档来源-[0-9a-f]{16}\.(magnet|txt)""")
        /** 历史版本曾写入同目录的来源旁路文件，现已不再生成，列表里仍过滤隐藏。 */
        fun isRecoveryFile(file: FileStat): Boolean = !file.isFolder && SOURCE_NAME.matches(file.name)

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

        private fun allCandidates(listing: Collection<FileStat>, locallyCachedIds: Set<String> = emptySet()): List<Candidate> =
            listing.mapNotNull { candidateOf(it, locallyCachedIds) }.sortedWith(ORDER)

        private fun topVersionCandidates(listing: Collection<FileStat>, locallyCachedIds: Set<String> = emptySet()): List<Candidate> {
            val candidates = allCandidates(listing, locallyCachedIds)
            val maxVersion = candidates.firstOrNull()?.version ?: return emptyList()
            return candidates.filter { it.version == maxVersion }
        }

        private fun pruneTombstones(deleted: Map<String, Long>, nowMs: Long): Map<String, Long> {
            val cutoff = nowMs - TOMBSTONE_RETENTION_MS
            return deleted.filterValues { it >= cutoff }
        }

        private fun mergeDeletedMaps(maps: List<Map<String, Long>>): Map<String, Long> {
            val merged = LinkedHashMap<String, Long>()
            for (map in maps) {
                for ((id, ts) in map) {
                    val prev = merged[id]
                    if (prev == null || ts > prev) merged[id] = ts
                }
            }
            return merged
        }

        private fun mergeBranches(
            branches: List<List<VaultEntry>>,
            deleted: Map<String, Long> = emptyMap(),
        ): List<VaultEntry> {
            val byId = LinkedHashMap<String, VaultEntry>()
            for (list in branches) {
                for (entry in list) {
                    val prev = byId[entry.id]
                    byId[entry.id] = if (prev == null) entry else mergePair(prev, entry)
                }
            }
            val byContent = LinkedHashMap<Pair<String, String>, VaultEntry>()
            for (entry in byId.values) {
                val delTs = deleted[entry.id]
                if (delTs != null && delTs >= entry.effectiveUpdatedAt) continue
                val key = entry.gcid to entry.name
                val prev = byContent[key]
                byContent[key] = if (prev == null) entry else mergePair(prev, entry)
            }
            return byContent.values.toList()
        }

        private fun mergePair(a: VaultEntry, b: VaultEntry): VaultEntry {
            val aUpdated = a.effectiveUpdatedAt
            val bUpdated = b.effectiveUpdatedAt
            val primary = when {
                bUpdated > aUpdated -> b
                aUpdated > bUpdated -> a
                a.restoredFileId != null && b.restoredFileId == null -> a
                b.restoredFileId != null && a.restoredFileId == null -> b
                b.addedAt > a.addedAt -> b
                else -> a
            }
            val secondary = if (primary === a) b else a
            return primary.copy(
                cid = primary.cid ?: secondary.cid,
                source = primary.source ?: secondary.source,
                duration = primary.duration ?: secondary.duration,
                width = primary.width ?: secondary.width,
                height = primary.height ?: secondary.height,
                mimeType = primary.mimeType ?: secondary.mimeType,
                createdAt = primary.createdAt ?: secondary.createdAt,
            )
        }

        private fun winner(listing: Collection<FileStat>, locallyCachedIds: Set<String> = emptySet()): Candidate? =
            topVersionCandidates(listing, locallyCachedIds).firstOrNull()

        /** 挑出目录列表中当前胜出的归档清单文件。 */
        fun winnerOf(listing: Collection<FileStat>, locallyCachedIds: Set<String> = emptySet()): FileStat? =
            winner(listing, locallyCachedIds)?.file

        /** 清单文件，含没写完的空壳与输掉的那几份。列表里一概不显示。 */
        fun looksLikeManifest(file: FileStat): Boolean =
            !file.isFolder && file.name.startsWith(PREFIX) && file.name.endsWith(SUFFIX)
    }
}

private fun randomToken(): String = Random.nextBytes(8).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
