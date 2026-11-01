package dev.piko.shared.data

import dev.piko.data.repository.FileCategory
import dev.piko.data.repository.fileCategory
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

/**
 * 文件夹里的一个文件，供文件夹行解析作品名。[category] 只在名字认不出类型、服务端却知道时才有：
 * 不带扩展名的视频（「… - 12 [WebRip 1080p][END]」）光看名字会被当成说明文件。
 */
data class ChildFile(val name: String, val category: FileCategory? = null) {
    companion object {
        fun of(file: FileStat): ChildFile {
            val byServer = file.fileCategory()
            return ChildFile(file.name, byServer.takeIf { file.name.fileCategory() == FileCategory.DOCUMENT && it != FileCategory.DOCUMENT })
        }
    }
}

/**
 * 各文件夹里的文件，跨进程保留。列表接口只给文件夹本身，不给其中的文件名，文件夹行要解析作品名
 * 就只能靠进过这个目录时记下的内容；原先只在内存里，每次重启都得把目录重新点一遍，文件夹才认得出来。
 *
 * 按账号分文件存在 [store] 里，登录后在后台载入，有改动时隔几秒写一次。总量有上限：每个目录只存前
 * [SAVED_FILES_PER_FOLDER] 个，最多 [MAX_SAVED_FOLDERS] 个目录，最久没用的先丢。缓存被系统清掉也无妨，
 * 进目录时会重新记下。
 */
