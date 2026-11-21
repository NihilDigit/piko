package dev.piko.ui.screens.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedToggleButton
import androidx.compose.material3.OutlinedToggleButtonDefaults
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.CardBadgeKind
import dev.piko.shared.data.CornerPosition
import dev.piko.shared.data.CornerSlots
import dev.piko.shared.data.DriveAppearanceMode
import dev.piko.shared.data.DriveAppearancePrefs
import dev.piko.shared.data.ThumbnailClarity
import dev.piko.shared.data.ThumbnailScale
import dev.piko.shared.i18n.tr
import dev.piko.ui.adaptive.isDesktopLayout
import dev.piko.ui.adaptive.readableWidth
import dev.piko.ui.components.BarItem
import dev.piko.ui.components.FileListItem
import dev.piko.ui.components.IslandHeaderSpace
import dev.piko.ui.components.IslandPage
import dev.piko.ui.components.IslandTitle
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.LocalPreviewArtworkRenderer
import dev.piko.ui.components.PikoDropdownMenu
import dev.piko.ui.components.PikoScaffold
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.PinnedPriority
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.connectedToggleShapes
import dev.piko.ui.components.menuItemShape
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.platform.captionExclusion
import dev.piko.ui.resources.*
import dev.piko.ui.screens.drive.GalleryCanvasTile
import dev.piko.ui.screens.drive.LocalDriveAppearancePrefs
import dev.piko.ui.screens.drive.PosterCard
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.LocalFixedColors
import dev.piko.ui.theme.isEInk
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat

private const val SAMPLE_VIDEO_SIZE_BYTES = 1_524_713_390L // ~1.42 GB
private const val SAMPLE_FOLDER_THUMB_SIZE_BYTES = 26_628_797_235L // ~24.8 GB
private const val SAMPLE_FOLDER_THUMB_DURATION_MS = 17_412_000L // 04:50:12
private const val SAMPLE_FOLDER_NO_THUMB_SIZE_BYTES = 4_509_715_660L // ~4.2 GB
private const val SAMPLE_FOLDER_NO_THUMB_DURATION_MS = 2_700_000L // 45:00

/** 交互式预览画布上可直接点按选中的编辑区域。 */
internal sealed interface DrivePreviewSlot {
    data class Corner(val position: CornerPosition) : DrivePreviewSlot
    data object Folder : DrivePreviewSlot
    data object TitleRow : DrivePreviewSlot
    data object Details : DrivePreviewSlot
}

internal fun defaultSlotFor(mode: DriveAppearanceMode): DrivePreviewSlot = when (mode) {
    DriveAppearanceMode.LIST -> DrivePreviewSlot.Folder
    DriveAppearanceMode.GRID -> DrivePreviewSlot.Corner(CornerPosition.BOTTOM_END)
    DriveAppearanceMode.GALLERY -> DrivePreviewSlot.Corner(CornerPosition.BOTTOM_END)
}

internal fun availableSlotsFor(mode: DriveAppearanceMode): List<DrivePreviewSlot> = when (mode) {
    DriveAppearanceMode.LIST -> listOf(
        DrivePreviewSlot.Folder,
        DrivePreviewSlot.TitleRow,
        DrivePreviewSlot.Details,
    )
    DriveAppearanceMode.GRID -> listOf(
        DrivePreviewSlot.Corner(CornerPosition.TOP_START),
        DrivePreviewSlot.Corner(CornerPosition.TOP_END),
        DrivePreviewSlot.Corner(CornerPosition.BOTTOM_START),
        DrivePreviewSlot.Corner(CornerPosition.BOTTOM_END),
        DrivePreviewSlot.Folder,
        DrivePreviewSlot.Details,
    )
    // 瀑布流仅展示媒体条目，不含文件夹
    DriveAppearanceMode.GALLERY -> listOf(
        DrivePreviewSlot.Corner(CornerPosition.TOP_START),
        DrivePreviewSlot.Corner(CornerPosition.TOP_END),
        DrivePreviewSlot.Corner(CornerPosition.BOTTOM_START),
        DrivePreviewSlot.Corner(CornerPosition.BOTTOM_END),
        DrivePreviewSlot.Details,
    )
}

