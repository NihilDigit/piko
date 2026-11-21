package dev.piko.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.isImportPreview
import dev.piko.shared.i18n.PikoLocale
import dev.piko.shared.i18n.tr
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.resources.*
import dev.piko.ui.screens.drive.LocalDriveAppearancePrefs
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.LocalFixedColors
import dev.piko.ui.theme.LocalPikoMotion
import dev.piko.ui.theme.isEInk
import io.github.nihildigit.pikpak.FileStat

/** M3 列表的前导图像尺寸（ItemLeadingImage 56x56dp）。 */
val ListLeadingSize = 56.dp

/** 紧凑列表密度下的前导图像尺寸。 */
private val CompactListLeadingSize = 44.dp

/** 前导图标尺寸（ItemLeadingIconSize）。 */
val ListLeadingIconSize = 24.dp

// 前导 56dp 加上下各 8dp 即 72dp，正是 M3 两行列表项的高度。
private val RowMinHeight = 72.dp
private val CompactRowMinHeight = 56.dp

private val RowOuterPadding = 4.dp

/** 上下两行之间的缝，M3 容器化列表的分段间隙（lists.md 的 Gaps & dividers）。 */
private val RowGap = 4.dp
private val RowContentPadding = PaddingValues(start = 12.dp, end = 0.dp, top = 8.dp, bottom = 8.dp)
private val CompactRowContentPadding = PaddingValues(start = 12.dp, end = 0.dp, top = 4.dp, bottom = 4.dp)

/** 禁用态内容的不透明度，用于弱化显示的行。 */
private const val DIMMED_ALPHA = 0.38f

