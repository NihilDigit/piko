package dev.piko.ui.screens.drive

import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.piko.data.repository.isPlayableVideo
import dev.piko.shared.data.FolderUsage
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.FileTypeIcon
import dev.piko.ui.components.MediaTagRow
import dev.piko.ui.components.PosterSpoilerBlur
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.SpoilerThumbnail
import dev.piko.ui.components.toReadableSize
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CancellationException

/**
 * 详情栏眼下看的是什么：选中的几项、焦点所在的一项，或者什么也没指着时的当前目录。
 */
internal sealed interface InspectorTarget {
    class Single(val file: FileStat, val tags: List<String>, val location: String?, val isBlurred: Boolean) : InspectorTarget

    class Selection(val files: List<FileStat>) : InspectorTarget

    class Folder(val name: String, val files: List<FileStat>) : InspectorTarget
}

/**
 * 宽窗口网盘页右侧的详情栏，资源管理器的详细信息窗格、Finder 的检查器：看一项的全名、属性与来源，
 * 不必弹出操作面板。操作与右键菜单是同一份（[actions]），面板留给触屏。
 *
 * 以后刮削到的作品信息（海报、简介、季与集的对应、改匹配）放在预览与属性之间，属性表与操作不动。
 */
@Composable
internal fun InspectorPane(
    target: InspectorTarget,
    actions: List<SheetAction>,
    modifier: Modifier = Modifier,
    /** 一项时最常做的那件事（播放、打开、下载），放在名字下面，与在列表里点它相同。 */
    primaryAction: SheetAction? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
    ) {
        when (target) {
            is InspectorTarget.Single -> SingleDetails(target, primaryAction)
            is InspectorTarget.Selection -> SummaryDetails(title = "已选择 ${target.files.size} 项", files = target.files, hint = null)
            is InspectorTarget.Folder -> SummaryDetails(
                title = target.name,
                files = target.files,
                hint = "勾选一项或用方向键指着一项，这里显示它的详情",
            )
        }
        if (actions.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            val (regular, destructive) = actions.partition { !it.destructive }
            ActionGroup(regular)
            if (destructive.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                ActionGroup(destructive)
            }
        }
    }
}

@Composable
private fun SingleDetails(target: InspectorTarget.Single, primaryAction: SheetAction?) {
    val file = target.file
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(MaterialTheme.shapes.medium),
    ) {
        if (file.thumbnailLink.isNotEmpty()) {
            SpoilerThumbnail(model = file.thumbnailLink, isBlurred = target.isBlurred, blur = PosterSpoilerBlur, modifier = Modifier.fillMaxSize())
        } else {
            FileTypeIcon(file = file, iconSize = 48.dp, modifier = Modifier.fillMaxSize())
        }
    }
    Spacer(Modifier.height(16.dp))
    // 全名可以选中复制：列表里显示的是解析后的短标题
    SelectionContainer {
        Text(file.name, style = MaterialTheme.typography.titleMedium)
    }
    if (target.tags.isNotEmpty()) {
        MediaTagRow(tags = target.tags, modifier = Modifier.padding(top = 8.dp))
    }
    if (primaryAction != null) {
        Button(onClick = primaryAction.onClick, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Icon(primaryAction.icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(primaryAction.label)
        }
    }
    Spacer(Modifier.height(16.dp))
    val usage = folderUsage(file)
    PropertyTable(
        buildList {
            add("类型" to (if (file.isFolder) "文件夹" else file.name.substringAfterLast('.', "").uppercase().ifEmpty { "文件" }))
            if (file.isFolder) {
                add("内容" to usage)
            } else {
                add("大小" to file.sizeBytes.toReadableSize())
            }
            if (file.isPlayableVideo()) {
                file.params["duration"]?.toDoubleOrNull()?.let { add("时长" to formatDuration(it.toLong())) }
                val width = file.params["width"]
                val height = file.params["height"]
                if (!width.isNullOrEmpty() && !height.isNullOrEmpty()) add("分辨率" to "$width × $height")
            }
            timestamp(file.modifiedTime)?.let { add("修改时间" to it) }
            timestamp(file.createdTime)?.takeIf { it != timestamp(file.modifiedTime) }?.let { add("创建时间" to it) }
            target.location?.takeIf { it.isNotEmpty() }?.let { add("位置" to it) }
            file.source?.let { add("来源" to it.label.removePrefix("来源：")) }
        },
    )
}

@Composable
private fun SummaryDetails(title: String, files: List<FileStat>, hint: String?) {
    val folders = files.count { it.isFolder }
    val others = files.size - folders
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Spacer(Modifier.height(16.dp))
    PropertyTable(
        buildList {
            add("项目" to listOfNotNull(folders.takeIf { it > 0 }?.let { "$it 个文件夹" }, others.takeIf { it > 0 }?.let { "$it 个文件" }).joinToString("、").ifEmpty { "空" })
            // 只加文件：文件夹的大小要递归统计，选中一批时不替每个都跑一遍
            if (others > 0) add("文件合计" to files.filterNot { it.isFolder }.sumOf { it.sizeBytes }.toReadableSize())
        },
    )
    if (hint != null) {
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

/** 标签与值两列，标签列定宽，值可以换行。 */
@Composable
private fun PropertyTable(rows: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, value) in rows) {
            Row {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(PropertyLabelWidth),
                )
                SelectionContainer {
                    Text(text = value, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ActionGroup(actions: List<SheetAction>) {
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        actions.forEachIndexed { index, action ->
            val color = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            SegmentedListItem(
                onClick = action.onClick,
                shapes = ListItemDefaults.segmentedShapes(index = index, count = actions.size),
                colors = ListItemDefaults.segmentedColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = color,
                    leadingContentColor = if (action.destructive) color else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                leadingContent = { Icon(action.icon, contentDescription = null) },
                content = { Text(action.label) },
            )
        }
    }
}

/** 文件夹的递归统计，逐步长上去；与操作面板用的是同一个统计，换了文件夹就重新数。 */
@Composable
private fun folderUsage(file: FileStat): String {
    if (!file.isFolder) return ""
    val driveRepo = LocalPikoServices.current.driveRepository
    val flow = remember(file.id) { driveRepo.folderUsage(file.id) }
    val usage by produceState<FolderUsage?>(null, flow) {
        try {
            flow.collect { value = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            value = null
        }
    }
    val counted = usage ?: return "统计中"
    val text = "${counted.fileCount} 个文件，${counted.bytes.toReadableSize()}"
    return when (counted.progress) {
        FolderUsage.Progress.COUNTING -> "$text（统计中）"
        FolderUsage.Progress.COMPLETE -> text
        FolderUsage.Progress.TRUNCATED -> "至少 $text"
    }
}

// ISO 8601 取到分钟：2024-05-01T12:34:56.789+08:00 -> 2024-05-01 12:34
private fun timestamp(iso: String): String? = iso.takeIf { it.isNotEmpty() }?.take(16)?.replace('T', ' ')

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}" else "$m:${s.toString().padStart(2, '0')}"
}

private val PropertyLabelWidth = 72.dp

/** 详情栏的默认宽度与下限，拖宽后存进偏好 inspectorPanelFlow。 */
internal val InspectorDefaultWidth = 320.dp
internal val InspectorMinWidth = 280.dp
