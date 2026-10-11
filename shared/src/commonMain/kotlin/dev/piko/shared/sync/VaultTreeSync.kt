package dev.piko.shared.sync

import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.VaultTrees
import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
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
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException

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
    ) : this(clients, DriveSettingsStore(clients, driveRepo, FILE_PREFIX), driveRepo.vaultTrees, scope, enabled)

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
        val account = clients.currentClient.value?.account ?: return@withLock Result.failure(IllegalStateException("未登录"))
        if (expectedAccount != null && account != expectedAccount) throw CancellationException("归档树账号已改变")
        runSuspendCatching { sync(account) }
            .logFailure(TAG, "归档树同步失败")
    }

    private suspend fun sync(account: String): Map<String, Set<String>> {
        val remoteText = remote.read(account)
        if (clients.currentClient.value?.account != account) throw CancellationException("归档树账号已改变")
        val remoteTrees = remoteText?.let(VaultTrees::decode).orEmpty()
        val before = trees.flow.value.size
        trees.merge(remoteTrees)
        val merged = trees.flow.value
        val pushed = merged != remoteTrees
        if (pushed) remote.write(account, VaultTrees.encode(merged), now())
        PikoLog.d(TAG, "归档树同步：本机 $before 个，远端${if (remoteText == null) "无文件" else " ${remoteTrees.size} 个"}，" +
            "合并后 ${merged.size} 个，${if (pushed) "已推送" else "无需推送"}")
        return merged
    }

    private companion object {
        const val FILE_PREFIX = "vault-trees-"
        const val PUSH_DELAY_MS = 3_000L
        const val TAG = "VaultTreeSync"
    }
}