/**
 * 全应用单一权威交互式行容器（`Surface + Row`，严禁调用 M3 `ListItem` / `SegmentedListItem` 以规避
 * `LookaheadScope` 基线测量 NPE）。
 *
 * 统一承载网盘列表行（[FileListItem]）、目录树与来源树行（[WorkspaceTreeRow]）及传输任务行（`TaskRow`）：
 * - 零协程 `fileItemHover()`，鼠标悬停在定宽 [leadingSize] 槽位原地切换复选框，标题零水平位移；
 * - 支持独立圆角形变（`12dp -> 16dp`）或分段容器圆角（`ListItemDefaults.segmentedShapes`）；
 * - 水墨屏模式下自动补齐完整外轮廓 `1.dp` 实线黑框（选中态加粗至 `2.dp`）；
 * - 支持树形前缀槽（[treePrefix]）与任务进度槽（[progressSlot]）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun CanonicalListItemShell(
    selectionLabel: String,
    leading: @Composable () -> Unit,
    headline: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    headlineStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    supportingStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    supporting: (@Composable () -> Unit)? = null,
    progressSlot: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    treePrefix: (@Composable RowScope.() -> Unit)? = null,
    leadingSize: Dp = ListLeadingSize,
    leadingGap: Dp = 16.dp,
    trailingGap: Dp = 12.dp,
    rowMinHeight: Dp = RowMinHeight,
    outerPadding: PaddingValues = PaddingValues(horizontal = RowOuterPadding, vertical = RowGap / 2),
    contentPadding: PaddingValues = RowContentPadding,
    segmentedIndex: Int? = null,
    segmentedCount: Int = 0,
    restingContainerColor: Color = Color.Transparent,
    onLeadingClick: (() -> Unit)? = null,
    leadingClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onStartSelection: (() -> Unit)? = null,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onSelectToggle: (Boolean) -> Unit = {},
    isHighlighted: Boolean = false,
    isEmphasized: Boolean = false,
    isImportPreview: Boolean = false,
    dimmed: Boolean = false,
    enableFocusIndication: Boolean = true,
    keepTrailingInSelectionMode: Boolean = false,
    selectionModeTrailingSpacerWidth: Dp = 48.dp,
) {
    val longClick = fileItemLongPress(onLongClick)
    val hover = fileItemHover()
    val colorScheme = MaterialTheme.colorScheme
    val isEInk = LocalAppearance.current.isEInk
    val reducedMotion = LocalPikoMotion.current.reduced
    val isSegmented = segmentedIndex != null && segmentedCount > 0
    val selectedVisual = isSelected && (isSelectionMode || isSegmented)

    val targetContainerColor = when {
        isEInk -> Color.White
        selectedVisual -> colorScheme.secondaryContainer
        isHighlighted -> colorScheme.primaryContainer.copy(alpha = 0.45f)
        isEmphasized -> colorScheme.secondaryContainer.copy(alpha = 0.55f)
        isImportPreview -> colorScheme.secondaryContainer.copy(alpha = 0.28f)
        hover.mouseHovered -> if (restingContainerColor == Color.Transparent) {
            colorScheme.surfaceContainerLow
        } else {
            colorScheme.surfaceContainer
        }
        else -> restingContainerColor
    }
    val containerColor by animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "canonical_row_bg",
    )
    val contentColor = when {
        isEInk -> Color.Black
        selectedVisual -> colorScheme.onSecondaryContainer
        else -> colorScheme.onSurface
    }
    val supportingColor = when {
        isEInk -> Color.Black
        selectedVisual -> colorScheme.onSecondaryContainer
        else -> colorScheme.onSurfaceVariant
    }

    val cornerRadius by animateDpAsState(
        targetValue = if (selectedVisual && !reducedMotion) 16.dp else 12.dp,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "canonical_row_corner",
    )
    val rowShape: Shape = if (isSegmented) {
        ListItemDefaults.segmentedShapes(
            index = segmentedIndex.coerceIn(0, (segmentedCount - 1).coerceAtLeast(0)),
            count = segmentedCount.coerceAtLeast(1),
        ).shape
    } else {
        RoundedCornerShape(cornerRadius.coerceAtLeast(0.dp))
    }

    val eInkBorder: BorderStroke? = if (isEInk && (isSegmented || selectedVisual || isHighlighted || isEmphasized || hover.mouseHovered)) {
        BorderStroke(if (selectedVisual || isEmphasized) 2.dp else 1.dp, colorScheme.outline)
    } else {
        null
    }

    val itemModifier = modifier
        .then(hover.modifier)
        .fillMaxWidth()
        .padding(outerPadding)
        .then(
            if (enableFocusIndication) {
                Modifier.focusIndication(rowShape, showPointerFocus = !isSelectionMode && !isSegmented)
            } else {
                Modifier
            },
        )
        .locateHighlight(isHighlighted, rowShape)

    // 弱化加在各槽位上而不是整行：整行降透明度会连按压状态层一起变淡
    val slotModifier = if (dimmed) Modifier.alpha(DIMMED_ALPHA) else Modifier
    val showSelectionControl = isSelectionMode || (hover.mouseHovered && onStartSelection != null)

    Surface(
        modifier = itemModifier,
        shape = rowShape,
        color = containerColor,
        contentColor = contentColor,
        border = eInkBorder,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    if (isSelectionMode) {
                        role = Role.Checkbox
                        toggleableState = if (isSelected) ToggleableState.On else ToggleableState.Off
                    }
                }
                .combinedClickable(
                    onClick = { if (isSelectionMode) onSelectToggle(!isSelected) else onClick() },
                    onLongClick = longClick,
                    onLongClickLabel = if (longClick != null) tr("操作", "Actions", "操作", "작업", "操作") else null,
                )
                .heightIn(min = rowMinHeight)
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (treePrefix != null) {
                treePrefix()
            }
            Box(
                modifier = slotModifier
                    .size(leadingSize)
                    .then(
                        if (!showSelectionControl && onLeadingClick != null) {
                            Modifier
                                .clip(RoundedCornerShape(leadingCornerRadiusFor(leadingSize)))
                                .clickable(onClickLabel = leadingClickLabel, onClick = onLeadingClick)
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (showSelectionControl) {
                    FileItemSelectionControl(
                        selection = FileItemSelection(
                            state = if (isSelected) ToggleableState.On else ToggleableState.Off,
                            onToggle = { if (isSelectionMode) onSelectToggle(!isSelected) else onStartSelection?.invoke() },
                            label = selectionLabel,
                        ),
                        modifier = Modifier.ownsClicks(),
                        interactive = !isSelectionMode,
                    )
                } else {
                    leading()
                }
            }
            Spacer(Modifier.width(leadingGap))
            Column(modifier = Modifier.weight(1f)) {
                ProvideTextStyle(headlineStyle) {
                    Box(slotModifier) {
                        headline()
                    }
                }
                if (supporting != null) {
                    CompositionLocalProvider(LocalContentColor provides supportingColor) {
                        ProvideTextStyle(supportingStyle) {
                            Box(slotModifier) {
                                supporting()
                            }
                        }
                    }
                }
                if (progressSlot != null) {
                    Box(slotModifier) {
                        progressSlot()
                    }
                }
            }
            if (trailing != null || (isSelectionMode && !keepTrailingInSelectionMode && selectionModeTrailingSpacerWidth > 0.dp)) {
                if (trailingGap > 0.dp) {
                    Spacer(Modifier.width(trailingGap))
                }
                CompositionLocalProvider(LocalContentColor provides supportingColor) {
                    if (isSelectionMode && !keepTrailingInSelectionMode) {
                        Spacer(Modifier.width(selectionModeTrailingSpacerWidth))
                    } else if (trailing != null) {
                        Box(slotModifier) {
                            trailing()
                        }
                    }
                }
            }
        }
    }
}

/**
 * 网盘列表行（基于 [CanonicalListItemShell]）。
 *
 * 多选时整行作为一个复选项，读屏报告勾选状态；鼠标悬停时前导图像切换为复选框而不触发标题水平位移。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FileListItem(
    headline: String,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    headlineFontWeight: FontWeight? = null,
    headlineMaxLines: Int = 2,
    badge: (@Composable () -> Unit)? = null,
    supporting: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit = { ListMoreButton(onClick = onMoreClick) },
    onLongClick: (() -> Unit)? = null,
    onStartSelection: (() -> Unit)? = null,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onSelectToggle: (Boolean) -> Unit = {},
    isHighlighted: Boolean = false,
    isImportPreview: Boolean = false,
    dimmed: Boolean = false,
    compactDensity: Boolean = false,
) {
    val leadingSize = if (compactDensity) CompactListLeadingSize else ListLeadingSize
    val rowMinHeight = if (compactDensity) CompactRowMinHeight else RowMinHeight
    val rowContentPadding = if (compactDensity) CompactRowContentPadding else RowContentPadding
    val leadingGap = if (compactDensity) 12.dp else 16.dp
    val headlineStyle = if (headlineFontWeight == FontWeight.Medium) {
        MaterialTheme.typography.bodyLargeEmphasized
    } else {
        MaterialTheme.typography.bodyLarge
    }

    CanonicalListItemShell(
        selectionLabel = tr("选择 $headline", "Select $headline", "$headline を選択", "$headline 선택", "選取 $headline"),
        leading = leading,
        headline = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileItemTitle(
                    name = FileItemName(headline),
                    fontWeight = headlineFontWeight,
                    maxLines = headlineMaxLines,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    badge()
                }
            }
        },
        onClick = onClick,
        modifier = modifier,
        headlineStyle = headlineStyle,
        supportingStyle = MaterialTheme.typography.bodyMedium,
        supporting = supporting,
        trailing = trailing,
        leadingSize = leadingSize,
        leadingGap = leadingGap,
        rowMinHeight = rowMinHeight,
        contentPadding = rowContentPadding,
        onLongClick = onLongClick,
        onStartSelection = onStartSelection,
        isSelectionMode = isSelectionMode,
        isSelected = isSelected,
        onSelectToggle = onSelectToggle,
        isHighlighted = isHighlighted,
        isImportPreview = isImportPreview,
        dimmed = dimmed,
    )
}

/**
 * 行尾的更多按钮，打开该项的详情面板。只在移动端画：桌面的同一组操作在右键菜单里，
 * 每一行挂一个按钮只是满屏一样的图标，见 FormFactor。
 */
