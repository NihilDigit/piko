package dev.piko.shared.data

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * 解压用过的密码，按账号各存一份在机密存储里（[ArchivePasswordStore]），最近用过的在前；只给当前账号看、只记进当前账号。
 * 开着设置同步时经 ArchivePasswordSync 加密带到这个账号的其他设备。
 *
 * 每个密码记着最后一次用到或删掉的时刻，删掉的留一条墓碑：两台设备合并时逐个比时刻，
 * 一台上删掉的不会被另一台的旧记录带回来。
 *
 * 解压不拿它们逐个试：多个密码逐个提交，每次都是一个真实的解压请求。浏览压缩包会试，见 ArchiveBrowser。
 *
 * 进程里只有一个，挂在 PikoServices 上：读过的记录缓存在这里，设置页、解压与浏览改了彼此当场看得到。
 *
 * 1.1.0 全机只存一份、不分账号（那时只能登录一个账号）。头一个运行的账号（[start]）把它并进自己的那份，随即删掉旧的；
 * 升级时登录着的就是 1.1.0 里那个账号，密码正是在它下面输的。
 */
class ArchivePasswordVault(
    private val clients: PikoClientProvider,
    private val store: ArchivePasswordStore,
    private val legacy: PikoUserPreferences,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val loaded = MutableStateFlow<Map<String, Map<String, ArchivePasswordEntry>>>(emptyMap())
    private val lock = Mutex()
    private var legacyChecked = false

    /** 当前账号连同墓碑的全部记录，同步用；没登录时为空。读不出（钥匙串锁着）时先给空表，不缓存，下次再读。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val entries: Flow<Map<String, ArchivePasswordEntry>> = clients.currentClient
        .map { it?.account }
        .distinctUntilChanged()
        .flatMapLatest { account ->
            if (account == null) {
                flowOf(emptyMap())
            } else {
                flow {
                    runSuspendCatching { load(account) }.logFailure(TAG, "读取压缩包密码失败")
                    emitAll(loaded.map { it[account].orEmpty() })
                }
            }
        }
        .distinctUntilChanged()

    val passwords: Flow<List<String>> = entries.map { all ->
        all.filterValues { !it.removed }.entries.sortedByDescending { it.value.at }.map { it.key }
    }

    /** 登录后当场把 1.1.0 的旧记录交给头一个账号，不等到第一次用压缩包：那时可能已换了号。 */
    fun start(scope: CoroutineScope) {
        scope.launch {
            val account = clients.currentClient.filterNotNull().first().account
            runSuspendCatching { load(account) }.logFailure(TAG, "读取压缩包密码失败")
        }
    }

    // 存不下（钥匙串锁着、密钥库不可用）只记日志：密码这次已经用上了，少记一个不该让解压或浏览失败
    suspend fun remember(password: String) {
        if (password.isEmpty()) return
        updateCurrent { it + (password to ArchivePasswordEntry(stampAfter(it))) }
    }

    suspend fun forget(password: String) {
        updateCurrent { if (password in it) it + (password to ArchivePasswordEntry(stampAfter(it), removed = true)) else it }
    }

    // 同一毫秒里连着记两个时，后记的仍排在前面
    private fun stampAfter(entries: Map<String, ArchivePasswordEntry>): Long =
        maxOf(now(), (entries.values.maxOfOrNull { it.at } ?: 0L) + 1)

    /** [account] 的全部记录。读不出时抛出：同步不能拿空表当本机的记录。 */
    suspend fun current(account: String): Map<String, ArchivePasswordEntry> = load(account)

    /**
     * 把同步合并出的记录并进 [account]。按账号而不是当前账号：同步途中可能已换了号。
     * 再与眼前的记录合并一次，同步读出本机记录之后这里新记下的密码不被盖掉。
     */
    suspend fun merge(account: String, entries: Map<String, ArchivePasswordEntry>) = lock.withLock {
        val current = loadLocked(account)
        val next = mergeArchivePasswords(current, entries)
        if (next != current) write(account, next)
    }

    private suspend fun updateCurrent(change: (Map<String, ArchivePasswordEntry>) -> Map<String, ArchivePasswordEntry>) {
        val account = clients.currentClient.value?.account ?: return
        runSuspendCatching {
            lock.withLock {
                val current = loadLocked(account)
                val next = pruneArchivePasswords(change(current))
                if (next != current) write(account, next)
            }
        }.logFailure(TAG, "保存压缩包密码失败")
    }

    private suspend fun load(account: String): Map<String, ArchivePasswordEntry> = lock.withLock { loadLocked(account) }

    private suspend fun loadLocked(account: String): Map<String, ArchivePasswordEntry> {
        loaded.value[account]?.let { return it }
        val stored = decodeArchivePasswords(store.loadArchivePasswords(account))
        val entries = adoptLegacy(account, stored)
        loaded.update { it + (account to entries) }
        return entries
    }

    // 先写进账号的那份、再删旧的：中途失败时旧的还在，下次再并，合并是幂等的
    private suspend fun adoptLegacy(account: String, stored: Map<String, ArchivePasswordEntry>): Map<String, ArchivePasswordEntry> {
        if (legacyChecked) return stored
        val text = legacy.loadLegacyArchivePasswords()
        if (text.isBlank()) {
            legacyChecked = true
            return stored
        }
        val adopted = decodeArchivePasswords(text)
        val merged = mergeArchivePasswords(stored, adopted)
        store.saveArchivePasswords(account, encodeArchivePasswords(merged))
        legacy.clearLegacyArchivePasswords()
        legacyChecked = true
        PikoLog.i(TAG, "1.1.0 的 ${adopted.size} 个压缩包密码已并入当前账号")
        return merged
    }

    private suspend fun write(account: String, entries: Map<String, ArchivePasswordEntry>) {
        store.saveArchivePasswords(account, encodeArchivePasswords(entries))
        loaded.update { it + (account to entries) }
    }

    companion object {
        private const val TAG = "ArchivePasswords"

        /** 挑选时一屏看得完；再旧的多半是一次性的，挤出去也无妨。墓碑另留同样多条。 */
        const val MAX_PASSWORDS = 30
    }
}

