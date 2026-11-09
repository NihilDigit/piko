package dev.piko.shared.auth

import dev.piko.shared.data.PikoSessionStore

/**
 * Create once at iOS app startup and share across account clients.
 * Use a stable, app-owned Keychain service (for example the host bundle ID plus `.sessions`).
 * Changing it starts a different storage namespace; do not include app versions in it.
 * This factory does not create an app host or modify Keychain entitlements.
 */
fun createIosSessionStore(service: String): PikoSessionStore = VaultSessionStore(IosKeychainVault(service))
