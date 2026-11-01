package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.ResolvedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/** 账本里的一条：内容从哪条链接来，[path] 是种子内路径（归档取回的只有文件名），[recordedAt] 是记下的时刻，毫秒。 */
data class SourceRecord(val source: String, val path: String, val recordedAt: Long)

/**
 * 秒传文件的来源账本。PikPak 只给离线下载与分享转存的文件填 `params.url`，按 gcid 秒传建出的文件为空，
 * 客户端也写不进（CLAUDE.md「文件的 params 客户端写不进」），所以由 Piko 记下，列表出来时并进 params，
 * 下游照常读 sourceUrl，不必分辨文件是怎么来的。
 *
 * 键是 gcid（大写）加大小，不是文件 ID：改名、移动不换 ID，复制却换，而内容不变。官方客户端秒传的、
 * 账本出现之前 Piko 秒传的文件没有记录，不回填，也不按文件名猜。
 *
 * 本机按账号存在缓存目录里，跨设备经 SourceLedgerSync 存进网盘 `.piko/sources-<时间戳>.json`。
 * 只增不减；同一键各处记了不同来源时取记下得晚的，时刻相同再按来源、路径的字典序取大者。这是全序上的取最大，
 * 合并的先后与次数不影响结果。只留一条：下游只读一个 url，晚记下的那条刚被秒传成功过，最可能仍有效。
 */
