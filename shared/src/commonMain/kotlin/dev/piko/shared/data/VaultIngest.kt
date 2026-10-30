package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.ResolvedFile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Clock

/**
 * 归档条目的可选媒体与校验元数据。
 * 以引用保存（分享链接解析、本机已知同内容文件）与网盘真实文件归档统一经此结构传递与合并。
 */
data class VaultMediaMeta(
    val cid: String? = null,
    val duration: String? = null,
    val width: String? = null,
    val height: String? = null,
    val mimeType: String? = null,
) {
    val isEmpty: Boolean
        get() = cid.isNullOrBlank() && duration.isNullOrBlank() && width.isNullOrBlank() &&
            height.isNullOrBlank() && mimeType.isNullOrBlank()

    fun mergeWith(fallback: VaultMediaMeta?): VaultMediaMeta {
        if (fallback == null) return this
        return VaultMediaMeta(
            cid = cid?.takeIf { it.isNotBlank() } ?: fallback.cid?.takeIf { it.isNotBlank() },
            duration = duration?.takeIf { it.isNotBlank() } ?: fallback.duration?.takeIf { it.isNotBlank() },
            width = width?.takeIf { it.isNotBlank() } ?: fallback.width?.takeIf { it.isNotBlank() },
            height = height?.takeIf { it.isNotBlank() } ?: fallback.height?.takeIf { it.isNotBlank() },
            mimeType = mimeType?.takeIf { it.isNotBlank() } ?: fallback.mimeType?.takeIf { it.isNotBlank() },
        )
    }

    companion object {
        fun fromFileStat(file: FileStat, sampledCid: String? = null): VaultMediaMeta = VaultMediaMeta(
            cid = sampledCid?.takeIf { it.isNotBlank() } ?: file.params["piko_vault_cid"]?.takeIf { it.isNotBlank() },
            duration = file.params["duration"]?.takeIf { it.isNotBlank() },
            width = file.params["width"]?.takeIf { it.isNotBlank() },
            height = file.params["height"]?.takeIf { it.isNotBlank() },
            mimeType = file.mimeType.takeIf { it.isNotBlank() },
        )

        fun fromVaultEntry(entry: VaultEntry): VaultMediaMeta = VaultMediaMeta(
            cid = entry.cid?.takeIf { it.isNotBlank() },
            duration = entry.duration?.takeIf { it.isNotBlank() },
            width = entry.width?.takeIf { it.isNotBlank() },
            height = entry.height?.takeIf { it.isNotBlank() },
            mimeType = entry.mimeType?.takeIf { it.isNotBlank() },
        )
    }
}

/**
 * 待写入归档清单的单条引用草稿。
 * - 「以引用保存」仅提供虚拟文件属性（[name]、[size]、[gcid]、[source]、[sourcePath]、[meta]），无原文件需处置；
 * - 「归档」额外携带网盘已有真实文件 [originFile]，供清单确认落盘后迁移星标、登记撤销并将原文件移入回收站或永久删除。
 */
internal class VaultEntryDraft(
    val name: String,
    val size: Long,
    val gcid: String,
    val source: String? = null,
    val sourcePath: String = name,
    val meta: VaultMediaMeta? = null,
    val originFile: FileStat? = null,
)

/** 单个目录一次引用写入的结果回执。 */
internal class VaultFolderIngestOutcome(
    val folderId: String,
    val drafts: List<VaultEntryDraft>,
    val entries: List<VaultEntry>,
    val writeResult: Result<Unit>,
    val postCommitError: Throwable? = null,
    val confirmedEntries: List<VaultEntry>? = null,
) {
    val failure: Throwable? get() = writeResult.exceptionOrNull() ?: postCommitError
}

/**
 * 统一的归档引用写入内核。
 *
 * 「以引用保存」与「归档」本质上都是向目标目录写入 `.piko-vault-v<版本>-<随机串>.json` 清单中的 [VaultEntry]：
 * 两者共用同一套条目元数据补齐、新建空目录单次直写快速通道（[freshFolderIds]）、目录级并发写入与来源账本提取；
 * 唯一区别在于「归档」会在每个目录清单确认写成后，通过 [onFolderCommitted] 额外执行原文件删除或移入回收站。
 */
internal object VaultIngest {

    fun buildEntry(
        draft: VaultEntryDraft,
        addedAtMillis: Long,
        knownMetaLookup: (gcid: String, size: Long) -> VaultMediaMeta? = { _, _ -> null },
    ): VaultEntry {
        val baseMeta = draft.meta ?: draft.originFile?.let { VaultMediaMeta.fromFileStat(it) }
        val needLookup = baseMeta == null || baseMeta.cid == null || baseMeta.duration == null ||
            baseMeta.width == null || baseMeta.height == null || baseMeta.mimeType == null
        val resolved = if (needLookup) {
            (baseMeta ?: VaultMediaMeta()).mergeWith(knownMetaLookup(draft.gcid, draft.size))
        } else {
            baseMeta
        }
        return VaultEntry.create(
            name = draft.name,
            size = draft.size,
            gcid = draft.gcid,
            source = draft.source,
            addedAt = addedAtMillis,
            cid = resolved.cid,
            duration = resolved.duration,
            width = resolved.width,
            height = resolved.height,
            mimeType = resolved.mimeType,
        )
    }

