package dev.piko.shared.data

import dev.piko.shared.i18n.tr
import io.github.nihildigit.pikpak.EventType
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.clearEvents
import io.github.nihildigit.pikpak.deleteEvents
import io.github.nihildigit.pikpak.listEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class HistoryKind(private val titleProvider: () -> String, internal val types: List<String>) {
    PLAY({ tr("播放历史", "Playback history", "再生履歴", "재생 기록", "播放記錄") }, listOf(EventType.PLAY)),
    RECENT({ tr("最近添加", "Recently added", "最近追加した項目", "최근 추가", "最近新增") }, listOf(EventType.UPLOAD, EventType.RESTORE));

    val title: String get() = titleProvider()
}

class HistoryWriteAccess internal constructor(private val client: PikPakClient) {
    suspend fun remove(ids: List<String>) = withContext(Dispatchers.Default) { runSuspendCatching { client.deleteEvents(ids) } }
    suspend fun clearPlay() = withContext(Dispatchers.Default) { runSuspendCatching { client.clearEvents(HistoryKind.PLAY.types) } }
    suspend fun list(kind: HistoryKind, token: String = "") = withContext(Dispatchers.Default) {
        runSuspendCatching { client.listEvents(kind.types, pageToken = token) }
    }
}
