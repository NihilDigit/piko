@file:JvmMultifileClass
@file:JvmName("PlayerLayoutPrefsKt")

package dev.piko.shared.media.player

import androidx.compose.runtime.Immutable
import kotlin.jvm.JvmMultifileClass
import kotlin.jvm.JvmName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val PLAYER_LAYOUT_PREFS_VERSION = 1
const val MIN_SEEK_SECONDS = 1
const val MAX_SEEK_SECONDS = 300
const val DEFAULT_SEEK_SECONDS = 10
const val DEFAULT_DOUBLE_TAP_SEEK_SECONDS = 10
val SEEK_SECONDS_PRESETS = listOf(5, 10, 15, 30)

const val MIN_VERTICAL_ZONE_FRACTION = 0.20f
const val MAX_VERTICAL_ZONE_FRACTION = 0.50f
const val DEFAULT_VERTICAL_ZONE_FRACTION = 0.40f
const val MIN_DOUBLE_TAP_ZONE_FRACTION = 0.15f
const val MAX_DOUBLE_TAP_ZONE_FRACTION = 0.40f
const val DEFAULT_DOUBLE_TAP_ZONE_FRACTION = 0.25f

/**
 * 播放器四态布局模式：横屏视频、竖屏视频、横屏信息流、竖屏信息流各自独立配置与持久保存。
 */
@Serializable
enum class PlayerLayoutMode(
    val isClips: Boolean,
    val isLandscape: Boolean,
) {
    VideoLandscape(isClips = false, isLandscape = true),
    VideoPortrait(isClips = false, isLandscape = false),
    ClipsLandscape(isClips = true, isLandscape = true),
    ClipsPortrait(isClips = true, isLandscape = false),
    ;

    val isVideo: Boolean get() = !isClips

    companion object {
        fun video(isLandscape: Boolean): PlayerLayoutMode =
            if (isLandscape) VideoLandscape else VideoPortrait

        fun clips(isLandscape: Boolean): PlayerLayoutMode =
            if (isLandscape) ClipsLandscape else ClipsPortrait
    }
}

@Serializable
enum class PlayerBarSlot {
    Top,
    Bottom,
    ;

    fun other(): PlayerBarSlot = if (this == Top) Bottom else Top
}

@Serializable
enum class CenterSlotPosition {
    OuterLeft,
    InnerLeft,
    Center,
    InnerRight,
    OuterRight,
    ;

    fun symmetricOpposite(): CenterSlotPosition = when (this) {
        OuterLeft -> OuterRight
        InnerLeft -> InnerRight
        Center -> Center
        InnerRight -> InnerLeft
        OuterRight -> OuterLeft
    }
}

/**
 * Piko 播放器可排布控件标识（视频播放与信息流共用，按 [allowedControlsFor] 区分适用集合）。
 */
@Serializable
enum class PlayerControlId {
    Back,
    Title,
    SystemStatus,
    PlayPause,
    Previous,
    Next,
    SeekBackward,
    SeekForward,
    TimeLabel,
    Volume,
    Quality,
    Speed,
    Episodes,
    Tracks,
    Listen,
    Rotate,
    AspectRatio,
    Lock,
    Stats,
    Settings,
    Fullscreen,
    PinOnTop,

    // 信息流专属动作
    AutoAdvance,
    Star,
    Vault,
    OpenFull,
    OpenFolder,
    Download,
    HideChrome,
}

@Immutable
@Serializable
sealed interface PlayerPanelItem {
    @Immutable
    @Serializable
    @SerialName("control")
    data class Control(
        val id: PlayerControlId,
        val showBackground: Boolean = true,
    ) : PlayerPanelItem

    @Immutable
    @Serializable
    @SerialName("spacer")
    data object Spacer : PlayerPanelItem
}

@Immutable
@Serializable
sealed interface CenterSlotAction {
    @Immutable
    @Serializable
    @SerialName("none")
    data object None : CenterSlotAction

    @Immutable
    @Serializable
    @SerialName("previous")
    data object Previous : CenterSlotAction

    @Immutable
    @Serializable
    @SerialName("next")
    data object Next : CenterSlotAction

