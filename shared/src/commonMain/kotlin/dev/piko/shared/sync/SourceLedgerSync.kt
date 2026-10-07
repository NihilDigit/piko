package dev.piko.shared.sync

import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.SourceLedger
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

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
                    syncNow()
                    // 一批秒传连着记好几次，等停下来再推
                    ledger.flow.drop(1).debounce(PUSH_DELAY_MS).collect { syncNow() }
                }
        }
    }

    // 日志只记条数：来源是磁力与分享链接，导出的日志要发给别人
    suspend fun syncNow(): Boolean = lock.withLock {
        val account = clients.currentClient.value?.account ?: return@withLock false
        runCatching { sync(account) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .logFailure(TAG, "来源账本同步失败")
            .isSuccess
    }

    private suspend fun sync(account: String) {
        val remoteText = remote.read(account)
        val remoteRecords = remoteText?.let(SourceLedger::decode).orEmpty()
        val before = ledger.flow.value.size
        ledger.merge(remoteRecords)
        val merged = ledger.flow.value
        val pushed = merged != remoteRecords
        if (pushed) remote.write(account, SourceLedger.encode(merged), now())
        PikoLog.d(TAG, "来源账本同步：本机 $before 条，远端${if (remoteText == null) "无文件" else " ${remoteRecords.size} 条"}，" +
            "合并后 ${merged.size} 条，${if (pushed) "已推送" else "无需推送"}")
    }

    private companion object {
        const val FILE_PREFIX = "sources-"
        const val PUSH_DELAY_MS = 3_000L
        const val TAG = "SourceLedgerSync"
    }
}
