package dev.piko.ui.platform

import io.ktor.http.encodeURLParameter

/** Only published player URL contracts belong here; URL is encoded as one query value. */
internal fun externalPlayerUrl(player: String, source: String): String? {
    val prefix = when (player) {
        "SenPlayer" -> "senplayer://x-callback-url/play?url="
        "Rex" -> "rex://play?url="
        "VLC" -> "vlc-x-callback://x-callback-url/stream?url="
        "Outplayer" -> "outplayer://x-callback-url/play?url="
        else -> return null
    }
    return prefix + source.encodeURLParameter()
}
