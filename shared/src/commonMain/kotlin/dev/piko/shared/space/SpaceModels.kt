package dev.piko.shared.space

import dev.piko.shared.data.PikoPathBreadcrumb

data class SpacePreviewBlock(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val isVaulted: Boolean,
    val bytes: Long,
    val physicalBytes: Long = if (isVaulted) 0L else bytes,
    val vaultedBytes: Long = if (isVaulted) bytes else 0L,
    val fileCount: Long = if (isFolder) 0L else 1L,
)

data class SpaceNodeItem(
    val id: String,
    val parentId: String,
    val name: String,
    val isFolder: Boolean,
    val isVaulted: Boolean,
    val physicalBytes: Long,
    val vaultedBytes: Long,
    val fileCount: Long,
    val durationMillis: Long,
    val isComplete: Boolean,
    val previewChildren: List<SpacePreviewBlock> = emptyList(),
) {
    fun bytes(includeVaulted: Boolean): Long =
        if (includeVaulted) physicalBytes + vaultedBytes else physicalBytes
}

data class SpaceFolderSnapshot(
    val folderId: String,
    val breadcrumbs: List<PikoPathBreadcrumb>,
    val physicalBytes: Long,
    val vaultedBytes: Long,
    val fileCount: Long,
    val isTreeComplete: Boolean,
    val isTreeBuilding: Boolean,
    val items: List<SpaceNodeItem>,
    val syncError: String? = null,
)
