package dev.piko.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.unit.dp
import dev.piko.shared.state.InstantSheetState
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.PikoPlatform
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.PikoTheme
import java.awt.datatransfer.DataFlavor
import java.io.File

/**
 * 拖进来的磁力链接或 .torrent 文件交给秒传面板，与协议唤起走同一个入口。种子文件在本地
 * 换算成磁力链接，见 [TorrentMagnet]。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MagnetDropTarget(
    platform: PikoPlatform,
    appearance: Appearance,
    onMagnet: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    var isHovering by remember { mutableStateOf(false) }
    val target = remember(onMagnet) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                isHovering = true
            }

            override fun onExited(event: DragAndDropEvent) {
                isHovering = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                isHovering = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                isHovering = false
                val magnet = magnetIn(event) ?: return false
                onMagnet(magnet)
                return true
            }
        }
    }

    // 主题与平台在 PikoApp 里面提供，覆盖层在它外面，要自己再套一层
    CompositionLocalProvider(LocalPikoPlatform provides platform) {
        Box(
            Modifier.fillMaxSize().dragAndDropTarget(
                // 拖动途中只看数据类型，内容到松手时才读
                shouldStartDragAndDrop = { event ->
                    event.awtTransferable.isDataFlavorSupported(DataFlavor.stringFlavor) ||
                        event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                },
                target = target,
            ),
        ) {
            content()
            PikoTheme(appearance = appearance) {
                AnimatedVisibility(visible = isHovering, enter = fadeIn(), exit = fadeOut()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                            .padding(24.dp)
                            .border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraLarge),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "松开以解析磁力链接或种子",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun magnetIn(event: DragAndDropEvent): String? {
    val transferable = event.awtTransferable
    if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
        val files = runCatching { transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*> }.getOrNull()
        return files.orEmpty().filterIsInstance<File>()
            .filter { it.extension.equals("torrent", ignoreCase = true) }
            .firstNotNullOfOrNull(TorrentMagnet::fromFile)
    }
    val text = runCatching { transferable.getTransferData(DataFlavor.stringFlavor) as? String }.getOrNull() ?: return null
    // 浏览器拖链接时可能带上标题或多行，逐行找第一条像磁力链的
    return text.lineSequence().firstNotNullOfOrNull { InstantSheetState.normalizeMagnet(it) }
}
