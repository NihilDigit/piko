package dev.piko.ui.components

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Storage
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.isImportPreview
import dev.piko.shared.i18n.PikoLocale
import dev.piko.ui.adaptive.isDesktopLayout
import dev.piko.ui.screens.drive.LocalDriveAppearancePrefs
import dev.piko.ui.theme.LocalPikoMotion
import io.github.nihildigit.pikpak.FileStat

/** M3 列表的前导图像尺寸（ItemLeadingImage 56x56dp）。 */
val ListLeadingSize = 56.dp

/** 紧凑列表密度下的前导图像尺寸。 */
private val CompactListLeadingSize = 44.dp

/** 前导图标尺寸（ItemLeadingIconSize）。 */
val ListLeadingIconSize = 24.dp

// 前导 56dp 加上下各 8dp 即 72dp，正是 M3 两行列表项的高度。原先按扫读密度降到 64dp，
// 前提是 48dp 的前导；前导改用规范的 56dp 后，64dp 放不下它加上下内边距。
private val RowMinHeight = 72.dp
private val CompactRowMinHeight = 56.dp

// 外侧留 4dp，让按压与选中时变圆的容器不贴屏幕边缘；内侧起始 12dp，
// 两者相加使内容仍落在紧凑窗口 16dp 的页边距上。末端为 0，让尾部 48dp 图标按钮
// 自带的 12dp 内边距把图标对齐到同一条页边距。
private val RowOuterPadding = 4.dp

/** 上下两行之间的缝，M3 容器化列表的分段间隙（lists.md 的 Gaps & dividers）。 */
private val RowGap = 4.dp
private val RowContentPadding = PaddingValues(start = 12.dp, end = 0.dp, top = 8.dp, bottom = 8.dp)
private val CompactRowContentPadding = PaddingValues(start = 12.dp, end = 0.dp, top = 4.dp, bottom = 4.dp)

/** 禁用态内容的不透明度，用于弱化显示的行。 */
private const val DIMMED_ALPHA = 0.38f

