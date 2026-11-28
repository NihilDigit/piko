package dev.piko.shared.data

import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFolder
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.createFolder
import io.github.nihildigit.pikpak.listFiles
import kotlinx.coroutines.CancellationException

private const val FOLDER_CLAIM_TAG = "FolderClaim"

internal fun Throwable.isPikPakDuplicateNameError(): Boolean {
    val ex = this as? PikPakException ?: return false
    if (ex.httpStatus != 400 && ex.httpStatus != 409) return false
    return ex.errorMessage == "file_duplicated_name" ||
        ex.rawBody?.contains("file_duplicated_name") == true ||
        ex.message?.contains("file_duplicated_name") == true
}

/**
 * Creates a folder under [parentId], or claims an existing active folder with the same [name]
 * when PikPak rejects creation with `file_duplicated_name` (for example after a timed-out
 * earlier `createFolder` whose response was lost).
 *
 * @return Pair of `(folderId, wasFreshlyCreated)`.
 */
internal suspend fun PikPakClient.createOrClaimFolder(
    parentId: String,
    name: String,
): Pair<String, Boolean> {
    try {
        val created = createFolder(parentId, name)
        if (created.isNotBlank()) return created to true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        if (!error.isPikPakDuplicateNameError()) throw error
        val existing = listFiles(parentId)
            .filter { it.isFolder && !it.trashed && it.name == name && it.id.isNotBlank() }
        val adopted = existing.maxByOrNull { it.modifiedTime } ?: existing.firstOrNull()
        if (adopted != null) {
            PikoLog.i(
                FOLDER_CLAIM_TAG,
                "Claimed existing folder ${logFolder(adopted.id)} in parent=${logFolder(parentId)} after duplicate-name rejection",
            )
            return adopted.id to false
        }
        throw error
    }
    throw IllegalStateException("Folder creation returned empty ID")
}

internal fun pickCollisionFreeFileName(desiredName: String, existingNames: Set<String>): String {
    if (desiredName !in existingNames) return desiredName
    val dotIndex = desiredName.lastIndexOf('.')
    val hasExt = dotIndex > 0 && dotIndex < desiredName.length - 1
    val stem = if (hasExt) desiredName.substring(0, dotIndex) else desiredName
    val ext = if (hasExt) desiredName.substring(dotIndex) else ""
    var counter = 1
    while (true) {
        val candidate = "$stem ($counter)$ext"
        if (candidate !in existingNames) return candidate
        counter++
    }
}

internal fun findClaimableTargetFile(
    candidates: MutableList<FileStat>,
    desiredName: String,
    expectedSize: Long,
    expectedGcid: String?,
): FileStat? {
    val gcid = expectedGcid?.takeIf { it.isNotBlank() } ?: return null
    val index = candidates.indexOfFirst { stat ->
        !stat.isFolder &&
            !stat.trashed &&
            stat.name == desiredName &&
            stat.sizeBytes == expectedSize &&
            stat.hash.equals(gcid, ignoreCase = true)
    }
    return if (index >= 0) candidates.removeAt(index) else null
}
