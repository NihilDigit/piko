package dev.piko.ui.platform

/** Only published player URL contracts belong here; URL is encoded as one query value. */
internal fun externalPlayerUrl(player: String, source: String): String? {
    val prefix = when (player) {
        "SenPlayer" -> "senplayer://x-callback-url/play?url="
        "Rex" -> "rex://play?url="
        "VLC" -> "vlc-x-callback://x-callback-url/stream?url="
        "Outplayer" -> "outplayer://x-callback-url/play?url="
        else -> return null
    }
    return prefix + encodePlayerQueryValue(source)
}

private fun encodePlayerQueryValue(value: String): String = buildString {
    val hex = "0123456789ABCDEF"
    value.encodeToByteArray().forEach { byte ->
        val n = byte.toInt() and 0xff
        if (n in 65..90 || n in 97..122 || n in 48..57 || n == 45 || n == 46 || n == 95 || n == 126) {
            append(n.toChar())
        } else {
            append('%')
            append(hex[n shr 4])
            append(hex[n and 15])
        }
    }
}
