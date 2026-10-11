package dev.piko.shared.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LinkSaveCompletion(val id: Long, val outcome: InstantSaveOutcome)

/** 解析、编辑与预览资源活到明确结束会话；面板是否可见由呈现层持有。 */
class InstantSession(
    private val newScope: () -> CoroutineScope,
    private val newState: (scope: CoroutineScope, initialMagnet: String) -> InstantSheetState,
) {
    var generation by mutableLongStateOf(0L)
        private set

    var state by mutableStateOf<InstantSheetState?>(null)
        private set

    private var scope: CoroutineScope? = null

    private val _pendingCompletions = MutableStateFlow<List<LinkSaveCompletion>>(emptyList())
    val pendingCompletions: StateFlow<List<LinkSaveCompletion>> = _pendingCompletions.asStateFlow()

    fun acknowledgeCompletion(id: Long) {
        _pendingCompletions.update { pending -> pending.filterNot { it.id == id } }
    }

    fun clear() {
        end()
        _pendingCompletions.value = emptyList()
    }

    /**
     * 点 × 之前要不要先确认：人亲手挑过、还勾着要保存的文件。只粘了链接、什么都没勾的直接结束，
     * 丢掉的东西再粘一次就回来，见 [InstantSheetState.hasPickedFiles]。
     */
    val endNeedsConfirm: Boolean
        get() = state?.hasPickedFiles == true

    /** 开一次新的会话，丢弃上一次。 */
    fun start(initialMagnet: String = "") {
        end()
        val sessionScope = newScope()
        scope = sessionScope
        val draft = newState(sessionScope, initialMagnet)
        state = draft
        generation++
        val id = generation
        sessionScope.launch(start = CoroutineStart.UNDISPATCHED) {
            draft.outcomes.collect { outcome ->
                if (state !== draft) return@collect
                _pendingCompletions.update { it + LinkSaveCompletion(id, outcome) }
                end()
            }
        }
    }

    fun end() {
        scope?.cancel()
        scope = null
        state = null
    }
}
