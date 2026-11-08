package dev.piko.shared.state

import dev.piko.shared.i18n.enCount

import dev.piko.shared.i18n.tr

import dev.piko.shared.data.HistoryKind

sealed interface HistoryEditCommand {
    val kind: HistoryKind
    val title: String
    data class Remove(override val kind: HistoryKind, val eventIds: List<String>) : HistoryEditCommand {
        override val title get() = tr("从${kind.title}移除 ${eventIds.size} 项记录", "Remove ${enCount(eventIds.size, "record", "records")} from ${kind.title}", "${kind.title}から ${eventIds.size} 件の記録を削除", "${kind.title}에서 기록 ${eventIds.size}건 삭제", "從${kind.title}移除 ${eventIds.size} 項記錄")
    }
    data object ClearPlay : HistoryEditCommand {
        override val kind = HistoryKind.PLAY
        override val title get() = tr("清空播放历史", "Clear playback history", "再生履歴を消去", "재생 기록 지우기", "清空播放歷史")
    }
}

enum class HistoryEditCompletion { SUCCEEDED, PARTIAL, FAILED, UNCONFIRMED }

data class HistoryEditResult(
    val completion: HistoryEditCompletion,
    val message: String,
    val confirmedEvents: Set<String> = emptySet(),
    val finishedAtMs: Long,
)
data class HistoryEditOutcome(val account: String, val id: Long, val title: String, val result: HistoryEditResult)