/**
 * 网盘列表与传输列表共用的一行。
 *
 * 基于 M3 Expressive 的交互式 ListItem：按压与选中的形状变化、点击与长按的语义都由
 * 组件提供。多选时换用 checked 重载，整行即一个复选项，读屏会报告勾选状态；尾部的
 * 复选框只作指示，不单独响应点击，符合「每项只保留一种选择交互」的规范。复选框与
 * 更多按钮同宽，进出多选时内容不会横移。
 *
 * 标题最多两行，完整内容在详情面板顶部可见。
 *
 * 行高须始终落在 72dp 或 88dp 两档内，任何状态都不能把行撑高：副文本只放一行，
 * 长内容（如完整的错误信息）放进详情面板。副文本下还要放进度条的行，把
 * [headlineMaxLines] 设为 1，两行标题、一行副文本再加进度条会超出 88dp。
 *
 * [onLongClick] 处理长按及无障碍长按操作，[onStartSelection] 用于悬停复选框。
 * 鼠标副键菜单由外面的 ContextMenuArea 提供。[dimmed] 把行内各槽位降到
 * 禁用态的不透明度，行本身仍可点击。
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
    val longClick = fileItemLongPress(onLongClick)
    val hover = fileItemHover()
    val colorScheme = MaterialTheme.colorScheme
    val reducedMotion = LocalPikoMotion.current.reduced
    val leadingSize = if (compactDensity) CompactListLeadingSize else ListLeadingSize
    val rowMinHeight = if (compactDensity) CompactRowMinHeight else RowMinHeight
    val rowContentPadding = if (compactDensity) CompactRowContentPadding else RowContentPadding
    val leadingGap = if (compactDensity) 12.dp else 16.dp
    val targetContainerColor = when {
        isSelectionMode && isSelected -> colorScheme.secondaryContainer
        isHighlighted -> colorScheme.primaryContainer.copy(alpha = 0.45f)
        isImportPreview -> colorScheme.secondaryContainer.copy(alpha = 0.28f)
        hover.mouseHovered -> colorScheme.surfaceContainerLow
        else -> Color.Transparent
    }
    val containerColor by animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "file_row_bg",
    )
    val contentColor = if (isSelectionMode && isSelected) colorScheme.onSecondaryContainer else colorScheme.onSurface
    val supportingColor = if (isSelectionMode && isSelected) colorScheme.onSecondaryContainer else colorScheme.onSurfaceVariant
    // 直接基于 Surface + Row + combinedClickable 排版，规避 M3 1.5.0-rc01 InteractiveListItemMeasurePolicy
    // 无条件查询 supportingContent[FirstBaseline] 在 LookaheadScope 下的潜在 NPE；
    // 选中态圆角在 12dp 与 16dp 之间正向平滑变形（coerced >= 0dp，绝不产生负圆角）。
    val cornerRadius by animateDpAsState(
        targetValue = if (isSelectionMode && isSelected && !reducedMotion) 16.dp else 12.dp,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "file_row_corner",
    )
    val rowShape = RoundedCornerShape(cornerRadius.coerceAtLeast(0.dp))
    val itemModifier = modifier
        .then(hover.modifier)
        .fillMaxWidth()
        .padding(horizontal = RowOuterPadding, vertical = RowGap / 2)
        .focusIndication(rowShape, showPointerFocus = !isSelectionMode)
        .locateHighlight(isHighlighted, rowShape)
    // 弱化加在各槽位上而不是整行：整行降透明度会连按压的状态层一起变淡
    val slotModifier = if (dimmed) Modifier.alpha(DIMMED_ALPHA) else Modifier

    val leadingSlot: @Composable () -> Unit = {
        Box(slotModifier.size(leadingSize), contentAlignment = Alignment.Center) {
            if (isSelectionMode || (hover.mouseHovered && onStartSelection != null)) {
                FileItemSelectionControl(FileItemSelection(
                    if (isSelected) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off,
                    { if (isSelectionMode) onSelectToggle(!isSelected) else onStartSelection?.invoke() }, label = tr("选择 $headline", "Select $headline", "$headline を選択", "$headline 선택", "選取 $headline")),
                    modifier = Modifier.ownsClicks(), interactive = !isSelectionMode)
            } else leading()
        }
    }
    val supportingSlot: (@Composable () -> Unit)? = supporting?.let { content ->
        { Box(slotModifier) { content() } }
    }
    val headlineSlot: @Composable () -> Unit = {
        Row(modifier = slotModifier, verticalAlignment = Alignment.CenterVertically) {
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
    }

    @OptIn(ExperimentalFoundationApi::class)
    Surface(
        modifier = itemModifier,
        shape = rowShape,
        color = containerColor,
        contentColor = contentColor,
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
                .padding(rowContentPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingSlot()
            Spacer(Modifier.width(leadingGap))
            Column(modifier = Modifier.weight(1f)) {
                val headlineStyle = if (headlineFontWeight == FontWeight.Medium) {
                    MaterialTheme.typography.bodyLargeEmphasized
                } else {
                    MaterialTheme.typography.bodyLarge
                }
                ProvideTextStyle(headlineStyle) {
                    headlineSlot()
                }
                if (supportingSlot != null) {
                    CompositionLocalProvider(LocalContentColor provides supportingColor) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                            supportingSlot()
                        }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            CompositionLocalProvider(LocalContentColor provides supportingColor) {
                if (isSelectionMode) Spacer(Modifier.width(48.dp)) else Box(slotModifier) { trailing() }
            }
        }
    }
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
 * 网盘文件的一行。名字最多两行，扩展名移到副标题单列，所以截断发生时丢掉的是名字中段
 * 而不是类型。
 *
 * 给出 [title] 时是解析后的短标题（「01」、罗马音作品名），只占一行，[tags] 整行排在其下，
 * 放不下的从尾部丢弃；文件在标签下仍有类型与大小，文件夹的类型已由图标表明，不再写。
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
    /** 全盘搜索结果所在的目录路径。仅搜索结果需要，平时为 null。 */
    locationLabel: String? = null,
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
        !locationLabel.isNullOrEmpty()
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
            )
        },
        onClick = onClick,
        onMoreClick = onMoreClick,
        trailing = { if (moreButton) ListMoreButton(onClick = onMoreClick, modifier = Modifier.ownsClicks()) },
        modifier = modifier,
        badge = if (listMarks.isEmpty()) null else ({ FileItemMarks(listMarks) }),
        supporting = if (hasSupporting) {
            {
                Column {
                    FileItemInformation(
                        item = displayItem,
                        showMetadata = detailParts.isNotEmpty(),
                        showOriginal = listPrefs.showSecondaryName,
                    )
                    if (!locationLabel.isNullOrEmpty()) {
                        // 路径从头截断：离命中项最近的几级目录最有辨识度
                        Text(
                            text = locationLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.StartEllipsis,
                        )
                    }
                }
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
 *
 * [thumbnail] 是交给 Coil 的模型：网盘缩略图的 URL，或本地文件。防窥模糊只作用于 URL，
 * 本地副本是用户自己下载的，不再遮蔽。[showPlayOverlay] 在缩略图上压一层播放标记，
 * 提示点按即播放；没有缩略图时类型图标已表明是媒体，不再叠加。
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
        // 本地文件与 SAF 地址也走 SpoilerThumbnail：传输列表的缩略图可能来自已下载的文件，
        // 只对 URL 做遮蔽的话，下载下来的东西反而绕过了防窥
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
        // 遮蔽时 SpoilerThumbnail 已画了遮蔽图标，再叠播放键就是两个图标摞在一起
        if (showPlayOverlay && activeThumbnail != null && !isSpoilerBlurred) {
            val fixed = dev.piko.ui.theme.LocalFixedColors.current
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
 * 没有缩略图时的类型图标块，外观与网盘文件（非文件夹）的图标块一致。自己定尺寸与圆角：原先铺满父布局，
 * 直接放进 ListItem 的前导槽时把整行宽度吃掉，标题与进度被挤成零宽（传输页的解压、归档行整行空白）。
 */
@Composable
fun ListLeadingIcon(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(ListLeadingSize)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
 * 归档标记：条目本身是归档记录（网盘里没有它的文件，打开时才临时取回），或文件夹里直接放着归档条目。
 * 图标与「归档」操作同一个。用 secondary：tertiary 给了星标，primary 给了定位与选中。
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
 * 标题旁的标记：星标与归档，都没有时为 null。[folderHasVault] 是文件夹里放着归档条目（直接放着的，
 * 或是归档时选的那一层），见 PikoDriveRepository.vaultedFolders。
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
