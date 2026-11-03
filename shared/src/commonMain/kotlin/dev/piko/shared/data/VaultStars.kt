package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * 单条归档星标记录（含取消星标的 LWW 墓碑，避免多端合并时把已取消的星标重新并回来）。
 */
@Serializable
data class VaultStarRecord(
    val entryId: String,
    val folderId: String,
    val name: String,
    val size: Long,
    val gcid: String,
    val source: String? = null,
    val addedAt: Long = 0L,
    val duration: String? = null,
    val width: String? = null,
    val height: String? = null,
    val mimeType: String? = null,
    val starred: Boolean = true,
    val updatedAt: Long,
    val restoredFileId: String? = null,
) {
    fun toVaultEntry(): VaultEntry = VaultEntry(
        id = entryId,
        name = name,
        size = size,
        gcid = gcid,
        source = source,
        addedAt = if (addedAt > 0L) addedAt else updatedAt,
        duration = duration,
        width = width,
        height = height,
        mimeType = mimeType,
    )

    fun toFileStat(): FileStat = toVaultEntry().toFileStat(folderId, starred = starred)
}

/**
 * Piko 自管的归档条目星标账本：
 * PikPak 服务端无法对不占空间的虚拟归档条目（`piko-vault:`）打星标，且真实文件归档删除后原服务端星标会随之消失。
 * 这里按账号维护归档条目的星标名单，本机存入缓存目录 `vault-stars-<账号>.json`，跨设备经 `VaultStarSync`
 * 同步至网盘 `.piko/vault-stars-<时间戳>.json`。
 */