@Composable
fun ListMoreButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(imageVector = Icons.Outlined.MoreVert, contentDescription = tr("更多操作", "More actions", "その他の操作", "추가 작업", "更多操作"))
    }
}

/**
 * 网盘文件的一行。名字最多两行，扩展名移到副标题单列，所以截断发生时丢掉的是名字中段而不是类型。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FileListItem(
    file: FileStat,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onStartSelection: (() -> Unit)? = null,
    onSelectToggle: (Boolean) -> Unit,
    onMoreClick: () -> Unit,
    /** 行尾画不画打开操作面板的更多按钮。宽窗口不画：鼠标有右键菜单，每一行挂一个按钮只是满屏一样的图标。 */
    moreButton: Boolean,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
    isSpoilerBlurred: Boolean = false,
    /** 搜索结果或查重路径，或资料库（播放历史、最近添加、回收站）结构化上下文附注。 */
    locationLabel: String? = null,
    watchProgress: Float? = null,
    title: String? = null,
    /** 真实名称，[title] 与它不同时写在标题下面一行，见 DriveItemName。 */
    original: String? = null,
    tags: List<String> = emptyList(),
    /** 番号芯片，排在标签行最前。 */
    code: String? = null,
    /** 文件夹里直接放着归档条目，见 [itemMarks]。 */
    folderHasVault: Boolean = false,
    /** 列过、确实是空的文件夹，拟物模式下不画纸。 */
    isEmptyFolder: Boolean = false,
    /** 文件夹递归条目数、总时长与总大小，供列表详情行按 [LocalDriveAppearancePrefs] 显示。 */
    folderItemCount: Int? = null,
    folderDurationMillis: Long? = null,
    folderSizeBytes: Long? = null,
) {
    val listPrefs = LocalDriveAppearancePrefs.current.list
    val leadingSize = if (listPrefs.compactDensity) CompactListLeadingSize else ListLeadingSize
    val shownTags = if (listPrefs.showParsedTags) tags else emptyList()
    val shownCode = if (listPrefs.showParsedTags) code else null
    val item = file.filePresentation(
        title = title,
        original = original,
        tags = shownTags,
        code = shownCode,
        folderHasVault = folderHasVault,
        emptyFolder = isEmptyFolder,
        locationLabel = locationLabel,
        watchProgress = watchProgress,
    )
    val detailParts = remember(
        file, listPrefs.detailFields, listPrefs.showParsedTags, tags,
        folderItemCount, folderDurationMillis, folderSizeBytes, folderHasVault, PikoLocale.effectiveLanguage,
    ) {
        file.detailLineParts(
            fields = listPrefs.detailFields,
            tags = tags,
            folderItemCount = folderItemCount,
            folderDurationMillis = folderDurationMillis,
            folderSizeBytes = folderSizeBytes,
            folderHasVault = folderHasVault,
            includeParsedTags = !listPrefs.showParsedTags,
        )
    }
    val displayItem = remember(item, detailParts) { item.copy(metadata = detailParts) }
    val listMarks = displayItem.marks - FileItemMark.LocalMount
    val hasSupporting = (listPrefs.showSecondaryName && displayItem.name.original != null) ||
        displayItem.tags.isNotEmpty() || displayItem.code != null ||
        detailParts.isNotEmpty() || displayItem.status != null ||
        displayItem.contextNote.isNotEmpty() || !displayItem.locationPath.isNullOrEmpty()
    FileListItem(
        headline = displayItem.name.title,
        headlineMaxLines = if (title != null || listPrefs.compactDensity) 1 else 2,
        headlineFontWeight = if (file.isFolder) FontWeight.Medium else null,
        leading = {
            FileListLeadingVisual(
                file = displayItem.visual,
                size = leadingSize,
                isSpoilerBlurred = isSpoilerBlurred,
                folderStyle = listPrefs.folderStyle,
                watchProgress = displayItem.watchProgress,
            )
        },
        onClick = onClick,
        onMoreClick = onMoreClick,
        trailing = { if (moreButton) ListMoreButton(onClick = onMoreClick, modifier = Modifier.ownsClicks()) },
        modifier = modifier,
        badge = if (listMarks.isEmpty()) null else ({ FileItemMarks(listMarks) }),
        supporting = if (hasSupporting) {
            {
                FileItemInformation(
                    item = displayItem,
                    showMetadata = detailParts.isNotEmpty(),
                    showOriginal = listPrefs.showSecondaryName,
                    showTags = listPrefs.showParsedTags,
                )
            }
        } else null,
        onLongClick = onLongClick,
        onStartSelection = onStartSelection,
        isSelectionMode = isSelectionMode,
        isSelected = isSelected,
        onSelectToggle = onSelectToggle,
        isHighlighted = isHighlighted,
        isImportPreview = file.isImportPreview,
        compactDensity = listPrefs.compactDensity,
    )
}

