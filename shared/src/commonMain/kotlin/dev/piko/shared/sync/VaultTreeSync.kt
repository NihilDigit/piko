package dev.piko.shared.sync

import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.VaultTrees
import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.log.PikoLog
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 把整棵归档过的文件夹（[VaultTrees]）存进网盘 `.piko` 下的 `vault-trees-<时间戳>.json`，换一台设备，外层文件夹上的
 * 归档标记照样在。单独一个文件，不放进设置同步那一份：它随归档次数一直变长，夹在几项开关中间读不清。
 *
 * 合并取并集，不比较时刻：这张表只增不减，两台设备各归档了一次，两条都要留。跟着设置同步的开关（[enabled]）：
 * 关掉同步的人不想让 Piko 往网盘里写东西。
 */
class VaultTreeSync(
    private val clients: PikoClientProvider,
    private val remote: RemoteSettingsStore,
    private val trees: VaultTrees,
    private val scope: CoroutineScope,
    private val enabled: Flow<Boolean>,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    constructor(
        clients: PikoClientProvider,
        driveRepo: PikoDriveRepository,
        scope: CoroutineScope,
        enabled: Flow<Boolean>,
    ) : this(clients, DriveSettingsStore(clients, driveRepo, FILE_PREFIX, TAG), driveRepo.vaultTrees, scope, enabled)

    private val lock = Mutex()

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            combine(clients.currentClient, enabled) { client, on -> client?.account.takeIf { on } }
                .distinctUntilChanged()
                .collectLatest { account ->
                    if (account == null) return@collectLatest
                    val before = trees.flow.value
                    var handled = syncSnapshot(account).getOrElse { before }
                    trees.flow.debounce(PUSH_DELAY_MS).collect { records ->
                        if (records != handled) handled = syncSnapshot(account).getOrElse { records }
                    }
                }
        }
    }

    suspend fun syncNow(): Boolean = syncSnapshot().isSuccess

    private suspend fun syncSnapshot(expectedAccount: String? = null): Result<Map<String, Set<String>>> = lock.withLock {
        val account = clients.currentClient.value?.account
            ?: return@withLock Result.failure(IllegalStateException("No active account"))
        if (expectedAccount != null && account != expectedAccount) {
            PikoLog.d(TAG, "Skipped sync: active account changed")
            return@withLock Result.failure(SyncAccountSwitchedException())
        }
        runSuspendCatching { sync(account) }.onFailure { err ->
            if (err is SyncAccountSwitchedException) {
                PikoLog.d(TAG, "Skipped sync: active account changed")
            } else {
                PikoLog.w(TAG, "Vault tree sync failed", err)
            }
        }
    }

    private fun checkAccount(account: String) {
        if (clients.currentClient.value?.account != account) throw SyncAccountSwitchedException()
    }

    private suspend fun sync(account: String): Map<String, Set<String>> {
        checkAccount(account)
        val remoteText = remote.read(account)
        checkAccount(account)
        val localBefore = trees.flow.value
        val remoteTrees = if (remoteText != null) {
            VaultTrees.decode(remoteText) ?: run {
                PikoLog.w(TAG, "Remote vault tree file is malformed or uses an unsupported version; skipping sync to preserve remote file")
                return localBefore
            }
        } else {
            emptyMap()
        }
        val before = localBefore.size
        checkAccount(account)
        trees.merge(remoteTrees)
        checkAccount(account)
        val merged = trees.flow.value
        val pushed = merged != remoteTrees
        if (pushed) {
            checkAccount(account)
            remote.write(account, VaultTrees.encode(merged), now())
            checkAccount(account)
        }
        PikoLog.d(
            TAG,
            "Vault trees synced: local=$before, remote=${if (remoteText == null) "none" else remoteTrees.size.toString()}, " +
                "merged=${merged.size}, pushed=$pushed",
        )
        return merged
    }

    private companion object {
        const val FILE_PREFIX = "vault-trees-"
        const val PUSH_DELAY_MS = 3_000L
        const val TAG = "VaultTreeSync"
    }
}
