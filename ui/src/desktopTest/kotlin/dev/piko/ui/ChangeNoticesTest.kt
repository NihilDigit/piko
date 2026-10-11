package dev.piko.ui

import androidx.compose.material3.SnackbarHostState
import dev.piko.shared.data.DriveChangeJournal
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChangeNoticesTest {
    @Test
    fun queuedOldAccountNoticeIsSkippedWhenTheAppReturnsToTheForeground() = runBlocking {
        val events = MutableSharedFlow<DriveChangeJournal.Event>(extraBufferCapacity = 8)
        val foreground = MutableStateFlow(false)
        val generation = MutableStateFlow(1L)
        val waiting = CompletableDeferred<Unit>()
        val host = SnackbarHostState()
        val job = launch {
            showChangeNotices(events, host, awaitVisible = { waiting.complete(Unit); foreground.first { it } }, undo = {},
                isCurrent = { it.generation == generation.value })
        }
        events.subscriptionCount.first { it > 0 }
        events.emit(DriveChangeJournal.Event("old account", null, generation = 1))
        withTimeout(5_000) { waiting.await() }
        generation.value = 2
        foreground.value = true
        events.emit(DriveChangeJournal.Event("new account", null, generation = 2))
        withTimeout(5_000) { while (host.currentSnackbarData == null) delay(10) }
        assertEquals("new account", host.currentSnackbarData!!.visuals.message)
        host.currentSnackbarData!!.dismiss()
        job.cancel()
    }
    /**
     * 归档在后台做完时，提示要等人回到前台才弹，弹出后点「撤销」撤的是那一次改动。
     * 不等的话 Snackbar 在后台计完时，人回来什么也看不到。
     */
    @Test
    fun noticeFromTheBackgroundWaitsForTheForegroundAndStillUndoes() = runBlocking {
        val events = MutableSharedFlow<DriveChangeJournal.Event>(extraBufferCapacity = 8)
        val foreground = MutableStateFlow(false)
        val host = SnackbarHostState()
        val undone = CopyOnWriteArrayList<DriveChangeJournal.Change>()
        val job = launch {
            showChangeNotices(events, host, awaitVisible = { foreground.first { it } }, undo = { undone += it })
        }
        events.subscriptionCount.first { it > 0 }

        val change = DriveChangeJournal.Change.Vault(emptyMap(), "「电影」：已归档 3 个文件，原文件已移入回收站")
        events.emit(DriveChangeJournal.Event(change.summary, change))
        delay(100)
        assertNull(host.currentSnackbarData, "在后台时不该弹")

        foreground.value = true
        val shown = withTimeout(5_000) {
            while (host.currentSnackbarData == null) delay(10)
            host.currentSnackbarData!!
        }
        assertEquals(change.summary, shown.visuals.message)
        assertEquals("撤销", shown.visuals.actionLabel)
        shown.performAction()
        withTimeout(5_000) { while (undone.isEmpty()) delay(10) }
        assertEquals(listOf<DriveChangeJournal.Change>(change), undone.toList())
        job.cancel()
    }
}
