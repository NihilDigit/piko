package dev.piko.shared.sync

import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.SourceLedger
import dev.piko.shared.data.SourceRecord
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
 * 把秒传文件的来源账本（[SourceLedger]）存进网盘 `.piko` 下的 `sources-<时间戳>.json`，换一台设备，
 * 那边秒传的文件在这里同样有来源。与 VaultTreeSync 同一做法：单独一个文件，合并见 [SourceLedger]，跟着设置同步的开关。
 *
 * 不加密：归档本就把完整磁力与分享链接以明文存进网盘（`归档来源-*.magnet`）。分享的提取码不进账本：
 * 分享转存的文件服务端自己填 url，不经这里，而那个 url 本就不带提取码。
 */
class SourceLedgerSync(
    private val clients: PikoClientProvider,
    private val remote: RemoteSettingsStore,
    private val ledger: SourceLedger,
    private val scope: CoroutineScope,
    private val enabled: Flow<Boolean>,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    constructor(
        clients: PikoClientProvider,
        driveRepo: PikoDriveRepository,
        scope: CoroutineScope,
        enabled: Flow<Boolean>,
    ) : this(clients, DriveSettingsStore(clients, driveRepo, FILE_PREFIX, TAG), driveRepo.sourceLedger, scope, enabled)

    private val lock = Mutex()

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            combine(clients.currentClient, enabled) { client, on -> client?.account.takeIf { on } }
                .distinctUntilChanged()
                .collectLatest { account ->
                    if (account == null) return@collectLatest
                    val before = ledger.flow.value
                    var handled = syncSnapshot(account).getOrElse { before }
                    // 一批秒传连着记好几次，等停下来再推
                    ledger.flow.debounce(PUSH_DELAY_MS).collect { records ->
                        if (records != handled) handled = syncSnapshot(account).getOrElse { records }
                    }
                }
        }
    }

    // 日志只记条数：来源是磁力与分享链接，导出的日志要发给别人
    suspend fun syncNow(): Boolean = syncSnapshot().isSuccess

    private suspend fun syncSnapshot(expectedAccount: String? = null): Result<Map<String, SourceRecord>> = lock.withLock {
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
                PikoLog.w(TAG, "Source ledger sync failed", err)
            }
        }
    }

    private fun checkAccount(account: String) {
        if (clients.currentClient.value?.account != account) throw SyncAccountSwitchedException()
    }

    private suspend fun sync(account: String): Map<String, SourceRecord> {
        checkAccount(account)
        val remoteText = remote.read(account)
        checkAccount(account)
        val localBefore = ledger.flow.value
        val remoteRecords = if (remoteText != null) {
            SourceLedger.decode(remoteText) ?: run {
                PikoLog.w(TAG, "Remote source ledger is malformed or uses an unsupported version; skipping sync to preserve remote file")
                return localBefore
            }
        } else {
            emptyMap()
        }
        val before = localBefore.size
        checkAccount(account)
        ledger.merge(remoteRecords)
        checkAccount(account)
        val merged = ledger.flow.value
        val pushed = merged != remoteRecords
        if (pushed) {
            checkAccount(account)
            remote.write(account, SourceLedger.encode(merged), now())
            checkAccount(account)
        }
        PikoLog.d(
            TAG,
            "Source ledger synced: local=$before, remote=${if (remoteText == null) "none" else remoteRecords.size.toString()}, " +
                "merged=${merged.size}, pushed=$pushed",
        )
        return merged
    }

    private companion object {
        const val FILE_PREFIX = "sources-"
        const val PUSH_DELAY_MS = 3_000L
        const val TAG = "SourceLedgerSync"
    }
}
