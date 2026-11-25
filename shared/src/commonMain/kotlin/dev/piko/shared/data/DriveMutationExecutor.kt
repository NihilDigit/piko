@file:JvmMultifileClass
@file:JvmName("PikoDriveRepositoryKt")

package dev.piko.shared.data

import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.logFolder
import dev.piko.shared.util.withPikoLock
import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileReactivatingException
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.FileTag
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.PikPakHash
import io.github.nihildigit.pikpak.QuotaResponse
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.TransferQuota
import io.github.nihildigit.pikpak.UploadStart
import io.github.nihildigit.pikpak.UrlExpiredException
import io.github.nihildigit.pikpak.batchCopy
import io.github.nihildigit.pikpak.batchDelete
import io.github.nihildigit.pikpak.batchMove
import io.github.nihildigit.pikpak.batchTrash
import io.github.nihildigit.pikpak.batchUntrash
import io.github.nihildigit.pikpak.cancelUpload
import io.github.nihildigit.pikpak.continueUpload
import io.github.nihildigit.pikpak.createFolder
import io.github.nihildigit.pikpak.getFile
import io.github.nihildigit.pikpak.getQuota
import io.github.nihildigit.pikpak.getTransferQuota
import io.github.nihildigit.pikpak.imageThumbnailUrlOf
import io.github.nihildigit.pikpak.instantCreate
import io.github.nihildigit.pikpak.rename
import io.github.nihildigit.pikpak.sampleCid
import io.github.nihildigit.pikpak.starFiles
import io.github.nihildigit.pikpak.startUpload
import io.github.nihildigit.pikpak.streamRangeFromUrl
import io.github.nihildigit.pikpak.unstarFiles
import io.ktor.utils.io.toByteArray
import kotlin.jvm.JvmMultifileClass
import kotlin.jvm.JvmName
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 新建、改名、移动、复制、星标、回收站、彻底删除、小文件读写与秒传等写操作执行器，
 * 统一承载乐观写投影、配额快照更新与 [DriveChange] 变更广播收口。
 */
