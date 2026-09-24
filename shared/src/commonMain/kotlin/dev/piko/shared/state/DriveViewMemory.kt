package dev.piko.shared.state

import androidx.compose.runtime.mutableStateMapOf
import io.github.nihildigit.pikpak.FileStat

/**
 * 网盘列表在进程内记住的东西：目录分析结果、文件夹描述、各目录的「显示全部」与分区展开状态。
 *
 * 放在进程级而不是 [DriveScreenState] 里：状态类随网盘页的组合重建，切一次 Tab 就没了，
 * 回来得把上千个文件重新解析一遍。只在主线程读写（状态类在主线程的协程里存取，计算才切到后台），
 * 所以用普通集合。
 */
internal object DriveViewMemory {
    val showAll = mutableStateMapOf<String, Boolean>()

    /** 键为「目录 id|块 id」，没有记录时按块的默认值。 */
    val expanded = mutableStateMapOf<String, Boolean>()

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

    fun structure(key: String): DriveStructure? = structures.remove(key)?.also { structures[key] = it }

    fun putStructure(key: String, value: DriveStructure) = putBounded(structures, key, value, MAX_STRUCTURES)

    /** 内容的有无与多少进键：补取到文件名后要重新描述。 */
    fun folderKey(folder: FileStat, content: List<String>?): String = "${folder.id}|${folder.name}|${content?.size ?: -1}"

    fun folderView(key: String): DriveFolderView? = folderViews[key]

    fun putFolderView(key: String, value: DriveFolderView) = putBounded(folderViews, key, value, MAX_FOLDER_VIEWS)

    // commonMain 的 LinkedHashMap 没有按访问排序的构造参数，命中时先删再插，最早插入的即最久未用
    private fun <V> putBounded(map: LinkedHashMap<String, V>, key: String, value: V, limit: Int) {
        map.remove(key)
        map[key] = value
        while (map.size > limit) map.remove(map.keys.first())
    }

    private const val MAX_STRUCTURES = 24
    private const val MAX_FOLDER_VIEWS = 4_000
}
