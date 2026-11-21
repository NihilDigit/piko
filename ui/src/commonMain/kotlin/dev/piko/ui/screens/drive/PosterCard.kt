package dev.piko.ui.screens.drive

import dev.piko.shared.i18n.tr

import dev.piko.ui.components.itemMarks
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import dev.piko.ui.theme.LocalFixedColors
import dev.piko.ui.theme.LocalPikoMotion
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.getValue
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.CardBadgeKind
import dev.piko.shared.data.CornerSlots
import dev.piko.shared.data.FolderStyle
import dev.piko.shared.i18n.PikoLocale
import dev.piko.ui.components.focusIndication
import dev.piko.ui.components.ownsClicks
import dev.piko.ui.components.locateHighlight
import dev.piko.ui.components.MediaTag
import dev.piko.ui.components.MediaTagRow
import dev.piko.ui.components.SkeletonBlock
import dev.piko.ui.components.SkeletonTextLine
import dev.piko.ui.format.formatDurationMillis
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.isEInk
import dev.piko.ui.theme.tabular
import io.github.nihildigit.pikpak.FileStat
import dev.piko.ui.components.*

/**
 * 卡片整体的点击语义。多选时整张卡是一个复选项；单击打开，长按及无障碍长按打开操作面板。
 * 与列表行的 ListItem 重载分工一致。图库方格共用。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.cardInteraction(
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onSelectToggle: (Boolean) -> Unit,
): Modifier {
    val longPress = fileItemLongPress(onLongClick)
    return semantics {
        if (isSelectionMode) {
            role = Role.Checkbox
            toggleableState = if (isSelected) ToggleableState.On else ToggleableState.Off
        }
    }.combinedClickable(
        onLongClickLabel = tr("操作", "Actions", "操作", "작업", "操作"),
        onLongClick = longPress,
        onClick = { if (isSelectionMode) onSelectToggle(!isSelected) else onClick() },
    )
}

/**
 * 海报墙里的一张卡：16:9 封面加两行标题，文件与文件夹共用。
 *
 * 多数视频就是 16:9，封面统一这个比例，整面墙排成齐整的网格；原先按位置轮换的高矮两档并不反映画面，
 * 只是看上去像瀑布流。标题固定占两行高，同一排卡片底边对齐；日期、大小不上卡片，在属性里看。
 *
 * 被定位时封面外圈加一道描边（[locateHighlight]）。叠在封面上的：右上角至多两个标签（调用方已按优先级排好，无码、中字在前），
 * 左下角番号芯片，有封面的文件夹在它前面加文件夹标记，右下角清晰度。清晰度单独放一角，
 * 不和其余标签抢右上角的两个位置。没有封面的文件夹画成叠起的纸张，
 * 其余没有缩略图的画类型图标，封面区照样占 16:9，不另起一种图块。
 */
