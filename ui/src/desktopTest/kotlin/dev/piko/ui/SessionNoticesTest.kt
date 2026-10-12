package dev.piko.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionNoticesTest {
    @Test
    fun addingAnotherSessionDoesNotCancelAnUnfinishedNotice() = runBlocking {
        val sessions = mutableStateListOf("first")
        val watching = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        val received = Channel<String>(Channel.UNLIMITED)
        val collecting = launch(Dispatchers.Default) {
            sessionNotices({ sessions.toList() }) { id, _ ->
                flow {
                    if (id == "first") {
                        watching.send(Unit)
                        release.receive()
                        emit("first finished")
                    }
                }
            }.collect { received.send(it) }
        }
        try {
            withTimeout(5_000) { watching.receive() }
            sessions += "second"
            Snapshot.sendApplyNotifications()
            release.send(Unit)
            assertEquals("first finished", withTimeout(5_000) { received.receive() })
        } finally {
            collecting.cancelAndJoin()
        }
    }

    @Test
    fun addingAnotherSessionDoesNotReplayOrDropTheFirstSessionNotice() = runBlocking {
        val sessions = mutableStateListOf("first")
        val first = MutableSharedFlow<String>(replay = 1)
        val second = MutableSharedFlow<String>(replay = 1)
        val received = Channel<String>(Channel.UNLIMITED)
        val registrations = Channel<Pair<String, Boolean>>(Channel.UNLIMITED)
        val collecting = launch(Dispatchers.Default) {
            sessionNotices({ sessions.toList() }) { id, preexisting ->
                registrations.trySend(id to preexisting)
                if (id == "first") first else second
            }
                .collect { received.send(it) }
        }
        try {
            withTimeout(5_000) { first.subscriptionCount.first { it == 1 } }
            assertEquals("first" to true, withTimeout(5_000) { registrations.receive() })
            first.emit("first finished")
            assertEquals("first finished", withTimeout(5_000) { received.receive() })

            sessions += "second"
            Snapshot.sendApplyNotifications()
            withTimeout(5_000) { second.subscriptionCount.first { it == 1 } }
            assertEquals("second" to false, withTimeout(5_000) { registrations.receive() })
            assertTrue(received.tryReceive().isFailure, "adding a session replayed an old result")
            second.emit("second finished")
            assertEquals("second finished", withTimeout(5_000) { received.receive() })
            assertTrue(received.tryReceive().isFailure)
        } finally {
            collecting.cancelAndJoin()
        }
    }
}
