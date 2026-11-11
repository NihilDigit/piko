package dev.piko.shared.data

import dev.piko.data.repository.isPlayableVideo
import dev.piko.shared.log.PikoLog
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileTag
import kotlinx.serialization.json.Json

/** Disk owns file identities. Heap projections contain folders and caller-requested rows only. */
internal class SqliteTreeStore(
    private val db: PikoTreeDatabase,
    private val ownerAccount: String,
    private val locallyCachedManifestIds: () -> Set<String> = ::emptySet,
) {
    private companion object {
        const val TAG = "SqliteTreeStore"
        val EMPTY_COMPLETE_CONTENT = ContentStats(listingComplete = true)
        val EMPTY_INCOMPLETE_CONTENT = ContentStats(listingComplete = false)
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private var closed = false
    private var revision = 0L
    private var sequence = 0L
    private var nextGeneration = 0L
    private val candidates = HashMap<Long, Long>()
    private val touchedFolders = HashSet<String>()
    private var rebuildAll = false
    private val canonical = TreeCanonicalizer()

    private class FolderProjection(
        val folders: Map<String, FileStat>,
        val childrenByParent: Map<String, Set<String>>,
        val created: Map<String, Long>,
        val modified: Map<String, Long>,
    )

    // In-memory hot projections of folder skeleton & materialized subtree aggregates (zero file rows retained).
    private var cachedFolderProjection: FolderProjection? = null
    private var cachedCoverage: Pair<Set<String>, Set<String>>? = null
    private var cachedManifestRows: Map<String, FileStat>? = null
    private var cachedSubtreeStats: Map<String, SubtreeStats>? = null
    // Bounded ContentMetadata cache active during candidate builds to avoid 120,000+ redundant SELECTs on Android.
    private val candidateContentCache = LinkedHashMap<ContentKey, ContentMetadata>(2048)

    private fun cacheCandidateContent(key: ContentKey, metadata: ContentMetadata) {
        // Common LinkedHashMap has insertion order only; remove/reinsert preserves the access-order LRU.
        candidateContentCache.remove(key)
        candidateContentCache[key] = metadata
        if (candidateContentCache.size > 8192) candidateContentCache.remove(candidateContentCache.keys.first())
    }
    private var candidateContentExhaustive = false

    private fun clearCandidateContentCache() {
        candidateContentCache.clear()
        candidateContentExhaustive = false
    }

    private fun invalidateFolderProjection() {
        cachedFolderProjection = null
    }

    private fun invalidateCoverage() {
        cachedCoverage = null
    }

    private fun invalidateAllCaches() {
        cachedFolderProjection = null
        cachedCoverage = null
        cachedManifestRows = null
        cachedSubtreeStats = null
    }

    init {
        val acctSlot = logAccountSlot(ownerAccount)
        db.execute("CREATE TABLE IF NOT EXISTS tree_meta (key TEXT PRIMARY KEY, value TEXT)")
        val owner = scalar("SELECT value FROM tree_meta WHERE key='owner_account'")
        val schemaVersion = meta("schema_version")
        if (owner != ownerAccount || schemaVersion != "1") {
            if (owner != null || schemaVersion != null) {
                PikoLog.w(
                    TAG,
                    "Resetting SQLite tree store due to metadata mismatch (expectedAcct=$acctSlot, storedAcct=${owner?.let(::logAccountSlot) ?: "none"}, schema=${schemaVersion ?: "none"})",
                )
            } else {
                PikoLog.i(TAG, "Initializing fresh SQLite tree store (acct=$acctSlot)")
            }
            for (table in listOf("tree_nodes", "tree_content", "tree_instances", "tree_coverage", "tree_vault", "tree_deleted", "tree_aggregate", "tree_sources", "tree_subtree", "tree_restores", "tree_overrides", "tree_vault_revision", "tree_vault_state", "tree_direct_usage", "tree_legacy_sources", "tree_legacy_refs", "tree_legacy_instances", "tree_legacy_content")) db.execute("DROP TABLE IF EXISTS $table")
            db.execute("DELETE FROM tree_meta")
        }
        db.execute("INSERT OR REPLACE INTO tree_meta VALUES ('owner_account', ?)", listOf(ownerAccount))
        meta("schema_version", "1")
        db.execute("CREATE TABLE IF NOT EXISTS tree_nodes (generation INTEGER NOT NULL, id TEXT NOT NULL, parent TEXT NOT NULL, folder INTEGER NOT NULL, virtual INTEGER NOT NULL, name TEXT NOT NULL, norm_name TEXT NOT NULL, excluded INTEGER NOT NULL, bytes INTEGER NOT NULL, gcid TEXT, duration INTEGER, video INTEGER NOT NULL, payload TEXT NOT NULL, seq INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(generation,id))")
        db.execute("CREATE INDEX IF NOT EXISTS tree_parent ON tree_nodes(generation,parent)")
        db.execute("CREATE INDEX IF NOT EXISTS tree_content_key ON tree_nodes(gcid,bytes)")
        db.execute("CREATE INDEX IF NOT EXISTS tree_folders ON tree_nodes(generation,folder)")
        db.execute("CREATE INDEX IF NOT EXISTS tree_search ON tree_nodes(generation,excluded,norm_name,id,folder)")
        db.execute("CREATE INDEX IF NOT EXISTS tree_names ON tree_nodes(generation,name)")
        db.execute("CREATE INDEX IF NOT EXISTS tree_space_files ON tree_nodes(generation,parent,folder,excluded,bytes DESC)")
        db.execute("CREATE TABLE IF NOT EXISTS tree_content (gcid TEXT NOT NULL, bytes INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(gcid,bytes))")
        db.execute("CREATE TABLE IF NOT EXISTS tree_instances (id TEXT PRIMARY KEY, original_created INTEGER NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS tree_restores (id TEXT PRIMARY KEY, gcid TEXT NOT NULL, bytes INTEGER NOT NULL, original_created INTEGER NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS tree_coverage (id TEXT PRIMARY KEY, dirty INTEGER NOT NULL, seq INTEGER NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS tree_vault (generation INTEGER NOT NULL, folder TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(generation,folder,id))")
        db.execute("CREATE TABLE IF NOT EXISTS tree_vault_revision (folder TEXT PRIMARY KEY, seq INTEGER NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS tree_vault_state (generation INTEGER NOT NULL, folder TEXT NOT NULL, manifest_id TEXT, PRIMARY KEY(generation,folder))")
        db.execute("CREATE TABLE IF NOT EXISTS tree_deleted (id TEXT PRIMARY KEY, seq INTEGER NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS tree_overrides (id TEXT PRIMARY KEY, name TEXT, name_seq INTEGER NOT NULL DEFAULT 0, parent TEXT, parent_seq INTEGER NOT NULL DEFAULT 0)")
        db.execute("CREATE TABLE IF NOT EXISTS tree_aggregate (folder TEXT NOT NULL, virtual INTEGER NOT NULL, bytes INTEGER NOT NULL, count INTEGER NOT NULL, duration INTEGER NOT NULL, known INTEGER NOT NULL, unknown INTEGER NOT NULL, PRIMARY KEY(folder,virtual))")
        db.execute("CREATE TABLE IF NOT EXISTS tree_sources (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS tree_subtree (folder TEXT PRIMARY KEY, payload TEXT NOT NULL, usage_bytes INTEGER NOT NULL, usage_count INTEGER NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS tree_direct_usage (folder TEXT PRIMARY KEY, bytes INTEGER NOT NULL, count INTEGER NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS tree_legacy_sources(generation INTEGER NOT NULL,id INTEGER NOT NULL,value TEXT NOT NULL,PRIMARY KEY(generation,id))")
        db.execute("CREATE TABLE IF NOT EXISTS tree_legacy_refs(generation INTEGER NOT NULL,id TEXT NOT NULL,ref INTEGER NOT NULL,PRIMARY KEY(generation,id))")
        db.execute("CREATE TABLE IF NOT EXISTS tree_legacy_instances(generation INTEGER NOT NULL,id TEXT NOT NULL,created INTEGER NOT NULL,PRIMARY KEY(generation,id))")
        db.execute("CREATE TABLE IF NOT EXISTS tree_legacy_content(generation INTEGER NOT NULL,gcid TEXT NOT NULL,bytes INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(generation,gcid,bytes))")
        for (table in listOf("tree_legacy_sources", "tree_legacy_refs", "tree_legacy_instances", "tree_legacy_content")) db.execute("DELETE FROM $table")
        // Candidate generations have no visibility after a process crash.
        db.execute("DELETE FROM tree_nodes WHERE generation>0")
        db.execute("DELETE FROM tree_vault WHERE generation>0")
        db.execute("DELETE FROM tree_vault_state WHERE generation>0")
        db.execute("DELETE FROM tree_deleted")
        db.execute("DELETE FROM tree_overrides")
        sequence = meta("sequence")?.toLongOrNull() ?: 0L
        val interruptedVaults = ArrayList<String>()
        rows("SELECT key FROM tree_meta WHERE key LIKE 'optimistic_vault:%' AND value='true'") {
            interruptedVaults += it.string(0)!!.removePrefix("optimistic_vault:")
        }
        if (interruptedVaults.isNotEmpty()) {
            PikoLog.i(
                TAG,
                "Clearing ${interruptedVaults.size} interrupted optimistic vault projection(s) on startup (acct=$acctSlot)",
            )
            mutate {
                for (folder in interruptedVaults) clearVault(folder)
                db.execute("DELETE FROM tree_meta WHERE key LIKE 'optimistic_vault:%'")
            }
        }
        if (meta("aggregate_version") != "2") {
            rebuildAll = true
            db.transaction { rebuildAggregates() }
        }
        runCatching { db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { while (it.next()) Unit } }
            .onFailure { err -> PikoLog.w(TAG, "Startup WAL checkpoint (TRUNCATE) failed (acct=$acctSlot)", err) }
    }

    private fun scalar(sql: String, args: List<Any?> = emptyList()): String? = db.scalarString(sql, args)
    private inline fun rows(sql: String, args: List<Any?> = emptyList(), consume: (PikoTreeCursor) -> Unit) {
        val c = db.query(sql, args)
        if (c is ManagedPikoTreeDatabase.ManagedCursor) {
            c.forEachRow(consume = consume)
        } else {
            try { while (c.next()) consume(c) } finally { c.close() }
        }
    }
    private inline fun <R> useQuery(sql: String, args: List<Any?> = emptyList(), block: (PikoTreeCursor) -> R): R {
        val c = db.query(sql, args)
        return if (c is ManagedPikoTreeDatabase.ManagedCursor) {
            c.useLocked(block)
        } else {
            try { block(c) } finally { c.close() }
        }
    }
    private fun decode(text: String) = canonical.compact(json.decodeFromString(DriveTreeIndex.StoredTreeEntry.serializer(), text).toFileStat())
    private fun encode(file: FileStat) = json.encodeToString(DriveTreeIndex.StoredTreeEntry.serializer(), DriveTreeIndex.StoredTreeEntry.of(file))
    private fun meta(key: String) = scalar("SELECT value FROM tree_meta WHERE key=?", listOf(key))
    private fun meta(key: String, value: String?) = db.execute("INSERT OR REPLACE INTO tree_meta VALUES (?,?)", listOf(key, value))
    val complete get() = !closed && meta("complete") == "true"
    val usage get() = if (closed) null else meta("usage")?.toLongOrNull()
    val syncedAt get() = if (closed) 0L else meta("synced")?.toLongOrNull() ?: 0L
    val mutationSequence get() = sequence
    val currentRevision get() = revision
    val syncCursor get() = if (closed) null else meta("sync_cursor")

    fun beginDelta() {
        check(!closed)
        db.execute("CREATE TEMP TABLE IF NOT EXISTS tree_delta(id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        db.execute("DELETE FROM tree_delta")
    }

    fun appendDelta(files: List<FileStat>) {
        check(!closed)
        db.transaction {
            for (file in files) db.execute("INSERT OR REPLACE INTO tree_delta VALUES (?,?)",
                listOf(file.id, json.encodeToString(FileStat.serializer(), file)))
        }
    }

    fun abortDelta() {
        if (!closed) db.execute("DELETE FROM tree_delta")
    }

    /** All pages and their conservative server cursor become visible in one transaction. */
    fun commitDelta(cursor: String): Set<String> {
        check(!closed)
        val affected = HashSet<String>()
        mutate {
            val manifestsBefore = HashMap<String, String?>()
            // Moves out of a removed subtree must land before deleting that subtree.
            for (removing in listOf(false, true)) {
                var after = ""
                while (true) {
                    val page = ArrayList<FileStat>(256)
                    rows("SELECT payload FROM tree_delta WHERE id>? ORDER BY id LIMIT 256", listOf(after)) {
                        page += json.decodeFromString(FileStat.serializer(), it.string(0)!!)
                    }
                    if (page.isEmpty()) break
                    for (row in page) {
                        if (row.trashed != removing) continue
                        val previous = file(row.id)
                        affected += row.parentId
                        previous?.parentId?.let(affected::add)
                        for (manifest in listOfNotNull(row, previous).filter(VaultStore::looksLikeManifest)) {
                            if (manifest.parentId !in manifestsBefore) manifestsBefore[manifest.parentId] = winner(manifest.parentId)?.id
                        }
                        if (!row.trashed) {
                            put(row)
                            if (row.isFolder) coverage(row.id, false)
                        }
                    }
                    if (removing) remove(page.filter { it.trashed }.map { it.id })
                    after = page.last().id
                }
            }
            for ((folder, previous) in manifestsBefore) if (previous != winner(folder)?.id) clearVault(folder)
            meta("sync_cursor", cursor)
            meta("sync_cursor_version", "2")
            db.execute("DELETE FROM tree_delta")
        }
        return affected
    }

    fun close() {
        if (!closed) {
            closed = true
            candidates.clear()
            clearCandidateContentCache()
            canonical.clear()
            invalidateAllCaches()
            db.close()
        }
    }
    private fun mutate(block: () -> Unit) {
        if (closed) return
        val savedSequence = sequence
        try {
            db.transaction {
                sequence++
                block()
                val fullReplacement = rebuildAll
                val needsAggregateRebuild = fullReplacement || touchedFolders.isNotEmpty() || cachedSubtreeStats == null
                if (needsAggregateRebuild) {
                    val previousUsage = if (!fullReplacement) usage?.takeIf { complete } else null
                    val previousBytes = if (previousUsage == null) 0L else scalar("SELECT COALESCE(SUM(bytes),0) FROM tree_direct_usage")!!.toLong()
                    rebuildAggregates()
                    if (previousUsage != null && (meta("usage_seq")?.toLongOrNull() ?: 0L) < sequence) {
                        val nextBytes = scalar("SELECT COALESCE(SUM(bytes),0) FROM tree_direct_usage")!!.toLong()
                        val delta = nextBytes - previousBytes
                        if (delta != 0L) {
                            val nextUsage = if (delta > 0L && previousUsage > Long.MAX_VALUE - delta) Long.MAX_VALUE else (previousUsage + delta).coerceAtLeast(0)
                            meta("usage", nextUsage.toString()); meta("usage_seq", sequence.toString())
                        }
                    }
                }
                meta("sequence", sequence.toString())
            }
            revision++
        } catch (error: Throwable) {
            sequence = savedSequence
            touchedFolders.clear()
            rebuildAll = false
            invalidateAllCaches()
            throw error
        }
    }
    fun clear() = mutate {
        for (table in listOf("tree_nodes", "tree_content", "tree_instances", "tree_coverage", "tree_vault", "tree_deleted", "tree_aggregate", "tree_subtree", "tree_restores", "tree_overrides", "tree_vault_revision", "tree_vault_state", "tree_direct_usage")) db.execute("DELETE FROM $table")
        meta("complete", "false"); meta("usage", null); meta("synced", null); meta("sync_cursor", null); meta("sync_cursor_version", null)
        candidates.clear(); clearCandidateContentCache(); rebuildAll = true
        invalidateAllCaches()
    }
    fun file(id: String, generation: Long = 0): FileStat? {
        if (closed) return null
        return useQuery("SELECT payload,duration FROM tree_nodes WHERE generation=? AND id=?", listOf(generation, id)) { cursor ->
            if (!cursor.next()) null
            else decode(cursor.string(0)!!).enrichWithContentMetadata(null, cursor.long(1))
        }
    }
    /** Nonmatching files never allocate FileStat, params, tags or metadata. */
    fun searchCandidates(query: String, extraFolderTitle: (FileStat) -> String?, consume: (Int, FileStat) -> Unit) =
        searchCandidates(dev.piko.shared.search.prepareCjkQuery(query), extraFolderTitle, consume)

    fun searchCandidates(
        preparedQuery: dev.piko.shared.search.PreparedCjkQuery,
        extraFolderTitle: (FileStat) -> String?,
        consume: (Int, FileStat) -> Unit,
    ) {
        if (closed) return
        val matchedFiles = ArrayList<Pair<String, Int>>()
        val matchedFolders = ArrayList<Pair<String, Int>>()
        val unmatchedFolders = ArrayList<String>()
        rows("SELECT id,norm_name,folder FROM tree_nodes WHERE generation=0 AND excluded=0") { c ->
            val id = c.string(0)!!
            val isFolder = c.long(2) == 1L
            val nameScore = preparedQuery.scoreNormalized(c.string(1)!!)
            if (nameScore != null) {
                if (isFolder) matchedFolders += id to nameScore else matchedFiles += id to nameScore
            } else if (isFolder) {
                unmatchedFolders += id
            }
        }
        if (matchedFolders.isNotEmpty() || unmatchedFolders.isNotEmpty()) {
            val folders = ensureFolderProjection().folders
            for ((id, score) in matchedFolders) {
                (folders[id] ?: file(id))?.let { consume(score, it) }
            }
            for (id in unmatchedFolders) {
                val folder = folders[id] ?: file(id) ?: continue
                extraFolderTitle(folder)?.let(preparedQuery::score)?.let { consume(it, folder) }
            }
        }
        for ((id, score) in matchedFiles) {
            file(id)?.let { consume(score, it) }
        }
    }

    fun children(id: String, realOnly: Boolean = false): DriveTreeIndex.FolderChildren {
        if (closed) return DriveTreeIndex.FolderChildren(emptyList(), DriveTreeIndex.FolderCoverage.UNKNOWN)
        val files = ArrayList<FileStat>()
        rows("SELECT payload,duration FROM tree_nodes WHERE generation=0 AND parent=?" + if (realOnly) " AND virtual=0" else "", listOf(id)) {
            files += decode(it.string(0)!!).enrichWithContentMetadata(null, it.long(1))
        }
        val dirty = scalar("SELECT dirty FROM tree_coverage WHERE id=?", listOf(id))
        val coverage = when {
            dirty == "1" -> DriveTreeIndex.FolderCoverage.DIRTY
            dirty == "0" -> DriveTreeIndex.FolderCoverage.COMPLETE
            files.isNotEmpty() || file(id) != null -> DriveTreeIndex.FolderCoverage.PARTIAL
            else -> DriveTreeIndex.FolderCoverage.UNKNOWN
        }
        return DriveTreeIndex.FolderChildren(files, coverage)
    }
    fun vaultEntries(folder: String): List<VaultEntry>? {
        if (closed || scalar("SELECT folder FROM tree_vault_state WHERE generation=0 AND folder=?", listOf(folder)) == null) return null
        val result = ArrayList<VaultEntry>()
        rows("SELECT payload FROM tree_vault WHERE generation=0 AND folder=?", listOf(folder)) { result += json.decodeFromString(VaultEntry.serializer(), it.string(0)!!) }
        return result
    }
    fun vaultFolders(): Set<String> {
        val result = HashSet<String>()
        if (!closed) rows("SELECT folder FROM tree_vault_state WHERE generation=0") { result += it.string(0)!! }
        return result
    }
    fun hasPhysicalMissingFrom(ids: Set<String>): Boolean {
        if (closed) return false
        rows("SELECT id FROM tree_nodes WHERE generation=0 AND virtual=0") { c ->
            if (c.string(0)!! !in ids) return true
        }
        return false
    }

    internal data class RawSpaceFile(
        val id: String,
        val name: String,
        val isVaulted: Boolean,
        val bytes: Long,
        val durationMillis: Long,
    )

    fun directSpaceFiles(parentId: String, includeVaulted: Boolean): List<RawSpaceFile> {
        if (closed) return emptyList()
        val out = ArrayList<RawSpaceFile>()
        val sql = if (includeVaulted) {
            "SELECT id,virtual,name,bytes,COALESCE(duration,0) FROM tree_nodes WHERE generation=0 AND parent=? AND folder=0 AND excluded=0"
        } else {
            "SELECT id,virtual,name,bytes,COALESCE(duration,0) FROM tree_nodes WHERE generation=0 AND parent=? AND folder=0 AND excluded=0 AND virtual=0"
        }
        rows(sql, listOf(parentId)) { c ->
            out += RawSpaceFile(
                id = c.string(0)!!,
                isVaulted = c.long(1) == 1L,
                name = c.string(2)!!,
                bytes = (c.long(3) ?: 0L).coerceAtLeast(0L),
                durationMillis = (c.long(4) ?: 0L).coerceAtLeast(0L),
            )
        }
        return out
    }

    fun totalPhysicalBytes(): Long =
        if (closed) 0L else scalar("SELECT COALESCE(SUM(bytes),0) FROM tree_direct_usage")?.toLongOrNull() ?: 0L

    fun topSpacePreviewFiles(parentId: String, includeVaulted: Boolean, limit: Int): List<dev.piko.shared.space.SpacePreviewBlock> {
        if (closed || limit <= 0) return emptyList()
        val out = ArrayList<dev.piko.shared.space.SpacePreviewBlock>(limit)
        val sql = if (includeVaulted) {
            "SELECT id,virtual,name,bytes FROM tree_nodes WHERE generation=0 AND parent=? AND folder=0 AND excluded=0 AND bytes>0 ORDER BY bytes DESC LIMIT ?"
        } else {
            "SELECT id,virtual,name,bytes FROM tree_nodes WHERE generation=0 AND parent=? AND folder=0 AND excluded=0 AND virtual=0 AND bytes>0 ORDER BY bytes DESC LIMIT ?"
        }
        rows(sql, listOf(parentId, limit)) { c ->
            val vaulted = c.long(1) == 1L
            val bytes = (c.long(3) ?: 0L).coerceAtLeast(0L)
            out += dev.piko.shared.space.SpacePreviewBlock(
                id = c.string(0)!!,
                name = c.string(2)!!,
                isFolder = false,
                isVaulted = vaulted,
                bytes = bytes,
                physicalBytes = if (vaulted) 0L else bytes,
                vaultedBytes = if (vaulted) bytes else 0L,
                fileCount = 1L,
            )
        }
        return out
    }

    fun topSpacePreviewFilesByParent(
        parentIds: Collection<String>,
        includeVaulted: Boolean,
        limitPerParent: Int,
    ): Map<String, List<dev.piko.shared.space.SpacePreviewBlock>> {
        if (closed || limitPerParent <= 0 || parentIds.isEmpty()) return emptyMap()
        val distinct = parentIds.distinct()
        val result = HashMap<String, ArrayList<dev.piko.shared.space.SpacePreviewBlock>>(distinct.size)
        val virtualClause = if (includeVaulted) "" else " AND virtual=0"
        for (chunk in distinct.chunked(200)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val sql = """
                SELECT parent,id,virtual,name,bytes FROM (
                    SELECT parent,id,virtual,name,bytes,
                           ROW_NUMBER() OVER (PARTITION BY parent ORDER BY bytes DESC) AS rn
                    FROM tree_nodes
                    WHERE generation=0 AND parent IN ($placeholders) AND folder=0 AND excluded=0$virtualClause AND bytes>0
                ) WHERE rn <= ?
            """.trimIndent()
            rows(sql, chunk + limitPerParent) { c ->
                val parent = c.string(0)!!
                val vaulted = c.long(2) == 1L
                val bytes = (c.long(4) ?: 0L).coerceAtLeast(0L)
                result.getOrPut(parent) { ArrayList(limitPerParent) } += dev.piko.shared.space.SpacePreviewBlock(
                    id = c.string(1)!!,
                    name = c.string(3)!!,
                    isFolder = false,
                    isVaulted = vaulted,
                    bytes = bytes,
                    physicalBytes = if (vaulted) 0L else bytes,
                    vaultedBytes = if (vaulted) bytes else 0L,
                    fileCount = 1L,
                )
            }
        }
        return result
    }

    private fun ensureFolderProjection(): FolderProjection {
        cachedFolderProjection?.let { return it }
        val folders = HashMap<String, FileStat>()
        rows("SELECT payload FROM tree_nodes WHERE generation=0 AND folder=1") {
            val f = decode(it.string(0)!!)
            folders[f.id] = f
        }
        val children = HashMap<String, MutableSet<String>>()
        val created = HashMap<String, Long>(folders.size)
        val modified = HashMap<String, Long>(folders.size)
        for (folder in folders.values) {
            children.getOrPut(folder.parentId) { HashSet() }.add(folder.id)
            treeEpochMillis(folder.createdTime)?.let { created[folder.id] = it }
            treeEpochMillis(folder.modifiedTime)?.let { modified[folder.id] = it }
        }
        val frozenChildren = HashMap<String, Set<String>>(children.size)
        for ((parentId, ids) in children) frozenChildren[parentId] = ids
        val built = FolderProjection(
            folders = folders,
            childrenByParent = frozenChildren,
            created = created,
            modified = modified,
        )
        cachedFolderProjection = built
        return built
    }

    private fun ensureCoverage(): Pair<Set<String>, Set<String>> {
        cachedCoverage?.let { return it }
        val dirty = HashSet<String>()
        val listed = HashSet<String>()
        rows("SELECT id,dirty FROM tree_coverage") {
            if (it.long(1) == 1L) dirty += it.string(0)!! else listed += it.string(0)!!
        }
        val built: Pair<Set<String>, Set<String>> = listed to dirty
        cachedCoverage = built
        return built
    }

    private fun ensureManifestRows(): Map<String, FileStat> {
        cachedManifestRows?.let { return it }
        val rowsMap = HashMap<String, FileStat>()
        rows("SELECT payload FROM tree_nodes WHERE generation=0 AND excluded=1 AND name LIKE '.piko-vault-%'") {
            val f = decode(it.string(0)!!)
            rowsMap[f.id] = f
        }
        cachedManifestRows = rowsMap
        return rowsMap
    }

    private fun ensureSubtreeStats(): Map<String, SubtreeStats> {
        cachedSubtreeStats?.let { return it }
        val stats = HashMap<String, SubtreeStats>()
        rows("SELECT folder,payload FROM tree_subtree") {
            stats[it.string(0)!!] = json.decodeFromString(SubtreeStats.serializer(), it.string(1)!!)
        }
        cachedSubtreeStats = stats
        return stats
    }

    fun projection(): DriveTreeIndex.State {
        if (closed) return DriveTreeIndex.State(revision = revision)
        val folderProj = ensureFolderProjection()
        val (listed, dirty) = ensureCoverage()
        val manifestRows = ensureManifestRows()
        val manifests: Map<String, FileStat> = if (manifestRows.isEmpty()) {
            emptyMap()
        } else {
            val cachedIds = locallyCachedManifestIds()
            val winners = HashMap<String, FileStat>()
            for (f in manifestRows.values) {
                val winner = VaultStore.winnerOf(listOfNotNull(winners[f.parentId], f), cachedIds)
                if (winner != null) winners[f.parentId] = winner
            }
            winners
        }
        val stats = ensureSubtreeStats()
        return DriveTreeIndex.State(
            entries = folderProj.folders,
            childrenByParent = folderProj.childrenByParent,
            manifestsByFolder = manifests,
            listedFolders = listed,
            dirtyFolders = dirty,
            lastUsageBytes = usage,
            complete = complete,
            revision = revision,
            sortProjection = SortContext(revision, stats, folderProj.created, folderProj.modified, emptyMap()),
        )
    }
    private fun lookupContentForWrite(key: ContentKey, generation: Long): ContentMetadata? {
        if (generation != 0L) {
            candidateContentCache.remove(key)?.let {
                candidateContentCache[key] = it
                return it
            }
            if (candidateContentExhaustive) return null
        }
        return content(key)?.also { if (generation != 0L) cacheCandidateContent(key, it) }
    }

    private fun clean(file: FileStat) = file.copy(params = file.params - "piko_original_created_at", thumbnailLink = file.thumbnailLink.takeUnless { '?' in it }.orEmpty())

    private fun put(file: FileStat, generation: Long = 0) {
        if (file.trashed) return
        val key = ContentKey.of(file)
        val observed = ContentMetadata.of(file)
        val previous = if (key != null) lookupContentForWrite(key, generation) else null
        val metadata = if (previous != null) previous.merge(observed) else observed
        if (key != null) {
            putContent(
                key = key,
                metadata = metadata,
                previousKnown = true,
                previousValue = previous,
                updateLiveDuration = generation == 0L,
                cacheInCandidate = generation != 0L,
            )
        }
        // Instance time is accepted only through setEntryMetadata or the trusted migration field.
        val clean = clean(file)
        val looksManifest = VaultStore.looksLikeManifest(clean)
        val excludedFlag = if (looksManifest || VaultStore.isRecoveryFile(clean)) 1 else 0
        if (generation == 0L) {
            scalar("SELECT parent FROM tree_nodes WHERE generation=0 AND id=?", listOf(clean.id))?.let(touchedFolders::add)
            touchedFolders += clean.parentId
            if (clean.isFolder) {
                touchedFolders += clean.id
                invalidateFolderProjection()
            } else if (cachedFolderProjection?.folders?.containsKey(clean.id) == true) {
                invalidateFolderProjection()
            }
            if (looksManifest || cachedManifestRows?.containsKey(clean.id) == true) {
                cachedManifestRows = null
            }
        }
        db.execute("INSERT OR REPLACE INTO tree_nodes VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)", listOf(generation, clean.id, clean.parentId, if (clean.isFolder) 1 else 0, if (VaultEntry.isVaulted(clean.id)) 1 else 0, clean.name, dev.piko.shared.search.normalizeCjk(clean.name), excludedFlag, clean.sizeBytes.coerceAtLeast(0), key?.gcid, metadata.durationMillis, if (clean.isPlayableVideo()) 1 else 0, encode(clean), if (generation == 0L) sequence else 0L))
        if (generation == 0L) db.execute("DELETE FROM tree_deleted WHERE id=?", listOf(clean.id))
        if (generation == 0L) bindRestoredInstance(clean)
    }
    private fun bindRestoredInstance(file: FileStat) {
        rows("SELECT gcid,bytes,original_created FROM tree_restores WHERE id=?", listOf(file.id)) { c ->
            if (!file.isFolder && file.hash.equals(c.string(0), ignoreCase = true) && file.sizeBytes == c.long(1)) {
                db.execute("INSERT OR REPLACE INTO tree_instances VALUES (?,?)", listOf(file.id, c.long(2)))
            } else db.execute("DELETE FROM tree_instances WHERE id=?", listOf(file.id))
        }
    }
    fun putFiles(files: List<FileStat>) = mutate {
        val before = HashMap<String, String?>()
        val isComplete = complete
        for (f in files) if (!f.trashed && !VaultEntry.isVaulted(f.id)) {
            if (VaultStore.looksLikeManifest(f)) {
                if (f.parentId !in before) before[f.parentId] = winner(f.parentId)?.id
                file(f.id)?.parentId?.let { old -> if (old !in before) before[old] = winner(old)?.id }
            }
            put(f)
            if (isComplete && f.isFolder) {
                invalidateCoverage()
                touchedFolders += f.id
                db.execute("INSERT OR IGNORE INTO tree_coverage VALUES (?,0,?)", listOf(f.id, sequence))
            }
        }
        for ((folder, previous) in before) if (previous != null && previous != winner(folder)?.id) clearVault(folder)
    }
    fun listing(parent: String, files: List<FileStat>, complete: Boolean): Set<String> {
        if (closed) return emptySet()
        val existing = HashMap<String, String>()
        rows("SELECT id,payload FROM tree_nodes WHERE generation=0 AND parent=? AND virtual=0", listOf(parent)) {
            existing[it.string(0)!!] = it.string(1)!!
        }
        val incoming = files.filterNot { it.trashed || VaultEntry.isVaulted(it.id) }
            .associate { it.id to encode(clean(it)) }
        if (incoming == existing) {
            if (complete && scalar("SELECT dirty FROM tree_coverage WHERE id=?", listOf(parent)) != "0") {
                mutate { touchedFolders += parent; coverage(parent, false) }
            }
            return emptySet()
        }
        val moved = HashSet<String>()
        mutate {
            touchedFolders += parent
            val before = winner(parent)
            db.execute("CREATE TEMP TABLE IF NOT EXISTS tree_observed (id TEXT PRIMARY KEY)")
            db.execute("DELETE FROM tree_observed")
            for (f in files) if (!f.trashed && !VaultEntry.isVaulted(f.id)) {
                file(f.id)?.parentId?.takeIf { it != parent }?.let(moved::add)
                db.execute("INSERT OR IGNORE INTO tree_observed VALUES (?)", listOf(f.id)); put(f)
            }
            if (complete) {
                val detached = HashSet<String>()
                rows("WITH RECURSIVE folders(id) AS (SELECT id FROM tree_nodes WHERE generation=0 AND parent=? AND folder=1 AND id NOT IN (SELECT id FROM tree_observed) UNION SELECT n.id FROM folders f CROSS JOIN tree_nodes n ON n.generation=0 AND n.parent=f.id AND n.folder=1) SELECT id FROM folders", listOf(parent)) { detached += it.string(0)!! }
                if (detached.isNotEmpty()) invalidateFolderProjection()
                if (cachedManifestRows?.values?.any { it.parentId == parent } == true) cachedManifestRows = null
                detached.forEach { coverage(it, true) }
                db.execute("INSERT OR REPLACE INTO tree_deleted SELECT id,? FROM tree_nodes WHERE generation=0 AND parent=? AND virtual=0 AND id NOT IN (SELECT id FROM tree_observed)", listOf(sequence, parent))
                db.execute("DELETE FROM tree_nodes WHERE generation=0 AND parent=? AND virtual=0 AND id NOT IN (SELECT id FROM tree_observed)", listOf(parent))
                coverage(parent, false)
            }
            if (before?.id != winner(parent)?.id) clearVault(parent)
        }
        return moved
    }
    private fun winner(folder: String, generation: Long = 0): FileStat? {
        var winner: FileStat? = null
        val cachedIds = locallyCachedManifestIds()
        rows("SELECT payload FROM tree_nodes WHERE generation=? AND parent=? AND excluded=1 AND name LIKE '.piko-vault-%'", listOf(generation, folder)) { winner = VaultStore.winnerOf(listOfNotNull(winner, decode(it.string(0)!!)), cachedIds) }
        return winner
    }
    private fun clearVault(folder: String, generation: Long = 0) {
        if (generation == 0L) {
            touchedFolders += folder
            db.execute("INSERT OR REPLACE INTO tree_vault_revision VALUES (?,?)", listOf(folder, sequence))
        }
        db.execute("DELETE FROM tree_vault WHERE generation=? AND folder=?", listOf(generation, folder))
        db.execute("DELETE FROM tree_vault_state WHERE generation=? AND folder=?", listOf(generation, folder))
        db.execute("DELETE FROM tree_nodes WHERE generation=? AND parent=? AND virtual=1", listOf(generation, folder))
    }
    private fun writeVaultFolderInTx(folder: String, entries: List<VaultEntry>, starred: Set<String>, generation: Long) {
        clearVault(folder, generation)
        db.execute("INSERT INTO tree_vault_state VALUES (?,?,?)", listOf(generation, folder, winner(folder, generation)?.id))
        for (entry in entries) {
            db.execute("INSERT OR REPLACE INTO tree_vault VALUES (?,?,?,?)", listOf(generation, folder, entry.id, json.encodeToString(VaultEntry.serializer(), entry)))
            if (entry.isArchived) put(entry.toFileStat(folder, starred = entry.id in starred), generation)
            else {
                val id = entry.restoredFileId!!
                if (ContentKey.of(entry.gcid, entry.size) != null) db.execute("INSERT OR REPLACE INTO tree_restores VALUES (?,?,?,?)", listOf(id, entry.gcid.uppercase(), entry.size, entry.createdAt ?: entry.addedAt))
                file(id, generation)?.let(::bindRestoredInstance)
                ContentKey.of(entry.gcid, entry.size)?.let { key ->
                    val observed = ContentMetadata.of(entry.toFileStat(folder))
                    val previous = lookupContentForWrite(key, generation)
                    val merged = previous?.merge(observed) ?: observed
                    putContent(
                        key = key,
                        metadata = merged,
                        previousKnown = true,
                        previousValue = previous,
                        updateLiveDuration = generation == 0L,
                        cacheInCandidate = generation != 0L,
                    )
                    if (generation == 0L && previous?.durationMillis != merged.durationMillis) {
                        rows("SELECT DISTINCT parent FROM tree_nodes WHERE generation=0 AND gcid=? AND bytes=?", listOf(key.gcid, key.sizeBytes)) { touchedFolders += it.string(0)!! }
                        db.execute("UPDATE tree_nodes SET duration=? WHERE gcid=? AND bytes=?", listOf(merged.durationMillis, key.gcid, key.sizeBytes))
                    }
                }
            }
        }
    }

    fun vault(folder: String, entries: List<VaultEntry>, expected: String?, requireMatch: Boolean, starred: Set<String>, generation: Long = 0, optimistic: Boolean = false): Boolean {
        if (closed || (generation != 0L && generation !in candidates)) return false
        if (requireMatch && winner(folder, generation)?.id != expected) return false
        val write = {
            writeVaultFolderInTx(folder, entries, starred, generation)
            if (generation == 0L) {
                if (optimistic) meta("optimistic_vault:$folder", "true")
                else db.execute("DELETE FROM tree_meta WHERE key=?", listOf("optimistic_vault:$folder"))
            }
        }
        if (generation == 0L) mutate(write) else db.transaction(write)
        return true
    }

    fun vaultBatch(generation: Long, batches: List<Pair<String, List<VaultEntry>>>) {
        if (closed || generation == 0L || generation !in candidates || batches.isEmpty()) return
        db.transaction {
            for ((folder, entries) in batches) {
                writeVaultFolderInTx(folder, entries, emptySet(), generation)
            }
        }
    }

    fun content(key: ContentKey): ContentMetadata? = if (closed) null else scalar("SELECT payload FROM tree_content WHERE gcid=? AND bytes=?", listOf(key.gcid, key.sizeBytes))?.let { json.decodeFromString(ContentMetadata.serializer(), it) }
    private fun putContent(
        key: ContentKey,
        metadata: ContentMetadata,
        previousKnown: Boolean = false,
        previousValue: ContentMetadata? = null,
        updateLiveDuration: Boolean = true,
        cacheInCandidate: Boolean = false,
    ) {
        val previous = if (previousKnown) previousValue else content(key)
        if (cacheInCandidate) {
            if (candidateContentCache.size >= 8192 && key !in candidateContentCache) {
                candidateContentExhaustive = false
            }
            cacheCandidateContent(key, metadata)
        }
        if (previous == metadata) return
        if (previous == null && metadata == ContentMetadata()) return
        db.execute("INSERT OR REPLACE INTO tree_content VALUES (?,?,?)", listOf(key.gcid, key.sizeBytes, json.encodeToString(ContentMetadata.serializer(), metadata)))
        if (updateLiveDuration && previous?.durationMillis != metadata.durationMillis) {
            rows("SELECT DISTINCT parent FROM tree_nodes WHERE generation=0 AND gcid=? AND bytes=?", listOf(key.gcid, key.sizeBytes)) { touchedFolders += it.string(0)!! }
            db.execute("UPDATE tree_nodes SET duration=? WHERE gcid=? AND bytes=?", listOf(metadata.durationMillis, key.gcid, key.sizeBytes))
        }
    }
    fun observeContent(key: ContentKey, value: ContentMetadata) {
        if (closed) return
        val normalized = ContentKey.of(key.gcid, key.sizeBytes) ?: return
        val prev = content(normalized)
        val merged = prev?.merge(value) ?: ContentMetadata().merge(value)
        if (merged == prev || (prev == null && merged == ContentMetadata())) return
        mutate {
            val currentPrev = content(normalized)
            val currentMerged = currentPrev?.merge(value) ?: ContentMetadata().merge(value)
            if (currentMerged != currentPrev && !(currentPrev == null && currentMerged == ContentMetadata())) {
                candidateContentCache.remove(normalized)
                putContent(normalized, currentMerged, previousKnown = true, previousValue = currentPrev, updateLiveDuration = true)
            }
        }
    }

    fun observeContentBatch(items: Collection<Pair<ContentKey, ContentMetadata>>) {
        if (closed || items.isEmpty()) return
        val incomingByKey = LinkedHashMap<ContentKey, ContentMetadata>(items.size)
        for ((rawKey, value) in items) {
            val normalized = ContentKey.of(rawKey.gcid, rawKey.sizeBytes) ?: continue
            val existing = incomingByKey[normalized]
            incomingByKey[normalized] = if (existing != null) existing.merge(value) else value
        }
        if (incomingByKey.isEmpty()) return
        val changedEntries = ArrayList<Triple<ContentKey, ContentMetadata?, ContentMetadata>>(incomingByKey.size)
        for ((key, incoming) in incomingByKey) {
            val prev = content(key)
            val merged = prev?.merge(incoming) ?: ContentMetadata().merge(incoming)
            if (merged != prev && !(prev == null && merged == ContentMetadata())) {
                changedEntries += Triple(key, prev, merged)
            }
        }
        if (changedEntries.isEmpty()) return
        mutate {
            for ((key, prev, merged) in changedEntries) {
                candidateContentCache.remove(key)
                putContent(key, merged, previousKnown = true, previousValue = prev, updateLiveDuration = true)
            }
        }
    }

    fun observeTranscodeSize(key: ContentKey, mediaId: String, streamSizeBytes: Long?, unreadable: Boolean = false) {
        if (closed) return
        val normalized = ContentKey.of(key.gcid, key.sizeBytes) ?: return
        val prev = content(normalized) ?: return
        if (prev.withTranscodeProbe(mediaId, streamSizeBytes, unreadable) == prev) return
        mutate {
            val currentPrev = content(normalized) ?: return@mutate
            val updated = currentPrev.withTranscodeProbe(mediaId, streamSizeBytes, unreadable)
            if (updated != currentPrev) {
                candidateContentCache.remove(normalized)
                putContent(normalized, updated, previousKnown = true, previousValue = currentPrev, updateLiveDuration = false)
            }
        }
    }
    fun originalCreated(id: String): Long? = if (closed) null else scalar("SELECT original_created FROM tree_instances WHERE id=?", listOf(id))?.toLongOrNull()
    fun instance(id: String, value: EntryMetadata) {
        if (closed) return
        if (originalCreated(id) == value.originalCreatedAtMillis) return
        mutate {
            if (value.originalCreatedAtMillis == null) db.execute("DELETE FROM tree_instances WHERE id=?", listOf(id))
            else db.execute("INSERT OR REPLACE INTO tree_instances VALUES (?,?)", listOf(id, value.originalCreatedAtMillis))
        }
    }
    private fun coverage(id: String, dirty: Boolean) {
        invalidateCoverage()
        touchedFolders += id
        db.execute("INSERT OR REPLACE INTO tree_coverage VALUES (?,?,?)", listOf(id, if (dirty) 1 else 0, sequence))
    }
    fun markComplete() = mutate { rebuildAll = true; meta("complete", "true") }
    fun usage(value: Long?) {
        if (closed) return
        if (candidates.isEmpty() && usage == value) return
        mutate { meta("usage", value?.toString()); meta("usage_seq", sequence.toString()) }
    }
    fun canary(files: List<FileStat>, value: Long?) = mutate {
        val isComplete = complete
        files.forEach {
            if (!VaultEntry.isVaulted(it.id)) {
                put(it)
                if (isComplete && it.isFolder) {
                    invalidateCoverage()
                    touchedFolders += it.id
                    db.execute("INSERT OR IGNORE INTO tree_coverage VALUES (?,0,?)", listOf(it.id, sequence))
                }
            }
        }
        if (value != null) { meta("usage", value.toString()); meta("usage_seq", sequence.toString()) }
    }
    fun descendants(root: String): Set<String> {
        val result = HashSet<String>()
        if (!closed) rows("WITH RECURSIVE folders(id) AS (SELECT ? UNION SELECT n.id FROM folders f CROSS JOIN tree_nodes n ON n.generation=0 AND n.parent=f.id AND n.folder=1) SELECT id FROM folders", listOf(root)) { result += it.string(0)!! }
        return result
    }
    private fun remove(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val folderProj = cachedFolderProjection
        if (folderProj == null || ids.any { it in folderProj.folders || it in folderProj.childrenByParent }) {
            invalidateFolderProjection()
        }
        if (cachedManifestRows?.isNotEmpty() == true) cachedManifestRows = null
        invalidateCoverage()
        val manifestParents = HashMap<String, String?>()
        for (id in ids) file(id)?.takeIf(VaultStore::looksLikeManifest)?.parentId?.let { manifestParents[it] = winner(it)?.id }
        for (batch in ids.chunked(256)) {
            // 从待删除父节点查子节点，避免 SQLite 反向连接时为每个墓碑扫描全盘。
            val roots = batch.joinToString(",") { "(?)" }
            db.execute("WITH RECURSIVE removed(id) AS (SELECT column1 FROM (VALUES $roots) UNION SELECT n.id FROM removed r CROSS JOIN tree_nodes n ON n.generation=0 AND n.parent=r.id) INSERT OR REPLACE INTO tree_deleted SELECT id,? FROM removed", batch + sequence)
        }
        rows("SELECT DISTINCT parent FROM tree_nodes WHERE generation=0 AND id IN (SELECT id FROM tree_deleted)") { touchedFolders += it.string(0)!! }
        db.execute("DELETE FROM tree_nodes WHERE generation=0 AND id IN (SELECT id FROM tree_deleted)")
        db.execute("DELETE FROM tree_coverage WHERE id IN (SELECT id FROM tree_deleted)")
        db.execute("DELETE FROM tree_vault WHERE generation=0 AND folder IN (SELECT id FROM tree_deleted)")
        db.execute("DELETE FROM tree_instances WHERE id IN (SELECT id FROM tree_deleted)")
        db.execute("DELETE FROM tree_vault_state WHERE generation=0 AND folder IN (SELECT id FROM tree_deleted)")
        for ((folder, previous) in manifestParents) if (previous != winner(folder)?.id) clearVault(folder)
    }
    fun change(change: DriveChange) = mutate {
        when (change) {
            is DriveChange.Renamed -> {
                db.execute("INSERT OR IGNORE INTO tree_overrides(id) VALUES (?)", listOf(change.id))
                db.execute("UPDATE tree_overrides SET name=?,name_seq=? WHERE id=?", listOf(change.name, sequence, change.id))
                file(change.id)?.let { current ->
                    if (current.name != change.name) {
                        val renamed = clean(current.copy(name = change.name))
                        val oldExcluded = VaultStore.looksLikeManifest(current) || VaultStore.isRecoveryFile(current)
                        val newExcluded = VaultStore.looksLikeManifest(renamed) || VaultStore.isRecoveryFile(renamed)
                        val oldVideo = current.isPlayableVideo()
                        val newVideo = renamed.isPlayableVideo()
                        val oldInternal = isPikoInternalFolder(current, current.parentId)
                        val newInternal = isPikoInternalFolder(renamed, renamed.parentId)
                        if (!oldExcluded && !newExcluded && oldVideo == newVideo && oldInternal == newInternal) {
                            if (renamed.isFolder) invalidateFolderProjection()
                            db.execute(
                                "UPDATE tree_nodes SET name=?,norm_name=?,payload=?,seq=? WHERE generation=0 AND id=?",
                                listOf(renamed.name, dev.piko.shared.search.normalizeCjk(renamed.name), encode(renamed), sequence, change.id),
                            )
                        } else {
                            if (oldInternal != newInternal) rebuildAll = true
                            if (oldExcluded || newExcluded) cachedManifestRows = null
                            val beforeManifest = if (oldExcluded || newExcluded) winner(renamed.parentId)?.id else null
                            put(renamed)
                            if ((oldExcluded || newExcluded) && beforeManifest != null && beforeManifest != winner(renamed.parentId)?.id) {
                                clearVault(renamed.parentId)
                            }
                        }
                    }
                }
            }
            is DriveChange.Moved -> for (id in change.ids) {
                db.execute("INSERT OR IGNORE INTO tree_overrides(id) VALUES (?)", listOf(id))
                db.execute("UPDATE tree_overrides SET parent=?,parent_seq=? WHERE id=?", listOf(change.to, sequence, id))
                val known = file(id)
                if (known != null) {
                    if (known.isFolder && isPikoInternalFolder(known, known.parentId) != isPikoInternalFolder(known, change.to)) {
                        rebuildAll = true
                    }
                    put(known.copy(parentId = change.to))
                } else change.folders.orEmpty().forEach { coverage(it, true) }
            }
            is DriveChange.Trashed -> remove(change.ids)
            is DriveChange.Deleted -> remove(change.ids)
            is DriveChange.Created -> if (change.isFolder) {
                put(FileStat(id = change.id, parentId = change.parentId, name = change.name, kind = FileKind.FOLDER)); coverage(change.id, false)
            } else if (change.stat != null) put(change.stat) else change.folders.forEach { coverage(it, true) }
            is DriveChange.Starred -> for (id in change.ids) {
                val f = file(id) ?: continue
                val nextTags = f.tags.filterNot { it.name.equals("STAR", true) } +
                    if (change.starred) listOf(FileTag(id = "STAR", name = "STAR")) else emptyList()
                val updated = clean(f.copy(starred = change.starred, tags = nextTags))
                if (updated == clean(f)) continue
                if (updated.isFolder) invalidateFolderProjection()
                if (VaultStore.looksLikeManifest(updated)) cachedManifestRows = null
                db.execute("UPDATE tree_nodes SET payload=?,seq=? WHERE generation=0 AND id=?", listOf(encode(updated), sequence, id))
            }
            is DriveChange.Copied -> change.folders.orEmpty().forEach { coverage(it, true) }
            is DriveChange.Restored, is DriveChange.ContentsChanged -> {
                if (change.folders == null) {
                    rebuildAll = true
                    meta("complete", "false")
                    candidates.clear()
                    invalidateCoverage()
                    db.execute("DELETE FROM tree_coverage")
                } else change.folders.orEmpty().forEach { coverage(it, true) }
            }
        }
    }

    fun appendLegacyEntries(generation: Long, entries: List<DriveTreeIndex.StoredTreeEntry>) {
        if (closed || generation !in candidates) return
        db.transaction {
            for (stored in entries) {
                require(file(stored.id, generation) == null) { "Duplicate node identity" }
                put(stored.toFileStat(), generation)
                stored.sourceRef?.let { db.execute("INSERT INTO tree_legacy_refs VALUES (?,?,?)", listOf(generation, stored.id, it)) }
                stored.originalCreatedAtMillis?.let { db.execute("INSERT OR REPLACE INTO tree_legacy_instances VALUES (?,?,?)", listOf(generation, stored.id, it)) }
            }
        }
    }
    fun appendLegacySources(generation: Long, sources: List<Pair<Int, String>>) {
        if (closed || generation !in candidates) return
        db.transaction { for ((id, value) in sources) db.execute("INSERT INTO tree_legacy_sources VALUES (?,?,?)", listOf(generation, id, value)) }
    }
    fun appendLegacyContent(generation: Long, values: List<StoredContentMetadata>) {
        if (closed || generation !in candidates) return
        db.transaction { for (value in values) ContentKey.of(value.key.gcid, value.key.sizeBytes)?.let { key ->
            db.execute("INSERT OR REPLACE INTO tree_legacy_content VALUES (?,?,?,?)", listOf(generation, key.gcid, key.sizeBytes, json.encodeToString(ContentMetadata.serializer(), value.metadata)))
        } }
    }
    fun appendLegacyInstances(generation: Long, values: List<Pair<String, EntryMetadata>>) {
        if (closed || generation !in candidates) return
        db.transaction { for ((id, value) in values) value.originalCreatedAtMillis?.let { db.execute("INSERT OR REPLACE INTO tree_legacy_instances VALUES (?,?,?)", listOf(generation, id, it)) } }
    }
    /** Resolve at most one bounded page; no Source IO occurs while the index/connection is locked. */
    fun resolveLegacySources(generation: Long, after: String?): String? {
        if (closed || generation !in candidates) return null
        val page = ArrayList<String>(128)
        rows("SELECT id FROM tree_legacy_refs WHERE generation=?" + (if (after == null) "" else " AND id>?") + " ORDER BY id LIMIT 128", if (after == null) listOf(generation) else listOf(generation, after)) { page += it.string(0)!! }
        if (page.isEmpty()) return null
        db.transaction {
            for (id in page) {
                val url = scalar("SELECT s.value FROM tree_legacy_refs r JOIN tree_legacy_sources s ON s.generation=r.generation AND s.id=r.ref WHERE r.generation=? AND r.id=?", listOf(generation, id)) ?: continue
                file(id, generation)?.let { put(it.copy(params = it.params + ("url" to url)), generation) }
            }
        }
        return page.last()
    }
    fun resolveLegacyContent(generation: Long, after: String?): String? {
        if (closed || generation !in candidates) return null
        val keys = ArrayList<String>(128)
        rows("SELECT DISTINCT gcid FROM tree_legacy_content WHERE generation=?" + (if (after == null) "" else " AND gcid>?") + " ORDER BY gcid LIMIT 128", if (after == null) listOf(generation) else listOf(generation, after)) { keys += it.string(0)!! }
        if (keys.isEmpty()) return null
        db.transaction {
            for (gcid in keys) rows("SELECT bytes,payload FROM tree_legacy_content WHERE generation=? AND gcid=?", listOf(generation, gcid)) { c ->
                val key = ContentKey(gcid, c.long(0)!!)
                val observed = json.decodeFromString(ContentMetadata.serializer(), c.string(1)!!)
                putContent(key, content(key)?.merge(observed) ?: ContentMetadata().merge(observed))
            }
            flushObservedMetadata()
        }
        return keys.last()
    }
    fun finishLegacy(generation: Long, header: DriveTreeIndex.PersistedTreeSnapshot): Boolean {
        if (closed || generation !in candidates || complete) return false
        try {
            db.transaction {
                commitCandidate(generation, header.lastUsageBytes, header.lastFullSyncAtMillis)
                db.execute("INSERT OR IGNORE INTO tree_instances SELECT i.id,i.created FROM tree_legacy_instances i JOIN tree_nodes n ON n.generation=0 AND n.id=i.id WHERE i.generation=?", listOf(generation))
                meta("complete", header.complete.toString())
                header.dirtyFolders.forEach { coverage(it, true) }
                rebuildAggregates()
            }
            revision++
        } catch (error: Throwable) {
            touchedFolders.clear()
            rebuildAll = false
            invalidateAllCaches()
            throw error
        }
        return true
    }

    fun pruneMutationHistory(through: Long) {
        if (closed) return
        val cutoff = minOf(through, candidates.values.minOrNull() ?: sequence)
        db.transaction {
            db.execute("DELETE FROM tree_deleted WHERE seq<=?", listOf(cutoff))
            db.execute("DELETE FROM tree_overrides WHERE name_seq<=? AND parent_seq<=?", listOf(cutoff, cutoff))
        }
    }

    fun beginCandidate(): Long {
        if (closed) return -1
        val id = ++nextGeneration
        if (candidates.isEmpty()) {
            candidateContentCache.clear()
            candidateContentExhaustive = scalar("SELECT 1 FROM tree_content LIMIT 1") == null
        }
        candidates[id] = sequence
        return id
    }
    fun appendCandidate(generation: Long, files: List<FileStat>) {
        if (closed || generation !in candidates) return
        db.transaction { files.forEach { if (!VaultEntry.isVaulted(it.id)) put(it, generation) } }
    }
    private fun flushObservedMetadata() {
        if (touchedFolders.isNotEmpty()) { rebuildAggregates(); revision++ }
    }
    fun abortCandidate(generation: Long) {
        if (closed) return
        candidates.remove(generation)
        if (candidates.isEmpty()) clearCandidateContentCache()
        db.transaction {
            db.execute("DELETE FROM tree_nodes WHERE generation=?", listOf(generation))
            db.execute("DELETE FROM tree_vault WHERE generation=?", listOf(generation))
            db.execute("DELETE FROM tree_vault_state WHERE generation=?", listOf(generation))
            for (table in listOf("tree_legacy_sources", "tree_legacy_refs", "tree_legacy_instances", "tree_legacy_content")) db.execute("DELETE FROM $table WHERE generation=?", listOf(generation))
        }
    }
    fun commitCandidate(generation: Long, usage: Long?, syncedAt: Long, syncCursor: String? = null): Set<String> {
        if (closed) return emptySet()
        val baseline = candidates.remove(generation) ?: return emptySet()
        val changed = HashSet<String>()
        mutate {
            db.execute("CREATE TEMP TABLE IF NOT EXISTS tree_candidate_folders(id TEXT PRIMARY KEY)")
            db.execute("DELETE FROM tree_candidate_folders")
            db.execute("INSERT INTO tree_candidate_folders SELECT id FROM tree_nodes WHERE generation=? AND folder=1", listOf(generation))
            // Preserve authoritative foreground listings and every subsequent per-instance write.
            db.execute("DELETE FROM tree_nodes WHERE generation=? AND parent IN (SELECT id FROM tree_coverage WHERE seq>? AND dirty=0)", listOf(generation, baseline))
            db.execute("INSERT OR REPLACE INTO tree_nodes SELECT ?,id,parent,folder,virtual,name,norm_name,excluded,bytes,gcid,duration,video,payload,seq FROM tree_nodes WHERE generation=0 AND (seq>? OR parent IN (SELECT id FROM tree_coverage WHERE seq>?))", listOf(generation, baseline, baseline))
            rows("SELECT id,name,name_seq,parent,parent_seq FROM tree_overrides WHERE name_seq>? OR parent_seq>?", listOf(baseline, baseline)) { c ->
                file(c.string(0)!!, generation)?.let { f ->
                    val ownSeq = scalar("SELECT seq FROM tree_nodes WHERE generation=? AND id=?", listOf(generation, f.id))?.toLongOrNull() ?: 0L
                    val name = c.string(1)?.takeIf { c.long(2)!! > baseline && c.long(2)!! >= ownSeq } ?: f.name
                    val parent = c.string(3)?.takeIf { c.long(4)!! > baseline && c.long(4)!! >= ownSeq } ?: f.parentId
                    put(f.copy(name = name, parentId = parent), generation)
                }
            }
            db.execute("WITH RECURSIVE removed(id) AS (SELECT id FROM tree_deleted WHERE seq>? UNION SELECT n.id FROM removed r CROSS JOIN tree_nodes n ON n.generation=? AND n.parent=r.id) DELETE FROM tree_nodes WHERE generation=? AND id IN (SELECT id FROM removed)", listOf(baseline, generation, generation))
            val liveVaultFolders = HashSet<String>()
            rows("SELECT folder FROM tree_vault_state WHERE generation=0 UNION SELECT folder FROM tree_vault_revision WHERE seq>?", listOf(baseline)) { liveVaultFolders += it.string(0)!! }
            for (folder in liveVaultFolders) {
                val preserve = (scalar("SELECT seq FROM tree_coverage WHERE id=?", listOf(folder))?.toLongOrNull() ?: 0L) > baseline ||
                    (scalar("SELECT seq FROM tree_vault_revision WHERE folder=?", listOf(folder))?.toLongOrNull() ?: 0L) > baseline
                val sameManifest = winner(folder)?.id?.let { it == winner(folder, generation)?.id } == true
                val candidateHasVault = scalar("SELECT folder FROM tree_vault_state WHERE generation=? AND folder=?", listOf(generation, folder)) != null
                if (preserve || (sameManifest && !candidateHasVault)) {
                    db.execute("DELETE FROM tree_nodes WHERE generation=? AND parent=? AND virtual=1", listOf(generation, folder))
                    db.execute("DELETE FROM tree_vault WHERE generation=? AND folder=?", listOf(generation, folder))
                    db.execute("DELETE FROM tree_vault_state WHERE generation=? AND folder=?", listOf(generation, folder))
                    db.execute("INSERT INTO tree_vault_state SELECT ?,folder,manifest_id FROM tree_vault_state WHERE generation=0 AND folder=?", listOf(generation, folder))
                    db.execute("INSERT INTO tree_vault SELECT ?,folder,id,payload FROM tree_vault WHERE generation=0 AND folder=?", listOf(generation, folder))
                    db.execute("INSERT OR REPLACE INTO tree_nodes SELECT ?,id,parent,folder,virtual,name,norm_name,excluded,bytes,gcid,duration,video,payload,seq FROM tree_nodes WHERE generation=0 AND parent=? AND virtual=1", listOf(generation, folder))
                }
            }
            val candidateVaultStates = ArrayList<Pair<String, String?>>()
            rows("SELECT folder,manifest_id FROM tree_vault_state WHERE generation=?", listOf(generation)) { candidateVaultStates += it.string(0)!! to it.string(1) }
            for ((folder, manifest) in candidateVaultStates) {
                if ((folder.isNotEmpty() && file(folder, generation)?.isFolder != true) || manifest != winner(folder, generation)?.id) clearVault(folder, generation)
            }
            rows("SELECT DISTINCT a.parent FROM tree_nodes a LEFT JOIN tree_nodes b ON b.generation=? AND b.id=a.id WHERE a.generation=0 AND (b.id IS NULL OR a.payload<>b.payload) UNION SELECT DISTINCT b.parent FROM tree_nodes b LEFT JOIN tree_nodes a ON a.generation=0 AND a.id=b.id WHERE b.generation=? AND (a.id IS NULL OR a.payload<>b.payload)", listOf(generation, generation)) { changed += it.string(0)!! }
            db.execute("DELETE FROM tree_nodes WHERE generation=0")
            db.execute("UPDATE tree_nodes SET generation=0 WHERE generation=?", listOf(generation))
            db.execute("DELETE FROM tree_vault WHERE generation=0")
            db.execute("UPDATE tree_vault SET generation=0 WHERE generation=?", listOf(generation))
            db.execute("DELETE FROM tree_vault_state WHERE generation=0")
            db.execute("UPDATE tree_vault_state SET generation=0 WHERE generation=?", listOf(generation))
            db.execute("DELETE FROM tree_coverage WHERE seq<=?", listOf(baseline))
            db.execute("INSERT OR IGNORE INTO tree_coverage SELECT id,1,? FROM tree_nodes WHERE generation=0 AND folder=1 AND id NOT IN (SELECT id FROM tree_candidate_folders)", listOf(sequence))
            db.execute("INSERT OR IGNORE INTO tree_coverage SELECT id,0,? FROM tree_nodes WHERE generation=0 AND folder=1", listOf(sequence))
            db.execute("INSERT OR IGNORE INTO tree_coverage VALUES ('',0,?)", listOf(sequence))
            db.execute("DELETE FROM tree_instances WHERE id NOT IN (SELECT id FROM tree_nodes WHERE generation=0)")
            db.execute("INSERT OR REPLACE INTO tree_instances SELECT r.id,r.original_created FROM tree_restores r JOIN tree_nodes n ON n.generation=0 AND n.id=r.id AND n.gcid=r.gcid AND n.bytes=r.bytes AND n.folder=0")
            meta("complete", "true")
            if ((meta("usage_seq")?.toLongOrNull() ?: 0L) <= baseline && usage != null) meta("usage", usage.toString())
            meta("synced", syncedAt.toString())
            meta("sync_cursor", syncCursor)
            meta("sync_cursor_version", "2")
            invalidateFolderProjection()
            invalidateCoverage()
            cachedManifestRows = null
            rebuildAll = true
        }
        candidates.clear()
        clearCandidateContentCache()
        runCatching { db.query("PRAGMA wal_checkpoint(PASSIVE)").use { while (it.next()) Unit } }
            .onFailure { err -> PikoLog.w(TAG, "Post-commit WAL checkpoint (PASSIVE) failed (acct=${logAccountSlot(ownerAccount)})", err) }
        return changed
    }

    private class UsageAcc(var bytes: Long = 0L, var count: Long = 0L)

    private class FolderStatAcc(
        var physBytes: Long = 0L,
        var physCount: Long = 0L,
        var physDuration: Long = 0L,
        var physKnown: Long = 0L,
        var physUnknown: Long = 0L,
        var physListingComplete: Boolean,
        var physMetaComplete: Boolean = true,
        var vaultBytes: Long = 0L,
        var vaultCount: Long = 0L,
        var vaultDuration: Long = 0L,
        var vaultKnown: Long = 0L,
        var vaultUnknown: Long = 0L,
        var vaultListingComplete: Boolean,
        var vaultMetaComplete: Boolean = true,
        var structuralValid: Boolean = true,
        var pendingChildren: Int = 0,
    ) {
        fun addPhysical(bytes: Long, count: Long, duration: Long, known: Long, unknown: Long) {
            physBytes = saturatedAdd(physBytes, bytes)
            physCount = saturatedAdd(physCount, count)
            physDuration = saturatedAdd(physDuration, duration)
            physKnown = saturatedAdd(physKnown, known)
            physUnknown = saturatedAdd(physUnknown, unknown)
            if (unknown != 0L) physMetaComplete = false
        }

        fun addVaulted(bytes: Long, count: Long, duration: Long, known: Long, unknown: Long) {
            vaultBytes = saturatedAdd(vaultBytes, bytes)
            vaultCount = saturatedAdd(vaultCount, count)
            vaultDuration = saturatedAdd(vaultDuration, duration)
            vaultKnown = saturatedAdd(vaultKnown, known)
            vaultUnknown = saturatedAdd(vaultUnknown, unknown)
            if (unknown != 0L) vaultMetaComplete = false
        }

        fun build(): SubtreeStats {
            val phys = if (physBytes == 0L && physCount == 0L && physDuration == 0L && physKnown == 0L && physUnknown == 0L && physMetaComplete) {
                if (physListingComplete) EMPTY_COMPLETE_CONTENT else EMPTY_INCOMPLETE_CONTENT
            } else {
                ContentStats(
                    bytes = physBytes,
                    fileCount = physCount,
                    durationMillis = physDuration,
                    knownDurationFiles = physKnown,
                    unknownDurationFiles = physUnknown,
                    listingComplete = physListingComplete,
                    metadataComplete = physMetaComplete,
                )
            }
            val vault = if (vaultBytes == 0L && vaultCount == 0L && vaultDuration == 0L && vaultKnown == 0L && vaultUnknown == 0L && vaultMetaComplete) {
                if (vaultListingComplete) EMPTY_COMPLETE_CONTENT else EMPTY_INCOMPLETE_CONTENT
            } else {
                ContentStats(
                    bytes = vaultBytes,
                    fileCount = vaultCount,
                    durationMillis = vaultDuration,
                    knownDurationFiles = vaultKnown,
                    unknownDurationFiles = vaultUnknown,
                    listingComplete = vaultListingComplete,
                    metadataComplete = vaultMetaComplete,
                )
            }
            return SubtreeStats(physical = phys, vaulted = vault, structuralValid = structuralValid)
        }

        companion object {
            fun saturatedAdd(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
        }
    }

    private fun rebuildAggregates() {
        val replaceAll = rebuildAll || cachedFolderProjection == null || cachedSubtreeStats == null
        val aggregateSql = "INSERT INTO tree_aggregate SELECT parent,virtual,SUM(bytes),COUNT(*),COALESCE(SUM(duration),0),SUM(CASE WHEN video=1 AND duration>0 THEN 1 ELSE 0 END),SUM(CASE WHEN video=1 AND (duration IS NULL OR duration<=0) THEN 1 ELSE 0 END) FROM tree_nodes WHERE generation=0 AND folder=0 AND excluded=0"
        val usageSql = "INSERT INTO tree_direct_usage SELECT parent,SUM(bytes),COUNT(*) FROM tree_nodes WHERE generation=0 AND folder=0 AND virtual=0"
        if (rebuildAll) {
            db.execute("DELETE FROM tree_aggregate")
            db.execute("DELETE FROM tree_direct_usage")
            db.execute("$aggregateSql GROUP BY parent,virtual")
            db.execute("$usageSql GROUP BY parent")
        } else for (folder in touchedFolders) {
            db.execute("DELETE FROM tree_aggregate WHERE folder=?", listOf(folder))
            db.execute("DELETE FROM tree_direct_usage WHERE folder=?", listOf(folder))
            db.execute("$aggregateSql AND parent=? GROUP BY parent,virtual", listOf(folder))
            db.execute("$usageSql AND parent=? GROUP BY parent", listOf(folder))
        }
        val subtreeStats = computeFolderStats()
        val folders = ensureFolderProjection().folders
        val usageByFolder = HashMap<String, UsageAcc>(folders.size + 1)
        val seen = HashSet<String>(16)
        rows("SELECT folder,bytes,count FROM tree_direct_usage") { c ->
            var parent = c.string(0)!!
            val bytes = (c.long(1) ?: 0L).coerceAtLeast(0L)
            val count = (c.long(2) ?: 0L).coerceAtLeast(0L)
            seen.clear()
            while (seen.add(parent) && (parent.isEmpty() || parent in folders)) {
                val acc = usageByFolder.getOrPut(parent) { UsageAcc() }
                acc.bytes = saturated(acc.bytes, bytes)
                acc.count = saturated(acc.count, count)
                if (parent.isEmpty()) break
                parent = folders.getValue(parent).parentId
            }
        }
        val affected = HashSet<String>()
        for (folder in touchedFolders) {
            var id = folder
            while (affected.add(id) && id.isNotEmpty()) id = folders[id]?.parentId ?: break
        }
        if (replaceAll) db.execute("DELETE FROM tree_subtree")
        for ((id, stats) in subtreeStats) {
            if (!replaceAll && id !in affected) continue
            val physicalUsage = usageByFolder[id]
            db.execute(
                "INSERT OR REPLACE INTO tree_subtree VALUES (?,?,?,?)",
                listOf(id, json.encodeToString(SubtreeStats.serializer(), stats), physicalUsage?.bytes ?: 0L, physicalUsage?.count ?: 0L),
            )
        }
        cachedSubtreeStats = subtreeStats
        meta("aggregate_version", "2")
        touchedFolders.clear(); rebuildAll = false
    }
    private fun saturated(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

    private fun computeFolderStats(): Map<String, SubtreeStats> {
        val folderProj = ensureFolderProjection()
        val folders = folderProj.folders
        val childrenByParent = folderProj.childrenByParent
        val (listed, dirty) = ensureCoverage()
        val isComplete = complete

        val excluded = HashSet<String>()
        val queue = ArrayDeque<String>()
        for (file in folders.values) if (isPikoInternalFolder(file, file.parentId)) queue.add(file.id)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (excluded.add(id)) queue.addAll(childrenByParent[id].orEmpty())
        }

        val accByFolder = HashMap<String, FolderStatAcc>(folders.size + 1)
        fun addFolder(id: String) {
            val folderComplete = (isComplete || id in listed) && id !in dirty
            accByFolder[id] = FolderStatAcc(
                physListingComplete = folderComplete,
                vaultListingComplete = folderComplete,
            )
        }
        addFolder("")
        for (file in folders.values) {
            if (file.isFolder && file.id !in excluded && !file.trashed) addFolder(file.id)
        }
        for (file in folders.values) {
            if (file.id in excluded || file.trashed || VaultStore.looksLikeManifest(file) || VaultStore.isRecoveryFile(file)) continue
            val parentAcc = accByFolder[file.parentId]
            if (parentAcc != null) parentAcc.pendingChildren++
            else accByFolder[file.id]?.structuralValid = false
        }

        queue.clear()
        for ((id, acc) in accByFolder) if (acc.pendingChildren == 0) queue.add(id)
        val visited = HashSet<String>(accByFolder.size)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!visited.add(id) || id.isEmpty()) continue
            val parentId = folders[id]?.parentId ?: continue
            val parentAcc = accByFolder[parentId] ?: continue
            val childAcc = accByFolder.getValue(id)
            if (!childAcc.physListingComplete) parentAcc.physListingComplete = false
            if (!childAcc.vaultListingComplete) parentAcc.vaultListingComplete = false
            if (!childAcc.structuralValid) parentAcc.structuralValid = false
            val remaining = parentAcc.pendingChildren - 1
            parentAcc.pendingChildren = remaining
            if (remaining == 0) queue.add(parentId)
        }
        // Kahn elimination leaves cycles and their dependent aggregates unprocessed.
        for ((id, acc) in accByFolder) if (id !in visited) {
            acc.structuralValid = false
            acc.physListingComplete = false
            acc.vaultListingComplete = false
        }

        // Walk only folder ancestry. No file identity or intermediate ContentStats/SubtreeStats is allocated.
        val seen = HashSet<String>(16)
        rows("SELECT folder,virtual,bytes,count,duration,known,unknown FROM tree_aggregate") { c ->
            var id = c.string(0)!!
            val isVirtual = c.long(1) == 1L
            val bytes = (c.long(2) ?: 0L).coerceAtLeast(0L)
            val count = (c.long(3) ?: 0L).coerceAtLeast(0L)
            val duration = (c.long(4) ?: 0L).coerceAtLeast(0L)
            val known = (c.long(5) ?: 0L).coerceAtLeast(0L)
            val unknown = (c.long(6) ?: 0L).coerceAtLeast(0L)
            seen.clear()
            while (seen.add(id)) {
                val acc = accByFolder[id] ?: break
                if (isVirtual) acc.addVaulted(bytes, count, duration, known, unknown)
                else acc.addPhysical(bytes, count, duration, known, unknown)
                if (id.isEmpty()) break
                id = folders[id]?.parentId ?: break
            }
        }

        val result = HashMap<String, SubtreeStats>(accByFolder.size)
        for ((id, acc) in accByFolder) result[id] = acc.build()
        return result
    }
    fun filesByIds(ids: Collection<String>): Map<String, FileStat> {
        if (closed) return emptyMap()
        val result = HashMap<String, FileStat>()
        for (chunk in ids.distinct().chunked(400)) {
            rows("SELECT payload FROM tree_nodes WHERE generation=0 AND id IN (${chunk.joinToString { "?" }})", chunk) {
                val file = decode(it.string(0)!!)
                result[file.id] = file
            }
        }
        return result
    }

    fun sortContext(state: DriveTreeIndex.State, folderId: String? = null, files: List<FileStat>? = null, order: PikoFileSortOrder? = null): SortContext {
        if (closed) return SortContext(state.revision, emptyMap(), emptyMap(), emptyMap(), emptyMap())
        val stats = ensureSubtreeStats()
        if (files == null && folderId == null) {
            val folderProj = ensureFolderProjection()
            return SortContext(state.revision, stats, folderProj.created, folderProj.modified, emptyMap())
        }
        val folderProj = cachedFolderProjection
        val created = HashMap<String, Long>(); val modified = HashMap<String, Long>()
        val metadataByKey = HashMap<ContentKey, ContentMetadata>()
        fun collect(f: FileStat, original: Long?) {
            if (f.isFolder && original == null && folderProj != null && f.id in folderProj.folders) {
                folderProj.created[f.id]?.let { created[f.id] = it }
                folderProj.modified[f.id]?.let { modified[f.id] = it }
                return
            }
            (original ?: treeEpochMillis(f.createdTime))?.let { created[f.id] = it }
            treeEpochMillis(f.modifiedTime)?.let { modified[f.id] = it }
        }
        val requested = files ?: buildList {
            rows("SELECT payload FROM tree_nodes WHERE generation=0 AND parent=?", listOf(folderId)) { add(decode(it.string(0)!!)) }
        }
        val original = HashMap<String, Long>()
        if (order == null || order == PikoFileSortOrder.TIME_ASC || order == PikoFileSortOrder.TIME_DESC) {
            for (chunk in requested.filterNot { it.isFolder }.map { it.id }.distinct().chunked(400)) {
                rows("SELECT id,original_created FROM tree_instances WHERE id IN (${chunk.joinToString { "?" }})", chunk) {
                    original[it.string(0)!!] = it.long(1)!!
                }
            }
        }
        if (order == null || order == PikoFileSortOrder.DURATION_ASC || order == PikoFileSortOrder.DURATION_DESC) {
            val keys = requested.mapNotNull { ContentKey.of(it) }.toSet()
            for (chunk in keys.map { it.gcid }.distinct().chunked(400)) {
                rows("SELECT gcid,bytes,payload FROM tree_content WHERE gcid IN (${chunk.joinToString { "?" }})", chunk) {
                    val key = ContentKey(it.string(0)!!, it.long(1)!!)
                    if (key in keys) metadataByKey[key] = json.decodeFromString(ContentMetadata.serializer(), it.string(2)!!)
                }
            }
        }
        for (file in requested) collect(file, original[file.id])
        return SortContext(state.revision, stats, created, modified, metadataByKey)
    }
    fun folderUsage(root: String, state: DriveTreeIndex.State): FolderUsage? {
        if (closed) return null
        val memStats = cachedSubtreeStats
        if (memStats != null) {
            val stats = memStats[root] ?: return null
            if (!stats.structuralValid || !stats.physical.listingComplete) return null
            return useQuery("SELECT usage_bytes,usage_count FROM tree_subtree WHERE folder=?", listOf(root)) { cursor ->
                if (!cursor.next()) null
                else FolderUsage(cursor.long(1)!!.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), cursor.long(0)!!, FolderUsage.Progress.COMPLETE)
            }
        }
        return useQuery("SELECT payload,usage_bytes,usage_count FROM tree_subtree WHERE folder=?", listOf(root)) { cursor ->
            if (!cursor.next()) return@useQuery null
            val stats = json.decodeFromString(SubtreeStats.serializer(), cursor.string(0)!!)
            if (!stats.structuralValid || !stats.physical.listingComplete) return@useQuery null
            FolderUsage(cursor.long(2)!!.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), cursor.long(1)!!, FolderUsage.Progress.COMPLETE)
        }
    }
}