internal class FolderContentMemory(
    private val store: PikoCacheStore?,
    private val scope: CoroutineScope,
    // 攒一会儿再写：进一个目录就改一次，逐次写盘没有必要
    private val saveDelayMillis: Long = 5_000L,
) {
    private data class MemoryState(
        val contents: Map<String, List<ChildFile>> = emptyMap(),
        val epochs: Map<String, Long> = emptyMap(),
        val clearEpoch: Long = 0L,
        val revision: Long = 0L,
    )
    private val memory = MutableStateFlow(MemoryState())

    // revision 让内容相同的访问也能发布新的迭代顺序，value 始终读取同一个原子状态。
    val state: StateFlow<Map<String, List<ChildFile>>> = object : StateFlow<Map<String, List<ChildFile>>> {
        override val value get() = memory.value.contents
        override val replayCache get() = listOf(value)

        @OptIn(InternalCoroutinesApi::class)
        override suspend fun collect(collector: FlowCollector<Map<String, List<ChildFile>>>): Nothing =
            memory.collect { collector.emit(it.contents) }
    }

    // 换号在仓库的后台协程里，记下内容在列目录的线程上，两边都读
    @Volatile
    private var account: String? = null
    private var generation = 0L
    private var saving: SaveSession? = null
    private var previousFlush: Deferred<Result<Unit>>? = null
    // 保留失败交接的完整快照，原账号再次进入时接管并重试。
    private val failedSnapshots = MutableStateFlow<Map<String, FolderSnapshot>>(emptyMap())

    private data class FolderSnapshot(
        val memory: MemoryState,
        val vaulted: Set<String>,
        val covers: Map<String, String>,
    )

    private inner class SaveSession(val owner: String, val generation: Long, initial: FolderSnapshot) {
        val job = Job(scope.coroutineContext[Job])
        val loaded = CompletableDeferred<Unit>()
        val snapshot = MutableStateFlow(VersionedSnapshot(PersistVersion(generation, 0), initial))
        val writer = SnapshotWriter(
            scope = CoroutineScope(scope.coroutineContext + job),
            accountGeneration = generation,
            source = SnapshotSource { snapshot.value },
            debounceMillis = saveDelayMillis,
        ) { value ->
            val folders = value.memory.contents.map { (id, files) ->
                StoredFolder(id, files.map { StoredFile(it.name, it.category) })
            }
            store?.writeStream(keyOf(owner)) { store?.jsonCodec?.encode(serializer, folders, it) ?: it.writeString(json.encodeToString(serializer, folders)) }
            store?.writeStream(vaultKeyOf(owner)) { store?.jsonCodec?.encode(vaultSerializer, value.vaulted.toList(), it)
                ?: it.writeString(json.encodeToString(vaultSerializer, value.vaulted.toList())) }
            store?.writeStream(coverKeyOf(owner)) { store?.jsonCodec?.encode(coverSerializer, value.covers, it) ?: it.writeString(json.encodeToString(coverSerializer, value.covers)) }
        }
    }

    fun get(folderId: String): List<ChildFile>? {
        while (true) {
            val current = memory.value
            val files = current.contents[folderId] ?: return null
            val next = current.copy(contents = touched(current.contents, folderId, files), revision = current.revision + 1)
            if (memory.compareAndSet(current, next)) {
                scheduleSave()
                return files
            }
        }
    }

    fun put(folderId: String, files: List<ChildFile>) {
        memory.update { it.copy(contents = touched(it.contents, folderId, files.take(SAVED_FILES_PER_FOLDER)), revision = it.revision + 1) }
        scheduleSave()
    }

    fun invalidate(folderIds: Set<String>?) {
        if (folderIds == null) {
            clear()
            return
        }
        invalidateCompleteness(folderIds)
        memory.update { current ->
            val epochs = current.epochs + folderIds.associateWith { (current.epochs[it] ?: 0L) + 1 }
            val overflow = epochs.size > MAX_SAVED_FOLDERS
            current.copy(
                contents = current.contents - folderIds,
                // 墓碑溢出时放弃整批旧快照回填，不遗忘单个失效记录后误恢复。
                epochs = if (overflow) emptyMap() else epochs,
                clearEpoch = current.clearEpoch + if (overflow) 1 else 0,
                revision = current.revision + 1,
            )
        }
        scheduleSave()
    }

    fun clear() {
        completeness.value = emptyMap()
        memory.update { MemoryState(clearEpoch = it.clearEpoch + 1, revision = it.revision + 1) }
        scheduleSave()
    }

    private fun touched(current: Map<String, List<ChildFile>>, folderId: String, files: List<ChildFile>): Map<String, List<ChildFile>> =
        bounded(LinkedHashMap(current).apply { remove(folderId); put(folderId, files) })

    private fun <T> bounded(value: Map<String, T>): Map<String, T> =
        if (value.size <= MAX_SAVED_FOLDERS) value else value.entries.toList().takeLast(MAX_SAVED_FOLDERS).associate { it.toPair() }

    // 完全归档只信本次会话完整列表或成功的全量归档，不从跨进程的抽样文件名推断。
    private data class Completeness(val hasRealFiles: Boolean, val children: Set<String>)
    private val completeness = MutableStateFlow<Map<String, Completeness>>(emptyMap())
    val fullyVaultedFolders: Flow<Set<String>> = completeness.map { known ->
        val full = mutableSetOf<String>()
        do {
            val before = full.size
            known.forEach { (id, level) ->
                if (!level.hasRealFiles && level.children.all { it in full }) full += id
            }
        } while (full.size != before)
        full
    }

    fun rememberCompleteness(folderId: String, hasRealFiles: Boolean, children: Set<String>, complete: Boolean) {
        if (complete && children.size <= MAX_SAVED_FOLDERS) completeness.update {
            bounded(it - folderId + (folderId to Completeness(hasRealFiles, children.toSet())))
        }
        else if (hasRealFiles || children.isNotEmpty()) invalidateCompleteness(setOf(folderId))
    }

    fun markFullyVaulted(folderIds: Set<String>, parents: Map<String, String>) {
        completeness.update { current -> bounded(current + folderIds.takeLastBounded().associateWith { id ->
            val children = folderIds.filterTo(mutableSetOf()) { parents[it] == id }
            // 不能截断子目录后错误宣称整个目录已归档。
            Completeness(children.size > MAX_SAVED_FOLDERS, children.take(MAX_SAVED_FOLDERS).toSet())
        }) }
    }

    private fun invalidateCompleteness(folderIds: Set<String>) {
        completeness.update { current ->
            val affected = folderIds.toMutableSet()
            do {
                val before = affected.size
                current.forEach { (id, level) -> if (level.children.any { it in affected }) affected += id }
            } while (affected.size != before)
            current - affected
        }
    }

    private val vaulted = MutableStateFlow<Set<String>>(emptySet())

    /**
     * 直接放着归档条目的文件夹，文件夹上据此挂标记。跨进程保留：列表接口不给文件夹里有什么，不记下的话
     * 每次重启都要把目录重新点一遍，标记才出来。只认进过的与 Piko 自己写过清单的文件夹。
     *
     * 与文件名分开存一份：归档一整棵树时，子文件夹的清单是 Piko 写的，没人列过它们，没有文件名可记；
     * 塞进文件名那份就成了「记下的内容是空表」，文件夹行会当它是空的去探。
     */
    val vaultedFolders: StateFlow<Set<String>> = vaulted.asStateFlow()

    fun markVaulted(folderId: String, has: Boolean) {
        if ((folderId in vaulted.value) == has) return
        vaulted.update { if (has) (it - folderId + folderId).takeLastBounded() else it - folderId }
        scheduleSave()
    }

    private val covers = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * 直接放着归档条目的文件夹 → 替它当封面的视频条目的 gcid；读过清单而其中没有视频的记空串，免得反复去读。
     *
     * 文件夹的封面是服务端按其中的真实文件给的 thumbnail_link，内容归档、原文件删掉或移出回收站后就没了，
     * 归档条目又不是服务端的文件，所以由清单补上。跨进程保留：没进过的文件夹要读一次清单才知道，读过的不再读。
     */
    val vaultCovers: StateFlow<Map<String, String>> = covers.asStateFlow()

    fun rememberCover(folderId: String, gcid: String) {
        if (covers.value[folderId] == gcid) return
        covers.update { bounded(it - folderId + (folderId to gcid)) }
        scheduleSave()
    }

    /** 列过、记下了内容的文件夹。归档树里的内层文件夹没列过时，外层按「还有条目」算，见 VaultTrees.marked。 */
    val listedFolders: Flow<Set<String>> = state.map { it.keys }

    /** 换账号或退出登录。退出时只清内存，磁盘上的留给下次登录同一账号。 */
    fun switchAccount(newAccount: String?) {
        if (newAccount == account) return
        flushPendingSave()
        account = newAccount
        val token = ++generation
        val loadedAt = memory.updateAndGet { MemoryState(clearEpoch = it.clearEpoch + 1, revision = it.revision + 1) }
        completeness.value = emptyMap()
        vaulted.value = emptySet()
        covers.value = emptyMap()
        val cacheStore = store ?: return
        if (newAccount == null) return
        val session = SaveSession(newAccount, token, FolderSnapshot(loadedAt, emptySet(), emptyMap()))
        saving = session
        val barrier = previousFlush
        scope.launch {
            try {
                barrier?.join()
                val recovered = failedSnapshots.value[newAccount]
                val stored = recovered?.memory?.contents?.map { (id, files) ->
                    StoredFolder(id, files.map { StoredFile(it.name, it.category) })
                } ?: runSuspendCatching {
                    cacheStore.readStream(keyOf(newAccount)) {
                        cacheStore.jsonCodec?.decode(serializer, it) ?: json.decodeFromString(serializer, it.readString())
                    }
                }.getOrNull()
                val storedVaulted = recovered?.vaulted?.toList() ?: runSuspendCatching {
                    cacheStore.readStream(vaultKeyOf(newAccount)) {
                        cacheStore.jsonCodec?.decode(vaultSerializer, it) ?: json.decodeFromString(vaultSerializer, it.readString())
                    }
                }.getOrNull()
                val storedCovers = recovered?.covers ?: runSuspendCatching {
                    cacheStore.readStream(coverKeyOf(newAccount)) {
                        cacheStore.jsonCodec?.decode(coverSerializer, it) ?: json.decodeFromString(coverSerializer, it.readString())
                    }
                }.getOrNull()
                val loaded = stored.orEmpty().takeLast(MAX_SAVED_FOLDERS).associate { folder ->
                    folder.id to folder.files.take(SAVED_FILES_PER_FOLDER).map { ChildFile(it.name, it.category) }
                }
                session.snapshot.update { snapshot ->
                    val current = snapshot.value
                    snapshot.copy(
                        version = snapshot.version.copy(revision = snapshot.version.revision + if (recovered != null) 1 else 0),
                        value = current.copy(
                            memory = if (current.memory.clearEpoch != loadedAt.clearEpoch) current.memory else current.memory.copy(
                                contents = bounded(loaded.filterKeys { current.memory.epochs[it] == loadedAt.epochs[it] } -
                                    current.memory.contents.keys + current.memory.contents),
                            ),
                            vaulted = (storedVaulted.orEmpty().toSet() + current.vaulted).takeLastBounded(),
                            covers = bounded(storedCovers.orEmpty() + current.covers),
                        ),
                    )
                }
                if (recovered != null) failedSnapshots.update { it - newAccount }
                if (generation != token) return@launch
                // 载入期间本会话记下的更新优先；旧标记在下次真实列举时重新校验。
                if (storedVaulted != null) vaulted.update { current -> (storedVaulted.toSet() + current).takeLastBounded() }
                if (storedCovers != null) covers.update { current -> bounded(storedCovers + current) }
                if (stored == null) return@launch
                memory.update { current ->
                    if (current.clearEpoch != loadedAt.clearEpoch) current else {
                        val valid = loaded.filterKeys { id -> current.epochs[id] == loadedAt.epochs[id] }
                        current.copy(
                            contents = bounded(LinkedHashMap(valid - current.contents.keys + current.contents)),
                            revision = current.revision + 1,
                        )
                    }
                }
            } catch (failure: Throwable) {
                session.loaded.completeExceptionally(failure)
                throw failure
            } finally {
                if (session.loaded.complete(Unit)) {
                    val version = session.snapshot.value.version
                    if (version.revision > 0 && session.job.isActive) session.writer.markDirty(version)
                }
            }
        }
    }

    private fun scheduleSave() {
        val session = saving ?: return
        val version = session.snapshot.updateAndGet {
            VersionedSnapshot(PersistVersion(session.generation, it.version.revision + 1), FolderSnapshot(memory.value, vaulted.value, covers.value))
        }.version
        if (session.loaded.isCompleted && !session.loaded.isCancelled) session.writer.markDirty(version)
    }

    private fun flushPendingSave() {
        val session = saving ?: return
        saving = null
        val predecessor = previousFlush
        previousFlush = scope.async {
            try {
                // 前代失败只影响它自己的提交，不能阻断其他账号加载。
                predecessor?.join()
                session.loaded.await()
                if (session.snapshot.value.version.revision > 0) session.writer.flushThrough(session.snapshot.value.version)
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failedSnapshots.update { it + (session.owner to session.snapshot.value.value) }
                dev.piko.shared.log.PikoLog.w("FolderContentMemory", "保存旧账号目录缓存失败", failure)
                Result.failure(failure)
            } finally {
                session.job.cancel()
            }
        }
    }

    private fun Set<String>.takeLastBounded(): Set<String> =
        if (size <= MAX_SAVED_FOLDERS) this else toList().takeLast(MAX_SAVED_FOLDERS).toSet()

    private fun keyOf(account: String) = "folder-contents-" + account.replace(UNSAFE_KEY_CHARS, "_") + ".json"

    private fun vaultKeyOf(account: String) = "vault-folders-" + account.replace(UNSAFE_KEY_CHARS, "_") + ".json"

    private fun coverKeyOf(account: String) = "vault-covers-" + account.replace(UNSAFE_KEY_CHARS, "_") + ".json"

    @Serializable
    private class StoredFolder(val id: String, val files: List<StoredFile>)

    // 键名缩短：上千个目录、几万个文件名，字段名重复几万遍
    @Serializable
    private class StoredFile(@SerialName("n") val name: String, @SerialName("c") val category: FileCategory? = null)

    private companion object {
        const val SAVED_FILES_PER_FOLDER = 40
        const val MAX_SAVED_FOLDERS = 2_000
        val UNSAFE_KEY_CHARS = Regex("""[^A-Za-z0-9._@-]""")
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val serializer = ListSerializer(StoredFolder.serializer())
        val vaultSerializer = ListSerializer(String.serializer())
        val coverSerializer = MapSerializer(String.serializer(), String.serializer())
    }
}
