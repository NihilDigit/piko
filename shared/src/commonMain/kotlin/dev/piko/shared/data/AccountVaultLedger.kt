package dev.piko.shared.data

import dev.piko.data.repository.isPlayableVideo
import dev.piko.data.repository.isPreviewableImage
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.search.prepareCjkQuery
import dev.piko.shared.util.PikoLock
import dev.piko.shared.util.withPikoLock
import dev.piko.shared.work.PikoDurableStore
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakHash
import io.github.nihildigit.pikpak.SearchHit
import io.github.nihildigit.pikpak.TaskPhase
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private const val LEDGER_TAG = "AccountVaultLedger"
private const val REAL_PARENT_PREFIX = "real:"

enum class VaultBatchSyncState(val wireName: String) {
    PENDING("PENDING"),
    SYNCED("SYNCED"),
    SPACE_FULL("SPACE_FULL"),
}

@Serializable
data class VaultNodeSnapshot(
    val nodeId: String,
    val isFolder: Boolean,
    val parentFolderId: String,
    val name: String,
    val size: Long = 0L,
    val gcid: String = "",
    val source: String? = null,
    val meta: VaultMediaMeta? = null,
    val restoredFileId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
sealed interface VaultOp {
    @Serializable
    @SerialName("upsert_folder")
    data class UpsertFolder(
        val nodeId: String,
        val parentFolderId: String,
        val name: String,
        val createdAt: Long,
    ) : VaultOp

    @Serializable
    @SerialName("upsert_file")
    data class UpsertFile(
        val nodeId: String,
        val parentFolderId: String,
        val name: String,
        val size: Long,
        val gcid: String,
        val source: String? = null,
        val meta: VaultMediaMeta? = null,
        val createdAt: Long,
    ) : VaultOp

    @Serializable
    @SerialName("rename")
    data class RenameNode(
        val nodeId: String,
        val newName: String,
        val updatedAt: Long,
    ) : VaultOp

    @Serializable
    @SerialName("move")
    data class MoveNode(
        val nodeId: String,
        val newParentFolderId: String,
        val updatedAt: Long,
    ) : VaultOp

    @Serializable
    @SerialName("delete")
    data class DeleteNode(
        val nodeId: String,
        val deletedAt: Long,
    ) : VaultOp

    @Serializable
    @SerialName("restore_snapshot")
    data class RestoreSnapshot(
        val nodes: List<VaultNodeSnapshot>,
        val restoredAt: Long,
    ) : VaultOp

    @Serializable
    @SerialName("mark_restored")
    data class MarkRestored(
        val nodeId: String,
        val restoredFileId: String? = null,
        val updatedAt: Long,
    ) : VaultOp
}

@Serializable
data class VaultOpBatch(
    val schema: Int = 1,
    val batchId: String,
    val actorId: String,
    val actorSeq: Long,
    val logicalClock: Long,
    val createdAt: Long,
    val parents: List<String> = emptyList(),
    val ops: List<VaultOp>,
    val digest: String,
)

data class StoredVaultBatch(
    val batch: VaultOpBatch,
    val synced: Boolean,
    val remoteFileId: String?,
    val syncState: VaultBatchSyncState,
    val materialized: Boolean = true,
)

@Serializable
data class VaultCheckpointNode(
    val nodeId: String,
    val isFolder: Boolean,
    val parentFolderId: String,
    val name: String,
    val size: Long = 0L,
    val gcid: String = "",
    val source: String? = null,
    val meta: VaultMediaMeta? = null,
    val restoredFileId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val updatedClock: Long,
    val updatedActor: String,
    val deletedAt: Long? = null,
    val deletedClock: Long? = null,
    val deletedActor: String? = null,
)

@Serializable
data class VaultCheckpointSnapshot(
    val schema: Int = 1,
    val logicalClock: Long,
    val actorId: String,
    val createdAt: Long,
    val coveredBatchIds: List<String>,
    val leafBatchIds: List<String>,
    val nodes: List<VaultCheckpointNode>,
    val digest: String,
)

data class LocalOpsReport(
    val touchedFolders: Set<String>,
    val skippedMoveIds: Set<String> = emptySet(),
)

data class VirtualImportOutcome(
    val containerFolderId: String?,
    val confirmedByPath: Map<String, String>,
    val touchedFolders: Set<String>,
)

/**
 * Account-scoped durable virtual reference ledger (`vault_batches` + `vault_nodes`).
 *
 * Commits `以引用保存` subtrees locally in a single SQLite transaction (< 10 ms, zero foreground
 * cloud requests) and replicates operation batches asynchronously under `.piko/vault-ops/`.
 */
class AccountVaultLedger(
    val store: PikoDurableStore,
) {
    data class NodeRecord(
        val nodeId: String,
        val isFolder: Boolean,
        val parentFolderId: String,
        val name: String,
        val size: Long,
        val gcid: String,
        val source: String?,
        val meta: VaultMediaMeta?,
        val restoredFileId: String?,
        val createdAt: Long,
        val updatedAt: Long,
        val updatedClock: Long,
        val updatedActor: String,
        val deletedAt: Long?,
        val deletedClock: Long?,
        val deletedActor: String?,
    ) {
        val isActive: Boolean get() = deletedAt == null
        val virtualFolderId: String get() = VirtualVaultLocation.of(nodeId)

        fun toVaultEntry(): VaultEntry = VaultEntry(
            id = nodeId,
            name = name,
            size = size,
            gcid = gcid,
            cid = meta?.cid,
            addedAt = createdAt,
            restoredFileId = restoredFileId,
            source = source,
            duration = meta?.duration,
            width = meta?.width,
            height = meta?.height,
            mimeType = meta?.mimeType,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

        fun toFolderFileStat(coverThumbnailUrl: String = ""): FileStat {
            val iso = Instant.fromEpochMilliseconds(updatedAt.coerceAtLeast(createdAt)).toString()
            val createdIso = Instant.fromEpochMilliseconds(createdAt).toString()
            return FileStat(
                id = virtualFolderId,
                kind = FileKind.FOLDER,
                name = name,
                parentId = parentFolderId,
                size = "0",
                createdTime = createdIso,
                modifiedTime = iso,
                thumbnailLink = coverThumbnailUrl,
                phase = TaskPhase.COMPLETE,
            )
        }

        fun toSnapshot(): VaultNodeSnapshot = VaultNodeSnapshot(
            nodeId = nodeId,
            isFolder = isFolder,
            parentFolderId = parentFolderId,
            name = name,
            size = size,
            gcid = gcid,
            source = source,
            meta = meta,
            restoredFileId = restoredFileId,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    data class Projection(
        val account: String? = null,
        val activeById: Map<String, NodeRecord> = emptyMap(),
        val childrenByParent: Map<String, List<NodeRecord>> = emptyMap(),
        val coverByFolderId: Map<String, String> = emptyMap(),
        val realFoldersWithVault: Set<String> = emptySet(),
        val subtreeBytesByFolderId: Map<String, Pair<Int, Long>> = emptyMap(),
        val unsyncedCount: Int = 0,
        val unmaterializedCount: Int = 0,
        val syncState: VaultBatchSyncState = VaultBatchSyncState.SYNCED,
    )

    private val lock = PikoLock()
    private val _projection = MutableStateFlow(Projection())
    val projection: StateFlow<Projection> = _projection.asStateFlow()

    private val _localBatchSignal = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val localBatchSignal: SharedFlow<String> = _localBatchSignal.asSharedFlow()

    init {
        store.registerAccountHook { account, db ->
            if (account != null && db != null) {
                initializeSchema(db)
                reloadProjection(account, db)
            } else {
                _projection.value = Projection()
            }
        }
    }

    private fun initializeSchema(db: PikoTreeDatabase) = db.transaction {
        db.execute(
            """
            CREATE TABLE IF NOT EXISTS vault_batches (
                batch_id TEXT PRIMARY KEY,
                actor_id TEXT NOT NULL,
                actor_seq INTEGER NOT NULL,
                logical_clock INTEGER NOT NULL,
                parents_json TEXT NOT NULL,
                ops_json TEXT NOT NULL,
                digest TEXT NOT NULL,
                synced INTEGER NOT NULL DEFAULT 0,
                remote_file_id TEXT,
                sync_state TEXT NOT NULL DEFAULT 'PENDING',
                created_at INTEGER NOT NULL,
                materialized INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent(),
        )
        runCatching {
            db.execute("ALTER TABLE vault_batches ADD COLUMN materialized INTEGER NOT NULL DEFAULT 1")
        }
        db.execute("CREATE INDEX IF NOT EXISTS idx_vault_batches_synced ON vault_batches(synced, created_at)")
        db.execute(
            """
            CREATE TABLE IF NOT EXISTS vault_checkpoint (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                snapshot_json TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execute(
            """
            CREATE TABLE IF NOT EXISTS vault_nodes (
                node_id TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                parent_ref TEXT NOT NULL,
                name TEXT NOT NULL,
                size INTEGER NOT NULL DEFAULT 0,
                gcid TEXT NOT NULL DEFAULT '',
                source TEXT,
                meta_json TEXT,
                restored_file_id TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                updated_clock INTEGER NOT NULL,
                updated_actor TEXT NOT NULL,
                deleted_at INTEGER,
                deleted_clock INTEGER,
                deleted_actor TEXT
            )
            """.trimIndent(),
        )
        db.execute("CREATE INDEX IF NOT EXISTS idx_vault_nodes_parent ON vault_nodes(parent_ref, deleted_at)")
    }

    fun hasVirtualFolder(folderId: String): Boolean {
        val nodeId = VirtualVaultLocation.nodeIdOf(folderId) ?: return false
        val node = _projection.value.activeById[nodeId] ?: return false
        return node.isFolder
    }

    fun hasEntry(entryId: String): Boolean {
        val node = _projection.value.activeById[entryId] ?: return false
        return !node.isFolder
    }

    fun nodeOf(nodeOrVirtualId: String): NodeRecord? {
        val key = VirtualVaultLocation.nodeIdOf(nodeOrVirtualId)
            ?: VaultEntry.entryIdOf(nodeOrVirtualId)
            ?: nodeOrVirtualId
        return _projection.value.activeById[key]
    }

    fun folderOfEntry(entryId: String): String? {
        val node = _projection.value.activeById[entryId] ?: return null
        return if (!node.isFolder && node.restoredFileId == null) node.parentFolderId else null
    }

    fun breadcrumbChainForVirtualFolder(virtualFolderId: String): Pair<String, List<PikoPathBreadcrumb>>? {
        val startId = VirtualVaultLocation.nodeIdOf(virtualFolderId) ?: return null
        val active = _projection.value.activeById
        val chain = ArrayDeque<PikoPathBreadcrumb>()
        var current = active[startId]?.takeIf { it.isFolder } ?: return null
        val visited = HashSet<String>()
        while (true) {
            if (!visited.add(current.nodeId)) return null
            chain.addFirst(PikoPathBreadcrumb(current.virtualFolderId, current.name))
            val parent = current.parentFolderId
            val parentNodeId = VirtualVaultLocation.nodeIdOf(parent) ?: return parent to chain.toList()
            current = active[parentNodeId]?.takeIf { it.isFolder } ?: return null
        }
    }

    fun coverOf(folderId: String): String? = _projection.value.coverByFolderId[folderId]

    fun subtreeUsageOf(folderId: String): Pair<Int, Long>? = _projection.value.subtreeBytesByFolderId[folderId]

    fun virtualFoldersIn(
        parentFolderId: String,
        coverUrlTransform: (String) -> String,
    ): List<FileStat> {
        val state = _projection.value
        val children = state.childrenByParent[parentFolderId].orEmpty()
        return children.asSequence()
            .filter { it.isFolder }
            .map { folder ->
                val rawCover = state.coverByFolderId[folder.virtualFolderId]
                val thumbUrl = rawCover?.let(coverUrlTransform).orEmpty()
                folder.toFolderFileStat(thumbUrl)
            }
            .toList()
    }

    fun archivedEntriesIn(parentFolderId: String): List<VaultEntry> {
        val children = _projection.value.childrenByParent[parentFolderId].orEmpty()
        return children.asSequence()
            .filter { !it.isFolder && it.restoredFileId == null }
            .map { it.toVaultEntry() }
            .toList()
    }

    fun restoredEntriesIn(parentFolderId: String): List<VaultEntry> {
        val children = _projection.value.childrenByParent[parentFolderId].orEmpty()
        return children.asSequence()
            .filter { !it.isFolder && it.restoredFileId != null }
            .map { it.toVaultEntry() }
            .toList()
    }

    fun browsableChildrenInVirtualFolder(
        virtualFolderId: String,
        vaultStars: VaultStars,
        coverUrlTransform: (String) -> String,
    ): List<FileStat> {
        val folders = virtualFoldersIn(virtualFolderId, coverUrlTransform)
        val files = archivedEntriesIn(virtualFolderId).map { entry ->
            entry.toFileStat(virtualFolderId, starred = vaultStars.isStarred(entry.id))
        }
        return folders + files
    }

    fun descendantSubtreeSnapshot(rootNodeOrVirtualId: String): List<VaultNodeSnapshot> {
        val rootKey = VirtualVaultLocation.nodeIdOf(rootNodeOrVirtualId)
            ?: VaultEntry.entryIdOf(rootNodeOrVirtualId)
            ?: rootNodeOrVirtualId
        val state = _projection.value
        val root = state.activeById[rootKey] ?: return emptyList()
        val out = ArrayList<VaultNodeSnapshot>()
        val queue = ArrayDeque<NodeRecord>()
        queue.addLast(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            out += node.toSnapshot()
            if (node.isFolder) {
                state.childrenByParent[node.virtualFolderId].orEmpty().forEach(queue::addLast)
            }
        }
        return out
    }

    fun searchLocal(
        query: String,
        parentId: String,
        vaultStars: VaultStars,
        coverUrlTransform: (String) -> String,
        realFolderBreadcrumbs: (String) -> List<String>,
        isRealDescendantOfScope: (String) -> Boolean = { true },
    ): List<SearchHit> {
        val clean = query.trim()
        if (clean.isEmpty()) return emptyList()
        val prepared = prepareCjkQuery(clean)
        val state = _projection.value
        if (state.activeById.isEmpty()) return emptyList()

        fun isUnderScope(folderId: String): Boolean {
            if (parentId.isEmpty()) return true
            var cur = folderId
            val seen = HashSet<String>()
            while (cur.isNotEmpty() && seen.add(cur)) {
                if (cur == parentId) return true
                val vNode = VirtualVaultLocation.nodeIdOf(cur)?.let { state.activeById[it] }
                if (vNode != null) {
                    cur = vNode.parentFolderId
                } else {
                    return isRealDescendantOfScope(cur)
                }
            }
            return cur == parentId
        }

        fun displayParentPath(folderId: String): List<String> {
            val chain = breadcrumbChainForVirtualFolder(folderId)
            return if (chain != null) {
                val full = realFolderBreadcrumbs(chain.first) + chain.second.map { it.name }
                if (VirtualVaultLocation.isVirtualFolder(parentId)) {
                    val scopeChain = breadcrumbChainForVirtualFolder(parentId)?.second?.size ?: 0
                    val anchorCount = realFolderBreadcrumbs(chain.first).size
                    full.drop((anchorCount + scopeChain).coerceAtMost(full.size))
                } else {
                    full
                }
            } else {
                realFolderBreadcrumbs(folderId)
            }
        }

        val hits = ArrayList<SearchHit>()
        for (node in state.activeById.values) {
            if (!node.isFolder && node.restoredFileId != null) continue
            if (!isUnderScope(node.parentFolderId)) continue
            if (prepared.score(node.name) == null) continue
            val stat = if (node.isFolder) {
                val cover = state.coverByFolderId[node.virtualFolderId]?.let(coverUrlTransform).orEmpty()
                node.toFolderFileStat(cover)
            } else {
                val entry = node.toVaultEntry()
                entry.toFileStat(node.parentFolderId, starred = vaultStars.isStarred(entry.id))
            }
            hits += SearchHit(stat, displayParentPath(node.parentFolderId))
        }
        return hits
    }

    suspend fun importTree(
        account: String,
        anchorFolderId: String,
        containerName: String?,
        items: List<InstantFileItem>,
        names: Map<String, String>,
        keepStructure: Boolean,
        source: String?,
        knownMetaLookup: (gcid: String, size: Long) -> VaultMediaMeta? = { _, _ -> null },
        nowMs: Long = Clock.System.now().toEpochMilliseconds(),
    ): Result<VirtualImportOutcome> = withContext(Dispatchers.Default) {
        withPikoLock(lock) {
            val db = store.databaseFor(account)
                ?: return@withPikoLock Result.failure(IllegalStateException("Durable database unavailable for active account"))
            runCatching {
                val readyItems = items.filter { !it.file.gcid.isNullOrBlank() }
                val currentState = _projection.value
                val ops = ArrayList<VaultOp>(readyItems.size + 16)
                val touchedFolders = LinkedHashSet<String>().apply { add(anchorFolderId) }

                val folderIdByRelPath = HashMap<String, String>()
                val existingChildrenCache = HashMap<String, MutableList<NodeRecord>>()
                fun childrenOfMutable(folderId: String): MutableList<NodeRecord> =
                    existingChildrenCache.getOrPut(folderId) {
                        currentState.childrenByParent[folderId].orEmpty().toMutableList()
                    }

                fun ensureVirtualSubfolder(parentFolderId: String, name: String): String {
                    val siblings = childrenOfMutable(parentFolderId)
                    val existing = siblings.firstOrNull { it.isFolder && it.isActive && it.name == name }
                    if (existing != null) return existing.virtualFolderId
                    val newId = PikoDurableStore.randomHexId(8)
                    ops += VaultOp.UpsertFolder(
                        nodeId = newId,
                        parentFolderId = parentFolderId,
                        name = name,
                        createdAt = nowMs,
                    )
                    val placeholder = NodeRecord(
                        nodeId = newId,
                        isFolder = true,
                        parentFolderId = parentFolderId,
                        name = name,
                        size = 0L,
                        gcid = "",
                        source = null,
                        meta = null,
                        restoredFileId = null,
                        createdAt = nowMs,
                        updatedAt = nowMs,
                        updatedClock = nowMs,
                        updatedActor = "",
                        deletedAt = null,
                        deletedClock = null,
                        deletedActor = null,
                    )
                    siblings += placeholder
                    val vFolderId = placeholder.virtualFolderId
                    touchedFolders += parentFolderId
                    touchedFolders += vFolderId
                    return vFolderId
                }

                val baseFolderId = if (!containerName.isNullOrBlank()) {
                    ensureVirtualSubfolder(anchorFolderId, containerName.trim())
                } else {
                    anchorFolderId
                }
                folderIdByRelPath[""] = baseFolderId

                if (keepStructure) {
                    val relDirs = readyItems
                        .map { it.file.path.substringBeforeLast('/', "") }
                        .filter { it.isNotEmpty() }
                        .flatMap { dir ->
                            val parts = dir.split('/')
                            parts.indices.map { i -> parts.subList(0, i + 1).joinToString("/") }
                        }
                        .distinct()
                        .sortedBy { it.count { ch -> ch == '/' } }
                    for (dir in relDirs) {
                        val parentRel = dir.substringBeforeLast('/', "")
                        val parentFolderId = folderIdByRelPath.getValue(parentRel)
                        val leafName = dir.substringAfterLast('/')
                        folderIdByRelPath[dir] = ensureVirtualSubfolder(parentFolderId, leafName)
                    }
                }

                val confirmedByPath = LinkedHashMap<String, String>(readyItems.size)
                for ((index, item) in readyItems.withIndex()) {
                    val gcid = requireNotNull(item.file.gcid)
                    val relDir = if (keepStructure) item.file.path.substringBeforeLast('/', "") else ""
                    val parentFolderId = folderIdByRelPath[relDir] ?: baseFolderId
                    val finalName = names[item.file.path] ?: item.file.name
                    val siblings = childrenOfMutable(parentFolderId)
                    val existingFile = siblings.firstOrNull {
                        !it.isFolder && it.isActive && it.restoredFileId == null &&
                            it.name == finalName && it.gcid.equals(gcid, ignoreCase = true) && it.size == item.file.size
                    }
                    if (existingFile != null) {
                        confirmedByPath[item.file.path] = existingFile.toVaultEntry().virtualId
                        continue
                    }
                    val entryId = PikoDurableStore.randomHexId(8)
                    val resolvedMeta = item.meta ?: knownMetaLookup(gcid, item.file.size)
                    val createdAt = nowMs + index
                    ops += VaultOp.UpsertFile(
                        nodeId = entryId,
                        parentFolderId = parentFolderId,
                        name = finalName,
                        size = item.file.size,
                        gcid = gcid,
                        source = source,
                        meta = resolvedMeta,
                        createdAt = createdAt,
                    )
                    val createdRecord = NodeRecord(
                        nodeId = entryId,
                        isFolder = false,
                        parentFolderId = parentFolderId,
                        name = finalName,
                        size = item.file.size,
                        gcid = gcid,
                        source = source,
                        meta = resolvedMeta,
                        restoredFileId = null,
                        createdAt = createdAt,
                        updatedAt = createdAt,
                        updatedClock = createdAt,
                        updatedActor = "",
                        deletedAt = null,
                        deletedClock = null,
                        deletedActor = null,
                    )
                    siblings += createdRecord
                    confirmedByPath[item.file.path] = createdRecord.toVaultEntry().virtualId
                    touchedFolders += parentFolderId
                }

                if (ops.isNotEmpty()) {
                    commitLocalOpsLocked(account, db, ops, nowMs)
                }
                VirtualImportOutcome(
                    containerFolderId = baseFolderId.takeIf { !containerName.isNullOrBlank() },
                    confirmedByPath = confirmedByPath,
                    touchedFolders = touchedFolders,
                )
            }
        }
    }

    suspend fun applyLocalOps(
        account: String,
        ops: List<VaultOp>,
        nowMs: Long = Clock.System.now().toEpochMilliseconds(),
    ): Result<Set<String>> = applyLocalOpsWithReport(account, ops, nowMs).map { it.touchedFolders }

    suspend fun applyLocalOpsWithReport(
        account: String,
        ops: List<VaultOp>,
        nowMs: Long = Clock.System.now().toEpochMilliseconds(),
    ): Result<LocalOpsReport> = withContext(Dispatchers.Default) {
        withPikoLock(lock) {
            if (ops.isEmpty()) return@withPikoLock Result.success(LocalOpsReport(emptySet()))
            val db = store.databaseFor(account)
                ?: return@withPikoLock Result.failure(IllegalStateException("Durable database unavailable for active account"))
            runCatching {
                commitLocalOpsLocked(account, db, ops, nowMs)
            }
        }
    }

    suspend fun applyRemoteBatches(
        account: String,
        remoteBatches: List<Pair<String, VaultOpBatch>>,
    ): Result<Set<String>> = withContext(Dispatchers.Default) {
        withPikoLock(lock) {
            val db = store.databaseFor(account)
                ?: return@withPikoLock Result.failure(IllegalStateException("Durable database unavailable for active account"))
            runCatching {
                val existingIds = loadKnownBatchIds(db)
                val unseen = remoteBatches.filterNot { (_, batch) -> batch.batchId in existingIds }
                if (unseen.isEmpty() && countUnmaterializedBatches(db) == 0) {
                    return@runCatching emptySet()
                }
                val beforeParents = _projection.value.activeById.mapValues { it.value.parentFolderId }
                var promotedAny = false
                db.transaction {
                    for ((remoteFileId, batch) in unseen) {
                        val expectedDigest = computeBatchDigest(batch.actorId, batch.actorSeq, batch.logicalClock, batch.parents, batch.ops)
                        if (!expectedDigest.equals(batch.digest, ignoreCase = true)) {
                            PikoLog.w(LEDGER_TAG, "Skipping remote vault-op batch ${batch.batchId}: digest mismatch")
                            continue
                        }
                        insertBatchRow(
                            db = db,
                            batch = batch,
                            synced = true,
                            remoteFileId = remoteFileId,
                            syncState = VaultBatchSyncState.SYNCED,
                            materialized = false,
                        )
                    }
                    promotedAny = promoteReadyBatchesAndRematerializeInTx(db)
                }
                if (promotedAny) {
                    reloadProjection(account, db)
                } else {
                    reloadSyncSummaryOnly(account, db)
                }
                if (!promotedAny) return@runCatching emptySet()
                val afterParents = _projection.value.activeById.mapValues { it.value.parentFolderId }
                val touched = LinkedHashSet<String>()
                for ((nodeId, prevParent) in beforeParents) {
                    val nextParent = afterParents[nodeId]
                    if (nextParent != prevParent) {
                        touched += prevParent
                        if (nextParent != null) touched += nextParent
                    }
                }
                for ((nodeId, nextParent) in afterParents) {
                    if (nodeId !in beforeParents) touched += nextParent
                }
                unseen.forEach { (_, b) ->
                    b.ops.forEach { op ->
                        when (op) {
                            is VaultOp.UpsertFolder -> touched += op.parentFolderId
                            is VaultOp.UpsertFile -> touched += op.parentFolderId
                            is VaultOp.MoveNode -> touched += op.newParentFolderId
                            is VaultOp.RestoreSnapshot -> op.nodes.forEach { touched += it.parentFolderId }
                            is VaultOp.RenameNode, is VaultOp.DeleteNode, is VaultOp.MarkRestored -> {
                                beforeParents[opNodeIdOf(op)]?.let(touched::add)
                                afterParents[opNodeIdOf(op)]?.let(touched::add)
                            }
                        }
                    }
                }
                touched
            }
        }
    }

    fun listUnsyncedBatches(account: String): List<StoredVaultBatch> = withPikoLock(lock) {
        val db = store.databaseFor(account) ?: return@withPikoLock emptyList()
        readBatchesWhere(db, "WHERE synced = ? AND materialized = ?", listOf(0L, 1L))
    }

    fun listAllBatches(account: String): List<StoredVaultBatch> = withPikoLock(lock) {
        val db = store.databaseFor(account) ?: return@withPikoLock emptyList()
        readBatchesWhere(db, "", emptyList())
    }

    fun hasUnmaterializedBatches(account: String): Boolean = withPikoLock(lock) {
        val db = store.databaseFor(account) ?: return@withPikoLock false
        countUnmaterializedBatches(db) > 0
    }

    fun knownBatchIds(account: String): Set<String> = withPikoLock(lock) {
        val db = store.databaseFor(account) ?: return@withPikoLock emptySet()
        loadKnownBatchIds(db) + (loadCheckpointInTx(db)?.coveredBatchIds.orEmpty())
    }

    fun markBatchSynced(account: String, batchId: String, remoteFileId: String) = withPikoLock(lock) {
        val db = store.databaseFor(account) ?: return@withPikoLock
        runCatching {
            db.execute(
                "UPDATE vault_batches SET synced = ?, remote_file_id = ?, sync_state = ? WHERE batch_id = ?",
                listOf(1L, remoteFileId, VaultBatchSyncState.SYNCED.wireName, batchId),
            )
            reloadSyncSummaryOnly(account, db)
        }.logFailure(LEDGER_TAG, "Failed to mark vault batch synced id=$batchId")
    }

    fun markBatchSyncState(account: String, batchId: String, state: VaultBatchSyncState) = withPikoLock(lock) {
        val db = store.databaseFor(account) ?: return@withPikoLock
        runCatching {
            db.execute(
                "UPDATE vault_batches SET sync_state = ? WHERE batch_id = ?",
                listOf(state.wireName, batchId),
            )
            reloadSyncSummaryOnly(account, db)
        }.logFailure(LEDGER_TAG, "Failed to update vault batch sync state id=$batchId")
    }

    suspend fun exportCheckpointSnapshot(
        account: String,
        minSyncedBatches: Int = 64,
        nowMs: Long = Clock.System.now().toEpochMilliseconds(),
    ): VaultCheckpointSnapshot? = withContext(Dispatchers.Default) {
        withPikoLock(lock) {
            val db = store.databaseFor(account) ?: return@withPikoLock null
            if (countUnmaterializedBatches(db) > 0) return@withPikoLock null
            val unsynced = readBatchesWhere(db, "WHERE synced = ?", listOf(0L))
            if (unsynced.isNotEmpty()) return@withPikoLock null
            val materialized = readBatchesWhere(db, "WHERE materialized = ?", listOf(1L))
            if (materialized.size < minSyncedBatches) return@withPikoLock null
            val existingCheckpoint = loadCheckpointInTx(db)
            val covered = LinkedHashSet<String>().apply {
                existingCheckpoint?.coveredBatchIds?.let(::addAll)
                materialized.forEach { add(it.batch.batchId) }
            }.toList()
            val leaves = currentLeafBatchIdsInTx(db)
            val maxClock = maxOf(
                existingCheckpoint?.logicalClock ?: 0L,
                materialized.maxOfOrNull { it.batch.logicalClock } ?: 0L,
            )
            val actorId = store.actorIdFor(account).orEmpty().ifBlank { "checkpoint" }
            val allNodes = loadAllNodesFromDb(db).values.map { n ->
                VaultCheckpointNode(
                    nodeId = n.nodeId,
                    isFolder = n.isFolder,
                    parentFolderId = n.parentFolderId,
                    name = n.name,
                    size = n.size,
                    gcid = n.gcid,
                    source = n.source,
                    meta = n.meta,
                    restoredFileId = n.restoredFileId,
                    createdAt = n.createdAt,
                    updatedAt = n.updatedAt,
                    updatedClock = n.updatedClock,
                    updatedActor = n.updatedActor,
                    deletedAt = n.deletedAt,
                    deletedClock = n.deletedClock,
                    deletedActor = n.deletedActor,
                )
            }
            val digest = computeSnapshotDigest(maxClock, actorId, covered, leaves, allNodes)
            VaultCheckpointSnapshot(
                schema = 1,
                logicalClock = maxClock,
                actorId = actorId,
                createdAt = nowMs,
                coveredBatchIds = covered,
                leafBatchIds = leaves,
                nodes = allNodes,
                digest = digest,
            )
        }
    }

    suspend fun applyCheckpointSnapshot(
        account: String,
        snapshot: VaultCheckpointSnapshot,
    ): Result<Set<String>> = withContext(Dispatchers.Default) {
        withPikoLock(lock) {
            val db = store.databaseFor(account)
                ?: return@withPikoLock Result.failure(IllegalStateException("Durable database unavailable for active account"))
            runCatching {
                val expected = computeSnapshotDigest(
                    snapshot.logicalClock,
                    snapshot.actorId,
                    snapshot.coveredBatchIds,
                    snapshot.leafBatchIds,
                    snapshot.nodes,
                )
                require(expected.equals(snapshot.digest, ignoreCase = true)) { "Invalid vault checkpoint digest" }
                val currentCheckpoint = loadCheckpointInTx(db)
                if (currentCheckpoint != null &&
                    snapshot.coveredBatchIds.all { it in currentCheckpoint.coveredBatchIds }
                ) {
                    return@runCatching emptySet()
                }
                val beforeIds = _projection.value.activeById.keys.toSet()
                db.transaction {
                    val mergedCovered = LinkedHashSet<String>().apply {
                        currentCheckpoint?.coveredBatchIds?.let(::addAll)
                        addAll(snapshot.coveredBatchIds)
                    }.toList()
                    val chosenSnapshot = if (currentCheckpoint == null ||
                        compareStamp(snapshot.logicalClock, snapshot.actorId, currentCheckpoint.logicalClock, currentCheckpoint.actorId) >= 0
                    ) {
                        if (mergedCovered.size == snapshot.coveredBatchIds.size) snapshot
                        else snapshot.copy(
                            coveredBatchIds = mergedCovered,
                            digest = computeSnapshotDigest(
                                snapshot.logicalClock,
                                snapshot.actorId,
                                mergedCovered,
                                snapshot.leafBatchIds,
                                snapshot.nodes,
                            ),
                        )
                    } else {
                        currentCheckpoint
                    }
                    db.execute(
                        "INSERT OR REPLACE INTO vault_checkpoint(id, snapshot_json) VALUES (1, ?)",
                        listOf(encodeSnapshot(chosenSnapshot)),
                    )
                    val coveredSet = chosenSnapshot.coveredBatchIds.toHashSet()
                    val leafSet = chosenSnapshot.leafBatchIds.toHashSet()
                    // Prune historical synced batches already subsumed by the checkpoint (retaining leaf frontier)
                    for (cid in coveredSet) {
                        if (cid !in leafSet) {
                            db.execute("DELETE FROM vault_batches WHERE batch_id = ? AND synced = 1", listOf(cid))
                        }
                    }
                    promoteReadyBatchesAndRematerializeInTx(db, forceRematerialize = true)
                }
                reloadProjection(account, db)
                val afterParents = _projection.value.activeById.mapValues { it.value.parentFolderId }
                val touched = LinkedHashSet<String>()
                for ((id, parent) in afterParents) {
                    if (id !in beforeIds) touched += parent
                }
                touched
            }
        }
    }

    fun retireCoveredSyncedBatches(account: String, snapshot: VaultCheckpointSnapshot) = withPikoLock(lock) {
        val db = store.databaseFor(account) ?: return@withPikoLock
        runCatching {
            val expected = computeSnapshotDigest(
                snapshot.logicalClock,
                snapshot.actorId,
                snapshot.coveredBatchIds,
                snapshot.leafBatchIds,
                snapshot.nodes,
            )
            if (!expected.equals(snapshot.digest, ignoreCase = true)) return@runCatching
            db.transaction {
                db.execute(
                    "INSERT OR REPLACE INTO vault_checkpoint(id, snapshot_json) VALUES (1, ?)",
                    listOf(encodeSnapshot(snapshot)),
                )
                val leafSet = snapshot.leafBatchIds.toHashSet()
                for (cid in snapshot.coveredBatchIds) {
                    if (cid !in leafSet) {
                        db.execute("DELETE FROM vault_batches WHERE batch_id = ? AND synced = 1", listOf(cid))
                    }
                }
            }
            reloadSyncSummaryOnly(account, db)
        }.logFailure(LEDGER_TAG, "Failed to record local vault checkpoint")
    }

    private fun commitLocalOpsLocked(
        account: String,
        db: PikoTreeDatabase,
        ops: List<VaultOp>,
        nowMs: Long,
    ): LocalOpsReport {
        val (actorId, actorSeq) = store.nextActorStamp(account)
            ?: error("Unable to allocate actor sequence for vault ledger")
        val prevProjection = _projection.value
        var batchResult: VaultOpBatch? = null
        var stepReport: StepApplyReport? = null
        db.transaction {
            val checkpointClock = loadCheckpointInTx(db)?.logicalClock ?: 0L
            val maxBatchClock = db.query("SELECT MAX(logical_clock) FROM vault_batches").use { cursor ->
                if (cursor.next()) cursor.long(0) ?: 0L else 0L
            }
            val logicalClock = maxOf(nowMs, maxOf(checkpointClock, maxBatchClock) + 1L)
            val parents = currentLeafBatchIdsInTx(db)
            val digest = computeBatchDigest(actorId, actorSeq, logicalClock, parents, ops)
            val batchId = "$actorId-$actorSeq-${digest.take(12)}"
            val created = VaultOpBatch(
                schema = 1,
                batchId = batchId,
                actorId = actorId,
                actorSeq = actorSeq,
                logicalClock = logicalClock,
                createdAt = nowMs,
                parents = parents,
                ops = ops,
                digest = digest,
            )
            insertBatchRow(
                db = db,
                batch = created,
                synced = false,
                remoteFileId = null,
                syncState = VaultBatchSyncState.PENDING,
                materialized = true,
            )
            stepReport = applyBatchIncrementallyInTx(db, created)
            batchResult = created
        }
        val report = requireNotNull(stepReport)
        requireNotNull(batchResult)
        if (prevProjection.account == account && !report.requiresFullProjectionRebuild) {
            applyIncrementalProjectionLocked(account, db, prevProjection, report)
        } else {
            reloadProjection(account, db)
        }
        _localBatchSignal.tryEmit(account)
        return LocalOpsReport(
            touchedFolders = report.touchedFolders,
            skippedMoveIds = report.skippedMoveIds,
        )
    }

    private data class StepApplyReport(
        val touchedFolders: Set<String>,
        val skippedMoveIds: Set<String>,
        val updatedActiveNodes: Map<String, NodeRecord?>,
        val dirtyParentFolders: Set<String>,
        val requiresFullProjectionRebuild: Boolean,
    )

    private fun applyBatchIncrementallyInTx(db: PikoTreeDatabase, batch: VaultOpBatch): StepApplyReport {
        val touched = LinkedHashSet<String>()
        val skippedMoves = LinkedHashSet<String>()
        val updatedActive = LinkedHashMap<String, NodeRecord?>()
        val dirtyParents = LinkedHashSet<String>()
        var needsFullRebuild = false
        val clock = batch.logicalClock
        val actor = batch.actorId

        for (op in batch.ops) {
            when (op) {
                is VaultOp.UpsertFolder -> {
                    val existing = loadNodeInTx(db, op.nodeId)
                    if (existing != null) dirtyParents += existing.parentFolderId
                    val record = NodeRecord(
                        nodeId = op.nodeId,
                        isFolder = true,
                        parentFolderId = op.parentFolderId,
                        name = op.name,
                        size = 0L,
                        gcid = "",
                        source = null,
                        meta = null,
                        restoredFileId = null,
                        createdAt = existing?.createdAt ?: op.createdAt,
                        updatedAt = op.createdAt,
                        updatedClock = clock,
                        updatedActor = actor,
                        deletedAt = null,
                        deletedClock = null,
                        deletedActor = null,
                    )
                    upsertNodeRowInTx(db, record)
                    updatedActive[record.nodeId] = record
                    dirtyParents += op.parentFolderId
                    dirtyParents += record.virtualFolderId
                    touched += op.parentFolderId
                }
                is VaultOp.UpsertFile -> {
                    val existing = loadNodeInTx(db, op.nodeId)
                    if (existing != null) dirtyParents += existing.parentFolderId
                    val record = NodeRecord(
                        nodeId = op.nodeId,
                        isFolder = false,
                        parentFolderId = op.parentFolderId,
                        name = op.name,
                        size = op.size,
                        gcid = op.gcid,
                        source = op.source,
                        meta = op.meta,
                        restoredFileId = existing?.restoredFileId,
                        createdAt = existing?.createdAt ?: op.createdAt,
                        updatedAt = op.createdAt,
                        updatedClock = clock,
                        updatedActor = actor,
                        deletedAt = null,
                        deletedClock = null,
                        deletedActor = null,
                    )
                    upsertNodeRowInTx(db, record)
                    updatedActive[record.nodeId] = record
                    dirtyParents += op.parentFolderId
                    touched += op.parentFolderId
                }
                is VaultOp.RenameNode -> {
                    val existing = loadNodeInTx(db, op.nodeId) ?: continue
                    if (!existing.isActive) continue
                    val record = existing.copy(
                        name = op.newName,
                        updatedAt = op.updatedAt,
                        updatedClock = clock,
                        updatedActor = actor,
                    )
                    upsertNodeRowInTx(db, record)
                    updatedActive[record.nodeId] = record
                    dirtyParents += existing.parentFolderId
                    touched += existing.parentFolderId
                }
                is VaultOp.MoveNode -> {
                    val existing = loadNodeInTx(db, op.nodeId)
                    if (existing == null || !existing.isActive) {
                        skippedMoves += op.nodeId
                        continue
                    }
                    if (VirtualVaultLocation.isVirtualFolder(op.newParentFolderId)) {
                        val destId = VirtualVaultLocation.nodeIdOf(op.newParentFolderId)
                        val destNode = destId?.let { loadNodeInTx(db, it) }
                        if (destNode == null || !destNode.isFolder || !destNode.isActive) {
                            skippedMoves += op.nodeId
                            continue
                        }
                    }
                    if (existing.isFolder && createsCycleInTx(db, op.nodeId, op.newParentFolderId)) {
                        skippedMoves += op.nodeId
                        continue
                    }
                    val record = existing.copy(
                        parentFolderId = op.newParentFolderId,
                        updatedAt = op.updatedAt,
                        updatedClock = clock,
                        updatedActor = actor,
                    )
                    upsertNodeRowInTx(db, record)
                    updatedActive[record.nodeId] = record
                    dirtyParents += existing.parentFolderId
                    dirtyParents += op.newParentFolderId
                    touched += existing.parentFolderId
                    touched += op.newParentFolderId
                    if (existing.isFolder) needsFullRebuild = true
                }
                is VaultOp.DeleteNode -> {
                    val targets = collectSubtreeIdsInTx(db, op.nodeId)
                    for (targetId in targets) {
                        val existing = loadNodeInTx(db, targetId) ?: continue
                        if (existing.isFolder) needsFullRebuild = true
                        val tombstoned = existing.copy(
                            deletedAt = op.deletedAt,
                            deletedClock = clock,
                            deletedActor = actor,
                        )
                        upsertNodeRowInTx(db, tombstoned)
                        updatedActive[targetId] = null
                        dirtyParents += existing.parentFolderId
                        touched += existing.parentFolderId
                    }
                }
                is VaultOp.RestoreSnapshot -> {
                    needsFullRebuild = true
                    for (snap in op.nodes) {
                        val existing = loadNodeInTx(db, snap.nodeId)
                        val record = if (existing == null) {
                            NodeRecord(
                                nodeId = snap.nodeId,
                                isFolder = snap.isFolder,
                                parentFolderId = snap.parentFolderId,
                                name = snap.name,
                                size = snap.size,
                                gcid = snap.gcid,
                                source = snap.source,
                                meta = snap.meta,
                                restoredFileId = snap.restoredFileId,
                                createdAt = snap.createdAt,
                                updatedAt = op.restoredAt,
                                updatedClock = clock,
                                updatedActor = actor,
                                deletedAt = null,
                                deletedClock = null,
                                deletedActor = null,
                            )
                        } else if (!existing.isActive) {
                            existing.copy(
                                parentFolderId = snap.parentFolderId,
                                name = snap.name,
                                restoredFileId = snap.restoredFileId,
                                updatedAt = op.restoredAt,
                                updatedClock = clock,
                                updatedActor = actor,
                                deletedAt = null,
                                deletedClock = null,
                                deletedActor = null,
                            )
                        } else {
                            existing
                        }
                        upsertNodeRowInTx(db, record)
                        updatedActive[record.nodeId] = record
                        dirtyParents += record.parentFolderId
                        touched += record.parentFolderId
                    }
                }
                is VaultOp.MarkRestored -> {
                    val existing = loadNodeInTx(db, op.nodeId) ?: continue
                    if (!existing.isActive) continue
                    val record = existing.copy(
                        restoredFileId = op.restoredFileId,
                        updatedAt = op.updatedAt,
                        updatedClock = clock,
                        updatedActor = actor,
                    )
                    upsertNodeRowInTx(db, record)
                    updatedActive[record.nodeId] = record
                    dirtyParents += existing.parentFolderId
                    touched += existing.parentFolderId
                }
            }
        }
        return StepApplyReport(touched, skippedMoves, updatedActive, dirtyParents, needsFullRebuild)
    }

    private fun promoteReadyBatchesAndRematerializeInTx(
        db: PikoTreeDatabase,
        forceRematerialize: Boolean = false,
    ): Boolean {
        val checkpoint = loadCheckpointInTx(db)
        val allStored = readBatchesWhere(db, "", emptyList())
        val materializedIds = HashSet<String>().apply {
            checkpoint?.coveredBatchIds?.let(::addAll)
            allStored.filter { it.materialized }.forEach { add(it.batch.batchId) }
        }
        val newlyPromoted = ArrayList<String>()
        var progress = true
        while (progress) {
            progress = false
            for (stored in allStored) {
                if (stored.batch.batchId in materializedIds) continue
                if (stored.batch.parents.all { it in materializedIds }) {
                    materializedIds += stored.batch.batchId
                    newlyPromoted += stored.batch.batchId
                    progress = true
                }
            }
        }
        if (newlyPromoted.isEmpty() && !forceRematerialize) return false
        for (id in newlyPromoted) {
            db.execute("UPDATE vault_batches SET materialized = 1 WHERE batch_id = ?", listOf(id))
        }

        val readyBatches = allStored
            .filter { it.batch.batchId in materializedIds }
            .map { (it.remoteFileId.orEmpty()) to it.batch }
        val canonicalBatches = sortTopologically(readyBatches).map { it.second }
        val nodesById = LinkedHashMap<String, NodeRecord>()
        val childrenIndex = HashMap<String, LinkedHashSet<String>>()

        fun putMemNode(record: NodeRecord) {
            val prev = nodesById.put(record.nodeId, record)
            if (prev != null && prev.parentFolderId != record.parentFolderId) {
                childrenIndex[prev.parentFolderId]?.remove(record.nodeId)
            }
            childrenIndex.getOrPut(record.parentFolderId) { LinkedHashSet() } += record.nodeId
        }

        checkpoint?.nodes?.forEach { snap ->
            putMemNode(
                NodeRecord(
                    nodeId = snap.nodeId,
                    isFolder = snap.isFolder,
                    parentFolderId = snap.parentFolderId,
                    name = snap.name,
                    size = snap.size,
                    gcid = snap.gcid,
                    source = snap.source,
                    meta = snap.meta,
                    restoredFileId = snap.restoredFileId,
                    createdAt = snap.createdAt,
                    updatedAt = snap.updatedAt,
                    updatedClock = snap.updatedClock,
                    updatedActor = snap.updatedActor,
                    deletedAt = snap.deletedAt,
                    deletedClock = snap.deletedClock,
                    deletedActor = snap.deletedActor,
                ),
            )
        }

        fun createsCycleInMem(movingFolderNodeId: String, newParentFolderId: String): Boolean {
            var cur = VirtualVaultLocation.nodeIdOf(newParentFolderId) ?: return false
            val visited = HashSet<String>()
            while (visited.add(cur)) {
                if (cur == movingFolderNodeId) return true
                val parentNode = nodesById[cur] ?: return false
                if (!parentNode.isActive) return true
                cur = VirtualVaultLocation.nodeIdOf(parentNode.parentFolderId) ?: return false
            }
            return true
        }

        fun collectSubtreeInMem(rootNodeId: String): List<String> {
            val out = ArrayList<String>()
            val queue = ArrayDeque<String>()
            queue.addLast(rootNodeId)
            val seen = HashSet<String>()
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                if (!seen.add(id)) continue
                val n = nodesById[id] ?: continue
                out += id
                if (n.isFolder) {
                    childrenIndex[n.virtualFolderId]?.forEach(queue::addLast)
                }
            }
            return out
        }

        val coveredByCheckpoint = checkpoint?.coveredBatchIds?.toHashSet().orEmpty()
        for (batch in canonicalBatches) {
            if (batch.batchId in coveredByCheckpoint && batch.ops.isEmpty()) continue
            if (batch.batchId in coveredByCheckpoint) continue
            val clock = batch.logicalClock
            val actor = batch.actorId
            for (op in batch.ops) {
                when (op) {
                    is VaultOp.UpsertFolder -> {
                        val existing = nodesById[op.nodeId]
                        putMemNode(
                            NodeRecord(
                                nodeId = op.nodeId,
                                isFolder = true,
                                parentFolderId = op.parentFolderId,
                                name = op.name,
                                size = 0L,
                                gcid = "",
                                source = null,
                                meta = null,
                                restoredFileId = null,
                                createdAt = existing?.createdAt ?: op.createdAt,
                                updatedAt = op.createdAt,
                                updatedClock = clock,
                                updatedActor = actor,
                                deletedAt = null,
                                deletedClock = null,
                                deletedActor = null,
                            ),
                        )
                    }
                    is VaultOp.UpsertFile -> {
                        val existing = nodesById[op.nodeId]
                        putMemNode(
                            NodeRecord(
                                nodeId = op.nodeId,
                                isFolder = false,
                                parentFolderId = op.parentFolderId,
                                name = op.name,
                                size = op.size,
                                gcid = op.gcid,
                                source = op.source,
                                meta = op.meta,
                                restoredFileId = existing?.restoredFileId,
                                createdAt = existing?.createdAt ?: op.createdAt,
                                updatedAt = op.createdAt,
                                updatedClock = clock,
                                updatedActor = actor,
                                deletedAt = null,
                                deletedClock = null,
                                deletedActor = null,
                            ),
                        )
                    }
                    is VaultOp.RenameNode -> {
                        val existing = nodesById[op.nodeId] ?: continue
                        if (!existing.isActive) continue
                        putMemNode(
                            existing.copy(
                                name = op.newName,
                                updatedAt = op.updatedAt,
                                updatedClock = clock,
                                updatedActor = actor,
                            ),
                        )
                    }
                    is VaultOp.MoveNode -> {
                        val existing = nodesById[op.nodeId] ?: continue
                        if (!existing.isActive) continue
                        if (VirtualVaultLocation.isVirtualFolder(op.newParentFolderId)) {
                            val destId = VirtualVaultLocation.nodeIdOf(op.newParentFolderId)
                            val destNode = destId?.let { nodesById[it] }
                            if (destNode == null || !destNode.isFolder || !destNode.isActive) continue
                        }
                        if (existing.isFolder && createsCycleInMem(op.nodeId, op.newParentFolderId)) continue
                        putMemNode(
                            existing.copy(
                                parentFolderId = op.newParentFolderId,
                                updatedAt = op.updatedAt,
                                updatedClock = clock,
                                updatedActor = actor,
                            ),
                        )
                    }
                    is VaultOp.DeleteNode -> {
                        for (targetId in collectSubtreeInMem(op.nodeId)) {
                            val existing = nodesById[targetId] ?: continue
                            putMemNode(
                                existing.copy(
                                    deletedAt = op.deletedAt,
                                    deletedClock = clock,
                                    deletedActor = actor,
                                ),
                            )
                        }
                    }
                    is VaultOp.RestoreSnapshot -> {
                        for (snap in op.nodes) {
                            val existing = nodesById[snap.nodeId]
                            val revived = if (existing == null) {
                                NodeRecord(
                                    nodeId = snap.nodeId,
                                    isFolder = snap.isFolder,
                                    parentFolderId = snap.parentFolderId,
                                    name = snap.name,
                                    size = snap.size,
                                    gcid = snap.gcid,
                                    source = snap.source,
                                    meta = snap.meta,
                                    restoredFileId = snap.restoredFileId,
                                    createdAt = snap.createdAt,
                                    updatedAt = op.restoredAt,
                                    updatedClock = clock,
                                    updatedActor = actor,
                                    deletedAt = null,
                                    deletedClock = null,
                                    deletedActor = null,
                                )
                            } else if (!existing.isActive) {
                                existing.copy(
                                    parentFolderId = snap.parentFolderId,
                                    name = snap.name,
                                    restoredFileId = snap.restoredFileId,
                                    updatedAt = op.restoredAt,
                                    updatedClock = clock,
                                    updatedActor = actor,
                                    deletedAt = null,
                                    deletedClock = null,
                                    deletedActor = null,
                                )
                            } else {
                                existing
                            }
                            putMemNode(revived)
                        }
                    }
                    is VaultOp.MarkRestored -> {
                        val existing = nodesById[op.nodeId] ?: continue
                        if (!existing.isActive) continue
                        putMemNode(
                            existing.copy(
                                restoredFileId = op.restoredFileId,
                                updatedAt = op.updatedAt,
                                updatedClock = clock,
                                updatedActor = actor,
                            ),
                        )
                    }
                }
            }
        }

        db.execute("DELETE FROM vault_nodes")
        for (node in nodesById.values) {
            upsertNodeRowInTx(db, node)
        }
        return true
    }

    private fun upsertNodeRowInTx(db: PikoTreeDatabase, node: NodeRecord) {
        val metaJson = node.meta?.let { json.encodeToString(VaultMediaMeta.serializer(), it) }
        db.execute(
            """
            INSERT OR REPLACE INTO vault_nodes(
                node_id, kind, parent_ref, name, size, gcid, source, meta_json, restored_file_id,
                created_at, updated_at, updated_clock, updated_actor, deleted_at, deleted_clock, deleted_actor
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf(
                node.nodeId,
                if (node.isFolder) "FOLDER" else "FILE",
                toParentRef(node.parentFolderId),
                node.name,
                node.size,
                node.gcid,
                node.source,
                metaJson,
                node.restoredFileId,
                node.createdAt,
                node.updatedAt,
                node.updatedClock,
                node.updatedActor,
                node.deletedAt,
                node.deletedClock,
                node.deletedActor,
            ),
        )
    }

    private fun currentLeafBatchIdsInTx(db: PikoTreeDatabase): List<String> {
        val allIds = LinkedHashSet<String>()
        val referencedParents = HashSet<String>()
        loadCheckpointInTx(db)?.leafBatchIds?.forEach(allIds::add)
        db.query("SELECT batch_id, parents_json FROM vault_batches WHERE materialized = 1 ORDER BY logical_clock ASC").use { cursor ->
            while (cursor.next()) {
                val id = cursor.string(0) ?: continue
                val parentsRaw = cursor.string(1).orEmpty()
                allIds += id
                if (parentsRaw.isNotBlank()) {
                    runCatching { json.decodeFromString(stringListSerializer, parentsRaw) }
                        .getOrNull()
                        ?.forEach(referencedParents::add)
                }
            }
        }
        return allIds.filterNot { it in referencedParents }
    }

    private fun insertBatchRow(
        db: PikoTreeDatabase,
        batch: VaultOpBatch,
        synced: Boolean,
        remoteFileId: String?,
        syncState: VaultBatchSyncState,
        materialized: Boolean = true,
    ) {
        db.execute(
            """
            INSERT OR IGNORE INTO vault_batches(
                batch_id, actor_id, actor_seq, logical_clock, parents_json, ops_json, digest,
                synced, remote_file_id, sync_state, created_at, materialized
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf(
                batch.batchId,
                batch.actorId,
                batch.actorSeq,
                batch.logicalClock,
                json.encodeToString(stringListSerializer, batch.parents),
                json.encodeToString(opListSerializer, batch.ops),
                batch.digest,
                if (synced) 1L else 0L,
                remoteFileId,
                syncState.wireName,
                batch.createdAt,
                if (materialized) 1L else 0L,
            ),
        )
    }

    private fun createsCycleInTx(db: PikoTreeDatabase, movingFolderNodeId: String, newParentFolderId: String): Boolean {
        var cur = VirtualVaultLocation.nodeIdOf(newParentFolderId) ?: return false
        val visited = HashSet<String>()
        while (visited.add(cur)) {
            if (cur == movingFolderNodeId) return true
            val parentNode = loadNodeInTx(db, cur) ?: return false
            if (!parentNode.isActive) return true
            cur = VirtualVaultLocation.nodeIdOf(parentNode.parentFolderId) ?: return false
        }
        return true
    }

    private fun collectSubtreeIdsInTx(db: PikoTreeDatabase, rootNodeId: String): List<String> {
        val result = ArrayList<String>()
        val queue = ArrayDeque<String>()
        queue.addLast(rootNodeId)
        val seen = HashSet<String>()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!seen.add(id)) continue
            val node = loadNodeInTx(db, id) ?: continue
            result += id
            if (node.isFolder) {
                db.query(
                    "SELECT node_id FROM vault_nodes WHERE parent_ref = ?",
                    listOf(node.virtualFolderId),
                ).use { cursor ->
                    while (cursor.next()) {
                        cursor.string(0)?.let(queue::addLast)
                    }
                }
            }
        }
        return result
    }

    private fun loadNodeInTx(db: PikoTreeDatabase, nodeId: String): NodeRecord? {
        db.query(
            """
            SELECT node_id, kind, parent_ref, name, size, gcid, source, meta_json, restored_file_id,
                   created_at, updated_at, updated_clock, updated_actor, deleted_at, deleted_clock, deleted_actor
            FROM vault_nodes
            WHERE node_id = ?
            """.trimIndent(),
            listOf(nodeId),
        ).use { cursor ->
            if (!cursor.next()) return null
            return parseNodeRow(cursor)
        }
    }

    private fun loadAllNodesFromDb(db: PikoTreeDatabase): Map<String, NodeRecord> {
        val out = LinkedHashMap<String, NodeRecord>()
        db.query(
            """
            SELECT node_id, kind, parent_ref, name, size, gcid, source, meta_json, restored_file_id,
                   created_at, updated_at, updated_clock, updated_actor, deleted_at, deleted_clock, deleted_actor
            FROM vault_nodes
            ORDER BY created_at ASC, node_id ASC
            """.trimIndent(),
        ).use { cursor ->
            while (cursor.next()) {
                val n = parseNodeRow(cursor) ?: continue
                out[n.nodeId] = n
            }
        }
        return out
    }

    private fun loadCheckpointInTx(db: PikoTreeDatabase): VaultCheckpointSnapshot? {
        val raw = db.scalarString("SELECT snapshot_json FROM vault_checkpoint WHERE id = 1", emptyList())
            ?.takeIf { it.isNotBlank() } ?: return null
        return decodeSnapshot(raw)
    }

    private fun countUnmaterializedBatches(db: PikoTreeDatabase): Int {
        db.query("SELECT COUNT(*) FROM vault_batches WHERE materialized = 0").use { cursor ->
            return if (cursor.next()) (cursor.long(0) ?: 0L).toInt() else 0
        }
    }

    private fun parseNodeRow(cursor: PikoTreeCursor): NodeRecord? {
        val nodeId = cursor.string(0) ?: return null
        val kind = cursor.string(1) ?: return null
        val parentRef = cursor.string(2) ?: return null
        val name = cursor.string(3) ?: return null
        val size = cursor.long(4) ?: 0L
        val gcid = cursor.string(5).orEmpty()
        val source = cursor.string(6)
        val meta = cursor.string(7)?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.decodeFromString(VaultMediaMeta.serializer(), it) }.getOrNull()
        }
        val restoredFileId = cursor.string(8)
        val createdAt = cursor.long(9) ?: 0L
        val updatedAt = cursor.long(10) ?: createdAt
        val updatedClock = cursor.long(11) ?: 0L
        val updatedActor = cursor.string(12).orEmpty()
        val deletedAt = cursor.long(13)
        val deletedClock = cursor.long(14)
        val deletedActor = cursor.string(15)
        return NodeRecord(
            nodeId = nodeId,
            isFolder = kind == "FOLDER",
            parentFolderId = fromParentRef(parentRef),
            name = name,
            size = size,
            gcid = gcid,
            source = source,
            meta = meta,
            restoredFileId = restoredFileId,
            createdAt = createdAt,
            updatedAt = updatedAt,
            updatedClock = updatedClock,
            updatedActor = updatedActor,
            deletedAt = deletedAt,
            deletedClock = deletedClock,
            deletedActor = deletedActor,
        )
    }

    private fun reloadSyncSummaryOnly(account: String, db: PikoTreeDatabase) {
        val (unsyncedCount, unmaterializedCount, syncState) = querySyncSummary(db)
        _projection.value = _projection.value.copy(
            account = account,
            unsyncedCount = unsyncedCount,
            unmaterializedCount = unmaterializedCount,
            syncState = syncState,
        )
    }

    private fun querySyncSummary(db: PikoTreeDatabase): Triple<Int, Int, VaultBatchSyncState> {
        var unsynced = 0
        var hasSpaceFull = false
        db.query("SELECT sync_state FROM vault_batches WHERE synced = ? AND materialized = 1", listOf(0L)).use { cursor ->
            while (cursor.next()) {
                unsynced++
                if (cursor.string(0) == VaultBatchSyncState.SPACE_FULL.wireName) {
                    hasSpaceFull = true
                }
            }
        }
        val unmaterialized = countUnmaterializedBatches(db)
        val state = when {
            hasSpaceFull -> VaultBatchSyncState.SPACE_FULL
            unsynced > 0 || unmaterialized > 0 -> VaultBatchSyncState.PENDING
            else -> VaultBatchSyncState.SYNCED
        }
        return Triple(unsynced, unmaterialized, state)
    }

    private fun fileCoverToken(file: NodeRecord): String? {
        if (file.gcid.isBlank() || file.restoredFileId != null) return null
        val probe = FileStat(
            id = "",
            kind = FileKind.FILE,
            name = file.name,
            mimeType = file.meta?.mimeType.orEmpty(),
        )
        return when {
            probe.isPlayableVideo() -> file.gcid
            probe.isPreviewableImage() -> "img:${file.gcid}"
            else -> null
        }
    }

    private fun applyIncrementalProjectionLocked(
        account: String,
        db: PikoTreeDatabase,
        prev: Projection,
        report: StepApplyReport,
    ) {
        val nextActive = LinkedHashMap<String, NodeRecord>(prev.activeById.size + report.updatedActiveNodes.size)
        nextActive.putAll(prev.activeById)
        for ((nodeId, updated) in report.updatedActiveNodes) {
            if (updated == null || !updated.isActive) {
                nextActive.remove(nodeId)
            } else {
                var parent = updated.parentFolderId
                var reachable = true
                val seen = HashSet<String>()
                while (VirtualVaultLocation.isVirtualFolder(parent)) {
                    if (!seen.add(parent)) { reachable = false; break }
                    val pNode = VirtualVaultLocation.nodeIdOf(parent)?.let { nextActive[it] }
                    if (pNode == null || !pNode.isFolder) { reachable = false; break }
                    parent = pNode.parentFolderId
                }
                if (reachable) nextActive[nodeId] = updated else nextActive.remove(nodeId)
            }
        }

        val nextChildren = LinkedHashMap<String, List<NodeRecord>>(prev.childrenByParent)
        val nextRealWithVault = LinkedHashSet<String>(prev.realFoldersWithVault)
        val nodeComparator = compareBy<NodeRecord> { it.createdAt }.thenBy { it.nodeId }

        val affectedAncestors = LinkedHashSet<String>()
        fun markFolderAndAncestors(folderId: String) {
            var cur = folderId
            val seen = HashSet<String>()
            while (seen.add(cur)) {
                affectedAncestors += cur
                if (!VirtualVaultLocation.isVirtualFolder(cur)) break
                val pNode = VirtualVaultLocation.nodeIdOf(cur)?.let { nextActive[it] } ?: break
                cur = pNode.parentFolderId
            }
        }
        report.dirtyParentFolders.forEach(::markFolderAndAncestors)

        for (dirtyParent in report.dirtyParentFolders) {
            val baseList = ArrayList<NodeRecord>()
            prev.childrenByParent[dirtyParent].orEmpty().forEach { child ->
                if (child.nodeId !in report.updatedActiveNodes) {
                    if (child.nodeId in nextActive) baseList += child
                }
            }
            for ((nodeId, updated) in report.updatedActiveNodes) {
                if (updated != null && nodeId in nextActive && updated.parentFolderId == dirtyParent) {
                    baseList += updated
                }
            }
            baseList.sortWith(nodeComparator)
            if (baseList.isEmpty()) {
                nextChildren.remove(dirtyParent)
                if (!VirtualVaultLocation.isVirtualFolder(dirtyParent)) {
                    nextRealWithVault.remove(dirtyParent)
                }
            } else {
                nextChildren[dirtyParent] = baseList
                if (!VirtualVaultLocation.isVirtualFolder(dirtyParent)) {
                    if (baseList.any { it.isFolder || it.restoredFileId == null }) {
                        nextRealWithVault += dirtyParent
                    } else {
                        nextRealWithVault.remove(dirtyParent)
                    }
                }
            }
        }

        val nextCovers = HashMap<String, String>(prev.coverByFolderId)
        val nextSubtreeBytes = HashMap<String, Pair<Int, Long>>(prev.subtreeBytesByFolderId)
        affectedAncestors.forEach {
            nextCovers.remove(it)
            nextSubtreeBytes.remove(it)
        }

        val visiting = HashSet<String>()
        fun recomputeFolder(folderId: String): Pair<Pair<Int, Long>, String?> {
            nextSubtreeBytes[folderId]?.let { cached -> return cached to nextCovers[folderId] }
            if (!visiting.add(folderId)) return (0 to 0L) to null
            val kids = nextChildren[folderId].orEmpty()
            if (kids.isEmpty()) {
                visiting.remove(folderId)
                return (0 to 0L) to null
            }
            var count = 0
            var bytes = 0L
            var firstVideo: String? = null
            var firstImage: String? = null
            for (child in kids) {
                if (!child.isFolder && child.restoredFileId == null) {
                    count += 1
                    bytes += child.size
                    val tok = fileCoverToken(child)
                    if (tok != null) {
                        if (tok.startsWith("img:")) {
                            if (firstImage == null) firstImage = tok
                        } else if (firstVideo == null) {
                            firstVideo = tok
                        }
                    }
                }
            }
            for (child in kids) {
                if (child.isFolder) {
                    val (subUsage, subCover) = recomputeFolder(child.virtualFolderId)
                    count += subUsage.first
                    bytes += subUsage.second
                    if (subCover != null) {
                        if (subCover.startsWith("img:")) {
                            if (firstImage == null) firstImage = subCover
                        } else if (firstVideo == null) {
                            firstVideo = subCover
                        }
                    }
                }
            }
            visiting.remove(folderId)
            val chosen = firstVideo ?: firstImage
            if (chosen != null) nextCovers[folderId] = chosen
            val usage = count to bytes
            nextSubtreeBytes[folderId] = usage
            return usage to chosen
        }

        for (folderId in affectedAncestors) {
            if (folderId in nextChildren) {
                recomputeFolder(folderId)
            }
        }

        val (unsyncedCount, unmaterializedCount, syncState) = querySyncSummary(db)
        _projection.value = Projection(
            account = account,
            activeById = nextActive,
            childrenByParent = nextChildren,
            coverByFolderId = nextCovers,
            realFoldersWithVault = nextRealWithVault,
            subtreeBytesByFolderId = nextSubtreeBytes,
            unsyncedCount = unsyncedCount,
            unmaterializedCount = unmaterializedCount,
            syncState = syncState,
        )
    }

    private fun reloadProjection(account: String, db: PikoTreeDatabase) {
        val activeById = LinkedHashMap<String, NodeRecord>()
        val rawChildren = LinkedHashMap<String, MutableList<NodeRecord>>()
        db.query(
            """
            SELECT node_id, kind, parent_ref, name, size, gcid, source, meta_json, restored_file_id,
                   created_at, updated_at, updated_clock, updated_actor, deleted_at, deleted_clock, deleted_actor
            FROM vault_nodes
            WHERE deleted_at IS NULL
            ORDER BY created_at ASC, node_id ASC
            """.trimIndent(),
        ).use { cursor ->
            while (cursor.next()) {
                val node = parseNodeRow(cursor) ?: continue
                activeById[node.nodeId] = node
                rawChildren.getOrPut(node.parentFolderId) { ArrayList() } += node
            }
        }

        val reachableActive = LinkedHashMap<String, NodeRecord>(activeById.size)
        for (node in activeById.values) {
            var parent = node.parentFolderId
            var valid = true
            val seen = HashSet<String>()
            while (VirtualVaultLocation.isVirtualFolder(parent)) {
                if (!seen.add(parent)) {
                    valid = false
                    break
                }
                val pNode = VirtualVaultLocation.nodeIdOf(parent)?.let { activeById[it] }
                if (pNode == null || !pNode.isFolder) {
                    valid = false
                    break
                }
                parent = pNode.parentFolderId
            }
            if (valid) reachableActive[node.nodeId] = node
        }

        val childrenByParent = LinkedHashMap<String, List<NodeRecord>>()
        val realFoldersWithVault = LinkedHashSet<String>()
        for ((parentFolderId, list) in rawChildren) {
            val filtered = list.filter { it.nodeId in reachableActive }
            if (filtered.isEmpty()) continue
            childrenByParent[parentFolderId] = filtered
            if (!VirtualVaultLocation.isVirtualFolder(parentFolderId)) {
                if (filtered.any { it.isFolder || it.restoredFileId == null }) {
                    realFoldersWithVault += parentFolderId
                }
            }
        }

        val coverByFolderId = HashMap<String, String>()
        val subtreeBytesByFolderId = HashMap<String, Pair<Int, Long>>()

        val visiting = HashSet<String>()
        fun computeFolderMetrics(folderId: String): Pair<Pair<Int, Long>, String?> {
            subtreeBytesByFolderId[folderId]?.let { cached ->
                return cached to coverByFolderId[folderId]
            }
            if (!visiting.add(folderId)) return (0 to 0L) to null
            var count = 0
            var bytes = 0L
            var firstVideoCover: String? = null
            var firstImageCover: String? = null
            val kids = childrenByParent[folderId].orEmpty()
            for (child in kids) {
                if (!child.isFolder) {
                    if (child.restoredFileId == null) {
                        count += 1
                        bytes += child.size
                        val tok = fileCoverToken(child)
                        if (tok != null) {
                            if (tok.startsWith("img:")) {
                                if (firstImageCover == null) firstImageCover = tok
                            } else if (firstVideoCover == null) {
                                firstVideoCover = tok
                            }
                        }
                    }
                }
            }
            for (child in kids) {
                if (child.isFolder) {
                    val (subUsage, subCover) = computeFolderMetrics(child.virtualFolderId)
                    count += subUsage.first
                    bytes += subUsage.second
                    if (subCover != null) {
                        if (subCover.startsWith("img:")) {
                            if (firstImageCover == null) firstImageCover = subCover
                        } else if (firstVideoCover == null) {
                            firstVideoCover = subCover
                        }
                    }
                }
            }
            visiting.remove(folderId)
            val chosenCover = firstVideoCover ?: firstImageCover
            if (chosenCover != null) coverByFolderId[folderId] = chosenCover
            val usage = count to bytes
            subtreeBytesByFolderId[folderId] = usage
            return usage to chosenCover
        }

        for (folderId in childrenByParent.keys) {
            computeFolderMetrics(folderId)
        }

        val (unsyncedCount, unmaterializedCount, syncState) = querySyncSummary(db)
        _projection.value = Projection(
            account = account,
            activeById = reachableActive,
            childrenByParent = childrenByParent,
            coverByFolderId = coverByFolderId,
            realFoldersWithVault = realFoldersWithVault,
            subtreeBytesByFolderId = subtreeBytesByFolderId,
            unsyncedCount = unsyncedCount,
            unmaterializedCount = unmaterializedCount,
            syncState = syncState,
        )
    }

    private fun loadKnownBatchIds(db: PikoTreeDatabase): Set<String> {
        val ids = HashSet<String>()
        db.query("SELECT batch_id FROM vault_batches").use { cursor ->
            while (cursor.next()) {
                cursor.string(0)?.let(ids::add)
            }
        }
        return ids
    }

    private fun readBatchesWhere(
        db: PikoTreeDatabase,
        whereClause: String,
        params: List<Any?>,
    ): List<StoredVaultBatch> {
        val out = ArrayList<StoredVaultBatch>()
        db.query(
            """
            SELECT batch_id, actor_id, actor_seq, logical_clock, parents_json, ops_json, digest,
                   synced, remote_file_id, sync_state, created_at, materialized
            FROM vault_batches
            $whereClause
            ORDER BY logical_clock ASC, actor_id ASC, actor_seq ASC
            """.trimIndent(),
            params,
        ).use { cursor ->
            while (cursor.next()) {
                val batchId = cursor.string(0) ?: continue
                val actorId = cursor.string(1) ?: continue
                val actorSeq = cursor.long(2) ?: 0L
                val logicalClock = cursor.long(3) ?: 0L
                val parents = runCatching {
                    json.decodeFromString(stringListSerializer, cursor.string(4).orEmpty())
                }.getOrDefault(emptyList())
                val ops = runCatching {
                    json.decodeFromString(opListSerializer, cursor.string(5).orEmpty())
                }.getOrNull() ?: continue
                val digest = cursor.string(6).orEmpty()
                val synced = (cursor.long(7) ?: 0L) != 0L
                val remoteFileId = cursor.string(8)
                val rawSyncState = cursor.string(9).orEmpty()
                val createdAt = cursor.long(10) ?: 0L
                val materialized = (cursor.long(11) ?: 1L) != 0L
                val syncState = VaultBatchSyncState.entries.firstOrNull { it.wireName == rawSyncState }
                    ?: if (synced) VaultBatchSyncState.SYNCED else VaultBatchSyncState.PENDING
                out += StoredVaultBatch(
                    batch = VaultOpBatch(
                        schema = 1,
                        batchId = batchId,
                        actorId = actorId,
                        actorSeq = actorSeq,
                        logicalClock = logicalClock,
                        createdAt = createdAt,
                        parents = parents,
                        ops = ops,
                        digest = digest,
                    ),
                    synced = synced,
                    remoteFileId = remoteFileId,
                    syncState = syncState,
                    materialized = materialized,
                )
            }
        }
        return out
    }

    private fun sortTopologically(batches: List<Pair<String, VaultOpBatch>>): List<Pair<String, VaultOpBatch>> {
        val byId = batches.associateBy { it.second.batchId }
        val sortedByClock = batches.sortedWith(
            compareBy<Pair<String, VaultOpBatch>> { it.second.logicalClock }
                .thenBy { it.second.actorId }
                .thenBy { it.second.actorSeq }
                .thenBy { it.second.batchId },
        )
        val visited = HashSet<String>()
        val result = ArrayList<Pair<String, VaultOpBatch>>(batches.size)
        fun visit(item: Pair<String, VaultOpBatch>) {
            if (!visited.add(item.second.batchId)) return
            for (parentId in item.second.parents.sorted()) {
                byId[parentId]?.let(::visit)
            }
            result += item
        }
        sortedByClock.forEach(::visit)
        return result
    }

    companion object {
        internal val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            classDiscriminator = "op"
        }
        private val stringListSerializer = ListSerializer(String.serializer())
        private val opListSerializer = ListSerializer(VaultOp.serializer())
        private val checkpointNodeListSerializer = ListSerializer(VaultCheckpointNode.serializer())

        fun encodeBatch(batch: VaultOpBatch): String =
            json.encodeToString(VaultOpBatch.serializer(), batch)

        fun decodeBatch(raw: String): VaultOpBatch? =
            runCatching { json.decodeFromString(VaultOpBatch.serializer(), raw) }
                .getOrNull()
                ?.takeIf { it.schema == 1 && it.batchId.isNotBlank() }

        fun encodeSnapshot(snapshot: VaultCheckpointSnapshot): String =
            json.encodeToString(VaultCheckpointSnapshot.serializer(), snapshot)

        fun decodeSnapshot(raw: String): VaultCheckpointSnapshot? =
            runCatching { json.decodeFromString(VaultCheckpointSnapshot.serializer(), raw) }
                .getOrNull()
                ?.takeIf { it.schema == 1 && it.digest.isNotBlank() }

        fun computeBatchDigest(
            actorId: String,
            actorSeq: Long,
            logicalClock: Long,
            parents: List<String>,
            ops: List<VaultOp>,
        ): String {
            val canonical = buildString {
                append(actorId).append('|').append(actorSeq).append('|').append(logicalClock).append('|')
                append(parents.sorted().joinToString(",")).append('|')
                append(json.encodeToString(opListSerializer, ops))
            }
            val bytes = canonical.encodeToByteArray()
            return PikPakHash.fromSource(Buffer().apply { write(bytes) }, bytes.size.toLong()).lowercase()
        }

        fun computeSnapshotDigest(
            logicalClock: Long,
            actorId: String,
            coveredBatchIds: List<String>,
            leafBatchIds: List<String>,
            nodes: List<VaultCheckpointNode>,
        ): String {
            val sortedNodes = nodes.sortedBy { it.nodeId }
            val canonical = buildString {
                append(logicalClock).append('|').append(actorId).append('|')
                append(coveredBatchIds.sorted().joinToString(",")).append('|')
                append(leafBatchIds.sorted().joinToString(",")).append('|')
                append(json.encodeToString(checkpointNodeListSerializer, sortedNodes))
            }
            val bytes = canonical.encodeToByteArray()
            return PikPakHash.fromSource(Buffer().apply { write(bytes) }, bytes.size.toLong()).lowercase()
        }

        private fun opNodeIdOf(op: VaultOp): String = when (op) {
            is VaultOp.UpsertFolder -> op.nodeId
            is VaultOp.UpsertFile -> op.nodeId
            is VaultOp.RenameNode -> op.nodeId
            is VaultOp.MoveNode -> op.nodeId
            is VaultOp.DeleteNode -> op.nodeId
            is VaultOp.MarkRestored -> op.nodeId
            is VaultOp.RestoreSnapshot -> ""
        }

        private fun toParentRef(folderId: String): String =
            if (VirtualVaultLocation.isVirtualFolder(folderId)) folderId else "$REAL_PARENT_PREFIX$folderId"

        private fun fromParentRef(parentRef: String): String =
            if (parentRef.startsWith(REAL_PARENT_PREFIX)) parentRef.removePrefix(REAL_PARENT_PREFIX) else parentRef

        private fun compareStamp(clockA: Long, actorA: String, clockB: Long, actorB: String): Int {
            val c = clockA.compareTo(clockB)
            return if (c != 0) c else actorA.compareTo(actorB)
        }
    }
}