class SourceLedger internal constructor(
    private val store: PikoCacheStore?,
    private val scope: CoroutineScope,
    private val saveDelayMillis: Long = 2_000L,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val records = MutableStateFlow<Map<String, SourceRecord>>(emptyMap())
    val flow: StateFlow<Map<String, SourceRecord>> = records.asStateFlow()

    // 新到或换了来源的键。列表在补全时就已缓存的那几层要据此重列，见 PikoDriveRepository
    private val _arrivals = MutableSharedFlow<Set<String>>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val arrivals: SharedFlow<Set<String>> = _arrivals.asSharedFlow()

    @Volatile
    private var account: String? = null
    private var generation = 0L
    private var saving: SaveSession? = null
    private var previousFlush: Deferred<Result<Unit>>? = null
    // 交接失败的脏快照保留到原账号再次接管，不能因取消旧 writer 而丢失。
    private val failedSnapshots = MutableStateFlow<Map<String, Map<String, SourceRecord>>>(emptyMap())

    private inner class SaveSession(val owner: String, val generation: Long) {
        val job = Job(scope.coroutineContext[Job])
        val loaded = CompletableDeferred<Unit>()
        val snapshot = MutableStateFlow(VersionedSnapshot(PersistVersion(generation, 0), emptyMap<String, SourceRecord>()))
        val writer = SnapshotWriter(
            scope = CoroutineScope(scope.coroutineContext + job),
            accountGeneration = generation,
            source = SnapshotSource { snapshot.value },
            debounceMillis = saveDelayMillis,
        ) { records -> store?.writeStream(keyOf(owner)) { store?.jsonCodec?.encode(Stored.serializer(), stored(records), it)
                ?: it.writeString(json.encodeToString(Stored.serializer(), stored(records))) } }
    }

    /** 记下 [files] 来自 [source]。没有 gcid 的跳过，[source] 为空或目标账号已切换时什么也不记。 */
    fun record(source: String, files: List<ResolvedFile>, expectedAccount: String? = null) {
        if (source.isBlank()) return
        if (expectedAccount != null && account != expectedAccount) return
        val at = now()
        merge(
            files.mapNotNull { file ->
                val gcid = file.gcid?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                keyOf(gcid, file.size) to SourceRecord(source, file.path, at)
            }.toMap(),
            expectedAccount = expectedAccount,
        )
    }

    /** 并进别处的账本。有新内容时返回 true。 */
    fun merge(other: Map<String, SourceRecord>, expectedAccount: String? = null): Boolean {
        if (other.isEmpty()) return false
        var changed: Set<String>
        do {
            if (expectedAccount != null && account != expectedAccount) return false
            val current = records.value
            val next = union(current, other)
            changed = other.keys.filterTo(HashSet()) { next[it] != current[it] }
        } while (!records.compareAndSet(current, next))
        if (changed.isEmpty()) return false
        _arrivals.tryEmit(changed)
        scheduleSave()
        return true
    }

    fun switchAccount(newAccount: String?) {
        if (newAccount == account) return
        flushPendingSave()
        account = newAccount
        val token = ++generation
        records.value = emptyMap()
        saving = newAccount?.takeIf { store != null }?.let { SaveSession(it, token) }
        val cacheStore = store ?: return
        if (newAccount == null) return
        val session = checkNotNull(saving)
        val barrier = previousFlush
        scope.launch {
            try {
                barrier?.join()
                val recovered = failedSnapshots.value[newAccount]
                val stored = union(runSuspendCatching {
                    cacheStore.readStream(keyOf(newAccount)) { cacheStore.jsonCodec?.decode(Stored.serializer(), it) ?: json.decodeFromString(Stored.serializer(), it.readString()) }?.let(::restore)
                }.getOrNull().orEmpty(), recovered.orEmpty())
                session.snapshot.update {
                    it.copy(
                        version = it.version.copy(revision = it.version.revision + if (recovered != null) 1 else 0),
                        value = union(stored, it.value),
                    )
                }
                if (recovered != null) failedSnapshots.update { it - newAccount }
                if (generation == token) merge(stored, expectedAccount = newAccount)
            } catch (failure: Throwable) {
                session.loaded.completeExceptionally(failure)
                throw failure
            } finally {
                if (session.loaded.complete(Unit)) {
                    val version = session.snapshot.value.version
                    if (version.revision > 0 && session.job.isActive) session.writer.markDirty(version)
                }
            }
        }
    }

    /** 服务端没给来源、账本里有的文件，来源并进 `params["url"]`，与 VaultEntry.enrich 同一形式。 */
    fun enrich(file: FileStat): FileStat {
        if (file.isFolder || file.hash.isEmpty() || !file.sourceUrl.isNullOrBlank()) return file
        val record = records.value[keyOf(file.hash, file.sizeBytes)] ?: return file
        return file.copy(params = file.params + ("url" to record.source))
    }

    fun enrich(files: List<FileStat>): List<FileStat> = if (records.value.isEmpty()) files else files.map(::enrich)

    private fun scheduleSave() {
        val session = saving ?: return
        val version = session.snapshot.updateAndGet {
            VersionedSnapshot(PersistVersion(session.generation, it.version.revision + 1), records.value)
        }.version
        if (session.loaded.isCompleted && !session.loaded.isCancelled) session.writer.markDirty(version)
    }

    /**
     * 换号前还没写下的当场写给原来的账号。只取消的话，换号前两秒内记下的来源就丢了，
     * 网盘那边没同步上的，切回来再也找不回。
     */
    private fun flushPendingSave() {
        val session = saving ?: return
        saving = null
        val predecessor = previousFlush
        previousFlush = scope.async {
            try {
                // 只等待前代结束；它的提交结果不属于当前账号。
                predecessor?.join()
                session.loaded.await()
                if (session.snapshot.value.version.revision > 0) session.writer.flushThrough(session.snapshot.value.version)
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failedSnapshots.update { it + (session.owner to session.snapshot.value.value) }
                // 保留失败结果，但不让 async 的异常取消共享作用域。
                dev.piko.shared.log.PikoLog.w("SourceLedger", "保存旧账号来源账本失败", failure)
                Result.failure(failure)
            } finally {
                session.job.cancel()
            }
        }
    }

    private fun keyOf(account: String) = "sources-" + account.replace(UNSAFE_KEY_CHARS, "_") + ".json"

    /**
     * 存盘格式。来源单列一张表、文件按下标引用：同一条磁力常带着几十个 tracker，一季几十集都来自它，
     * 逐条重复会让文件大出一个量级。
     */
    @Serializable
    private class Stored(val version: Int = VERSION, val sources: List<String> = emptyList(), val files: Map<String, Row> = emptyMap())

    @Serializable
    private class Row(val source: Int, val path: String = "", val at: Long = 0)

    companion object {
        private const val VERSION = 1
        private val UNSAFE_KEY_CHARS = Regex("""[^A-Za-z0-9._@-]""")
        private val json = Json { ignoreUnknownKeys = true }
        private val ORDER = compareBy<SourceRecord>({ it.recordedAt }, { it.source }, { it.path })

        fun keyOf(gcid: String, size: Long): String = "${gcid.uppercase()}:$size"

        fun encode(records: Map<String, SourceRecord>): String =
            json.encodeToString(Stored.serializer(), stored(records))

        private fun stored(records: Map<String, SourceRecord>): Stored {
            val sources = records.values.map { it.source }.distinct().sorted()
            val index = sources.withIndex().associate { it.value to it.index }
            val files = records.entries.sortedBy { it.key }
                .associate { (key, record) -> key to Row(index.getValue(record.source), record.path, record.recordedAt) }
            return Stored(VERSION, sources, files)
        }

        /** 读不出时为 null。下标越界的行丢掉，键按 [keyOf] 重新规整。 */
        fun decode(text: String): Map<String, SourceRecord>? = runCatching {
            restore(json.decodeFromString(Stored.serializer(), text))
        }.getOrNull()

        private fun restore(stored: Stored): Map<String, SourceRecord> {
            require(stored.version == VERSION) { "Unsupported source ledger schema" }
            val merged = HashMap<String, SourceRecord>(stored.files.size * 2)
            for ((key, row) in stored.files) {
                val source = stored.sources.getOrNull(row.source) ?: continue
                val gcid = key.substringBeforeLast(':')
                val size = key.substringAfterLast(':').toLongOrNull() ?: continue
                val normalizedKey = keyOf(gcid, size)
                val record = SourceRecord(source, row.path, row.at)
                val existing = merged[normalizedKey]
                if (existing == null || ORDER.compare(record, existing) > 0) {
                    merged[normalizedKey] = record
                }
            }
            return merged
        }

        fun union(a: Map<String, SourceRecord>, b: Map<String, SourceRecord>): Map<String, SourceRecord> {
            if (b.isEmpty()) return a
            val merged = HashMap(a)
            for ((key, record) in b) {
                val existing = merged[key]
                if (existing == null || ORDER.compare(record, existing) > 0) merged[key] = record
            }
            return merged
        }
    }
}
