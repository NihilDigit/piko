@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.piko.shared.storage

import kotlinx.io.IOException
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSSearchPathDirectory
import platform.Foundation.NSUserDomainMask

/** Resolve these again each launch: an iOS container's absolute path is not a durable identifier. */
data class IosAppDirectories(val support: String, val caches: String, val downloads: String)

/** Foundation-owned sandbox directories; downloads remain inside Documents/Piko. */
fun createIosAppDirectories(): IosAppDirectories {
    fun directory(kind: NSSearchPathDirectory): String {
        val parent = NSSearchPathForDirectoriesInDomains(kind, NSUserDomainMask, true).firstOrNull() as? String
            ?: throw IOException("App sandbox directory is unavailable")
        val path = SandboxPaths.resolveDirectory(parent, "Piko")
        ensureIosDirectory(path)
        return path
    }
    return IosAppDirectories(
        support = directory(NSApplicationSupportDirectory),
        caches = directory(NSCachesDirectory),
        downloads = directory(NSDocumentDirectory),
    )
}

internal fun ensureIosDirectory(path: String) {
    val created = NSFileManager.defaultManager.createDirectoryAtPath(
        path = path,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    if (!created) throw IOException("Creating app sandbox directory failed")
}
