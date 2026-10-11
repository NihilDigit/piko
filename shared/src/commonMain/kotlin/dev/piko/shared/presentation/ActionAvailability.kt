package dev.piko.shared.presentation

sealed interface ActionAvailability {
    data object Available : ActionAvailability
    data class Blocked(val reason: String) : ActionAvailability
}
