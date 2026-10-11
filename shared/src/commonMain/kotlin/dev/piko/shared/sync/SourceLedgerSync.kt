package dev.piko.shared.sync

import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.SourceLedger
import dev.piko.shared.data.SourceRecord
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
    ) : this(clients, DriveSettingsStore(clients, driveRepo, FILE_PREFIX), driveRepo.sourceLedger, scope, enabled)

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
        val account = clients.currentClient.value?.account ?: return@withLock Result.failure(IllegalStateException("未登录"))
        if (expectedAccount != null && account != expectedAccount) throw CancellationException("来源账本账号已改变")
        runSuspendCatching { sync(account) }
            .logFailure(TAG, "来源账本同步失败")
    }

    private suspend fun sync(account: String): Map<String, SourceRecord> {
        val remoteText = remote.read(account)
        if (clients.currentClient.value?.account != account) throw CancellationException("来源账本账号已改变")
        val remoteRecords = remoteText?.let(SourceLedger::decode).orEmpty()
        val before = ledger.flow.value.size
        ledger.merge(remoteRecords)
        val merged = ledger.flow.value
        val pushed = merged != remoteRecords
        if (pushed) remote.write(account, SourceLedger.encode(merged), now())
        PikoLog.d(TAG, "来源账本同步：本机 $before 条，远端${if (remoteText == null) "无文件" else " ${remoteRecords.size} 条"}，" +
            "合并后 ${merged.size} 条，${if (pushed) "已推送" else "无需推送"}")
        return merged
    }

    private companion object {
        const val FILE_PREFIX = "sources-"
        const val PUSH_DELAY_MS = 3_000L
        const val TAG = "SourceLedgerSync"
    }
}
