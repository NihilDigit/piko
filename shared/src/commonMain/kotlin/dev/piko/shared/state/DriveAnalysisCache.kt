package dev.piko.shared.state

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import dev.piko.shared.data.ChildFile
import io.github.nihildigit.pikpak.FileStat

/**
 * 进程内复用纯计算结果，浏览上下文自己的展开与揭示状态不进入缓存。
 * 缓存访问可以来自不同上下文的协程；计算在锁之外进行，同一内容的并发请求只算一次。
 */
internal object DriveAnalysisCache {
    private val structureLock = Mutex()
    private val folderLock = Mutex()
    private val structures = LinkedHashMap<String, DriveStructure>()
    private val structureRequests = mutableMapOf<String, CompletableDeferred<DriveStructure>>()
    private val folderViews = LinkedHashMap<String, DriveFolderView>()

    /**
     * 按内容而不是目录 id 缓存：同一目录刷新后内容没变就直接命中，改名或增删则自然失效。
     * 顺序也进指纹，未识别的文件按用户选的排序排列。
     */
    fun fingerprint(files: List<FileStat>): String {
        var hash = 17L
        files.forEach { file ->
            hash = hash * 31 + file.id.hashCode()
            hash = hash * 31 + file.name.hashCode()
            hash = hash * 31 + file.size.hashCode()
            hash = hash * 31 + file.isFolder.hashCode()
            hash = hash * 31 + file.mimeType.hashCode()
        }
        return "${files.size}:$hash"
    }

    suspend fun analyze(
        files: List<FileStat>,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        compute: suspend (List<FileStat>) -> DriveStructure = ::analyzeDriveFolder,
    ): DriveStructure = withContext(dispatcher) { analyzeShared(files, compute) }

    private suspend fun analyzeShared(
        files: List<FileStat>,
        compute: suspend (List<FileStat>) -> DriveStructure,
    ): DriveStructure {
        val key = fingerprint(files)
        while (true) {
            currentCoroutineContext().ensureActive()
            val pending = CompletableDeferred<DriveStructure>()
            val request = structureLock.withLock {
                structures.remove(key)?.let {
                    structures[key] = it
                    return it
                }
                structureRequests.getOrPut(key) { pending }
            }
            if (request !== pending) {
                try {
                    return request.await()
                } catch (error: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    continue
                }
            }
            try {
                val value = compute(files)
                currentCoroutineContext().ensureActive()
                structureLock.withLock {
                    putBounded(structures, key, value, MAX_STRUCTURES)
                    structureRequests.remove(key)
                }
                pending.complete(value)
                return value
            } catch (error: Throwable) {
                // 先移除失败请求，再唤醒等待者，让仍有效的调用者接手计算。
                withContext(NonCancellable) {
                    structureLock.withLock { structureRequests.remove(key) }
                }
                pending.completeExceptionally(error)
                throw error
            }
        }
    }

    suspend fun structure(key: String): DriveStructure? = structureLock.withLock {
        structures.remove(key)?.also { structures[key] = it }
    }

    suspend fun putStructure(key: String, value: DriveStructure) = structureLock.withLock {
        putBounded(structures, key, value, MAX_STRUCTURES)
    }

    /** 名称或类型变化就重新描述，项数相同也不能复用旧结果。 */
    fun folderKey(folder: FileStat, content: List<ChildFile>?): String = "${folder.id}|${folder.name}|${content?.hashCode() ?: -1}"

    suspend fun folderView(key: String): DriveFolderView? = folderLock.withLock {
        folderViews.remove(key)?.also { folderViews[key] = it }
    }

    suspend fun putFolderView(key: String, value: DriveFolderView) = folderLock.withLock {
        putBounded(folderViews, key, value, MAX_FOLDER_VIEWS)
    }

    // commonMain 的 LinkedHashMap 没有按访问排序的构造参数，命中时先删再插，最早插入的即最久未用
    private fun <V> putBounded(map: LinkedHashMap<String, V>, key: String, value: V, limit: Int) {
        map.remove(key)
        map[key] = value
        while (map.size > limit) map.remove(map.keys.first())
    }

    private const val MAX_STRUCTURES = 24
    private const val MAX_FOLDER_VIEWS = 4_000
}
