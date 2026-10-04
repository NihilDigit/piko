package dev.piko.shared.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

/**
 * 整棵归档过的文件夹：归档时选的那一层与它往下的各层 → 其下写了清单的文件夹。文件夹上的归档标记据此往外层推，
 * 见 [marked]。外层文件夹里往往只有子文件夹、没有清单，只按「直接放着条目」算的话，选了归档的那一层反而看不出归档过。
 *
 * 本机按账号存在缓存目录里，跨设备经 `VaultTreeSync` 存进网盘 `.piko` 下单独的一个文件。清单只记在放着条目的那一层，
 * 别的设备列外层看不出它归档过，这张表是唯一的来源。只增不减，两边合并取并集：多出来的旧记录不会挂错标记，
 * 标记还要看里面那些文件夹此刻有没有条目。
 */
class VaultTrees internal constructor(
    private val store: PikoCacheStore?,
    private val scope: CoroutineScope,
    private val saveDelayMillis: Long = 2_000L,
) {
    private val trees = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val flow: StateFlow<Map<String, Set<String>>> = trees.asStateFlow()

    @Volatile
    private var account: String? = null
    private var pendingSave: Job? = null

    /** 记下一次归档或合并进别处的：键是选的那一层与中间各层，值是其下写了清单的文件夹。有新内容时返回 true。 */
    fun merge(members: Map<String, Set<String>>): Boolean {
        var changed = false
        trees.update { current ->
            union(current, members).also { changed = it != current }
        }
        if (changed) scheduleSave()
        return changed
    }

    fun switchAccount(newAccount: String?) {
        if (newAccount == account) return
        pendingSave?.cancel()
        account = newAccount
        trees.value = emptyMap()
        val cacheStore = store ?: return
        if (newAccount == null) return
        scope.launch {
            val stored = cacheStore.read(keyOf(newAccount))?.let(::decode) ?: return@launch
            if (account != newAccount) return@launch
            trees.update { union(stored, it) }
        }
    }

    private fun scheduleSave() {
        val cacheStore = store ?: return
        val owner = account ?: return
        if (pendingSave?.isActive == true) return
        pendingSave = scope.launch {
            delay(saveDelayMillis)
            cacheStore.write(keyOf(owner), encode(trees.value))
        }
    }

    private fun keyOf(account: String) = "vault-trees-" + account.replace(UNSAFE_KEY_CHARS, "_") + ".json"

    companion object {
        private val UNSAFE_KEY_CHARS = Regex("""[^A-Za-z0-9._@-]""")
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

        fun encode(trees: Map<String, Set<String>>): String = json.encodeToString(serializer, trees.mapValues { it.value.sorted() })

        fun decode(text: String): Map<String, Set<String>>? =
            runCatching { json.decodeFromString(serializer, text).mapValues { it.value.toSet() } }.getOrNull()

        fun union(a: Map<String, Set<String>>, b: Map<String, Set<String>>): Map<String, Set<String>> =
            (a.keys + b.keys).associateWith { a[it].orEmpty() + b[it].orEmpty() }

        /**
         * 挂归档标记的文件夹：直接放着条目的（[direct]），加上表里其下还有放着条目的那些外层。里面的文件夹在这台设备上
         * 没列过（不在 [listed] 里）时当作还有：别的设备归档的，这里还没进去看过。不往上推到根目录：「电影」这类大目录
         * 下面迟早有归档过的，全挂上就没有意义了。
         */
        fun marked(direct: Set<String>, listed: Set<String>, trees: Map<String, Set<String>>): Set<String> =
            direct + trees.filterValues { members -> members.any { it in direct || it !in listed } }.keys
    }
}
