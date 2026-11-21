package dev.piko.shared.media.player

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

private val VIDEO_ALLOWED_CONTROLS: Set<PlayerControlId> = setOf(
    PlayerControlId.Back,
    PlayerControlId.Title,
    PlayerControlId.SystemStatus,
    PlayerControlId.PlayPause,
    PlayerControlId.Previous,
    PlayerControlId.Next,
    PlayerControlId.SeekBackward,
    PlayerControlId.SeekForward,
    PlayerControlId.TimeLabel,
    PlayerControlId.Volume,
    PlayerControlId.Quality,
    PlayerControlId.Speed,
    PlayerControlId.Episodes,
    PlayerControlId.Tracks,
    PlayerControlId.Listen,
    PlayerControlId.Rotate,
    PlayerControlId.AspectRatio,
    PlayerControlId.Lock,
    PlayerControlId.Stats,
    PlayerControlId.Settings,
    PlayerControlId.Fullscreen,
    PlayerControlId.PinOnTop,
)

private val CLIPS_TOP_ALLOWED_CONTROLS: Set<PlayerControlId> = setOf(
    PlayerControlId.Back,
    PlayerControlId.Title,
    PlayerControlId.SystemStatus,
    PlayerControlId.PlayPause,
    PlayerControlId.Volume,
    PlayerControlId.Rotate,
    PlayerControlId.Fullscreen,
    PlayerControlId.PinOnTop,
    PlayerControlId.AutoAdvance,
    PlayerControlId.HideChrome,
)

private val CLIPS_BOTTOM_ALLOWED_CONTROLS: Set<PlayerControlId> = setOf(
    PlayerControlId.PlayPause,
    PlayerControlId.Previous,
    PlayerControlId.Next,
    PlayerControlId.SeekBackward,
    PlayerControlId.SeekForward,
    PlayerControlId.Star,
    PlayerControlId.OpenFull,
    PlayerControlId.OpenFolder,
)

private val CLIPS_ALLOWED_CONTROLS: Set<PlayerControlId> =
    CLIPS_TOP_ALLOWED_CONTROLS + CLIPS_BOTTOM_ALLOWED_CONTROLS

fun allowedControlsFor(mode: PlayerLayoutMode): Set<PlayerControlId> =
    if (mode.isClips) CLIPS_ALLOWED_CONTROLS else VIDEO_ALLOWED_CONTROLS

fun allowedControlsFor(mode: PlayerLayoutMode, bar: PlayerBarSlot): Set<PlayerControlId> = when {
    mode.isVideo -> VIDEO_ALLOWED_CONTROLS
    bar == PlayerBarSlot.Top -> CLIPS_TOP_ALLOWED_CONTROLS
    else -> CLIPS_BOTTOM_ALLOWED_CONTROLS
}

fun allowsSpacerIn(mode: PlayerLayoutMode, bar: PlayerBarSlot): Boolean =
    mode.isVideo || bar == PlayerBarSlot.Top

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
}

