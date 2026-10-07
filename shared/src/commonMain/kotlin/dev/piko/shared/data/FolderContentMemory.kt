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
        vaulted.update { if (has) it + folderId else it - folderId }
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
        covers.update { it + (folderId to gcid) }
        scheduleSave()
    }

    /** 列过、记下了内容的文件夹。归档树里的内层文件夹没列过时，外层按「还有条目」算，见 VaultTrees.marked。 */
    val listedFolders: Flow<Set<String>> = contents.map { it.keys }

    /** 换账号或退出登录。退出时只清内存，磁盘上的留给下次登录同一账号。 */
    fun switchAccount(newAccount: String?) {
        if (newAccount == account) return
        pendingSave?.cancel()
        account = newAccount
        contents.value = emptyMap()
        vaulted.value = emptySet()
        covers.value = emptyMap()
        val cacheStore = store ?: return
        if (newAccount == null) return
        scope.launch {
            val stored = cacheStore.read(keyOf(newAccount))?.let { text -> runCatching { json.decodeFromString(serializer, text) }.getOrNull() }
            val storedVaulted = cacheStore.read(vaultKeyOf(newAccount))?.let { text -> runCatching { json.decodeFromString(vaultSerializer, text) }.getOrNull() }
            val storedCovers = cacheStore.read(coverKeyOf(newAccount))?.let { text -> runCatching { json.decodeFromString(coverSerializer, text) }.getOrNull() }
            if (account != newAccount) return@launch
            // 载入期间本会话记下的更新，留着它们。本会话里去掉的标记会被磁盘上的旧值加回来，下次列到那个目录再去掉
            if (storedVaulted != null) vaulted.update { current -> current + storedVaulted }
            if (storedCovers != null) covers.update { current -> storedCovers + current }
            if (stored == null) return@launch
            val loaded = stored.associate { folder -> folder.id to folder.files.map { ChildFile(it.name, it.category) } }
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
            cacheStore.write(vaultKeyOf(owner), json.encodeToString(vaultSerializer, vaulted.value.toList()))
            cacheStore.write(coverKeyOf(owner), json.encodeToString(coverSerializer, covers.value))
        }
    }

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