/**
 * 列表行的前导图像：有缩略图时显示缩略图（可带防窥模糊），否则显示 [fallback]。
 */
@Composable
fun ListLeadingMedia(
    thumbnail: Any?,
    fallback: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    isSpoilerBlurred: Boolean = false,
    showPlayOverlay: Boolean = false,
    size: Dp = ListLeadingSize,
    stableId: String? = null,
    shape: Shape = MaterialTheme.shapes.small,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        var coverFailed by remember(thumbnail) { mutableStateOf(false) }
        val activeThumbnail = thumbnail?.takeUnless { coverFailed }
        if (activeThumbnail == null) {
            fallback()
        } else {
            SpoilerThumbnail(
                model = activeThumbnail,
                isBlurred = isSpoilerBlurred,
                blur = ListSpoilerBlur,
                modifier = Modifier.fillMaxSize(),
                onError = { coverFailed = true },
                stableId = stableId,
            )
        }
        if (showPlayOverlay && activeThumbnail != null && !isSpoilerBlurred) {
            val fixed = LocalFixedColors.current
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(fixed.ScrimOnMedia.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = fixed.OnMedia,
                    modifier = Modifier.size(leadingIconSizeFor(size)),
                )
            }
        }
    }
}

/**
 * 没有缩略图时的类型图标块，外观与网盘文件（非文件夹）的图标块一致。
 */
