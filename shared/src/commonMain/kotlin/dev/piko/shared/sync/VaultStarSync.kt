package dev.piko.shared.sync

import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.VaultStarRecord
import dev.piko.shared.data.VaultStars
import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import kotlinx.coroutines.CancellationException
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

/**
 * 把 Piko 自管的归档星标账本（[VaultStars]）同步至网盘 `.piko/vault-stars-<时间戳>.json`。
 * 按 `entryId` 与 `updatedAt` 执行 LWW 合并（带取消星标墓碑），严格跟随设置同步总开关 [enabled]。
 */
class VaultStarSync(
    private val clients: PikoClientProvider,
    private val remote: RemoteSettingsStore,
    private val stars: VaultStars,
    private val scope: CoroutineScope,
    private val enabled: Flow<Boolean>,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    constructor(
        clients: PikoClientProvider,
        driveRepo: PikoDriveRepository,
        scope: CoroutineScope,
        enabled: Flow<Boolean>,
    ) : this(clients, DriveSettingsStore(clients, driveRepo, FILE_PREFIX), driveRepo.vaultStars, scope, enabled)

    private val lock = Mutex()

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            combine(clients.currentClient, enabled) { client, on -> client?.account.takeIf { on } }
                .distinctUntilChanged()
                .collectLatest { account ->
                    if (account == null) return@collectLatest
                    val before = stars.flow.value
                    var handled = syncSnapshot(account).getOrElse { before }
                    stars.flow.debounce(PUSH_DELAY_MS).collect { records ->
                        if (records != handled) handled = syncSnapshot(account).getOrElse { records }
                    }
                }
        }
    }

    suspend fun syncNow(): Boolean = syncSnapshot().isSuccess

    private suspend fun syncSnapshot(expectedAccount: String? = null): Result<Map<String, VaultStarRecord>> = lock.withLock {
        val account = clients.currentClient.value?.account ?: return@withLock Result.failure(IllegalStateException("未登录"))
        if (expectedAccount != null && account != expectedAccount) throw CancellationException("归档星标账号已改变")
        runSuspendCatching { sync(account) }
            .logFailure(TAG, "归档星标同步失败")
    }

    private suspend fun sync(account: String): Map<String, VaultStarRecord> {
        val localBefore = stars.awaitAccountReady(account) ?: throw CancellationException("归档星标账号已改变")
        val remoteText = remote.read(account)
        if (clients.currentClient.value?.account != account) throw CancellationException("归档星标账号已改变")
        val remoteRecords = remoteText?.let(VaultStars::decode).orEmpty()
        val beforeActive = localBefore.values.count { it.starred }
        val merged = stars.mergeAndSnapshot(remoteRecords, now(), expectedAccount = account)
            ?: throw CancellationException("归档星标账号已改变")
        val activeAfter = merged.values.count { it.starred }
        val pushed = merged != remoteRecords
        if (pushed) {
            if (clients.currentClient.value?.account != account) throw CancellationException("归档星标账号已改变")
            remote.write(account, VaultStars.encode(merged), now())
        }
        PikoLog.d(
            TAG,
            "归档星标同步：本机 $beforeActive 条，远端${if (remoteText == null) "无文件" else " ${remoteRecords.values.count { it.starred }} 条"}，" +
                "合并后 $activeAfter 条，${if (pushed) "已推送" else "无需推送"}",
        )
        return merged
    }

    private companion object {
        const val FILE_PREFIX = "vault-stars-"
        const val PUSH_DELAY_MS = 3_000L
        const val TAG = "VaultStarSync"
    }
}