@Composable
internal fun PosterCard(
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
) {
    val gridPrefs = LocalDriveAppearancePrefs.current.grid
    val corners = gridPrefs.corners
    val item = file.filePresentation(title, original, tags, code, resolution, folderHasVault, isEmptyFolder)
    val hover = fileItemHover()
    val reducedMotion = LocalPikoMotion.current.reduced
    val fixedColors = LocalFixedColors.current
    val colors = MaterialTheme.colorScheme
    val coverShape = MaterialTheme.shapes.medium
    val hoverBg by animateColorAsState(
        targetValue = when {
            isSelected -> colors.secondaryContainer.copy(alpha = 0.42f)
            hover.mouseHovered -> colors.surfaceContainerLow
            else -> Color.Transparent
        },
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "poster_hover_bg",
    )
    val selectionBorderColor by animateColorAsState(
        targetValue = if (isSelected) colors.primary else Color.Transparent,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "poster_selection_border",
    )
    val coverScale by animateFloatAsState(
        targetValue = if (hover.mouseHovered && !reducedMotion) 1.02f else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "poster_hover_scale",
    )
    val hasResolutionCorner = corners.contains(CardBadgeKind.RESOLUTION)
    val hasStatusCorner = corners.contains(CardBadgeKind.STATUS_BADGES)
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
    val isEInk = LocalAppearance.current.isEInk
    val effectiveFolderStyle = resolveEffectiveFolderStyle(gridPrefs.folderStyle, isEInk)
    val showSelectionOverlay = isSelectionMode || (hover.mouseHovered && onStartSelection != null)
    val showsMediaCover = item.visual.thumbnail != null && (!file.isFolder || effectiveFolderStyle == FolderStyle.MINIMAL_ICON)
    val showMinimalFolderBadge = file.isFolder && effectiveFolderStyle == FolderStyle.MINIMAL_ICON && showsMediaCover
    val hasTopStart = showSelectionOverlay || badgeData.hasContent(corners.topStart)
    val hasTopEnd = badgeData.hasContent(corners.topEnd)
    val hasBottomStart = showMinimalFolderBadge || badgeData.hasContent(corners.bottomStart)
    val hasBottomEnd = badgeData.hasContent(corners.bottomEnd)

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

    Column(
        modifier = modifier
            .then(hover.modifier)
            .fillMaxWidth()
            .focusIndication(coverShape, showPointerFocus = !isSelectionMode)
            .clip(coverShape)
            .background(hoverBg)
            .cardInteraction(isSelectionMode, isSelected, onClick, onLongClick, onSelectToggle),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(COVER_ASPECT)
                .then(
                    when {
                        selectionBorderColor.alpha > 0f -> Modifier.border(3.dp, selectionBorderColor, coverShape)
                        isEInk -> Modifier.border(1.dp, colors.outline, coverShape)
                        showsMediaCover -> Modifier.border(1.dp, fixedColors.HudSpecularBorder, coverShape)
                        else -> Modifier
                    },
                )
                .clip(coverShape)
                .locateHighlight(isHighlighted, coverShape),
        ) {
            FileArtwork(
                item.visual,
                FileArtworkFormat.Poster,
                isSpoilerBlurred,
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = coverScale
                        scaleY = coverScale
                    },
                folderStyle = gridPrefs.folderStyle,
                showMinimalFolderBadge = false,
            )
            if ((hasBottomStart || hasBottomEnd) && showsMediaCover) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, fixedColors.ScrimOnMedia.copy(alpha = 0.72f)),
                            ),
                        ),
                )
            }
            if (hasTopStart || hasTopEnd) {
                val topEndFlex = corners.topEnd == CardBadgeKind.PARSED_TAGS && !showSelectionOverlay && corners.topStart != CardBadgeKind.PARSED_TAGS
                Row(
                    modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        modifier = if (topEndFlex) Modifier else Modifier.weight(1f),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        if (showSelectionOverlay) {
                            FileOverlaySelectionControl(
                                FileItemSelection(
                                    if (isSelected) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off,
                                    { if (isSelectionMode) onSelectToggle(!isSelected) else onStartSelection?.invoke() },
                                    label = tr("选择 ${item.name.fullName}", "Select ${item.name.fullName}", "${item.name.fullName} を選択", "${item.name.fullName} 선택", "選取 ${item.name.fullName}"),
                                ),
                                modifier = if (!isSelectionMode) Modifier.ownsClicks() else Modifier,
                                interactive = !isSelectionMode,
                            )
                        } else if (badgeData.hasContent(corners.topStart)) {
                            PosterCornerBadge(corners.topStart, badgeData)
                        }
                    }
                    if (hasTopEnd) {
                        Box(
                            modifier = if (topEndFlex) Modifier.weight(1f) else Modifier,
                            contentAlignment = Alignment.TopEnd,
                        ) {
                            PosterCornerBadge(corners.topEnd, badgeData)
                        }
                    }
                }
            }
            if (hasBottomStart || hasBottomEnd) {
                val bottomEndFlex = corners.bottomEnd == CardBadgeKind.PARSED_TAGS && corners.bottomStart != CardBadgeKind.PARSED_TAGS
                Row(
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = if (bottomEndFlex) Modifier else Modifier.weight(1f),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        if (hasBottomStart) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (showMinimalFolderBadge) {
                                    MinimalFolderBadge(compact = false)
                                }
                                if (badgeData.hasContent(corners.bottomStart)) {
                                    PosterCornerBadge(corners.bottomStart, badgeData)
                                }
                            }
                        }
                    }
                    if (hasBottomEnd) {
                        Box(
                            modifier = if (bottomEndFlex) Modifier.weight(1f) else Modifier,
                            contentAlignment = Alignment.BottomEnd,
                        ) {
                            PosterCornerBadge(corners.bottomEnd, badgeData)
                        }
                    }
                }
            }
        }

        // 右端的勾选框或更多按钮浮在标题上，不另占一栏：原来的三点按钮占着 48dp 的一栏，
        // 又往右上偏移出卡片，被卡片的圆角裁掉一半，偏出去的部分也点不到。显示时给标题让出位置
        val trailingShown = moreButton
        val showMarksBelowTitle = item.marks.isNotEmpty() && !hasStatusCorner
        val showFooter = gridPrefs.showTitle || detailParts.isNotEmpty() || showMarksBelowTitle
        if (showFooter) {
            // 下面留 4dp：叠在一行标题上的 28dp 按钮上下各探出 4dp，卡片裁了圆角，不留就被切掉；左右留 4dp 使悬停/选中底色柔和包住文字
            Box(modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(end = if (trailingShown) CARD_TRAILING_SIZE else 0.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    // 未在封面角落放置状态徽标时，星标与归档标记留在标题前；已放上封面时不再重复（DRY）
                    if (showMarksBelowTitle) Box(Modifier.padding(top = 2.dp, end = 4.dp)) { FileItemMarks(item.marks) }
                    Column(Modifier.weight(1f)) {
                        if (gridPrefs.showTitle) {
                            FileItemTitle(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = TITLE_LINES)
                            FileItemOriginal(item.name)
                        }
                        if (detailParts.isNotEmpty()) {
                            MetaRow(
                                parts = detailParts,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                                modifier = if (gridPrefs.showTitle) Modifier.padding(top = 2.dp) else Modifier,
                            )
                        }
                    }
                }
                if (trailingShown && !isSelectionMode) {
                    // matchParentSize 不参与量高度：标题只有一行时这一行 20dp，按钮一出现就把它撑高的话，悬停时卡片会跳
                    Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                        CardTrailing(onMoreClick = onMoreClick)
                    }
                }
            }
        }
    }
}