    @Immutable
    @Serializable
    @SerialName("seek_backward")
    data class SeekBackward(val seconds: Int = DEFAULT_SEEK_SECONDS) : CenterSlotAction

    @Immutable
    @Serializable
    @SerialName("seek_forward")
    data class SeekForward(val seconds: Int = DEFAULT_SEEK_SECONDS) : CenterSlotAction

    fun sanitized(): CenterSlotAction = when (this) {
        None, Previous, Next -> this
        is SeekBackward -> SeekBackward(seconds.coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS))
        is SeekForward -> SeekForward(seconds.coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS))
    }

    fun toControlIdOrNull(): PlayerControlId? = when (this) {
        None -> null
        Previous -> PlayerControlId.Previous
        Next -> PlayerControlId.Next
        is SeekBackward -> PlayerControlId.SeekBackward
        is SeekForward -> PlayerControlId.SeekForward
    }

    fun symmetricOpposite(): CenterSlotAction = when (this) {
        None -> None
        Previous -> Next
        Next -> Previous
        is SeekBackward -> SeekForward(seconds.coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS))
        is SeekForward -> SeekBackward(seconds.coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS))
    }

    fun seekSecondsOrNull(): Int? = when (this) {
        is SeekBackward -> seconds.coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS)
        is SeekForward -> seconds.coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS)
        None, Previous, Next -> null
    }
}

@Serializable
enum class CenterSlotScale(val ratio: Float) {
    Large(0.92f),
    Medium(0.78f),
    Small(0.62f),
    ExtraSmall(0.50f),
}

@Immutable
@Serializable
data class CenterSlotConfig(
    val action: CenterSlotAction = CenterSlotAction.None,
    val scale: CenterSlotScale = CenterSlotScale.Medium,
    val showBackground: Boolean = true,
) {
    fun sanitized(): CenterSlotConfig = copy(action = action.sanitized())
}

@Immutable
@Serializable
data class CenterClusterConfig(
    val showCenterPlay: Boolean = true,
    val symmetricSideSlots: Boolean = true,
    val outerLeft: CenterSlotConfig = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
    val innerLeft: CenterSlotConfig = CenterSlotConfig(CenterSlotAction.Previous, CenterSlotScale.Medium),
    val innerRight: CenterSlotConfig = CenterSlotConfig(CenterSlotAction.Next, CenterSlotScale.Medium),
    val outerRight: CenterSlotConfig = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
) {
    fun slot(position: CenterSlotPosition): CenterSlotConfig = when (position) {
        CenterSlotPosition.OuterLeft -> outerLeft
        CenterSlotPosition.InnerLeft -> innerLeft
        CenterSlotPosition.Center -> CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Large)
        CenterSlotPosition.InnerRight -> innerRight
        CenterSlotPosition.OuterRight -> outerRight
    }

    fun withSlot(position: CenterSlotPosition, config: CenterSlotConfig): CenterClusterConfig = when (position) {
        CenterSlotPosition.OuterLeft -> copy(outerLeft = config.sanitized())
        CenterSlotPosition.InnerLeft -> copy(innerLeft = config.sanitized())
        CenterSlotPosition.Center -> this
        CenterSlotPosition.InnerRight -> copy(innerRight = config.sanitized())
        CenterSlotPosition.OuterRight -> copy(outerRight = config.sanitized())
    }

    fun hasControl(id: PlayerControlId): Boolean {
        if (id == PlayerControlId.PlayPause) return showCenterPlay
        return outerLeft.action.toControlIdOrNull() == id ||
            innerLeft.action.toControlIdOrNull() == id ||
            innerRight.action.toControlIdOrNull() == id ||
            outerRight.action.toControlIdOrNull() == id
    }

    fun removeControl(id: PlayerControlId): CenterClusterConfig {
        if (id == PlayerControlId.PlayPause) return copy(showCenterPlay = false)
        return copy(
            outerLeft = if (outerLeft.action.toControlIdOrNull() == id) outerLeft.copy(action = CenterSlotAction.None) else outerLeft,
            innerLeft = if (innerLeft.action.toControlIdOrNull() == id) innerLeft.copy(action = CenterSlotAction.None) else innerLeft,
            innerRight = if (innerRight.action.toControlIdOrNull() == id) innerRight.copy(action = CenterSlotAction.None) else innerRight,
            outerRight = if (outerRight.action.toControlIdOrNull() == id) outerRight.copy(action = CenterSlotAction.None) else outerRight,
        )
    }

    fun withAllSlotBackgrounds(showBackground: Boolean): CenterClusterConfig = copy(
        outerLeft = outerLeft.copy(showBackground = showBackground),
        innerLeft = innerLeft.copy(showBackground = showBackground),
        innerRight = innerRight.copy(showBackground = showBackground),
        outerRight = outerRight.copy(showBackground = showBackground),
    )
}

