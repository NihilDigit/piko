@file:JvmMultifileClass
@file:JvmName("PikoDriveRepositoryKt")

package dev.piko.shared.data

import dev.piko.shared.i18n.PikoLocale
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.logFolder
import dev.piko.shared.naming.MediaFileInput
import dev.piko.shared.naming.describeFolder
import dev.piko.shared.naming.toFileKind
import dev.piko.shared.search.prepareCjkQuery
import dev.piko.shared.util.PikoLock
import dev.piko.shared.util.withPikoLock
import io.github.nihildigit.pikpak.CreatedShare
import io.github.nihildigit.pikpak.EventPage
import io.github.nihildigit.pikpak.EventType
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.SearchHit
import io.github.nihildigit.pikpak.ShareFolderPage
import io.github.nihildigit.pikpak.ShareInfo
import io.github.nihildigit.pikpak.ShareListPage
import io.github.nihildigit.pikpak.TaskPhase
import io.github.nihildigit.pikpak.clearEvents
import io.github.nihildigit.pikpak.createShare
import io.github.nihildigit.pikpak.deleteEvents
import io.github.nihildigit.pikpak.deleteShares
import io.github.nihildigit.pikpak.getShareInfo
import io.github.nihildigit.pikpak.getTask
import io.github.nihildigit.pikpak.listEvents
import io.github.nihildigit.pikpak.listMyShares
import io.github.nihildigit.pikpak.listPlayHistory
import io.github.nihildigit.pikpak.listShareFiles
import io.github.nihildigit.pikpak.listStarred
import io.github.nihildigit.pikpak.listTrash
import io.github.nihildigit.pikpak.restoreShare
import io.github.nihildigit.pikpak.searchFiles
import kotlin.jvm.JvmMultifileClass
import kotlin.jvm.JvmName
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.withContext

/**
 * 四类资料库源（星标、最近添加、播放历史、回收站单飞缓存与服务端后台删除对账）、
 * 全盘/子树 CJK 模糊搜索（含作品标题解析缓存）与云端分享读写。
 */
