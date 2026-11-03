package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileStat
import kotlin.io.encoding.Base64

/** 设备本地位置。路径逐段编码，不接受绝对路径、空段或父目录跳转。 */
data class LocalLocation(val mountId: String, val segments: List<String> = emptyList(), val directory: Boolean = true) {
    init {
        require(mountId.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        require(segments.all { it.isNotEmpty() && it != "." && it != ".." && it.none { c -> c == '/' || c == '\\' || c == '\u0000' || c == ':' } })
    }
    val id: String get() = (if (directory) DIR_PREFIX else FILE_PREFIX) + mountId + "/" +
        segments.joinToString("/") { Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(it.encodeToByteArray()) }
    fun child(name: String, directory: Boolean) = LocalLocation(mountId, segments + name, directory)
    val parent: LocalLocation? get() = if (segments.isEmpty()) null else copy(segments = segments.dropLast(1), directory = true)

    companion object {
        const val DIR_PREFIX = "piko-local-dir:"
        const val FILE_PREFIX = "piko-local-file:"
        fun isLocal(id: String): Boolean = id.startsWith("piko-local-")
        fun of(id: String): LocalLocation? {
            if (!id.startsWith(DIR_PREFIX) && !id.startsWith(FILE_PREFIX)) return null
            return runCatching {
                val directory = id.startsWith(DIR_PREFIX)
                val raw = id.removePrefix(if (directory) DIR_PREFIX else FILE_PREFIX)
                require('/' in raw)
                val mount = raw.substringBefore('/')
                val path = raw.substringAfter('/')
                val segments = if (path.isEmpty()) emptyList() else path.split('/').map {
                    Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(it).decodeToString(throwOnInvalidSequence = true)
                }
                LocalLocation(mount, segments, directory).also { require(it.id == id) }
            }.getOrNull()
        }
        /** 不能把编码后的相对路径交给日志，非法 ID 同样不保留路径。 */
        fun logIdentity(id: String): String? = if (isLocal(id)) {
            "本地 " + (of(id)?.mountId ?: "?")
        } else null
    }
}

val FileStat.isLocal: Boolean get() = LocalLocation.isLocal(id)
val FileStat.isLocalMountRoot: Boolean get() = LocalLocation.of(id)?.let { it.directory && it.segments.isEmpty() } == true
