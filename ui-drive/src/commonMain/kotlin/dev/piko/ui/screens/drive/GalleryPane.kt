package dev.piko.ui.screens.drive

import dev.piko.shared.i18n.tr

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
    showImages: Boolean = true,
    onShowImagesChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val latestFiles by rememberUpdatedState(files)
    val latestLoading by rememberUpdatedState(loading)
    val latestHasMore by rememberUpdatedState(hasMore)
    val latestFailureCount by rememberUpdatedState(failureCount)
    val latestRequestMore by rememberUpdatedState(onRequestMore)
    fun checkRequestMore(visibleIds: Set<String>) {
        if (!latestLoading && latestHasMore && latestFailureCount == 0) {
            val current = latestFiles
            if (current.isEmpty() || current.takeLast(36).any { it.id in visibleIds }) {
                latestRequestMore()
            }
        }
    }
    LaunchedEffect(loading, hasMore, failureCount, files.size) {
        if (!loading && hasMore && failureCount == 0 && files.isEmpty()) {
            onRequestMore()
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
                        stringResource(Res.string.text_some_folders_could_not_be_read),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRetry) { Text(stringResource(Res.string.common_retry)) }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (showViewSwitcher) ViewSwitcher(
                    viewMode = viewMode,
                    onViewModeChange = onViewModeChange,
                    tileSize = tileSize,
                    onTileSizeChange = onTileSizeChange,
                    feedShown = false,
                    onFeedShownChange = null,
                    galleryShown = true,
                    galleryShowImages = showImages,
                    onGalleryShowImagesChange = onShowImagesChange,
                )
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
                            checkRequestMore(ids)
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
                            stringResource(Res.string.text_preparing),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else if (files.isEmpty() && complete && failureCount == 0) {
                    PikoEmptyState(
                        title = if (showImages) {
                            tr("此范围内没有可浏览的视频或图片", "No videos or images to browse in this scope", "この範囲に閲覧できる動画や画像はありません", "이 범위에 볼 수 있는 동영상이나 이미지가 없습니다", "此範圍內沒有可瀏覽的影片或圖片")
                        } else {
                            tr("此范围内没有可浏览的视频", "No videos to browse in this scope", "この範囲に閲覧できる動画はありません", "이 범위에 볼 수 있는 동영상이 없습니다", "此範圍內沒有可瀏覽的影片")
                        },
                        icon = Icons.Outlined.PhotoLibrary,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
}
