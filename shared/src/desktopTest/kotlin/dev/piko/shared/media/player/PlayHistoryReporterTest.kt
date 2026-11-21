package dev.piko.shared.media.player

import dev.piko.shared.data.HistoryKind
import dev.piko.shared.data.LeasedFile
import dev.piko.shared.data.LocalLocation
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.smoke.FakePikPakServer
import dev.piko.shared.smoke.MemoryPreferences
import dev.piko.shared.smoke.awaitUntil
import dev.piko.shared.smoke.smoke
import dev.piko.shared.state.HistoryMutations
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayHistoryReporterTest {

    private data class SentPlay(
        val account: String,
        val fileId: String,
        val positionSeconds: Long,
        val durationSeconds: Long,
        val sentAtMillis: Long,
    )

    private fun fakeClient(account: String): PikPakClient {
        val engine = MockEngine { respondOk("{}") }
        return PikPakClient(
            account = account,
            password = "pw",
            sessionStore = InMemorySessionStore(),
            httpClient = HttpClient(engine),
            cdnHttpClient = HttpClient(engine),
        )
    }

    @Test
    fun cooldownCoalescesUrgentFinalReportAndNeverFiresEarly() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val clock = AtomicLong(1_000L)
            val sent = CopyOnWriteArrayList<SentPlay>()
            val reportedEvents = CopyOnWriteArrayList<PlayHistoryReportEvent>()
            val delayGate = CompletableDeferred<Unit>()
            val scheduledDelayMs = AtomicLong(-1L)

            val reporter = PlayHistoryReporter(
                scope = scope,
                isPlayHistorySynced = { true },
                submitPlay = { client, fileId, posSec, durSec ->
                    sent += SentPlay(client.account, fileId, posSec, durSec, clock.get())
                },
                cooldownMillis = 6_500L,
                nowMillis = { clock.get() },
                delayFn = { ms ->
                    scheduledDelayMs.set(ms)
                    delayGate.await()
                },
                onReported = { reportedEvents += it },
                publishGlobalEvent = false,
            )

            val client = fakeClient("alice@piko.dev")
            // 1. 首次上报立即提交
            assertTrue(reporter.submit(client, "f1", positionMillis = 10_000L, durationMillis = 600_000L, urgent = false))
            awaitUntil("first play history report sent") { sent.size == 1 }
            assertEquals(10L, sent[0].positionSeconds)
            assertEquals(600L, sent[0].durationSeconds)

            // 2. 冷却窗口内（2s 后与 3s 后）先后到来普通与紧急上报：必须合并为最新位置且绝不提前发包
            clock.set(3_000L)
            assertTrue(reporter.submit(client, "f1", positionMillis = 12_000L, durationMillis = 600_000L, urgent = false))
            awaitUntil("cooldown timer scheduled") { scheduledDelayMs.get() == 4_500L }

            clock.set(4_000L)
            assertTrue(reporter.submit(client, "f1", positionMillis = 15_000L, durationMillis = 600_000L, urgent = true))
            yield()
            assertEquals(1, sent.size, "Must NOT fire prematurely inside PikPak's 6.5s cooldown window")

            // 3. 冷却期满放行：只补发一次，且携带合并后的最新进度 15s
            clock.set(7_500L)
            delayGate.complete(Unit)
            reporter.flushAll(timeoutMillis = 2_000L)

            assertEquals(2, sent.size)
            assertEquals(15L, sent[1].positionSeconds)
            assertEquals(7_500L, sent[1].sentAtMillis)
            assertEquals(2, reportedEvents.size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun retriesTransientFailuresWithBackoffAndDoesNotStampCooldownOnPermanentFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val clock = AtomicLong(10_000L)
            val backoffLog = CopyOnWriteArrayList<Long>()
            val attempts = AtomicInteger(0)
            val sent = CopyOnWriteArrayList<SentPlay>()

            val reporter = PlayHistoryReporter(
                scope = scope,
                isPlayHistorySynced = { true },
                submitPlay = { client, fileId, posSec, durSec ->
                    val n = attempts.incrementAndGet()
                    when {
                        fileId == "flaky" && n == 1 -> throw IOException("connection reset")
                        fileId == "flaky" && n == 2 -> throw PikPakException(429, "too_many_requests", httpStatus = 429)
                        fileId == "missing" && n == 4 -> throw PikPakException(404, "file_not_found", httpStatus = 404)
                        else -> sent += SentPlay(client.account, fileId, posSec, durSec, clock.get())
                    }
                },
                retryDelaysMillis = longArrayOf(500L, 1_500L, 3_500L),
                nowMillis = { clock.get() },
                delayFn = { ms ->
                    backoffLog += ms
                    clock.addAndGet(ms)
                },
                publishGlobalEvent = false,
            )

            val client = fakeClient("retry@piko.dev")
            // 1. 瞬态网络故障 + 429 后在第 3 次尝试成功
            reporter.submit(client, "flaky", positionMillis = 45_000L, durationMillis = 300_000L, urgent = true)
            reporter.flushAll(timeoutMillis = 2_000L)
            assertEquals(3, attempts.get())
            assertEquals(listOf(500L, 1_500L), backoffLog.toList())
            assertEquals(1, sent.size)
            assertEquals(45L, sent[0].positionSeconds)

            // 2. 404 永久客户端错误立即停止（不重试），且不记录冷却时间戳，后续对同文件上报可立刻发出
            backoffLog.clear()
            reporter.submit(client, "missing", positionMillis = 20_000L, durationMillis = 300_000L, urgent = true)
            reporter.flushAll(timeoutMillis = 2_000L)
            assertEquals(4, attempts.get(), "404 must not retry")
            assertTrue(backoffLog.isEmpty())

            reporter.submit(client, "missing", positionMillis = 25_000L, durationMillis = 300_000L, urgent = true)
            reporter.flushAll(timeoutMillis = 2_000L)
            assertEquals(5, attempts.get(), "Failed attempt must not impose a 6.5s cooldown penalty")
            assertTrue(backoffLog.isEmpty())
            assertEquals(2, sent.size)
            assertEquals(25L, sent[1].positionSeconds)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun flushAllAwaitsInFlightAndReachableCooldownWithoutStallingOnExcessiveCooldown() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val clock = AtomicLong(0L)
            val sent = CopyOnWriteArrayList<SentPlay>()
            val inFlightEntered = CompletableDeferred<Unit>()
            val releaseInFlight = CompletableDeferred<Unit>()
            var holdFirst = true

            val reporter = PlayHistoryReporter(
                scope = scope,
                isPlayHistorySynced = { true },
                submitPlay = { client, fileId, posSec, durSec ->
                    if (holdFirst) {
                        holdFirst = false
                        inFlightEntered.complete(Unit)
                        releaseInFlight.await()
                    }
                    sent += SentPlay(client.account, fileId, posSec, durSec, clock.get())
                },
                cooldownMillis = 6_500L,
                nowMillis = { clock.get() },
                delayFn = { ms ->
                    // 仅在超过预算分支保持挂起，测试预算不足时的快速返回
                    if (ms >= 5_000L) {
                        CompletableDeferred<Unit>().await()
                    } else {
                        clock.addAndGet(ms)
                    }
                },
                publishGlobalEvent = false,
            )

            val client = fakeClient("flush@piko.dev")
            // 1. 在途请求：flushAll 必须等待其完成
            reporter.submit(client, "f1", positionMillis = 30_000L, durationMillis = 600_000L, urgent = true)
            inFlightEntered.await()
            val flushDone = CompletableDeferred<Unit>()
            val waiter = launch(Dispatchers.Default) {
                reporter.flushAll(timeoutMillis = 4_500L)
                flushDone.complete(Unit)
            }
            yield()
            assertFalse(flushDone.isCompleted, "flushAll must wait for in-flight report")
            releaseInFlight.complete(Unit)
            flushDone.await()
            waiter.join()
            assertEquals(1, sent.size)

            // 2. 剩余冷却 3.5s <= 预算 4.5s：flushAll 等待冷却结束并提交
            clock.set(3_000L)
            reporter.submit(client, "f1", positionMillis = 35_000L, durationMillis = 600_000L, urgent = true)
            reporter.flushAll(timeoutMillis = 4_500L)
            assertEquals(2, sent.size)
            assertEquals(35L, sent[1].positionSeconds)

            // 3. 剩余冷却 6.0s > 预算 2.0s：flushAll 立刻放行退出，既不卡死也不提前撞进静默丢弃窗
            clock.set(7_000L)
            reporter.submit(client, "f1", positionMillis = 40_000L, durationMillis = 600_000L, urgent = true)
            reporter.flushAll(timeoutMillis = 2_000L)
            assertEquals(2, sent.size, "Must not send prematurely when remaining cooldown exceeds flush budget")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun capturesOwningClientAndIsolatesCooldownPerAccount() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val clock = AtomicLong(0L)
            val sent = CopyOnWriteArrayList<SentPlay>()
            val reporter = PlayHistoryReporter(
                scope = scope,
                isPlayHistorySynced = { true },
                submitPlay = { client, fileId, posSec, durSec ->
                    sent += SentPlay(client.account, fileId, posSec, durSec, clock.get())
                },
                cooldownMillis = 6_500L,
                nowMillis = { clock.get() },
                publishGlobalEvent = false,
            )

            val clientA = fakeClient("a@piko.dev")
            val clientB = fakeClient("b@piko.dev")

            reporter.submit(clientA, "same-file", positionMillis = 50_000L, durationMillis = 500_000L, urgent = true)
            reporter.submit(clientB, "same-file", positionMillis = 80_000L, durationMillis = 500_000L, urgent = true)
            reporter.flushAll(timeoutMillis = 2_000L)

            assertEquals(2, sent.size)
            assertEquals(setOf("a@piko.dev" to 50L, "b@piko.dev" to 80L), sent.map { it.account to it.positionSeconds }.toSet())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun filtersInvalidLocalLeasedSubSecondDisabledSyncAndUnchangedPositions() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val clock = AtomicLong(0L)
            var syncEnabled = true
            val sent = CopyOnWriteArrayList<SentPlay>()
            val reporter = PlayHistoryReporter(
                scope = scope,
                isPlayHistorySynced = { syncEnabled },
                submitPlay = { client, fileId, posSec, durSec ->
                    sent += SentPlay(client.account, fileId, posSec, durSec, clock.get())
                },
                nowMillis = { clock.get() },
                publishGlobalEvent = false,
            )

            val client = fakeClient("filter@piko.dev")
            val localId = LocalLocation("mount-1", listOf("a.mp4"), directory = false).id
            val leasedId = "piko-content:GCID1234:100"
            assertTrue(LocalLocation.isLocal(localId))
            assertTrue(LeasedFile.isLeased(leasedId))
            assertFalse(reporter.submit(client, "", 10_000L, 100_000L))
            assertFalse(reporter.submit(client, localId, 10_000L, 100_000L))
            assertFalse(reporter.submit(client, leasedId, 10_000L, 100_000L))
            assertFalse(reporter.submit(client, "f1", 999L, 100_000L))
            assertFalse(reporter.submit(client, "f1", 10_000L, 0L))

            syncEnabled = false
            assertTrue(reporter.submit(client, "f1", 10_000L, 100_000L))
            reporter.flushAll(timeoutMillis = 2_000L)
            assertTrue(sent.isEmpty(), "Sync disabled must skip cloud submission")

            syncEnabled = true
            assertTrue(reporter.submit(client, "f1", 10_200L, 100_000L))
            reporter.flushAll(timeoutMillis = 2_000L)
            assertEquals(1, sent.size)

            // 同一秒数（10.9s -> 10s）重复提交必须去重跳过
            clock.set(20_000L)
            assertTrue(reporter.submit(client, "f1", 10_900L, 100_000L, urgent = true))
            reporter.flushAll(timeoutMillis = 2_000L)
            assertEquals(1, sent.size, "Unchanged position seconds must be deduplicated")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun acceptedCloudReportNotifiesHistoryMutationsRevisionForMatchingAccount() = smoke { scope ->
        val server = FakePikPakServer()
        val client = PikPakClient(
            account = "history-sync@piko.dev",
            password = "pw",
            sessionStore = InMemorySessionStore(),
            httpClient = server.httpClient(),
        )
        val repo = PikoDriveRepository(server.provider(client), MemoryPreferences())
        val mutations = HistoryMutations(repo, scope)
        val before = mutations.revisions.value[HistoryKind.PLAY] ?: 0L

        val reporter = PlayHistoryReporter(
            scope = scope,
            isPlayHistorySynced = { true },
            submitPlay = { _, _, _, _ -> },
            publishGlobalEvent = true,
        )

        // 其他账号上报不应触发当前账号的历史修订递增
        reporter.submit(fakeClient("other@piko.dev"), "f-other", 15_000L, 120_000L, urgent = true)
        reporter.flushAll(timeoutMillis = 2_000L)
        assertEquals(before, mutations.revisions.value[HistoryKind.PLAY] ?: 0L)

        // 当前账号上报成功后自动递增 HistoryKind.PLAY 修订号
        reporter.submit(client, "f-mine", 30_000L, 120_000L, urgent = true)
        reporter.flushAll(timeoutMillis = 2_000L)
        awaitUntil("HistoryMutations PLAY revision incremented") {
            (mutations.revisions.value[HistoryKind.PLAY] ?: 0L) == before + 1L
        }
    }
}
