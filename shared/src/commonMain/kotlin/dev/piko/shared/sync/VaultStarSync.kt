package dev.piko.shared.sync

import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.VaultStarRecord
import dev.piko.shared.data.VaultStars
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
    ) : this(clients, DriveSettingsStore(clients, driveRepo, FILE_PREFIX, TAG), driveRepo.vaultStars, scope, enabled)

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
                PikoLog.w(TAG, "Vault stars sync failed", err)
            }
        }
    }

    private fun checkAccount(account: String) {
        if (clients.currentClient.value?.account != account) throw SyncAccountSwitchedException()
    }

    private suspend fun sync(account: String): Map<String, VaultStarRecord> {
        checkAccount(account)
        val localBefore = stars.awaitAccountReady(account) ?: throw SyncAccountSwitchedException()
        val remoteText = remote.read(account)
        checkAccount(account)
        val remoteRecords = if (remoteText != null) {
            VaultStars.decode(remoteText) ?: run {
                PikoLog.w(TAG, "Remote vault stars file is malformed or uses an unsupported version; skipping sync to preserve remote file")
                return localBefore
            }
        } else {
            emptyMap()
        }
        val beforeActive = localBefore.values.count { it.starred }
        val merged = stars.mergeAndSnapshot(remoteRecords, now(), expectedAccount = account)
            ?: throw SyncAccountSwitchedException()
        val activeAfter = merged.values.count { it.starred }
        val pushed = merged != remoteRecords
        if (pushed) {
            checkAccount(account)
            remote.write(account, VaultStars.encode(merged), now())
            checkAccount(account)
        }
        PikoLog.d(
            TAG,
            "Vault stars synced: local=$beforeActive, remote=${if (remoteText == null) "none" else remoteRecords.values.count { it.starred }.toString()}, " +
                "merged=$activeAfter, pushed=$pushed",
        )
        return merged
    }

    private companion object {
        const val FILE_PREFIX = "vault-stars-"
        const val PUSH_DELAY_MS = 3_000L
        const val TAG = "VaultStarSync"
    }
}
