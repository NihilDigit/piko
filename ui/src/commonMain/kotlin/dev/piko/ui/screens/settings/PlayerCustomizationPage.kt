package dev.piko.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.SwipeVertical
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.tr
import dev.piko.shared.state.Clip
import dev.piko.shared.media.player.CenterSlotAction
import dev.piko.shared.media.player.CenterSlotConfig
import dev.piko.shared.media.player.CenterSlotPosition
import dev.piko.shared.media.player.CenterSlotScale
import dev.piko.shared.media.player.ListenCoverStyle
import dev.piko.shared.media.player.ListenLayoutConfig
import dev.piko.shared.media.player.ListenQuickChip
import dev.piko.shared.media.player.MAX_DOUBLE_TAP_ZONE_FRACTION
import dev.piko.shared.media.player.MAX_VERTICAL_ZONE_FRACTION
import dev.piko.shared.media.player.MIN_DOUBLE_TAP_ZONE_FRACTION
import dev.piko.shared.media.player.MIN_VERTICAL_ZONE_FRACTION
import dev.piko.shared.media.player.PlayerBarSlot
import dev.piko.shared.media.player.PlayerControlId
import dev.piko.shared.media.player.PlayerFormFactorLayout
import dev.piko.shared.media.player.PlayerGestureConfig
import dev.piko.shared.media.player.PlayerLayoutMode
import dev.piko.shared.media.player.PlayerLayoutPrefs
import dev.piko.shared.media.player.PlayerLayoutPreset
import dev.piko.shared.media.player.PlayerPanelItem
import dev.piko.shared.media.player.SEEK_SECONDS_PRESETS
import dev.piko.shared.media.player.SwipeSeekSensitivity
import dev.piko.shared.media.player.VerticalSwipeAction
import dev.piko.shared.media.player.allowedControlsFor
import dev.piko.shared.media.player.allowsSpacerIn
import dev.piko.ui.adaptive.readableWidth
import dev.piko.ui.components.IslandHeaderSpace
import dev.piko.ui.components.IslandPage
import dev.piko.ui.components.IslandTitle
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.PikoScaffold
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.PinnedPriority
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.connectedToggleShapes
import dev.piko.ui.components.iconBarItem
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.platform.PlayerNetworkType
import dev.piko.ui.platform.PlayerSystemStatusInfo
import dev.piko.ui.resources.*
import dev.piko.ui.screens.clips.BottomScrim as ClipBottomScrim
import dev.piko.ui.screens.clips.ClipCaption
import dev.piko.ui.screens.clips.ScopeTitle
import dev.piko.ui.screens.clips.TopScrim as ClipTopScrim
import dev.piko.ui.screens.clips.chromeContainer
import dev.piko.ui.screens.player.BottomScrim
import dev.piko.ui.screens.player.ControlBarTransportCluster
import dev.piko.ui.screens.player.FullscreenPromptButtonContent
import dev.piko.ui.screens.player.ListenActionPill
import dev.piko.ui.screens.player.ListenStaticCover
import dev.piko.ui.screens.player.ListenTimeReadout
import dev.piko.ui.screens.player.ListenTransportRow
import dev.piko.ui.screens.player.ListenVinylDiscVisual
import dev.piko.ui.screens.player.PlayerCenterCluster
import dev.piko.ui.screens.player.PlayerChipButton
import dev.piko.ui.screens.player.PlayerIconButton
import dev.piko.ui.screens.player.PlayerSeekBar
import dev.piko.ui.screens.player.PlayerSystemStatusCapsule
import dev.piko.ui.screens.player.PlayerTextShadow
import dev.piko.ui.screens.player.PlayerTheme
import dev.piko.ui.screens.player.PlayerTimeReadout
import dev.piko.ui.screens.player.PlayerVolumeControl
import dev.piko.ui.screens.player.TopScrim
import dev.piko.ui.theme.LocalFixedColors
import dev.piko.ui.theme.tabular
import kotlin.math.roundToInt

private enum class CustomizationTab {
    VideoLandscape,
    VideoPortrait,
    ClipsLandscape,
    ClipsPortrait,
    Listen,
    Gestures,
    ;

    fun toLayoutModeOrNull(): PlayerLayoutMode? = when (this) {
        VideoLandscape -> PlayerLayoutMode.VideoLandscape
        VideoPortrait -> PlayerLayoutMode.VideoPortrait
        ClipsLandscape -> PlayerLayoutMode.ClipsLandscape
        ClipsPortrait -> PlayerLayoutMode.ClipsPortrait
        Listen, Gestures -> null
    }
}

private sealed interface SlotSelection {
    data class CenterSlot(val position: CenterSlotPosition) : SlotSelection
    data class PanelItem(val bar: PlayerBarSlot, val index: Int) : SlotSelection
    data object PortraitFullscreenPrompt : SlotSelection
}

