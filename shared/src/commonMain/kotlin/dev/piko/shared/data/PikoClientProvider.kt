package dev.piko.shared.data

import io.github.nihildigit.pikpak.PikPakClient
import kotlinx.coroutines.flow.StateFlow

interface PikoClientProvider {
    val currentClient: StateFlow<PikPakClient?>
}