@Immutable
@Serializable
data class PlayerFormFactorLayout(
    val center: CenterClusterConfig,
    val topBar: List<PlayerPanelItem>,
    val bottomBar: List<PlayerPanelItem>,
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

    fun withSymmetricSideSlots(symmetric: Boolean, mode: PlayerLayoutMode): PlayerFormFactorLayout {
        val next = copy(center = center.copy(symmetricSideSlots = symmetric))
        if (!symmetric) return next.sanitize(mode)
        return next
            .withSymmetricCenterSlot(CenterSlotPosition.InnerLeft, mode)
            .withSymmetricCenterSlot(CenterSlotPosition.OuterLeft, mode)
    }

    fun withCenterSlot(
        position: CenterSlotPosition,
        config: CenterSlotConfig,
        mode: PlayerLayoutMode,
        applySymmetry: Boolean = center.symmetricSideSlots,
    ): PlayerFormFactorLayout {
        if (position == CenterSlotPosition.Center) return this
        val sanitizedSlot = config.sanitized()
        val controlId = sanitizedSlot.action.toControlIdOrNull()
        val clearedCenter = if (controlId != null) center.removeControl(controlId) else center
        val updated = copy(center = clearedCenter.withSlot(position, sanitizedSlot)).sanitize(mode)
        return if (applySymmetry) updated.withSymmetricCenterSlot(position, mode) else updated
    }

    fun withSymmetricCenterSlot(
        position: CenterSlotPosition,
        mode: PlayerLayoutMode,
    ): PlayerFormFactorLayout {
        if (position == CenterSlotPosition.Center) return this
        val source = center.slot(position)
        val opposite = position.symmetricOpposite()
        val targetConfig = CenterSlotConfig(
            action = source.action.symmetricOpposite(),
            scale = source.scale,
            showBackground = source.showBackground,
        )
        return withCenterSlot(opposite, targetConfig, mode, applySymmetry = false)
    }

    fun withBarItemBackground(
        bar: PlayerBarSlot,
        index: Int,
        showBackground: Boolean,
        mode: PlayerLayoutMode,
    ): PlayerFormFactorLayout {
        val list = itemsFor(bar)
        val item = list.getOrNull(index) as? PlayerPanelItem.Control ?: return this
        val mutable = list.toMutableList()
        mutable[index] = item.copy(showBackground = showBackground)
        return when (bar) {
            PlayerBarSlot.Top -> copy(topBar = mutable)
            PlayerBarSlot.Bottom -> copy(bottomBar = mutable)
        }.sanitize(mode)
    }

    fun isItemAllowedInBar(item: PlayerPanelItem, bar: PlayerBarSlot, mode: PlayerLayoutMode): Boolean = when (item) {
        PlayerPanelItem.Spacer -> allowsSpacerIn(mode, bar)
        is PlayerPanelItem.Control -> item.id in allowedControlsFor(mode, bar)
    }

    fun addToBar(
        bar: PlayerBarSlot,
        item: PlayerPanelItem,
        mode: PlayerLayoutMode,
    ): PlayerFormFactorLayout {
        if (!isItemAllowedInBar(item, bar, mode)) return this
        val base = if (item is PlayerPanelItem.Control) removeFromBars(item.id) else this
        val updated = when (bar) {
            PlayerBarSlot.Top -> base.copy(topBar = base.topBar + item)
            PlayerBarSlot.Bottom -> base.copy(bottomBar = base.bottomBar + item)
        }
        return updated.sanitize(mode)
    }

    fun insertIntoBar(
        bar: PlayerBarSlot,
        index: Int,
        item: PlayerPanelItem,
        mode: PlayerLayoutMode,
    ): PlayerFormFactorLayout {
        if (!isItemAllowedInBar(item, bar, mode)) return this
        if (item is PlayerPanelItem.Control && mode.isVideo && item.id == PlayerControlId.Back) return this
        val existingIndexInTarget = if (item is PlayerPanelItem.Control) {
            itemsFor(bar).indexOfFirst { it is PlayerPanelItem.Control && it.id == item.id }
        } else {
            -1
        }
        val base = if (item is PlayerPanelItem.Control) removeFromBars(item.id) else this
        val targetList = base.itemsFor(bar).toMutableList()
        val adjusted = if (existingIndexInTarget in 0 until index) index - 1 else index
        val minIndex = if (
            mode.isVideo &&
            bar == PlayerBarSlot.Top &&
            targetList.firstOrNull().let { it is PlayerPanelItem.Control && it.id == PlayerControlId.Back }
        ) {
            1
        } else {
            0
        }
        val clampedIndex = adjusted.coerceIn(minIndex, targetList.size)
        targetList.add(clampedIndex, item)
        val updated = when (bar) {
            PlayerBarSlot.Top -> base.copy(topBar = targetList)
            PlayerBarSlot.Bottom -> base.copy(bottomBar = targetList)
        }
        return updated.sanitize(mode)
    }

    fun moveBarItemToBarAt(
        fromBar: PlayerBarSlot,
        fromIndex: Int,
        toBar: PlayerBarSlot,
        toIndex: Int,
        mode: PlayerLayoutMode,
    ): PlayerFormFactorLayout {
        val sourceList = itemsFor(fromBar)
        val item = sourceList.getOrNull(fromIndex) ?: return this
        if (mode.isVideo && item is PlayerPanelItem.Control && item.id == PlayerControlId.Back) return this
        if (!isItemAllowedInBar(item, toBar, mode)) return this
        if (fromBar == toBar) {
            val mutable = sourceList.toMutableList()
            val moved = mutable.removeAt(fromIndex)
            val adjusted = if (fromIndex < toIndex) toIndex - 1 else toIndex
            val minIndex = if (
                mode.isVideo &&
                toBar == PlayerBarSlot.Top &&
                mutable.firstOrNull().let { it is PlayerPanelItem.Control && it.id == PlayerControlId.Back }
            ) {
                1
            } else {
                0
            }
            val clamped = adjusted.coerceIn(minIndex, mutable.size)
            mutable.add(clamped, moved)
            return when (toBar) {
                PlayerBarSlot.Top -> copy(topBar = mutable)
                PlayerBarSlot.Bottom -> copy(bottomBar = mutable)
            }.sanitize(mode)
        }
        val removedSource = sourceList.toMutableList().apply { removeAt(fromIndex) }
        val targetList = itemsFor(toBar).toMutableList()
        val minIndex = if (
            mode.isVideo &&
            toBar == PlayerBarSlot.Top &&
            targetList.firstOrNull().let { it is PlayerPanelItem.Control && it.id == PlayerControlId.Back }
        ) {
            1
        } else {
            0
        }
        val clamped = toIndex.coerceIn(minIndex, targetList.size)
        targetList.add(clamped, item)
        val updated = when (fromBar) {
            PlayerBarSlot.Top -> copy(topBar = removedSource, bottomBar = targetList)
            PlayerBarSlot.Bottom -> copy(bottomBar = removedSource, topBar = targetList)
        }
        return updated.sanitize(mode)
    }

    fun moveBarItem(
        bar: PlayerBarSlot,
        fromIndex: Int,
        toIndex: Int,
        mode: PlayerLayoutMode,
    ): PlayerFormFactorLayout {
        val list = itemsFor(bar)
        if (fromIndex !in list.indices || toIndex !in list.indices || fromIndex == toIndex) return this
        if (mode.isVideo && bar == PlayerBarSlot.Top) {
            val moving = list[fromIndex]
            val target = list[toIndex]
            if ((moving is PlayerPanelItem.Control && moving.id == PlayerControlId.Back) ||
                (target is PlayerPanelItem.Control && target.id == PlayerControlId.Back)
            ) {
                return this
            }
        }
        val mutable = list.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)
        return when (bar) {
            PlayerBarSlot.Top -> copy(topBar = mutable)
            PlayerBarSlot.Bottom -> copy(bottomBar = mutable)
        }.sanitize(mode)
    }

    fun canMoveToOtherBar(
        fromBar: PlayerBarSlot,
        index: Int,
        mode: PlayerLayoutMode,
    ): Boolean {
        val item = itemsFor(fromBar).getOrNull(index) ?: return false
        if (item is PlayerPanelItem.Control && item.id == PlayerControlId.Back) return false
        return isItemAllowedInBar(item, fromBar.other(), mode)
    }

    fun moveBarItemToOtherBar(
        fromBar: PlayerBarSlot,
        index: Int,
        mode: PlayerLayoutMode,
    ): PlayerFormFactorLayout {
        if (!canMoveToOtherBar(fromBar, index, mode)) return this
        val list = itemsFor(fromBar)
        val item = list[index]
        val removed = list.toMutableList().apply { removeAt(index) }
        val updated = when (fromBar) {
            PlayerBarSlot.Top -> copy(topBar = removed, bottomBar = bottomBar + item)
            PlayerBarSlot.Bottom -> copy(bottomBar = removed, topBar = topBar + item)
        }
        return updated.sanitize(mode)
    }

    fun canRemoveBarItem(bar: PlayerBarSlot, index: Int, mode: PlayerLayoutMode): Boolean {
        val list = itemsFor(bar)
        val item = list.getOrNull(index) ?: return false
        if (item is PlayerPanelItem.Spacer) return true
        val id = (item as PlayerPanelItem.Control).id
        return when {
            id == PlayerControlId.Back -> false
            mode.isVideo && id == PlayerControlId.PlayPause -> false
            else -> true
        }
    }

    fun removeBarItem(
        bar: PlayerBarSlot,
        index: Int,
        mode: PlayerLayoutMode,
    ): PlayerFormFactorLayout {
        if (!canRemoveBarItem(bar, index, mode)) return this
        val list = itemsFor(bar)
        val updated = list.toMutableList().apply { removeAt(index) }
        return when (bar) {
            PlayerBarSlot.Top -> copy(topBar = updated)
            PlayerBarSlot.Bottom -> copy(bottomBar = updated)
        }.sanitize(mode)
    }

    /**
     * 强校验与修复护栏：
     * 1. 秒数限制在 [MIN_SEEK_SECONDS]..[MAX_SEEK_SECONDS]；
     * 2. 过滤不属于当前模式（视频 vs 信息流）的非法控件；
     * 3. 中央簇 4 个侧槽位内去重，顶栏与底栏之间去重（严格遵守单一入口 DRY）；
     * 4. 基本可用性：视频模式顶栏保留最左端返回键、栏位中保留播放/暂停；信息流模式顶栏保留返回/关闭键。
     */
    fun sanitize(mode: PlayerLayoutMode): PlayerFormFactorLayout {
        val allowed = allowedControlsFor(mode)
        val seenCenter = mutableSetOf<PlayerControlId>()
        fun sanitizeSlot(slot: CenterSlotConfig): CenterSlotConfig {
            val clamped = slot.sanitized()
            val id = clamped.action.toControlIdOrNull() ?: return clamped
            return if (id in allowed && seenCenter.add(id)) clamped else clamped.copy(action = CenterSlotAction.None)
        }
        val cleanCenter = CenterClusterConfig(
            showCenterPlay = center.showCenterPlay,
            symmetricSideSlots = center.symmetricSideSlots,
            innerLeft = sanitizeSlot(center.innerLeft),
            innerRight = sanitizeSlot(center.innerRight),
            outerLeft = sanitizeSlot(center.outerLeft),
            outerRight = sanitizeSlot(center.outerRight),
        )

        val allowedTop = allowedControlsFor(mode, PlayerBarSlot.Top)
        val allowedBottom = allowedControlsFor(mode, PlayerBarSlot.Bottom)
        val seenBars = mutableSetOf<PlayerControlId>()
        val cleanTop = mutableListOf<PlayerPanelItem>()
        if (mode.isVideo) {
            val existingBack = topBar.firstOrNull {
                it is PlayerPanelItem.Control && it.id == PlayerControlId.Back
            } as? PlayerPanelItem.Control
            seenBars += PlayerControlId.Back
            cleanTop += existingBack ?: PlayerPanelItem.Control(PlayerControlId.Back)
        }
        for (item in topBar) {
            when (item) {
                PlayerPanelItem.Spacer -> if (allowsSpacerIn(mode, PlayerBarSlot.Top)) cleanTop += item
                is PlayerPanelItem.Control -> {
                    if (item.id !in allowedTop) continue
                    if (seenBars.add(item.id)) {
                        cleanTop += item
                    }
                }
            }
        }
        if (mode.isClips && PlayerControlId.Back !in seenBars) {
            cleanTop += PlayerPanelItem.Control(PlayerControlId.Back)
            seenBars += PlayerControlId.Back
        }

        val cleanBottom = mutableListOf<PlayerPanelItem>()
        for (item in bottomBar) {
            when (item) {
                PlayerPanelItem.Spacer -> if (allowsSpacerIn(mode, PlayerBarSlot.Bottom)) cleanBottom += item
                is PlayerPanelItem.Control -> {
                    if (item.id !in allowedBottom) continue
                    if (item.id == PlayerControlId.Back) continue
                    if (seenBars.add(item.id)) {
                        cleanBottom += item
                    }
                }
            }
        }

        if (mode.isVideo && PlayerControlId.PlayPause !in seenBars) {
            cleanBottom.add(0, PlayerPanelItem.Control(PlayerControlId.PlayPause))
            seenBars += PlayerControlId.PlayPause
        }

        return PlayerFormFactorLayout(
            center = cleanCenter,
            topBar = cleanTop,
            bottomBar = cleanBottom,
        )
    }
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

    fun withSymmetricSideSlots(symmetric: Boolean): ListenLayoutConfig {
        val next = copy(symmetricSideSlots = symmetric)
        if (!symmetric) return next.sanitize()
        return next
            .withSymmetricSlot(CenterSlotPosition.InnerLeft)
            .withSymmetricSlot(CenterSlotPosition.OuterLeft)
    }

    fun withSlot(
        position: CenterSlotPosition,
        config: CenterSlotConfig,
        applySymmetry: Boolean = symmetricSideSlots,
    ): ListenLayoutConfig {
        if (position == CenterSlotPosition.Center) return this
        val sanitizedSlot = config.sanitized()
        val id = sanitizedSlot.action.toControlIdOrNull()
        fun clearIfSame(existing: CenterSlotConfig): CenterSlotConfig =
            if (id != null && existing.action.toControlIdOrNull() == id) {
                existing.copy(action = CenterSlotAction.None)
            } else {
                existing
            }
        val cleared = copy(
            outerLeft = clearIfSame(outerLeft),
            innerLeft = clearIfSame(innerLeft),
            innerRight = clearIfSame(innerRight),
            outerRight = clearIfSame(outerRight),
        )
        val updated = when (position) {
            CenterSlotPosition.OuterLeft -> cleared.copy(outerLeft = sanitizedSlot)
            CenterSlotPosition.InnerLeft -> cleared.copy(innerLeft = sanitizedSlot)
            CenterSlotPosition.Center -> cleared
            CenterSlotPosition.InnerRight -> cleared.copy(innerRight = sanitizedSlot)
            CenterSlotPosition.OuterRight -> cleared.copy(outerRight = sanitizedSlot)
        }.sanitize()
        return if (applySymmetry) updated.withSymmetricSlot(position) else updated
    }

    fun withSymmetricSlot(position: CenterSlotPosition): ListenLayoutConfig {
        if (position == CenterSlotPosition.Center) return this
        val source = slot(position)
        val opposite = position.symmetricOpposite()
        val targetConfig = CenterSlotConfig(
            action = source.action.symmetricOpposite(),
            scale = source.scale,
            showBackground = source.showBackground,
        )
        return withSlot(opposite, targetConfig, applySymmetry = false)
    }

    fun hasChip(chip: ListenQuickChip): Boolean = chip in visibleChips

    fun toggleChip(chip: ListenQuickChip, visible: Boolean): ListenLayoutConfig {
        val updated = if (visible) {
            if (chip in visibleChips) visibleChips else visibleChips + chip
        } else {
            visibleChips.filterNot { it == chip }
        }
        return copy(visibleChips = updated).sanitize()
    }

    fun sanitize(): ListenLayoutConfig {
        val seen = mutableSetOf<PlayerControlId>()
        fun sanitizeSlot(slot: CenterSlotConfig): CenterSlotConfig {
            val clamped = slot.sanitized()
            val id = clamped.action.toControlIdOrNull() ?: return clamped
            return if (seen.add(id)) clamped else clamped.copy(action = CenterSlotAction.None)
        }
        val cleanInnerLeft = sanitizeSlot(innerLeft)
        val cleanInnerRight = sanitizeSlot(innerRight)
        val cleanOuterLeft = sanitizeSlot(outerLeft)
        val cleanOuterRight = sanitizeSlot(outerRight)

        return copy(
            outerLeft = cleanOuterLeft,
            innerLeft = cleanInnerLeft,
            innerRight = cleanInnerRight,
            outerRight = cleanOuterRight,
            visibleChips = visibleChips.distinct(),
        )
    }

    companion object {
        val Default = ListenLayoutConfig(
            coverStyle = ListenCoverStyle.VinylDisc,
            outerLeft = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
            innerLeft = CenterSlotConfig(CenterSlotAction.Previous, CenterSlotScale.Medium),
            innerRight = CenterSlotConfig(CenterSlotAction.Next, CenterSlotScale.Medium),
            outerRight = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
            visibleChips = ListenQuickChip.entries,
        )
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
    fun sanitize(): PlayerGestureConfig = copy(
        verticalZoneFraction = if (verticalZoneFraction.isFinite()) {
            verticalZoneFraction.coerceIn(MIN_VERTICAL_ZONE_FRACTION, MAX_VERTICAL_ZONE_FRACTION)
        } else {
            DEFAULT_VERTICAL_ZONE_FRACTION
        },
        doubleTapZoneFraction = if (doubleTapZoneFraction.isFinite()) {
            doubleTapZoneFraction.coerceIn(MIN_DOUBLE_TAP_ZONE_FRACTION, MAX_DOUBLE_TAP_ZONE_FRACTION)
        } else {
            DEFAULT_DOUBLE_TAP_ZONE_FRACTION
        },
    )

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
    ): PlayerLayoutPrefs {
        val updated = when (mode) {
            PlayerLayoutMode.VideoLandscape -> copy(videoLandscape = transform(videoLandscape))
            PlayerLayoutMode.VideoPortrait -> copy(videoPortrait = transform(videoPortrait))
            PlayerLayoutMode.ClipsLandscape -> copy(clipsLandscape = transform(clipsLandscape))
            PlayerLayoutMode.ClipsPortrait -> copy(clipsPortrait = transform(clipsPortrait))
        }
        return updated.sanitize()
    }

    fun resetMode(mode: PlayerLayoutMode): PlayerLayoutPrefs {
        val reset = updateLayout(mode) { defaultLayoutFor(mode) }
        return if (mode == PlayerLayoutMode.VideoPortrait) {
            reset.copy(showPortraitFullscreenPrompt = true)
        } else {
            reset
        }
    }

    fun updateListen(
        transform: (ListenLayoutConfig) -> ListenLayoutConfig,
    ): PlayerLayoutPrefs = copy(listen = transform(listen)).sanitize()

    fun updateGestures(
        transform: (PlayerGestureConfig) -> PlayerGestureConfig,
    ): PlayerLayoutPrefs = copy(gestures = transform(gestures)).sanitize()

    fun updateStatusBar(
        transform: (PlayerStatusBarConfig) -> PlayerStatusBarConfig,
    ): PlayerLayoutPrefs = copy(statusBar = transform(statusBar)).sanitize()

    fun sanitize(): PlayerLayoutPrefs {
        val clampedSeek = seekButtonSeconds.coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS)
        val clampedDoubleTap = doubleTapSeekSeconds.coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS)
        return copy(
            version = PLAYER_LAYOUT_PREFS_VERSION,
            videoLandscape = videoLandscape.sanitize(PlayerLayoutMode.VideoLandscape),
            videoPortrait = videoPortrait.sanitize(PlayerLayoutMode.VideoPortrait),
            clipsLandscape = clipsLandscape.sanitize(PlayerLayoutMode.ClipsLandscape),
            clipsPortrait = clipsPortrait.sanitize(PlayerLayoutMode.ClipsPortrait),
            seekButtonSeconds = clampedSeek,
            doubleTapSeekSeconds = clampedDoubleTap,
            listen = listen.sanitize(),
            gestures = gestures.sanitize(),
        )
    }

    companion object {
        private fun c(id: PlayerControlId, showBackground: Boolean = true): PlayerPanelItem =
            PlayerPanelItem.Control(id, showBackground)

        private val sp: PlayerPanelItem = PlayerPanelItem.Spacer

        val DefaultCenter = CenterClusterConfig(
            showCenterPlay = true,
            outerLeft = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
            innerLeft = CenterSlotConfig(CenterSlotAction.Previous, CenterSlotScale.Medium),
            innerRight = CenterSlotConfig(CenterSlotAction.Next, CenterSlotScale.Medium),
            outerRight = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
        )

        val MinimalCenter = CenterClusterConfig(
            showCenterPlay = true,
            outerLeft = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
            innerLeft = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Medium),
            innerRight = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Medium),
            outerRight = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
        )

        val PodcastCenter = CenterClusterConfig(
            showCenterPlay = true,
            outerLeft = CenterSlotConfig(CenterSlotAction.Previous, CenterSlotScale.Small),
            innerLeft = CenterSlotConfig(CenterSlotAction.SeekBackward(15), CenterSlotScale.Medium),
            innerRight = CenterSlotConfig(CenterSlotAction.SeekForward(15), CenterSlotScale.Medium),
            outerRight = CenterSlotConfig(CenterSlotAction.Next, CenterSlotScale.Small),
        )

        val DefaultVideoLandscape = PlayerFormFactorLayout(
            center = DefaultCenter,
            topBar = listOf(
                c(PlayerControlId.Back),
                c(PlayerControlId.Title),
                sp,
                c(PlayerControlId.SystemStatus, showBackground = false),
                c(PlayerControlId.PinOnTop),
                c(PlayerControlId.Episodes),
                c(PlayerControlId.Tracks),
                c(PlayerControlId.Settings),
            ),
            bottomBar = listOf(
                c(PlayerControlId.PlayPause),
                c(PlayerControlId.TimeLabel),
                c(PlayerControlId.Volume),
                sp,
                c(PlayerControlId.Speed),
                c(PlayerControlId.Quality),
                c(PlayerControlId.Rotate),
                c(PlayerControlId.Fullscreen),
            ),
        )

        val DefaultVideoPortrait = PlayerFormFactorLayout(
            center = DefaultCenter,
            topBar = listOf(
                c(PlayerControlId.Back),
                c(PlayerControlId.Title),
                sp,
                c(PlayerControlId.Episodes),
                c(PlayerControlId.Tracks),
                c(PlayerControlId.Settings),
            ),
            bottomBar = listOf(
                c(PlayerControlId.PlayPause),
                c(PlayerControlId.TimeLabel),
                sp,
                c(PlayerControlId.Speed),
                c(PlayerControlId.Quality),
                c(PlayerControlId.Fullscreen),
            ),
        )

        val DefaultClipsLandscape = PlayerFormFactorLayout(
            center = DefaultCenter,
            topBar = listOf(
                c(PlayerControlId.Title),
                sp,
                c(PlayerControlId.SystemStatus, showBackground = false),
                c(PlayerControlId.HideChrome),
                c(PlayerControlId.AutoAdvance),
                c(PlayerControlId.Volume),
                c(PlayerControlId.PinOnTop),
                c(PlayerControlId.Fullscreen),
                c(PlayerControlId.Back),
            ),
            bottomBar = listOf(
                c(PlayerControlId.Star),
                c(PlayerControlId.OpenFull),
                c(PlayerControlId.OpenFolder),
            ),
        )

        val DefaultClipsPortrait = PlayerFormFactorLayout(
            center = MinimalCenter,
            topBar = listOf(
                c(PlayerControlId.Title),
                sp,
                c(PlayerControlId.Rotate),
                c(PlayerControlId.HideChrome),
                c(PlayerControlId.AutoAdvance),
                c(PlayerControlId.Volume),
                c(PlayerControlId.Back),
            ),
            bottomBar = listOf(
                c(PlayerControlId.Star),
                c(PlayerControlId.OpenFull),
                c(PlayerControlId.OpenFolder),
            ),
        )

        fun defaultLayoutFor(mode: PlayerLayoutMode): PlayerFormFactorLayout = when (mode) {
            PlayerLayoutMode.VideoLandscape -> DefaultVideoLandscape
            PlayerLayoutMode.VideoPortrait -> DefaultVideoPortrait
            PlayerLayoutMode.ClipsLandscape -> DefaultClipsLandscape
            PlayerLayoutMode.ClipsPortrait -> DefaultClipsPortrait
        }

        val Default = PlayerLayoutPrefs(
            version = PLAYER_LAYOUT_PREFS_VERSION,
            videoLandscape = DefaultVideoLandscape,
            videoPortrait = DefaultVideoPortrait,
            clipsLandscape = DefaultClipsLandscape,
            clipsPortrait = DefaultClipsPortrait,
            showCenterControlsOnTouch = true,
            seekButtonSeconds = DEFAULT_SEEK_SECONDS,
            doubleTapSeekSeconds = DEFAULT_DOUBLE_TAP_SEEK_SECONDS,
            syncDoubleTapWithSeekButton = true,
            listen = ListenLayoutConfig.Default,
            gestures = PlayerGestureConfig.Default,
        ).sanitize()

        val Minimal = PlayerLayoutPrefs(
            version = PLAYER_LAYOUT_PREFS_VERSION,
            videoLandscape = PlayerFormFactorLayout(
                center = MinimalCenter,
                topBar = listOf(
                    c(PlayerControlId.Back),
                    c(PlayerControlId.Title),
                    sp,
                    c(PlayerControlId.Settings),
                ),
                bottomBar = listOf(
                    c(PlayerControlId.PlayPause),
                    c(PlayerControlId.TimeLabel),
                    c(PlayerControlId.Volume),
                    sp,
                    c(PlayerControlId.Fullscreen),
                ),
            ),
            videoPortrait = PlayerFormFactorLayout(
                center = MinimalCenter,
                topBar = listOf(
                    c(PlayerControlId.Back),
                    c(PlayerControlId.Title),
                    sp,
                    c(PlayerControlId.Settings),
                ),
                bottomBar = listOf(
                    c(PlayerControlId.PlayPause),
                    c(PlayerControlId.TimeLabel),
                    sp,
                    c(PlayerControlId.Fullscreen),
                ),
            ),
            clipsLandscape = PlayerFormFactorLayout(
                center = MinimalCenter,
                topBar = listOf(
                    c(PlayerControlId.Title),
                    sp,
                    c(PlayerControlId.Volume),
                    c(PlayerControlId.Fullscreen),
                    c(PlayerControlId.Back),
                ),
                bottomBar = listOf(
                    c(PlayerControlId.Star),
                    c(PlayerControlId.OpenFull),
                ),
            ),
            clipsPortrait = PlayerFormFactorLayout(
                center = MinimalCenter,
                topBar = listOf(
                    c(PlayerControlId.Title),
                    sp,
                    c(PlayerControlId.Volume),
                    c(PlayerControlId.Back),
                ),
                bottomBar = listOf(
                    c(PlayerControlId.Star),
                    c(PlayerControlId.OpenFull),
                ),
            ),
            showCenterControlsOnTouch = true,
            seekButtonSeconds = DEFAULT_SEEK_SECONDS,
            doubleTapSeekSeconds = DEFAULT_DOUBLE_TAP_SEEK_SECONDS,
            syncDoubleTapWithSeekButton = true,
            listen = ListenLayoutConfig.Default,
            gestures = PlayerGestureConfig.Default,
        ).sanitize()

        val Podcast = PlayerLayoutPrefs(
            version = PLAYER_LAYOUT_PREFS_VERSION,
            videoLandscape = PlayerFormFactorLayout(
                center = PodcastCenter,
                topBar = listOf(
                    c(PlayerControlId.Back),
                    c(PlayerControlId.Title),
                    sp,
                    c(PlayerControlId.SystemStatus, showBackground = false),
                    c(PlayerControlId.Episodes),
                    c(PlayerControlId.Listen),
                    c(PlayerControlId.Settings),
                ),
                bottomBar = listOf(
                    c(PlayerControlId.PlayPause),
                    c(PlayerControlId.SeekBackward),
                    c(PlayerControlId.SeekForward),
                    c(PlayerControlId.Previous),
                    c(PlayerControlId.Next),
                    c(PlayerControlId.TimeLabel),
                    c(PlayerControlId.Volume),
                    sp,
                    c(PlayerControlId.Speed),
                    c(PlayerControlId.Fullscreen),
                ),
            ),
            videoPortrait = PlayerFormFactorLayout(
                center = PodcastCenter,
                topBar = listOf(
                    c(PlayerControlId.Back),
                    c(PlayerControlId.Title),
                    sp,
                    c(PlayerControlId.Episodes),
                    c(PlayerControlId.Listen),
                    c(PlayerControlId.Settings),
                ),
                bottomBar = listOf(
                    c(PlayerControlId.PlayPause),
                    c(PlayerControlId.SeekBackward),
                    c(PlayerControlId.SeekForward),
                    c(PlayerControlId.TimeLabel),
                    sp,
                    c(PlayerControlId.Speed),
                    c(PlayerControlId.Fullscreen),
                ),
            ),
            clipsLandscape = DefaultClipsLandscape.copy(center = PodcastCenter),
            clipsPortrait = DefaultClipsPortrait,
            showCenterControlsOnTouch = true,
            seekButtonSeconds = 15,
            doubleTapSeekSeconds = 15,
            syncDoubleTapWithSeekButton = true,
            listen = ListenLayoutConfig(
                coverStyle = ListenCoverStyle.VinylDisc,
                outerLeft = CenterSlotConfig(CenterSlotAction.Previous, CenterSlotScale.Small),
                innerLeft = CenterSlotConfig(CenterSlotAction.SeekBackward(15), CenterSlotScale.Medium),
                innerRight = CenterSlotConfig(CenterSlotAction.SeekForward(15), CenterSlotScale.Medium),
                outerRight = CenterSlotConfig(CenterSlotAction.Next, CenterSlotScale.Small),
                visibleChips = ListenQuickChip.entries,
            ),
            gestures = PlayerGestureConfig.Default,
        ).sanitize()

        fun preset(preset: PlayerLayoutPreset): PlayerLayoutPrefs = when (preset) {
            PlayerLayoutPreset.Default -> Default
            PlayerLayoutPreset.Minimal -> Minimal
            PlayerLayoutPreset.Podcast -> Podcast
        }
    }
}

private val PlayerLayoutJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun encodePlayerLayoutPrefs(prefs: PlayerLayoutPrefs): String =
    PlayerLayoutJson.encodeToString(PlayerLayoutPrefs.serializer(), prefs.sanitize())

fun decodePlayerLayoutPrefs(raw: String?): PlayerLayoutPrefs {
    if (raw.isNullOrBlank()) return PlayerLayoutPrefs.Default
    return runCatching {
        PlayerLayoutJson.decodeFromString(PlayerLayoutPrefs.serializer(), raw).sanitize()
    }.getOrElse { PlayerLayoutPrefs.Default }
}
