package dev.piko.shared.sync

import dev.piko.shared.data.AccountVaultLedger
import dev.piko.shared.data.DriveChange
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.VaultBatchSyncState
import dev.piko.shared.data.VaultCheckpointSnapshot
import dev.piko.shared.data.VaultOpBatch
import dev.piko.shared.data.createOrClaimFolder
import dev.piko.shared.data.isPikPakDuplicateNameError
import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import io.github.nihildigit.pikpak.ApiRequestPriority
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.TaskPhase
import io.github.nihildigit.pikpak.batchDelete
import io.github.nihildigit.pikpak.listFiles
import io.github.nihildigit.pikpak.withApiPriority
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class VaultOpsSyncOutcome {
    COMPLETE,
    PENDING_RETRY,
    SPACE_FULL,
    SKIPPED,
}

/**
 * Multi-head causal replication of [AccountVaultLedger] batches under `.piko/vault-ops/`.
 *
 * Preserves concurrent device operation logs (`vault-op-<batchId>.json`), stages child batches
 * until all causal parents arrive, acknowledges matching already-uploaded batches on restart,
 * and compacts historical batches behind verified `vault-snapshot-*.json` checkpoints.
 */
class VaultOpsSync(
    private val clients: PikoClientProvider,
    private val driveRepo: PikoDriveRepository,
    private val scope: CoroutineScope,
    private val enabled: Flow<Boolean>,
    private val pushDelayMs: Long = DEFAULT_PUSH_DELAY_MS,
    private val compactionThreshold: Int = DEFAULT_COMPACTION_THRESHOLD,
) {
    private val syncLock = Mutex()
    private val vaultOpsFolderByAccount = HashMap<String, String>()
    private val folderMapLock = dev.piko.shared.util.PikoLock()

    @OptIn(FlowPreview::class)
    fun start() {
        val ledger = driveRepo.vaultLedger ?: return
        val readyFlow = driveRepo.durableStore?.readyState
        scope.launch {
            val activeSyncClient = if (readyFlow != null) {
                combine(clients.currentClient, enabled, readyFlow) { client, on, ready ->
                    client?.takeIf { on && ready?.account == it.account }
                }
            } else {
                combine(clients.currentClient, enabled) { client, on -> client?.takeIf { on } }
            }
            activeSyncClient
                .distinctUntilChanged { a, b -> a === b }
                .collectLatest { client ->
                    if (client == null) return@collectLatest
                    val account = client.account
                    syncWithRetry(client)
                    val outboxSignal = ledger.projection
                        .filter { it.account == account && (it.unsyncedCount > 0 || it.unmaterializedCount > 0) && it.syncState != VaultBatchSyncState.SPACE_FULL }
                        .map { it.unsyncedCount to it.unmaterializedCount }
                        .distinctUntilChanged()
                        .map { account }
                    merge(
                        ledger.localBatchSignal.filter { it == account },
                        outboxSignal,
                    )
                        .debounce(pushDelayMs)
                        .collect {
                            if (enabled.first() && clients.currentClient.value === client) {
                                syncWithRetry(client)
                            }
                        }
                }
        }
    }

    private suspend fun syncWithRetry(client: PikPakClient) {
        var attempt = 0
        while (attempt < MAX_AUTO_RETRIES && clients.currentClient.value === client && enabled.first()) {
            val outcome = syncClient(client).getOrDefault(VaultOpsSyncOutcome.PENDING_RETRY)
            if (outcome != VaultOpsSyncOutcome.PENDING_RETRY) return
            delay(pushDelayMs shl attempt.coerceAtMost(3))
            attempt++
        }
    }

    suspend fun syncIfEnabled(): Boolean {
        if (!enabled.first()) return false
        return syncNow()
    }

    suspend fun syncNow(): Boolean {
        val client = clients.currentClient.value ?: return false
        val outcome = syncClient(client).getOrNull() ?: return false
        return outcome == VaultOpsSyncOutcome.COMPLETE
    }

    suspend fun syncAccount(expectedAccount: String): Result<VaultOpsSyncOutcome> {
        val client = clients.currentClient.value?.takeIf { it.account == expectedAccount }
            ?: return Result.failure(SyncAccountSwitchedException())
        return syncClient(client)
    }

    private suspend fun syncClient(boundClient: PikPakClient): Result<VaultOpsSyncOutcome> = syncLock.withLock {
        val ledger = driveRepo.vaultLedger ?: return@withLock Result.success(VaultOpsSyncOutcome.COMPLETE)
        if (clients.currentClient.value !== boundClient) {
            return@withLock Result.failure(SyncAccountSwitchedException())
        }
        runSuspendCatching {
            withApiPriority(ApiRequestPriority.BACKGROUND) {
                runSyncCycle(boundClient, ledger)
            }
        }.onFailure { err ->
            if (err !is SyncAccountSwitchedException && err !is CancellationException) {
                PikoLog.w(TAG, "Vault-ops sync cycle failed: ${err::class.simpleName}", err)
            }
        }
    }

    private suspend fun runSyncCycle(boundClient: PikPakClient, ledger: AccountVaultLedger): VaultOpsSyncOutcome {
        val account = boundClient.account
        requireBoundClient(boundClient)
        val unsyncedBefore = ledger.listUnsyncedBatches(account)
        val folderId = try {
            resolveVaultOpsFolder(boundClient, createIfNeeded = unsyncedBefore.isNotEmpty())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (err: Throwable) {
            if (isSpaceNotEnough(err)) {
                PikoLog.w(TAG, "Cloud storage full while creating .piko/vault-ops folder; keeping local reference tree intact")
                unsyncedBefore.forEach { stored ->
                    ledger.markBatchSyncState(account, stored.batch.batchId, VaultBatchSyncState.SPACE_FULL)
                }
                return VaultOpsSyncOutcome.SPACE_FULL
            }
            throw err
        }
        requireBoundClient(boundClient)

        var hadPullGap = false
        val remoteByBatchId = HashMap<String, FileStat>()
        var remoteListing: List<FileStat> = emptyList()

        if (folderId != null) {
            remoteListing = runSuspendCatching { boundClient.listFiles(folderId) }.getOrElse { err ->
                invalidateCachedFolder(account)
                throw err
            }
            requireBoundClient(boundClient)

            // 1. Bootstrap from latest verified COMPLETE checkpoint snapshot if present
            val snapshotFiles = remoteListing.filter { stat ->
                !stat.isFolder && !stat.trashed && stat.phase == TaskPhase.COMPLETE &&
                    stat.name.startsWith(SNAPSHOT_PREFIX) && stat.name.endsWith(FILE_SUFFIX)
            }.sortedByDescending { it.name }
            for (snapFile in snapshotFiles) {
                requireBoundClient(boundClient)
                val rawBytes = readRemoteBytes(boundClient, snapFile.id)
                    .logFailure(TAG, "Failed to download vault snapshot ${snapFile.name}")
                    .getOrNull()
                if (rawBytes == null) {
                    hadPullGap = true
                    continue
                }
                val decodedSnap = AccountVaultLedger.decodeSnapshot(rawBytes.decodeToString()) ?: continue
                val appliedSnap = ledger.applyCheckpointSnapshot(account, decodedSnap)
                if (appliedSnap.isSuccess) {
                    val touched = appliedSnap.getOrThrow()
                    if (touched.isNotEmpty() && clients.currentClient.value === boundClient) {
                        driveRepo.applyChange(DriveChange.ContentsChanged(touched))
                    }
                    break
                } else {
                    hadPullGap = true
                }
            }

            // 2. Index all COMPLETE remote op-batches (used both for pull and outbox dedup acknowledgement)
            for (stat in remoteListing) {
                if (!stat.isFolder && !stat.trashed && stat.phase == TaskPhase.COMPLETE &&
                    stat.name.startsWith(FILE_PREFIX) && stat.name.endsWith(FILE_SUFFIX)
                ) {
                    remoteByBatchId[batchIdFromFileName(stat.name)] = stat
                }
            }

            val knownIds = ledger.knownBatchIds(account)
            val remoteCandidates = remoteByBatchId.entries
                .filter { (bId, _) -> bId !in knownIds }
                .map { it.value }
            if (remoteCandidates.isNotEmpty()) {
                val downloaded = ArrayList<Pair<String, VaultOpBatch>>(remoteCandidates.size)
                for (remoteFile in remoteCandidates) {
                    requireBoundClient(boundClient)
                    val bytes = readRemoteBytes(boundClient, remoteFile.id)
                        .logFailure(TAG, "Failed to download remote vault-op ${remoteFile.name}")
                        .getOrNull()
                    if (bytes == null) {
                        hadPullGap = true
                        continue
                    }
                    val decoded = AccountVaultLedger.decodeBatch(bytes.decodeToString())
                    if (decoded == null) {
                        PikoLog.w(TAG, "Ignoring malformed vault-op file ${remoteFile.name}")
                        continue
                    }
                    downloaded += remoteFile.id to decoded
                }
                if (downloaded.isNotEmpty()) {
                    requireBoundClient(boundClient)
                    val touched = ledger.applyRemoteBatches(account, downloaded).getOrThrow()
                    if (touched.isNotEmpty() && clients.currentClient.value === boundClient) {
                        driveRepo.applyChange(DriveChange.ContentsChanged(touched))
                    }
                }
            } else if (ledger.hasUnmaterializedBatches(account)) {
                // Attempt promotion if checkpoint or previous state already satisfied parents
                val touched = ledger.applyRemoteBatches(account, emptyList()).getOrThrow()
                if (touched.isNotEmpty() && clients.currentClient.value === boundClient) {
                    driveRepo.applyChange(DriveChange.ContentsChanged(touched))
                }
            }
        }

        if (ledger.hasUnmaterializedBatches(account)) {
            hadPullGap = true
        }

        val pendingOutbox = ledger.listUnsyncedBatches(account)
        if (pendingOutbox.isNotEmpty()) {
            val targetFolderId = folderId ?: try {
                resolveVaultOpsFolder(boundClient, createIfNeeded = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (err: Throwable) {
                if (isSpaceNotEnough(err)) {
                    PikoLog.w(TAG, "Cloud storage full while creating .piko/vault-ops folder; keeping local reference tree intact")
                    pendingOutbox.forEach { stored ->
                        ledger.markBatchSyncState(account, stored.batch.batchId, VaultBatchSyncState.SPACE_FULL)
                    }
                    return VaultOpsSyncOutcome.SPACE_FULL
                }
                throw err
            } ?: return VaultOpsSyncOutcome.PENDING_RETRY

            for (stored in pendingOutbox) {
                requireBoundClient(boundClient)
                val existingRemote = remoteByBatchId[stored.batch.batchId]
                if (existingRemote != null && verifyMatchingRemoteBatch(boundClient, existingRemote.id, stored.batch)) {
                    ledger.markBatchSynced(account, stored.batch.batchId, existingRemote.id)
                    continue
                }
                val fileName = "$FILE_PREFIX${stored.batch.batchId}$FILE_SUFFIX"
                val payloadBytes = AccountVaultLedger.encodeBatch(stored.batch).encodeToByteArray()
                val uploaded = uploadRemoteBytes(boundClient, targetFolderId, fileName, payloadBytes)
                val err = uploaded.exceptionOrNull()
                if (err != null) {
                    if (err is CancellationException) throw err
                    if (err.isPikPakDuplicateNameError()) {
                        val refreshed = runSuspendCatching { boundClient.listFiles(targetFolderId) }.getOrNull().orEmpty()
                        val matched = refreshed.firstOrNull { !it.isFolder && !it.trashed && it.name == fileName && it.phase == TaskPhase.COMPLETE }
                        if (matched != null && verifyMatchingRemoteBatch(boundClient, matched.id, stored.batch)) {
                            ledger.markBatchSynced(account, stored.batch.batchId, matched.id)
                            continue
                        }
                    }
                    if (isSpaceNotEnough(err)) {
                        PikoLog.w(TAG, "Cloud storage full while syncing vault-op ${stored.batch.batchId}; keeping local reference tree intact")
                        ledger.markBatchSyncState(account, stored.batch.batchId, VaultBatchSyncState.SPACE_FULL)
                        return VaultOpsSyncOutcome.SPACE_FULL
                    }
                    throw err
                }
                ledger.markBatchSynced(account, stored.batch.batchId, uploaded.getOrThrow())
            }
        }

        // 3. Optional history compaction behind verified COMPLETE snapshot
        val activeFolderId = folderId ?: dev.piko.shared.util.withPikoLock(folderMapLock) { vaultOpsFolderByAccount[account] }
        if (!hadPullGap && activeFolderId != null) {
            maybeCompactHistory(boundClient, activeFolderId, ledger)
        }

        return if (hadPullGap) VaultOpsSyncOutcome.PENDING_RETRY else VaultOpsSyncOutcome.COMPLETE
    }

    private suspend fun verifyMatchingRemoteBatch(
        boundClient: PikPakClient,
        remoteFileId: String,
        expected: VaultOpBatch,
    ): Boolean {
        val bytes = readRemoteBytes(boundClient, remoteFileId).getOrNull() ?: return false
        val decoded = AccountVaultLedger.decodeBatch(bytes.decodeToString()) ?: return false
        val recomputed = AccountVaultLedger.computeBatchDigest(
            decoded.actorId,
            decoded.actorSeq,
            decoded.logicalClock,
            decoded.parents,
            decoded.ops,
        )
        return decoded.batchId == expected.batchId &&
            decoded.digest.equals(expected.digest, ignoreCase = true) &&
            recomputed.equals(expected.digest, ignoreCase = true)
    }

    private suspend fun maybeCompactHistory(
        boundClient: PikPakClient,
        folderId: String,
        ledger: AccountVaultLedger,
    ) {
        val account = boundClient.account
        val candidateSnapshot = ledger.exportCheckpointSnapshot(account, minSyncedBatches = compactionThreshold) ?: return
        requireBoundClient(boundClient)
        val paddedClock = candidateSnapshot.logicalClock.toString().padStart(16, '0')
        val snapName = "$SNAPSHOT_PREFIX$paddedClock-${candidateSnapshot.actorId}-${candidateSnapshot.digest.take(12)}$FILE_SUFFIX"
        val snapBytes = AccountVaultLedger.encodeSnapshot(candidateSnapshot).encodeToByteArray()
        val uploadedId = uploadRemoteBytes(boundClient, folderId, snapName, snapBytes).getOrNull() ?: return
        requireBoundClient(boundClient)
        val freshListing = runSuspendCatching { boundClient.listFiles(folderId) }.getOrNull() ?: return
        val confirmedSnap = freshListing.firstOrNull {
            it.id == uploadedId && !it.trashed && it.phase == TaskPhase.COMPLETE
        } ?: return
        if (confirmedSnap.name != snapName) return

        ledger.retireCoveredSyncedBatches(account, candidateSnapshot)
        val leafSet = candidateSnapshot.leafBatchIds.toHashSet()
        val coveredSet = candidateSnapshot.coveredBatchIds.toHashSet()
        val obsoleteFileIds = freshListing.mapNotNull { stat ->
            if (stat.isFolder || stat.trashed || stat.phase != TaskPhase.COMPLETE) return@mapNotNull null
            when {
                stat.name.startsWith(FILE_PREFIX) && stat.name.endsWith(FILE_SUFFIX) -> {
                    val bId = batchIdFromFileName(stat.name)
                    stat.id.takeIf { bId in coveredSet && bId !in leafSet }
                }
                stat.name.startsWith(SNAPSHOT_PREFIX) && stat.name.endsWith(FILE_SUFFIX) && stat.id != confirmedSnap.id -> {
                    stat.id
                }
                else -> null
            }
        }
        if (obsoleteFileIds.isNotEmpty() && clients.currentClient.value === boundClient) {
            runSuspendCatching { boundClient.batchDelete(obsoleteFileIds) }
                .logFailure(TAG, "Failed to delete compacted vault-op files (count=${obsoleteFileIds.size})")
        }
    }

    private suspend fun readRemoteBytes(boundClient: PikPakClient, fileId: String): Result<ByteArray> {
        requireBoundClient(boundClient)
        return driveRepo.readBytes(fileId).also { requireBoundClient(boundClient) }
    }

    private suspend fun uploadRemoteBytes(
        boundClient: PikPakClient,
        parentId: String,
        name: String,
        bytes: ByteArray,
    ): Result<String> {
        requireBoundClient(boundClient)
        return driveRepo.uploadBytes(parentId, name, bytes).also { requireBoundClient(boundClient) }
    }

    private suspend fun resolveVaultOpsFolder(boundClient: PikPakClient, createIfNeeded: Boolean): String? {
        val account = boundClient.account
        dev.piko.shared.util.withPikoLock(folderMapLock) { vaultOpsFolderByAccount[account] }?.let { return it }

        val pikoFolderId = resolvePikoRootFolder(boundClient, createIfNeeded) ?: return null
        requireBoundClient(boundClient)
        val pikoChildren = boundClient.listFiles(pikoFolderId)
        requireBoundClient(boundClient)
        val existing = pikoChildren.firstOrNull { it.isFolder && !it.trashed && it.name == SUBFOLDER_NAME }?.id
        if (existing != null) {
            dev.piko.shared.util.withPikoLock(folderMapLock) { vaultOpsFolderByAccount[account] = existing }
            return existing
        }
        if (!createIfNeeded) return null
        val (createdId, _) = boundClient.createOrClaimFolder(pikoFolderId, SUBFOLDER_NAME)
        driveRepo.syncFolderCache.invalidateListing(account)
        dev.piko.shared.util.withPikoLock(folderMapLock) { vaultOpsFolderByAccount[account] = createdId }
        return createdId
    }

    private suspend fun resolvePikoRootFolder(boundClient: PikPakClient, createIfNeeded: Boolean): String? =
        driveRepo.syncFolderCache.resolveLock.withLock {
            val account = boundClient.account
            requireBoundClient(boundClient)
            driveRepo.syncFolderCache.freshListing(account, 8.seconds)?.let { return@withLock it.first }
            driveRepo.syncFolderCache.folderId(account)?.let { cachedId ->
                val listed = runSuspendCatching { boundClient.listFiles(cachedId) }
                if (listed.isSuccess) {
                    requireBoundClient(boundClient)
                    driveRepo.syncFolderCache.rememberListing(account, cachedId, listed.getOrThrow())
                    return@withLock cachedId
                }
                driveRepo.syncFolderCache.invalidateAccount(account)
            }
            val rootEntries = boundClient.listFiles("")
            requireBoundClient(boundClient)
            val existingPiko = rootEntries.firstOrNull { it.isFolder && !it.trashed && it.name == PikoSettingsSync.FOLDER_NAME }?.id
            if (existingPiko != null) {
                val entries = boundClient.listFiles(existingPiko)
                requireBoundClient(boundClient)
                driveRepo.syncFolderCache.rememberListing(account, existingPiko, entries)
                return@withLock existingPiko
            }
            if (!createIfNeeded) return@withLock null
            val (createdPiko, _) = boundClient.createOrClaimFolder("", PikoSettingsSync.FOLDER_NAME)
            requireBoundClient(boundClient)
            driveRepo.syncFolderCache.rememberListing(account, createdPiko, emptyList())
            createdPiko
        }

    private fun invalidateCachedFolder(account: String) {
        dev.piko.shared.util.withPikoLock(folderMapLock) {
            vaultOpsFolderByAccount.remove(account)
        }
    }

    private fun requireBoundClient(boundClient: PikPakClient) {
        if (clients.currentClient.value !== boundClient) {
            throw SyncAccountSwitchedException()
        }
    }

    private fun batchIdFromFileName(name: String): String =
        name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX)

    private fun isSpaceNotEnough(error: Throwable): Boolean {
        val ex = error as? PikPakException ?: return false
        return ex.errorCode == 8 ||
            ex.errorMessage == "file_space_not_enough" ||
            ex.rawBody?.contains("file_space_not_enough") == true
    }

    companion object {
        const val SUBFOLDER_NAME = "vault-ops"
        const val FILE_PREFIX = "vault-op-"
        const val SNAPSHOT_PREFIX = "vault-snapshot-"
        const val FILE_SUFFIX = ".json"
        private const val DEFAULT_PUSH_DELAY_MS = 1_500L
        private const val DEFAULT_COMPACTION_THRESHOLD = 64
        private const val MAX_AUTO_RETRIES = 3
        private const val TAG = "VaultOpsSync"
    }
}
