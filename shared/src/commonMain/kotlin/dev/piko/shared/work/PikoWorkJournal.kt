package dev.piko.shared.work

import dev.piko.shared.data.PikoTreeDatabase
import dev.piko.shared.data.PikoTreeDatabaseFactory
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.util.PikoLock
import dev.piko.shared.util.withPikoLock
import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "DurableWork"
private const val SCHEMA_VERSION = "1"
private const val KEY_SCHEMA_VERSION = "schema_version"
private const val KEY_ACTOR_ID = "actor_id"
private const val KEY_ACTOR_SEQ = "actor_seq"
private const val TERMINAL_RETENTION_MS = 7L * 24L * 3600L * 1000L

/**
 * Per-account non-evictable SQLite lifecycle holder shared by [PikoWorkJournal] and `AccountVaultLedger`.
 * Opens a single [PikoTreeDatabase] lease per account so concurrent components share one WAL connection.
 */
class PikoDurableStore(
    private val factory: PikoTreeDatabaseFactory,
) : AutoCloseable {
    data class AccountReady(
        val account: String,
        val generation: Long,
    )

    private val transitionLock = PikoLock()
    private val stateLock = PikoLock()
    private var activeAccount: String? = null
    private var activeDatabase: PikoTreeDatabase? = null
    private var cachedActorId: String? = null
    private var readyGeneration: Long = 0L
    private val _readyState = MutableStateFlow<AccountReady?>(null)
    val readyState: StateFlow<AccountReady?> = _readyState.asStateFlow()
    private val accountHooks = ArrayList<(String?, PikoTreeDatabase?) -> Unit>()

    val currentAccount: String?
        get() = withPikoLock(stateLock) { activeAccount }

    fun isDatabaseOpen(expectedAccount: String): Boolean = databaseFor(expectedAccount) != null

    fun registerAccountHook(hook: (String?, PikoTreeDatabase?) -> Unit) {
        val snapshot = withPikoLock(stateLock) {
            accountHooks += hook
            activeAccount to activeDatabase
        }
        if (snapshot.first != null || snapshot.second != null) {
            runCatching { hook(snapshot.first, snapshot.second) }
                .logFailure(TAG, "Failed to run initial durable store hook")
        }
    }

    fun enterAccount(account: String?) = withPikoLock(transitionLock) {
        val normalized = account?.takeIf { it.isNotBlank() }
        val (alreadySame, retired) = withPikoLock(stateLock) {
            if (activeAccount == normalized && (normalized == null || activeDatabase != null)) {
                return@withPikoLock true to null
            }
            val old = activeDatabase
            activeAccount = null
            activeDatabase = null
            cachedActorId = null
            false to old
        }
        if (alreadySame) return@withPikoLock
        _readyState.value = null
        runCatching { retired?.close() }.logFailure(TAG, "Failed to close previous account durable database")

        var openedDb: PikoTreeDatabase? = null
        var actorId: String? = null
        if (normalized != null) {
            val opened = runCatching {
                val db = factory.open(normalized)
                try {
                    actorId = initializeMeta(db)
                    db
                } catch (error: Throwable) {
                    runCatching { db.close() }
                    throw error
                }
            }.logFailure(TAG, "Failed to open durable database; falling back to in-memory execution")
            openedDb = opened.getOrNull()
        }

        val (hooks, nextReady) = withPikoLock(stateLock) {
            activeAccount = normalized
            activeDatabase = openedDb
            cachedActorId = actorId
            val ready = if (normalized != null) {
                readyGeneration += 1L
                AccountReady(normalized, readyGeneration)
            } else {
                null
            }
            accountHooks.toList() to ready
        }
        for (hook in hooks) {
            runCatching { hook(normalized, openedDb) }
                .logFailure(TAG, "Failed to run durable store account hook")
        }
        _readyState.value = nextReady
    }

    fun databaseFor(expectedAccount: String): PikoTreeDatabase? = withPikoLock(stateLock) {
        if (activeAccount == expectedAccount) activeDatabase else null
    }

    fun actorIdFor(expectedAccount: String): String? = withPikoLock(stateLock) {
        if (activeAccount == expectedAccount) cachedActorId else null
    }

    fun nextActorStamp(expectedAccount: String): Pair<String, Long>? {
        val db = databaseFor(expectedAccount) ?: return null
        return runCatching {
            db.transaction {
                val actor = db.scalarString(
                    "SELECT value FROM durable_meta WHERE key = ?",
                    listOf(KEY_ACTOR_ID),
                ) ?: randomHexId().also { generated ->
                    db.execute(
                        "INSERT OR REPLACE INTO durable_meta(key, value) VALUES (?, ?)",
                        listOf(KEY_ACTOR_ID, generated),
                    )
                }
                val currentSeq = db.scalarString(
                    "SELECT value FROM durable_meta WHERE key = ?",
                    listOf(KEY_ACTOR_SEQ),
                )?.toLongOrNull() ?: 0L
                val nextSeq = currentSeq + 1L
                db.execute(
                    "INSERT OR REPLACE INTO durable_meta(key, value) VALUES (?, ?)",
                    listOf(KEY_ACTOR_SEQ, nextSeq.toString()),
                )
                actor to nextSeq
            }
        }.logFailure(TAG, "Failed to advance durable actor sequence").getOrNull()
    }

    override fun close() {
        enterAccount(null)
    }

    private fun initializeMeta(db: PikoTreeDatabase): String = db.transaction {
        db.execute(
            """
            CREATE TABLE IF NOT EXISTS durable_meta (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execute(
            "INSERT OR IGNORE INTO durable_meta(key, value) VALUES (?, ?)",
            listOf(KEY_SCHEMA_VERSION, SCHEMA_VERSION),
        )
        val existingActor = db.scalarString(
            "SELECT value FROM durable_meta WHERE key = ?",
            listOf(KEY_ACTOR_ID),
        )?.takeIf { it.isNotBlank() }
        val actor = existingActor ?: randomHexId().also { generated ->
            db.execute(
                "INSERT OR REPLACE INTO durable_meta(key, value) VALUES (?, ?)",
                listOf(KEY_ACTOR_ID, generated),
            )
        }
        db.execute(
            "INSERT OR IGNORE INTO durable_meta(key, value) VALUES (?, ?)",
            listOf(KEY_ACTOR_SEQ, "0"),
        )
        actor
    }

    companion object {
        fun randomHexId(bytesCount: Int = 8): String {
            val bytes = Random.nextBytes(bytesCount)
            val chars = CharArray(bytesCount * 2)
            val hex = "0123456789abcdef"
            for (i in bytes.indices) {
                val v = bytes[i].toInt() and 0xFF
                chars[i * 2] = hex[v ushr 4]
                chars[i * 2 + 1] = hex[v and 0x0F]
            }
            return chars.concatToString()
        }
    }
}

enum class WorkJobKind(val wireName: String) {
    COPY("COPY"),
    EXTRACT("EXTRACT"),
    INSTANT_SAVE("INSTANT_SAVE"),
    UPLOAD_PREP("UPLOAD_PREP"),
}

enum class WorkJobStatus(val wireName: String) {
    IN_PROGRESS("IN_PROGRESS"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED"),
}

data class StoredWorkJob(
    val workId: String,
    val kind: WorkJobKind,
    val status: WorkJobStatus,
    val payload: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class DurableCopyTask(
    val id: String,
    val finished: Boolean = false,
    val createdFileId: String? = null,
)

@Serializable
data class DurableCopyJobPayload(
    val id: String,
    val account: String,
    val sourceIds: List<String>,
    val sourceParentById: Map<String, String> = emptyMap(),
    val destinationParentId: String,
    val totalCount: Int,
    val phase: String,
    val stopping: Boolean = false,
    val submittedChunks: Int = 0,
    val submittingChunkIndex: Int? = null,
    val copies: List<DurableCopyTask> = emptyList(),
    val confirmedSourceIds: List<String> = emptyList(),
)

@Serializable
data class DurableExtractJobPayload(
    val id: String,
    val account: String,
    val fileId: String,
    val fileName: String,
    val fileParentId: String = "",
    val fileSize: Long = 0L,
    val fileMimeType: String = "",
    val fileGcid: String = "",
    val targetFolderId: String,
    val targetFolderName: String,
    val createSubfolder: Boolean,
    val deleteArchiveAfter: Boolean,
    val selectedInnerPaths: List<String>? = null,
    val hasPassword: Boolean = false,
    val state: String,
    val remoteTaskId: String? = null,
    val progress: Int = 0,
)

@Serializable
data class DurableInstantItem(
    val index: Int,
    val name: String,
    val path: String = "",
    val size: Long,
    val fileHash: String = "",
    val gcid: String = "",
    val mimeType: String = "",
    val fileCategory: String = "",
    val mediaDurationSeconds: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class DurableInstantSaveJobPayload(
    val attemptId: String,
    val account: String,
    val magnet: String,
    val title: String,
    val targetFolderId: String,
    val targetFolderDisplayPath: String,
    val saveMode: String,
    val items: List<DurableInstantItem>,
    val names: Map<Int, String> = emptyMap(),
    val reusableIds: Map<String, String> = emptyMap(),
    val keepStructure: Boolean = true,
    val requestedParentFolderId: String? = null,
    val containerFolderName: String? = null,
    val containerFolderId: String? = null,
    val folders: Map<String, String> = emptyMap(),
    val freshlyCreatedFolderIds: List<String> = emptyList(),
    val confirmed: Map<Int, String> = emptyMap(),
    val pendingRenames: Map<Int, String> = emptyMap(),
)

@Serializable
data class DurableUploadPrepEntry(
    val relativeDirectory: String,
    val displayName: String,
    val sourceUri: String,
)

@Serializable
data class DurableUploadPrepJobPayload(
    val prepId: String,
    val account: String,
    val rootParentId: String,
    val directories: Map<String, String> = emptyMap(),
    val pendingEntries: List<DurableUploadPrepEntry> = emptyList(),
)

/**
 * Durable step-receipt journal for client-orchestrated multi-step jobs (`COPY`, `EXTRACT`,
 * `INSTANT_SAVE`, `UPLOAD_PREP`). Keeps unfinished work in `IN_PROGRESS` across transient network
 * interruptions and process restarts without re-submitting already accepted server mutations.
 */
class PikoWorkJournal(
    val store: PikoDurableStore,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        store.registerAccountHook { _, db ->
            if (db != null) initializeSchema(db)
        }
    }

    private fun initializeSchema(db: PikoTreeDatabase) = db.transaction {
        db.execute(
            """
            CREATE TABLE IF NOT EXISTS work_jobs (
                work_id TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                status TEXT NOT NULL,
                payload TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execute(
            "CREATE INDEX IF NOT EXISTS idx_work_jobs_kind_status ON work_jobs(kind, status, created_at)",
        )
        db.execute(
            """
            CREATE TABLE IF NOT EXISTS work_receipts (
                work_id TEXT NOT NULL,
                item_key TEXT NOT NULL,
                item_value TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (work_id, item_key)
            )
            """.trimIndent(),
        )
        db.execute(
            "CREATE INDEX IF NOT EXISTS idx_work_receipts_work_id ON work_receipts(work_id)",
        )
        val cutoff = Clock.System.now().toEpochMilliseconds() - TERMINAL_RETENTION_MS
        db.execute(
            "DELETE FROM work_jobs WHERE status != ? AND updated_at < ?",
            listOf(WorkJobStatus.IN_PROGRESS.wireName, cutoff),
        )
        db.execute(
            "DELETE FROM work_receipts WHERE work_id NOT IN (SELECT work_id FROM work_jobs)",
        )
    }

    fun isPersistentFor(account: String): Boolean = store.isDatabaseOpen(account)

    fun recordReceipt(
        account: String,
        workId: String,
        itemKey: String,
        itemValue: String,
        nowMs: Long = Clock.System.now().toEpochMilliseconds(),
    ): Boolean {
        val db = store.databaseFor(account) ?: return false
        return runCatching {
            db.execute(
                """
                INSERT OR REPLACE INTO work_receipts(work_id, item_key, item_value, updated_at)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                listOf(workId, itemKey, itemValue, nowMs),
            )
            true
        }.logFailure(TAG, "Failed to record durable item receipt id=$workId").getOrDefault(false)
    }

    fun loadReceipts(
        account: String,
        workId: String,
    ): Map<String, String> {
        val db = store.databaseFor(account) ?: return emptyMap()
        return runCatching {
            val out = LinkedHashMap<String, String>()
            db.query(
                "SELECT item_key, item_value FROM work_receipts WHERE work_id = ?",
                listOf(workId),
            ).use { cursor ->
                while (cursor.next()) {
                    val key = cursor.string(0) ?: continue
                    val value = cursor.string(1) ?: continue
                    out[key] = value
                }
            }
            out
        }.logFailure(TAG, "Failed to load durable item receipts id=$workId").getOrDefault(emptyMap())
    }

    fun upsertJob(
        account: String,
        workId: String,
        kind: WorkJobKind,
        status: WorkJobStatus,
        payload: String,
        nowMs: Long = Clock.System.now().toEpochMilliseconds(),
    ): Boolean {
        val db = store.databaseFor(account) ?: return false
        return runCatching {
            db.transaction {
                val existingCreatedAt = db.query(
                    "SELECT created_at FROM work_jobs WHERE work_id = ?",
                    listOf(workId),
                ).use { cursor ->
                    if (cursor.next()) cursor.long(0) else null
                } ?: nowMs
                db.execute(
                    """
                    INSERT OR REPLACE INTO work_jobs(work_id, kind, status, payload, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    listOf(workId, kind.wireName, status.wireName, payload, existingCreatedAt, nowMs),
                )
            }
            true
        }.logFailure(TAG, "Failed to upsert durable work job kind=${kind.wireName} id=$workId").getOrDefault(false)
    }

    fun deleteJob(account: String, workId: String): Boolean {
        val db = store.databaseFor(account) ?: return false
        return runCatching {
            db.transaction {
                db.execute("DELETE FROM work_receipts WHERE work_id = ?", listOf(workId))
                db.execute("DELETE FROM work_jobs WHERE work_id = ?", listOf(workId))
            }
            true
        }.logFailure(TAG, "Failed to delete durable work job id=$workId").getOrDefault(false)
    }

    fun listJobs(
        account: String,
        kind: WorkJobKind,
        status: WorkJobStatus = WorkJobStatus.IN_PROGRESS,
    ): List<StoredWorkJob> {
        val db = store.databaseFor(account) ?: return emptyList()
        return runCatching {
            val out = ArrayList<StoredWorkJob>()
            db.query(
                """
                SELECT work_id, kind, status, payload, created_at, updated_at
                FROM work_jobs
                WHERE kind = ? AND status = ?
                ORDER BY created_at ASC
                """.trimIndent(),
                listOf(kind.wireName, status.wireName),
            ).use { cursor ->
                while (cursor.next()) {
                    val workId = cursor.string(0) ?: continue
                    val rawKind = cursor.string(1) ?: continue
                    val rawStatus = cursor.string(2) ?: continue
                    val payload = cursor.string(3) ?: continue
                    val createdAt = cursor.long(4) ?: 0L
                    val updatedAt = cursor.long(5) ?: createdAt
                    val parsedKind = WorkJobKind.entries.firstOrNull { it.wireName == rawKind } ?: continue
                    val parsedStatus = WorkJobStatus.entries.firstOrNull { it.wireName == rawStatus } ?: continue
                    out += StoredWorkJob(workId, parsedKind, parsedStatus, payload, createdAt, updatedAt)
                }
            }
            out
        }.logFailure(TAG, "Failed to list durable work jobs kind=${kind.wireName}").getOrDefault(emptyList())
    }

    fun saveCopyJob(
        payload: DurableCopyJobPayload,
        status: WorkJobStatus = WorkJobStatus.IN_PROGRESS,
        nowMs: Long = Clock.System.now().toEpochMilliseconds(),
    ): Boolean = upsertJob(
        account = payload.account,
        workId = payload.id,
        kind = WorkJobKind.COPY,
        status = status,
        payload = json.encodeToString(DurableCopyJobPayload.serializer(), payload),
        nowMs = nowMs,
    )

    fun loadInProgressCopyJobs(account: String): List<DurableCopyJobPayload> =
        listJobs(account, WorkJobKind.COPY, WorkJobStatus.IN_PROGRESS).mapNotNull { row ->
            runCatching { json.decodeFromString(DurableCopyJobPayload.serializer(), row.payload) }
                .logFailure(TAG, "Skipping corrupt COPY durable payload id=${row.workId}")
                .getOrNull()
        }

    fun saveExtractJob(
        payload: DurableExtractJobPayload,
        status: WorkJobStatus = WorkJobStatus.IN_PROGRESS,
        nowMs: Long = Clock.System.now().toEpochMilliseconds(),
    ): Boolean = upsertJob(
        account = payload.account,
        workId = payload.id,
        kind = WorkJobKind.EXTRACT,
        status = status,
        payload = json.encodeToString(DurableExtractJobPayload.serializer(), payload),
        nowMs = nowMs,
    )

    fun loadInProgressExtractJobs(account: String): List<Pair<StoredWorkJob, DurableExtractJobPayload>> =
        listJobs(account, WorkJobKind.EXTRACT, WorkJobStatus.IN_PROGRESS).mapNotNull { row ->
            runCatching { row to json.decodeFromString(DurableExtractJobPayload.serializer(), row.payload) }
                .logFailure(TAG, "Skipping corrupt EXTRACT durable payload id=${row.workId}")
                .getOrNull()
        }

    fun saveInstantSaveJob(
        payload: DurableInstantSaveJobPayload,
        status: WorkJobStatus = WorkJobStatus.IN_PROGRESS,
        nowMs: Long = Clock.System.now().toEpochMilliseconds(),
    ): Boolean = upsertJob(
        account = payload.account,
        workId = payload.attemptId,
        kind = WorkJobKind.INSTANT_SAVE,
        status = status,
        payload = json.encodeToString(DurableInstantSaveJobPayload.serializer(), payload),
        nowMs = nowMs,
    )

    fun loadInProgressInstantSaveJobs(account: String): List<Pair<StoredWorkJob, DurableInstantSaveJobPayload>> =
        listJobs(account, WorkJobKind.INSTANT_SAVE, WorkJobStatus.IN_PROGRESS).mapNotNull { row ->
            runCatching { row to json.decodeFromString(DurableInstantSaveJobPayload.serializer(), row.payload) }
                .logFailure(TAG, "Skipping corrupt INSTANT_SAVE durable payload id=${row.workId}")
                .getOrNull()
        }

    fun saveUploadPrepJob(
        payload: DurableUploadPrepJobPayload,
        status: WorkJobStatus = WorkJobStatus.IN_PROGRESS,
        nowMs: Long = Clock.System.now().toEpochMilliseconds(),
    ): Boolean = upsertJob(
        account = payload.account,
        workId = payload.prepId,
        kind = WorkJobKind.UPLOAD_PREP,
        status = status,
        payload = json.encodeToString(DurableUploadPrepJobPayload.serializer(), payload),
        nowMs = nowMs,
    )

    fun loadInProgressUploadPrepJobs(account: String): List<DurableUploadPrepJobPayload> =
        listJobs(account, WorkJobKind.UPLOAD_PREP, WorkJobStatus.IN_PROGRESS).mapNotNull { row ->
            runCatching { json.decodeFromString(DurableUploadPrepJobPayload.serializer(), row.payload) }
                .logFailure(TAG, "Skipping corrupt UPLOAD_PREP durable payload id=${row.workId}")
                .getOrNull()
        }
}
