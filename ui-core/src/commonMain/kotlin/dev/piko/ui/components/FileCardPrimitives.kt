package dev.piko.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.CardBadgeKind
import dev.piko.shared.data.CornerSlots
import dev.piko.shared.data.FolderStyle
import dev.piko.shared.i18n.tr
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.LocalFixedColors
import dev.piko.ui.theme.LocalPikoMotion
import dev.piko.ui.theme.isEInk
import dev.piko.ui.theme.tabular

/**
 * 网格与瀑布流卡片共用的整体点击与多选无障碍语义：
 * 多选模式下整张卡作为一个复选项；平时单击打开，触屏长按或无障碍长按呼出操作面板。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.cardInteraction(
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
        onLongClickLabel = if (longPress != null) tr("操作", "Actions", "操作", "작업", "操作") else null,
        onLongClick = longPress,
        onClick = { if (isSelectionMode) onSelectToggle(!isSelected) else onClick() },
    )
}

class PosterCornerData(
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

fun CornerSlots.contains(kind: CardBadgeKind): Boolean =
    topStart == kind || topEnd == kind || bottomStart == kind || bottomEnd == kind

/**
 * 网格（`PosterCard`）与瀑布流（`GalleryCanvasTile`）100% 共用的单一权威媒体卡片底座：
 * 统一承载封面 `FileArtwork`（含拟物/极简文件夹与大写扩展名 `TypePlaceholder`）、水墨屏 `1.dp` 实线黑框、
 * `HudSpecularBorder` 微光描边、四角 `PosterCornerBadge` 与悬停复选框叠层、底沿播放进度条、双名称标题、
 * `.tabular` `MetaRow` 详情/上下文行以及 `StartEllipsis` 所在目录路径。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MediaCardScaffold(
    item: FilePresentation,
    corners: CornerSlots,
    badgeData: PosterCornerData,
    detailParts: List<String>,
    folderStyle: FolderStyle,
    showTitle: Boolean,
    showSecondaryName: Boolean,
    showDetailLine: Boolean,
    alwaysShowLocation: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isSpoilerBlurred: Boolean,
    isHighlighted: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onStartSelection: (() -> Unit)?,
    onSelectToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** 指定比例时按固定宽高比测量（海报墙 16:9 或设置预览）；为 null 时占满虚拟化画布槽剩余高度。 */
    coverAspectRatio: Float? = 16f / 9f,
    titleStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    titleMaxLines: Int = 2,
    footerTopPadding: Dp = 6.dp,
    showVideoPlayMark: Boolean = true,
    scaleCoverOnHover: Boolean = true,
    alwaysOutlineCover: Boolean = false,
    moreButton: Boolean = false,
    onMoreClick: (() -> Unit)? = null,
    onAspectRatio: ((Float) -> Unit)? = null,
    onCoverFailed: (() -> Unit)? = null,
    coverOverlay: (@Composable BoxScope.() -> Unit)? = null,
) {
    val hover = fileItemHover()
    val reducedMotion = LocalPikoMotion.current.reduced
    val isEInk = LocalAppearance.current.isEInk
    val fixedColors = LocalFixedColors.current
    val colors = MaterialTheme.colorScheme
    val coverShape = MaterialTheme.shapes.medium

    var coverFailed by remember(item.visual.thumbnail) { mutableStateOf(false) }
    val effectiveFolderStyle = resolveEffectiveFolderStyle(folderStyle, isEInk)
    val showSelectionOverlay = isSelectionMode || (hover.mouseHovered && onStartSelection != null)
    val showsMediaCover = item.visual.thumbnail != null && !coverFailed &&
        (!item.visual.isFolder || effectiveFolderStyle == FolderStyle.MINIMAL_ICON)
    val showMinimalFolderBadge = item.visual.isFolder &&
        effectiveFolderStyle == FolderStyle.MINIMAL_ICON && showsMediaCover

    val hasStatusCorner = corners.contains(CardBadgeKind.STATUS_BADGES)
    val showMarksBelowTitle = item.marks.isNotEmpty() && !hasStatusCorner
    val hasLocationContent = item.contextNote.isNotEmpty() || !item.locationPath.isNullOrEmpty()
    val hasDownloadMark = item.isDownloaded || item.isDownloading
    val showFooter = showTitle ||
        (showDetailLine && (detailParts.isNotEmpty() || hasDownloadMark || (!alwaysShowLocation && hasLocationContent))) ||
        (alwaysShowLocation && (detailParts.isNotEmpty() || (showDetailLine && hasDownloadMark) || hasLocationContent)) ||
        showMarksBelowTitle

    val hoverBg by animateColorAsState(
        targetValue = when {
            isSelected -> colors.secondaryContainer.copy(alpha = if (isEInk) 1f else 0.42f)
            hover.mouseHovered && (scaleCoverOnHover || showFooter) -> colors.surfaceContainerLow
            else -> Color.Transparent
        },
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "media_card_hover_bg",
    )
    val selectionBorderColor by animateColorAsState(
        targetValue = if (isSelected) colors.primary else Color.Transparent,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "media_card_selection_border",
    )
    val coverScale by animateFloatAsState(
        targetValue = if (scaleCoverOnHover && hover.mouseHovered && !reducedMotion) 1.02f else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "media_card_hover_scale",
    )

    Column(
        modifier = modifier
            .then(hover.modifier)
            .focusIndication(coverShape, showPointerFocus = !isSelectionMode)
            .clip(coverShape)
            .background(hoverBg)
            .cardInteraction(isSelectionMode, isSelected, onClick, onLongClick, onSelectToggle),
    ) {
        val coverSizingModifier = if (coverAspectRatio != null) {
            Modifier.fillMaxWidth().aspectRatio(coverAspectRatio)
        } else {
            Modifier.fillMaxWidth().weight(1f)
        }
        Box(
            modifier = coverSizingModifier
                .then(
                    when {
                        selectionBorderColor.alpha > 0f -> Modifier.border(3.dp, selectionBorderColor, coverShape)
                        isEInk -> Modifier.border(1.dp, colors.outline, coverShape)
                        showsMediaCover -> Modifier.border(1.dp, fixedColors.HudSpecularBorder, coverShape)
                        alwaysOutlineCover -> Modifier.border(1.dp, colors.outlineVariant.copy(alpha = 0.25f), coverShape)
                        else -> Modifier
                    },
                )
                .clip(coverShape)
                .locateHighlight(isHighlighted, coverShape),
        ) {
            FileArtwork(
                file = item.visual,
                format = FileArtworkFormat.Poster,
                isSpoilerBlurred = isSpoilerBlurred,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (coverScale != 1f) {
                            Modifier.graphicsLayer {
                                scaleX = coverScale
                                scaleY = coverScale
                            }
                        } else {
                            Modifier
                        },
                    ),
                folderStyle = folderStyle,
                showMinimalFolderBadge = false,
                showVideoPlayMark = showVideoPlayMark,
                onAspectRatio = onAspectRatio,
                onCoverError = {
                    coverFailed = true
                    onCoverFailed?.invoke()
                },
            )
            CardCornerBadgesOverlay(
                item = item,
                corners = corners,
                badgeData = badgeData,
                showsMediaCover = showsMediaCover,
                showMinimalFolderBadge = showMinimalFolderBadge,
                showSelectionOverlay = showSelectionOverlay,
                isSelectionMode = isSelectionMode,
                isSelected = isSelected,
                onStartSelection = onStartSelection,
                onSelectToggle = onSelectToggle,
            )
            if (item.watchProgress != null && item.watchProgress > 0f) {
                WatchProgressStrip(
                    progress = item.watchProgress,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            if (coverOverlay != null) {
                coverOverlay()
            }
        }

        if (showFooter) {
            CardFooterBlock(
                item = item,
                detailParts = detailParts,
                showMarksBelowTitle = showMarksBelowTitle,
                showTitle = showTitle,
                showSecondaryName = showSecondaryName,
                showDetailLine = showDetailLine,
                alwaysShowLocation = alwaysShowLocation,
                titleStyle = titleStyle,
                titleMaxLines = titleMaxLines,
                topPadding = footerTopPadding,
                isSelectionMode = isSelectionMode,
                moreButton = moreButton,
                onMoreClick = onMoreClick,
            )
        }
    }
}

@Composable
fun BoxScope.CardCornerBadgesOverlay(
    item: FilePresentation,
    corners: CornerSlots,
    badgeData: PosterCornerData,
    showsMediaCover: Boolean,
    showMinimalFolderBadge: Boolean,
    showSelectionOverlay: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onStartSelection: (() -> Unit)?,
    onSelectToggle: (Boolean) -> Unit,
) {
    val isEInk = LocalAppearance.current.isEInk
    val fixedColors = LocalFixedColors.current
    val hasTopStart = showSelectionOverlay || badgeData.hasContent(corners.topStart)
    val hasTopEnd = badgeData.hasContent(corners.topEnd)
    val hasBottomStart = showMinimalFolderBadge || badgeData.hasContent(corners.bottomStart)
    val hasBottomEnd = badgeData.hasContent(corners.bottomEnd)

    if ((hasBottomStart || hasBottomEnd) && showsMediaCover && !isEInk) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(44.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            fixedColors.ScrimOnMedia.copy(alpha = 0.28f),
                            fixedColors.ScrimOnMedia.copy(alpha = 0.72f),
                        ),
                    ),
                ),
        )
    }
    if (hasTopStart || hasTopEnd) {
        val topEndFlex = corners.topEnd == CardBadgeKind.PARSED_TAGS &&
            !showSelectionOverlay &&
            corners.topStart != CardBadgeKind.PARSED_TAGS
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
                            state = if (isSelected) ToggleableState.On else ToggleableState.Off,
                            onToggle = { if (isSelectionMode) onSelectToggle(!isSelected) else onStartSelection?.invoke() },
                            label = tr(
                                "选择 ${item.name.fullName}",
                                "Select ${item.name.fullName}",
                                "${item.name.fullName} を選択",
                                "${item.name.fullName} 선택",
                                "選取 ${item.name.fullName}",
                            ),
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
        val bottomEndFlex = corners.bottomEnd == CardBadgeKind.PARSED_TAGS &&
            corners.bottomStart != CardBadgeKind.PARSED_TAGS
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

@Composable
fun CardFooterBlock(
    item: FilePresentation,
    detailParts: List<String>,
    showMarksBelowTitle: Boolean,
    showTitle: Boolean,
    showSecondaryName: Boolean,
    showDetailLine: Boolean,
    alwaysShowLocation: Boolean,
    titleStyle: TextStyle,
    titleMaxLines: Int,
    topPadding: Dp,
    isSelectionMode: Boolean,
    moreButton: Boolean,
    onMoreClick: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val trailingShown = moreButton && onMoreClick != null
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = topPadding, bottom = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = if (trailingShown) CardTrailingReservedWidth else 0.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // 未在封面角落放置状态徽标时，星标与归档标记留在标题前；已放上封面时不再重复（DRY）
            if (showMarksBelowTitle) {
                Box(Modifier.padding(top = 2.dp, end = 4.dp)) {
                    FileItemMarks(item.marks)
                }
            }
            Column(Modifier.weight(1f)) {
                if (showTitle) {
                    FileItemTitle(item.name, style = titleStyle, maxLines = titleMaxLines)
                    if (showSecondaryName) {
                        FileItemOriginal(item.name)
                    }
                }
                val downloadedTrailing: (@Composable () -> Unit)? = when {
                    !showDetailLine -> null
                    item.isDownloaded -> ({ DownloadedMark(size = 13.dp) })
                    item.isDownloading -> ({ DownloadingMark(size = 13.dp) })
                    else -> null
                }
                if (alwaysShowLocation) {
                    var hasAbove = showTitle
                    if (detailParts.isNotEmpty() || downloadedTrailing != null) {
                        MetaRow(
                            parts = detailParts,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            trailing = downloadedTrailing,
                            modifier = if (hasAbove) Modifier.padding(top = 2.dp) else Modifier,
                        )
                        hasAbove = true
                    }
                    if (item.contextNote.isNotEmpty()) {
                        MetaRow(
                            parts = item.contextNote,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.primary,
                            modifier = if (hasAbove) Modifier.padding(top = 2.dp) else Modifier,
                        )
                        hasAbove = true
                    }
                    item.locationPath?.takeIf { it.isNotEmpty() }?.let { path ->
                        Text(
                            text = path,
                            style = MaterialTheme.typography.labelSmall.tabular,
                            color = colors.primary,
                            maxLines = 1,
                            overflow = TextOverflow.StartEllipsis,
                            modifier = if (hasAbove) Modifier.padding(top = 2.dp) else Modifier,
                        )
                    }
                } else if (showDetailLine) {
                    val topGap = if (showTitle) Modifier.padding(top = 2.dp) else Modifier
                    when {
                        detailParts.isNotEmpty() -> MetaRow(
                            parts = detailParts,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            trailing = downloadedTrailing,
                            modifier = topGap,
                        )
                        item.contextNote.isNotEmpty() -> MetaRow(
                            parts = item.contextNote,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.primary,
                            trailing = downloadedTrailing,
                            modifier = topGap,
                        )
                        !item.locationPath.isNullOrEmpty() -> Row(
                            modifier = topGap,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = item.locationPath,
                                style = MaterialTheme.typography.labelSmall.tabular,
                                color = colors.primary,
                                maxLines = 1,
                                overflow = TextOverflow.StartEllipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (downloadedTrailing != null) downloadedTrailing()
                        }
                        downloadedTrailing != null -> MetaRow(
                            parts = emptyList(),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            trailing = downloadedTrailing,
                            modifier = topGap,
                        )
                    }
                }
            }
        }
        if (trailingShown && !isSelectionMode && onMoreClick != null) {
            // matchParentSize 不参与测量高度，避免单行标题卡片在按钮显隐时发生高度跳变
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                CardMoreButton(onMoreClick = onMoreClick)
            }
        }
    }
}

