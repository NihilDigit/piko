package dev.piko.ui.screens.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedToggleButton
import androidx.compose.material3.OutlinedToggleButtonDefaults
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.CardBadgeKind
import dev.piko.shared.data.CornerPosition
import dev.piko.shared.data.CornerSlots
import dev.piko.shared.data.DetailField
import dev.piko.shared.data.DriveAppearanceMode
import dev.piko.shared.data.DriveAppearancePrefs
import dev.piko.shared.data.FolderStyle
import dev.piko.shared.data.ListViewStylePrefs
import dev.piko.shared.data.ThumbnailClarity
import dev.piko.shared.data.ThumbnailScale
import dev.piko.shared.data.toggleDetailField
import dev.piko.shared.i18n.tr
import dev.piko.ui.adaptive.readableWidth
import dev.piko.ui.components.FileListItem
import dev.piko.ui.components.IslandHeaderSpace
import dev.piko.ui.components.IslandPage
import dev.piko.ui.components.IslandTitle
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.LocalPreviewArtworkRenderer
import dev.piko.ui.components.PikoScaffold
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.PinnedPriority
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.connectedToggleShapes
import dev.piko.ui.components.iconBarItem
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.resources.*
import dev.piko.ui.screens.drive.GalleryCanvasTile
import dev.piko.ui.screens.drive.LocalDriveAppearancePrefs
import dev.piko.ui.screens.drive.PosterCard
import dev.piko.ui.theme.LocalFixedColors
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat

private const val PREVIEW_COVER_VIDEO = "piko-preview://video-night-sky"
private const val PREVIEW_COVER_FOLDER = "piko-preview://folder-sunset-dusk"

private const val SAMPLE_VIDEO_SIZE_BYTES = 1_524_713_390L // ~1.42 GB
private const val SAMPLE_FOLDER_THUMB_SIZE_BYTES = 26_628_797_235L // ~24.8 GB
private const val SAMPLE_FOLDER_THUMB_DURATION_MS = 17_412_000L // 04:50:12
private const val SAMPLE_FOLDER_NO_THUMB_SIZE_BYTES = 4_509_715_660L // ~4.2 GB
private const val SAMPLE_FOLDER_NO_THUMB_DURATION_MS = 2_700_000L // 45:00

/** 交互式预览画布上可直接点按选中的编辑区域。 */
private sealed interface DrivePreviewSlot {
    data class Corner(val position: CornerPosition) : DrivePreviewSlot
    data object Folder : DrivePreviewSlot
    data object TitleRow : DrivePreviewSlot
    data object Details : DrivePreviewSlot
}

private fun defaultSlotFor(mode: DriveAppearanceMode): DrivePreviewSlot = when (mode) {
    DriveAppearanceMode.LIST -> DrivePreviewSlot.Folder
    DriveAppearanceMode.GRID -> DrivePreviewSlot.Corner(CornerPosition.BOTTOM_END)
    DriveAppearanceMode.GALLERY -> DrivePreviewSlot.Corner(CornerPosition.BOTTOM_END)
}

