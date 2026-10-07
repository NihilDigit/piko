package dev.piko.ui.screens.drive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import dev.piko.data.repository.isPlayableVideo
import dev.piko.shared.data.FolderUsage
import dev.piko.shared.data.isDriveFolderId
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
 * 属性看的是哪几项：一项、几项，或者没指着条目时的当前目录。在打开的那一刻取定，之后选中怎么变都不跟，照资源管理器。
 */
internal sealed interface PropertiesTarget {
    class Single(val file: FileStat, val tags: List<String>, val location: String?, val isBlurred: Boolean) : PropertiesTarget

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
    FitWidthColumn(
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
                .layoutId(FollowsWidth)
                .aspectRatio(16f / 9f)
                .clip(MaterialTheme.shapes.medium),
        ) {
            SpoilerThumbnail(model = file.thumbnailLink, isBlurred = target.isBlurred, blur = PosterSpoilerBlur, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(16.dp))
        FullName(file)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            FileTypeIcon(file = file, iconSize = 32.dp, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            FullName(file)
        }
    }
    if (target.tags.isNotEmpty()) {
        MediaTagRow(tags = target.tags, modifier = Modifier.layoutId(FollowsWidth).padding(top = 8.dp))
    }
    Spacer(Modifier.height(16.dp))
    val usage = folderUsage(file)
    PropertyTable(
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
    PropertyTable(
        buildList {
            add("项目" to listOfNotNull(folders.takeIf { it > 0 }?.let { "$it 个文件夹" }, others.takeIf { it > 0 }?.let { "$it 个文件" }).joinToString("、").ifEmpty { "空" })
            // 只加文件：文件夹的大小要递归统计，选中一批时不替每个都跑一遍
            if (others > 0) add("文件合计" to files.filterNot { it.isFolder }.sumOf { it.sizeBytes }.toReadableSize())
            location?.takeIf { it.isNotEmpty() }?.let { add("位置" to it) }
        },
    )
}

// 全名可以选中复制：列表里显示的是解析后的短标题。长名字换行，不截断
@Composable
private fun FullName(file: FileStat) {
    SelectionContainer {
        Text(file.name, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * 标签与值两列。标签列取最长的那个标签的宽度，各行的值左沿对齐；值放不下就换行，位置、哈希都要看全。
 */
@Composable
private fun PropertyTable(rows: List<Pair<String, String>>) {
    val colors = MaterialTheme.colorScheme
    Layout(
        content = {
            for ((label, value) in rows) {
                Text(text = label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                SelectionContainer {
                    Text(text = value, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
    ) { measurables, constraints ->
        val labels = measurables.filterIndexed { index, _ -> index % 2 == 0 }.map { it.measure(Constraints()) }
        val labelWidth = labels.maxOfOrNull { it.width } ?: 0
        val gap = PropertyLabelGap.roundToPx()
        val valueMax = if (constraints.hasBoundedWidth) (constraints.maxWidth - labelWidth - gap).coerceAtLeast(0) else Constraints.Infinity
        val values = measurables.filterIndexed { index, _ -> index % 2 == 1 }.map { it.measure(Constraints(maxWidth = valueMax)) }
        val rowGap = PropertyRowGap.roundToPx()
        val rowHeights = labels.indices.map { maxOf(labels[it].height, values[it].height) }
        val width = (labelWidth + gap + (values.maxOfOrNull { it.width } ?: 0)).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = rowHeights.sum() + rowGap * (rowHeights.size - 1).coerceAtLeast(0)
        layout(width, height) {
            var y = 0
            labels.indices.forEach { row ->
                labels[row].place(0, y)
                values[row].place(labelWidth + gap, y)
                y += rowHeights[row] + rowGap
            }
        }
    }
}

/**
 * 竖排，宽度由文字一类的子项定，标了 [FollowsWidth] 的子项（预览图、标签行）再按这个宽度量：它们自己会填满给多少占多少，
 * 先量的话卡片总被撑到上限。不用 IntrinsicSize：缩略图与选中容器的固有尺寸靠不住。
 */
@Composable
private fun FitWidthColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        val placeables = arrayOfNulls<Placeable>(measurables.size)
        measurables.forEachIndexed { index, measurable ->
            if (measurable.layoutId != FollowsWidth) placeables[index] = measurable.measure(loose)
        }
        val width = maxOf(constraints.minWidth, placeables.maxOf { it?.width ?: 0 })
        measurables.forEachIndexed { index, measurable ->
            if (measurable.layoutId == FollowsWidth) {
                placeables[index] = measurable.measure(Constraints(minWidth = width, maxWidth = width))
            }
        }
        val laid = placeables.map { it!! }
        layout(width, laid.sumOf { it.height }) {
            var y = 0
            laid.forEach {
                it.place(0, y)
                y += it.height
            }
        }
    }
}

private const val FollowsWidth = "followsWidth"

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

private val PropertyLabelGap = 16.dp
private val PropertyRowGap = 8.dp

/** 右键菜单与操作面板末尾的「属性」，单独成组。组号只用来与别的组分开，取一个各处都不会用到的。 */
internal fun propertiesAction(onClick: () -> Unit) = SheetAction(Icons.Outlined.Info, "属性", onClick, group = 100)

/**
 * 属性卡片放在哪要用到的几样位置，都在列表这一块（[propertiesAnchorArea] 挂的那一层）的坐标里。
 * 不进快照：右键按下的点与各条目的位置随点击、滚动一直在变，只在打开属性的那一刻读一次，不必为它们重组。
 */
internal class PropertiesAnchors {
    private var area: LayoutCoordinates? = null
    private var press: Offset? = null
    // 条目的布局坐标是活的，滚动后读到的仍是眼下的位置；换目录时清掉
    private val items = HashMap<String, LayoutCoordinates>()

    fun placed(id: String, coordinates: LayoutCoordinates) {
        items[id] = coordinates
    }

    fun forgetItems() = items.clear()

    fun forgetPress() {
        press = null
    }

    /** 右键菜单里点的「属性」：放在右键按下的那一点，用过即忘；没有那一点（操作面板里点的）就贴着条目。 */
    fun fromMenu(id: String): PanelAnchor {
        val point = press ?: return beside(id)
        press = null
        return with(density) { PanelAnchor.AtPoint(DpOffset(point.x.toDp(), point.y.toDp())) }
    }

    /** 贴着这一项；它已滚出视野、量不到时放在中央偏上。 */
    fun beside(id: String): PanelAnchor {
        val area = area?.takeIf { it.isAttached } ?: return PanelAnchor.Centered
        val item = items[id]?.takeIf { it.isAttached } ?: return PanelAnchor.Centered
        val box = area.localBoundingBoxOf(item, clipBounds = false)
        return with(density) { PanelAnchor.Beside(DpRect(box.left.toDp(), box.top.toDp(), box.right.toDp(), box.bottom.toDp())) }
    }

    private var density: Density = Density(1f)

    fun attach(coordinates: LayoutCoordinates, density: Density) {
        area = coordinates
        this.density = density
    }

    fun pressed(at: Offset) {
        press = at
    }
}

/** 挂在列表这一块上：记下它的坐标与右键按下的位置，见 [PropertiesAnchors]。只看不吃，事件照常往下走。 */
internal fun Modifier.propertiesAnchorArea(anchors: PropertiesAnchors): Modifier = composed {
    val density = LocalDensity.current
    onPlaced { anchors.attach(it, density) }
        .pointerInput(anchors) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                        event.changes.firstOrNull()?.let { anchors.pressed(it.position) }
                    }
                }
            }
        }
}

/** 属性卡片的宽度随内容，夹在这两者之间，照 Windows 的属性窗：不给改大小。 */
internal val PropertiesMinWidth = 280.dp
internal val PropertiesMaxWidth = 440.dp