/** 手势与状态指示器的垂直位置偏好（偏上、居中、偏下），按布局独立配置。 */
@Serializable
enum class IndicatorVerticalPosition {
    Top,
    Center,
    Bottom,
}

@Immutable
@Serializable
data class PlayerFormFactorLayout(
    val center: CenterClusterConfig,
    val topBar: List<PlayerPanelItem>,
    val bottomBar: List<PlayerPanelItem>,
    val showIndicatorBackground: Boolean = true,
    val indicatorPosition: IndicatorVerticalPosition = IndicatorVerticalPosition.Center,
) {
    fun itemsFor(bar: PlayerBarSlot): List<PlayerPanelItem> = when (bar) {
        PlayerBarSlot.Top -> topBar
        PlayerBarSlot.Bottom -> bottomBar
    }

    fun hasInTopBar(id: PlayerControlId): Boolean =
        topBar.any { it is PlayerPanelItem.Control && it.id == id }

    fun hasInBottomBar(id: PlayerControlId): Boolean =
        bottomBar.any { it is PlayerPanelItem.Control && it.id == id }

    fun hasControl(id: PlayerControlId): Boolean =
        center.hasControl(id) || hasInTopBar(id) || hasInBottomBar(id)

    /**
     * 当触控中央控件簇激活时，已在中央簇展示的播放/切集/步进键在顶栏与底栏中自动去重隐藏；
     * 键鼠模式或关闭触控中央控件时，栏位中的对应控件正常显示。
     */
    fun isHiddenByCenter(id: PlayerControlId, centerActive: Boolean): Boolean =
        centerActive && center.hasControl(id)

    fun removeFromBars(id: PlayerControlId): PlayerFormFactorLayout = copy(
        topBar = topBar.filterNot { it is PlayerPanelItem.Control && it.id == id },
        bottomBar = bottomBar.filterNot { it is PlayerPanelItem.Control && it.id == id },
    )

    fun withCenterPlay(show: Boolean, mode: PlayerLayoutMode): PlayerFormFactorLayout =
        copy(center = center.copy(showCenterPlay = show)).sanitize(mode)

    fun withSymmetricSideSlots(symmetric: Boolean, mode: PlayerLayoutMode): PlayerFormFactorLayout =
        withSymmetricSideSlotsRule(this, symmetric, mode)

    fun withCenterSlot(
        position: CenterSlotPosition,
        config: CenterSlotConfig,
        mode: PlayerLayoutMode,
        applySymmetry: Boolean = center.symmetricSideSlots,
    ): PlayerFormFactorLayout =
        withCenterSlotRule(this, position, config, mode, applySymmetry)

    fun withSymmetricCenterSlot(
        position: CenterSlotPosition,
        mode: PlayerLayoutMode,
    ): PlayerFormFactorLayout =
        withSymmetricCenterSlotRule(this, position, mode)

    fun withBarItemBackground(
        bar: PlayerBarSlot,
        index: Int,
        showBackground: Boolean,
        mode: PlayerLayoutMode,
    ): PlayerFormFactorLayout =
        withBarItemBackgroundRule(this, bar, index, showBackground, mode)

    fun hasAnyControlBackground(mode: PlayerLayoutMode): Boolean =
        hasAnyControlBackgroundRule(this, mode)

    fun withAllControlBackgrounds(
        showBackground: Boolean,
        mode: PlayerLayoutMode,
    ): PlayerFormFactorLayout =
        withAllControlBackgroundsRule(this, showBackground, mode)

    fun withIndicatorBackground(
        showBackground: Boolean,
        mode: PlayerLayoutMode,
    ): PlayerFormFactorLayout =
        copy(showIndicatorBackground = showBackground).sanitize(mode)

    fun withIndicatorPosition(
        position: IndicatorVerticalPosition,
        mode: PlayerLayoutMode,
    ): PlayerFormFactorLayout =
        copy(indicatorPosition = position).sanitize(mode)

    fun isItemAllowedInBar(item: PlayerPanelItem, bar: PlayerBarSlot, mode: PlayerLayoutMode): Boolean =
        isItemAllowedInBarRule(item, bar, mode)

    fun addToBar(
        bar: PlayerBarSlot,
        item: PlayerPanelItem,
        mode: PlayerLayoutMode,
    ): PlayerFormFactorLayout =
        addToBarRule(this, bar, item, mode)

    fun insertIntoBar(
        bar: PlayerBarSlot,
        index: Int,
        item: PlayerPanelItem,
        mode: PlayerLayoutMode,
    ): PlayerFormFactorLayout =
        insertIntoBarRule(this, bar, index, item, mode)

    fun moveBarItemToBarAt(
        fromBar: PlayerBarSlot,
        fromIndex: Int,
        toBar: PlayerBarSlot,
        toIndex: Int,
        mode: PlayerLayoutMode,
    ): PlayerFormFactorLayout =
        moveBarItemToBarAtRule(this, fromBar, fromIndex, toBar, toIndex, mode)

    fun moveBarItem(
        bar: PlayerBarSlot,
        fromIndex: Int,
        toIndex: Int,
        mode: PlayerLayoutMode,
    ): PlayerFormFactorLayout =
        moveBarItemRule(this, bar, fromIndex, toIndex, mode)

    fun canMoveToOtherBar(
        fromBar: PlayerBarSlot,
        index: Int,
        mode: PlayerLayoutMode,
    ): Boolean =
        canMoveToOtherBarRule(this, fromBar, index, mode)

    fun moveBarItemToOtherBar(
        fromBar: PlayerBarSlot,
        index: Int,
        mode: PlayerLayoutMode,
    ): PlayerFormFactorLayout =
        moveBarItemToOtherBarRule(this, fromBar, index, mode)

    fun canRemoveBarItem(bar: PlayerBarSlot, index: Int, mode: PlayerLayoutMode): Boolean =
        canRemoveBarItemRule(this, bar, index, mode)

    fun removeBarItem(
        bar: PlayerBarSlot,
        index: Int,
        mode: PlayerLayoutMode,
    ): PlayerFormFactorLayout =
        removeBarItemRule(this, bar, index, mode)

    fun sanitize(mode: PlayerLayoutMode): PlayerFormFactorLayout =
        sanitizeFormFactorLayout(this, mode)
}