    /** 将一批已确认写入的草稿按来源分组转换为 [SourceLedger.record] 所需的映射。 */
    fun sourceRecordsOf(drafts: Iterable<VaultEntryDraft>): Map<String, List<ResolvedFile>> {
        val grouped = LinkedHashMap<String, MutableList<ResolvedFile>>()
        for (draft in drafts) {
            val src = draft.source?.takeIf { it.isNotBlank() } ?: continue
            if (draft.gcid.isBlank()) continue
            grouped.getOrPut(src) { ArrayList() } += ResolvedFile(path = draft.sourcePath, size = draft.size, gcid = draft.gcid)
        }
        return grouped
    }

    suspend fun ingestFolders(
        batches: Map<String, List<VaultEntryDraft>>,
        folderPermits: Semaphore,
        freshFolderIds: Set<String> = emptySet(),
        shouldStop: () -> Boolean = { false },
        knownMetaLookup: (gcid: String, size: Long) -> VaultMediaMeta? = { _, _ -> null },
        beforeFolderWrite: suspend (folderId: String, drafts: List<VaultEntryDraft>, entries: List<VaultEntry>) -> Unit = { _, _, _ -> },
        writeFolder: suspend (folderId: String, entries: List<VaultEntry>, isFreshFolder: Boolean) -> Result<Unit>,
        verifyCommittedOnFailure: (suspend (folderId: String) -> List<VaultEntry>?)? = null,
        onFolderCommitted: suspend (folderId: String, drafts: List<VaultEntryDraft>, entries: List<VaultEntry>) -> Unit = { _, _, _ -> },
    ): List<VaultFolderIngestOutcome> = ingestFolders(
        folderIds = batches.keys.toList(),
        folderPermits = folderPermits,
        freshFolderIds = freshFolderIds,
        shouldStop = shouldStop,
        prepareDrafts = { folderId -> batches.getValue(folderId) },
        knownMetaLookup = knownMetaLookup,
        beforeFolderWrite = beforeFolderWrite,
        writeFolder = writeFolder,
        verifyCommittedOnFailure = verifyCommittedOnFailure,
        onFolderCommitted = onFolderCommitted,
    )

    suspend fun ingestFolders(
        folderIds: List<String>,
        folderPermits: Semaphore,
        freshFolderIds: Set<String> = emptySet(),
        shouldStop: () -> Boolean = { false },
        prepareDrafts: suspend (folderId: String) -> List<VaultEntryDraft>,
        knownMetaLookup: (gcid: String, size: Long) -> VaultMediaMeta? = { _, _ -> null },
        beforeFolderWrite: suspend (folderId: String, drafts: List<VaultEntryDraft>, entries: List<VaultEntry>) -> Unit = { _, _, _ -> },
        writeFolder: suspend (folderId: String, entries: List<VaultEntry>, isFreshFolder: Boolean) -> Result<Unit>,
        verifyCommittedOnFailure: (suspend (folderId: String) -> List<VaultEntry>?)? = null,
        onFolderCommitted: suspend (folderId: String, drafts: List<VaultEntryDraft>, entries: List<VaultEntry>) -> Unit = { _, _, _ -> },
    ): List<VaultFolderIngestOutcome> = coroutineScope {
        folderIds.map { folderId ->
            async {
                folderPermits.withPermit {
                    if (shouldStop()) return@withPermit null
                    val preparedResult = runSuspendCatching { prepareDrafts(folderId) }
                    if (preparedResult.isFailure) {
                        return@withPermit VaultFolderIngestOutcome(
                            folderId = folderId,
                            drafts = emptyList(),
                            entries = emptyList(),
                            writeResult = preparedResult.map { },
                        )
                    }
                    val drafts = preparedResult.getOrThrow()
                    if (drafts.isEmpty()) return@withPermit null
                    val addedAt = Clock.System.now().toEpochMilliseconds()
                    val entries = drafts.map { draft -> buildEntry(draft, addedAt, knownMetaLookup) }
                    val preflight = runSuspendCatching { beforeFolderWrite(folderId, drafts, entries) }
                    if (preflight.isFailure) {
                        return@withPermit VaultFolderIngestOutcome(
                            folderId = folderId,
                            drafts = drafts,
                            entries = entries,
                            writeResult = preflight,
                        )
                    }
                    val isFresh = folderId in freshFolderIds
                    val writeResult = writeFolder(folderId, entries, isFresh)
                    if (writeResult.isSuccess) {
                        val postResult = runSuspendCatching { onFolderCommitted(folderId, drafts, entries) }
                        VaultFolderIngestOutcome(
                            folderId = folderId,
                            drafts = drafts,
                            entries = entries,
                            writeResult = writeResult,
                            postCommitError = postResult.exceptionOrNull(),
                            confirmedEntries = entries,
                        )
                    } else {
                        val verified = verifyCommittedOnFailure?.invoke(folderId)
                        VaultFolderIngestOutcome(
                            folderId = folderId,
                            drafts = drafts,
                            entries = entries,
                            writeResult = writeResult,
                            confirmedEntries = verified,
                        )
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }
}
