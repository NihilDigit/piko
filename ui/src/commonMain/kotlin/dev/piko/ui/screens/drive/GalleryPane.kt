package dev.piko.ui.screens.drive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoLoadingIndicator
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
    highlightedIds: Set<String> = emptySet(),
    callbacks: DriveItemCallbacks,
    folderName: (String) -> String?,
    rootKey: Any,
    modifier: Modifier = Modifier,
) {
    var visibleIds by remember(rootKey) { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(loading, hasMore, failureCount, files.size, visibleIds) {
        if (!loading && hasMore && failureCount == 0) {
            if (files.isEmpty() || files.takeLast(36).any { it.id in visibleIds }) {
                onRequestMore()
            }
        }
    }
    Column(modifier) {
        if (showViewSwitcher || failureCount > 0) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (failureCount > 0 && !loading) {
                    Text(
                        "有目录读取失败",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRetry) { Text("重试") }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (showViewSwitcher) ViewSwitcher(viewMode, onViewModeChange, tileSize, onTileSizeChange, false, null, galleryShown = true)
            }
        }
        key(rootKey) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!loading || files.isNotEmpty()) {
                    GalleryCanvas(
                        files = files,
                        tileSize = tileSize,
                        isBlurred = isBlurred,
                        callbacks = callbacks,
                        modifier = Modifier.fillMaxSize(),
                        isSelectionMode = isSelectionMode,
                        selectedIds = selectedIds,
                        highlightedIds = highlightedIds,
                        onZoom = onZoom,
                        locationLabel = { folderName(it.parentId) },
                        onVisibleFilesChanged = { ids ->
                            visibleIds = ids
                            onVisibleFilesChanged(ids)
                        },
                    )
                }
                if (loading && files.isEmpty()) {
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        PikoLoadingIndicator(size = 56.dp)
                        Text(
                            "正在准备",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else if (files.isEmpty() && complete && failureCount == 0) {
                    PikoEmptyState(
                        title = "此范围内没有可浏览的视频",
                        icon = Icons.Outlined.PhotoLibrary,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
}