/** [at] 是最后一次用到或删掉的时刻，毫秒。 */
@Serializable
data class ArchivePasswordEntry(val at: Long, val removed: Boolean = false)

/** 逐个密码取较新的一条；同一时刻删掉的赢，两边才会收敛到同一份。结果已裁到上限。 */
fun mergeArchivePasswords(
    a: Map<String, ArchivePasswordEntry>,
    b: Map<String, ArchivePasswordEntry>,
): Map<String, ArchivePasswordEntry> {
    val merged = (a.keys + b.keys).associateWith { key ->
        listOfNotNull(a[key], b[key]).maxWith(compareBy({ it.at }, { it.removed }))
    }
    return pruneArchivePasswords(merged)
}

// 墓碑也有上限：被裁掉的墓碑若在别的设备上还是一条更旧的有效记录，会被带回来。只在删了三十个以上时发生，可以接受
private fun pruneArchivePasswords(entries: Map<String, ArchivePasswordEntry>): Map<String, ArchivePasswordEntry> {
    val (removed, live) = entries.entries.partition { it.value.removed }
    fun List<Map.Entry<String, ArchivePasswordEntry>>.newest() =
        sortedByDescending { it.value.at }.take(ArchivePasswordVault.MAX_PASSWORDS)
    return (live.newest() + removed.newest()).associate { it.key to it.value }
}

internal fun encodeArchivePasswords(entries: Map<String, ArchivePasswordEntry>): String =
    json.encodeToString(entriesSerializer, entries)

/**
 * 1.1.0 存的是最近的在前的密码列表，读成按先后递减的极小时刻：保住原来的顺序，又比之后任何一次真实的记录都旧。
 * 内容损坏时当作空表，不让一份坏数据挡住解压。
 */
internal fun decodeArchivePasswords(serialized: String): Map<String, ArchivePasswordEntry> {
    if (serialized.isBlank()) return emptyMap()
    runCatching { return json.decodeFromString(entriesSerializer, serialized) }
    val legacy = runCatching { json.decodeFromString(legacySerializer, serialized) }.getOrDefault(emptyList())
    return legacy.withIndex().associate { (index, password) -> password to ArchivePasswordEntry((legacy.size - index).toLong()) }
}

private val json = Json { ignoreUnknownKeys = true }
private val entriesSerializer = MapSerializer(String.serializer(), ArchivePasswordEntry.serializer())
private val legacySerializer = ListSerializer(String.serializer())
