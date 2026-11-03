package dev.piko.desktop

import dev.piko.shared.i18n.tr

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.auth.SecretVault
import dev.piko.shared.preferences.PreferenceSettings
import dev.piko.shared.preferences.StoredPikoPreferences

/** Desktop persistence adapter; shared behavior lives in [StoredPikoPreferences]. */
class DesktopPikoPreferences(
    settings: DesktopSettingsStore,
    openSecrets: () -> SecretVault,
) : PikoUserPreferences by StoredPikoPreferences(DesktopPreferenceSettings(settings), openSecrets)

private class DesktopPreferenceSettings(private val store: DesktopSettingsStore) : PreferenceSettings {
    override fun get(key: String, default: String): String = store.get(key, default)
    override fun set(key: String, value: String) = store.set(key, value)
    override fun remove(key: String) = store.remove(key)
    override fun keysWithPrefix(prefix: String): List<String> = store.keysWithPrefix(prefix)
    override val downloadDirectoryPath: String get() = store.downloadDirectory.absolutePath
    override fun setDownloadDirectory(path: String) = store.setDownloadDirectory(path)
}