internal data class DrivePreviewSamples(
    val videoFile: FileStat,
    val videoTitle: String,
    val videoRaw: String,
    val videoTags: List<String>,
    val folderWithThumbFile: FileStat,
    val folderWithThumbTitle: String,
    val folderNoThumbFile: FileStat,
    val folderNoThumbTitle: String,
)

@Composable
internal fun rememberDrivePreviewSamples(): DrivePreviewSamples {
    val videoTitle = stringResource(Res.string.drive_custom_sample_video_title)
    val videoRaw = stringResource(Res.string.drive_custom_sample_video_raw)
    val subTag = stringResource(Res.string.drive_custom_sample_sub_tag)
    val folderWithThumbTitle = stringResource(Res.string.drive_custom_sample_folder_title)
    val folderNoThumbTitle = stringResource(Res.string.drive_custom_sample_folder_no_thumb_title)
    return remember(videoTitle, videoRaw, subTag, folderWithThumbTitle, folderNoThumbTitle) {
        DrivePreviewSamples(
            videoFile = FileStat(
                kind = FileKind.FILE,
                id = "preview_video",
                name = videoRaw,
                size = SAMPLE_VIDEO_SIZE_BYTES.toString(),
                fileExtension = "mkv",
                mimeType = "video/x-matroska",
                createdTime = "2026-11-20T18:30:00Z",
                modifiedTime = "2026-11-20T18:30:00Z",
                thumbnailLink = PREVIEW_COVER_VIDEO,
                params = mapOf("duration" to "1452", "width" to "1920", "height" to "1080"),
                starred = true,
            ),
            videoTitle = videoTitle,
            videoRaw = videoRaw,
            videoTags = listOf("1080p", "HEVC", subTag),
            folderWithThumbFile = FileStat(
                kind = FileKind.FOLDER,
                id = "preview_folder_with_thumb",
                name = folderWithThumbTitle,
                createdTime = "2026-11-20T18:30:00Z",
                modifiedTime = "2026-11-20T18:30:00Z",
                thumbnailLink = PREVIEW_COVER_FOLDER,
                starred = true,
            ),
            folderWithThumbTitle = folderWithThumbTitle,
            folderNoThumbFile = FileStat(
                kind = FileKind.FOLDER,
                id = "preview_folder_no_thumb",
                name = folderNoThumbTitle,
                createdTime = "2026-11-18T10:15:00Z",
                modifiedTime = "2026-11-18T10:15:00Z",
                thumbnailLink = "",
            ),
            folderNoThumbTitle = folderNoThumbTitle,
        )
    }
}

