package dev.piko.shared.data

import dev.piko.data.auth.PikoUserPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 按账号存的偏好。快速访问与最近移动目标记的是文件夹 ID，只在一个账号里有意义；平台的偏好全机一份，
 * 照旧存的话换号后会列出另一个账号的文件夹，设置同步还会把它们推进这个账号的网盘。这两项改存缓存目录、
 * 按账号分，其余照旧交给平台实现。
 *
 * 有多账号之前这两项存在平台偏好里，那时只有一个账号：升级后第一个读到它们的账号接过去，另记一笔「已接走」，
 * 之后的账号不再接。平台那份不清空：旧版本（桌面便携版与安装版可以先后开）仍读它，清空的话旧版本的设置同步
 * 会把「本机改成了空」推进网盘，新版本随即拉回这个空值，快速访问就没了。
 */
class AccountScopedPreferences(
    private val delegate: PikoUserPreferences,
    private val clients: PikoClientProvider,
    private val cacheStore: PikoCacheStore,
) : PikoUserPreferences by delegate {
    private val pinned = Scoped("pinned-folders") { delegate.pinnedFoldersFlow }
    private val moveTargets = Scoped("recent-move-targets") { delegate.recentMoveTargetsFlow }

    override val pinnedFoldersFlow: Flow<String> = pinned.flow
    override suspend fun savePinnedFolders(serialized: String) = pinned.save(serialized)

    override val recentMoveTargetsFlow: Flow<String> = moveTargets.flow
    override suspend fun saveRecentMoveTargets(serialized: String) = moveTargets.save(serialized)

    private inner class Scoped(
        private val name: String,
        private val legacy: () -> Flow<String>,
    ) {
        private val values = MutableStateFlow<Map<String, String>>(emptyMap())
        private val lock = Mutex()

        // 按读的那一刻的账号取，不是订阅那一刻的：设置同步在换号后立刻读，读到的必须已是新账号的
        @OptIn(ExperimentalCoroutinesApi::class)
        val flow: Flow<String> = clients.currentClient
            .map { it?.account }
            .distinctUntilChanged()
            .flatMapLatest { account ->
                if (account == null) {
                    flowOf("")
                } else {
                    flow {
                        load(account)
                        emitAll(values.map { it[account].orEmpty() })
                    }
                }
            }
            .distinctUntilChanged()

        suspend fun save(value: String) {
            val account = clients.currentClient.value?.account ?: return
            lock.withLock {
                values.update { it + (account to value) }
                cacheStore.write(key(account), value)
            }
        }

        private suspend fun load(account: String) = lock.withLock {
            if (account in values.value) return@withLock
            val stored = cacheStore.read(key(account)) ?: adoptLegacy(account)
            values.update { it + (account to stored) }
        }

        // 只有头一个来的账号接旧值；标记与值都写下之后才算接走，中途失败下次再接
        private suspend fun adoptLegacy(account: String): String {
            val claimKey = "prefs-$name-legacy-claimed.txt"
            if (cacheStore.read(claimKey) != null) return ""
            val adopted = legacy().first()
            cacheStore.write(key(account), adopted)
            cacheStore.write(claimKey, account)
            return adopted
        }

        private fun key(account: String) ="prefs-$name-" + account.replace(UNSAFE_KEY_CHARS, "_") + ".json"
    }

    private companion object {
        val UNSAFE_KEY_CHARS = Regex("""[^A-Za-z0-9._@-]""")
    }
}