/**
 * 播放器自定义页面：覆盖四态（横屏视频 / 竖屏视频 / 横屏信息流 / 竖屏信息流）、听音频与手势。
 * 提供实时交互槽位预览、单点选中属性检查器、可用控件托盘与恢复默认。
 * 遵循 M3 Expressive 与 Piko 安全约定：无 `ListItem`/`SegmentedListItem`，无弹窗外层 `BoxWithConstraints`。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun PlayerCustomizationPage(
    prefs: PlayerLayoutPrefs,
    onUpdate: ((PlayerLayoutPrefs) -> PlayerLayoutPrefs) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeTab by rememberSaveable { mutableStateOf(CustomizationTab.VideoLandscape) }
    val activeMode = activeTab.toLayoutModeOrNull()
    val activeLayout = activeMode?.let(prefs::layoutFor)
    val defaultTouchPreview = LocalPointerSource.current.isTouchLike
    var previewTouchMode by rememberSaveable { mutableStateOf(defaultTouchPreview) }
    var selection by remember { mutableStateOf<SlotSelection?>(null) }
    var selectedListenSlot by rememberSaveable { mutableStateOf(CenterSlotPosition.InnerLeft) }
    var resetDialogOpen by remember { mutableStateOf(false) }
    var containerWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    // 切换模式或栏位长度变化时自动修正越界选中索引
    LaunchedEffect(activeTab, activeLayout?.topBar?.size, activeLayout?.bottomBar?.size) {
        val current = selection
        if (activeLayout == null) {
            selection = null
            return@LaunchedEffect
        }
        if (current == SlotSelection.PortraitFullscreenPrompt && activeMode != PlayerLayoutMode.VideoPortrait) {
            selection = null
            return@LaunchedEffect
        }
        if (current is SlotSelection.CenterSlot && activeMode?.isClips == true) {
            selection = null
            return@LaunchedEffect
        }
        if (current is SlotSelection.PanelItem) {
            val size = activeLayout.itemsFor(current.bar).size
            if (current.index !in 0 until size) {
                selection = if (size > 0) SlotSelection.PanelItem(current.bar, size - 1) else null
            }
        }
    }

    val pageTitle = stringResource(Res.string.player_custom_title)
    val resetLabel = stringResource(Res.string.player_custom_reset)
    val isAllDefault = prefs == PlayerLayoutPrefs.Default

    if (resetDialogOpen) {
        SettingsChoiceDialog(
            icon = Icons.Outlined.RestartAlt,
            title = resetLabel,
            onDismiss = { resetDialogOpen = false },
        ) {
            if (activeMode != null) {
                SettingsChoiceOption(
                    title = stringResource(Res.string.player_custom_reset_current),
                    selected = false,
                    onClick = {
                        onUpdate { it.resetMode(activeMode) }
                        resetDialogOpen = false
                    },
                )
            } else if (activeTab == CustomizationTab.Listen) {
                SettingsChoiceOption(
                    title = stringResource(Res.string.player_custom_reset_current),
                    selected = false,
                    onClick = {
                        onUpdate { it.copy(listen = ListenLayoutConfig.Default).sanitize() }
                        resetDialogOpen = false
                    },
                )
            } else {
                SettingsChoiceOption(
                    title = stringResource(Res.string.player_custom_reset_current),
                    selected = false,
                    onClick = {
                        onUpdate { it.copy(gestures = PlayerGestureConfig.Default).sanitize() }
                        resetDialogOpen = false
                    },
                )
            }
            SettingsChoiceOption(
                title = stringResource(Res.string.player_custom_reset_all),
                selected = false,
                onClick = {
                    onUpdate { PlayerLayoutPrefs.Default }
                    resetDialogOpen = false
                },
            )
        }
    }

    PikoScaffold(
        modifier = modifier.fillMaxSize(),
        island = IslandPage(header = {
            TooltipIconButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                label = stringResource(Res.string.player_custom_ctrl_back),
                onClick = onBackClick,
            )
            IslandTitle(pageTitle)
            IslandHeaderSpace()
            if (!isAllDefault) {
                TooltipIconButton(
                    icon = Icons.Outlined.RestartAlt,
                    label = resetLabel,
                    onClick = { resetDialogOpen = true },
                )
            }
        }),
        topBar = {
            PikoTopBar(
                title = pageTitle,
                onBackClick = onBackClick,
                actions = buildList {
                    if (!isAllDefault) {
                        add(
                            iconBarItem(
                                icon = Icons.Outlined.RestartAlt,
                                label = resetLabel,
                                priority = PinnedPriority,
                                onClick = { resetDialogOpen = true },
                            ),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val isWideTwoPane = with(density) { containerWidthPx.toDp() } >= 880.dp
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .onSizeChanged { containerWidthPx = it.width },
        ) {
            if (isWideTwoPane) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(0.58f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CustomizationTabSelector(
                            selected = activeTab,
                            onSelect = { activeTab = it },
                        )
                        PreviewPaneContent(
                            activeTab = activeTab,
                            activeMode = activeMode,
                            activeLayout = activeLayout,
                            prefs = prefs,
                            previewTouchMode = previewTouchMode,
                            onPreviewTouchModeChange = { previewTouchMode = it },
                            selection = selection,
                            onSelect = { selection = it },
                            selectedListenSlot = selectedListenSlot,
                            onSelectListenSlot = { selectedListenSlot = it },
                            onUpdate = onUpdate,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(0.42f)
                            .widthIn(min = 300.dp, max = 440.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        EditorPaneContent(
                            activeTab = activeTab,
                            activeMode = activeMode,
                            activeLayout = activeLayout,
                            prefs = prefs,
                            selection = selection,
                            onSelect = { selection = it },
                            selectedListenSlot = selectedListenSlot,
                            onUpdate = onUpdate,
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .readableWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CustomizationTabSelector(
                        selected = activeTab,
                        onSelect = { activeTab = it },
                    )
                    PreviewPaneContent(
                        activeTab = activeTab,
                        activeMode = activeMode,
                        activeLayout = activeLayout,
                        prefs = prefs,
                        previewTouchMode = previewTouchMode,
                        onPreviewTouchModeChange = { previewTouchMode = it },
                        selection = selection,
                        onSelect = { selection = it },
                        selectedListenSlot = selectedListenSlot,
                        onSelectListenSlot = { selectedListenSlot = it },
                        onUpdate = onUpdate,
                    )
                    EditorPaneContent(
                        activeTab = activeTab,
                        activeMode = activeMode,
                        activeLayout = activeLayout,
                        prefs = prefs,
                        selection = selection,
                        onSelect = { selection = it },
                        selectedListenSlot = selectedListenSlot,
                        onUpdate = onUpdate,
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CustomizationTabSelector(
    selected: CustomizationTab,
    onSelect: (CustomizationTab) -> Unit,
) {
    val tabs = CustomizationTab.entries
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        tabs.forEachIndexed { index, tab ->
            FilledTonalToggleButton(
                checked = tab == selected,
                onCheckedChange = { onSelect(tab) },
                shapes = connectedToggleShapes(index, tabs.size),
            ) {
                Text(tabLabel(tab), maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewPaneContent(
    activeTab: CustomizationTab,
    activeMode: PlayerLayoutMode?,
    activeLayout: PlayerFormFactorLayout?,
    prefs: PlayerLayoutPrefs,
    previewTouchMode: Boolean,
    onPreviewTouchModeChange: (Boolean) -> Unit,
    selection: SlotSelection?,
    onSelect: (SlotSelection?) -> Unit,
    selectedListenSlot: CenterSlotPosition,
    onSelectListenSlot: (CenterSlotPosition) -> Unit,
    onUpdate: ((PlayerLayoutPrefs) -> PlayerLayoutPrefs) -> Unit,
) {
    when {
        activeMode != null && activeLayout != null -> {
            if (activeMode.isVideo) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = previewTouchMode,
                            onClick = { onPreviewTouchModeChange(true) },
                            label = { Text(stringResource(Res.string.player_custom_preview_touch)) },
                        )
                        FilterChip(
                            selected = !previewTouchMode,
                            onClick = { onPreviewTouchModeChange(false) },
                            label = { Text(stringResource(Res.string.player_custom_preview_mouse)) },
                        )
                    }
                }
            }

            PlayerPreviewCanvas(
                mode = activeMode,
                layout = activeLayout,
                statusBar = prefs.statusBar,
                showPortraitFullscreenPrompt = prefs.showPortraitFullscreenPrompt,
                transportInCenter = activeMode.isVideo &&
                    prefs.isCenterControlsActive(previewTouchMode) &&
                    activeLayout.center.showCenterPlay,
                selection = selection,
                onSelect = onSelect,
                onTogglePortraitFullscreenPrompt = {
                    onUpdate { it.copy(showPortraitFullscreenPrompt = !it.showPortraitFullscreenPrompt) }
                    onSelect(SlotSelection.PortraitFullscreenPrompt)
                },
            )

            PresetAndTouchCard(
                prefs = prefs,
                onUpdate = onUpdate,
            )
        }

        activeTab == CustomizationTab.Listen -> {
            ListenPreviewCanvas(
                config = prefs.listen,
                selectedSlot = selectedListenSlot,
                onSelectSlot = onSelectListenSlot,
            )
        }

        activeTab == CustomizationTab.Gestures -> {
            GesturePreviewCanvas(gestures = prefs.gestures)
        }
    }
}

@Composable
private fun EditorPaneContent(
    activeTab: CustomizationTab,
    activeMode: PlayerLayoutMode?,
    activeLayout: PlayerFormFactorLayout?,
    prefs: PlayerLayoutPrefs,
    selection: SlotSelection?,
    onSelect: (SlotSelection?) -> Unit,
    selectedListenSlot: CenterSlotPosition,
    onUpdate: ((PlayerLayoutPrefs) -> PlayerLayoutPrefs) -> Unit,
) {
    when {
        activeMode != null && activeLayout != null -> {
            SelectionInspectorCard(
                mode = activeMode,
                layout = activeLayout,
                prefs = prefs,
                selection = selection,
                onSelect = onSelect,
                onUpdate = onUpdate,
            )
            ControlPaletteCard(
                mode = activeMode,
                layout = activeLayout,
                onAddControl = { bar, id ->
                    val nextIndex = activeLayout.itemsFor(bar).size
                    onUpdate { p ->
                        p.updateLayout(activeMode) {
                            it.addToBar(bar, PlayerPanelItem.Control(id), activeMode)
                        }
                    }
                    onSelect(SlotSelection.PanelItem(bar, nextIndex))
                },
                onAddSpacer = { bar ->
                    val nextIndex = activeLayout.itemsFor(bar).size
                    onUpdate { p ->
                        p.updateLayout(activeMode) {
                            it.addToBar(bar, PlayerPanelItem.Spacer, activeMode)
                        }
                    }
                    onSelect(SlotSelection.PanelItem(bar, nextIndex))
                },
            )
        }

        activeTab == CustomizationTab.Listen -> {
            ListenCustomizationEditor(
                config = prefs.listen,
                seekSeconds = prefs.seekButtonSeconds,
                selectedSlot = selectedListenSlot,
                onUpdate = onUpdate,
            )
        }

        activeTab == CustomizationTab.Gestures -> {
            GestureCustomizationEditor(
                prefs = prefs,
                onUpdate = onUpdate,
            )
        }
    }
}

private val PreviewSampleSystemStatus = PlayerSystemStatusInfo(
    batteryPercent = 86,
    isCharging = false,
    networkType = PlayerNetworkType.Wifi,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayerPreviewCanvas(
    mode: PlayerLayoutMode,
    layout: PlayerFormFactorLayout,
    statusBar: dev.piko.shared.media.player.PlayerStatusBarConfig,
    showPortraitFullscreenPrompt: Boolean,
    transportInCenter: Boolean,
    selection: SlotSelection?,
    onSelect: (SlotSelection) -> Unit,
    onTogglePortraitFullscreenPrompt: () -> Unit,
) {
    val canvasModifier = when {
        mode.isLandscape -> Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        mode == PlayerLayoutMode.ClipsPortrait -> Modifier.fillMaxWidth().height(450.dp)
        else -> Modifier.fillMaxWidth().height(420.dp)
    }
    val sampleFolder = stringResource(Res.string.drive_custom_sample_folder_title)
    val sampleClipName = stringResource(Res.string.player_custom_sample_title)
    val sampleClip = remember(sampleClipName) {
        Clip(
            fileId = "preview-clip",
            name = sampleClipName,
            parentId = "preview-folder",
            startMs = 754_000L,
            videoDurationMs = 2_700_000L,
            occurrence = 1L,
        )
    }
    PlayerTheme {
        val primary = MaterialTheme.colorScheme.primary
        // 预览画布内禁用 M3 默认 48dp 最小触控尺寸外扩，彻底消除微缩圆形按钮的 48dp 阴影/边框重叠
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF101318),
                    modifier = if (mode.isLandscape) canvasModifier else Modifier.widthIn(max = 316.dp).then(canvasModifier),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color(0xFF18202B), Color(0xFF0C0F14), Color(0xFF141922)),
                                ),
                            ),
                    ) {
                        // 顶栏预览：复用真实 TopScrim / ClipTopScrim、ScopeTitle、PlayerSystemStatusCapsule、PlayerIconButton 等控件
                        PreviewBarRow(
                            mode = mode,
                            bar = PlayerBarSlot.Top,
                            items = layout.topBar,
                            layout = layout,
                            statusBar = statusBar,
                            transportInCenter = transportInCenter,
                            selection = selection,
                            accent = primary,
                            onSelect = onSelect,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .background(Brush.verticalGradient(if (mode.isClips) ClipTopScrim else TopScrim))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )

                        if (mode.isVideo) {
                            // 视频模式中央 5 槽位簇预览：直接复用真实 PlayerCenterCluster(previewMode = true)；
                            // 键鼠视角或关闭中央播放键时主控组迁入底栏 PlayPause 槽位，画面中央不重复渲染
                            if (transportInCenter) {
                                PlayerCenterCluster(
                                    config = layout.center,
                                    centerVisible = true,
                                    showSideButtons = true,
                                    isPlaying = false,
                                    isLoading = false,
                                    indicator = null,
                                    large = mode.isLandscape,
                                    canPrevious = true,
                                    canNext = true,
                                    allowSeek = true,
                                    onPlayPause = { onSelect(SlotSelection.CenterSlot(CenterSlotPosition.Center)) },
                                    onPrevious = {},
                                    onNext = {},
                                    onSeekDeltaMillis = {},
                                    previewMode = true,
                                    selectedSlot = (selection as? SlotSelection.CenterSlot)?.position,
                                    onSelectSlot = { pos -> onSelect(SlotSelection.CenterSlot(pos)) },
                                    scaleMultiplier = if (mode.isLandscape) 0.78f else 0.64f,
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            }

                            // 竖屏视频悬浮「全屏播放」入口（复用真实 FullscreenPromptButtonContent，并与底栏保持充足净空）
                            if (mode == PlayerLayoutMode.VideoPortrait) {
                                val promptSelected = selection == SlotSelection.PortraitFullscreenPrompt
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 104.dp)
                                        .then(
                                            if (promptSelected) {
                                                Modifier
                                                    .border(2.dp, primary, CircleShape)
                                                    .padding(3.dp)
                                            } else {
                                                Modifier
                                            },
                                        ),
                                ) {
                                    FullscreenPromptButtonContent(
                                        onClick = onTogglePortraitFullscreenPrompt,
                                        muted = !showPortraitFullscreenPrompt,
                                        modifier = Modifier.alpha(if (showPortraitFullscreenPrompt) 1f else 0.58f),
                                    )
                                }
                            }

                            // 视频底部真实 PlayerSeekBar（直线进度条）+ 底栏预览
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .background(Brush.verticalGradient(BottomScrim))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                PlayerSeekBar(
                                    positionMillisProvider = { 754_000L },
                                    durationMillis = 2_700_000L,
                                    bufferedPositionMillisProvider = { 1_350_000L },
                                    onSeek = {},
                                    isPlaying = false,
                                    thumbOnHoverOnly = false,
                                    wavy = false,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                PreviewBarRow(
                                    mode = mode,
                                    bar = PlayerBarSlot.Bottom,
                                    items = layout.bottomBar,
                                    layout = layout,
                                    statusBar = statusBar,
                                    transportInCenter = transportInCenter,
                                    selection = selection,
                                    accent = primary,
                                    onSelect = onSelect,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        } else {
                            // 信息流（ClipsPortrait 竖屏右列带标签 / ClipsLandscape 横屏右下横排纯图标）与真实 ClipFeedScreen + ClipChrome 1:1 对齐
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .fillMaxHeight(if (mode.isLandscape) 0.44f else 0.52f),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Brush.verticalGradient(ClipBottomScrim)),
                                )
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .padding(
                                            start = if (mode.isLandscape) 16.dp else 12.dp,
                                            end = if (mode.isLandscape) 14.dp else 8.dp,
                                            bottom = 24.dp,
                                        ),
                                    horizontalArrangement = if (mode.isLandscape) Arrangement.SpaceBetween else Arrangement.Start,
                                    verticalAlignment = Alignment.Bottom,
                                ) {
                                    ClipCaption(
                                        clip = sampleClip,
                                        startMs = sampleClip.startMs,
                                        folderName = sampleFolder,
                                        compact = true,
                                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                                    )
                                    PreviewClipActionRail(
                                        items = layout.bottomBar,
                                        horizontal = mode.isLandscape,
                                        selection = selection,
                                        accent = primary,
                                        onSelect = onSelect,
                                    )
                                }
                                PlayerSeekBar(
                                    positionMillisProvider = { 12_000L },
                                    durationMillis = 30_000L,
                                    bufferedPositionMillisProvider = { 24_000L },
                                    onSeek = {},
                                    isPlaying = false,
                                    thumbOnHoverOnly = true,
                                    wavy = false,
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .padding(horizontal = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewClipActionRail(
    items: List<PlayerPanelItem>,
    horizontal: Boolean,
    selection: SlotSelection?,
    accent: Color,
    onSelect: (SlotSelection) -> Unit,
) {
    val fixed = LocalFixedColors.current
    val buttonSize = if (horizontal) 36.dp else 34.dp
    val iconSize = if (horizontal) 20.dp else 18.dp
    val content: @Composable () -> Unit = {
        items.forEachIndexed { index, item ->
            val control = item as? PlayerPanelItem.Control ?: return@forEachIndexed
            val isSelected = selection == SlotSelection.PanelItem(PlayerBarSlot.Bottom, index)
            if (horizontal) {
                // 横屏信息流与真实 ClipChrome.kt 保持 1:1：纯圆形图标按钮（无下方文字标题、无描边）
                Surface(
                    shape = CircleShape,
                    color = if (control.showBackground) chromeContainer() else Color.Transparent,
                    contentColor = fixed.OnMedia,
                    modifier = Modifier
                        .size(buttonSize)
                        .clip(CircleShape)
                        .then(
                            if (isSelected) Modifier.border(2.dp, accent, CircleShape) else Modifier,
                        )
                        .clickable { onSelect(SlotSelection.PanelItem(PlayerBarSlot.Bottom, index)) },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = controlIcon(control.id, isClips = true),
                            contentDescription = null,
                            modifier = Modifier.size(iconSize),
                        )
                    }
                }
            } else {
                // 竖屏信息流右列与真实 ClipChrome.kt RailAction 保持 1:1：圆形图标 + 下方简短文字
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .then(
                            if (isSelected) Modifier.border(2.dp, accent, RoundedCornerShape(10.dp)).padding(2.dp) else Modifier,
                        )
                        .clickable { onSelect(SlotSelection.PanelItem(PlayerBarSlot.Bottom, index)) },
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (control.showBackground) chromeContainer() else Color.Transparent,
                        contentColor = fixed.OnMedia,
                        modifier = Modifier.size(buttonSize),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = controlIcon(control.id, isClips = true),
                                contentDescription = null,
                                modifier = Modifier.size(iconSize),
                            )
                        }
                    }
                    Text(
                        text = controlLabel(control.id),
                        style = MaterialTheme.typography.labelSmall.copy(shadow = PlayerTextShadow),
                        color = fixed.OnMedia,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 56.dp).padding(top = 2.dp),
                    )
                }
            }
        }
    }
    if (horizontal) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    } else {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PreviewBarRow(
    mode: PlayerLayoutMode,
    bar: PlayerBarSlot,
    items: List<PlayerPanelItem>,
    layout: PlayerFormFactorLayout,
    statusBar: dev.piko.shared.media.player.PlayerStatusBarConfig,
    transportInCenter: Boolean,
    selection: SlotSelection?,
    accent: Color,
    onSelect: (SlotSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sampleTitle = stringResource(Res.string.player_custom_sample_title)
    val sampleFolder = stringResource(Res.string.drive_custom_sample_folder_title)
    val fixed = LocalFixedColors.current

    // 对齐 RenderVideoPanelItems：当 Title 紧邻 Spacer 时，由 Title 占据主权重，相邻 Spacer 收窄为可点击标记，
    // 防止两者各分 50% 把标题压成单字省略。
    val titleIndex = items.indexOfFirst { it is PlayerPanelItem.Control && it.id == PlayerControlId.Title }
    val adjacentSpacerIndex = if (titleIndex >= 0 && items.getOrNull(titleIndex + 1) == PlayerPanelItem.Spacer) {
        titleIndex + 1
    } else {
        -1
    }
    val hasOtherSpacer = items.withIndex().any { (idx, it) ->
        it == PlayerPanelItem.Spacer && idx != adjacentSpacerIndex
    }
    val transportInAnyBar = mode.isVideo && !transportInCenter &&
        (layout.hasInTopBar(PlayerControlId.PlayPause) || layout.hasInBottomBar(PlayerControlId.PlayPause))

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEachIndexed { index, item ->
            val isSelected = selection == SlotSelection.PanelItem(bar, index)
            when (item) {
                PlayerPanelItem.Spacer -> {
                    val spacerMod = if (index == adjacentSpacerIndex) {
                        Modifier.width(22.dp)
                    } else {
                        Modifier.weight(1f)
                    }
                    Box(
                        modifier = spacerMod
                            .height(24.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) accent else fixed.HudSpecularBorder,
                                shape = RoundedCornerShape(8.dp),
                            )
                            .clickable { onSelect(SlotSelection.PanelItem(bar, index)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SpaceBar,
                            contentDescription = null,
                            tint = fixed.OnMedia.copy(alpha = 0.45f),
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }

                is PlayerPanelItem.Control -> {
                    // 键鼠视角或关闭中央播放键时，底栏 PlayPause 位置直接复用真实 ControlBarTransportCluster
                    if (item.id == PlayerControlId.PlayPause && mode.isVideo && !transportInCenter) {
                        ControlBarTransportCluster(
                            config = layout.center,
                            isPlaying = false,
                            showEpisodeSkip = true,
                            canPrevious = true,
                            canNext = true,
                            allowSeek = true,
                            onTogglePlayPause = { onSelect(SlotSelection.PanelItem(bar, index)) },
                            onPrevious = {},
                            onNext = {},
                            onSeekDeltaMillis = {},
                            previewMode = true,
                            selectedSlot = (selection as? SlotSelection.CenterSlot)?.position,
                            isPlaySelected = isSelected,
                            onSelectSlot = { pos -> onSelect(SlotSelection.CenterSlot(pos)) },
                        )
                        return@forEachIndexed
                    }

                    // 对齐 PlayerBarRenderer：主控组已在栏位展开时，不再重复绘制独立的上一集/下一集/快进快退键
                    if (transportInAnyBar && item.id != PlayerControlId.PlayPause && layout.center.hasControl(item.id)) {
                        return@forEachIndexed
                    }

                    val hiddenByCenter = mode.isVideo && layout.isHiddenByCenter(item.id, transportInCenter)
                    val selectMod = if (isSelected) {
                        Modifier
                            .border(2.dp, accent, CircleShape)
                            .padding(2.dp)
                    } else {
                        Modifier
                    }

                    when (item.id) {
                        PlayerControlId.Title -> {
                            val titleBoxMod = if (hasOtherSpacer) {
                                Modifier.weight(1f, fill = false)
                            } else {
                                Modifier.weight(1f)
                            }
                            if (mode.isClips && bar == PlayerBarSlot.Top) {
                                Box(
                                    modifier = titleBoxMod,
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    ScopeTitle(
                                        title = sampleFolder,
                                        compact = true,
                                        showBackground = item.showBackground,
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .then(
                                                if (isSelected) Modifier.border(2.dp, accent, CircleShape) else Modifier,
                                            )
                                            .clickable { onSelect(SlotSelection.PanelItem(bar, index)) },
                                    )
                                }
                            } else {
                                Box(
                                    modifier = titleBoxMod
                                        .alpha(if (hiddenByCenter) 0.32f else 1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .then(
                                            if (isSelected) {
                                                Modifier.border(2.dp, accent, RoundedCornerShape(8.dp))
                                            } else {
                                                Modifier
                                            },
                                        )
                                        .clickable { onSelect(SlotSelection.PanelItem(bar, index)) }
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        text = sampleTitle,
                                        style = MaterialTheme.typography.titleMediumEmphasized.copy(shadow = PlayerTextShadow),
                                        color = fixed.OnMedia,
                                        maxLines = 1,
                                        overflow = TextOverflow.MiddleEllipsis,
                                    )
                                }
                            }
                        }

                        PlayerControlId.TimeLabel -> Box(
                            modifier = Modifier
                                .alpha(if (hiddenByCenter) 0.32f else 1f)
                                .clip(CircleShape)
                                .then(selectMod)
                                .clickable { onSelect(SlotSelection.PanelItem(bar, index)) }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        ) {
                            PlayerTimeReadout(
                                positionMillisProvider = { 754_000L },
                                scrubPositionMillis = null,
                                previewPositionMillis = null,
                                durationMillis = 2_700_000L,
                            )
                        }

                        PlayerControlId.SystemStatus -> Box(
                            modifier = Modifier
                                .alpha(if (hiddenByCenter) 0.32f else 1f)
                                .clip(CircleShape)
                                .then(selectMod)
                                .clickable { onSelect(SlotSelection.PanelItem(bar, index)) },
                        ) {
                            if (statusBar.hasAnyItem) {
                                PlayerSystemStatusCapsule(
                                    showBackground = item.showBackground,
                                    config = statusBar,
                                    previewStatus = PreviewSampleSystemStatus,
                                    previewClockText = "16:20",
                                )
                            } else {
                                Text(
                                    text = "—",
                                    style = MaterialTheme.typography.labelSmall.tabular,
                                    color = fixed.OnMedia.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }

                        PlayerControlId.Volume -> if (mode.isVideo && mode.isLandscape && bar == PlayerBarSlot.Bottom) {
                            Box(
                                modifier = Modifier
                                    .alpha(if (hiddenByCenter) 0.32f else 1f)
                                    .then(selectMod),
                            ) {
                                PlayerVolumeControl(
                                    volume = 0.72f,
                                    showSlider = true,
                                    onVolumeChange = { onSelect(SlotSelection.PanelItem(bar, index)) },
                                    onToggleMute = { onSelect(SlotSelection.PanelItem(bar, index)) },
                                    onDraggingChange = {},
                                    showBackground = item.showBackground,
                                )
                            }
                        } else {
                            PreviewBarIconButton(
                                icon = controlIcon(item.id, isClips = mode.isClips),
                                label = controlLabel(item.id),
                                isClips = mode.isClips,
                                isBack = false,
                                showBackground = item.showBackground,
                                isSelected = isSelected,
                                hiddenByCenter = hiddenByCenter,
                                accent = accent,
                                onClick = { onSelect(SlotSelection.PanelItem(bar, index)) },
                            )
                        }

                        PlayerControlId.Episodes -> if (mode.isVideo && mode.isLandscape && bar == PlayerBarSlot.Bottom) {
                            Box(
                                modifier = Modifier
                                    .alpha(if (hiddenByCenter) 0.32f else 1f)
                                    .then(selectMod),
                            ) {
                                PlayerChipButton(
                                    text = tr("选集", "Playlist", "再生リスト", "재생 목록", "選集"),
                                    icon = Icons.Outlined.VideoLibrary,
                                    onClick = { onSelect(SlotSelection.PanelItem(bar, index)) },
                                    tooltip = null,
                                    showBackground = item.showBackground,
                                )
                            }
                        } else {
                            PreviewBarIconButton(
                                icon = controlIcon(item.id, isClips = mode.isClips),
                                label = controlLabel(item.id),
                                isClips = mode.isClips,
                                isBack = false,
                                showBackground = item.showBackground,
                                isSelected = isSelected,
                                hiddenByCenter = hiddenByCenter,
                                accent = accent,
                                onClick = { onSelect(SlotSelection.PanelItem(bar, index)) },
                            )
                        }

                        PlayerControlId.Speed -> Box(
                            modifier = Modifier
                                .alpha(if (hiddenByCenter) 0.32f else 1f)
                                .then(selectMod),
                        ) {
                            PlayerChipButton(
                                text = "1.0x",
                                onClick = { onSelect(SlotSelection.PanelItem(bar, index)) },
                                tooltip = null,
                                showBackground = item.showBackground,
                            )
                        }

                        PlayerControlId.Quality -> Box(
                            modifier = Modifier
                                .alpha(if (hiddenByCenter) 0.32f else 1f)
                                .then(selectMod),
                        ) {
                            PlayerChipButton(
                                text = "1080P",
                                onClick = { onSelect(SlotSelection.PanelItem(bar, index)) },
                                tooltip = null,
                                showBackground = item.showBackground,
                            )
                        }

                        else -> PreviewBarIconButton(
                            icon = controlIcon(item.id, isClips = mode.isClips),
                            label = controlLabel(item.id),
                            isClips = mode.isClips,
                            isBack = item.id == PlayerControlId.Back,
                            showBackground = item.showBackground,
                            isSelected = isSelected,
                            hiddenByCenter = hiddenByCenter,
                            accent = accent,
                            onClick = { onSelect(SlotSelection.PanelItem(bar, index)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewBarIconButton(
    icon: ImageVector,
    label: String,
    isClips: Boolean,
    isBack: Boolean,
    showBackground: Boolean,
    isSelected: Boolean,
    hiddenByCenter: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val fixed = LocalFixedColors.current
    if (isClips) {
        // 信息流顶栏按钮：与真实 ClipChrome.ChromeIconButton 1:1（0.45f 半透明无描边圆钮，无 48dp 外扩重叠）
        Surface(
            shape = CircleShape,
            color = if (showBackground) chromeContainer() else Color.Transparent,
            contentColor = fixed.OnMedia,
            modifier = Modifier
                .size(32.dp)
                .alpha(if (hiddenByCenter) 0.32f else 1f)
                .clip(CircleShape)
                .then(if (isSelected) Modifier.border(2.dp, accent, CircleShape) else Modifier)
                .clickable(onClick = onClick),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    } else {
        // 视频模式栏位按钮：复用真实 PlayerIconButton（无描边，仅选中时套主色边框）
        Box(
            modifier = Modifier
                .alpha(if (hiddenByCenter) 0.32f else 1f)
                .then(
                    if (isSelected) Modifier.border(2.dp, accent, CircleShape).padding(1.dp) else Modifier,
                ),
        ) {
            PlayerIconButton(
                icon = icon,
                label = label,
                onClick = onClick,
                showBackground = showBackground,
                containerSize = if (isBack) DpSize(30.dp, 36.dp) else DpSize(36.dp, 36.dp),
                iconSize = 18.dp,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetAndTouchCard(
    prefs: PlayerLayoutPrefs,
    onUpdate: ((PlayerLayoutPrefs) -> PlayerLayoutPrefs) -> Unit,
) {
    SettingsGroup(null) {
        SettingsRow(
            icon = Icons.Outlined.Tune,
            title = stringResource(Res.string.player_custom_preset_default),
            supporting = null,
            below = {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PlayerLayoutPreset.entries.forEach { preset ->
                        val target = PlayerLayoutPrefs.preset(preset)
                        val selected = prefs.videoLandscape == target.videoLandscape &&
                            prefs.videoPortrait == target.videoPortrait &&
                            prefs.clipsLandscape == target.clipsLandscape &&
                            prefs.clipsPortrait == target.clipsPortrait
                        FilterChip(
                            selected = selected,
                            onClick = { onUpdate { PlayerLayoutPrefs.preset(preset) } },
                            label = { Text(presetLabel(preset)) },
                        )
                    }
                }
            },
        )
        SettingsSwitchRow(
            icon = Icons.Filled.PlayArrow,
            title = stringResource(Res.string.player_custom_show_center_on_touch),
            supporting = null,
            checked = prefs.showCenterControlsOnTouch,
            onCheckedChange = { enabled -> onUpdate { it.copy(showCenterControlsOnTouch = enabled) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionInspectorCard(
    mode: PlayerLayoutMode,
    layout: PlayerFormFactorLayout,
    prefs: PlayerLayoutPrefs,
    selection: SlotSelection?,
    onSelect: (SlotSelection?) -> Unit,
    onUpdate: ((PlayerLayoutPrefs) -> PlayerLayoutPrefs) -> Unit,
) {
    SettingsGroup(stringResource(Res.string.player_custom_section_inspector)) {
        when (selection) {
            null -> {
                SettingsRow(
                    icon = Icons.Outlined.Info,
                    title = stringResource(Res.string.player_custom_inspector_hint),
                    supporting = null,
                )
            }

            is SlotSelection.CenterSlot -> {
                val pos = selection.position
                val isCenterPlay = pos == CenterSlotPosition.Center
                if (isCenterPlay) {
                    SettingsSwitchRow(
                        icon = Icons.Filled.PlayArrow,
                        title = stringResource(Res.string.player_custom_slot_center),
                        supporting = null,
                        checked = layout.center.showCenterPlay,
                        onCheckedChange = { show ->
                            onUpdate { p -> p.updateLayout(mode) { it.withCenterPlay(show, mode) } }
                        },
                    )
                    SettingsSwitchRow(
                        icon = Icons.Outlined.SwapHoriz,
                        title = stringResource(Res.string.player_custom_symmetric_sides),
                        supporting = null,
                        checked = layout.center.symmetricSideSlots,
                        onCheckedChange = { sym ->
                            onUpdate { p -> p.updateLayout(mode) { it.withSymmetricSideSlots(sym, mode) } }
                        },
                    )
                } else {
                    val slot = layout.center.slot(pos)
                    CenterSlotEditorRows(
                        slotLabel = centerSlotLabel(pos),
                        slot = slot,
                        symmetric = layout.center.symmetricSideSlots,
                        seekSeconds = prefs.seekButtonSeconds,
                        onSymmetricChange = { sym ->
                            onUpdate { p -> p.updateLayout(mode) { it.withSymmetricSideSlots(sym, mode) } }
                        },
                        onUpdateSlot = { next ->
                            onUpdate { p -> p.updateLayout(mode) { it.withCenterSlot(pos, next, mode) } }
                        },
                        onSeekSecondsChange = { sec ->
                            val nextAction = when (slot.action) {
                                is CenterSlotAction.SeekBackward -> CenterSlotAction.SeekBackward(sec)
                                is CenterSlotAction.SeekForward -> CenterSlotAction.SeekForward(sec)
                                else -> slot.action
                            }
                            onUpdate { p ->
                                p.copy(seekButtonSeconds = sec)
                                    .updateLayout(mode) { it.withCenterSlot(pos, slot.copy(action = nextAction), mode) }
                            }
                        },
                    )
                }
            }

            SlotSelection.PortraitFullscreenPrompt -> {
                SettingsSwitchRow(
                    icon = Icons.Filled.Fullscreen,
                    title = tr("竖屏全屏悬浮按钮", "Portrait fullscreen button", "縦画面の全画面ボタン", "세로 화면 전체 화면 버튼", "直向全螢幕懸浮按鈕"),
                    supporting = tr("竖屏播放横屏视频时，在画面下方悬浮显示一键全屏按钮", "Show a floating fullscreen button below landscape videos in portrait mode", "縦画面で横向き動画を再生するとき、下部に全画面ボタンを表示します", "세로 화면에서 가로 동영상 재생 시 하단에 전체 화면 버튼 표시", "直向播放橫向影片時，在畫面下方懸浮顯示一鍵全螢幕按鈕"),
                    checked = prefs.showPortraitFullscreenPrompt,
                    onCheckedChange = { show ->
                        onUpdate { it.copy(showPortraitFullscreenPrompt = show) }
                    },
                )
            }

            is SlotSelection.PanelItem -> {
                val bar = selection.bar
                val list = layout.itemsFor(bar)
                val item = list.getOrNull(selection.index)
                if (item == null) {
                    SettingsRow(
                        icon = Icons.Outlined.Info,
                        title = stringResource(Res.string.player_custom_inspector_hint),
                        supporting = null,
                    )
                } else {
                    val itemTitle = when (item) {
                        PlayerPanelItem.Spacer -> stringResource(Res.string.player_custom_spacer)
                        is PlayerPanelItem.Control -> controlLabel(item.id)
                    }
                    val itemIcon = when (item) {
                        PlayerPanelItem.Spacer -> Icons.Filled.SpaceBar
                        is PlayerPanelItem.Control -> controlIcon(item.id, isClips = mode.isClips)
                    }
                    val isVerticalRail = mode == PlayerLayoutMode.ClipsPortrait && bar == PlayerBarSlot.Bottom
                    val canMoveLeft = selection.index > 0 && !(mode.isVideo && bar == PlayerBarSlot.Top && selection.index == 1 && (list.firstOrNull() as? PlayerPanelItem.Control)?.id == PlayerControlId.Back)
                    val canMoveRight = selection.index < list.lastIndex && !(mode.isVideo && bar == PlayerBarSlot.Top && selection.index == 0 && (item as? PlayerPanelItem.Control)?.id == PlayerControlId.Back)
                    val canSwitchBar = layout.canMoveToOtherBar(bar, selection.index, mode)
                    val canRemove = layout.canRemoveBarItem(bar, selection.index, mode)

                    SettingsRow(
                        icon = itemIcon,
                        title = itemTitle,
                        supporting = barSlotLabel(mode, bar),
                        below = {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    enabled = canMoveLeft,
                                    onClick = {
                                        val target = selection.index - 1
                                        onUpdate { p -> p.updateLayout(mode) { it.moveBarItem(bar, selection.index, target, mode) } }
                                        onSelect(SlotSelection.PanelItem(bar, target))
                                    },
                                ) {
                                    Icon(
                                        if (isVerticalRail) Icons.Filled.ArrowUpward else Icons.AutoMirrored.Filled.ArrowBack,
                                        null,
                                        Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        if (isVerticalRail) tr("上移", "Move up", "上へ", "위로", "上移")
                                        else stringResource(Res.string.player_custom_move_left),
                                    )
                                }
                                OutlinedButton(
                                    enabled = canMoveRight,
                                    onClick = {
                                        val target = selection.index + 1
                                        onUpdate { p -> p.updateLayout(mode) { it.moveBarItem(bar, selection.index, target, mode) } }
                                        onSelect(SlotSelection.PanelItem(bar, target))
                                    },
                                ) {
                                    Text(
                                        if (isVerticalRail) tr("下移", "Move down", "下へ", "아래로", "下移")
                                        else stringResource(Res.string.player_custom_move_right),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        if (isVerticalRail) Icons.Filled.ArrowDownward else Icons.AutoMirrored.Filled.ArrowForward,
                                        null,
                                        Modifier.size(16.dp),
                                    )
                                }
                                OutlinedButton(
                                    enabled = canSwitchBar,
                                    onClick = {
                                        val otherBar = bar.other()
                                        val targetIndex = layout.itemsFor(otherBar).size
                                        onUpdate { p -> p.updateLayout(mode) { it.moveBarItemToOtherBar(bar, selection.index, mode) } }
                                        onSelect(SlotSelection.PanelItem(otherBar, targetIndex))
                                    },
                                ) {
                                    Icon(Icons.Filled.SwapVert, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(Res.string.player_custom_move_other_bar))
                                }
                                if (canRemove) {
                                    OutlinedButton(
                                        onClick = {
                                            onUpdate { p -> p.updateLayout(mode) { it.removeBarItem(bar, selection.index, mode) } }
                                            onSelect(null)
                                        },
                                    ) {
                                        Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(Res.string.player_custom_remove))
                                    }
                                }
                            }
                        },
                    )
                    if (item is PlayerPanelItem.Control) {
                        // 仅当选中「时间与电量」槽位时上下文展示完整状态栏 4 项开关
                        if (item.id == PlayerControlId.SystemStatus) {
                            val sb = prefs.statusBar
                            SettingsSwitchRow(
                                icon = Icons.Outlined.Timer,
                                title = stringResource(Res.string.player_custom_status_clock),
                                supporting = null,
                                checked = sb.showClock,
                                onCheckedChange = { on -> onUpdate { p -> p.updateStatusBar { it.copy(showClock = on) } } },
                            )
                            SettingsSwitchRow(
                                icon = Icons.Outlined.Tune,
                                title = stringResource(Res.string.player_custom_status_network),
                                supporting = null,
                                checked = sb.showNetwork,
                                onCheckedChange = { on -> onUpdate { p -> p.updateStatusBar { it.copy(showNetwork = on) } } },
                            )
                            SettingsSwitchRow(
                                icon = Icons.Outlined.Info,
                                title = stringResource(Res.string.player_custom_status_battery),
                                supporting = null,
                                checked = sb.showBattery,
                                onCheckedChange = { on -> onUpdate { p -> p.updateStatusBar { it.copy(showBattery = on) } } },
                            )
                            SettingsSwitchRow(
                                icon = Icons.Outlined.Tune,
                                title = stringResource(Res.string.player_custom_status_icons),
                                supporting = null,
                                checked = sb.showIcons,
                                onCheckedChange = { on -> onUpdate { p -> p.updateStatusBar { it.copy(showIcons = on) } } },
                            )
                        }
                        // 仅当选中快退/快进控件时上下文展示步长秒数选择
                        if (item.id == PlayerControlId.SeekBackward || item.id == PlayerControlId.SeekForward) {
                            SettingsRow(
                                icon = Icons.Outlined.Timer,
                                title = stringResource(Res.string.player_custom_seek_seconds),
                                supporting = null,
                                below = {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        SEEK_SECONDS_PRESETS.forEach { sec ->
                                            FilterChip(
                                                selected = prefs.seekButtonSeconds == sec,
                                                onClick = { onUpdate { it.copy(seekButtonSeconds = sec).sanitize() } },
                                                label = { Text(stringResource(Res.string.player_custom_seconds_fmt, sec)) },
                                            )
                                        }
                                    }
                                },
                            )
                        }
                        SettingsSwitchRow(
                            icon = Icons.Outlined.Tune,
                            title = stringResource(Res.string.player_custom_show_bg),
                            supporting = null,
                            checked = item.showBackground,
                            onCheckedChange = { showBg ->
                                onUpdate { p ->
                                    p.updateLayout(mode) { it.withBarItemBackground(bar, selection.index, showBg, mode) }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CenterSlotEditorRows(
    slotLabel: String,
    slot: CenterSlotConfig,
    symmetric: Boolean,
    seekSeconds: Int,
    onSymmetricChange: (Boolean) -> Unit,
    onUpdateSlot: (CenterSlotConfig) -> Unit,
    onSeekSecondsChange: (Int) -> Unit,
) {
    val activeSeekSeconds = slot.action.seekSecondsOrNull() ?: seekSeconds
    val actions = listOf(
        CenterSlotAction.None,
        CenterSlotAction.Previous,
        CenterSlotAction.Next,
        CenterSlotAction.SeekBackward(activeSeekSeconds),
        CenterSlotAction.SeekForward(activeSeekSeconds),
    )
    SettingsRow(
        icon = Icons.Outlined.Tune,
        title = slotLabel,
        supporting = null,
        below = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                actions.forEach { candidate ->
                    val selected = slot.action::class == candidate::class
                    FilterChip(
                        selected = selected,
                        onClick = { onUpdateSlot(slot.copy(action = candidate)) },
                        label = { Text(centerActionLabel(candidate)) },
                    )
                }
            }
        },
    )
    // 仅当当前槽位为快进/快退时按需展示步长选项，避免常驻表单堆积
    if (slot.action is CenterSlotAction.SeekBackward || slot.action is CenterSlotAction.SeekForward) {
        SettingsRow(
            icon = Icons.Outlined.Timer,
            title = stringResource(Res.string.player_custom_seek_seconds),
            supporting = null,
            below = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SEEK_SECONDS_PRESETS.forEach { sec ->
                        FilterChip(
                            selected = activeSeekSeconds == sec,
                            onClick = { onSeekSecondsChange(sec) },
                            label = { Text(stringResource(Res.string.player_custom_seconds_fmt, sec)) },
                        )
                    }
                }
            },
        )
    }
    SettingsSwitchRow(
        icon = Icons.Outlined.SwapHoriz,
        title = stringResource(Res.string.player_custom_symmetric_sides),
        supporting = null,
        checked = symmetric,
        onCheckedChange = onSymmetricChange,
    )
    if (slot.action != CenterSlotAction.None) {
        SettingsSwitchRow(
            icon = Icons.Outlined.Tune,
            title = stringResource(Res.string.player_custom_show_bg),
            supporting = null,
            checked = slot.showBackground,
            onCheckedChange = { onUpdateSlot(slot.copy(showBackground = it)) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlPaletteCard(
    mode: PlayerLayoutMode,
    layout: PlayerFormFactorLayout,
    onAddControl: (PlayerBarSlot, PlayerControlId) -> Unit,
    onAddSpacer: (PlayerBarSlot) -> Unit,
) {
    var targetBar by rememberSaveable { mutableStateOf(PlayerBarSlot.Bottom) }
    val available = remember(mode, layout, targetBar) {
        allowedControlsFor(mode, targetBar).filter { id ->
            id != PlayerControlId.Back && !layout.hasInTopBar(id) && !layout.hasInBottomBar(id)
        }
    }
    val canAddSpacer = allowsSpacerIn(mode, targetBar)
    val topBarLabel = barSlotLabel(mode, PlayerBarSlot.Top)
    val bottomBarLabel = barSlotLabel(mode, PlayerBarSlot.Bottom)
    SettingsGroup(stringResource(Res.string.player_custom_section_palette)) {
        SettingsRow(
            icon = Icons.Filled.Add,
            title = if (targetBar == PlayerBarSlot.Top) {
                stringResource(Res.string.player_custom_add_top)
            } else if (mode == PlayerLayoutMode.ClipsPortrait) {
                tr("添加到右侧操作栏", "Add to right action rail", "右アクションレールに追加", "오른쪽 작업 레일에 추가", "新增至右側操作欄")
            } else {
                stringResource(Res.string.player_custom_add_bottom)
            },
            supporting = null,
            below = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsSegmentedChoice(
                        options = listOf(PlayerBarSlot.Top, PlayerBarSlot.Bottom),
                        selected = targetBar,
                        label = { bar -> if (bar == PlayerBarSlot.Top) topBarLabel else bottomBarLabel },
                        onSelect = { targetBar = it },
                        fill = true,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        available.forEach { id ->
                            FilterChip(
                                selected = false,
                                onClick = { onAddControl(targetBar, id) },
                                leadingIcon = { Icon(controlIcon(id, isClips = mode.isClips), null, Modifier.size(16.dp)) },
                                label = { Text(controlLabel(id)) },
                            )
                        }
                        if (canAddSpacer) {
                            FilledTonalButton(onClick = { onAddSpacer(targetBar) }) {
                                Icon(Icons.Filled.SpaceBar, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(Res.string.player_custom_add_spacer))
                            }
                        }
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ListenPreviewCanvas(
    config: ListenLayoutConfig,
    selectedSlot: CenterSlotPosition,
    onSelectSlot: (CenterSlotPosition) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var previewPlaying by remember { mutableStateOf(true) }
    val rawAudioTitle = stringResource(Res.string.player_custom_sample_audio_title)
    val displayTitle = rawAudioTitle.substringBeforeLast('.').ifBlank { rawAudioTitle }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = colors.surface,
        contentColor = colors.onSurface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 直接复用 ListenScreen.kt 的黑胶唱片与方形静态封面，保证 1:1 一致
            if (config.coverStyle == ListenCoverStyle.VinylDisc) {
                ListenVinylDiscVisual(
                    coverUrl = "",
                    discSize = 160.dp,
                )
            } else {
                ListenStaticCover(
                    coverUrl = "",
                    maxWidth = 180.dp,
                    maxHeight = 180.dp,
                )
            }

            Text(
                text = displayTitle,
                style = MaterialTheme.typography.headlineSmallEmphasized,
                color = colors.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )

            // 直接复用真实双色等宽 ListenTimeReadout
            ListenTimeReadout(
                positionMillis = 88_000L,
                durationMillis = 255_000L,
                active = false,
                cancelArmed = false,
            )

            // 复用真实 PlayerSeekBar（默认播放态以实时呈现 M3E 波浪轨道，点按中央播放/暂停键可切换预览状态）
            PlayerSeekBar(
                positionMillisProvider = { 88_000L },
                durationMillis = 255_000L,
                bufferedPositionMillisProvider = { 160_000L },
                onSeek = {},
                isPlaying = previewPlaying,
                wavy = true,
                modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
            )

            // 直接复用真实 ListenTransportRow（纯图标无秒数角标，64dp 中央键 / 36-54dp 侧键 / 12dp 间距）
            ListenTransportRow(
                config = config,
                isPlaying = previewPlaying,
                hasPrevious = true,
                hasNext = true,
                onTogglePlayPause = { previewPlaying = !previewPlaying },
                onPrevious = {},
                onNext = {},
                onSeekBySeconds = {},
                previewMode = true,
                selectedSlot = selectedSlot,
                onSelectSlot = onSelectSlot,
            )

            // 直接复用真实 ListenActionPill（未激活态为 surfaceContainerHigh，队列带曲目计数）
            if (config.visibleChips.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    config.visibleChips.forEach { chip ->
                        val chipIcon = when (chip) {
                            ListenQuickChip.Speed -> Icons.Filled.Speed
                            ListenQuickChip.SleepTimer -> Icons.Filled.Bedtime
                            ListenQuickChip.Queue -> Icons.AutoMirrored.Outlined.QueueMusic
                        }
                        val chipLabel = when (chip) {
                            ListenQuickChip.Speed -> stringResource(Res.string.listen_speed)
                            ListenQuickChip.SleepTimer -> stringResource(Res.string.listen_sleep_timer)
                            ListenQuickChip.Queue -> stringResource(Res.string.listen_queue_count_fmt, 12)
                        }
                        ListenActionPill(
                            icon = chipIcon,
                            label = chipLabel,
                            highlighted = false,
                            onClick = {},
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ListenCustomizationEditor(
    config: ListenLayoutConfig,
    seekSeconds: Int,
    selectedSlot: CenterSlotPosition,
    onUpdate: ((PlayerLayoutPrefs) -> PlayerLayoutPrefs) -> Unit,
) {
    val vinylLabel = stringResource(Res.string.player_custom_listen_vinyl)
    val staticLabel = stringResource(Res.string.player_custom_listen_static)
    SettingsGroup(stringResource(Res.string.player_custom_mode_listen)) {
        SettingsRow(
            icon = Icons.Filled.Headphones,
            title = stringResource(Res.string.player_custom_listen_cover),
            supporting = null,
            below = {
                SettingsSegmentedChoice(
                    options = ListenCoverStyle.entries,
                    selected = config.coverStyle,
                    label = { style -> if (style == ListenCoverStyle.VinylDisc) vinylLabel else staticLabel },
                    onSelect = { style -> onUpdate { it.updateListen { l -> l.copy(coverStyle = style) } } },
                    fill = true,
                )
            },
        )
        val listenSlot = config.slot(selectedSlot)
        CenterSlotEditorRows(
            slotLabel = centerSlotLabel(selectedSlot),
            slot = listenSlot,
            symmetric = config.symmetricSideSlots,
            seekSeconds = seekSeconds,
            onSymmetricChange = { sym ->
                onUpdate { it.updateListen { l -> l.withSymmetricSideSlots(sym) } }
            },
            onUpdateSlot = { next ->
                onUpdate { it.updateListen { l -> l.withSlot(selectedSlot, next) } }
            },
            onSeekSecondsChange = { sec ->
                val nextAction = when (listenSlot.action) {
                    is CenterSlotAction.SeekBackward -> CenterSlotAction.SeekBackward(sec)
                    is CenterSlotAction.SeekForward -> CenterSlotAction.SeekForward(sec)
                    else -> listenSlot.action
                }
                onUpdate { p ->
                    p.copy(seekButtonSeconds = sec)
                        .updateListen { l -> l.withSlot(selectedSlot, listenSlot.copy(action = nextAction)) }
                }
            },
        )
        SettingsRow(
            icon = Icons.Outlined.Tune,
            title = stringResource(Res.string.player_custom_listen_chips),
            supporting = null,
            below = {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ListenQuickChip.entries.forEach { chip ->
                        val active = config.hasChip(chip)
                        FilterChip(
                            selected = active,
                            onClick = { onUpdate { it.updateListen { l -> l.toggleChip(chip, !active) } } },
                            leadingIcon = if (active) ({ Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }) else null,
                            label = { Text(listenChipLabel(chip)) },
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun GesturePreviewCanvas(gestures: PlayerGestureConfig) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val error = MaterialTheme.colorScheme.error
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF101318),
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
    ) {
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                val vWidth = size.width * gestures.verticalZoneFraction
                val dtWidth = size.width * gestures.doubleTapZoneFraction
                // 左侧/右侧纵向手势区域
                if (gestures.swipeBrightnessVolumeEnabled && gestures.leftVerticalSwipe != VerticalSwipeAction.None) {
                    drawRoundRect(
                        color = primary.copy(alpha = 0.22f),
                        topLeft = Offset.Zero,
                        size = Size(vWidth, size.height),
                        cornerRadius = CornerRadius(12f, 12f),
                    )
                }
                if (gestures.swipeBrightnessVolumeEnabled && gestures.rightVerticalSwipe != VerticalSwipeAction.None) {
                    drawRoundRect(
                        color = tertiary.copy(alpha = 0.22f),
                        topLeft = Offset(size.width - vWidth, 0f),
                        size = Size(vWidth, size.height),
                        cornerRadius = CornerRadius(12f, 12f),
                    )
                }
                // 双击左右触发边界线
                if (gestures.doubleTapSeekEnabled) {
                    val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                    drawLine(
                        color = Color.White.copy(alpha = 0.55f),
                        start = Offset(dtWidth, 0f),
                        end = Offset(dtWidth, size.height),
                        strokeWidth = 2f,
                        pathEffect = dash,
                    )
                    drawLine(
                        color = Color.White.copy(alpha = 0.55f),
                        start = Offset(size.width - dtWidth, 0f),
                        end = Offset(size.width - dtWidth, size.height),
                        strokeWidth = 2f,
                        pathEffect = dash,
                    )
                }
                // 顶部横划取消区
                if (gestures.swipeSeekEnabled && gestures.swipeSeekCancelZone) {
                    drawRect(
                        color = error.copy(alpha = 0.20f),
                        topLeft = Offset.Zero,
                        size = Size(size.width, size.height * 0.22f),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxSize().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = verticalActionLabel(gestures.leftVerticalSwipe),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
                if (gestures.swipeSeekEnabled) {
                    Text(
                        text = sensitivityLabel(gestures.swipeSeekSensitivity),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
                Text(
                    text = verticalActionLabel(gestures.rightVerticalSwipe),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GestureCustomizationEditor(
    prefs: PlayerLayoutPrefs,
    onUpdate: ((PlayerLayoutPrefs) -> PlayerLayoutPrefs) -> Unit,
) {
    val g = prefs.gestures
    val sensitivityLabels = mapOf(
        SwipeSeekSensitivity.Low to stringResource(Res.string.player_custom_gesture_sens_low),
        SwipeSeekSensitivity.Standard to stringResource(Res.string.player_custom_gesture_sens_standard),
        SwipeSeekSensitivity.High to stringResource(Res.string.player_custom_gesture_sens_high),
    )
    val verticalActionLabels = mapOf(
        VerticalSwipeAction.Brightness to stringResource(Res.string.player_custom_gesture_act_brightness),
        VerticalSwipeAction.Volume to stringResource(Res.string.player_custom_gesture_act_volume),
        VerticalSwipeAction.Episode to stringResource(Res.string.player_custom_gesture_act_episode),
        VerticalSwipeAction.None to stringResource(Res.string.player_custom_gesture_act_none),
    )
    SettingsGroup(stringResource(Res.string.player_custom_mode_gestures)) {
        SettingsSwitchRow(
            icon = Icons.Filled.FastForward,
            title = stringResource(Res.string.player_custom_gesture_double_tap),
            supporting = null,
            checked = g.doubleTapSeekEnabled,
            onCheckedChange = { enabled -> onUpdate { p -> p.updateGestures { it.copy(doubleTapSeekEnabled = enabled) } } },
        )
        SettingsSwitchRow(
            icon = Icons.Outlined.Speed,
            title = stringResource(Res.string.player_custom_gesture_long_press),
            supporting = null,
            checked = g.longPressSpeedEnabled,
            onCheckedChange = { enabled -> onUpdate { p -> p.updateGestures { it.copy(longPressSpeedEnabled = enabled) } } },
        )
        DependentRow(visible = g.longPressSpeedEnabled) {
            SettingsSwitchRow(
                icon = Icons.Filled.Speed,
                title = stringResource(Res.string.player_custom_gesture_speed_dial),
                supporting = null,
                checked = g.longPressSpeedDialEnabled,
                onCheckedChange = { enabled -> onUpdate { p -> p.updateGestures { it.copy(longPressSpeedDialEnabled = enabled) } } },
            )
        }
        SettingsSwitchRow(
            icon = Icons.Outlined.SwapHoriz,
            title = stringResource(Res.string.player_custom_gesture_swipe_seek),
            supporting = null,
            checked = g.swipeSeekEnabled,
            onCheckedChange = { enabled -> onUpdate { p -> p.updateGestures { it.copy(swipeSeekEnabled = enabled) } } },
        )
        DependentRow(visible = g.swipeSeekEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsSwitchRow(
                    icon = Icons.Outlined.Tune,
                    title = stringResource(Res.string.player_custom_gesture_cancel_zone),
                    supporting = null,
                    checked = g.swipeSeekCancelZone,
                    onCheckedChange = { enabled -> onUpdate { p -> p.updateGestures { it.copy(swipeSeekCancelZone = enabled) } } },
                )
                SettingsRow(
                    icon = Icons.Outlined.Speed,
                    title = stringResource(Res.string.player_custom_gesture_sensitivity),
                    supporting = null,
                    below = {
                        SettingsSegmentedChoice(
                            options = SwipeSeekSensitivity.entries,
                            selected = g.swipeSeekSensitivity,
                            label = { sensitivityLabels.getValue(it) },
                            onSelect = { sens -> onUpdate { p -> p.updateGestures { it.copy(swipeSeekSensitivity = sens) } } },
                            fill = true,
                        )
                    },
                )
            }
        }
        SettingsSwitchRow(
            icon = Icons.Filled.SwapVert,
            title = stringResource(Res.string.player_custom_gesture_vertical),
            supporting = null,
            checked = g.swipeBrightnessVolumeEnabled,
            onCheckedChange = { enabled -> onUpdate { p -> p.updateGestures { it.copy(swipeBrightnessVolumeEnabled = enabled) } } },
        )
        DependentRow(visible = g.swipeBrightnessVolumeEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsRow(
                    icon = Icons.Filled.SwapVert,
                    title = stringResource(Res.string.player_custom_gesture_left_swipe),
                    supporting = null,
                    below = {
                        SettingsSegmentedChoice(
                            options = VerticalSwipeAction.entries,
                            selected = g.leftVerticalSwipe,
                            label = { verticalActionLabels.getValue(it) },
                            onSelect = { act -> onUpdate { p -> p.updateGestures { it.copy(leftVerticalSwipe = act) } } },
                            fill = true,
                        )
                    },
                )
                SettingsRow(
                    icon = Icons.Filled.SwapVert,
                    title = stringResource(Res.string.player_custom_gesture_right_swipe),
                    supporting = null,
                    below = {
                        SettingsSegmentedChoice(
                            options = VerticalSwipeAction.entries,
                            selected = g.rightVerticalSwipe,
                            label = { verticalActionLabels.getValue(it) },
                            onSelect = { act -> onUpdate { p -> p.updateGestures { it.copy(rightVerticalSwipe = act) } } },
                            fill = true,
                        )
                    },
                )
                SettingsRow(
                    icon = Icons.Outlined.SwapHoriz,
                    title = stringResource(Res.string.player_custom_gesture_vertical_zone),
                    supporting = "${(g.verticalZoneFraction * 100).roundToInt()}%",
                    below = {
                        Slider(
                            value = g.verticalZoneFraction,
                            onValueChange = { value -> onUpdate { p -> p.updateGestures { it.copy(verticalZoneFraction = value) } } },
                            valueRange = MIN_VERTICAL_ZONE_FRACTION..MAX_VERTICAL_ZONE_FRACTION,
                        )
                    },
                )
            }
        }
        SettingsRow(
            icon = Icons.Outlined.Timer,
            title = stringResource(Res.string.player_custom_seek_seconds),
            supporting = stringResource(Res.string.player_custom_seconds_fmt, prefs.seekButtonSeconds),
            below = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SEEK_SECONDS_PRESETS.forEach { sec ->
                        FilterChip(
                            selected = prefs.seekButtonSeconds == sec,
                            onClick = { onUpdate { it.copy(seekButtonSeconds = sec).sanitize() } },
                            label = { Text(stringResource(Res.string.player_custom_seconds_fmt, sec)) },
                        )
                    }
                }
            },
        )
        SettingsSwitchRow(
            icon = Icons.Outlined.Timer,
            title = stringResource(Res.string.player_custom_sync_double_tap),
            supporting = null,
            checked = prefs.syncDoubleTapWithSeekButton,
            onCheckedChange = { synced -> onUpdate { it.copy(syncDoubleTapWithSeekButton = synced) } },
        )
        DependentRow(visible = !prefs.syncDoubleTapWithSeekButton) {
            SettingsRow(
                icon = Icons.Outlined.Timer,
                title = stringResource(Res.string.player_custom_double_tap_seconds),
                supporting = stringResource(Res.string.player_custom_seconds_fmt, prefs.doubleTapSeekSeconds),
                below = {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SEEK_SECONDS_PRESETS.forEach { sec ->
                            FilterChip(
                                selected = prefs.doubleTapSeekSeconds == sec,
                                onClick = { onUpdate { it.copy(doubleTapSeekSeconds = sec).sanitize() } },
                                label = { Text(stringResource(Res.string.player_custom_seconds_fmt, sec)) },
                            )
                        }
                    }
                },
            )
        }
        SettingsRow(
            icon = Icons.Outlined.SwapHoriz,
            title = stringResource(Res.string.player_custom_gesture_double_tap_zone),
            supporting = "${(g.doubleTapZoneFraction * 100).roundToInt()}%",
            below = {
                Slider(
                    value = g.doubleTapZoneFraction,
                    onValueChange = { value -> onUpdate { p -> p.updateGestures { it.copy(doubleTapZoneFraction = value) } } },
                    valueRange = MIN_DOUBLE_TAP_ZONE_FRACTION..MAX_DOUBLE_TAP_ZONE_FRACTION,
                )
            },
        )
    }
}

@Composable
private fun barSlotLabel(mode: PlayerLayoutMode, bar: PlayerBarSlot): String = when {
    bar == PlayerBarSlot.Top -> stringResource(Res.string.player_custom_section_top_bar)
    mode == PlayerLayoutMode.ClipsPortrait -> tr("右侧操作栏", "Right action rail", "右アクションレール", "오른쪽 작업 레일", "右側操作欄")
    else -> stringResource(Res.string.player_custom_section_bottom_bar)
}

@Composable
private fun tabLabel(tab: CustomizationTab): String = when (tab) {
    CustomizationTab.VideoLandscape -> stringResource(Res.string.player_custom_mode_video_land)
    CustomizationTab.VideoPortrait -> stringResource(Res.string.player_custom_mode_video_port)
    CustomizationTab.ClipsLandscape -> stringResource(Res.string.player_custom_mode_clips_land)
    CustomizationTab.ClipsPortrait -> stringResource(Res.string.player_custom_mode_clips_port)
    CustomizationTab.Listen -> stringResource(Res.string.player_custom_mode_listen)
    CustomizationTab.Gestures -> stringResource(Res.string.player_custom_mode_gestures)
}

@Composable
private fun presetLabel(preset: PlayerLayoutPreset): String = when (preset) {
    PlayerLayoutPreset.Default -> stringResource(Res.string.player_custom_preset_default)
    PlayerLayoutPreset.Minimal -> stringResource(Res.string.player_custom_preset_minimal)
    PlayerLayoutPreset.Podcast -> stringResource(Res.string.player_custom_preset_podcast)
}

@Composable
private fun centerSlotLabel(pos: CenterSlotPosition): String = when (pos) {
    CenterSlotPosition.OuterLeft -> stringResource(Res.string.player_custom_slot_outer_left)
    CenterSlotPosition.InnerLeft -> stringResource(Res.string.player_custom_slot_inner_left)
    CenterSlotPosition.Center -> stringResource(Res.string.player_custom_slot_center)
    CenterSlotPosition.InnerRight -> stringResource(Res.string.player_custom_slot_inner_right)
    CenterSlotPosition.OuterRight -> stringResource(Res.string.player_custom_slot_outer_right)
}

@Composable
private fun centerActionLabel(action: CenterSlotAction): String = when (action) {
    CenterSlotAction.None -> stringResource(Res.string.player_custom_slot_action_none)
    CenterSlotAction.Previous -> stringResource(Res.string.player_custom_slot_action_prev)
    CenterSlotAction.Next -> stringResource(Res.string.player_custom_slot_action_next)
    is CenterSlotAction.SeekBackward -> stringResource(Res.string.player_custom_slot_action_rewind)
    is CenterSlotAction.SeekForward -> stringResource(Res.string.player_custom_slot_action_forward)
}

@Composable
private fun scaleLabel(scale: CenterSlotScale): String = when (scale) {
    CenterSlotScale.Large -> stringResource(Res.string.player_custom_scale_large)
    CenterSlotScale.Medium -> stringResource(Res.string.player_custom_scale_medium)
    CenterSlotScale.Small -> stringResource(Res.string.player_custom_scale_small)
    CenterSlotScale.ExtraSmall -> stringResource(Res.string.player_custom_scale_xs)
}

@Composable
private fun listenChipLabel(chip: ListenQuickChip): String = when (chip) {
    ListenQuickChip.Speed -> stringResource(Res.string.player_custom_listen_chip_speed)
    ListenQuickChip.SleepTimer -> stringResource(Res.string.player_custom_listen_chip_sleep)
    ListenQuickChip.Queue -> stringResource(Res.string.player_custom_listen_chip_queue)
}

@Composable
private fun verticalActionLabel(action: VerticalSwipeAction): String = when (action) {
    VerticalSwipeAction.Brightness -> stringResource(Res.string.player_custom_gesture_act_brightness)
    VerticalSwipeAction.Volume -> stringResource(Res.string.player_custom_gesture_act_volume)
    VerticalSwipeAction.Episode -> stringResource(Res.string.player_custom_gesture_act_episode)
    VerticalSwipeAction.None -> stringResource(Res.string.player_custom_gesture_act_none)
}

@Composable
private fun sensitivityLabel(sensitivity: SwipeSeekSensitivity): String = when (sensitivity) {
    SwipeSeekSensitivity.Low -> stringResource(Res.string.player_custom_gesture_sens_low)
    SwipeSeekSensitivity.Standard -> stringResource(Res.string.player_custom_gesture_sens_standard)
    SwipeSeekSensitivity.High -> stringResource(Res.string.player_custom_gesture_sens_high)
}

@Composable
private fun controlLabel(id: PlayerControlId): String = when (id) {
    PlayerControlId.Back -> stringResource(Res.string.player_custom_ctrl_back)
    PlayerControlId.Title -> stringResource(Res.string.player_custom_ctrl_title)
    PlayerControlId.SystemStatus -> stringResource(Res.string.player_custom_ctrl_system_status)
    PlayerControlId.PlayPause -> stringResource(Res.string.player_custom_ctrl_play_pause)
    PlayerControlId.Previous -> stringResource(Res.string.player_custom_ctrl_previous)
    PlayerControlId.Next -> stringResource(Res.string.player_custom_ctrl_next)
    PlayerControlId.SeekBackward -> stringResource(Res.string.player_custom_ctrl_seek_back)
    PlayerControlId.SeekForward -> stringResource(Res.string.player_custom_ctrl_seek_forward)
    PlayerControlId.TimeLabel -> stringResource(Res.string.player_custom_ctrl_time)
    PlayerControlId.Volume -> stringResource(Res.string.player_custom_ctrl_volume)
    PlayerControlId.Quality -> stringResource(Res.string.player_custom_ctrl_quality)
    PlayerControlId.Speed -> stringResource(Res.string.player_custom_ctrl_speed)
    PlayerControlId.Episodes -> stringResource(Res.string.player_custom_ctrl_episodes)
    PlayerControlId.Tracks -> stringResource(Res.string.player_custom_ctrl_tracks)
    PlayerControlId.Listen -> stringResource(Res.string.player_custom_ctrl_listen)
    PlayerControlId.Rotate -> stringResource(Res.string.player_custom_ctrl_rotate)
    PlayerControlId.AspectRatio -> stringResource(Res.string.player_custom_ctrl_aspect)
    PlayerControlId.Lock -> stringResource(Res.string.player_custom_ctrl_lock)
    PlayerControlId.Stats -> stringResource(Res.string.player_custom_ctrl_stats)
    PlayerControlId.Settings -> stringResource(Res.string.player_custom_ctrl_settings)
    PlayerControlId.Fullscreen -> stringResource(Res.string.player_custom_ctrl_fullscreen)
    PlayerControlId.PinOnTop -> stringResource(Res.string.player_custom_ctrl_pin)
    PlayerControlId.AutoAdvance -> stringResource(Res.string.player_custom_ctrl_auto_advance)
    PlayerControlId.Star -> stringResource(Res.string.player_custom_ctrl_star)
    PlayerControlId.Vault -> stringResource(Res.string.player_custom_ctrl_vault)
    PlayerControlId.OpenFull -> stringResource(Res.string.player_custom_ctrl_open_full)
    PlayerControlId.OpenFolder -> stringResource(Res.string.player_custom_ctrl_open_folder)
    PlayerControlId.Download -> stringResource(Res.string.player_custom_ctrl_download)
    PlayerControlId.HideChrome -> stringResource(Res.string.player_custom_ctrl_hide_chrome)
}

private fun controlIcon(id: PlayerControlId, isClips: Boolean = false): ImageVector = when (id) {
    PlayerControlId.Back -> if (isClips) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack
    PlayerControlId.Title -> Icons.Outlined.Info
    PlayerControlId.SystemStatus -> Icons.Outlined.Timer
    PlayerControlId.PlayPause -> Icons.Filled.PlayArrow
    PlayerControlId.Previous -> Icons.Filled.SkipPrevious
    PlayerControlId.Next -> Icons.Filled.SkipNext
    PlayerControlId.SeekBackward -> Icons.Filled.FastRewind
    PlayerControlId.SeekForward -> Icons.Filled.FastForward
    PlayerControlId.TimeLabel -> Icons.Outlined.Timer
    PlayerControlId.Volume -> Icons.AutoMirrored.Filled.VolumeUp
    PlayerControlId.Quality -> Icons.Filled.HighQuality
    PlayerControlId.Speed -> Icons.Filled.Speed
    PlayerControlId.Episodes -> Icons.Outlined.VideoLibrary
    PlayerControlId.Tracks -> Icons.Outlined.Subtitles
    PlayerControlId.Listen -> Icons.Outlined.Headphones
    PlayerControlId.Rotate -> if (isClips) Icons.Filled.StayCurrentLandscape else Icons.Filled.Rotate90DegreesCw
    PlayerControlId.AspectRatio -> Icons.Outlined.AspectRatio
    PlayerControlId.Lock -> Icons.Filled.LockOpen
    PlayerControlId.Stats -> Icons.Outlined.Info
    PlayerControlId.Settings -> Icons.Outlined.Tune
    PlayerControlId.Fullscreen -> Icons.Filled.Fullscreen
    PlayerControlId.PinOnTop -> Icons.Outlined.PushPin
    PlayerControlId.AutoAdvance -> Icons.Filled.SwipeVertical
    PlayerControlId.Star -> Icons.Filled.Star
    PlayerControlId.Vault -> Icons.Outlined.Folder
    PlayerControlId.OpenFull -> Icons.Outlined.OpenInFull
    PlayerControlId.OpenFolder -> Icons.Outlined.FolderOpen
    PlayerControlId.Download -> Icons.Outlined.Download
    PlayerControlId.HideChrome -> Icons.Filled.VisibilityOff
}