/**
 * 网盘文件外观自定义页面：直接挂载真实的 `FileListItem`、`PosterCard` 与 `GalleryCanvasTile` 组件实时预览，
 * 列表与网格同时呈现 3 组典型样本（视频文件、有缩略图文件夹、无缩略图文件夹），瀑布流仅呈现媒体样本。
 * 点按预览中的任意区域或下方槽位切换按钮，即可原地调节该区域的外观选项。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DriveAppearanceCustomizationPage(
    prefs: DriveAppearancePrefs,
    onUpdate: (DriveAppearancePrefs) -> Unit,
    onBackClick: () -> Unit,
    initialMode: DriveAppearanceMode = DriveAppearanceMode.GRID,
    modifier: Modifier = Modifier,
) {
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var selectedSlot by remember { mutableStateOf(defaultSlotFor(initialMode)) }

    LaunchedEffect(mode) {
        if (selectedSlot !in availableSlotsFor(mode)) {
            selectedSlot = defaultSlotFor(mode)
        }
    }

    val pageTitle = stringResource(Res.string.drive_custom_title)
    val resetLabel = stringResource(Res.string.drive_custom_reset)
    val resetCurrentLabel = stringResource(Res.string.drive_custom_reset_current)
    val resetAllLabel = stringResource(Res.string.drive_custom_reset_all)
    val isCurrentDefault = when (mode) {
        DriveAppearanceMode.LIST -> prefs.list == DriveAppearancePrefs.DEFAULT.list
        DriveAppearanceMode.GRID -> prefs.grid == DriveAppearancePrefs.DEFAULT.grid
        DriveAppearanceMode.GALLERY -> prefs.gallery == DriveAppearancePrefs.DEFAULT.gallery
    }
    val isAllDefault = prefs == DriveAppearancePrefs.DEFAULT
    val resetOverflow = buildList {
        if (!isCurrentDefault) {
            add(SheetAction(Icons.Outlined.RestartAlt, resetCurrentLabel, { onUpdate(prefs.reset(mode)) }))
        }
        if (!isAllDefault) {
            add(SheetAction(Icons.Outlined.RestartAlt, resetAllLabel, { onUpdate(DriveAppearancePrefs.DEFAULT) }))
        }
    }

    PikoScaffold(
        modifier = modifier.fillMaxSize(),
        island = IslandPage(header = {
            TooltipIconButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                label = tr("返回", "Back", "戻る", "뒤로", "返回"),
                onClick = onBackClick,
            )
            IslandTitle(pageTitle, Modifier.padding(start = 4.dp))
            IslandHeaderSpace()
            ResetAppearanceSplitButton(
                resetLabel = resetLabel,
                resetCurrentLabel = resetCurrentLabel,
                resetAllLabel = resetAllLabel,
                canResetCurrent = !isCurrentDefault,
                canResetAll = !isAllDefault,
                onResetCurrent = { onUpdate(prefs.reset(mode)) },
                onResetAll = { onUpdate(DriveAppearancePrefs.DEFAULT) },
                modifier = Modifier.captionExclusion(),
            )
        }),
        topBar = {
            PikoTopBar(
                title = pageTitle,
                onBackClick = onBackClick,
                actions = listOf(
                    BarItem(
                        key = "reset_appearance",
                        priority = PinnedPriority,
                        overflow = resetOverflow,
                    ) {
                        ResetAppearanceSplitButton(
                            resetLabel = resetLabel,
                            resetCurrentLabel = resetCurrentLabel,
                            resetAllLabel = resetAllLabel,
                            canResetCurrent = !isCurrentDefault,
                            canResetAll = !isAllDefault,
                            onResetCurrent = { onUpdate(prefs.reset(mode)) },
                            onResetAll = { onUpdate(DriveAppearancePrefs.DEFAULT) },
                            compact = true,
                        )
                    },
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.readableWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DriveModeSelector(
                    selected = mode,
                    onSelect = { mode = it },
                )

                InteractiveDrivePreviewCard(
                    mode = mode,
                    prefs = prefs,
                    selectedSlot = selectedSlot,
                    onSelectSlot = { selectedSlot = it },
                )

                SlotInspectorCard(
                    mode = mode,
                    prefs = prefs,
                    selectedSlot = selectedSlot,
                    onSelectSlot = { selectedSlot = it },
                    onUpdate = onUpdate,
                )

                ThumbnailOptionsCard(
                    prefs = prefs,
                    onUpdate = onUpdate,
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * M3 Expressive `SplitButtonLayout` 恢复默认操作：
 * 主段一键恢复当前视图默认（或当当前视图已为默认时恢复全部默认），尾段展开菜单提供「恢复全部默认」，彻底消除一次性动作误用单选弹窗。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ResetAppearanceSplitButton(
    resetLabel: String,
    resetCurrentLabel: String,
    resetAllLabel: String,
    canResetCurrent: Boolean,
    canResetAll: Boolean,
    onResetCurrent: () -> Unit,
    onResetAll: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "reset_appearance_split_chevron",
    )
    val isEInk = LocalAppearance.current.isEInk
    val colors = MaterialTheme.colorScheme
    val tonalColors = if (isEInk) {
        ButtonDefaults.filledTonalButtonColors(
            containerColor = Color.White,
            contentColor = Color.Black,
        )
    } else {
        ButtonDefaults.filledTonalButtonColors()
    }
    val eInkBorder = if (isEInk) BorderStroke(1.dp, colors.outline) else null
    val primaryAction = if (canResetCurrent) onResetCurrent else onResetAll
    val primaryText = if (compact) resetLabel else if (canResetCurrent) resetCurrentLabel else resetAllLabel

    Box(modifier = modifier) {
        SplitButtonLayout(
            leadingButton = {
                SplitButtonDefaults.TonalLeadingButton(
                    onClick = primaryAction,
                    enabled = canResetAll,
                    colors = tonalColors,
                    border = eInkBorder,
                    contentPadding = PaddingValues(start = 12.dp, end = 10.dp),
                    modifier = Modifier.height(36.dp),
                ) {
                    Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(primaryText, maxLines = 1)
                }
            },
            trailingButton = {
                SplitButtonDefaults.TonalTrailingButton(
                    checked = expanded,
                    onCheckedChange = { expanded = it },
                    enabled = canResetAll,
                    colors = if (isEInk && expanded) {
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color.Black,
                            contentColor = Color.White,
                        )
                    } else {
                        tonalColors
                    },
                    border = eInkBorder,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.height(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ArrowDropDown,
                        contentDescription = resetLabel,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { rotationZ = chevronRotation },
                    )
                }
            },
        )
        PikoDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val items = buildList {
                if (canResetCurrent) add(resetCurrentLabel to onResetCurrent)
                if (canResetAll) add(resetAllLabel to onResetAll)
            }
            items.forEachIndexed { index, (title, action) ->
                DropdownMenuItem(
                    text = { Text(title) },
                    leadingIcon = { Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    shape = menuItemShape(index, items.size),
                    onClick = {
                        expanded = false
                        action()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DriveModeSelector(
    selected: DriveAppearanceMode,
    onSelect: (DriveAppearanceMode) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val isEInk = LocalAppearance.current.isEInk
    val minHeight = if (isDesktopLayout()) 40.dp else 48.dp
    val toggleColors = OutlinedToggleButtonDefaults.colors(
        containerColor = if (isEInk) Color.White else Color.Transparent,
        contentColor = if (isEInk) Color.Black else colors.onSurfaceVariant,
        checkedContainerColor = if (isEInk) Color.Black else colors.secondaryContainer,
        checkedContentColor = if (isEInk) Color.White else colors.onSecondaryContainer,
    )
    val modes = DriveAppearanceMode.entries
    Row(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        modes.forEachIndexed { index, item ->
            val checked = item == selected
            val icon = when (item) {
                DriveAppearanceMode.LIST -> Icons.AutoMirrored.Outlined.ViewList
                DriveAppearanceMode.GRID -> Icons.Outlined.GridView
                DriveAppearanceMode.GALLERY -> Icons.Outlined.Dashboard
            }
            val label = when (item) {
                DriveAppearanceMode.LIST -> stringResource(Res.string.drive_custom_mode_list)
                DriveAppearanceMode.GRID -> stringResource(Res.string.drive_custom_mode_grid)
                DriveAppearanceMode.GALLERY -> stringResource(Res.string.drive_custom_mode_gallery)
            }
            OutlinedToggleButton(
                checked = checked,
                onCheckedChange = { if (!checked) onSelect(item) },
                shapes = connectedToggleShapes(index, modes.size),
                colors = toggleColors,
                border = if (isEInk) {
                    BorderStroke(1.dp, colors.outline)
                } else {
                    OutlinedToggleButtonDefaults.border(enabled = true, checked = checked)
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.weight(1f).heightIn(min = minHeight),
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun InteractiveDrivePreviewCard(
    mode: DriveAppearanceMode,
    prefs: DriveAppearancePrefs,
    selectedSlot: DrivePreviewSlot,
    onSelectSlot: (DrivePreviewSlot) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val isEInk = LocalAppearance.current.isEInk
    val samples = rememberDrivePreviewSamples()
    val previewArtworkRenderer: @Composable (Any, Modifier) -> Boolean = remember {
        { model, mod ->
            when (model) {
                PREVIEW_COVER_VIDEO -> {
                    PreviewProceduralArtwork(variant = PreviewArtworkVariant.VideoNightSky, modifier = mod)
                    true
                }
                PREVIEW_COVER_FOLDER -> {
                    PreviewProceduralArtwork(variant = PreviewArtworkVariant.FolderSunsetDusk, modifier = mod)
                    true
                }
                else -> false
            }
        }
    }

    Surface(
        color = if (isEInk) Color.White else colors.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, if (isEInk) colors.outline else colors.outlineVariant.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(Res.string.drive_custom_preview_label),
                    style = MaterialTheme.typography.labelMediumEmphasized,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    text = stringResource(Res.string.drive_custom_preview_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 向真实组件注入当前编辑中的偏好与离线原创矢量封面绘制器，确保预览与网盘实际渲染 100% 同源
            CompositionLocalProvider(
                LocalDriveAppearancePrefs provides prefs,
                LocalPreviewArtworkRenderer provides previewArtworkRenderer,
            ) {
                when (mode) {
                    DriveAppearanceMode.LIST -> RealListPreview(
                        samples = samples,
                        selectedSlot = selectedSlot,
                        onSelectSlot = onSelectSlot,
                    )
                    DriveAppearanceMode.GRID -> RealGridPreview(
                        samples = samples,
                        corners = prefs.grid.corners,
                        selectedSlot = selectedSlot,
                        onSelectSlot = onSelectSlot,
                    )
                    DriveAppearanceMode.GALLERY -> RealGalleryPreview(
                        samples = samples,
                        corners = prefs.gallery.corners,
                        selectedSlot = selectedSlot,
                        onSelectSlot = onSelectSlot,
                    )
                }
            }
        }
    }
}

@Composable
private fun RealListPreview(
    samples: DrivePreviewSamples,
    selectedSlot: DrivePreviewSlot,
    onSelectSlot: (DrivePreviewSlot) -> Unit,
) {
    val folderSelected = selectedSlot == DrivePreviewSlot.Folder
    val videoSelected = selectedSlot == DrivePreviewSlot.TitleRow || selectedSlot == DrivePreviewSlot.Details
    val showMoreButton = LocalPointerSource.current.isTouchLike

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PreviewItemFocusFrame(selected = videoSelected) {
            FileListItem(
                file = samples.videoFile,
                isSelectionMode = false,
                isSelected = false,
                onClick = {
                    onSelectSlot(
                        if (selectedSlot == DrivePreviewSlot.TitleRow) {
                            DrivePreviewSlot.Details
                        } else {
                            DrivePreviewSlot.TitleRow
                        },
                    )
                },
                onLongClick = null,
                onSelectToggle = {},
                onMoreClick = {},
                moreButton = showMoreButton,
                title = samples.videoTitle,
                original = samples.videoRaw,
                tags = samples.videoTags,
            )
        }

        PreviewItemFocusFrame(selected = folderSelected) {
            FileListItem(
                file = samples.folderWithThumbFile,
                isSelectionMode = false,
                isSelected = false,
                onClick = { onSelectSlot(DrivePreviewSlot.Folder) },
                onLongClick = null,
                onSelectToggle = {},
                onMoreClick = {},
                moreButton = showMoreButton,
                title = samples.folderWithThumbTitle,
                folderHasVault = true,
                isEmptyFolder = false,
                folderItemCount = 12,
                folderDurationMillis = SAMPLE_FOLDER_THUMB_DURATION_MS,
                folderSizeBytes = SAMPLE_FOLDER_THUMB_SIZE_BYTES,
            )
        }

        PreviewItemFocusFrame(selected = folderSelected) {
            FileListItem(
                file = samples.folderNoThumbFile,
                isSelectionMode = false,
                isSelected = false,
                onClick = { onSelectSlot(DrivePreviewSlot.Folder) },
                onLongClick = null,
                onSelectToggle = {},
                onMoreClick = {},
                moreButton = showMoreButton,
                title = samples.folderNoThumbTitle,
                folderHasVault = false,
                isEmptyFolder = false,
                folderItemCount = 3,
                folderDurationMillis = SAMPLE_FOLDER_NO_THUMB_DURATION_MS,
                folderSizeBytes = SAMPLE_FOLDER_NO_THUMB_SIZE_BYTES,
            )
        }
    }
}

@Composable
private fun RealGridPreview(
    samples: DrivePreviewSamples,
    corners: CornerSlots,
    selectedSlot: DrivePreviewSlot,
    onSelectSlot: (DrivePreviewSlot) -> Unit,
) {
    var containerWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val threeColumnRow = containerWidthPx == 0 || with(density) { containerWidthPx.toDp() } >= 440.dp
    val selectedCorner = (selectedSlot as? DrivePreviewSlot.Corner)?.position
    val folderSelected = selectedSlot == DrivePreviewSlot.Folder
    val detailsSelected = selectedSlot == DrivePreviewSlot.Details
    val showMoreButton = LocalPointerSource.current.isTouchLike

    val videoCard: @Composable (Modifier) -> Unit = { cellModifier ->
        PreviewItemFocusFrame(selected = detailsSelected, modifier = cellModifier) {
            PosterCard(
                file = samples.videoFile,
                isSelectionMode = false,
                isSelected = false,
                isSpoilerBlurred = false,
                isHighlighted = false,
                onClick = { onSelectSlot(DrivePreviewSlot.Details) },
                onLongClick = null,
                onSelectToggle = {},
                onMoreClick = {},
                moreButton = showMoreButton,
                title = samples.videoTitle,
                original = samples.videoRaw,
                tags = samples.videoTags,
                resolution = "1080p",
                coverOverlay = {
                    InteractiveCornerQuadrantsOverlay(
                        corners = corners,
                        selectedCorner = selectedCorner,
                        onSelectCorner = { onSelectSlot(DrivePreviewSlot.Corner(it)) },
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            )
        }
    }

    val folderWithThumbCard: @Composable (Modifier) -> Unit = { cellModifier ->
        PreviewItemFocusFrame(selected = folderSelected, modifier = cellModifier) {
            PosterCard(
                file = samples.folderWithThumbFile,
                isSelectionMode = false,
                isSelected = false,
                isSpoilerBlurred = false,
                isHighlighted = false,
                onClick = { onSelectSlot(DrivePreviewSlot.Folder) },
                onLongClick = null,
                onSelectToggle = {},
                onMoreClick = {},
                moreButton = showMoreButton,
                isEmptyFolder = false,
                title = samples.folderWithThumbTitle,
                folderHasVault = true,
                folderItemCount = 12,
                folderDurationMillis = SAMPLE_FOLDER_THUMB_DURATION_MS,
                folderSizeBytes = SAMPLE_FOLDER_THUMB_SIZE_BYTES,
            )
        }
    }

    val folderNoThumbCard: @Composable (Modifier) -> Unit = { cellModifier ->
        PreviewItemFocusFrame(selected = folderSelected, modifier = cellModifier) {
            PosterCard(
                file = samples.folderNoThumbFile,
                isSelectionMode = false,
                isSelected = false,
                isSpoilerBlurred = false,
                isHighlighted = false,
                onClick = { onSelectSlot(DrivePreviewSlot.Folder) },
                onLongClick = null,
                onSelectToggle = {},
                onMoreClick = {},
                moreButton = showMoreButton,
                isEmptyFolder = false,
                title = samples.folderNoThumbTitle,
                folderHasVault = false,
                folderItemCount = 3,
                folderDurationMillis = SAMPLE_FOLDER_NO_THUMB_DURATION_MS,
                folderSizeBytes = SAMPLE_FOLDER_NO_THUMB_SIZE_BYTES,
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { containerWidthPx = it.width },
    ) {
        if (threeColumnRow) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                videoCard(Modifier.weight(1f))
                folderWithThumbCard(Modifier.weight(1f))
                folderNoThumbCard(Modifier.weight(1f))
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    videoCard(Modifier.weight(1f))
                    folderWithThumbCard(Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    folderNoThumbCard(Modifier.weight(1f))
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RealGalleryPreview(
    samples: DrivePreviewSamples,
    corners: CornerSlots,
    selectedSlot: DrivePreviewSlot,
    onSelectSlot: (DrivePreviewSlot) -> Unit,
) {
    val selectedCorner = (selectedSlot as? DrivePreviewSlot.Corner)?.position
    val detailsSelected = selectedSlot == DrivePreviewSlot.Details

    // 瀑布流仅展示媒体条目（不含文件夹），直接复用真实 GalleryCanvasTile
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        PreviewItemFocusFrame(
            selected = detailsSelected,
            modifier = Modifier.widthIn(max = 240.dp).fillMaxWidth(),
        ) {
            GalleryCanvasTile(
                file = samples.videoFile,
                onClick = { onSelectSlot(DrivePreviewSlot.Details) },
                coverAspectRatio = 4f / 3f,
                modifier = Modifier.fillMaxWidth(),
                coverOverlay = {
                    InteractiveCornerQuadrantsOverlay(
                        corners = corners,
                        selectedCorner = selectedCorner,
                        onSelectCorner = { onSelectSlot(DrivePreviewSlot.Corner(it)) },
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            )
        }
    }
}

/** 预览样本的轻量选中外框：仅用于指示当前正在编辑的关联组件，不改变内部真实组件尺寸与排版。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PreviewItemFocusFrame(
    selected: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val borderColor by animateColorAsState(
        targetValue = if (selected) colors.primary else Color.Transparent,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "drive_preview_frame_border",
    )
    Box(
        modifier = modifier
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, borderColor, shape)
            .padding(3.dp),
    ) {
        content()
    }
}

/**
 * 叠在真实封面上的四角透明交互热区：
 * - 非空角完全透明透出真实组件渲染的 `PosterCornerBadge`，仅在选中时沿外角绘一道细 L 形高亮框；
 * - 空角（`NONE`）在对应角落放一枚轻巧 `+` 引导圆标，方便用户直接点按配置。
 */
