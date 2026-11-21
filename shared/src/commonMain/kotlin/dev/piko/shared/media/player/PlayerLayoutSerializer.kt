@file:JvmMultifileClass
@file:JvmName("PlayerLayoutPrefsKt")

package dev.piko.shared.media.player

import kotlin.jvm.JvmMultifileClass
import kotlin.jvm.JvmName
import kotlinx.serialization.json.Json

private val PlayerLayoutJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun encodePlayerLayoutPrefs(prefs: PlayerLayoutPrefs): String =
    PlayerLayoutJson.encodeToString(PlayerLayoutPrefs.serializer(), prefs.sanitize())

fun decodePlayerLayoutPrefs(raw: String?): PlayerLayoutPrefs {
    if (raw.isNullOrBlank()) return PlayerLayoutPrefs.Default
    return runCatching {
        PlayerLayoutJson.decodeFromString(PlayerLayoutPrefs.serializer(), raw).sanitize()
    }.getOrElse { PlayerLayoutPrefs.Default }
}