private fun availableSlotsFor(mode: DriveAppearanceMode): List<DrivePreviewSlot> = when (mode) {
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

private data class DrivePreviewSamples(
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
private fun rememberDrivePreviewSamples(): DrivePreviewSamples {
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
 * 点按预览中的任意区域或下方槽位芯片，即可原地调节该区域的外观选项。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
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
    var resetMenuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(mode) {
        if (selectedSlot !in availableSlotsFor(mode)) {
            selectedSlot = defaultSlotFor(mode)
        }
    }

    val pageTitle = stringResource(Res.string.drive_custom_title)
    val resetLabel = stringResource(Res.string.drive_custom_reset)
    val isCurrentDefault = when (mode) {
        DriveAppearanceMode.LIST -> prefs.list == DriveAppearancePrefs.DEFAULT.list
        DriveAppearanceMode.GRID -> prefs.grid == DriveAppearancePrefs.DEFAULT.grid
        DriveAppearanceMode.GALLERY -> prefs.gallery == DriveAppearancePrefs.DEFAULT.gallery
    }
    val isAllDefault = prefs == DriveAppearancePrefs.DEFAULT

    PikoScaffold(
        modifier = modifier.fillMaxSize(),
        island = IslandPage(header = {
            TooltipIconButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                label = stringResource(Res.string.common_close),
                onClick = onBackClick,
            )
            IslandTitle(pageTitle, Modifier.padding(start = 4.dp))
            IslandHeaderSpace()
            TooltipIconButton(
                icon = Icons.Outlined.RestartAlt,
                label = resetLabel,
                onClick = { resetMenuOpen = true },
                enabled = !isAllDefault,
            )
        }),
        topBar = {
            PikoTopBar(
                title = pageTitle,
                onBackClick = onBackClick,
                actions = listOf(
                    iconBarItem(
                        icon = Icons.Outlined.RestartAlt,
                        label = resetLabel,
                        onClick = { resetMenuOpen = true },
                        priority = PinnedPriority,
                        enabled = !isAllDefault,
                    ),
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

    if (resetMenuOpen) {
        SettingsChoiceDialog(
            icon = Icons.Outlined.RestartAlt,
            title = resetLabel,
            onDismiss = { resetMenuOpen = false },
        ) {
            if (!isCurrentDefault) {
                SettingsChoiceOption(
                    title = stringResource(Res.string.drive_custom_reset_current),
                    selected = false,
                    onClick = {
                        resetMenuOpen = false
                        onUpdate(prefs.reset(mode))
                    },
                )
            }
            if (!isAllDefault) {
                SettingsChoiceOption(
                    title = stringResource(Res.string.drive_custom_reset_all),
                    selected = false,
                    onClick = {
                        resetMenuOpen = false
                        onUpdate(DriveAppearancePrefs.DEFAULT)
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
    val toggleColors = OutlinedToggleButtonDefaults.colors(
        containerColor = Color.Transparent,
        contentColor = colors.onSurfaceVariant,
        checkedContainerColor = colors.secondaryContainer,
        checkedContentColor = colors.onSecondaryContainer,
    )
    val modes = DriveAppearanceMode.entries
    Row(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        modes.forEachIndexed { index, item ->
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
                checked = item == selected,
                onCheckedChange = { onSelect(item) },
                shapes = connectedToggleShapes(index, modes.size),
                colors = toggleColors,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.weight(1f).height(38.dp),
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
        color = colors.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.45f)),
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
        // 样本 1：视频文件（真实 FileListItem）
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

        // 样本 2：有缩略图文件夹（真实 FileListItem）
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

        // 样本 3：无缩略图文件夹（真实 FileListItem）
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
            Box(modifier = Modifier.fillMaxWidth()) {
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
                )
                // 仅在封面四角叠加轻量透明点按热区与空角 + 号 / 选中角标框，绝不重绘真实 PosterCornerBadge
                InteractiveCornerQuadrantsOverlay(
                    corners = corners,
                    selectedCorner = selectedCorner,
                    onSelectCorner = { onSelectSlot(DrivePreviewSlot.Corner(it)) },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                )
            }
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
    val fixed = LocalFixedColors.current
    val ringColor by animateColorAsState(
        targetValue = if (selected) colors.primary else fixed.HudSpecularBorder,
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
                    .background(if (selected) colors.primary.copy(alpha = 0.88f) else fixed.ScrimOnMedia.copy(alpha = 0.45f))
                    .border(if (selected) 2.dp else 0.75.dp, ringColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = cornerPositionLabel(position),
                    tint = if (selected) colors.onPrimary else fixed.OnMedia.copy(alpha = 0.85f),
                    modifier = Modifier.size(12.dp),
                )
            }
        } else if (selected) {
            val accent = colors.primary
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

private enum class PreviewArtworkVariant {
    VideoNightSky,
    FolderSunsetDusk,
}

/** 100% 原创矢量程序封面（无网络依赖、无版权海报），经 `LocalPreviewArtworkRenderer` 注入真实组件渲染。 */
@Composable
private fun PreviewProceduralArtwork(
    variant: PreviewArtworkVariant,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val accentGlow = colors.primary
    val tertiaryGlow = colors.tertiary
    val scale = LocalDriveAppearancePrefs.current.thumbnailScale
    val intrinsicAspect = when (variant) {
        PreviewArtworkVariant.VideoNightSky -> 16f / 9f
        PreviewArtworkVariant.FolderSunsetDusk -> 4f / 3f
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .then(if (scale == ThumbnailScale.FIT) Modifier.background(colors.surfaceContainerHighest) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        // 严格按真实 AsyncImage 的 ContentScale.Fit / ContentScale.Crop 数学公式计算目标矩形
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cw = size.width
            val ch = size.height
            if (cw <= 0f || ch <= 0f) return@Canvas
            val containerAspect = cw / ch
            val drawW: Float
            val drawH: Float
            if (scale == ThumbnailScale.FIT) {
                if (containerAspect > intrinsicAspect) {
                    drawH = ch
                    drawW = ch * intrinsicAspect
                } else {
                    drawW = cw
                    drawH = cw / intrinsicAspect
                }
            } else {
                if (containerAspect > intrinsicAspect) {
                    drawW = cw
                    drawH = cw / intrinsicAspect
                } else {
                    drawH = ch
                    drawW = ch * intrinsicAspect
                }
            }
            val left = (cw - drawW) / 2f
            val top = (ch - drawH) / 2f
            val minDim = minOf(drawW, drawH)

            clipRect(
                left = left.coerceAtLeast(0f),
                top = top.coerceAtLeast(0f),
                right = (left + drawW).coerceAtMost(cw),
                bottom = (top + drawH).coerceAtMost(ch),
            ) {
                translate(left = left, top = top) {
                    val w = drawW
                    val h = drawH
                    when (variant) {
                        PreviewArtworkVariant.VideoNightSky -> {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color(0xFF15192E), Color(0xFF28204B), Color(0xFF5A335C)),
                                    startY = 0f,
                                    endY = h,
                                ),
                                size = Size(w, h),
                            )
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(accentGlow.copy(alpha = 0.48f), Color.Transparent),
                                    center = Offset(w * 0.72f, h * 0.30f),
                                    radius = minDim * 0.62f,
                                ),
                                center = Offset(w * 0.72f, h * 0.30f),
                                radius = minDim * 0.62f,
                            )
                            drawCircle(
                                color = Color(0xFFF6E7D8).copy(alpha = 0.88f),
                                radius = minDim * 0.13f,
                                center = Offset(w * 0.72f, h * 0.30f),
                            )
                            drawLine(
                                brush = Brush.linearGradient(
                                    colors = listOf(Color.Transparent, tertiaryGlow.copy(alpha = 0.45f), Color.Transparent),
                                    start = Offset(0f, h * 0.78f),
                                    end = Offset(w, h * 0.22f),
                                ),
                                start = Offset(0f, h * 0.78f),
                                end = Offset(w, h * 0.22f),
                                strokeWidth = (minDim * 0.045f).coerceAtLeast(2f),
                                cap = StrokeCap.Round,
                            )
                            val ridge = Path().apply {
                                moveTo(0f, h)
                                lineTo(0f, h * 0.66f)
                                lineTo(w * 0.22f, h * 0.66f)
                                lineTo(w * 0.22f, h * 0.56f)
                                lineTo(w * 0.44f, h * 0.56f)
                                lineTo(w * 0.54f, h * 0.70f)
                                lineTo(w * 0.80f, h * 0.70f)
                                lineTo(w * 0.80f, h * 0.60f)
                                lineTo(w, h * 0.60f)
                                lineTo(w, h)
                                close()
                            }
                            drawPath(ridge, color = Color(0xFF0E1122).copy(alpha = 0.78f))
                        }
                        PreviewArtworkVariant.FolderSunsetDusk -> {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color(0xFF2B1C3D), Color(0xFF8C3B4A), Color(0xFFE07A47)),
                                    startY = 0f,
                                    endY = h,
                                ),
                                size = Size(w, h),
                            )
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(Color(0xFFFFD88A).copy(alpha = 0.75f), Color.Transparent),
                                    center = Offset(w * 0.34f, h * 0.44f),
                                    radius = minDim * 0.58f,
                                ),
                                center = Offset(w * 0.34f, h * 0.44f),
                                radius = minDim * 0.58f,
                            )
                            drawCircle(
                                color = Color(0xFFFFF1C6).copy(alpha = 0.92f),
                                radius = minDim * 0.14f,
                                center = Offset(w * 0.34f, h * 0.44f),
                            )
                            val farPeak = Path().apply {
                                moveTo(0f, h)
                                lineTo(0f, h * 0.68f)
                                lineTo(w * 0.28f, h * 0.52f)
                                lineTo(w * 0.58f, h * 0.66f)
                                lineTo(w * 0.82f, h * 0.48f)
                                lineTo(w, h * 0.62f)
                                lineTo(w, h)
                                close()
                            }
                            drawPath(farPeak, color = Color(0xFF47213E).copy(alpha = 0.72f))
                            val nearPeak = Path().apply {
                                moveTo(0f, h)
                                lineTo(0f, h * 0.80f)
                                lineTo(w * 0.36f, h * 0.68f)
                                lineTo(w * 0.70f, h * 0.78f)
                                lineTo(w, h * 0.64f)
                                lineTo(w, h)
                                close()
                            }
                            drawPath(nearPeak, color = Color(0xFF20132B).copy(alpha = 0.84f))
                        }
                    }
                }
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

/** 紧随预览下方的上下文槽位检查器：顶部快速切换芯片 + 当前选中区域的精简控件。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SlotInspectorCard(
    mode: DriveAppearanceMode,
    prefs: DriveAppearancePrefs,
    selectedSlot: DrivePreviewSlot,
    onSelectSlot: (DrivePreviewSlot) -> Unit,
    onUpdate: (DriveAppearancePrefs) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val slots = remember(mode) { availableSlotsFor(mode) }

    Surface(
        color = colors.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                slots.forEach { slot ->
                    val isSelected = slot == selectedSlot
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectSlot(slot) },
                        label = { Text(slotLabel(slot, mode), maxLines = 1) },
                    )
                }
            }

            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.45f))

            when (selectedSlot) {
                is DrivePreviewSlot.Corner -> {
                    val corners = when (mode) {
                        DriveAppearanceMode.GRID -> prefs.grid.corners
                        DriveAppearanceMode.GALLERY -> prefs.gallery.corners
                        DriveAppearanceMode.LIST -> return@Column
                    }
                    CornerBadgeChips(
                        selected = corners[selectedSlot.position],
                        onSelect = { badge ->
                            val next = corners.assign(selectedSlot.position, badge)
                            when (mode) {
                                DriveAppearanceMode.GRID -> onUpdate(prefs.copy(grid = prefs.grid.copy(corners = next)))
                                DriveAppearanceMode.GALLERY -> onUpdate(prefs.copy(gallery = prefs.gallery.copy(corners = next)))
                                DriveAppearanceMode.LIST -> Unit
                            }
                        },
                    )
                }
                DrivePreviewSlot.Folder -> {
                    val currentStyle = when (mode) {
                        DriveAppearanceMode.LIST -> prefs.list.folderStyle
                        DriveAppearanceMode.GRID -> prefs.grid.folderStyle
                        DriveAppearanceMode.GALLERY -> return@Column
                    }
                    FolderStyleInspector(
                        selected = currentStyle,
                        onSelect = { style ->
                            when (mode) {
                                DriveAppearanceMode.LIST -> onUpdate(prefs.copy(list = prefs.list.copy(folderStyle = style)))
                                DriveAppearanceMode.GRID -> onUpdate(prefs.copy(grid = prefs.grid.copy(folderStyle = style)))
                                DriveAppearanceMode.GALLERY -> Unit
                            }
                        },
                    )
                }
                DrivePreviewSlot.TitleRow -> {
                    if (mode == DriveAppearanceMode.LIST) {
                        ListTitleRowInspector(
                            prefs = prefs.list,
                            onChange = { onUpdate(prefs.copy(list = it)) },
                        )
                    }
                }
                DrivePreviewSlot.Details -> when (mode) {
                    DriveAppearanceMode.LIST -> DetailFieldsChipGroup(
                        selected = prefs.list.detailFields,
                        onToggle = { field ->
                            onUpdate(
                                prefs.copy(
                                    list = prefs.list.copy(
                                        detailFields = toggleDetailField(prefs.list.detailFields, field),
                                    ),
                                ),
                            )
                        },
                    )
                    DriveAppearanceMode.GRID -> CardDetailsInspector(
                        showTitle = prefs.grid.showTitle,
                        showDetailLine = prefs.grid.showDetailLine,
                        detailFields = prefs.grid.detailFields,
                        onShowTitle = { onUpdate(prefs.copy(grid = prefs.grid.copy(showTitle = it))) },
                        onShowDetailLine = { onUpdate(prefs.copy(grid = prefs.grid.copy(showDetailLine = it))) },
                        onToggleField = { field ->
                            onUpdate(
                                prefs.copy(
                                    grid = prefs.grid.copy(
                                        detailFields = toggleDetailField(prefs.grid.detailFields, field),
                                    ),
                                ),
                            )
                        },
                    )
                    DriveAppearanceMode.GALLERY -> CardDetailsInspector(
                        showTitle = prefs.gallery.showTitle,
                        showDetailLine = prefs.gallery.showDetailLine,
                        detailFields = prefs.gallery.detailFields,
                        onShowTitle = { onUpdate(prefs.copy(gallery = prefs.gallery.copy(showTitle = it))) },
                        onShowDetailLine = { onUpdate(prefs.copy(gallery = prefs.gallery.copy(showDetailLine = it))) },
                        onToggleField = { field ->
                            onUpdate(
                                prefs.copy(
                                    gallery = prefs.gallery.copy(
                                        detailFields = toggleDetailField(prefs.gallery.detailFields, field),
                                    ),
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CornerBadgeChips(
    selected: CardBadgeKind,
    onSelect: (CardBadgeKind) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CardBadgeKind.entries.forEach { kind ->
            val isSelected = kind == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(kind) },
                label = { Text(badgeKindLabel(kind), maxLines = 1) },
                leadingIcon = if (isSelected) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun FolderStyleInspector(
    selected: FolderStyle,
    onSelect: (FolderStyle) -> Unit,
) {
    val skeuomorphicLabel = stringResource(Res.string.drive_custom_folder_skeuomorphic)
    val minimalLabel = stringResource(Res.string.drive_custom_folder_minimal)
    SettingsSegmentedChoice(
        options = FolderStyle.entries,
        selected = selected,
        label = { folderStyle ->
            when (folderStyle) {
                FolderStyle.SKEUOMORPHIC -> skeuomorphicLabel
                FolderStyle.MINIMAL_ICON -> minimalLabel
            }
        },
        onSelect = onSelect,
        fill = true,
    )
}

@Composable
private fun ListTitleRowInspector(
    prefs: ListViewStylePrefs,
    onChange: (ListViewStylePrefs) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SettingsSwitchRow(
            icon = Icons.Outlined.Subtitles,
            title = stringResource(Res.string.drive_custom_show_secondary_name),
            supporting = null,
            checked = prefs.showSecondaryName,
            onCheckedChange = { onChange(prefs.copy(showSecondaryName = it)) },
        )
        SettingsSwitchRow(
            icon = Icons.Outlined.Label,
            title = stringResource(Res.string.drive_custom_show_parsed_tags),
            supporting = null,
            checked = prefs.showParsedTags,
            onCheckedChange = { onChange(prefs.copy(showParsedTags = it)) },
        )
        SettingsSwitchRow(
            icon = Icons.Outlined.ViewAgenda,
            title = stringResource(Res.string.drive_custom_compact_density),
            supporting = null,
            checked = prefs.compactDensity,
            onCheckedChange = { onChange(prefs.copy(compactDensity = it)) },
        )
    }
}

@Composable
private fun CardDetailsInspector(
    showTitle: Boolean,
    showDetailLine: Boolean,
    detailFields: List<DetailField>,
    onShowTitle: (Boolean) -> Unit,
    onShowDetailLine: (Boolean) -> Unit,
    onToggleField: (DetailField) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsSwitchRow(
            icon = Icons.Outlined.Title,
            title = stringResource(Res.string.drive_custom_show_title),
            supporting = null,
            checked = showTitle,
            onCheckedChange = onShowTitle,
        )
        SettingsSwitchRow(
            icon = Icons.Outlined.Subtitles,
            title = stringResource(Res.string.drive_custom_show_detail_line),
            supporting = null,
            checked = showDetailLine,
            onCheckedChange = onShowDetailLine,
        )
        DependentRow(visible = showDetailLine) {
            Column(
                modifier = Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(Res.string.drive_custom_detail_fields_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DetailFieldsChipGroup(
                    selected = detailFields,
                    onToggle = onToggleField,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailFieldsChipGroup(
    selected: List<DetailField>,
    onToggle: (DetailField) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DetailField.entries.forEach { field ->
            val isSelected = field in selected
            FilterChip(
                selected = isSelected,
                onClick = { onToggle(field) },
                label = { Text(detailFieldLabel(field), maxLines = 1) },
                leadingIcon = if (isSelected) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun slotLabel(slot: DrivePreviewSlot, mode: DriveAppearanceMode): String = when (slot) {
    is DrivePreviewSlot.Corner -> cornerPositionLabel(slot.position)
    DrivePreviewSlot.Folder -> stringResource(Res.string.drive_custom_section_folder)
    DrivePreviewSlot.TitleRow -> stringResource(Res.string.drive_custom_slot_title_row)
    DrivePreviewSlot.Details -> if (mode == DriveAppearanceMode.LIST) {
        stringResource(Res.string.drive_custom_detail_fields_label)
    } else {
        stringResource(Res.string.drive_custom_section_text)
    }
}

@Composable
private fun cornerPositionLabel(position: CornerPosition): String = when (position) {
    CornerPosition.TOP_START -> stringResource(Res.string.drive_custom_corner_top_start)
    CornerPosition.TOP_END -> stringResource(Res.string.drive_custom_corner_top_end)
    CornerPosition.BOTTOM_START -> stringResource(Res.string.drive_custom_corner_bottom_start)
    CornerPosition.BOTTOM_END -> stringResource(Res.string.drive_custom_corner_bottom_end)
}

@Composable
private fun badgeKindLabel(kind: CardBadgeKind): String = when (kind) {
    CardBadgeKind.NONE -> stringResource(Res.string.drive_custom_badge_none)
    CardBadgeKind.DURATION -> stringResource(Res.string.drive_custom_badge_duration)
    CardBadgeKind.RESOLUTION -> stringResource(Res.string.drive_custom_badge_resolution)
    CardBadgeKind.PARSED_TAGS -> stringResource(Res.string.drive_custom_badge_parsed_tags)
    CardBadgeKind.FILE_SIZE -> stringResource(Res.string.drive_custom_badge_file_size)
    CardBadgeKind.STATUS_BADGES -> stringResource(Res.string.drive_custom_badge_status)
    CardBadgeKind.EXTENSION -> stringResource(Res.string.drive_custom_badge_extension)
}

@Composable
private fun detailFieldLabel(field: DetailField): String = when (field) {
    DetailField.SIZE -> stringResource(Res.string.drive_custom_field_size)
    DetailField.DURATION -> stringResource(Res.string.drive_custom_field_duration)
    DetailField.ADDED_TIME -> stringResource(Res.string.drive_custom_field_added_time)
    DetailField.PARSED_TAGS -> stringResource(Res.string.drive_custom_field_parsed_tags)
    DetailField.ITEM_COUNT -> stringResource(Res.string.drive_custom_field_item_count)
    DetailField.EXTENSION -> stringResource(Res.string.drive_custom_field_extension)
    DetailField.VAULT_STATUS -> stringResource(Res.string.drive_custom_field_vault_status)
}
