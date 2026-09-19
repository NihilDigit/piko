package dev.piko.shared.download

import io.github.nihildigit.pikpak.PikPakStreamReader

/** Platform boundary for persistent file I/O. The scheduler never sees Context or java.io.File. */
interface PikoDownloadStorage {
    fun pathFor(fileName: String): String

    suspend fun download(
        fileName: String,
        reader: PikPakStreamReader,
        totalBytes: Long,
        onProgress: suspend (downloadedBytes: Long) -> Unit,
    ): String

    suspend fun existingLength(fileName: String): Long
    suspend fun exists(fileName: String): Boolean
    suspend fun delete(path: String): Boolean
}
