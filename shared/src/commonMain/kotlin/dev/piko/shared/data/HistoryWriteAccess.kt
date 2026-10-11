package dev.piko.shared.data

import io.github.nihildigit.pikpak.EventType
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.clearEvents
import io.github.nihildigit.pikpak.deleteEvents
import io.github.nihildigit.pikpak.listEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class HistoryKind(val title: String, internal val types: List<String>) {
    PLAY("播放历史", listOf(EventType.PLAY)),
    RECENT("最近添加", listOf(EventType.UPLOAD, EventType.RESTORE)),
}

class HistoryWriteAccess internal constructor(private val client: PikPakClient) {
    suspend fun remove(ids: List<String>) = withContext(Dispatchers.Default) { runSuspendCatching { client.deleteEvents(ids) } }
    suspend fun clearPlay() = withContext(Dispatchers.Default) { runSuspendCatching { client.clearEvents(HistoryKind.PLAY.types) } }
    suspend fun list(kind: HistoryKind, token: String = "") = withContext(Dispatchers.Default) {
        runSuspendCatching { client.listEvents(kind.types, pageToken = token) }
    }
}
