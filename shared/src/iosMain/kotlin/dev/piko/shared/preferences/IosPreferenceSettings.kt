@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.piko.shared.preferences

import dev.piko.shared.storage.SandboxPaths
import dev.piko.shared.storage.ensureIosDirectory
import platform.Foundation.NSUserDefaults

/**
 * String-only, namespaced NSUserDefaults adapter for non-secret preferences.
 * Foundation publishes changes immediately in-process and flushes them asynchronously to disk.
 * Use one StoredPikoPreferences instance per process to keep its flows coherent.
 *
 * Download paths are stored relative to the managed Documents/Piko directory, never as durable
 * container URLs. External document-provider folders need bookmark integration and are not
 * accepted here. Lexical path normalization is not a replacement for the OS sandbox.
 */
class IosPreferenceSettings(
    private val defaults: NSUserDefaults,
    managedDownloads: String,
    private val keyPrefix: String = "piko.",
) : PreferenceSettings {
    private val downloadRoot = SandboxPaths.resolveDirectory(managedDownloads, "")

    init {
        require(keyPrefix.isNotEmpty() && '\u0000' !in keyPrefix) { "A non-empty preferences namespace is required" }
    }

    override fun get(key: String, default: String): String =
        if (key == DOWNLOAD_PATH) downloadDirectoryPath else readString(key) ?: default

    override fun set(key: String, value: String) {
        if (key == DOWNLOAD_PATH) setDownloadDirectory(value)
        else defaults.setObject(value, forKey = keyPrefix + key)
    }

    override fun remove(key: String) {
        if (key == DOWNLOAD_PATH) setDownloadDirectory("")
        else defaults.removeObjectForKey(keyPrefix + key)
    }

    override fun keysWithPrefix(prefix: String): List<String> = defaults.dictionaryRepresentation().keys
        .filterIsInstance<String>()
        .filter { it.startsWith(keyPrefix + prefix) }
        .map { it.removePrefix(keyPrefix) }
        .sorted()

    override val downloadDirectoryPath: String
        get() = SandboxPaths.resolveDirectory(downloadRoot, readString(DOWNLOAD_RELATIVE).orEmpty())

    override fun setDownloadDirectory(path: String) {
        val relative = SandboxPaths.relativeDirectory(downloadRoot, path)
        ensureIosDirectory(SandboxPaths.resolveDirectory(downloadRoot, relative))
        if (relative.isEmpty()) defaults.removeObjectForKey(keyPrefix + DOWNLOAD_RELATIVE)
        else defaults.setObject(relative, forKey = keyPrefix + DOWNLOAD_RELATIVE)
    }

    private fun readString(key: String): String? = defaults.objectForKey(keyPrefix + key) as? String

    private companion object {
        const val DOWNLOAD_PATH = "download.directory"
        const val DOWNLOAD_RELATIVE = "__storage.downloadRelative"
    }
}
