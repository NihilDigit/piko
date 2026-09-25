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
import dev.piko.shared.state.extractLinks
import dev.piko.shared.upload.UploadSelection
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.PikoPlatform
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.PikoTheme
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File

/**
 * 拖进来的磁力链接或 .torrent 文件交给秒传面板，与协议唤起走同一个入口。种子文件在本地
 * 换算成磁力链接，见 [TorrentMagnet]。其余本机文件与文件夹交给 [onUpload]。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MagnetDropTarget(
    platform: PikoPlatform,
    appearance: Appearance,
    onMagnet: (String) -> Unit,
    onUpload: (UploadSelection) -> Unit,
    content: @Composable () -> Unit,
) {
    var isHovering by remember { mutableStateOf(false) }
    val target = remember(onMagnet, onUpload) {
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
                when (val dropped = droppedIn(event)) {
                    is Dropped.Magnet -> onMagnet(dropped.text)
                    is Dropped.Upload -> onUpload(dropped.selection)
                    null -> return false
                }
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
                            "松开以上传文件，或打开链接与种子",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

private sealed interface Dropped {
    /** 交给添加链接面板的文本，可含多条磁力链接。 */
    data class Magnet(val text: String) : Dropped

    data class Upload(val selection: UploadSelection) : Dropped
}

@OptIn(ExperimentalComposeUiApi::class)
private fun droppedIn(event: DragAndDropEvent): Dropped? {
    val transferable = event.awtTransferable
    if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
        val files = runCatching { transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*> }.getOrNull()
        return filesDropped(files.orEmpty().filterIsInstance<File>().filter { it.exists() })
    }
    return magnetIn(transferable)?.let(Dropped::Magnet)
}

/**
 * 全是种子时才按种子打开，多个种子换成多条磁力链接，面板列成批量清单。混着别的文件时
 * 种子也当普通文件上传：一次拖进一批文件，用意是搬运，种子只是其中一个；同时弹出秒传面板与
 * 上传确认会叠在一起。
 */
private fun filesDropped(files: List<File>): Dropped? {
    if (files.isEmpty()) return null
    if (files.all { it.isFile && it.extension.equals("torrent", ignoreCase = true) }) {
        val magnets = files.mapNotNull(TorrentMagnet::fromFile)
        return magnets.takeIf { it.isNotEmpty() }?.let { Dropped.Magnet(it.joinToString("\n")) }
    }
    val (folders, plainFiles) = files.partition { it.isDirectory }
    return Dropped.Upload(UploadSelection(files = plainFiles.map { it.absolutePath }, folders = folders.map { it.absolutePath }))
}

private fun magnetIn(transferable: Transferable): String? {
    val text = runCatching { transferable.getTransferData(DataFlavor.stringFlavor) as? String }.getOrNull() ?: return null
    // 分享链接连同整段文本交出去：提取码常写在链接后面，面板从同一段里认出来
    if (InstantSheetState.findShareLink(text) != null) return text.trim()
    // 浏览器拖链接时可能带上标题或多行。只取磁力链，多条时一并交出去，面板列成批量清单
    val magnets = extractLinks(text).filter { it.isMagnet }
    return magnets.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.uri }
}
