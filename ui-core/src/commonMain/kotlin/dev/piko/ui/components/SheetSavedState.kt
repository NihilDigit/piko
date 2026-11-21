package dev.piko.ui.screens.drive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry

/** 在 [block] 里 rememberSaveable 退化成 remember：不向页面的保存表写入，也不从中恢复。 */
@Composable
fun <T> withoutSavedState(block: @Composable () -> T): T {
    var result: T? = null
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) { result = block() }
    @Suppress("UNCHECKED_CAST")
    return result as T
}
