package dev.piko.shared.state

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.piko.shared.data.ChildFile
import io.github.nihildigit.pikpak.FileStat

/**
 * 进程内复用纯计算结果，浏览上下文自己的展开与揭示状态不进入缓存。
 * 缓存访问可以来自不同上下文的协程；计算在锁之外进行。
 */
internal object DriveAnalysisCache {
    private val structureLock = Mutex()
    private val folderLock = Mutex()
    private val structures = LinkedHashMap<String, DriveStructure>()
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
        }
        return "${files.size}:$hash"
    }

    suspend fun structure(key: String): DriveStructure? = structureLock.withLock {
        structures.remove(key)?.also { structures[key] = it }
    }

    suspend fun putStructure(key: String, value: DriveStructure) = structureLock.withLock {
        putBounded(structures, key, value, MAX_STRUCTURES)
    }

    /** 名称或类型变化就重新描述，项数相同也不能复用旧结果。 */
    fun folderKey(folder: FileStat, content: List<ChildFile>?): String = "${folder.id}|${folder.name}|${content?.hashCode() ?: -1}"

    suspend fun folderView(key: String): DriveFolderView? = folderLock.withLock { folderViews[key] }

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
