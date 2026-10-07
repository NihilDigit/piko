package dev.piko.ui.screens.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.media.player.SubtitleBrowserState
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.SkeletonBlock
import dev.piko.ui.components.SkeletonGroup
import dev.piko.ui.components.SkeletonText
import dev.piko.ui.components.SkeletonTitleWidths
import io.github.nihildigit.pikpak.FileStat

/**
 * 从网盘挑一个字幕文件。从 [videoFileId] 所在的文件夹开始，先列子文件夹、再列字幕文件，都按名字排；
 * 顶上一行是所在路径，其下是「上一级」。选中即回调，由调用方挂到播放器上。
 *
 * 目录是面板自己列的，不经 [MobilePlayerControls] 的参数传进来：它只在打开这个面板时才要，
 * 播放器本身并不关心网盘目录。
 */
@Composable
internal fun DriveSubtitlePanel(
    videoFileId: String,
    onPick: (FileStat) -> Unit,
    modifier: Modifier = Modifier,
) {
    val services = LocalPikoServices.current
    val scope = rememberCoroutineScope()
    val state = remember(videoFileId) { SubtitleBrowserState(services.driveRepository, scope, videoFileId) }
    // 横屏的侧边面板里返回先回上一级；在最上一层时交给面板自己的返回，收起面板
    BackHandler(enabled = state.path.size > 1) { state.navigateUp() }

    Column(modifier.fillMaxSize()) {
        Text(
            text = state.path.joinToString(" / ") { it.name },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            if (state.path.size > 1) {
                item(key = "up") { PickerRow(icon = Icons.Filled.ArrowUpward, label = "上一级", onClick = { state.navigateUp() }) }
            }
            items(state.folders, key = { "d:" + it.id }) { folder ->
                PickerRow(icon = Icons.Outlined.Folder, label = folder.name, onClick = { state.open(folder) })
            }
            items(state.subtitles, key = { "f:" + it.id }) { file ->
                PickerRow(icon = Icons.Outlined.Subtitles, label = file.name, onClick = { onPick(file) })
            }
            val error = state.loadError
            when {
                state.isLoading -> item(key = "loading") { PickerRowsSkeleton() }
                error != null -> item(key = "error") {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("读取目录失败：$error", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { state.reload() }) { Text("重试") }
                    }
                }
                state.folders.isEmpty() && state.subtitles.isEmpty() -> item(key = "empty") {
                    Text(
                        text = "这个文件夹里没有字幕文件",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            }
        }
    }
}

/**
 * 列目录时的骨架：几行 [PickerRow]，尺寸照抄那一行（最小高 48dp、左右内边距 12dp、20dp 图标、间隔 12dp、一行 bodyLarge）。
 * 「上一级」在加载时照常列在上面，骨架接在它后面，正是文件夹与字幕将出现的位置。
 */
@Composable
private fun PickerRowsSkeleton(rows: Int = 6) {
    SkeletonGroup {
        Column {
            repeat(rows) { index ->
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SkeletonBlock(Modifier.size(20.dp), MaterialTheme.shapes.extraSmall)
                    Spacer(Modifier.width(12.dp))
                    SkeletonText(MaterialTheme.typography.bodyLarge, SkeletonTitleWidths[index % SkeletonTitleWidths.size])
                }
            }
        }
    }
}
