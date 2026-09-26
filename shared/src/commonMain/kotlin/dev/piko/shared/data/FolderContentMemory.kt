package dev.piko.shared.data

import dev.piko.data.repository.FileCategory
import dev.piko.data.repository.fileCategory
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
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
    // 插入顺序即使用顺序：命中时移到末尾，超额时从头丢
    private val contents = MutableStateFlow<Map<String, List<ChildFile>>>(emptyMap())

    private val _loads = MutableStateFlow(0)

    /** 从磁盘载入完成一次就加一，文件夹行据此重新描述。 */
    val loads: StateFlow<Int> = _loads.asStateFlow()

    // 换号在仓库的后台协程里，记下内容在列目录的线程上，两边都读
    @Volatile
    private var account: String? = null
    private var pendingSave: Job? = null

    fun get(folderId: String): List<ChildFile>? = contents.value[folderId]

    fun put(folderId: String, files: List<ChildFile>) {
        contents.update { (it - folderId) + (folderId to files) }
        scheduleSave()
    }

    /** 换账号或退出登录。退出时只清内存，磁盘上的留给下次登录同一账号。 */
    fun switchAccount(newAccount: String?) {
        if (newAccount == account) return
        pendingSave?.cancel()
        account = newAccount
        contents.value = emptyMap()
        val cacheStore = store ?: return
        if (newAccount == null) return
        scope.launch {
            val stored = cacheStore.read(keyOf(newAccount))?.let { text -> runCatching { json.decodeFromString(serializer, text) }.getOrNull() }
            if (account != newAccount || stored == null) return@launch
            val loaded = stored.associate { folder -> folder.id to folder.files.map { ChildFile(it.name, it.category) } }
            // 载入期间本会话记下的更新，留着它们
            contents.update { current -> loaded - current.keys + current }
            _loads.update { it + 1 }
        }
    }

    private fun scheduleSave() {
        val cacheStore = store ?: return
        val owner = account ?: return
        if (pendingSave?.isActive == true) return
        pendingSave = scope.launch {
            delay(saveDelayMillis)
            val snapshot = contents.value.entries.toList().takeLast(MAX_SAVED_FOLDERS).map { (id, files) ->
                StoredFolder(id, files.take(SAVED_FILES_PER_FOLDER).map { StoredFile(it.name, it.category) })
            }
            cacheStore.write(keyOf(owner), json.encodeToString(serializer, snapshot))
        }
    }

    private fun keyOf(account: String) = "folder-contents-" + account.replace(UNSAFE_KEY_CHARS, "_") + ".json"

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
    }
}
