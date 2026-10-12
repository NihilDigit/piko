package dev.piko.ui.screens.drive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.nihildigit.pikpak.FileStat

@Composable
internal fun GalleryPane(
    files: List<FileStat>,
    tileSize: TileSize,
    onTileSizeChange: (TileSize) -> Unit,
    viewMode: DriveViewMode,
    showViewSwitcher: Boolean,
    onViewModeChange: (DriveViewMode) -> Unit,
    onZoom: (Boolean) -> Unit,
    loading: Boolean,
    complete: Boolean,
    hasMore: Boolean,
    failureCount: Int,
    onRetry: () -> Unit,
    onRequestMore: () -> Unit,
    onVisibleFilesChanged: (Set<String>) -> Unit,
    isBlurred: (FileStat) -> Boolean,
    isSelectionMode: Boolean,
    selectedIds: Set<String>,
    callbacks: DriveItemCallbacks,
    folderName: (String) -> String?,
    rootKey: Any,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                when {
                    failureCount > 0 -> "${files.size} 个视频 · $failureCount 个目录读取失败"
                    loading -> "${files.size} 个视频 · 正在查找"
                    files.isEmpty() && complete -> "没有视频"
                    else -> "${files.size} 个视频"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (failureCount > 0) TextButton(onClick = onRetry, enabled = !loading) { Text("重试") }
            if (hasMore && !loading) TextButton(onClick = onRequestMore) { Text("继续查找") }
            if (showViewSwitcher) ViewSwitcher(viewMode, onViewModeChange, tileSize, onTileSizeChange, false, null)
        }
        key(rootKey) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                GalleryCanvas(
                    files = files,
                    tileSize = tileSize,
                    isBlurred = isBlurred,
                    callbacks = callbacks,
                    modifier = Modifier.fillMaxSize(),
                    isSelectionMode = isSelectionMode,
                    selectedIds = selectedIds,
                    onZoom = onZoom,
                    locationLabel = { folderName(it.parentId) },
                    onVisibleFilesChanged = onVisibleFilesChanged,
                )
                if (files.isEmpty() && complete && failureCount == 0) {
                    Text(
                        "此范围内没有可浏览的视频",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