class VaultStars internal constructor(
    private val store: PikoCacheStore?,
    private val scope: CoroutineScope,
    private val saveDelayMillis: Long = 2_000L,
) {
    private data class AccountStarsState(
        val account: String? = null,
        val ready: Boolean = true,
        val records: Map<String, VaultStarRecord> = emptyMap(),
    )

    private val state = MutableStateFlow(AccountStarsState())
    val flow: StateFlow<Map<String, VaultStarRecord>> = state
        .map { it.records }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    // 从磁盘载入或跨设备同步合并导致某些目录的星标状态变化时发出受影响目录 ID，供仓库刷新列表缓存
    private val _changedFolders = MutableSharedFlow<Set<String>>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val changedFolders: SharedFlow<Set<String>> = _changedFolders.asSharedFlow()

    private var pendingSave: Job? = null

    fun isStarred(entryId: String): Boolean = state.value.records[entryId]?.starred == true

    fun folderOf(entryId: String): String? = state.value.records[entryId]?.takeIf { it.starred }?.folderId

    /** 等待目标账号 [expectedAccount] 的本地星标账本完成切换与磁盘缓存加载；若期间已切往其他账号则返回 null。 */
    suspend fun awaitAccountReady(expectedAccount: String): Map<String, VaultStarRecord>? {
        val snapshot = state.first { it.account != expectedAccount || it.ready }
        return snapshot.records.takeIf { snapshot.account == expectedAccount && snapshot.ready }
    }

    /** 当前全部处于星标状态的未取回归档条目，转换为带 STAR 标签的虚拟 [FileStat] 供「星标」库直接合并展示。 */
    fun starredFileStats(): List<FileStat> =
        state.value.records.values
            .filter { it.starred && it.restoredFileId == null }
            .sortedByDescending { it.updatedAt }
            .map { it.toFileStat() }

    /** 归档带星标的真实文件或对已知 [VaultEntry] 加/去星标。 */
    fun starEntries(
        folderId: String,
        entries: Collection<VaultEntry>,
        starred: Boolean = true,
        nowMs: Long = now(),
        expectedAccount: String? = null,
    ): List<String> {
        if (entries.isEmpty()) return emptyList()
        val affectedVirtualIds = ArrayList<String>(entries.size)
        var changed = false
        var accepted = false
        state.update { cur ->
            if (expectedAccount != null && cur.account != expectedAccount) return@update cur
            accepted = true
            val current = cur.records
            val next = LinkedHashMap(current)
            for (entry in entries) {
                if (!entry.isArchived) continue
                val prev = next[entry.id]
                if (prev?.starred == starred && prev.folderId == folderId && prev.name == entry.name && prev.restoredFileId == null) continue
                val stamp = maxOf(nowMs, (prev?.updatedAt ?: 0L) + 1L)
                next[entry.id] = VaultStarRecord(
                    entryId = entry.id,
                    folderId = folderId,
                    name = entry.name,
                    size = entry.size,
                    gcid = entry.gcid,
                    source = entry.source ?: prev?.source,
                    addedAt = entry.addedAt.takeIf { it > 0L } ?: prev?.addedAt ?: stamp,
                    duration = entry.duration ?: prev?.duration,
                    width = entry.width ?: prev?.width,
                    height = entry.height ?: prev?.height,
                    mimeType = entry.mimeType ?: prev?.mimeType,
                    starred = starred,
                    updatedAt = stamp,
                    restoredFileId = null,
                )
                changed = true
            }
            if (changed) cur.copy(records = pruneTombstones(next, nowMs)) else cur
        }
        if (!accepted) return emptyList()
        for (entry in entries) {
            if (entry.isArchived) affectedVirtualIds += entry.virtualId
        }
        if (changed) scheduleSave()
        return affectedVirtualIds
    }

    /** 从列表或播放器手头的虚拟 [FileStat]（或仅含虚拟 ID 的 [FileStat]）直接设置星标状态。 */
    fun setStarredFromStats(
        stats: Collection<FileStat>,
        starred: Boolean,
        nowMs: Long = now(),
        expectedAccount: String? = null,
    ): List<String> {
        if (stats.isEmpty()) return emptyList()
        val affectedVirtualIds = ArrayList<String>(stats.size)
        var changed = false
        var accepted = false
        state.update { cur ->
            if (expectedAccount != null && cur.account != expectedAccount) return@update cur
            accepted = true
            val current = cur.records
            val next = LinkedHashMap(current)
            for (stat in stats) {
                val entryId = VaultEntry.entryIdOf(stat.id) ?: continue
                val resolved = VaultEntry.resolvedFileOf(stat.id) ?: continue
                val gcid = stat.hash.ifBlank { resolved.gcid.orEmpty() }
                if (gcid.isBlank()) continue
                val prev = next[entryId]
                val folderId = stat.parentId.ifEmpty { prev?.folderId.orEmpty() }
                val name = stat.name.ifBlank { resolved.path }
                val size = if (stat.sizeBytes > 0L) stat.sizeBytes else resolved.size
                if (!starred && prev == null) continue
                val stamp = maxOf(nowMs, (prev?.updatedAt ?: 0L) + 1L)
                next[entryId] = VaultStarRecord(
                    entryId = entryId,
                    folderId = folderId,
                    name = name,
                    size = size,
                    gcid = gcid,
                    source = stat.sourceUrl?.takeIf { it.isNotBlank() } ?: prev?.source,
                    addedAt = prev?.addedAt ?: stamp,
                    duration = stat.params["duration"]?.takeIf { it.isNotBlank() } ?: prev?.duration,
                    width = stat.params["width"]?.takeIf { it.isNotBlank() } ?: prev?.width,
                    height = stat.params["height"]?.takeIf { it.isNotBlank() } ?: prev?.height,
                    mimeType = stat.mimeType.takeIf { it.isNotBlank() } ?: prev?.mimeType,
                    starred = starred,
                    updatedAt = stamp,
                    restoredFileId = null,
                )
                changed = true
            }
            if (changed) cur.copy(records = pruneTombstones(next, nowMs)) else cur
        }
        if (!accepted) return emptyList()
        for (stat in stats) {
            if (VaultEntry.entryIdOf(stat.id) != null && VaultEntry.resolvedFileOf(stat.id) != null) {
                affectedVirtualIds += stat.id
            }
        }
        if (changed) scheduleSave()
        return affectedVirtualIds
    }

    /** 按 [entryIds] 批量取消星标（如从清单移除或已取回为真实文件后迁出）。 */
    fun unstarByEntryIds(
        entryIds: Collection<String>,
        nowMs: Long = now(),
        expectedAccount: String? = null,
    ): Set<String> {
        if (entryIds.isEmpty()) return emptySet()
        val touchedFolders = HashSet<String>()
        var changed = false
        state.update { cur ->
            if (expectedAccount != null && cur.account != expectedAccount) return@update cur
            val current = cur.records
            val next = LinkedHashMap(current)
            for (id in entryIds) {
                val prev = next[id] ?: continue
                if (!prev.starred) continue
                val stamp = maxOf(nowMs, prev.updatedAt + 1L)
                next[id] = prev.copy(starred = false, updatedAt = stamp, restoredFileId = null)
                touchedFolders += prev.folderId
                changed = true
            }
            if (changed) cur.copy(records = pruneTombstones(next, nowMs)) else cur
        }
        if (changed) scheduleSave()
        return touchedFolders
    }

    /** 归档条目在清单中改名后，同步更新星标账本里的名称。 */
    fun renameEntry(entryId: String, newName: String, nowMs: Long = now(), expectedAccount: String? = null) {
        var changed = false
        state.update { cur ->
            if (expectedAccount != null && cur.account != expectedAccount) return@update cur
            val prev = cur.records[entryId] ?: return@update cur
            if (prev.name == newName) return@update cur
            val stamp = maxOf(nowMs, prev.updatedAt + 1L)
            changed = true
            cur.copy(records = cur.records + (entryId to prev.copy(name = newName, updatedAt = stamp)))
        }
        if (changed) scheduleSave()
    }

    /**
     * 读取或写成 [folderId] 的最新已确认归档清单后对账：
     * - 仍在归档中的已星标条目同步最新名称与元数据，并清除 `restoredFileId`；
     * - 已取回为真实文件（`restoredFileId != null`）的已星标条目暂留 `starred = true` 并记录 `restoredFileId`，
     *   仅在服务端对真实文件打星成功后再显式 [unstarByEntryIds]，防止服务端打星失败导致星标彻底丢失；
     * - 原本属于该目录、但已从最新已确认清单中彻底移除的星标记录自动标为未星标。
     */
    fun reconcileFolder(
        folderId: String,
        entries: List<VaultEntry>,
        nowMs: Long = now(),
        expectedAccount: String? = null,
    ) {
        val currentSnapshot = state.value
        if (expectedAccount != null && currentSnapshot.account != expectedAccount) return
        if (currentSnapshot.records.isEmpty()) return
        val allById = entries.associateBy { it.id }
        val hasRelated = currentSnapshot.records.values.any { it.starred && (it.folderId == folderId || it.entryId in allById) }
        if (!hasRelated) return
        var changed = false
        state.update { cur ->
            if (expectedAccount != null && cur.account != expectedAccount) return@update cur
            val current = cur.records
            val next = LinkedHashMap(current)
            for ((entryId, record) in current) {
                if (!record.starred) continue
                val present = allById[entryId]
                if (present != null) {
                    val candidate = record.copy(
                        folderId = folderId,
                        name = present.name,
                        size = present.size,
                        gcid = present.gcid,
                        source = present.source ?: record.source,
                        duration = present.duration ?: record.duration,
                        width = present.width ?: record.width,
                        height = present.height ?: record.height,
                        mimeType = present.mimeType ?: record.mimeType,
                        restoredFileId = present.restoredFileId,
                    )
                    if (candidate != record) {
                        val stamp = maxOf(nowMs, record.updatedAt + 1L)
                        next[entryId] = candidate.copy(updatedAt = stamp)
                        changed = true
                    }
                } else if (record.folderId == folderId) {
                    val stamp = maxOf(nowMs, record.updatedAt + 1L)
                    next[entryId] = record.copy(starred = false, updatedAt = stamp, restoredFileId = null)
                    changed = true
                }
            }
            if (changed) cur.copy(records = pruneTombstones(next, nowMs)) else cur
        }
        if (changed) scheduleSave()
    }

    /** 与远端或磁盘载入的记录按 `entryId` 执行全序 LWW 合并，并返回目标账号合并后的原子快照（账号已切换时返回 null）。 */
    fun mergeAndSnapshot(
        incoming: Map<String, VaultStarRecord>,
        nowMs: Long = now(),
        expectedAccount: String? = null,
    ): Map<String, VaultStarRecord>? {
        var changed = false
        var resultingRecords: Map<String, VaultStarRecord>? = null
        val touchedFolders = HashSet<String>()
        state.update { cur ->
            if (expectedAccount != null && cur.account != expectedAccount) return@update cur
            if (incoming.isEmpty()) {
                resultingRecords = cur.records
                return@update cur
            }
            val current = cur.records
            val merged = union(current, incoming, nowMs)
            resultingRecords = merged
            if (merged != current) {
                changed = true
                for (id in (current.keys + merged.keys)) {
                    val a = current[id]
                    val b = merged[id]
                    if (a?.starred != b?.starred || a?.name != b?.name || a?.restoredFileId != b?.restoredFileId) {
                        a?.folderId?.let(touchedFolders::add)
                        b?.folderId?.let(touchedFolders::add)
                    }
                }
                cur.copy(records = merged)
            } else {
                cur
            }
        }
        if (changed) {
            scheduleSave()
            if (touchedFolders.isNotEmpty()) _changedFolders.tryEmit(touchedFolders)
        }
        return resultingRecords
    }

    /** 与远端或磁盘载入的记录按 `entryId` 执行 LWW 合并。 */
    fun merge(incoming: Map<String, VaultStarRecord>, nowMs: Long = now(), expectedAccount: String? = null): Boolean {
        if (incoming.isEmpty()) return false
        val before = state.value.records
        val after = mergeAndSnapshot(incoming, nowMs, expectedAccount) ?: return false
        return after != before
    }

    fun switchAccount(newAccount: String?) {
        val prev = state.value
        if (newAccount == prev.account) return
        flushPendingSave(prev)
        val cacheStore = store
        val needsLoad = cacheStore != null && !newAccount.isNullOrEmpty()
        state.value = AccountStarsState(account = newAccount, ready = !needsLoad, records = emptyMap())
        if (cacheStore == null || newAccount.isNullOrEmpty()) return
        scope.launch {
            val stored = runCatching { cacheStore.read(keyOf(newAccount))?.let(::decode) }.getOrNull().orEmpty()
            var changed = false
            val touchedFolders = HashSet<String>()
            state.update { cur ->
                if (cur.account != newAccount) return@update cur
                val merged = union(stored, cur.records, now())
                if (merged != cur.records) {
                    changed = true
                    merged.values.forEach { touchedFolders += it.folderId }
                }
                cur.copy(ready = true, records = merged)
            }
            if (changed && touchedFolders.isNotEmpty()) {
                _changedFolders.tryEmit(touchedFolders)
            }
        }
    }

    private fun scheduleSave() {
        val cacheStore = store ?: return
        val owner = state.value.account ?: return
        if (pendingSave?.isActive == true) return
        pendingSave = scope.launch {
            delay(saveDelayMillis)
            val snapshot = state.value
            if (snapshot.account == owner) cacheStore.write(keyOf(owner), encode(snapshot.records))
        }
    }

    internal fun flushPendingSave() = flushPendingSave(state.value)

    private fun flushPendingSave(previous: AccountStarsState) {
        val pending = pendingSave ?: return
        pendingSave = null
        if (!pending.isActive) return
        pending.cancel()
        val cacheStore = store ?: return
        val owner = previous.account ?: return
        val snapshot = previous.records
        scope.launch { cacheStore.write(keyOf(owner), encode(snapshot)) }
    }

    private fun keyOf(account: String) = "vault-stars-" + account.replace(UNSAFE_KEY_CHARS, "_") + ".json"

    companion object {
        private val UNSAFE_KEY_CHARS = Regex("""[^A-Za-z0-9._@-]""")
        private val TOMBSTONE_RETENTION_MS = 90.days.inWholeMilliseconds
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(VaultStarRecord.serializer())

        private fun now(): Long = Clock.System.now().toEpochMilliseconds()

        fun encode(records: Map<String, VaultStarRecord>): String =
            json.encodeToString(serializer, records.values.sortedBy { it.entryId })

        fun decode(text: String): Map<String, VaultStarRecord>? =
            runCatching {
                json.decodeFromString(serializer, text).associateBy { it.entryId }
            }.getOrNull()

        private val RECORD_ORDER = compareBy<VaultStarRecord>(
            { it.updatedAt },
            { it.starred },
            { it.restoredFileId != null },
            { it.restoredFileId.orEmpty() },
            { it.folderId },
            { it.name },
            { it.size },
            { it.gcid },
            { it.source.orEmpty() },
            { it.addedAt },
            { it.duration.orEmpty() },
            { it.width.orEmpty() },
            { it.height.orEmpty() },
            { it.mimeType.orEmpty() },
        )

        fun union(
            a: Map<String, VaultStarRecord>,
            b: Map<String, VaultStarRecord>,
            nowMs: Long = now(),
        ): Map<String, VaultStarRecord> {
            val merged = LinkedHashMap<String, VaultStarRecord>(a.size + b.size)
            for (id in (a.keys + b.keys)) {
                val left = a[id]
                val right = b[id]
                val winner = when {
                    left == null -> right!!
                    right == null -> left
                    RECORD_ORDER.compare(right, left) >= 0 -> right
                    else -> left
                }
                merged[id] = winner
            }
            return pruneTombstones(merged, nowMs)
        }

        private fun pruneTombstones(
            map: LinkedHashMap<String, VaultStarRecord>,
            nowMs: Long,
        ): Map<String, VaultStarRecord> {
            val cutoff = nowMs - TOMBSTONE_RETENTION_MS
            val iter = map.entries.iterator()
            while (iter.hasNext()) {
                val record = iter.next().value
                if (!record.starred && record.updatedAt < cutoff) iter.remove()
            }
            return map.toMap()
        }
    }
}
