package dev.piko.shared.media.player

import dev.piko.shared.data.LeasedFile
import dev.piko.shared.data.LocalLocation
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFile
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.reportPlay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

/**
 * 云端播放历史成功提交后的通知事件，供历史记录页刷新列表缓存。
 */
data class PlayHistoryReportEvent(
    val account: String,
    val fileId: String,
    val positionMillis: Long,
    val durationMillis: Long,
)

/**
 * 可靠的云端播放历史上报队列（独立于播放器界面作用域，由仓库/进程级作用域持有）：
 *
 * - **账号隔离与捕获**：在 [submit] 时同步捕获归属的 [PikPakClient] 与账号标识，不受随后退出登录或切换账号竞态影响；
 * - **PikPak 同文件冷却护航**：PikPak `/drive/v1/events` 会对同一 `fileId` 约 6 秒内的重复上报返回 HTTP 200 `{}` 并静默丢弃；
 *   本队列仅在上报真正被服务端接受后记录单调时间戳，冷却窗口内到达的新进度（含退出、换集、暂停、退后台）合并为最新待发快照，
 *   待冷却满 [cooldownMillis]（默认 6.5 秒）后再发出，绝不提前撞进静默丢弃窗口；
 * - **有界退避重试**：网络抖动、超时、429 与 5xx 错误自动按指数退避重试，4xx 永久拒绝不盲目重试，全程带日志记录；
 * - **退出前刷盘**：[flushAll] 等待在途与可在预算内越过冷却的待发上报完成，防止桌面端关闭窗口即刻 `exitProcess(0)` 截断上报。
 */
