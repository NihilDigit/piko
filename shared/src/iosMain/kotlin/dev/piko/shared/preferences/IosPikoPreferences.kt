@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.piko.shared.preferences

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.auth.IosKeychainVault
import dev.piko.shared.storage.IosAppDirectories
import platform.Foundation.NSUserDefaults

/**
 * Compose the full shared preference implementation with iOS storage. Create once per process.
 * Use a stable, app-owned Keychain service distinct from session storage, e.g. bundle ID plus
 * `.preferences`. Ordinary preferences go to NSUserDefaults; upload credentials and MetaTube
 * tokens go only to Keychain. This does not connect an app host or configure signing entitlements.
 */
fun createIosPreferences(
    directories: IosAppDirectories,
    keychainService: String,
    defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
): PikoUserPreferences {
    require(keychainService.isNotBlank() && '\u0000' !in keychainService) { "A stable Keychain service is required" }
    return StoredPikoPreferences(IosPreferenceSettings(defaults, directories.downloads)) {
        IosKeychainVault(keychainService)
    }
}
