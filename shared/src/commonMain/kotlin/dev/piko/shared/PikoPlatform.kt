package dev.piko.shared

/**
 * Shared entry point used by both platform shells. Platform-specific services
 * stay outside this module; business state can therefore be tested without an
 * Android Activity or a desktop window.
 */
class PikoPlatform {
    val platformName: String = "Compose Multiplatform"
}
