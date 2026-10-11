package dev.piko.shared.presentation

import dev.piko.shared.data.DriveClipboard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DriveClipboardState {
    private val clipboard = MutableStateFlow<DriveClipboard?>(null)
    val contents: StateFlow<DriveClipboard?> = clipboard.asStateFlow()

    fun put(value: DriveClipboard) { clipboard.value = value }
    fun clear() { clipboard.value = null }
}