internal class PosterCornerData(
    val duration: String?,
    val resolution: String?,
    val tags: List<String>,
    val code: String?,
    val fileSize: String?,
    val marks: Set<FileItemMark>,
    val extension: String?,
) {
    fun hasContent(kind: CardBadgeKind): Boolean = when (kind) {
        CardBadgeKind.NONE -> false
        CardBadgeKind.DURATION -> duration != null
        CardBadgeKind.RESOLUTION -> resolution != null
        CardBadgeKind.PARSED_TAGS -> code != null || tags.isNotEmpty()
        CardBadgeKind.FILE_SIZE -> fileSize != null
        CardBadgeKind.STATUS_BADGES -> marks.isNotEmpty()
        CardBadgeKind.EXTENSION -> extension != null
    }
}

internal fun CornerSlots.contains(kind: CardBadgeKind): Boolean =
    topStart == kind || topEnd == kind || bottomStart == kind || bottomEnd == kind

@Composable
internal fun PosterCornerBadge(kind: CardBadgeKind, data: PosterCornerData) {
    when (kind) {
        CardBadgeKind.NONE -> Unit
        CardBadgeKind.DURATION -> data.duration?.let { PosterCornerPill(it, tabular = true) }
        CardBadgeKind.RESOLUTION -> data.resolution?.let { PosterCornerPill(it, tabular = true) }
        CardBadgeKind.PARSED_TAGS -> if (data.code != null || data.tags.isNotEmpty()) {
            MediaTagRow(tags = data.tags, lead = data.code, onMedia = true)
        }
        CardBadgeKind.FILE_SIZE -> data.fileSize?.let { PosterCornerPill(it, tabular = true) }
        CardBadgeKind.STATUS_BADGES -> if (data.marks.isNotEmpty()) {
            PosterCornerStatusPill(data.marks)
        }
        CardBadgeKind.EXTENSION -> data.extension?.let { PosterCornerPill(it, tabular = false) }
    }
}