@Composable
fun ListLeadingIcon(icon: ImageVector, modifier: Modifier = Modifier) {
    val isEInk = LocalAppearance.current.isEInk
    val shape = MaterialTheme.shapes.small
    Box(
        modifier = modifier
            .size(ListLeadingSize)
            .then(if (isEInk) Modifier.clip(shape).background(Color.White) else Modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isEInk) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(ListLeadingIconSize),
        )
    }
}

/**
 * 加了星标的条目标记：标题旁一颗实心星。用 tertiary：primary 已给了定位描边与选中态，
 * 同色放在一起分不出是哪一种。
 */
@Composable
fun StarMark(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Filled.Star,
        contentDescription = tr("已加星标", "Starred", "スター付き", "별표 있음", "已加星號"),
        tint = MaterialTheme.colorScheme.tertiary,
        modifier = modifier.size(StarMarkSize),
    )
}

private val StarMarkSize = 16.dp

/**
 * 归档标记：条目本身是归档记录，或文件夹里直接放着归档条目。
 */
@Composable
fun VaultMark(inFolder: Boolean, modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Filled.Inventory2,
        contentDescription = if (inFolder) tr("含归档条目", "Includes archived items", "アーカイブ項目を含む", "보관 항목 포함", "含歸檔項目") else tr("已归档", "Archived", "アーカイブ済み", "보관됨", "已歸檔"),
        tint = MaterialTheme.colorScheme.secondary,
        modifier = modifier.size(StarMarkSize),
    )
}

@Composable
fun LocalMountMark(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Filled.Storage,
        contentDescription = tr("本机挂载文件夹", "Mounted local folder", "マウントされたローカルフォルダー", "마운트된 로컬 폴더", "本機掛載資料夾"),
        tint = MaterialTheme.colorScheme.secondary,
        modifier = modifier.size(StarMarkSize),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ImportPreviewMark(modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(Res.string.text_unsaved),
            style = MaterialTheme.typography.labelSmallEmphasized,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

/**
 * 标题旁的标记：星标与归档，都没有时为 null。
 */
fun itemMarks(file: FileStat, folderHasVault: Boolean): (@Composable () -> Unit)? {
    val marks = file.fileItemMarks(folderHasVault)
    return if (marks.isEmpty()) null else ({ FileItemMarks(marks) })
}

/** 条目旁的一枚文字角标，查重里标「保留」。被定位的条目不用它，见 [locateHighlight]。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HighlightBadge(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmallEmphasized,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}
