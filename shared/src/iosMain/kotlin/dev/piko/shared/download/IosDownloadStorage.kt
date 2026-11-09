@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.piko.shared.download

import dev.piko.shared.preferences.IosPreferenceSettings
import dev.piko.shared.storage.IosAppDirectories
import dev.piko.shared.storage.SandboxPaths
import platform.Foundation.NSUserDefaults

/**
 * Compose the real filesystem download boundary for iOS. Use the same NSUserDefaults domain as
 * createIosPreferences. Download selection is reread for new operations, not captured at startup.
 * Files stay in Documents/Piko; sparse data lives in the purgeable Caches/Piko/downloads directory.
 * This is storage, not a background URLSession transport or a segment/remux implementation.
 */
fun createIosDownloadStorage(
    directories: IosAppDirectories,
    defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
): PikoDownloadStorage {
    val settings = IosPreferenceSettings(defaults, directories.downloads)
    return AppDirectoryDownloadStorage(
        managedDirectory = directories.downloads,
        cacheDirectory = SandboxPaths.resolveDirectory(directories.caches, "downloads"),
        directoryProvider = { settings.downloadDirectoryPath },
    )
}