internal class DriveMutationExecutor(
    private val ctx: DriveRepoContext,
) {
    private val optimisticWrites = OptimisticWrites()
    private val _optimisticRevision = MutableStateFlow(0L)
    val optimisticRevision: StateFlow<Long> = _optimisticRevision.asStateFlow()

    private val _quotaFlow = MutableStateFlow<QuotaResponse?>(null)
    val quotaFlow: StateFlow<QuotaResponse?> = _quotaFlow.asStateFlow()

    private val _quotaUpdates = MutableSharedFlow<Pair<String, QuotaResponse>>(extraBufferCapacity = 4)
    val quotaUpdates: SharedFlow<Pair<String, QuotaResponse>> = _quotaUpdates.asSharedFlow()

    private val _transferQuotaFlow = MutableStateFlow<TransferQuota?>(null)
    val transferQuotaFlow: StateFlow<TransferQuota?> = _transferQuotaFlow.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val isFreeAccountFlow: StateFlow<Boolean?> =
        ctx.clientManager.currentClient.flatMapLatest { active ->
            active?.isFreeAccountFlow ?: _transferQuotaFlow.map { it?.account?.isPremium?.not() }
        }.stateIn(ctx.backgroundScope, SharingStarted.Eagerly, ctx.clientManager.currentClient.value?.isFreeAccount)

    val hasPendingOptimisticWrites: Boolean
        get() = optimisticWrites.hasPending(ctx.currentAccount) || ctx.overlay.hasPendingBoundVaultWrites

    fun hasPendingOptimisticWritesFor(account: String): Boolean = optimisticWrites.hasPending(account)

    fun optimisticCandidates(): List<FileStat> = optimisticWrites.candidates(ctx.currentAccount)

    fun projectOptimisticFiles(files: List<FileStat>, folderId: String? = null, trash: Boolean = false): List<FileStat> =
        optimisticWrites.project(ctx.currentAccount, folderId, files, trash) { id ->
            ctx.index.folderIndex.confirmedNode(id)?.parentId
                ?: ctx.index.driveTreeIndex.file(id)?.parentId
                ?: ctx.index.knownConfirmedFile(id)?.parentId
        }

    private fun observeChangeTargetsForQuota(
        asked: PikPakClient,
        ids: Collection<String>,
        before: Map<String, FileStat> = emptyMap(),
    ) {
        val fileStats = ArrayList<FileStat>(ids.size)
        val driveTreeIndex = ctx.index.driveTreeIndex
        val missingIds = ArrayList<String>()
        for (id in ids) {
            if (LocalLocation.isLocal(id) || VaultEntry.isVaulted(id)) continue
            if (id !in before) missingIds.add(id)
        }
        val fetchedMissing = if (missingIds.isNotEmpty()) ctx.index.knownConfirmedFiles(missingIds) else emptyMap()
        for (id in ids) {
            if (LocalLocation.isLocal(id) || VaultEntry.isVaulted(id)) continue
            val known = before[id] ?: fetchedMissing[id]
            if (known != null) {
                if (known.isFolder) {
                    val folderBytes = driveTreeIndex.folderUsage(id)?.bytes
                    if (folderBytes != null && folderBytes > 0L) {
                        asked.observeFolderSubtree(id, folderBytes, trashed = known.trashed)
                    }
                } else {
                    fileStats += known
                }
            } else {
                val folderBytes = driveTreeIndex.folderUsage(id)?.bytes
                if (folderBytes != null && folderBytes > 0L) {
                    asked.observeFolderSubtree(id, folderBytes, trashed = false)
                }
            }
        }
        if (fileStats.isNotEmpty()) {
            asked.observeFiles(fileStats)
        }
    }

    fun beginOptimisticWrite(account: String, change: DriveChange): OptimisticWrites.Write =
        beginOptimisticWrites(account, listOf(change)).single()

    fun beginOptimisticWrites(account: String, changes: List<DriveChange>): List<OptimisticWrites.Write> {
        if (changes.isEmpty()) return emptyList()
        val allIds = LinkedHashSet<String>()
        val perChangeIds = ArrayList<Set<String>>(changes.size)
        for (i in 0 until changes.size) {
            val ids = changes[i].fileIds()
            perChangeIds.add(ids)
            allIds.addAll(ids)
        }
        val allBefore = ctx.index.knownFiles(allIds)
        val asked = ctx.clientManager.currentClient.value?.takeIf { it.account == account }
        val entries = ArrayList<Pair<DriveChange, Map<String, FileStat>>>(changes.size)
        for (i in 0 until changes.size) {
            val ids = perChangeIds[i]
            val before = if (changes.size == 1) {
                allBefore
            } else {
                val map = HashMap<String, FileStat>(ids.size * 4 / 3 + 1)
                for (id in ids) {
                    allBefore[id]?.let { map[id] = it }
                }
                map
            }
            entries.add(changes[i] to before)
        }
        val writes = optimisticWrites.beginBatch(account, entries, asked)
        try {
            if (ctx.currentAccount == account) {
                if (asked != null) {
                    observeChangeTargetsForQuota(asked, allIds, allBefore)
                    val trashedIds = ArrayList<String>()
                    val deletedIds = ArrayList<String>()
                    val restoredIds = ArrayList<String>()
                    for (i in 0 until changes.size) {
                        when (val change = changes[i]) {
                            is DriveChange.Trashed -> trashedIds.addAll(change.ids)
                            is DriveChange.Deleted -> deletedIds.addAll(change.ids)
                            is DriveChange.Restored -> restoredIds.addAll(change.ids)
                            else -> Unit
                        }
                    }
                    if (trashedIds.isNotEmpty()) asked.noteFilesTrashed(trashedIds)
                    if (deletedIds.isNotEmpty()) asked.noteFilesDeleted(deletedIds)
                    if (restoredIds.isNotEmpty()) asked.noteFilesUntrashed(restoredIds)
                }
                val mergedFolders = mergeChangeFolders(changes)
                withPikoLock(ctx.index.treeStateLock) { ctx.index.markChanged(mergedFolders) }
                ctx.index.folderIndex.project { optimisticWrites.projectFolders(ctx.currentAccount, it) }
                ctx.index.normalizeLocations()
                _optimisticRevision.update { it + 1 }
            }
        } catch (error: Throwable) {
            runCatching { optimisticWrites.finishBatch(writes) }
            throw error
        }
        return writes
    }

    fun finishOptimisticWrite(write: OptimisticWrites.Write, succeeded: Boolean) {
        finishOptimisticWrites(listOf(write), booleanArrayOf(succeeded))
    }

    fun finishOptimisticWrites(writes: List<OptimisticWrites.Write>, succeeded: BooleanArray) {
        if (writes.isEmpty()) return
        val account = writes[0].account
        val boundClient = writes[0].client ?: ctx.clientManager.currentClient.value?.takeIf { it.account == account }
        val revertSnapshots = ArrayList<FileStat>()
        val succeededChanges = ArrayList<DriveChange>()
        for (i in 0 until writes.size) {
            val write = writes[i]
            val ok = succeeded[i]
            if (!ok) {
                val ids = write.change.fileIds()
                PikoLog.d(
                    DRIVE_REPO_TAG,
                    "Reverting failed optimistic write (kind=${write.change::class.simpleName}, items=${ids.size}, acct=${logAccountSlot(write.account)})",
                )
                if (boundClient != null) {
                    for (id in ids) {
                        revertSnapshots.add(
                            write.before[id] ?: FileStat(
                                id = id,
                                trashed = write.change is DriveChange.Restored,
                            ),
                        )
                    }
                }
            } else if (ctx.currentAccount == write.account) {
                succeededChanges.add(write.change)
            }
        }
        try {
            if (boundClient != null && revertSnapshots.isNotEmpty()) {
                boundClient.revertFiles(revertSnapshots)
            }
            for (change in coalesceChanges(succeededChanges)) {
                applyChange(change, expectedAccount = account)
            }
        } finally {
            optimisticWrites.finishBatch(writes)
            if (!optimisticWrites.hasPending(account)) {
                boundClient?.quotaFlow?.value?.let { confirmedQuota ->
                    _quotaUpdates.tryEmit(account to confirmedQuota)
                }
            }
            if (ctx.currentAccount == account) {
                val mergedFolders = mergeChangeFolders(writes.map { it.change })
                withPikoLock(ctx.index.treeStateLock) { ctx.index.markChanged(mergedFolders) }
                ctx.index.folderIndex.project { optimisticWrites.projectFolders(ctx.currentAccount, it) }
                ctx.index.normalizeLocations()
                _optimisticRevision.update { it + 1 }
                ctx.folderChanges.tryEmit(DriveChange.ContentsChanged(mergedFolders))
            }
        }
    }

    private fun mergeChangeFolders(changes: List<DriveChange>): Set<String>? {
        val acc = HashSet<String>()
        for (i in 0 until changes.size) {
            val folders = changes[i].folders ?: return null
            acc.addAll(folders)
        }
        return acc
    }

    private fun coalesceChanges(changes: List<DriveChange>): List<DriveChange> {
        if (changes.size <= 1) return changes
        val out = ArrayList<DriveChange>()
        var i = 0
        while (i < changes.size) {
            when (val first = changes[i]) {
                is DriveChange.Trashed -> {
                    val ids = ArrayList(first.ids)
                    var folders: MutableSet<String>? = first.folders?.toMutableSet()
                    while (i + 1 < changes.size && changes[i + 1] is DriveChange.Trashed) {
                        val next = changes[++i] as DriveChange.Trashed
                        ids.addAll(next.ids)
                        folders = if (folders == null || next.folders == null) null else folders.apply { addAll(next.folders) }
                    }
                    out.add(DriveChange.Trashed(ids, folders))
                }
                is DriveChange.Deleted -> {
                    val ids = ArrayList(first.ids)
                    val folders = first.folders.toMutableSet()
                    while (i + 1 < changes.size && changes[i + 1] is DriveChange.Deleted) {
                        val next = changes[++i] as DriveChange.Deleted
                        ids.addAll(next.ids)
                        folders.addAll(next.folders)
                    }
                    out.add(DriveChange.Deleted(ids, folders))
                }
                is DriveChange.Restored -> {
                    val ids = ArrayList(first.ids)
                    while (i + 1 < changes.size && changes[i + 1] is DriveChange.Restored) {
                        ids.addAll((changes[++i] as DriveChange.Restored).ids)
                    }
                    out.add(DriveChange.Restored(ids))
                }
                is DriveChange.Starred -> {
                    val ids = ArrayList(first.ids)
                    var folders: MutableSet<String>? = first.folders?.toMutableSet()
                    while (i + 1 < changes.size) {
                        val next = changes[i + 1] as? DriveChange.Starred ?: break
                        if (next.starred != first.starred) break
                        i++
                        ids.addAll(next.ids)
                        folders = if (folders == null || next.folders == null) null else folders.apply { addAll(next.folders) }
                    }
                    out.add(DriveChange.Starred(ids, first.starred, folders))
                }
                else -> out.add(first)
            }
            i++
        }
        return out
    }

    fun applyWriteChange(account: String, change: DriveChange, notifyChange: Boolean = true) {
        if (ctx.currentAccount != account) return
        if (change is DriveChange.Renamed) ctx.overlay.pinnedFolders.renamed(change.id, change.name)
        applyChange(change, notifyChange)
    }

    fun applyChange(change: DriveChange, notifyChange: Boolean = true, expectedAccount: String? = null) {
        if (expectedAccount != null && ctx.currentAccount != expectedAccount) return
        if (change is DriveChange.ContentsChanged && change.folders?.all(LocalLocation::isLocal) == true) {
            if (notifyChange) ctx.folderChanges.tryEmit(change)
            return
        }
        val indexChanged = withPikoLock(ctx.index.treeStateLock) {
            if (expectedAccount != null && ctx.currentAccount != expectedAccount) return@withPikoLock null
            if (change is DriveChange.Deleted) ctx.library.recordServerDelete(change.ids)
            if (change is DriveChange.Trashed ||
                change is DriveChange.Restored ||
                change is DriveChange.Deleted ||
                (change is DriveChange.ContentsChanged && change.folders == null)
            ) {
                ctx.library.invalidateTrashCache()
            }
            ctx.clientManager.currentClient.value?.let { asked ->
                when (change) {
                    is DriveChange.Trashed -> {
                        observeChangeTargetsForQuota(asked, change.ids)
                        asked.noteFilesTrashed(change.ids)
                    }
                    is DriveChange.Deleted -> {
                        observeChangeTargetsForQuota(asked, change.ids)
                        asked.noteFilesDeleted(change.ids)
                    }
                    is DriveChange.Restored -> {
                        observeChangeTargetsForQuota(asked, change.ids)
                        asked.noteFilesUntrashed(change.ids)
                    }
                    else -> Unit
                }
            }
            val driveTreeIndex = ctx.index.driveTreeIndex
            val folderIndex = ctx.index.folderIndex
            fun batchKnownParents(ids: List<String>): Set<String> {
                if (ids.isEmpty()) return emptySet()
                val parents = ctx.index.knownParentMap(ids)
                val out = HashSet<String>(parents.size * 4 / 3 + 1)
                out.addAll(parents.values)
                if (parents.size < ids.size) {
                    val missing = ids.filter { it !in parents }
                    if (missing.isNotEmpty()) {
                        for (file in driveTreeIndex.filesByIds(missing).values) {
                            out.add(file.parentId)
                        }
                    }
                }
                return out
            }
            val knownParentsBeforeChange: Set<String> = when (change) {
                is DriveChange.Renamed -> setOfNotNull(
                    change.parentId ?: ctx.index.knownParentOf(change.id) ?: driveTreeIndex.file(change.id)?.parentId,
                )
                is DriveChange.Moved -> change.folders.orEmpty() + batchKnownParents(change.ids) + change.to
                is DriveChange.Trashed -> change.folders.orEmpty() + batchKnownParents(change.ids)
                is DriveChange.Deleted -> change.folders.orEmpty() + batchKnownParents(change.ids)
                else -> change.folders.orEmpty()
            }
            val changed = when (change) {
                is DriveChange.Created -> folderIndex.learned(change.id, change.name, change.parentId, change.isFolder, trashed = false)
                is DriveChange.Renamed -> folderIndex.renamed(change.id, change.name, always = change.id in ctx.index.referencedNodeIds())
                is DriveChange.Moved -> folderIndex.moved(change.ids, change.to)
                is DriveChange.Trashed -> folderIndex.removed(change.ids, ctx.index.referencedNodeIds())
                is DriveChange.Deleted -> folderIndex.removed(change.ids, ctx.index.referencedNodeIds())
                is DriveChange.Restored -> folderIndex.restored(change.ids)
                is DriveChange.Copied, is DriveChange.Starred, is DriveChange.ContentsChanged -> false
            }
            val folders = change.folders
            ctx.listing.childContents.invalidate(folders)
            driveTreeIndex.applyChange(change)
            ctx.index.markChanged(
                folders = if (folders == null) null else folders + knownParentsBeforeChange,
                knownTouchedFolders = knownParentsBeforeChange,
                removedIds = when (change) {
                    is DriveChange.Trashed -> change.ids
                    is DriveChange.Deleted -> change.ids
                    else -> emptyList()
                },
                renamed = (change as? DriveChange.Renamed)?.let { it.id to it.name },
                moved = (change as? DriveChange.Moved)?.let { it.ids to it.to },
                invalidatesWholeTree = change is DriveChange.Restored || (change is DriveChange.ContentsChanged && change.folders == null),
            )
            val fencedFolders = if (folders == null) null else folders + knownParentsBeforeChange
            ctx.listing.listingRequests.update { requests -> if (fencedFolders == null) emptyMap() else requests - fencedFolders }
            val changedIds = when (change) {
                is DriveChange.Trashed, is DriveChange.Deleted, is DriveChange.Moved, is DriveChange.Starred -> change.fileIds()
                else -> emptySet()
            }
            ctx.listing.listings.update { cache ->
                cache.mapValues { (id, listing) ->
                    when {
                        change is DriveChange.Trashed || change is DriveChange.Deleted -> DriveListingEngine.Listing(
                            listing.files.filterNot { it.id in changedIds },
                            listing.listedAt,
                            listing.stale,
                        )
                        change is DriveChange.Moved -> DriveListingEngine.Listing(
                            listing.files.filterNot { it.id in changedIds && id != change.to }
                                .map { if (it.id in changedIds) it.copy(parentId = change.to) else it },
                            listing.listedAt,
                            listing.stale,
                        )
                        change is DriveChange.Renamed && notifyChange -> DriveListingEngine.Listing(
                            listing.files.map { file -> if (file.id == change.id) file.copy(name = change.name) else file },
                            listing.listedAt,
                            listing.stale,
                        )
                        change is DriveChange.Starred && notifyChange -> {
                            if (listing.files.none { it.id in changedIds }) {
                                listing
                            } else {
                                DriveListingEngine.Listing(
                                    listing.files.map { file ->
                                        if (file.id !in changedIds) {
                                            file
                                        } else {
                                            val withoutStar = file.tags.filterNot { it.name.equals("STAR", ignoreCase = true) }
                                            val nextTags = if (change.starred) withoutStar + FileTag(id = "STAR", name = "STAR") else withoutStar
                                            file.copy(starred = change.starred, tags = nextTags)
                                        }
                                    },
                                    listing.listedAt,
                                    listing.stale,
                                )
                            }
                        }
                        change is DriveChange.Created && id == change.parentId && (change.isFolder || change.stat != null) -> {
                            val added = change.stat ?: FileStat(
                                kind = FileKind.FOLDER,
                                id = change.id,
                                parentId = change.parentId,
                                name = change.name,
                            )
                            DriveListingEngine.Listing(
                                listing.files.filterNot { it.id == added.id } + added,
                                listing.listedAt,
                                listing.stale,
                            )
                        }
                        folders == null || id in folders -> listing.expired()
                        else -> listing
                    }
                }
            }
            ctx.listing.invalidateFolderEmptiness(folders)
            changed
        }
        if (indexChanged == null) return
        if (indexChanged) ctx.index.normalizeLocations()
        if (notifyChange) ctx.folderChanges.tryEmit(change)
    }

    fun notifyFoldersRefreshed(folders: Set<String>) {
        if (folders.isEmpty()) return
        val state = ctx.index.driveTreeIndex.flow.value
        val needsDirty = folders.filterTo(HashSet()) { folderId ->
            !(state.complete && folderId in state.listedFolders && folderId !in state.dirtyFolders)
        }
        if (needsDirty.isNotEmpty()) {
            applyChange(DriveChange.ContentsChanged(needsDirty))
        }
        val cleanOnly = folders - needsDirty
        if (cleanOnly.isNotEmpty()) {
            withPikoLock(ctx.index.treeStateLock) {
                ctx.listing.listings.update { cache ->
                    cache.mapValues { (id, listing) -> if (id in cleanOnly) listing.expired() else listing }
                }
            }
            ctx.folderChanges.tryEmit(DriveChange.ContentsChanged(cleanOnly))
        }
    }

    suspend fun getQuota(maxAge: Duration = Duration.ZERO): Result<QuotaResponse> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val asked = ctx.requireClient()
            val deferred = ctx.backgroundScope.async {
                asked.getQuota(maxAge).also {
                    if (ctx.clientManager.currentClient.value === asked) {
                        _quotaFlow.value = it
                        _quotaUpdates.tryEmit(asked.account to it)
                        val driveTreeIndex = ctx.index.driveTreeIndex
                        if (driveTreeIndex.isComplete && driveTreeIndex.lastUsageBytes == null) {
                            driveTreeIndex.setLastUsageBytes(it.quota.usageBytes)
                        }
                    }
                }
            }
            deferred.await()
        }
    }

    suspend fun isFreeAccount(): Boolean? =
        isFreeAccountFlow.value ?: getTransferQuota().getOrNull()?.account?.isPremium?.not()

    suspend fun getTransferQuota(maxAge: Duration = Duration.ZERO): Result<TransferQuota> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val asked = ctx.requireClient()
            val deferred = ctx.backgroundScope.async {
                asked.getTransferQuota(maxAge).also { tq ->
                    if (ctx.clientManager.currentClient.value === asked) {
                        _transferQuotaFlow.value = tq
                        ctx.cacheStore?.let { store ->
                            ctx.backgroundScope.launch {
                                runSuspendCatching {
                                    store.write(transferQuotaCacheKey(asked.account), QUOTA_JSON.encodeToString(TransferQuota.serializer(), tq))
                                }.logFailure(DRIVE_REPO_TAG, "Failed to persist transfer quota cache (acct=${logAccountSlot(asked.account)})")
                            }
                        }
                    }
                }
            }
            deferred.await()
        }
    }

    suspend fun originalImageUrl(fileId: String): String? =
        if (LeasedFile.isLeased(fileId)) {
            LeasedFile.resolvedFileOf(fileId)?.gcid?.takeIf { it.isNotBlank() }?.let { imageThumbnailUrlOf(it, large = true) }
        } else {
            ctx.index.getFileDetail(fileId).getOrNull()?.downloadUrl
        }

    suspend fun uploadBytes(parentId: String, name: String, bytes: ByteArray): Result<String> =
        withContext(Dispatchers.Default) {
            runSuspendCatching { uploadBytes(ctx.requireClient(), parentId, name, bytes) }
        }

    internal suspend fun uploadBytes(asked: PikPakClient, parentId: String, name: String, bytes: ByteArray): String {
        requireCloudFileIds(listOf(parentId))
        val size = bytes.size.toLong()
        val gcid = PikPakHash.fromSource(Buffer().apply { write(bytes) }, size)
        fun recordUploadedInTree(fileId: String) {
            withPikoLock(ctx.index.treeStateLock) {
                if (ctx.clientManager.currentClient.value === asked) {
                    ctx.index.driveTreeIndex.putFiles(
                        listOf(FileStat(id = fileId, parentId = parentId, name = name, kind = FileKind.FILE, hash = gcid, size = size.toString())),
                    )
                }
            }
        }
        val session = try {
            when (val start = asked.startUpload(parentId, name, size, gcid)) {
                is UploadStart.Instant -> return start.fileId.also(::recordUploadedInTree)
                is UploadStart.Pending -> start.session
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            PikoLog.w(DRIVE_REPO_TAG, "Small file upload init failed in ${logFolder(parentId)} (size=$size, error=${e::class.simpleName})", e)
            throw e
        }
        var lastError: Throwable? = null
        try {
            for (attempt in 0 until UPLOAD_BYTES_ATTEMPTS) {
                try {
                    asked.continueUpload(
                        session,
                        open = { offset: Long ->
                            val startOffset = offset.toInt().coerceIn(0, bytes.size)
                            Buffer().apply { write(bytes, startOffset, bytes.size) }
                        },
                        onProgress = {},
                        freshSession = attempt == 0,
                    )
                    recordUploadedInTree(session.fileId)
                    return session.fileId
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    lastError = e
                    if (e is PikPakException || attempt == UPLOAD_BYTES_ATTEMPTS - 1) throw e
                    delay(LINK_RETRY_DELAYS.getOrElse(attempt) { 1.seconds })
                }
            }
            throw lastError ?: error(tr("上传失败", "Upload failed", "アップロードに失敗しました", "업로드 실패", "上傳失敗"))
        } catch (e: Throwable) {
            if (e !is CancellationException) {
                PikoLog.w(
                    DRIVE_REPO_TAG,
                    "Small file part upload failed in ${logFolder(parentId)} (id=${session.fileId}, host=${session.endpoint}, size=$size, error=${e::class.simpleName})",
                    e,
                )
            }
            withContext(NonCancellable) { runCatching { asked.cancelUpload(session) } }
            throw e
        }
    }

    suspend fun readBytes(fileId: String): Result<ByteArray> = withContext(Dispatchers.Default) {
        runSuspendCatching { readBytes(ctx.requireClient(), fileId) }
    }

    internal suspend fun readBytes(asked: PikPakClient, fileId: String): ByteArray {
        requireCloudFileIds(listOf(fileId))
        return readSmallFileBytes(asked, fileId)
    }

    suspend fun folderNamed(parentId: String, name: String): Result<String> {
        ctx.listing.localRealChildrenIfClean(parentId)
            ?.firstOrNull { it.isFolder && it.name == name && !it.trashed }
            ?.let { return Result.success(it.id) }
        val existing = ctx.listing.listAllFiles(parentId).getOrElse { return Result.failure(it) }
            .firstOrNull { it.isFolder && it.name == name && !it.trashed }
        return existing?.let { Result.success(it.id) } ?: createFolder(parentId, name)
    }

    suspend fun sampleCid(fileId: String): Result<String> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            requireCloudFileIds(listOf(fileId))
            val asked = ctx.requireClient()
            val observer = ctx.index.metadataSink.capture(asked)
            val detail = asked.getFile(fileId)
            observer?.observe(fileId, detail)
            asked.sampleCid(detail).also { sampled ->
                if (sampled.isNotBlank() && ctx.clientManager.currentClient.value === asked) {
                    ContentKey.of(detail.hash, detail.sizeBytes)?.let { key ->
                        ctx.index.driveTreeIndex.observeContentMetadata(key, ContentMetadata(cid = sampled))
                    }
                }
            }
        }
    }

    suspend fun instantCreate(
        file: ResolvedFile,
        parentId: String,
        source: String? = null,
        notifyChange: Boolean = true,
    ): Result<String> = withContext(Dispatchers.Default) {
        val asked = ctx.requireClient()
        runSuspendCatching { requireCloudFileIds(listOf(parentId)); asked.instantCreate(file, parentId) }.onSuccess { id ->
            if (ctx.clientManager.currentClient.value === asked) {
                source?.let { ctx.overlay.sourceLedger.record(it, listOf(file)) }
                applyChange(DriveChange.Created(parentId, id, file.path.substringAfterLast('/'), isFolder = false), notifyChange)
            }
        }
    }

    suspend fun createFolder(parentId: String, name: String): Result<String> = withContext(Dispatchers.Default) {
        val asked = ctx.requireClient()
        runSuspendCatching { requireCloudFileIds(listOf(parentId)); asked.createFolder(parentId, name) }.onSuccess { id ->
            if (ctx.clientManager.currentClient.value === asked) {
                applyChange(DriveChange.Created(parentId, id, name, isFolder = true))
            }
        }
    }

    suspend fun rename(fileId: String, name: String, parentId: String? = null, notifyChange: Boolean = true): Result<Unit> =
        withContext(Dispatchers.Default) {
            val asked = ctx.requireClient()
            val effectiveParentId = parentId ?: ctx.index.knownParentOf(fileId)
            runSuspendCatching { requireCloudFileIds(listOf(fileId)); asked.rename(fileId, name) }.onSuccess {
                if (ctx.clientManager.currentClient.value === asked) {
                    ctx.overlay.pinnedFolders.renamed(fileId, name)
                    applyChange(DriveChange.Renamed(fileId, name, effectiveParentId), notifyChange)
                }
            }
        }

    suspend fun trash(ids: List<String>): Result<Unit> = withContext(Dispatchers.Default) {
        val started = TimeSource.Monotonic.markNow()
        val asked = ctx.requireClient()
        val from = ctx.index.knownParentsOf(ids)
        runSuspendCatching {
            requireCloudFileIds(ids)
            observeChangeTargetsForQuota(asked, ids)
            asked.batchTrash(ids)
        }.onSuccess {
            if (ctx.clientManager.currentClient.value === asked) applyChange(DriveChange.Trashed(ids, from))
        }.logOperation("Trash", ids, started)
    }

    suspend fun restore(ids: List<String>): Result<Unit> = withContext(Dispatchers.Default) {
        val started = TimeSource.Monotonic.markNow()
        val asked = ctx.requireClient()
        runSuspendCatching {
            requireCloudFileIds(ids)
            observeChangeTargetsForQuota(asked, ids)
            asked.batchUntrash(ids)
        }.onSuccess {
            if (ctx.clientManager.currentClient.value === asked) applyChange(DriveChange.Restored(ids))
        }.logOperation("Restore from trash", ids, started)
    }

    suspend fun delete(ids: List<String>): Result<Unit> = withContext(Dispatchers.Default) {
        val started = TimeSource.Monotonic.markNow()
        val asked = ctx.requireClient()
        val from = ids.mapNotNullTo(HashSet(), ctx.index::knownParentOf)
        runSuspendCatching {
            requireCloudFileIds(ids)
            observeChangeTargetsForQuota(asked, ids)
            asked.batchDelete(ids)
        }.onSuccess {
            if (ctx.clientManager.currentClient.value === asked) applyChange(DriveChange.Deleted(ids, from))
        }.logOperation("Delete permanently", ids, started)
    }

    suspend fun move(ids: List<String>, parentId: String): Result<Unit> = withContext(Dispatchers.Default) {
        val started = TimeSource.Monotonic.markNow()
        val asked = ctx.requireClient()
        val from = ctx.index.knownParentsOf(ids)
        runSuspendCatching { requireCloudFileIds(ids + parentId); asked.batchMove(ids, parentId) }.onSuccess {
            if (ctx.clientManager.currentClient.value === asked) applyChange(DriveChange.Moved(ids, from, parentId))
        }.logOperation("Move to ${logFolder(parentId)}", ids, started)
    }

    suspend fun copy(ids: List<String>, parentId: String): Result<Unit> = withContext(Dispatchers.Default) {
        val started = TimeSource.Monotonic.markNow()
        val asked = ctx.requireClient()
        runSuspendCatching { requireCloudFileIds(ids + parentId); asked.batchCopy(ids, parentId); Unit }.onSuccess {
            if (ctx.clientManager.currentClient.value === asked) applyChange(DriveChange.Copied(ids, parentId))
        }.logOperation("Copy to ${logFolder(parentId)}", ids, started)
    }

    private fun <T> Result<T>.logOperation(action: String, ids: List<String>, started: TimeMark): Result<T> {
        val shown = ids.take(LOGGED_IDS).joinToString(",") { LocalLocation.logIdentity(it) ?: it } +
            if (ids.size > LOGGED_IDS) ",..." else ""
        val elapsed = started.elapsedNow().inWholeMilliseconds
        onSuccess { PikoLog.i(DRIVE_REPO_TAG, "$action succeeded: count=${ids.size} ($shown), elapsedMs=$elapsed") }
        onFailure { error ->
            if (error is CancellationException) return@onFailure
            val reason = (error as? PikPakException)?.let { "${it.errorCode} ${it.errorMessage}" }
                ?: "${error::class.simpleName}: ${error.message}"
            PikoLog.w(DRIVE_REPO_TAG, "$action failed: count=${ids.size} ($shown), elapsedMs=$elapsed, reason=$reason", error)
        }
        return this
    }

    private fun resolveVaultedStat(virtualId: String, knownStats: Map<String, FileStat> = emptyMap()): FileStat? {
        knownStats[virtualId]?.let { return it }
        ctx.index.driveTreeIndex.file(virtualId)?.let { return it }
        val entryId = VaultEntry.entryIdOf(virtualId)
        if (entryId != null) {
            ctx.overlay.vaultStars.flow.value[entryId]?.toFileStat()?.let { return it }
            val folderId = ctx.vaultAccessor().folderOfEntry(entryId).orEmpty()
            val resolved = VaultEntry.resolvedFileOf(virtualId)
            if (resolved != null && !resolved.gcid.isNullOrBlank()) {
                return FileStat(
                    kind = FileKind.FILE,
                    id = virtualId,
                    parentId = folderId,
                    name = resolved.path,
                    size = resolved.size.toString(),
                    hash = resolved.gcid!!,
                )
            }
        }
        return null
    }

    fun setVaultStarred(stats: Collection<FileStat>, starred: Boolean) {
        val affected = ctx.overlay.vaultStars.setStarredFromStats(stats, starred)
        if (affected.isNotEmpty()) {
            val resolvedParents = stats.map { stat ->
                stat.parentId.takeIf { it.isNotEmpty() || stat.id in ctx.listing.listings.value[""]?.files.orEmpty().map(FileStat::id) }
                    ?: VaultEntry.entryIdOf(stat.id)?.let { ctx.overlay.vaultStars.folderOf(it) ?: ctx.vaultAccessor().folderOfEntry(it) }
                    ?: stat.parentId
            }
            val folders: Set<String>? = if (resolvedParents.isEmpty()) null else resolvedParents.toHashSet()
            if (folders != null) {
                for (folderId in folders) {
                    val existing = ctx.index.driveTreeIndex.vaultEntries(folderId)
                    if (existing != null) ctx.overlay.vaultEntriesKnown(folderId, existing)
                }
            }
            applyChange(DriveChange.Starred(affected, starred, folders))
        }
    }

    suspend fun setStarred(
        ids: List<String>,
        starred: Boolean,
        knownStats: Map<String, FileStat> = emptyMap(),
    ): Result<Unit> = withContext(Dispatchers.Default) {
        val (vaultedIds, realIds) = ids.distinct().filterNot(LocalLocation::isLocal).partition(VaultEntry::isVaulted)
        if (vaultedIds.isNotEmpty()) {
            val stats = vaultedIds.mapNotNull { resolveVaultedStat(it, knownStats) }
            setVaultStarred(stats, starred)
        }
        if (realIds.isEmpty()) return@withContext Result.success(Unit)
        val asked = ctx.requireClient()
        val parents = ctx.index.knownParentsOf(realIds)
        runSuspendCatching { if (starred) asked.starFiles(realIds) else asked.unstarFiles(realIds) }
            .onSuccess { if (ctx.clientManager.currentClient.value === asked) applyChange(DriveChange.Starred(realIds, starred, parents)) }
    }

    fun startAccountAndQuotaSync() {
        val cacheStore = ctx.cacheStore
        ctx.backgroundScope.launch {
            var account: String? = null
            ctx.clientManager.currentClient.collectLatest { client ->
                if (client?.account != account) {
                    account = client?.account
                    _quotaFlow.value = client?.currentQuota
                    _transferQuotaFlow.value = client?.currentTransferQuota
                    ctx.index.enterAccount(client?.account)
                } else if (client != null) {
                    val prevQuota = _quotaFlow.value
                    val prevTq = _transferQuotaFlow.value
                    if (prevQuota != null || prevTq != null) {
                        client.seedAccountState(quota = prevQuota, transferQuota = prevTq)
                    }
                    _quotaFlow.value = client.currentQuota
                    _transferQuotaFlow.value = client.currentTransferQuota
                }
                if (client == null) return@collectLatest
                launch {
                    client.quotaFlow.collect { q ->
                        if (ctx.clientManager.currentClient.value === client && q != null) {
                            _quotaFlow.value = q
                            _quotaUpdates.tryEmit(client.account to q)
                            val driveTreeIndex = ctx.index.driveTreeIndex
                            if (driveTreeIndex.isComplete && driveTreeIndex.lastUsageBytes == null) {
                                driveTreeIndex.setLastUsageBytes(q.quota.usageBytes)
                            }
                        }
                    }
                }
                val slot = logAccountSlot(client.account)
                launch {
                    client.transferQuotaFlow.collect { tq ->
                        if (ctx.clientManager.currentClient.value === client && tq != null) {
                            _transferQuotaFlow.value = tq
                            cacheStore?.let { store ->
                                ctx.backgroundScope.launch {
                                    runSuspendCatching {
                                        store.write(transferQuotaCacheKey(client.account), QUOTA_JSON.encodeToString(TransferQuota.serializer(), tq))
                                    }.logFailure(DRIVE_REPO_TAG, "Failed to persist transfer quota cache (acct=$slot)")
                                }
                            }
                        }
                    }
                }
                if (cacheStore != null) {
                    if (_transferQuotaFlow.value == null) {
                        val cachedTq = runSuspendCatching {
                            cacheStore.read(transferQuotaCacheKey(client.account))
                                ?.let { QUOTA_JSON.decodeFromString(TransferQuota.serializer(), it) }
                        }.onFailure { err ->
                            PikoLog.w(
                                DRIVE_REPO_TAG,
                                "Corrupted transfer quota cache; ignoring snapshot (acct=$slot, error=${err::class.simpleName})",
                                err.takeIf { it !is SerializationException },
                            )
                        }.getOrNull()
                        if (cachedTq != null && ctx.clientManager.currentClient.value === client && _transferQuotaFlow.value == null) {
                            client.seedAccountState(transferQuota = cachedTq)
                            _transferQuotaFlow.value = client.currentTransferQuota ?: cachedTq
                        }
                    }
                    if (ctx.index.driveTreeIndex.isPersistent && ctx.index.driveTreeIndex.isComplete) {
                        val restoredIndex = ctx.index.driveTreeIndex
                        val restoredState = restoredIndex.flow.value
                        PikoLog.i(
                            DRIVE_REPO_TAG,
                            "Reused SQLite WAL tree index on startup (acct=$slot, folders=${restoredState.entries.size}, " +
                                "complete=${restoredState.complete}, dirtyFolders=${restoredState.dirtyFolders.size}, hasCursor=${restoredIndex.syncCursor != null})",
                        )
                        launch {
                            migrateLegacyTreeCache(restoredIndex, client.account, cacheStore)
                            delay(TREE_WARMUP_DELAY)
                            if (ctx.clientManager.currentClient.value === client && ctx.index.driveTreeIndex === restoredIndex) {
                                val firstProbe = ctx.index.probeRemoteChanges(client)
                                if (firstProbe.isFailure && ctx.clientManager.currentClient.value === client && ctx.index.driveTreeIndex === restoredIndex) {
                                    delay(2_000L)
                                    if (ctx.clientManager.currentClient.value === client && ctx.index.driveTreeIndex === restoredIndex) {
                                        ctx.index.probeRemoteChanges(client, force = true)
                                            .logFailure(DRIVE_REPO_TAG, "Background tree reconciliation failed after retry (acct=$slot)")
                                    }
                                }
                            }
                        }
                    }
                    if (!ctx.index.driveTreeIndex.isComplete) {
                        launch {
                            val restored = ctx.index.restoreTreeFromCache(client)
                            if (ctx.clientManager.currentClient.value !== client) return@launch
                            delay(TREE_WARMUP_DELAY)
                            if (ctx.clientManager.currentClient.value !== client) return@launch
                            if (restored) {
                                val firstProbe = ctx.index.probeRemoteChanges(client)
                                if (firstProbe.isFailure && ctx.clientManager.currentClient.value === client) {
                                    delay(2_000L)
                                    if (ctx.clientManager.currentClient.value === client) {
                                        ctx.index.probeRemoteChanges(client, force = true)
                                            .logFailure(DRIVE_REPO_TAG, "Background tree reconciliation failed after cache migration (acct=$slot)")
                                    }
                                }
                            } else if (!ctx.index.driveTreeIndex.isComplete) {
                                val firstSync = runSuspendCatching { ctx.index.ensureTreeSync(client).await() }
                                if (firstSync.isFailure && ctx.clientManager.currentClient.value === client && !ctx.index.driveTreeIndex.isComplete) {
                                    delay(2_000L)
                                    if (ctx.clientManager.currentClient.value === client && !ctx.index.driveTreeIndex.isComplete) {
                                        runSuspendCatching { ctx.index.ensureTreeSync(client, forceRebuild = false).await() }
                                            .logFailure(DRIVE_REPO_TAG, "Background tree index build failed after retry (acct=$slot)")
                                    }
                                } else {
                                    firstSync.logFailure(DRIVE_REPO_TAG, "Background tree index build failed (acct=$slot)")
                                }
                            }
                        }
                    }
                }
                launch {
                    getTransferQuota().logFailure(DRIVE_REPO_TAG, "Failed to fetch transfer quota (acct=$slot)")
                }
                launch {
                    getQuota()
                        .logFailure(DRIVE_REPO_TAG, "Failed to fetch storage quota; archive lease budget temporarily unbounded (acct=$slot)")
                        .getOrNull()?.quota?.takeIf { it.limitBytes > 0 }?.let { quota ->
                            if (client.leaseBudget != null) {
                                PikoLog.d(
                                    DRIVE_REPO_TAG,
                                    "Archive lease budget updated from storage quota (acct=$slot, remainingBytes=${quota.remainingBytes}, limitBytes=${quota.limitBytes})",
                                )
                            }
                        }
                }
            }
        }
    }

    companion object {
        private const val LOGGED_IDS = 5
        private const val UPLOAD_BYTES_ATTEMPTS = 3
        private val LINK_RETRY_DELAYS = listOf(500.milliseconds, 1.seconds, 2.seconds, 3.seconds, 5.seconds)
        private val TREE_WARMUP_DELAY = 1500.milliseconds
        private val QUOTA_JSON = Json { ignoreUnknownKeys = true }

        private fun transferQuotaCacheKey(account: String): String =
            "transfer-quota-" + account.replace(Regex("[^A-Za-z0-9._@-]"), "_") + ".json"

        internal suspend fun readSmallFileBytes(
            asked: PikPakClient,
            fileId: String,
            wait: suspend (Duration) -> Unit = { delay(it) },
        ): ByteArray {
            var detail: FileDetail? = null
            var lastError: Throwable? = null
            fun diagnostic(attempts: Int): String =
                "id=$fileId, phase=${detail?.phase}, size=${detail?.size}, links=${detail?.links?.keys}, " +
                    "hasWebLink=${detail?.webContentLink?.isNotBlank() == true}, attempts=$attempts"

            for (attempt in 0..LINK_RETRY_DELAYS.size) {
                lastError = null
                try {
                    val current = asked.getFile(fileId)
                    detail = current
                    val url = current.downloadUrl
                        ?: current.links.values.firstOrNull { it.url.isNotBlank() }?.url
                        ?: current.webContentLink.takeIf { it.isNotBlank() }
                    if (url != null) {
                        val size = current.size.toLongOrNull() ?: error(
                            tr("文件大小未知", "Unknown file size", "ファイルサイズが不明です", "파일 크기를 알 수 없습니다", "檔案大小未知"),
                        )
                        if (size == 0L) return ByteArray(0)
                        return asked.streamRangeFromUrl(url, start = 0L, length = size, {}) { stream -> stream.channel.toByteArray() }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    if (!isRetryableSmallFileFailure(e)) {
                        PikoLog.w(DRIVE_REPO_TAG, "Small file read failed: ${diagnostic(attempt + 1)}, error=${e::class.simpleName}", e)
                        throw e
                    }
                }
                val reason = lastError?.let { error ->
                    (error as? PikPakException)?.let { "${it::class.simpleName}(${it.httpStatus} ${it.errorMessage})" }
                        ?: "${error::class.simpleName}: ${error.message}"
                } ?: "empty_download_url"
                if (attempt == LINK_RETRY_DELAYS.size) {
                    val terminalError = lastError ?: IllegalStateException(
                        tr("未获取到下载链接", "Download link unavailable", "ダウンロードリンクを取得できません", "다운로드 링크를 가져올 수 없습니다", "無法取得下載連結"),
                    )
                    PikoLog.w(DRIVE_REPO_TAG, "Small file read exhausted retries: ${diagnostic(attempt + 1)}, reason=$reason", lastError)
                    throw terminalError
                }
                PikoLog.d(DRIVE_REPO_TAG, "Retrying small file read: ${diagnostic(attempt + 1)}, reason=$reason")
                wait(LINK_RETRY_DELAYS[attempt])
            }
            error("Unreachable")
        }

        private fun isRetryableSmallFileFailure(error: Exception): Boolean = when (error) {
            is PikPakException -> when {
                error.errorMessage == "file_in_recycle_bin" || error.errorMessage == "file_not_found" || error.httpStatus == 404 -> false
                error is FileReactivatingException || error is UrlExpiredException -> true
                else -> error.httpStatus == 403 || error.httpStatus == 429 || error.httpStatus in 500..599
            }
            is IOException -> true
            else -> false
        }
    }
}

internal fun requireCloudFileIds(ids: List<String>) {
    require(ids.none(LocalLocation::isLocal)) { "本地文件夹为只读" }
}
