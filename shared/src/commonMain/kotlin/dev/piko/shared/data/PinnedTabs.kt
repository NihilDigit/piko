package dev.piko.shared.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

@Serializable
data class PinnedTabCrumb(val id: String, val name: String)

@Serializable
data class PinnedTabRecord(
    val pinId: String,
    val crumbs: List<PinnedTabCrumb> = emptyList(),
    val order: Int = 0,
    val pinned: Boolean = true,
    val updatedAt: Long = 0L,
)

/** Account-owned LWW records, including unpin tombstones so another device cannot revive a pin. */
class PinnedTabs(
    private val store: PikoCacheStore?,
    private val scope: CoroutineScope,
    private val saveDelayMillis: Long = 2_000L,
) {
    private data class AccountState(
        val account: String? = null,
        val ready: Boolean = true,
        val records: Map<String, PinnedTabRecord> = emptyMap(),
    )
    private val state = MutableStateFlow(AccountState())
    val flow: StateFlow<Map<String, PinnedTabRecord>> = state.map { it.records }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())
    private var pendingSave: Job? = null

    suspend fun awaitAccountReady(expectedAccount: String): Map<String, PinnedTabRecord>? {
        val snapshot = state.first { it.account != null && (it.account != expectedAccount || it.ready) }
        return snapshot.records.takeIf { snapshot.account == expectedAccount && snapshot.ready }
    }

    fun activeRecords(): List<PinnedTabRecord> = state.value.records.values.filter { it.pinned }
        .sortedWith(compareBy({ it.order }, { it.pinId }))

    fun upsert(pinId: String, crumbs: List<PinnedTabCrumb>, order: Int = 0, nowMs: Long = now(), expectedAccount: String? = null) {
        mutate(expectedAccount, nowMs) { records ->
            val previous = records[pinId]
            if (previous?.pinned == true && previous.crumbs == crumbs && previous.order == order) records
            else records + (pinId to PinnedTabRecord(pinId, crumbs, order, true, maxOf(nowMs, (previous?.updatedAt ?: 0L) + 1)))
        }
    }

    fun unpin(pinId: String, nowMs: Long = now(), expectedAccount: String? = null) {
        mutate(expectedAccount, nowMs) { records ->
            val previous = records[pinId] ?: PinnedTabRecord(pinId)
            if (!previous.pinned) records else records + (pinId to previous.copy(pinned = false, updatedAt = maxOf(nowMs, previous.updatedAt + 1)))
        }
    }

    fun reorder(pinIds: List<String>, nowMs: Long = now(), expectedAccount: String? = null) {
        mutate(expectedAccount, nowMs) { records ->
            val next = records.toMutableMap()
            pinIds.distinct().forEachIndexed { order, id ->
                val previous = next[id] ?: return@forEachIndexed
                if (previous.pinned && previous.order != order) next[id] = previous.copy(order = order, updatedAt = maxOf(nowMs, previous.updatedAt + 1))
            }
            next.toMap()
        }
    }

    private fun mutate(expectedAccount: String?, nowMs: Long, change: (Map<String, PinnedTabRecord>) -> Map<String, PinnedTabRecord>) {
        var changed = false
        state.update { current ->
            if (expectedAccount != null && current.account != expectedAccount) return@update current
            val next = union(emptyMap(), change(current.records), nowMs)
            changed = next != current.records
            current.copy(records = next)
        }
        if (changed) scheduleSave()
    }

    fun mergeAndSnapshot(incoming: Map<String, PinnedTabRecord>, nowMs: Long = now(), expectedAccount: String? = null): Map<String, PinnedTabRecord>? {
        var snapshot: Map<String, PinnedTabRecord>? = null
        var changed = false
        state.update { current ->
            if (expectedAccount != null && current.account != expectedAccount) return@update current
            val merged = union(current.records, incoming, nowMs)
            snapshot = merged
            changed = merged != current.records
            current.copy(records = merged)
        }
        if (changed) scheduleSave()
        return snapshot
    }

    fun switchAccount(account: String?) {
        val previous = state.value
        if (previous.account == account) return
        flushPendingSave()
        val cache = store
        state.value = AccountState(account, ready = cache == null || account.isNullOrEmpty())
        if (cache == null || account.isNullOrEmpty()) return
        scope.launch {
            val stored = runCatching { cache.read(keyOf(account))?.let(::decode) }.getOrNull().orEmpty()
            state.update { current ->
                if (current.account != account) current
                else current.copy(ready = true, records = union(stored, current.records))
            }
        }
    }

    private fun scheduleSave() {
        val cache = store ?: return
        val owner = state.value.account ?: return
        if (pendingSave?.isActive == true) return
        pendingSave = scope.launch {
            delay(saveDelayMillis)
            val snapshot = state.value
            if (snapshot.account == owner) cache.write(keyOf(owner), encode(snapshot.records))
        }
    }

    internal fun flushPendingSave() {
        val pending = pendingSave ?: return
        pendingSave = null
        if (!pending.isActive) return
        pending.cancel()
        val cache = store ?: return
        val snapshot = state.value
        val owner = snapshot.account ?: return
        scope.launch { cache.write(keyOf(owner), encode(snapshot.records)) }
    }

    private fun keyOf(account: String) = "pinned-tabs-" + account.replace(UNSAFE_KEY_CHARS, "_") + ".json"

    companion object {
        private val UNSAFE_KEY_CHARS = Regex("""[^A-Za-z0-9._@-]""")
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(PinnedTabRecord.serializer())
        private fun now() = Clock.System.now().toEpochMilliseconds()
        fun encode(records: Map<String, PinnedTabRecord>): String = json.encodeToString(serializer, records.values.sortedBy { it.pinId })
        fun decode(text: String): Map<String, PinnedTabRecord>? = runCatching {
            json.decodeFromString(serializer, text).associateBy { it.pinId }
        }.getOrNull()

        fun union(a: Map<String, PinnedTabRecord>, b: Map<String, PinnedTabRecord>, nowMs: Long = now()): Map<String, PinnedTabRecord> {
            // Equal timestamps also have a deterministic order; an unpin wins a concurrent pin.
            val comparator = compareBy<PinnedTabRecord>({ it.updatedAt }, { !it.pinned }, { it.order }, { json.encodeToString(PinnedTabRecord.serializer(), it) })
            return (a.keys + b.keys).associateWith { id ->
                listOfNotNull(a[id], b[id]).maxWith(comparator)
            }.filterValues { it.pinned || it.updatedAt >= nowMs - 90.days.inWholeMilliseconds }
        }
    }
}