@Serializable
enum class ListenCoverStyle {
    VinylDisc,
    StaticCover,
}

@Serializable
enum class ListenQuickChip {
    Speed,
    SleepTimer,
    Queue,
}

@Immutable
@Serializable
data class PlayerStatusBarConfig(
    val showClock: Boolean = true,
    val showNetwork: Boolean = true,
    val showBattery: Boolean = true,
    val showIcons: Boolean = true,
) {
    val hasAnyItem: Boolean get() = showClock || showNetwork || showBattery

    companion object {
        val Default = PlayerStatusBarConfig()
    }
}

@Immutable
@Serializable
data class ListenLayoutConfig(
    val coverStyle: ListenCoverStyle = ListenCoverStyle.VinylDisc,
    val translucentPlayButton: Boolean = false,
    val symmetricSideSlots: Boolean = true,
    val outerLeft: CenterSlotConfig = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
    val innerLeft: CenterSlotConfig = CenterSlotConfig(CenterSlotAction.Previous, CenterSlotScale.Medium),
    val innerRight: CenterSlotConfig = CenterSlotConfig(CenterSlotAction.Next, CenterSlotScale.Medium),
    val outerRight: CenterSlotConfig = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
    val visibleChips: List<ListenQuickChip> = ListenQuickChip.entries,
) {
    fun slot(position: CenterSlotPosition): CenterSlotConfig = when (position) {
        CenterSlotPosition.OuterLeft -> outerLeft
        CenterSlotPosition.InnerLeft -> innerLeft
        CenterSlotPosition.Center -> CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Large)
        CenterSlotPosition.InnerRight -> innerRight
        CenterSlotPosition.OuterRight -> outerRight
    }

    fun withSymmetricSideSlots(symmetric: Boolean): ListenLayoutConfig =
        withListenSymmetricSideSlotsRule(this, symmetric)

    fun withSlot(
        position: CenterSlotPosition,
        config: CenterSlotConfig,
        applySymmetry: Boolean = symmetricSideSlots,
    ): ListenLayoutConfig =
        withListenSlotRule(this, position, config, applySymmetry)

    fun withSymmetricSlot(position: CenterSlotPosition): ListenLayoutConfig =
        withListenSymmetricSlotRule(this, position)

    fun hasChip(chip: ListenQuickChip): Boolean = chip in visibleChips

    fun toggleChip(chip: ListenQuickChip, visible: Boolean): ListenLayoutConfig =
        toggleListenChipRule(this, chip, visible)

    fun sanitize(): ListenLayoutConfig = sanitizeListenLayoutConfig(this)

    companion object {
        val Default = createDefaultListenLayoutConfig()
    }
}