class PlayHistoryReporter(
    private val scope: CoroutineScope,
    private val isPlayHistorySynced: suspend () -> Boolean,
    private val submitPlay: suspend (client: PikPakClient, fileId: String, positionSeconds: Long, durationSeconds: Long) -> Unit =
        { client, fileId, positionSeconds, durationSeconds ->
            client.reportPlay(fileId, positionSeconds, durationSeconds)
        },
    private val cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS,
    private val retryDelaysMillis: LongArray = DEFAULT_RETRY_DELAYS_MILLIS,
    private val nowMillis: () -> Long = defaultMonotonicMillis(),
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    private val onReported: ((PlayHistoryReportEvent) -> Unit)? = null,
    private val publishGlobalEvent: Boolean = true,
) {
    private data class FileKey(val account: String, val fileId: String)

    private data class PendingReport(
        val client: PikPakClient,
        val account: String,
        val fileId: String,
        val title: String,
        val positionMillis: Long,
        val durationMillis: Long,
        val positionSeconds: Long,
        val durationSeconds: Long,
        val urgent: Boolean,
    )

    private class FileEntry {
        var pending: PendingReport? = null
        var inFlight: Boolean = false
        var cooldownJob: Job? = null
        var lastAcceptedAtMillis: Long = Long.MIN_VALUE
        var lastAcceptedPositionSec: Long = -1L
        var lastAcceptedDurationSec: Long = -1L
    }

    private data class FlushWaiter(
        val deferred: CompletableDeferred<Unit>,
        val deadlineMillis: Long,
    )

    private sealed interface Command {
        data class Submit(val report: PendingReport) : Command
        data class CooldownReady(val key: FileKey) : Command
        data class UploadFinished(
            val key: FileKey,
            val report: PendingReport,
            val acceptedAtMillis: Long?,
        ) : Command

        data class Flush(
            val deferred: CompletableDeferred<Unit>,
            val deadlineMillis: Long,
        ) : Command
    }

    private val _reported = MutableSharedFlow<PlayHistoryReportEvent>(extraBufferCapacity = 32)
    val reported: SharedFlow<PlayHistoryReportEvent> = _reported.asSharedFlow()

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val entries = HashMap<FileKey, FileEntry>()
    private val flushWaiters = ArrayList<FlushWaiter>()

    init {
        scope.launch {
            for (command in commands) {
                when (command) {
                    is Command.Submit -> handleSubmit(command.report)
                    is Command.CooldownReady -> handleCooldownReady(command.key)
                    is Command.UploadFinished -> handleUploadFinished(command.key, command.report, command.acceptedAtMillis)
                    is Command.Flush -> handleFlush(command.deferred, command.deadlineMillis)
                }
            }
        }
    }

    /**
     * 同步入队一条云端播放进度上报（调用方无需挂起，可在 `release()`、`switchTo()`、`pause()`、`ON_STOP` 中直接调用）。
     * 仅接纳非本地、非借用且 `positionMillis >= 1_000L && durationMillis > 0L` 的有效进度。
     */
    fun submit(
        client: PikPakClient,
        fileId: String,
        positionMillis: Long,
        durationMillis: Long,
        urgent: Boolean = false,
        title: String = "",
    ): Boolean {
        if (fileId.isBlank()) {
            PikoLog.d(TAG, "Skipped cloud play history report (blank fileId)")
            return false
        }
        if (LocalLocation.isLocal(fileId) || LeasedFile.isLeased(fileId)) {
            PikoLog.d(TAG, "Skipped cloud play history report (non-cloud file): ${logFile(fileId, title)}")
            return false
        }
        if (durationMillis <= 0L) {
            PikoLog.d(
                TAG,
                "Skipped cloud play history report (duration<=0, posMs=$positionMillis): ${logFile(fileId, title)}",
            )
            return false
        }
        if (positionMillis < MIN_REPORT_POSITION_MILLIS) {
            PikoLog.d(
                TAG,
                "Skipped cloud play history report (posMs=$positionMillis < ${MIN_REPORT_POSITION_MILLIS}ms): ${logFile(fileId, title)}",
            )
            return false
        }
        val durationSec = (durationMillis / 1_000L).coerceAtLeast(1L)
        val positionSec = (positionMillis / 1_000L).coerceIn(1L, durationSec)
        val report = PendingReport(
            client = client,
            account = client.account,
            fileId = fileId,
            title = title,
            positionMillis = positionMillis,
            durationMillis = durationMillis,
            positionSeconds = positionSec,
            durationSeconds = durationSec,
            urgent = urgent,
        )
        return commands.trySend(Command.Submit(report)).isSuccess
    }

    /**
     * 等待全部在途上报与可在 [timeoutMillis] 预算内越过同文件冷却的待发上报完成。
     * 绝不提前向 PikPak 静默丢弃窗口发包；若超时或冷却超出预算则按时返回，不卡死退出流程。
     */
    suspend fun flushAll(timeoutMillis: Long = DEFAULT_FLUSH_TIMEOUT_MILLIS) {
        if (!scope.isActive) return
        val bounded = timeoutMillis.coerceAtLeast(0L)
        val done = CompletableDeferred<Unit>()
        val deadline = nowMillis() + bounded
        if (!commands.trySend(Command.Flush(done, deadline)).isSuccess) return
        if (bounded == 0L) {
            withTimeoutOrNull(50L) { done.await() }
        } else {
            withTimeoutOrNull(bounded) { done.await() }
        }
    }

    private fun handleSubmit(report: PendingReport) {
        val key = FileKey(report.account, report.fileId)
        val entry = entries.getOrPut(key) { FileEntry() }
        if (entry.lastAcceptedAtMillis != Long.MIN_VALUE &&
            entry.lastAcceptedPositionSec == report.positionSeconds &&
            entry.lastAcceptedDurationSec == report.durationSeconds &&
            entry.pending == null &&
            !entry.inFlight
        ) {
            PikoLog.d(
                TAG,
                "Skipped unchanged cloud play history (${report.positionSeconds}s/${report.durationSeconds}s): ${logFile(report.fileId, report.title)}",
            )
            return
        }
        val previous = entry.pending
        entry.pending = if (previous != null) {
            report.copy(urgent = previous.urgent || report.urgent)
        } else {
            report
        }
        maybeDispatch(key, entry)
        pruneStaleEntriesIfNeeded()
    }

    private fun handleCooldownReady(key: FileKey) {
        val entry = entries[key] ?: return
        entry.cooldownJob = null
        maybeDispatch(key, entry)
        checkFlushWaiters()
    }

    private fun handleUploadFinished(key: FileKey, report: PendingReport, acceptedAtMillis: Long?) {
        val entry = entries[key] ?: return
        entry.inFlight = false
        if (acceptedAtMillis != null) {
            entry.lastAcceptedAtMillis = acceptedAtMillis
            entry.lastAcceptedPositionSec = report.positionSeconds
            entry.lastAcceptedDurationSec = report.durationSeconds
            val event = PlayHistoryReportEvent(
                account = report.account,
                fileId = report.fileId,
                positionMillis = report.positionMillis,
                durationMillis = report.durationMillis,
            )
            _reported.tryEmit(event)
            if (publishGlobalEvent) {
                _reportedEvents.tryEmit(event)
            }
            onReported?.invoke(event)
        }
        maybeDispatch(key, entry)
        checkFlushWaiters()
    }

    private fun handleFlush(deferred: CompletableDeferred<Unit>, deadlineMillis: Long) {
        flushWaiters += FlushWaiter(deferred, deadlineMillis)
        checkFlushWaiters()
    }

    private fun maybeDispatch(key: FileKey, entry: FileEntry) {
        if (entry.inFlight) return
        val pending = entry.pending ?: return
        if (entry.lastAcceptedAtMillis != Long.MIN_VALUE &&
            entry.lastAcceptedPositionSec == pending.positionSeconds &&
            entry.lastAcceptedDurationSec == pending.durationSeconds
        ) {
            entry.pending = null
            entry.cooldownJob?.cancel()
            entry.cooldownJob = null
            PikoLog.d(
                TAG,
                "Skipped unchanged pending cloud play history (${pending.positionSeconds}s/${pending.durationSeconds}s): ${logFile(pending.fileId, pending.title)}",
            )
            checkFlushWaiters()
            return
        }

        val now = nowMillis()
        val remainingCooldown = remainingCooldownMs(entry, now)
        if (remainingCooldown > 0L) {
            if (entry.cooldownJob?.isActive != true) {
                PikoLog.d(
                    TAG,
                    "Coalescing & deferring cloud play history by ${remainingCooldown}ms for PikPak cooldown " +
                        "(pos=${pending.positionSeconds}s/${pending.durationSeconds}s, urgent=${pending.urgent}): ${logFile(pending.fileId, pending.title)}",
                )
                entry.cooldownJob = scope.launch {
                    delayFn(remainingCooldown)
                    commands.trySend(Command.CooldownReady(key))
                }
            }
            return
        }

        entry.pending = null
        entry.cooldownJob?.cancel()
        entry.cooldownJob = null
        entry.inFlight = true
        scope.launch {
            val acceptedAt = executeWithRetries(pending)
            commands.trySend(Command.UploadFinished(key, pending, acceptedAt))
        }
    }

    private suspend fun executeWithRetries(report: PendingReport): Long? {
        val synced = try {
            isPlayHistorySynced()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            PikoLog.w(TAG, "Failed to read syncPlayHistory preference before reporting ${logFile(report.fileId, report.title)}", e)
            return null
        }
        if (!synced) {
            PikoLog.d(TAG, "Skipped cloud play history report (sync disabled): ${logFile(report.fileId, report.title)}")
            return null
        }

        var attempt = 0
        val maxAttempts = 1 + retryDelaysMillis.size
        while (attempt < maxAttempts) {
            try {
                submitPlay(report.client, report.fileId, report.positionSeconds, report.durationSeconds)
                val acceptedAt = nowMillis()
                PikoLog.d(
                    TAG,
                    "Reported cloud play history: ${logFile(report.fileId, report.title)}, " +
                        "pos=${report.positionSeconds}s/${report.durationSeconds}s, urgent=${report.urgent}, attempt=${attempt + 1}",
                )
                return acceptedAt
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val retryable = isRetryable(e)
                val hasNextAttempt = retryable && attempt < retryDelaysMillis.size
                if (!hasNextAttempt) {
                    PikoLog.w(
                        TAG,
                        "Failed to report cloud play history after ${attempt + 1} attempt(s) " +
                            "(retryable=$retryable, pos=${report.positionSeconds}s/${report.durationSeconds}s): " +
                            logFile(report.fileId, report.title),
                        e,
                    )
                    return null
                }
                val backoff = retryDelaysMillis[attempt]
                PikoLog.d(
                    TAG,
                    "Cloud play history attempt ${attempt + 1}/$maxAttempts failed, retrying in ${backoff}ms " +
                        "(${e::class.simpleName}: ${e.message}): ${logFile(report.fileId, report.title)}",
                )
                delayFn(backoff)
                attempt++
            }
        }
        return null
    }

    private fun checkFlushWaiters() {
        if (flushWaiters.isEmpty()) return
        val now = nowMillis()
        val iterator = flushWaiters.iterator()
        while (iterator.hasNext()) {
            val waiter = iterator.next()
            if (waiter.deferred.isCompleted) {
                iterator.remove()
                continue
            }
            val remainingBudget = (waiter.deadlineMillis - now).coerceAtLeast(0L)
            var mustWait = false
            var skippedUnreachableCooldown = 0
            for (entry in entries.values) {
                if (entry.inFlight) {
                    mustWait = true
                    break
                }
                if (entry.pending != null) {
                    val remainingCooldown = remainingCooldownMs(entry, now)
                    if (remainingCooldown <= 0L || remainingCooldown <= remainingBudget) {
                        mustWait = true
                        break
                    } else {
                        skippedUnreachableCooldown++
                    }
                }
            }
            if (!mustWait) {
                if (skippedUnreachableCooldown > 0) {
                    PikoLog.d(
                        TAG,
                        "Flush returning without waiting for $skippedUnreachableCooldown cooldown-deferred report(s) exceeding remaining budget (${remainingBudget}ms)",
                    )
                }
                waiter.deferred.complete(Unit)
                iterator.remove()
            }
        }
    }

    private fun remainingCooldownMs(entry: FileEntry, now: Long): Long {
        if (entry.lastAcceptedAtMillis == Long.MIN_VALUE) return 0L
        val elapsed = (now - entry.lastAcceptedAtMillis).coerceAtLeast(0L)
        return (cooldownMillis - elapsed).coerceAtLeast(0L)
    }

    private fun pruneStaleEntriesIfNeeded() {
        if (entries.size <= MAX_TRACKED_ENTRIES) return
        val now = nowMillis()
        entries.entries.removeAll { (_, entry) ->
            !entry.inFlight &&
                entry.pending == null &&
                entry.cooldownJob?.isActive != true &&
                entry.lastAcceptedAtMillis != Long.MIN_VALUE &&
                (now - entry.lastAcceptedAtMillis) > ENTRY_RETENTION_MILLIS
        }
    }

    private fun isRetryable(error: Throwable): Boolean {
        if (error is PikPakException) {
            val status = error.httpStatus
            if (status != null && status in 400..499 && status != 408 && status != 429) {
                return false
            }
        }
        return true
    }

    companion object {
        private const val TAG = "PlayHistory"

        /** PikPak `/drive/v1/events` 同文件冷却约 6 秒，留 0.5 秒安全余量防止静默丢弃。 */
        const val DEFAULT_COOLDOWN_MILLIS = 6_500L

        /** 小于 1 秒折算为 0 秒，服务端无意义且不记入历史。 */
        const val MIN_REPORT_POSITION_MILLIS = 1_000L

        const val DEFAULT_FLUSH_TIMEOUT_MILLIS = 4_500L

        val DEFAULT_RETRY_DELAYS_MILLIS = longArrayOf(500L, 1_500L, 3_500L)

        private const val MAX_TRACKED_ENTRIES = 64
        private const val ENTRY_RETENTION_MILLIS = 60L * 60_000L

        private val _reportedEvents = MutableSharedFlow<PlayHistoryReportEvent>(extraBufferCapacity = 32)

        /** 进程级播放历史上报成功流，供 [dev.piko.shared.state.HistoryMutations] 刷新已打开的历史记录页。 */
        val reportedEvents: SharedFlow<PlayHistoryReportEvent> = _reportedEvents.asSharedFlow()

        private fun defaultMonotonicMillis(): () -> Long {
            val origin = TimeSource.Monotonic.markNow()
            return { origin.elapsedNow().inWholeMilliseconds }
        }
    }
}
