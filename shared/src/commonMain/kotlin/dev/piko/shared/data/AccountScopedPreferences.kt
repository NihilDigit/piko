package dev.piko.shared.data

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.sync.PortableDigest
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 按账号隔离的偏好代理：
 * - 快速访问（`pinned-folders`）、最近移动目标（`recent-move-targets`）与按目录记忆的排序方式（`folder-sort-orders`）
 *   仅在所属账号下有意义，按账号存放在缓存目录；
 * - MetaTube 服务地址与机密访问令牌同样按账号隔离，但实际数据严格保存在底层平台偏好与平台加密保管处
 *   （`SessionManager` / `StoredPikoPreferences`），绝不将机密令牌写入明文缓存目录。
 *
 * 旧版全机共用一份配置时，升级后最多认领一次遗留值（若本地同步账本摘要匹配某账号则精确归入该账号，否则由首个活动账号认领）；
 * 平台旧槽位保留不清空，避免便携版与安装版混用时旧客户端将空值推回云端。
 */
class AccountScopedPreferences(
    private val delegate: PikoUserPreferences,
    private val clients: PikoClientProvider,
    private val cacheStore: PikoCacheStore,
    private val knownAccounts: () -> Collection<String>,
) : PikoUserPreferences by delegate {
    constructor(
        delegate: PikoUserPreferences,
        clients: PikoClientProvider,
        cacheStore: PikoCacheStore,
    ) : this(
        delegate = delegate,
        clients = clients,
        cacheStore = cacheStore,
        knownAccounts = { (clients as? PikoClientManager)?.accounts?.value?.accounts?.map { it.account }.orEmpty() },
    )

    private val pinned = Scoped("pinned-folders") { delegate.pinnedFoldersFlow }
    private val moveTargets = Scoped("recent-move-targets") { delegate.recentMoveTargetsFlow }
    private val folderSortOrders = Scoped("folder-sort-orders") { delegate.folderSortOrdersFlow }

    private val metaTubeUrlMigrated = HashSet<String>()
    private val metaTubeUrlLock = Mutex()
    private val metaTubeTokenMigrated = HashSet<String>()
    private val metaTubeTokenLock = Mutex()

    internal fun currentAccount(): String? = clients.currentClient.value?.account

    override val pinnedFoldersFlow: Flow<String> = pinned.flow
    override suspend fun savePinnedFolders(serialized: String) = pinned.save(serialized)
    override fun pinnedFoldersFlowFor(account: String): Flow<String> = pinned.flowFor(account)
    override suspend fun savePinnedFoldersFor(account: String, serialized: String) = pinned.saveFor(account, serialized)

    override val recentMoveTargetsFlow: Flow<String> = moveTargets.flow
    override suspend fun saveRecentMoveTargets(serialized: String) = moveTargets.save(serialized)
    override fun recentMoveTargetsFlowFor(account: String): Flow<String> = moveTargets.flowFor(account)
    override suspend fun saveRecentMoveTargetsFor(account: String, serialized: String) = moveTargets.saveFor(account, serialized)

    override val folderSortOrdersFlow: Flow<String> = folderSortOrders.flow
    override suspend fun saveFolderSortOrders(serialized: String) = folderSortOrders.save(serialized)
    override fun folderSortOrdersFlowFor(account: String): Flow<String> = folderSortOrders.flowFor(account)
    override suspend fun saveFolderSortOrdersFor(account: String, serialized: String) = folderSortOrders.saveFor(account, serialized)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val metaTubeUrlFlow: Flow<String> = clients.currentClient
        .map { it?.account }
        .distinctUntilChanged()
        .flatMapLatest { account -> if (account == null) flowOf("") else metaTubeUrlFlowFor(account) }
        .distinctUntilChanged()

    override suspend fun setMetaTubeUrl(url: String) {
        val account = clients.currentClient.value?.account ?: return
        setMetaTubeUrlFor(account, url)
    }

    override fun metaTubeUrlFlowFor(account: String): Flow<String> =
        if (account.isBlank()) {
            flowOf("")
        } else {
            flow {
                ensureMetaTubeUrlMigrated(account)
                emitAll(delegate.metaTubeUrlFlowFor(account))
            }.distinctUntilChanged()
        }

    override suspend fun setMetaTubeUrlFor(account: String, url: String) {
        if (account.isBlank()) return
        ensureMetaTubeUrlMigrated(account)
        delegate.setMetaTubeUrlFor(account, url)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val metaTubeTokenFlow: Flow<String> = clients.currentClient
        .map { it?.account }
        .distinctUntilChanged()
        .flatMapLatest { account -> if (account == null) flowOf("") else metaTubeTokenFlowFor(account) }
        .distinctUntilChanged()

    override suspend fun setMetaTubeToken(token: String) {
        val account = clients.currentClient.value?.account ?: return
        setMetaTubeTokenFor(account, token)
    }

    override fun metaTubeTokenFlowFor(account: String): Flow<String> =
        if (account.isBlank()) {
            flowOf("")
        } else {
            flow {
                ensureMetaTubeTokenMigrated(account)
                emitAll(delegate.metaTubeTokenFlowFor(account))
            }.distinctUntilChanged()
        }

    override suspend fun setMetaTubeTokenFor(account: String, token: String) {
        if (account.isBlank()) return
        ensureMetaTubeTokenMigrated(account)
        delegate.setMetaTubeTokenFor(account, token)
    }

    private suspend fun ensureMetaTubeUrlMigrated(account: String) {
        if (account.isBlank()) return
        metaTubeUrlLock.withLock {
            if (account in metaTubeUrlMigrated) return@withLock
            if (delegate.metaTubeUrlFlowFor(account).first().isNotEmpty()) {
                metaTubeUrlMigrated += account
                return@withLock
            }
            if (cacheStore.read(METATUBE_URL_CLAIM_KEY) != null) {
                metaTubeUrlMigrated += account
                return@withLock
            }
            val legacyUrl = delegate.metaTubeUrlFlow.first()
            if (legacyUrl.isEmpty()) {
                metaTubeUrlMigrated += account
                return@withLock
            }
            val legacySha = sha256Hex(legacyUrl)
            val owner = resolveLegacyMetaTubeOwner(account, legacySha) { it.url?.sha256 }
            if (owner != null) {
                delegate.setMetaTubeUrlFor(owner, legacyUrl)
                cacheStore.write(METATUBE_URL_CLAIM_KEY, owner)
                metaTubeUrlMigrated += owner
            }
            metaTubeUrlMigrated += account
        }
    }

    private suspend fun ensureMetaTubeTokenMigrated(account: String) {
        if (account.isBlank()) return
        metaTubeTokenLock.withLock {
            if (account in metaTubeTokenMigrated) return@withLock
            if (delegate.metaTubeTokenFlowFor(account).first().isNotEmpty()) {
                metaTubeTokenMigrated += account
                return@withLock
            }
            if (cacheStore.read(METATUBE_TOKEN_CLAIM_KEY) != null) {
                metaTubeTokenMigrated += account
                return@withLock
            }
            val legacyToken = delegate.metaTubeTokenFlow.first()
            if (legacyToken.isEmpty()) {
                metaTubeTokenMigrated += account
                return@withLock
            }
            val legacySha = sha256Hex(legacyToken)
            val owner = resolveLegacyMetaTubeOwner(account, legacySha) { it.token?.sha256 }
            if (owner != null) {
                delegate.setMetaTubeTokenFor(owner, legacyToken)
                cacheStore.write(METATUBE_TOKEN_CLAIM_KEY, owner)
                metaTubeTokenMigrated += owner
            }
            metaTubeTokenMigrated += account
        }
    }

    private suspend fun resolveLegacyMetaTubeOwner(
        currentAccount: String,
        legacySha: String,
        selector: (MetaTubeLedgerPreview) -> String?,
    ): String? {
        val candidates = LinkedHashSet<String>().apply {
            add(currentAccount)
            addAll(knownAccounts())
        }.filter { it.isNotBlank() }
        val shaByAccount = LinkedHashMap<String, String>()
        for (cand in candidates) {
            val raw = cacheStore.read(metaTubeSyncLedgerKey(cand)) ?: continue
            val parsed = runCatching { ledgerJson.decodeFromString(MetaTubeLedgerPreview.serializer(), raw) }.getOrNull() ?: continue
            val sha = selector(parsed)?.takeIf { it.isNotEmpty() } ?: continue
            shaByAccount[cand] = sha
        }
        if (shaByAccount[currentAccount]?.equals(legacySha, ignoreCase = true) == true) {
            return currentAccount
        }
        shaByAccount.entries.firstOrNull { it.value.equals(legacySha, ignoreCase = true) }?.let { return it.key }
        if (currentAccount in shaByAccount) {
            return null
        }
        return currentAccount
    }

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
            .flatMapLatest { account -> if (account == null) flowOf("") else flowFor(account) }
            .distinctUntilChanged()

        fun flowFor(account: String): Flow<String> =
            if (account.isBlank()) {
                flowOf("")
            } else {
                flow {
                    load(account)
                    emitAll(values.map { it[account].orEmpty() })
                }.distinctUntilChanged()
            }

        suspend fun save(value: String) {
            val account = clients.currentClient.value?.account ?: return
            saveFor(account, value)
        }

        suspend fun saveFor(account: String, value: String) {
            if (account.isBlank()) return
            lock.withLock {
                if (account !in values.value && cacheStore.read(key(account)) == null) {
                    adoptLegacy(account)
                }
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

        private fun key(account: String) = "prefs-$name-" + account.replace(UNSAFE_KEY_CHARS, "_") + ".json"
    }

    @Serializable
    private data class MetaTubeLedgerField(val sha256: String = "")

    @Serializable
    private data class MetaTubeLedgerPreview(
        val url: MetaTubeLedgerField? = null,
        val token: MetaTubeLedgerField? = null,
    )

    private companion object {
        const val METATUBE_URL_CLAIM_KEY = "prefs-metatube-url-legacy-claimed.txt"
        const val METATUBE_TOKEN_CLAIM_KEY = "prefs-metatube-token-legacy-claimed.txt"
        val UNSAFE_KEY_CHARS = Regex("""[^A-Za-z0-9._@-]""")
        val ledgerJson = Json { ignoreUnknownKeys = true }

        fun metaTubeSyncLedgerKey(account: String) = "metatube-sync-${account.replace(UNSAFE_KEY_CHARS, "_")}.json"
        fun sha256Hex(value: String): String = PortableDigest.sha256(value.encodeToByteArray()).toHexString()
    }
}