@Composable
fun PosterCornerBadge(kind: CardBadgeKind, data: PosterCornerData) {
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
fun PosterCornerPill(text: String, tabular: Boolean, modifier: Modifier = Modifier) {
    val isEInk = LocalAppearance.current.isEInk
    val style = MaterialTheme.typography.labelSmall
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (isEInk) Color.White else Color.Black.copy(alpha = 0.6f),
        contentColor = if (isEInk) Color.Black else Color.White,
        border = if (isEInk) BorderStroke(1.dp, Color.Black) else null,
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
    val isEInk = LocalAppearance.current.isEInk
    val iconTint = if (isEInk) Color.Black else Color.White
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (isEInk) Color.White else Color.Black.copy(alpha = 0.6f),
        contentColor = iconTint,
        border = if (isEInk) BorderStroke(1.dp, Color.Black) else null,
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
                    tint = iconTint,
                    modifier = Modifier.size(14.dp),
                )
            }
            if (FileItemMark.ImportPreview in marks) {
                Text(
                    text = tr("待保存", "Unsaved", "未保存", "저장 대기", "待儲存"),
                    style = MaterialTheme.typography.labelSmall,
                    color = iconTint,
                    maxLines = 1,
                )
            }
            if (FileItemMark.Starred in marks) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = tr("已加星标", "Starred", "スター付き", "별표 있음", "已加星號"),
                    tint = if (isEInk) Color.Black else Color(0xFFFFD54F),
                    modifier = Modifier.size(14.dp),
                )
            }
            if (FileItemMark.Vaulted in marks || FileItemMark.ContainsVault in marks) {
                val inFolder = FileItemMark.ContainsVault in marks
                Icon(
                    imageVector = Icons.Filled.Inventory2,
                    contentDescription = if (inFolder) tr("含归档条目", "Includes archived items", "アーカイブ項目を含む", "보관 항목 포함", "含歸檔項目") else tr("已归档", "Archived", "アーカイブ済み", "보관됨", "已歸檔"),
                    tint = iconTint,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
fun CardMoreButton(
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isEInk = LocalAppearance.current.isEInk
    val colors = MaterialTheme.colorScheme
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
        Icon(
            imageVector = Icons.Outlined.MoreVert,
            contentDescription = tr("更多操作", "More actions", "その他の操作", "추가 작업", "更多操作"),
            modifier = Modifier.size(18.dp),
        )
    }
}

val CardTrailingReservedWidth = 32.dp