@Composable
internal fun PosterCornerPill(text: String, tabular: Boolean, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.labelSmall
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = Color.Black.copy(alpha = 0.6f),
        contentColor = Color.White,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = if (tabular) style.tabular else style,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun PosterCornerStatusPill(marks: Set<FileItemMark>, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = Color.Black.copy(alpha = 0.6f),
        contentColor = Color.White,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (FileItemMark.LocalMount in marks) {
                Icon(
                    imageVector = Icons.Filled.Storage,
                    contentDescription = tr("本机挂载文件夹", "Mounted local folder", "マウントされたローカルフォルダー", "마운트된 로컬 폴더", "本機掛載資料夾"),
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
            if (FileItemMark.ImportPreview in marks) {
                Text(
                    text = tr("待保存", "Unsaved", "未保存", "저장 대기", "待儲存"),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                )
            }
            if (FileItemMark.Starred in marks) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = tr("已加星标", "Starred", "スター付き", "별표 있음", "已加星號"),
                    tint = Color(0xFFFFD54F),
                    modifier = Modifier.size(14.dp),
                )
            }
            if (FileItemMark.Vaulted in marks || FileItemMark.ContainsVault in marks) {
                val inFolder = FileItemMark.ContainsVault in marks
                Icon(
                    imageVector = Icons.Filled.Inventory2,
                    contentDescription = if (inFolder) tr("含归档条目", "Includes archived items", "アーカイブ項目を含む", "보관 항목 포함", "含歸檔項目") else tr("已归档", "Archived", "アーカイブ済み", "보관됨", "已歸檔"),
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * [PosterCard] 的骨架：同样的 16:9 封面，标题区同高，即两行字的 40dp。右端让出更多按钮的宽度，
 * 与按钮一直显示时（触屏）文字止于同一处。
 */
@Composable
internal fun PosterCardSkeleton(titleFraction: Float, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(COVER_ASPECT), MaterialTheme.shapes.medium)
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(TITLE_HEIGHT)) {
            // bodyMedium 行高 20dp、字形约 12dp，上留 4dp、行间 8dp，两条正好落在两行字的位置
            Column(
                modifier = Modifier.weight(1f).padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SkeletonTextLine(0.9f)
                SkeletonTextLine(titleFraction)
            }
            Spacer(modifier = Modifier.width(CARD_TRAILING_SIZE))
        }
    }
}

@Composable
private fun CardTrailing(
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isEInk = LocalAppearance.current.isEInk
    val colors = MaterialTheme.colorScheme
    // 盖在标题上时要有底色，否则图标压在字上读不清；水墨屏下改为白底黑框黑图标，避免灰底低对比
    FilledTonalIconButton(
        onClick = onMoreClick,
        colors = if (isEInk) {
            IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = Color.White,
                contentColor = Color.Black,
            )
        } else {
            IconButtonDefaults.filledTonalIconButtonColors()
        },
        modifier = modifier
            .size(28.dp)
            .then(if (isEInk) Modifier.border(1.dp, colors.outline, CircleShape) else Modifier)
            .ownsClicks(),
    ) {
        Icon(Icons.Outlined.MoreVert, contentDescription = tr("更多操作", "More actions", "その他の操作", "추가 작업", "更多操作"), modifier = Modifier.size(18.dp))
    }
}


private const val COVER_ASPECT = 16f / 9f
private const val TITLE_LINES = 2
private const val COVER_CORNER_TAGS = 2
private val CARD_TRAILING_SIZE = 32.dp
private val TITLE_HEIGHT = 40.dp
