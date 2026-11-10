package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakClient

/** Captures an account-generation-bound observer before a network request starts. */
fun interface DriveMetadataSink {
    fun capture(owner: PikPakClient): DriveDetailObserver?
    fun lookupFile(owner: PikPakClient, fileId: String): FileStat? = null
    fun lookupContent(owner: PikPakClient, gcid: String, sizeBytes: Long): ContentMetadata? = null
    fun lookupContentByFileId(owner: PikPakClient, fileId: String): ContentMetadata? = null
}

/** Receives content facts only; leased detail IDs must never become physical tree nodes. */
fun interface DriveDetailObserver {
    fun observe(originalFileId: String, detail: FileDetail)
    fun observeTranscodeSize(
        gcid: String,
        fileSizeBytes: Long,
        mediaId: String,
        streamSizeBytes: Long?,
        unreadable: Boolean = false,
    ) {}
}
