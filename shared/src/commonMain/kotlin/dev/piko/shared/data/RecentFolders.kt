package dev.piko.shared.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 最近去过的文件夹，新的在前，至多 [LIMIT] 个，供命令面板列出。每项存整条路径，点回去不必再查上级。
 * 与浏览历史不同：历史按步记、有前进后退两头，这里按文件夹去重，只看最近到过哪些。根目录不记。
 * 按账号存进缓存目录，换号时换一份。
 */
internal class RecentFolders(private val store: PikoCacheStore?, private val scope: CoroutineScope) {
    private val folders = MutableStateFlow<List<List<PikoPathBreadcrumb>>>(emptyList())
    val flow: StateFlow<List<List<PikoPathBreadcrumb>>> = folders.asStateFlow()

    private var account: String? = null
    private var pendingSave: Job? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ListSerializer(SavedCrumb.serializer()))

    fun switchAccount(newAccount: String?) {
        if (newAccount == account) return
        pendingSave?.cancel()
        // 账号是异步报上来的，启动时恢复位置可能早于它。那时记下的算这个账号的，不清掉
        val previous = account
        account = newAccount
        if (previous != null) folders.value = emptyList()
        val cacheStore = store ?: return
        if (newAccount == null) return
        scope.launch {
            val stored = runCatching { cacheStore.read(keyOf(newAccount))?.let { json.decodeFromString(serializer, it) } }.getOrNull()
            if (account != newAccount) return@launch
            val loaded = stored.orEmpty().map { stack -> stack.map { PikoPathBreadcrumb(it.id, it.name) } }
            // 载入期间本会话到过的排在前面
            folders.update { current -> merge(current, loaded) }
            save()
        }
    }

    fun visited(stack: List<PikoPathBreadcrumb>) {
        if (stack.size <= 1) return
        folders.update { merge(listOf(stack), it) }
        save()
    }

    /** 文件夹被删、被移走或改了名，从记录里拿掉，免得点过去扑空。 */
    fun forget(folderId: String) {
        folders.update { list -> list.filterNot { stack -> stack.any { it.id == folderId } } }
        save()
    }

    private fun merge(first: List<List<PikoPathBreadcrumb>>, then: List<List<PikoPathBreadcrumb>>) =
        (first + then).distinctBy { it.last().id }.take(LIMIT)

    private fun save() {
        val cacheStore = store ?: return
        val owner = account ?: return
        val snapshot = folders.value.map { stack -> stack.map { SavedCrumb(it.id, it.name) } }
        pendingSave?.cancel()
        pendingSave = scope.launch { runCatching { cacheStore.write(keyOf(owner), json.encodeToString(serializer, snapshot)) } }
    }

    private fun keyOf(account: String) = "recent-folders-" + account.replace(UNSAFE_KEY_CHARS, "_") + ".json"

    @Serializable
    private class SavedCrumb(val id: String, val name: String)

    companion object {
        const val LIMIT = 8
        private val UNSAFE_KEY_CHARS = Regex("""[^A-Za-z0-9._@-]""")
    }
}
