package dev.piko.ui

import dev.piko.shared.state.FileWriteCommand
import dev.piko.shared.state.FileWriteCompletion
import dev.piko.shared.state.FileWriteResult
import dev.piko.shared.state.FileWriteWork
import dev.piko.shared.upload.UploadSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopSessionPolicyTest {
    @Test
    fun pendingUploadConfirmationKeepsTheWindowSessionUntilTheRequestIsCancelled() = runBlocking {
        val request = MutableStateFlow<UploadSelection?>(UploadSelection(files = listOf("local-file")))
        val changes = Channel<Boolean>(Channel.UNLIMITED)
        val collecting = launch(Dispatchers.Default) {
            desktopSessionRetentionChanges(flowOf(false), flowOf(false), flowOf(Unit), flowOf(Unit), request.map { Unit }) {
                request.value != null
            }.collect { changes.send(it) }
        }
        try {
            assertEquals(true, withTimeout(5_000) { changes.receive() })
            request.value = null
            assertEquals(false, withTimeout(5_000) { changes.receive() })
        } finally { collecting.cancelAndJoin() }
    }
    @Test
    fun forgettingTheLastUnknownResultReleasesTheDesktopEvenWhenExecutionRemainsIdle() = runBlocking {
        val unknown = FileWriteWork(1, "account", FileWriteCommand.Rename("file", "new.txt"), 1,
            result = FileWriteResult(FileWriteCompletion.UNCONFIRMED, "待确认", unconfirmed = setOf("file"), finishedAtMs = 2))
        val results = MutableStateFlow(listOf(unknown))
        val changes = Channel<Boolean>(Channel.UNLIMITED)
        val collecting = launch(Dispatchers.Default) {
            desktopSessionRetentionChanges(flowOf(false), flowOf(false), results.map { Unit }, flowOf(Unit)) {
                results.value.any { it.result?.completion != FileWriteCompletion.SUCCEEDED }
            }.collect { changes.send(it) }
        }
        try {
            assertEquals(true, withTimeout(5_000) { changes.receive() })
            results.value = emptyList()
            assertEquals(false, withTimeout(5_000) { changes.receive() })
        } finally { collecting.cancelAndJoin() }
    }
}
