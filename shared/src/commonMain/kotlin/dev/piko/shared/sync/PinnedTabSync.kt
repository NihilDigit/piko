package dev.piko.shared.sync

import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PinnedTabRecord
import dev.piko.shared.data.PinnedTabs
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

/** Sync account-owned pinned directories through the same remote store as settings. */
class PinnedTabSync(
    private val clients: PikoClientProvider,
    private val remote: RemoteSettingsStore,
    private val pinnedTabs: PinnedTabs,
    private val scope: CoroutineScope,
    private val enabled: Flow<Boolean>,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    constructor(clients: PikoClientProvider, driveRepo: PikoDriveRepository, pinnedTabs: PinnedTabs, scope: CoroutineScope, enabled: Flow<Boolean>) :
        this(clients, DriveSettingsStore(clients, driveRepo, FILE_PREFIX, TAG), pinnedTabs, scope, enabled)

    private val lock = Mutex()

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            combine(clients.currentClient, enabled) { client, on -> client?.account.takeIf { on } }
                .distinctUntilChanged().collectLatest { account ->
                    if (account == null) return@collectLatest
                    val before = pinnedTabs.flow.value
                    var handled = syncSnapshot(account).getOrElse { before }
                    pinnedTabs.flow.debounce(PUSH_DELAY_MS).collect { records ->
                        if (records != handled) handled = syncSnapshot(account).getOrElse { records }
                    }
                }
        }
    }

    suspend fun syncNow(): Boolean = syncSnapshot().isSuccess

    private suspend fun syncSnapshot(expectedAccount: String? = null): Result<Map<String, PinnedTabRecord>> = lock.withLock {
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
                PikoLog.w(TAG, "Pinned tabs sync failed", err)
            }
        }
    }

    private fun checkAccount(account: String) {
        if (clients.currentClient.value?.account != account) throw SyncAccountSwitchedException()
    }

    private suspend fun sync(account: String): Map<String, PinnedTabRecord> {
        checkAccount(account)
        pinnedTabs.switchAccount(account)
        val local = pinnedTabs.awaitAccountReady(account) ?: throw SyncAccountSwitchedException()
        checkAccount(account)
        val text = remote.read(account)
        checkAccount(account)
        val remoteRecords = if (text != null) {
            PinnedTabs.decode(text) ?: run {
                PikoLog.w(TAG, "Remote pinned tabs file is malformed or uses an unsupported version; skipping sync to preserve remote file")
                return local
            }
        } else {
            emptyMap()
        }
        val merged = pinnedTabs.mergeAndSnapshot(remoteRecords, now(), expectedAccount = account)
            ?: throw SyncAccountSwitchedException()
        val pushed = merged != remoteRecords
        if (pushed) {
            checkAccount(account)
            remote.write(account, PinnedTabs.encode(merged), now())
            checkAccount(account)
        }
        val activeLocal = local.values.count { it.pinned }
        val activeRemote = remoteRecords.values.count { it.pinned }
        val activeMerged = merged.values.count { it.pinned }
        PikoLog.i(
            TAG,
            "Pinned tabs synced: local=$activeLocal, remote=${if (text == null) "none" else activeRemote.toString()}, " +
                "merged=$activeMerged, pushed=$pushed",
        )
        return merged
    }

    private companion object {
        const val FILE_PREFIX = "pinned-tabs-"
        const val PUSH_DELAY_MS = 1_200L
        const val TAG = "PinnedTabSync"
    }
}