@Composable
private fun InteractiveCornerQuadrantsOverlay(
    corners: CornerSlots,
    selectedCorner: CornerPosition?,
    onSelectCorner: (CornerPosition) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            CornerQuadrantTarget(
                position = CornerPosition.TOP_START,
                kind = corners.topStart,
                selected = selectedCorner == CornerPosition.TOP_START,
                alignment = Alignment.TopStart,
                onClick = { onSelectCorner(CornerPosition.TOP_START) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            CornerQuadrantTarget(
                position = CornerPosition.TOP_END,
                kind = corners.topEnd,
                selected = selectedCorner == CornerPosition.TOP_END,
                alignment = Alignment.TopEnd,
                onClick = { onSelectCorner(CornerPosition.TOP_END) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            CornerQuadrantTarget(
                position = CornerPosition.BOTTOM_START,
                kind = corners.bottomStart,
                selected = selectedCorner == CornerPosition.BOTTOM_START,
                alignment = Alignment.BottomStart,
                onClick = { onSelectCorner(CornerPosition.BOTTOM_START) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            CornerQuadrantTarget(
                position = CornerPosition.BOTTOM_END,
                kind = corners.bottomEnd,
                selected = selectedCorner == CornerPosition.BOTTOM_END,
                alignment = Alignment.BottomEnd,
                onClick = { onSelectCorner(CornerPosition.BOTTOM_END) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CornerQuadrantTarget(
    position: CornerPosition,
    kind: CardBadgeKind,
    selected: Boolean,
    alignment: Alignment,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val isEInk = LocalAppearance.current.isEInk
    val fixed = LocalFixedColors.current
    val ringColor by animateColorAsState(
        targetValue = when {
            isEInk -> Color.Black
            selected -> colors.primary
            else -> fixed.HudSpecularBorder
        },
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "corner_target_${position.name}",
    )

    Box(
        modifier = modifier.clickable(role = Role.Button, onClick = onClick),
        contentAlignment = alignment,
    ) {
        if (kind == CardBadgeKind.NONE) {
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isEInk && selected -> Color.Black
                            isEInk -> Color.White
                            selected -> colors.primary.copy(alpha = 0.88f)
                            else -> fixed.ScrimOnMedia.copy(alpha = 0.45f)
                        },
                    )
                    .border(if (selected) 2.dp else 1.dp, ringColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = cornerPositionLabel(position),
                    tint = when {
                        isEInk && selected -> Color.White
                        isEInk -> Color.Black
                        selected -> colors.onPrimary
                        else -> fixed.OnMedia.copy(alpha = 0.85f)
                    },
                    modifier = Modifier.size(12.dp),
                )
            }
        } else if (selected) {
            val accent = if (isEInk) Color.Black else colors.primary
            Canvas(modifier = Modifier.padding(3.dp).size(18.dp)) {
                val stroke = 2.5.dp.toPx()
                val half = stroke / 2f
                val w = size.width
                val h = size.height
                val path = Path().apply {
                    when (position) {
                        CornerPosition.TOP_START -> {
                            moveTo(half, h)
                            lineTo(half, half)
                            lineTo(w, half)
                        }
                        CornerPosition.TOP_END -> {
                            moveTo(0f, half)
                            lineTo(w - half, half)
                            lineTo(w - half, h)
                        }
                        CornerPosition.BOTTOM_START -> {
                            moveTo(half, 0f)
                            lineTo(half, h - half)
                            lineTo(w, h - half)
                        }
                        CornerPosition.BOTTOM_END -> {
                            moveTo(0f, h - half)
                            lineTo(w - half, h - half)
                            lineTo(w - half, 0f)
                        }
                    }
                }
                drawPath(path, color = accent, style = Stroke(width = stroke, cap = StrokeCap.Round))
            }
        }
    }
}

/** 跨视图共用的缩略图设置卡：缩放方式（裁剪填满 / 完整不裁剪）与清晰度档位（省流 / 标准 / 高清）。 */
@Composable
private fun ThumbnailOptionsCard(
    prefs: DriveAppearancePrefs,
    onUpdate: (DriveAppearancePrefs) -> Unit,
) {
    val cropLabel = tr("裁剪填满", "Crop to fill", "切り抜いて表示", "채워서 자르기", "裁切填滿")
    val fitLabel = tr("完整不裁剪", "Fit without cropping", "切り抜かず全体表示", "자르지 않고 전체 표시", "完整不裁切")
    val lowLabel = tr("省流", "Low", "省データ", "절약", "省流量")
    val standardLabel = tr("标准", "Standard", "標準", "표준", "標準")
    val highLabel = tr("高清", "High", "高画質", "고화질", "高畫質")
    SettingsGroup(tr("缩略图", "Thumbnails", "サムネイル", "미리 보기 이미지", "縮圖")) {
        SettingsRow(
            icon = Icons.Outlined.AspectRatio,
            title = tr("显示比例", "Display fit", "表示比率", "표시 비율", "顯示比例"),
            supporting = if (prefs.thumbnailScale == ThumbnailScale.FIT) {
                tr("保持原图完整画面，不裁切边缘内容", "Show the entire thumbnail without cropping edges", "端を切り抜かずにサムネイル全体を表示します", "가장자리를 자르지 않고 전체 이미지를 표시합니다", "保持原圖完整畫面，不裁切邊緣內容")
            } else {
                tr("自动裁切填满封面区域，视觉更整齐", "Fill the cover frame cleanly by cropping excess edges", "カバー枠に合わせて自動的に切り抜きます", "커버 영역에 맞춰 자동으로 채웁니다", "自動裁切填滿封面區域，視覺更整齊")
            },
            below = {
                SettingsSegmentedChoice(
                    options = ThumbnailScale.entries,
                    selected = prefs.thumbnailScale,
                    label = { scale ->
                        when (scale) {
                            ThumbnailScale.CROP -> cropLabel
                            ThumbnailScale.FIT -> fitLabel
                        }
                    },
                    onSelect = { onUpdate(prefs.copy(thumbnailScale = it)) },
                    fill = true,
                )
            },
        )
        SettingsRow(
            icon = Icons.Outlined.HighQuality,
            title = tr("清晰度", "Clarity", "画質", "선명도", "清晰度"),
            supporting = when (prefs.thumbnailClarity) {
                ThumbnailClarity.LOW -> tr("更小解码尺寸（256px），列表滑动更省内存", "Lower decode resolution (256px) for lighter memory usage", "低解像度デコード（256px）でメモリ消費を抑えます", "낮은 디코딩 해상도(256px)로 메모리 절약", "較小解碼尺寸（256px），捲動更省記憶體")
                ThumbnailClarity.STANDARD -> tr("平衡清晰度与缓存体积（512px）", "Balanced sharpness and disk cache footprint (512px)", "画質とキャッシュ容量のバランス（512px）", "선명도와 캐시 용량의 균형 (512px)", "平衡清晰度與快取體積（512px）")
                ThumbnailClarity.HIGH -> tr("优先加载高清封面（1024px），画质更锐利", "Load high-resolution thumbnails (1024px) for sharper covers", "高解像度サムネイル（1024px）を優先して読み込みます", "고해상도 미리 보기(1024px) 우선 로드", "優先載入高畫質封面（1024px），畫質更銳利")
            },
            below = {
                SettingsSegmentedChoice(
                    options = ThumbnailClarity.entries,
                    selected = prefs.thumbnailClarity,
                    label = { clarity ->
                        when (clarity) {
                            ThumbnailClarity.LOW -> lowLabel
                            ThumbnailClarity.STANDARD -> standardLabel
                            ThumbnailClarity.HIGH -> highLabel
                        }
                    },
                    onSelect = { onUpdate(prefs.copy(thumbnailClarity = it)) },
                    fill = true,
                )
            },
        )
    }
}