internal class DriveLibraryAndSearchSource(
    private val ctx: DriveRepoContext,
) {
    private data class FolderTitleKey(val id: String, val name: String, val language: String, val contentHash: Int)
    private val folderTitleCache = LinkedHashMap<FolderTitleKey, String?>()
    private val folderTitleLock = PikoLock()

    private data class CachedTrashListing(
        val client: PikPakClient,
        val files: List<FileStat>,
        val listedAt: TimeSource.Monotonic.ValueTimeMark,
    )

    private val pendingServerDeletes = MutableStateFlow<Set<String>>(emptySet())
    private val cachedTrashListing = MutableStateFlow<CachedTrashListing?>(null)
    private val trashListingRequest = MutableStateFlow<Pair<PikPakClient, Deferred<List<FileStat>>>?>(null)

    fun clearFolderTitleCache() {
        withPikoLock(folderTitleLock) { folderTitleCache.clear() }
    }

    fun resetOnAccountSwitchLocked() {
        pendingServerDeletes.value = emptySet()
        invalidateTrashCache()
        clearFolderTitleCache()
    }

    fun recordServerDelete(ids: Collection<String>) {
        pendingServerDeletes.update { it + ids }
    }

    fun invalidateTrashCache() {
        cachedTrashListing.value = null
        trashListingRequest.value = null
    }

    suspend fun search(query: String): Result<List<FileStat>> = withContext(Dispatchers.Default) {
        runSuspendCatching { ctx.overlay.sourceLedger.enrich(ctx.requireClient().searchFiles(query)) }
    }

    fun searchRecursive(query: String, parentId: String = ""): Flow<SearchHit> = flow {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@flow
        val asked = ctx.requireClient()
        val store = ctx.vaultAccessor()
        val emittedIds = HashSet<String>()
        val checkedMissIds = HashSet<String>()

        fun extraFolderTitle(folder: FileStat): String? {
            val children = ctx.listing.childContents.get(folder.id) ?: return null
            val key = FolderTitleKey(folder.id, folder.name, PikoLocale.effectiveLanguage.name, children.hashCode())
            withPikoLock(folderTitleLock) {
                if (folderTitleCache.containsKey(key)) {
                    val cached = folderTitleCache.remove(key)
                    folderTitleCache[key] = cached
                    return cached
                }
            }
            val computed = describeFolder(folder.name, children.map { MediaFileInput(it.name, 0, it.category?.toFileKind()) }).title
            withPikoLock(folderTitleLock) {
                folderTitleCache[key] = computed
                var weight = folderTitleCache.entries.sumOf { (k, v) -> 96L + 2L * (k.id.length + k.name.length + (v?.length ?: 0)) }
                while (folderTitleCache.size > 4096 || weight > 1024L * 1024) {
                    val oldest = folderTitleCache.entries.first()
                    weight -= 96L + 2L * (oldest.key.id.length + oldest.key.name.length + (oldest.value?.length ?: 0))
                    folderTitleCache.remove(oldest.key)
                }
            }
            return computed
        }

        fun enrichFile(file: FileStat): FileStat =
            if (file.isFolder) ctx.overlay.withVaultCovers(listOf(file)).first() else ctx.overlay.sourceLedger.enrich(file)

        val preparedSearchQuery = prepareCjkQuery(cleanQuery)

        suspend fun emitNewMatches(incrementalSkip: MutableSet<String>? = null, excludeDirty: Boolean = false) {
            val driveTreeIndex = ctx.index.driveTreeIndex
            val hasPending = ctx.mutations.hasPendingOptimisticWrites
            val dirtyNow = if (excludeDirty) driveTreeIndex.dirtyFolders else emptySet()
            val hits = driveTreeIndex.searchLocal(
                query = cleanQuery,
                parentId = if (hasPending) "" else parentId,
                folderIndex = ctx.index.folderIndex,
                extraFolderTitle = ::extraFolderTitle,
                transformFile = ::enrichFile,
                skipIds = if (dirtyNow.isEmpty()) incrementalSkip else null,
            )
            val projectedHits = if (!hasPending) hits else {
                val folders = ctx.mutations.projectOptimisticFiles(driveTreeIndex.flow.value.entries.values.toList()).associateBy { it.id }
                ctx.mutations.projectOptimisticFiles((hits.map { it.file } + ctx.mutations.optimisticCandidates()).distinctBy { it.id })
                    .mapNotNull { file ->
                        val score = preparedSearchQuery.score(file.name)
                            ?: extraFolderTitle(file).takeIf { file.isFolder }?.let(preparedSearchQuery::score)
                        if (score == null || isPikoInternalFolder(file, file.parentId) || VaultStore.looksLikeManifest(file) || VaultStore.isRecoveryFile(file)) {
                            null
                        } else {
                            DriveTreeIndex.resolveBreadcrumb(file, parentId, folders, ctx.index.folderIndex.flow.value.nodes)?.let {
                                SearchHit(enrichFile(file), it)
                            }
                        }
                    }
            }
            for (hit in projectedHits) {
                if (dirtyNow.isNotEmpty() && (hit.file.parentId in dirtyNow || hit.file.id in dirtyNow)) continue
                if (emittedIds.add(hit.file.id)) emit(hit)
            }
        }

        emitNewMatches(checkedMissIds, excludeDirty = true)

        val initialTree = ctx.index.driveTreeIndex
        if (initialTree.isComplete) {
            val dirty = initialTree.dirtyFolders
            if (dirty.isNotEmpty()) {
                var pendingEmit = false
                var lastEmitMark = TimeSource.Monotonic.markNow()
                for (folderId in dirty) {
                    if (ctx.clientManager.currentClient.value !== asked) return@flow
                    val listing = runSuspendCatching { ctx.listing.fetchListing(folderId, asked) }
                        .logFailure(DRIVE_REPO_TAG, "Search failed to refresh dirty ${logFolder(folderId)}; skipping")
                        .getOrNull() ?: continue
                    runSuspendCatching {
                        ctx.overlay.readAndRecordVaultEntries(folderId, listing, store, asked, logFailure = true, logTagSuffix = " (search dirty refresh)")
                    }
                    pendingEmit = true
                    if (lastEmitMark.elapsedNow() >= 250.milliseconds) {
                        emitNewMatches()
                        pendingEmit = false
                        lastEmitMark = TimeSource.Monotonic.markNow()
                    }
                }
                if (pendingEmit) emitNewMatches()
            }
            return@flow
        }

        val sync = ctx.index.ensureTreeSync(asked)
        var lastSeenRev = ctx.index.driveTreeIndex.flow.value.revision
        while (sync.isActive) {
            delay(250)
            val currentRev = ctx.index.driveTreeIndex.flow.value.revision
            if (currentRev != lastSeenRev) {
                lastSeenRev = currentRev
                emitNewMatches(checkedMissIds)
            }
        }
        sync.await()
        emitNewMatches()
    }.flowOn(Dispatchers.Default)

    suspend fun trashFiles(fresh: Boolean = true): Result<List<FileStat>> =
        trashFilesForClient(ctx.requireClient(), fresh)

    suspend fun trashFilesForClient(asked: PikPakClient, fresh: Boolean = true): Result<List<FileStat>> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                if (!fresh) {
                    cachedTrashListing.value
                        ?.takeIf { it.client === asked && it.listedAt.elapsedNow() < TRASH_CACHE_FRESH }
                        ?.let {
                            return@runSuspendCatching if (ctx.clientManager.currentClient.value === asked) {
                                ctx.mutations.projectOptimisticFiles(
                                    (it.files + ctx.mutations.optimisticCandidates()).distinctBy { file -> file.id },
                                    trash = true,
                                )
                            } else {
                                it.files
                            }
                        }
                }
                var winner: Deferred<List<FileStat>>? = null
                val created = ctx.backgroundScope.async(start = CoroutineStart.LAZY) {
                    val listed = asked.listTrash()
                    if (ctx.clientManager.currentClient.value === asked) {
                        cachedTrashListing.value = CachedTrashListing(asked, listed, TimeSource.Monotonic.markNow())
                    }
                    listed
                }
                trashListingRequest.update { existing ->
                    if (!fresh && existing != null && existing.first === asked && existing.second.isActive) {
                        winner = existing.second
                        existing
                    } else {
                        winner = created
                        asked to created
                    }
                }
                val active = winner ?: created
                if (active !== created) {
                    created.cancel()
                } else {
                    created.invokeOnCompletion {
                        trashListingRequest.update { cur -> if (cur?.second === created) null else cur }
                    }
                    created.start()
                }
                active.await().let { files ->
                    if (ctx.clientManager.currentClient.value === asked) {
                        ctx.mutations.projectOptimisticFiles(
                            (files + ctx.mutations.optimisticCandidates()).distinctBy { it.id },
                            trash = true,
                        )
                    } else {
                        files
                    }
                }
            }
        }

    fun reconcileTrashListing(files: List<FileStat>): Boolean {
        val presentIds = files.mapTo(HashSet()) { it.id }
        return pendingServerDeletes.updateAndGet { it intersect presentIds }.isNotEmpty()
    }

    suspend fun shareInfo(shareId: String, passCode: String): Result<ShareInfo> = withContext(Dispatchers.Default) {
        runSuspendCatching { ctx.requireClient().getShareInfo(shareId, passCode) }
    }

    suspend fun shareFolderFull(
        shareId: String,
        passCodeToken: String,
        parentId: String,
        maxPages: Int = 20,
    ): Result<Pair<List<FileStat>, Boolean>> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val asked = ctx.requireClient()
            val all = ArrayList<FileStat>()
            var token = ""
            var pages = 0
            while (pages < maxPages) {
                val page = asked.listShareFiles(shareId, passCodeToken, parentId = parentId, pageToken = token)
                all += page.files
                token = page.nextPageToken
                pages++
                if (token.isEmpty()) break
            }
            all to token.isNotEmpty()
        }
    }

    suspend fun shareFolderPage(
        shareId: String,
        passCodeToken: String,
        parentId: String = "",
        pageToken: String = "",
    ): Result<ShareFolderPage> = withContext(Dispatchers.Default) {
        runSuspendCatching { ctx.requireClient().listShareFiles(shareId, passCodeToken, parentId = parentId, pageToken = pageToken) }
    }

    suspend fun shareFolder(shareId: String, passCodeToken: String, parentId: String): Result<List<FileStat>> =
        shareFolderFull(shareId, passCodeToken, parentId).map { it.first }

    suspend fun restoreFromShare(
        shareId: String,
        passCodeToken: String,
        fileIds: List<String>,
        toParentId: String,
        ancestorIds: List<String>,
    ): Result<Unit> = withContext(Dispatchers.Default) {
        val boundClient = ctx.requireClient()
        runSuspendCatching {
            val restore = boundClient.restoreShare(shareId, passCodeToken, fileIds, toParentId = toParentId, ancestorIds = ancestorIds)
            if (restore.restoreTaskId.isEmpty()) return@runSuspendCatching
            repeat(RESTORE_POLL_LIMIT) {
                val task = boundClient.getTask(restore.restoreTaskId)
                if (task.phase == TaskPhase.COMPLETE) return@runSuspendCatching
                if (task.phase == TaskPhase.ERROR) {
                    error(task.message.ifBlank { tr("转存失败", "Failed to save share", "共有の保存に失敗しました", "공유 저장 실패", "轉存失敗") })
                }
                delay(RESTORE_POLL_INTERVAL_MILLIS)
            }
            error(tr("转存超时", "Share save timed out", "共有の保存がタイムアウトしました", "공유 저장 시간이 초과되었습니다", "轉存逾時"))
        }.onSuccess {
            if (ctx.clientManager.currentClient.value === boundClient) {
                ctx.mutations.applyChange(DriveChange.ContentsChanged(setOf(toParentId)))
            }
        }
    }

    suspend fun createShare(fileIds: List<String>, passCode: String?, expirationDays: Int): Result<CreatedShare> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                requireCloudFileIds(fileIds)
                ctx.requireClient().createShare(
                    fileIds = fileIds,
                    requirePassCode = passCode != null,
                    customPassCode = passCode.orEmpty(),
                    expirationDays = expirationDays,
                )
            }
        }

    suspend fun myShares(pageToken: String = ""): Result<ShareListPage> = withContext(Dispatchers.Default) {
        runSuspendCatching { ctx.requireClient().listMyShares(pageToken = pageToken) }
    }

    suspend fun cancelShares(shareIds: List<String>): Result<Unit> = withContext(Dispatchers.Default) {
        runSuspendCatching { ctx.requireClient().deleteShares(shareIds) }
    }

    suspend fun starredFiles(): Result<List<FileStat>> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val remote = ctx.overlay.sourceLedger.enrich(ctx.requireClient().listStarred())
            val vaulted = ctx.overlay.sourceLedger.enrich(ctx.overlay.vaultStars.starredFileStats())
            val combined = ctx.mutations.projectOptimisticFiles(
                (remote + vaulted + ctx.mutations.optimisticCandidates()).distinctBy { it.id },
            ).filter { file -> file.isStarred || vaulted.any { it.id == file.id } }
            ctx.listing.sortFiles(combined, PikoFileSortOrder.TIME_DESC, "")
        }
    }

    suspend fun playHistory(pageToken: String = ""): Result<EventPage> = withContext(Dispatchers.Default) {
        runSuspendCatching { ctx.requireClient().listPlayHistory(pageToken = pageToken).withSources() }
    }

    suspend fun recentlyAdded(pageToken: String = ""): Result<EventPage> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            ctx.requireClient().listEvents(listOf(EventType.UPLOAD, EventType.RESTORE), pageToken = pageToken).withSources()
        }
    }

    private fun EventPage.withSources(): EventPage =
        copy(events = events.map { event -> event.copy(file = event.file?.let(ctx.overlay.sourceLedger::enrich)) })

    suspend fun deleteEvents(eventIds: List<String>): Result<Unit> = withContext(Dispatchers.Default) {
        runSuspendCatching { ctx.requireClient().deleteEvents(eventIds) }
    }

    suspend fun clearPlayHistory(): Result<Unit> = withContext(Dispatchers.Default) {
        runSuspendCatching { ctx.requireClient().clearEvents(listOf(EventType.PLAY)) }
    }

    private companion object {
        val TRASH_CACHE_FRESH = 300.seconds
        const val RESTORE_POLL_LIMIT = 60
        const val RESTORE_POLL_INTERVAL_MILLIS = 1_000L
    }
}
