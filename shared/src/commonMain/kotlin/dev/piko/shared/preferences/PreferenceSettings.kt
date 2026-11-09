package dev.piko.shared.preferences

/** Platform persistence used by [StoredPikoPreferences]. */
interface PreferenceSettings {
    fun get(key: String, default: String = ""): String
    fun set(key: String, value: String)
    fun remove(key: String)
    /** Matching keys in sorted order, as used by bounded playback history pruning. */
    fun keysWithPrefix(prefix: String): List<String>
    val downloadDirectoryPath: String
    fun setDownloadDirectory(path: String)
}
