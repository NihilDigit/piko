package dev.piko.shared.smoke

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun <T> observeMutationOutcomes(scope: CoroutineScope, flow: Flow<T>): StateFlow<List<T>> {
    val outcomes = MutableStateFlow<List<T>>(emptyList())
    scope.launch(start = CoroutineStart.UNDISPATCHED) { flow.collect { result -> outcomes.update { it + result } } }
    return outcomes
}