@Serializable
enum class VerticalSwipeAction {
    Brightness,
    Volume,
    Episode,
    None,
}

@Serializable
enum class SwipeSeekSensitivity(val spanMultiplier: Float) {
    Low(0.6f),
    Standard(1.0f),
    High(1.6f),
}

@Immutable
@Serializable
data class PlayerGestureConfig(
    val doubleTapSeekEnabled: Boolean = true,
    val longPressSpeedEnabled: Boolean = true,
    val longPressSpeedDialEnabled: Boolean = true,
    val swipeSeekEnabled: Boolean = true,
    val swipeBrightnessVolumeEnabled: Boolean = true,
    val leftVerticalSwipe: VerticalSwipeAction = VerticalSwipeAction.Brightness,
    val rightVerticalSwipe: VerticalSwipeAction = VerticalSwipeAction.Volume,
    val verticalZoneFraction: Float = DEFAULT_VERTICAL_ZONE_FRACTION,
    val swipeSeekSensitivity: SwipeSeekSensitivity = SwipeSeekSensitivity.Standard,
    val swipeSeekCancelZone: Boolean = true,
    val doubleTapZoneFraction: Float = DEFAULT_DOUBLE_TAP_ZONE_FRACTION,
) {
    fun sanitize(): PlayerGestureConfig = sanitizePlayerGestureConfig(this)

    companion object {
        val Default = PlayerGestureConfig()
    }
}

enum class PlayerLayoutPreset {
    Default,
    Minimal,
    Podcast,
}

