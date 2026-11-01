package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.PikPakClient

/** Captures an account-generation-bound observer before a network request starts. */
fun interface DriveMetadataSink {
    fun capture(owner: PikPakClient): DriveDetailObserver?
}

/** Receives content facts only; leased detail IDs must never become physical tree nodes. */
fun interface DriveDetailObserver {
    fun observe(originalFileId: String, detail: FileDetail)
}
