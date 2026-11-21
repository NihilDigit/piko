package dev.piko.ui.screens.drive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.CardBadgeKind
import dev.piko.shared.i18n.PikoLocale
import dev.piko.ui.components.CardTrailingReservedWidth
import dev.piko.ui.components.MediaCardScaffold
import dev.piko.ui.components.PosterCornerData
import dev.piko.ui.components.SkeletonBlock
import dev.piko.ui.components.SkeletonTextLine
import dev.piko.ui.components.contains
import dev.piko.ui.components.detailLineParts
import dev.piko.ui.components.extensionLabel
import dev.piko.ui.components.fileDurationMillis
import dev.piko.ui.components.filePresentation
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.format.formatDurationMillis
import io.github.nihildigit.pikpak.FileStat

/**
 * 海报墙里的一张卡：固定 16:9 封面加标题与详情/上下文行，文件与文件夹共用单一权威 [MediaCardScaffold]。
 */
@Composable
fun PosterCard(
    file: FileStat,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isSpoilerBlurred: Boolean,
    isHighlighted: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onStartSelection: (() -> Unit)? = null,
    onSelectToggle: (Boolean) -> Unit,
    onMoreClick: () -> Unit,
    /** 画不画打开操作面板的更多按钮。宽窗口不画，那里有右键菜单。 */
    moreButton: Boolean,
    /** 列过、确实是空的文件夹：封面里不画纸。不知道空不空时为 false，照常画。 */
    isEmptyFolder: Boolean = false,
    modifier: Modifier = Modifier,
    /** 搜索结果或查重相对路径，或播放历史、最近添加、回收站的结构化上下文附注。 */
    locationLabel: String? = null,
    watchProgress: Float? = null,
    title: String? = null,
    /** 真实名称，[title] 与它不同时在标题下面写一行小字，见 DriveItemName。 */
    original: String? = null,
    tags: List<String> = emptyList(),
    /** 番号芯片，放在封面左下角。 */
    code: String? = null,
    /** 清晰度，放在封面右下角；[tags] 里的同一项不再重复显示。 */
    resolution: String? = null,
    /** 文件夹里直接放着归档条目，见 itemMarks。 */
    folderHasVault: Boolean = false,
    /** 文件夹递归条目数、总时长与总大小，供四角徽标与底部详情行按 [LocalDriveAppearancePrefs] 显示。 */
    folderItemCount: Int? = null,
    folderDurationMillis: Long? = null,
    folderSizeBytes: Long? = null,
    coverOverlay: (@Composable BoxScope.() -> Unit)? = null,
) {
    val gridPrefs = LocalDriveAppearancePrefs.current.grid
    val corners = gridPrefs.corners
    val item = file.filePresentation(
        title = title,
        original = original,
        tags = tags,
        code = code,
        resolution = resolution,
        folderHasVault = folderHasVault,
        emptyFolder = isEmptyFolder,
        locationLabel = locationLabel,
        watchProgress = watchProgress,
    )
    val hasResolutionCorner = corners.contains(CardBadgeKind.RESOLUTION)
    val hasTagsCorner = corners.contains(CardBadgeKind.PARSED_TAGS)
    val cornerTags = remember(tags, resolution, hasResolutionCorner) {
        tags.filter { !hasResolutionCorner || it != resolution }.take(COVER_CORNER_TAGS)
    }
    val durationLabel = remember(file, folderDurationMillis) {
        val ms = if (file.isFolder) folderDurationMillis?.takeIf { it > 0L } else file.fileDurationMillis()
        ms?.let { formatDurationMillis(it) }
    }
    val sizeLabel = remember(file, folderSizeBytes) {
        val bytes = if (file.isFolder) folderSizeBytes?.takeIf { it > 0L } else file.sizeBytes.takeIf { it >= 0L }
        bytes?.toReadableSize()
    }
    val extensionText = remember(file) { if (file.isFolder) null else file.extensionLabel() }
    val badgeData = PosterCornerData(
        duration = durationLabel,
        resolution = resolution?.takeIf { it.isNotBlank() },
        tags = cornerTags,
        code = code,
        fileSize = sizeLabel,
        marks = item.marks,
        extension = extensionText,
    )
    val detailParts = remember(
        file, gridPrefs.showDetailLine, gridPrefs.detailFields, hasTagsCorner, tags,
        folderItemCount, folderDurationMillis, folderSizeBytes, folderHasVault, PikoLocale.effectiveLanguage,
    ) {
        if (gridPrefs.showDetailLine) {
            file.detailLineParts(
                fields = gridPrefs.detailFields,
                tags = tags,
                folderItemCount = folderItemCount,
                folderDurationMillis = folderDurationMillis,
                folderSizeBytes = folderSizeBytes,
                folderHasVault = folderHasVault,
                includeParsedTags = !hasTagsCorner,
            )
        } else {
            emptyList()
        }
    }

    MediaCardScaffold(
        item = item,
        corners = corners,
        badgeData = badgeData,
        detailParts = detailParts,
        folderStyle = gridPrefs.folderStyle,
        showTitle = gridPrefs.showTitle,
        showSecondaryName = true,
        showDetailLine = gridPrefs.showDetailLine,
        alwaysShowLocation = true,
        isSelectionMode = isSelectionMode,
        isSelected = isSelected,
        isSpoilerBlurred = isSpoilerBlurred,
        isHighlighted = isHighlighted,
        onClick = onClick,
        onLongClick = onLongClick,
        onStartSelection = onStartSelection,
        onSelectToggle = onSelectToggle,
        modifier = modifier.fillMaxWidth(),
        coverAspectRatio = COVER_ASPECT,
        titleStyle = MaterialTheme.typography.bodyMedium,
        titleMaxLines = TITLE_LINES,
        footerTopPadding = 6.dp,
        showVideoPlayMark = true,
        scaleCoverOnHover = true,
        alwaysOutlineCover = false,
        moreButton = moreButton,
        onMoreClick = onMoreClick,
        coverOverlay = coverOverlay,
    )
}

/**
 * [PosterCard] 的骨架：同样的 16:9 封面，标题区同高，即两行字的 40dp。右端让出更多按钮的宽度，
 * 与按钮一直显示时（触屏）文字止于同一处。
 */
@Composable
fun PosterCardSkeleton(titleFraction: Float, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(COVER_ASPECT), MaterialTheme.shapes.medium)
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(TITLE_HEIGHT)) {
            Column(
                modifier = Modifier.weight(1f).padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SkeletonTextLine(0.9f)
                SkeletonTextLine(titleFraction)
            }
            Spacer(modifier = Modifier.width(CardTrailingReservedWidth))
        }
    }
}

private const val COVER_ASPECT = 16f / 9f
private const val TITLE_LINES = 2
private const val COVER_CORNER_TAGS = 2
private val TITLE_HEIGHT = 40.dp