@Immutable
@Serializable
data class PlayerLayoutPrefs(
    val version: Int = PLAYER_LAYOUT_PREFS_VERSION,
    val videoLandscape: PlayerFormFactorLayout = DefaultVideoLandscape,
    val videoPortrait: PlayerFormFactorLayout = DefaultVideoPortrait,
    val clipsLandscape: PlayerFormFactorLayout = DefaultClipsLandscape,
    val clipsPortrait: PlayerFormFactorLayout = DefaultClipsPortrait,
    val showCenterControlsOnTouch: Boolean = true,
    /** 视频播放器中央与控制栏播放按钮采用半透明暗色毛玻璃底，默认开启。 */
    val translucentPlayButton: Boolean = true,
    /** 竖屏播放横屏视频时在画面下方悬浮展示一键全屏入口，默认开启。 */
    val showPortraitFullscreenPrompt: Boolean = true,
    val seekButtonSeconds: Int = DEFAULT_SEEK_SECONDS,
    val doubleTapSeekSeconds: Int = DEFAULT_DOUBLE_TAP_SEEK_SECONDS,
    val syncDoubleTapWithSeekButton: Boolean = true,
    val listen: ListenLayoutConfig = ListenLayoutConfig.Default,
    val gestures: PlayerGestureConfig = PlayerGestureConfig.Default,
    val statusBar: PlayerStatusBarConfig = PlayerStatusBarConfig.Default,
) {
    val effectiveDoubleTapSeekSeconds: Int
        get() = (if (syncDoubleTapWithSeekButton) seekButtonSeconds else doubleTapSeekSeconds)
            .coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS)

    fun isCenterControlsActive(isTouch: Boolean): Boolean = isTouch && showCenterControlsOnTouch

    fun layoutFor(mode: PlayerLayoutMode): PlayerFormFactorLayout = when (mode) {
        PlayerLayoutMode.VideoLandscape -> videoLandscape
        PlayerLayoutMode.VideoPortrait -> videoPortrait
        PlayerLayoutMode.ClipsLandscape -> clipsLandscape
        PlayerLayoutMode.ClipsPortrait -> clipsPortrait
    }

    fun videoLayout(isLandscape: Boolean): PlayerFormFactorLayout =
        layoutFor(PlayerLayoutMode.video(isLandscape))

    fun clipsLayout(isLandscape: Boolean): PlayerFormFactorLayout =
        layoutFor(PlayerLayoutMode.clips(isLandscape))

    fun updateLayout(
        mode: PlayerLayoutMode,
        transform: (PlayerFormFactorLayout) -> PlayerFormFactorLayout,
    ): PlayerLayoutPrefs = updateModeLayoutRule(this, mode, transform)

    fun resetMode(mode: PlayerLayoutMode): PlayerLayoutPrefs =
        resetModeLayoutRule(this, mode)

    fun updateListen(
        transform: (ListenLayoutConfig) -> ListenLayoutConfig,
    ): PlayerLayoutPrefs = copy(listen = transform(listen)).sanitize()

    fun updateGestures(
        transform: (PlayerGestureConfig) -> PlayerGestureConfig,
    ): PlayerLayoutPrefs = copy(gestures = transform(gestures)).sanitize()

    fun updateStatusBar(
        transform: (PlayerStatusBarConfig) -> PlayerStatusBarConfig,
    ): PlayerLayoutPrefs = copy(statusBar = transform(statusBar)).sanitize()

    fun sanitize(): PlayerLayoutPrefs = sanitizePlayerLayoutPrefs(this)

    companion object {
        val DefaultCenter: CenterClusterConfig = createDefaultCenter()
        val MinimalCenter: CenterClusterConfig = createMinimalCenter()
        val PodcastCenter: CenterClusterConfig = createPodcastCenter()

        val DefaultVideoLandscape: PlayerFormFactorLayout = createDefaultVideoLandscape(DefaultCenter)
        val DefaultVideoPortrait: PlayerFormFactorLayout = createDefaultVideoPortrait(DefaultCenter)
        val DefaultClipsLandscape: PlayerFormFactorLayout = createDefaultClipsLandscape(DefaultCenter)
        val DefaultClipsPortrait: PlayerFormFactorLayout = createDefaultClipsPortrait(MinimalCenter)

        fun defaultLayoutFor(mode: PlayerLayoutMode): PlayerFormFactorLayout = when (mode) {
            PlayerLayoutMode.VideoLandscape -> DefaultVideoLandscape
            PlayerLayoutMode.VideoPortrait -> DefaultVideoPortrait
            PlayerLayoutMode.ClipsLandscape -> DefaultClipsLandscape
            PlayerLayoutMode.ClipsPortrait -> DefaultClipsPortrait
        }

        val Default: PlayerLayoutPrefs = createDefaultPlayerLayoutPrefs(
            videoLandscape = DefaultVideoLandscape,
            videoPortrait = DefaultVideoPortrait,
            clipsLandscape = DefaultClipsLandscape,
            clipsPortrait = DefaultClipsPortrait,
        )

        val Minimal: PlayerLayoutPrefs = createMinimalPlayerLayoutPrefs(MinimalCenter)

        val Podcast: PlayerLayoutPrefs = createPodcastPlayerLayoutPrefs(
            podcastCenter = PodcastCenter,
            defaultClipsLandscape = DefaultClipsLandscape,
            defaultClipsPortrait = DefaultClipsPortrait,
        )

        fun preset(preset: PlayerLayoutPreset): PlayerLayoutPrefs = when (preset) {
            PlayerLayoutPreset.Default -> Default
            PlayerLayoutPreset.Minimal -> Minimal
            PlayerLayoutPreset.Podcast -> Podcast
        }
    }
}
