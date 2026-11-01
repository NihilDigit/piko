package dev.piko.shared.data

import dev.piko.data.repository.isPlayableVideo
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileTag
import kotlinx.serialization.json.Json

/** Disk owns file identities. Heap projections contain folders and caller-requested rows only. */
internal class SqliteTreeStore(
    private val db: PikoTreeDatabase,
    ownerAccount: String,
    private val locallyCachedManifestIds: () -> Set<String> = ::emptySet,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private var closed = false
    private var revision = 0L
    private var sequence = 0L
    private var nextGeneration = 0L
    private val candidates = HashMap<Long, Long>()
    private val touchedFolders = HashSet<String>()
    private var rebuildAll = false
    private val canonical = TreeCanonicalizer()

    // In-memory hot projections of folder skeleton & materialized subtree aggregates (zero file rows retained).
    private var cachedFolders: Map<String, FileStat>? = null
    private var cachedChildrenByParent: Map<String, Set<String>>? = null
    private var cachedSubtreeStats: Map<String, SubtreeStats>? = null
    // Bounded ContentMetadata cache active during candidate builds to avoid 120,000+ redundant SELECTs on Android.
    private val candidateContentCache = object : LinkedHashMap<ContentKey, ContentMetadata>(2048, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ContentKey, ContentMetadata>): Boolean = size > 8192
    }
    private var candidateContentExhaustive = false

    private fun clearCandidateContentCache() {
        candidateContentCache.clear()
        candidateContentExhaustive = false
    }

    private fun invalidateFolderProjection() {
        cachedFolders = null
        cachedChildrenByParent = null
    }

    init {
        db.execute("CREATE TABLE IF NOT EXISTS tree_meta (key TEXT PRIMARY KEY, value TEXT)")
        val owner = scalar("SELECT value FROM tree_meta WHERE key='owner_account'")
        if (owner != ownerAccount || meta("schema_version") != "1") {
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
        if (!complete && syncedAt > 0L && scalar("SELECT 1 FROM tree_nodes WHERE generation=0 LIMIT 1") != null) {
            // 自愈先前已完成全盘同步、却被全盘 ContentsChanged(null) 误抹除 complete 标记的持久库，随后由后台哨兵探针对账增量变更
            db.transaction {
                sequence++
                db.execute("INSERT OR IGNORE INTO tree_coverage SELECT id,0,? FROM tree_nodes WHERE generation=0 AND folder=1", listOf(sequence))
                db.execute("INSERT OR IGNORE INTO tree_coverage VALUES ('',0,?)", listOf(sequence))
                meta("complete", "true")
                meta("sequence", sequence.toString())
                rebuildAll = true
                rebuildAggregates()
            }
        } else if (meta("aggregate_version") != "2") {
            rebuildAll = true
            db.transaction { rebuildAggregates() }
        }
        runCatching { db.query("PRAGMA wal_checkpoint(TRUNCATE)").close() }
    }

    private fun scalar(sql: String, args: List<Any?> = emptyList()): String? = db.scalarString(sql, args)
    private inline fun rows(sql: String, args: List<Any?> = emptyList(), consume: (PikoTreeCursor) -> Unit) {
        val c = db.query(sql, args)
        try { while (c.next()) consume(c) } finally { c.close() }
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

    fun close() {
        if (!closed) {
            closed = true
            candidates.clear()
            clearCandidateContentCache()
            canonical.clear()
            invalidateFolderProjection()
            cachedSubtreeStats = null
            db.close()
        }
    }
    private fun mutate(block: () -> Unit) {
        if (closed) return
        db.transaction {
            val previousUsage = usage?.takeIf { complete }
            val previousBytes = if (previousUsage == null) 0L else scalar("SELECT COALESCE(SUM(bytes),0) FROM tree_direct_usage")!!.toLong()
            sequence++
            block()
            val fullReplacement = rebuildAll
            rebuildAggregates()
            if (!fullReplacement && previousUsage != null && (meta("usage_seq")?.toLongOrNull() ?: 0L) < sequence) {
                val nextBytes = scalar("SELECT COALESCE(SUM(bytes),0) FROM tree_direct_usage")!!.toLong()
                val delta = nextBytes - previousBytes
                if (delta != 0L) {
                    val nextUsage = if (delta > 0L && previousUsage > Long.MAX_VALUE - delta) Long.MAX_VALUE else (previousUsage + delta).coerceAtLeast(0)
                    meta("usage", nextUsage.toString()); meta("usage_seq", sequence.toString())
                }
            }
            meta("sequence", sequence.toString())
        }
        revision++
    }
    fun clear() = mutate {
        for (table in listOf("tree_nodes", "tree_content", "tree_instances", "tree_coverage", "tree_vault", "tree_deleted", "tree_aggregate", "tree_subtree", "tree_restores", "tree_overrides", "tree_vault_revision", "tree_vault_state", "tree_direct_usage")) db.execute("DELETE FROM $table")
        meta("complete", "false"); meta("usage", null); meta("synced", null)
        candidates.clear(); clearCandidateContentCache(); rebuildAll = true
        invalidateFolderProjection()
        cachedSubtreeStats = null
    }
    fun file(id: String, generation: Long = 0): FileStat? = if (closed) null else scalar("SELECT payload FROM tree_nodes WHERE generation=? AND id=?", listOf(generation, id))?.let(::decode)
    /** Nonmatching files never allocate FileStat, params, tags or metadata. */
    fun searchCandidates(query: String, extraFolderTitle: (FileStat) -> String?, consume: (Int, FileStat) -> Unit) {
        if (closed) return
        rows("SELECT id,norm_name,folder FROM tree_nodes WHERE generation=0 AND excluded=0") { c ->
            val nameScore = dev.piko.shared.search.cjkFuzzyScore(query, c.string(1)!!)
            if (nameScore != null) file(c.string(0)!!)?.let { consume(nameScore, it) }
            else if (c.long(2) == 1L) {
                val folder = file(c.string(0)!!) ?: return@rows
                extraFolderTitle(folder)?.let { dev.piko.shared.search.cjkFuzzyScore(query, it) }?.let { consume(it, folder) }
            }
        }
    }

    fun children(id: String, realOnly: Boolean = false): DriveTreeIndex.FolderChildren {
        if (closed) return DriveTreeIndex.FolderChildren(emptyList(), DriveTreeIndex.FolderCoverage.UNKNOWN)
        val files = ArrayList<FileStat>()
        rows("SELECT payload FROM tree_nodes WHERE generation=0 AND parent=?" + if (realOnly) " AND virtual=0" else "", listOf(id)) { files += decode(it.string(0)!!) }
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
        val cursor = db.query("SELECT id FROM tree_nodes WHERE generation=0 AND virtual=0")
        return try { while (cursor.next()) if (cursor.string(0)!! !in ids) return true; false } finally { cursor.close() }
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

    private fun ensureFolderProjection(): Pair<Map<String, FileStat>, Map<String, Set<String>>> {
        val existingFolders = cachedFolders
        val existingChildren = cachedChildrenByParent
        if (existingFolders != null && existingChildren != null) {
            return existingFolders to existingChildren
        }
        val folders = HashMap<String, FileStat>()
        rows("SELECT payload FROM tree_nodes WHERE generation=0 AND folder=1") {
            val f = decode(it.string(0)!!)
            folders[f.id] = f
        }
        val children = folders.values.groupBy { it.parentId }.mapValues { (_, fs) -> fs.mapTo(HashSet()) { it.id } as Set<String> }
        val frozenFolders = folders.toMap()
        val frozenChildren = children.toMap()
        cachedFolders = frozenFolders
        cachedChildrenByParent = frozenChildren
        return frozenFolders to frozenChildren
    }

    private fun ensureSubtreeStats(): Map<String, SubtreeStats> {
        cachedSubtreeStats?.let { return it }
        val stats = HashMap<String, SubtreeStats>()
        rows("SELECT folder,payload FROM tree_subtree") {
            stats[it.string(0)!!] = json.decodeFromString(SubtreeStats.serializer(), it.string(1)!!)
        }
        val frozen = stats.toMap()
        cachedSubtreeStats = frozen
        return frozen
    }

    fun projection(): DriveTreeIndex.State {
        if (closed) return DriveTreeIndex.State(revision = revision)
        val (folders, childrenByParent) = ensureFolderProjection()
        val dirty = HashSet<String>(); val listed = HashSet<String>()
        rows("SELECT id,dirty FROM tree_coverage") { if (it.long(1) == 1L) dirty += it.string(0)!! else listed += it.string(0)!! }
        val manifests = HashMap<String, FileStat>()
        val cachedIds = locallyCachedManifestIds()
        rows("SELECT payload FROM tree_nodes WHERE generation=0 AND excluded=1 AND name LIKE '.piko-vault-%'") {
            val f = decode(it.string(0)!!)
            val winner = VaultStore.winnerOf(listOfNotNull(manifests[f.parentId], f), cachedIds)
            if (winner != null) manifests[f.parentId] = winner
        }
        val stats = ensureSubtreeStats()
        val created = HashMap<String, Long>(folders.size)
        val modified = HashMap<String, Long>(folders.size)
        for (folder in folders.values) {
            treeEpochMillis(folder.createdTime)?.let { created[folder.id] = it }
            treeEpochMillis(folder.modifiedTime)?.let { modified[folder.id] = it }
        }
        val snapshot = DriveTreeIndex.State(
            entries = folders,
            childrenByParent = childrenByParent,
            manifestsByFolder = manifests,
            listedFolders = listed,
            dirtyFolders = dirty,
            lastUsageBytes = usage,
            complete = complete,
            revision = revision,
        )
        return snapshot.copy(sortProjection = SortContext(revision, stats, created, modified, emptyMap()))
    }
    private fun lookupContentForWrite(key: ContentKey, generation: Long): ContentMetadata? {
        if (generation != 0L) {
            candidateContentCache[key]?.let { return it }
            if (candidateContentExhaustive) return null
        }
        return content(key)?.also { if (generation != 0L) candidateContentCache[key] = it }
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
        if (generation == 0L) {
            scalar("SELECT parent FROM tree_nodes WHERE generation=0 AND id=?", listOf(clean.id))?.let(touchedFolders::add)
            touchedFolders += clean.parentId
            if (clean.isFolder) invalidateFolderProjection()
        }
        db.execute("INSERT OR REPLACE INTO tree_nodes VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)", listOf(generation, clean.id, clean.parentId, if (clean.isFolder) 1 else 0, if (VaultEntry.isVaulted(clean.id)) 1 else 0, clean.name, dev.piko.shared.search.normalizeCjk(clean.name), if (VaultStore.looksLikeManifest(clean) || VaultStore.isRecoveryFile(clean)) 1 else 0, clean.sizeBytes.coerceAtLeast(0), key?.gcid, metadata.durationMillis, if (clean.isPlayableVideo()) 1 else 0, encode(clean), if (generation == 0L) sequence else 0L))
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
        for (f in files) if (!f.trashed && !VaultEntry.isVaulted(f.id)) {
            if (VaultStore.looksLikeManifest(f)) {
                if (f.parentId !in before) before[f.parentId] = winner(f.parentId)?.id
                file(f.id)?.parentId?.let { old -> if (old !in before) before[old] = winner(old)?.id }
            }
            put(f)
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
                rows("WITH RECURSIVE folders(id) AS (SELECT id FROM tree_nodes WHERE generation=0 AND parent=? AND folder=1 AND id NOT IN (SELECT id FROM tree_observed) UNION SELECT n.id FROM tree_nodes n JOIN folders f ON n.parent=f.id WHERE n.generation=0 AND n.folder=1) SELECT id FROM folders", listOf(parent)) { detached += it.string(0)!! }
                if (detached.isNotEmpty()) invalidateFolderProjection()
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
                    if (generation == 0L) {
                        rows("SELECT DISTINCT parent FROM tree_nodes WHERE generation=0 AND gcid=? AND bytes=?", listOf(key.gcid, key.sizeBytes)) { touchedFolders += it.string(0)!! }
                        db.execute("UPDATE tree_nodes SET duration=? WHERE gcid=? AND bytes=?", listOf(merged.durationMillis, key.gcid, key.sizeBytes))
                    }
                }
            }
        }
    }

    fun vault(folder: String, entries: List<VaultEntry>, expected: String?, requireMatch: Boolean, starred: Set<String>, generation: Long = 0): Boolean {
        if (closed || (generation != 0L && generation !in candidates)) return false
        if (requireMatch && winner(folder, generation)?.id != expected) return false
        val write = { writeVaultFolderInTx(folder, entries, starred, generation) }
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
            candidateContentCache[key] = metadata
        }
        if (previous == metadata) return
        if (previous == null && metadata == ContentMetadata()) return
        db.execute("INSERT OR REPLACE INTO tree_content VALUES (?,?,?)", listOf(key.gcid, key.sizeBytes, json.encodeToString(ContentMetadata.serializer(), metadata)))
        if (updateLiveDuration && previous?.durationMillis != metadata.durationMillis) {
            rows("SELECT DISTINCT parent FROM tree_nodes WHERE generation=0 AND gcid=? AND bytes=?", listOf(key.gcid, key.sizeBytes)) { touchedFolders += it.string(0)!! }
            db.execute("UPDATE tree_nodes SET duration=? WHERE gcid=? AND bytes=?", listOf(metadata.durationMillis, key.gcid, key.sizeBytes))
        }
    }
    fun observeContent(key: ContentKey, value: ContentMetadata) = mutate {
        val normalized = ContentKey.of(key.gcid, key.sizeBytes) ?: return@mutate
        val prev = content(normalized)
        val merged = prev?.merge(value) ?: ContentMetadata().merge(value)
        candidateContentCache.remove(normalized)
        putContent(normalized, merged, previousKnown = true, previousValue = prev)
        rows("SELECT DISTINCT parent FROM tree_nodes WHERE generation=0 AND gcid=? AND bytes=?", listOf(normalized.gcid, normalized.sizeBytes)) { touchedFolders += it.string(0)!! }
        db.execute("UPDATE tree_nodes SET duration=? WHERE gcid=? AND bytes=?", listOf(merged.durationMillis, normalized.gcid, normalized.sizeBytes))
    }
    fun instance(id: String, value: EntryMetadata) = mutate {
        if (value.originalCreatedAtMillis == null) db.execute("DELETE FROM tree_instances WHERE id=?", listOf(id))
        else db.execute("INSERT OR REPLACE INTO tree_instances VALUES (?,?)", listOf(id, value.originalCreatedAtMillis))
    }
    private fun coverage(id: String, dirty: Boolean) { touchedFolders += id; db.execute("INSERT OR REPLACE INTO tree_coverage VALUES (?,?,?)", listOf(id, if (dirty) 1 else 0, sequence)) }
    fun markComplete() = mutate { rebuildAll = true; meta("complete", "true") }
    fun usage(value: Long?) = mutate { meta("usage", value?.toString()); meta("usage_seq", sequence.toString()) }
    fun canary(files: List<FileStat>, value: Long?) = mutate { files.forEach { if (!VaultEntry.isVaulted(it.id)) put(it) }; if (value != null) { meta("usage", value.toString()); meta("usage_seq", sequence.toString()) } }
    fun descendants(root: String): Set<String> {
        val result = HashSet<String>()
        if (!closed) rows("WITH RECURSIVE folders(id) AS (SELECT ? UNION SELECT n.id FROM tree_nodes n JOIN folders f ON n.parent=f.id WHERE n.generation=0 AND n.folder=1) SELECT id FROM folders", listOf(root)) { result += it.string(0)!! }
        return result
    }
    private fun remove(ids: Collection<String>) {
        if (ids.any { file(it)?.isFolder == true }) invalidateFolderProjection()
        val manifestParents = HashMap<String, String?>()
        for (id in ids) file(id)?.takeIf(VaultStore::looksLikeManifest)?.parentId?.let { manifestParents[it] = winner(it)?.id }
        for (id in ids) {
            db.execute("WITH RECURSIVE removed(id) AS (SELECT ? UNION SELECT n.id FROM tree_nodes n JOIN removed r ON n.parent=r.id WHERE n.generation=0) INSERT OR REPLACE INTO tree_deleted SELECT id,? FROM removed", listOf(id, sequence))
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
                file(change.id)?.let { put(it.copy(name = change.name)) }
            }
            is DriveChange.Moved -> for (id in change.ids) {
                db.execute("INSERT OR IGNORE INTO tree_overrides(id) VALUES (?)", listOf(id))
                db.execute("UPDATE tree_overrides SET parent=?,parent_seq=? WHERE id=?", listOf(change.to, sequence, id))
                val known = file(id)
                if (known != null) put(known.copy(parentId = change.to)) else change.folders.orEmpty().forEach { coverage(it, true) }
            }
            is DriveChange.Trashed -> remove(change.ids)
            is DriveChange.Deleted -> remove(change.ids)
            is DriveChange.Created -> if (change.isFolder) {
                put(FileStat(id = change.id, parentId = change.parentId, name = change.name, kind = FileKind.FOLDER)); coverage(change.id, false)
            } else if (change.stat != null) put(change.stat) else change.folders.forEach { coverage(it, true) }
            is DriveChange.Starred -> for (id in change.ids) file(id)?.let { f -> put(f.copy(tags = f.tags.filterNot { it.name.equals("STAR", true) } + if (change.starred) listOf(FileTag(id = "STAR", name = "STAR")) else emptyList())) }
            is DriveChange.Copied -> change.folders.orEmpty().forEach { coverage(it, true) }
            is DriveChange.Restored, is DriveChange.ContentsChanged -> {
                if (change.folders == null) { rebuildAll = true; meta("complete", "false"); candidates.clear(); db.execute("DELETE FROM tree_coverage") }
                else change.folders.orEmpty().forEach { coverage(it, true) }
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
        db.transaction {
            commitCandidate(generation, header.lastUsageBytes, header.lastFullSyncAtMillis)
            db.execute("INSERT OR IGNORE INTO tree_instances SELECT i.id,i.created FROM tree_legacy_instances i JOIN tree_nodes n ON n.generation=0 AND n.id=i.id WHERE i.generation=?", listOf(generation))
            meta("complete", header.complete.toString())
            header.dirtyFolders.forEach { coverage(it, true) }
            rebuildAggregates()
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
    fun commitCandidate(generation: Long, usage: Long?, syncedAt: Long): Set<String> {
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
            db.execute("WITH RECURSIVE removed(id) AS (SELECT id FROM tree_deleted WHERE seq>? UNION SELECT n.id FROM tree_nodes n JOIN removed r ON n.parent=r.id WHERE n.generation=?) DELETE FROM tree_nodes WHERE generation=? AND id IN (SELECT id FROM removed)", listOf(baseline, generation, generation))
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
            invalidateFolderProjection()
            rebuildAll = true
        }
        candidates.clear()
        clearCandidateContentCache()
        runCatching { db.query("PRAGMA wal_checkpoint(PASSIVE)").close() }
        return changed
    }

    private fun rebuildAggregates() {
        val replaceAll = rebuildAll || cachedFolders == null || cachedSubtreeStats == null
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
        val snapshot = computeFolderStats()
        val folders = ensureFolderProjection().first
        val usageByFolder = HashMap<String, Pair<Long, Long>>()
        rows("SELECT folder,bytes,count FROM tree_direct_usage") { c ->
            var parent = c.string(0)!!
            val bytes = c.long(1)!!; val count = c.long(2)!!
            val seen = HashSet<String>()
            while (seen.add(parent) && (parent.isEmpty() || parent in folders)) {
                val previous = usageByFolder[parent] ?: (0L to 0L)
                usageByFolder[parent] = saturated(previous.first, bytes) to saturated(previous.second, count)
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
        for ((id, stats) in snapshot.subtreeStats) {
            if (!replaceAll && id !in affected) continue
            val physicalUsage = usageByFolder[id] ?: (0L to 0L)
            db.execute("INSERT OR REPLACE INTO tree_subtree VALUES (?,?,?,?)", listOf(id, json.encodeToString(SubtreeStats.serializer(), stats), physicalUsage.first, physicalUsage.second))
        }
        cachedSubtreeStats = snapshot.subtreeStats.toMap()
        meta("aggregate_version", "2")
        touchedFolders.clear(); rebuildAll = false
    }
    private fun saturated(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

    private fun folderState(): DriveTreeIndex.State {
        val (folders, childrenByParent) = ensureFolderProjection()
        val dirty = HashSet<String>(); val listed = HashSet<String>()
        rows("SELECT id,dirty FROM tree_coverage") { if (it.long(1) == 1L) dirty += it.string(0)!! else listed += it.string(0)!! }
        return DriveTreeIndex.State(entries = folders, childrenByParent = childrenByParent,
            complete = complete, dirtyFolders = dirty, listedFolders = listed, revision = revision)
    }
    private fun computeFolderStats(): SortContext {
        val state = folderState()
        val direct = HashMap<String, SubtreeStats>()
        rows("SELECT folder,virtual,bytes,count,duration,known,unknown FROM tree_aggregate") { c ->
            val own = ContentStats(c.long(2)!!, c.long(3)!!, c.long(4)!!, c.long(5)!!, c.long(6)!!, metadataComplete = c.long(6) == 0L)
            val id = c.string(0)!!
            direct[id] = (direct[id] ?: SubtreeStats()) + if (c.long(1) == 1L) SubtreeStats(vaulted = own) else SubtreeStats(physical = own)
        }
        val folders = state.entries
        val base = SortContext.of(state)
        val stats = base.subtreeStats.toMutableMap()
        // Walk only folder ancestry. No file identity is retained in aggregate construction.
        for ((folder, value) in direct) {
            var id = folder; val seen = HashSet<String>()
            while (seen.add(id)) {
                if (id !in stats) break
                stats[id] = stats.getValue(id) + value
                if (id.isEmpty()) break
                id = folders[id]?.parentId ?: break
            }
        }
        return base.copy(subtreeStats = stats)
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
        val created = HashMap<String, Long>(); val modified = HashMap<String, Long>()
        val metadataByKey = HashMap<ContentKey, ContentMetadata>()
        fun collect(f: FileStat, original: Long?) {
            (original ?: treeEpochMillis(f.createdTime))?.let { created[f.id] = it }
            treeEpochMillis(f.modifiedTime)?.let { modified[f.id] = it }
        }
        val requested = files ?: buildList {
            rows("SELECT payload FROM tree_nodes WHERE generation=0 AND " + if (folderId == null) "folder=1" else "parent=?", if (folderId == null) emptyList() else listOf(folderId)) { add(decode(it.string(0)!!)) }
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
        val cursor = db.query("SELECT payload,usage_bytes,usage_count FROM tree_subtree WHERE folder=?", listOf(root))
        return try {
            if (!cursor.next()) return null
            val stats = json.decodeFromString(SubtreeStats.serializer(), cursor.string(0)!!)
            if (!stats.structuralValid || !stats.physical.listingComplete) return null
            FolderUsage(cursor.long(2)!!.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), cursor.long(1)!!, FolderUsage.Progress.COMPLETE)
        } finally { cursor.close() }
    }
}
