package dev.piko.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.nihildigit.pikpak.FileStat

/** 一次看图请求。[images] 是同上下文可连续翻看的图片，桌面端在独立看图窗口中展示。 */
@Immutable
class ImageViewerRequest(
    val images: List<FileStat>,
    val initialIndex: Int = 0,
    val onLocate: ((FileStat) -> Unit)? = null,
)

val LocalDetachedImageViewer = staticCompositionLocalOf<((ImageViewerRequest) -> Unit)?> { null }

val LocalInAppImageViewer = staticCompositionLocalOf<(@Composable (images: List<FileStat>, initialIndex: Int, onDismiss: () -> Unit) -> Unit)?> { null }
