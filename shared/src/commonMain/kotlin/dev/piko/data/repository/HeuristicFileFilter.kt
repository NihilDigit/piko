package dev.piko.data.repository

import io.github.nihildigit.pikpak.FileStat

object HeuristicFileFilter {
    fun filter(files: List<FileStat>, enabled: Boolean, revealAll: Boolean): List<FileStat> {
        if (!enabled || revealAll || files.any(FileStat::isFolder)) return files
        val maximumSize = files.maxOfOrNull(FileStat::sizeBytes) ?: return files
        val threshold = (maximumSize / 10L).takeIf { maximumSize >= MINIMUM_LARGE_FILE_BYTES } ?: return files
        return files.filter { it.sizeBytes >= threshold }
    }

    fun hiddenCount(files: List<FileStat>, enabled: Boolean, revealAll: Boolean): Int =
        files.size - filter(files, enabled, revealAll).size

    private const val MINIMUM_LARGE_FILE_BYTES = 5 * 1024 * 1024L
}
