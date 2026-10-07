package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.ResolvedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private var pendingSave: Job? = null

    /** 记下 [files] 来自 [source]。没有 gcid 的跳过，[source] 为空时什么也不记。 */
    fun record(source: String, files: List<ResolvedFile>) {
        if (source.isBlank()) return
        val at = now()
        merge(
            files.mapNotNull { file ->
                val gcid = file.gcid?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                keyOf(gcid, file.size) to SourceRecord(source, file.path, at)
            }.toMap(),
        )
    }

    /** 并进别处的账本。有新内容时返回 true。 */
    fun merge(other: Map<String, SourceRecord>): Boolean {
        if (other.isEmpty()) return false
        var changed: Set<String>
        do {
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
        records.value = emptyMap()
        val cacheStore = store ?: return
        if (newAccount == null) return
        scope.launch {
            val stored = cacheStore.read(keyOf(newAccount))?.let(::decode) ?: return@launch
            if (account != newAccount) return@launch
            merge(stored)
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
        val cacheStore = store ?: return
        val owner = account ?: return
        if (pendingSave?.isActive == true) return
        pendingSave = scope.launch {
            delay(saveDelayMillis)
            // 先取内容再核对账号：换号先改账号、再清内容，核对通过时取到的必是 owner 的
            val snapshot = records.value
            if (account == owner) cacheStore.write(keyOf(owner), encode(snapshot))
        }
    }

    /**
     * 换号前还没写下的当场写给原来的账号。只取消的话，换号前两秒内记下的来源就丢了，
     * 网盘那边没同步上的，切回来再也找不回。
     */
    private fun flushPendingSave() {
        val pending = pendingSave ?: return
        pendingSave = null
        if (!pending.isActive) return
        pending.cancel()
        val cacheStore = store ?: return
        val owner = account ?: return
        val snapshot = records.value
        scope.launch { cacheStore.write(keyOf(owner), encode(snapshot)) }
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

        fun encode(records: Map<String, SourceRecord>): String {
            val sources = records.values.map { it.source }.distinct().sorted()
            val index = sources.withIndex().associate { it.value to it.index }
            val files = records.entries.sortedBy { it.key }
                .associate { (key, record) -> key to Row(index.getValue(record.source), record.path, record.recordedAt) }
            return json.encodeToString(Stored.serializer(), Stored(VERSION, sources, files))
        }

        /** 读不出时为 null。下标越界的行丢掉，键按 [keyOf] 重新规整。 */
        fun decode(text: String): Map<String, SourceRecord>? = runCatching {
            val stored = json.decodeFromString(Stored.serializer(), text)
            stored.files.entries.fold(emptyMap<String, SourceRecord>()) { acc, (key, row) ->
                val source = stored.sources.getOrNull(row.source) ?: return@fold acc
                val gcid = key.substringBeforeLast(':')
                val size = key.substringAfterLast(':').toLongOrNull() ?: return@fold acc
                union(acc, mapOf(keyOf(gcid, size) to SourceRecord(source, row.path, row.at)))
            }
        }.getOrNull()

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
