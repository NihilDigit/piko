package dev.piko.ui.screens.drive

import dev.piko.ui.components.DetailHeading

import dev.piko.ui.components.DetailFields
import dev.piko.ui.components.FitDetailWidthColumn
import dev.piko.ui.components.DetailFollowsWidth


import dev.piko.ui.components.FloatingPanel

import androidx.compose.foundation.layout.Box
import dev.piko.shared.state.DriveItemName
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.dp
import dev.piko.data.repository.isPlayableVideo
import dev.piko.shared.data.FolderUsage
import dev.piko.shared.data.isDriveFolderId
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.FileTypeIcon
import dev.piko.ui.components.MediaTagRow
import dev.piko.ui.components.PosterSpoilerBlur
import dev.piko.ui.components.SpoilerThumbnail
import dev.piko.ui.components.toReadableSize
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CancellationException

/**
 * 属性看的是哪几项：一项、几项，或者没指着条目时的当前目录。在打开的那一刻取定，之后选中怎么变都不跟，照资源管理器。
 */
internal sealed interface PropertiesTarget {
    class Single(val name: DriveItemName, val tags: List<String>, val location: String?, val isBlurred: Boolean) : PropertiesTarget {
        val file: FileStat get() = name.file
    }

    class Selection(val files: List<FileStat>, val location: String?) : PropertiesTarget

    class Folder(val name: String, val files: List<FileStat>, val location: String?) : PropertiesTarget
}

/**
 * 条目的属性，资源管理器的「属性」、Finder 的「显示简介」：看一项的全名、属性与来源，几项时是合计。
 * 宽窗口装在浮动卡片里（[FloatingPanel]），窄窗口装在面板里。只看不改，操作在右键菜单与操作面板。
 *
 * 宽度随内容：名字、属性值这些文字定下宽度（卡片另有上下限），预览图与标签行随它，不反过来把卡片撑到最宽，
 * 见 [FitWidthColumn]。没有缩略图的文件与文件夹不留预览的空框，图标与名字排成一行。
 *
 * 曾是网盘页常驻的详情栏，跟着选中走：先是推开列表的侧栏，开合时网格重排，又与信息流抢右侧那一栏；
 * 改成浮窗后仍挡着列表，而要看的往往只是某一项的几行属性，常驻不值得。
 *
 * 以后刮削到的作品信息（海报、简介、季与集的对应、改匹配）放在预览与属性表之间。
 */
@Composable
internal fun PropertiesPane(target: PropertiesTarget, modifier: Modifier = Modifier) {
    FitDetailWidthColumn(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
    ) {
        when (target) {
            is PropertiesTarget.Single -> SingleDetails(target)
            is PropertiesTarget.Selection -> SummaryDetails("已选择 ${target.files.size} 项", target.files, target.location)
            is PropertiesTarget.Folder -> SummaryDetails(target.name, target.files, target.location)
        }
    }
}

@Composable
private fun SingleDetails(target: PropertiesTarget.Single) {
    val file = target.file
    if (file.thumbnailLink.isNotEmpty()) {
        Box(
            modifier = Modifier
                .layoutId(DetailFollowsWidth)
                .aspectRatio(16f / 9f)
                .clip(MaterialTheme.shapes.medium),
        ) {
            SpoilerThumbnail(model = file.thumbnailLink, isBlurred = target.isBlurred, blur = PosterSpoilerBlur, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(16.dp))
        FullName(target.name)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            FileTypeIcon(file = file, iconSize = 32.dp, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            FullName(target.name)
        }
    }
    if (target.tags.isNotEmpty()) {
        MediaTagRow(tags = target.tags, modifier = Modifier.layoutId(DetailFollowsWidth).padding(top = 8.dp))
    }
    Spacer(Modifier.height(16.dp))
    val usage = folderUsage(file)
    DetailFields(
        buildList {
            add("类型" to (if (file.isFolder) "文件夹" else file.name.substringAfterLast('.', "").uppercase().ifEmpty { "文件" }))
            if (file.isFolder) {
                if (usage.isNotEmpty()) add("内容" to usage)
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
            // gcid，内容哈希：判断两份是不是同一个文件、秒传能否命中都看它
            if (!file.isFolder) file.hash.takeIf { it.isNotEmpty() }?.let { add("哈希" to it) }
        },
    )
}

@Composable
private fun SummaryDetails(title: String, files: List<FileStat>, location: String?) {
    val folders = files.count { it.isFolder }
    val others = files.size - folders
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
    Spacer(Modifier.height(12.dp))
    DetailFields(
        buildList {
            add("项目" to listOfNotNull(folders.takeIf { it > 0 }?.let { "$it 个文件夹" }, others.takeIf { it > 0 }?.let { "$it 个文件" }).joinToString("、").ifEmpty { "空" })
            // 只加文件：文件夹的大小要递归统计，选中一批时不替每个都跑一遍
            if (others > 0) add("文件合计" to files.filterNot { it.isFolder }.sumOf { it.sizeBytes }.toReadableSize())
            location?.takeIf { it.isNotEmpty() }?.let { add("位置" to it) }
        },
    )
}

// 与卡片上同一个名字，真实名称在它下面；都可以选中复制，长名字换行，不截断
@Composable
private fun FullName(name: DriveItemName) {
    DetailHeading(name.headingText, name.headingOriginal)
}

/** 文件夹的递归统计，逐步长上去；与操作面板用的是同一个统计，换了文件夹就重新数。库与压缩包里的文件夹数不了，为空。 */
@Composable
private fun folderUsage(file: FileStat): String {
    if (!file.isFolder || !isDriveFolderId(file.id)) return ""
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


/** 属性卡片的宽度随内容，夹在这两者之间，照 Windows 的属性窗：不给改大小。 */
internal val PropertiesMinWidth = 280.dp
internal val PropertiesMaxWidth = 440.dp
internal val FilePropertiesPanelKey = dev.piko.ui.components.DetailPanelKey<PropertiesTarget>(
    dev.piko.ui.components.DetailPanelSpec("属性", "关闭属性", PropertiesMinWidth, PropertiesMaxWidth),
)
