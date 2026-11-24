@file:JvmMultifileClass
@file:JvmName("PlayerLayoutPrefsKt")

package dev.piko.shared.media.player

import kotlin.jvm.JvmMultifileClass
import kotlin.jvm.JvmName

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

fun supportsControlBackground(id: PlayerControlId, mode: PlayerLayoutMode): Boolean = when (id) {
    PlayerControlId.TimeLabel, PlayerControlId.PlayPause -> false
    PlayerControlId.Title -> mode.isClips
    else -> true
}

internal fun hasAnyControlBackgroundRule(
    layout: PlayerFormFactorLayout,
    mode: PlayerLayoutMode,
): Boolean {
    val barHasBg = (layout.topBar + layout.bottomBar).any { item ->
        item is PlayerPanelItem.Control &&
            supportsControlBackground(item.id, mode) &&
            item.showBackground
    }
    if (barHasBg) return true
    if (!mode.isVideo) return false
    return listOf(
        layout.center.outerLeft,
        layout.center.innerLeft,
        layout.center.innerRight,
        layout.center.outerRight,
    ).any { it.action != CenterSlotAction.None && it.showBackground }
}

internal fun withAllControlBackgroundsRule(
    layout: PlayerFormFactorLayout,
    showBackground: Boolean,
    mode: PlayerLayoutMode,
): PlayerFormFactorLayout {
    fun updateItems(items: List<PlayerPanelItem>): List<PlayerPanelItem> = items.map { item ->
        if (item is PlayerPanelItem.Control && supportsControlBackground(item.id, mode)) {
            item.copy(showBackground = showBackground)
        } else {
            item
        }
    }
    return layout.copy(
        center = if (mode.isVideo) layout.center.withAllSlotBackgrounds(showBackground) else layout.center,
        topBar = updateItems(layout.topBar),
        bottomBar = updateItems(layout.bottomBar),
    ).sanitize(mode)
}

internal fun withSymmetricSideSlotsRule(
    layout: PlayerFormFactorLayout,
    symmetric: Boolean,
    mode: PlayerLayoutMode,
): PlayerFormFactorLayout {
    val next = layout.copy(center = layout.center.copy(symmetricSideSlots = symmetric))
    if (!symmetric) return next.sanitize(mode)
    return next
        .withSymmetricCenterSlot(CenterSlotPosition.InnerLeft, mode)
        .withSymmetricCenterSlot(CenterSlotPosition.OuterLeft, mode)
}

internal fun withCenterSlotRule(
    layout: PlayerFormFactorLayout,
    position: CenterSlotPosition,
    config: CenterSlotConfig,
    mode: PlayerLayoutMode,
    applySymmetry: Boolean,
): PlayerFormFactorLayout {
    if (position == CenterSlotPosition.Center) return layout
    val sanitizedSlot = config.sanitized()
    val controlId = sanitizedSlot.action.toControlIdOrNull()
    val clearedCenter = if (controlId != null) layout.center.removeControl(controlId) else layout.center
    val updated = layout.copy(center = clearedCenter.withSlot(position, sanitizedSlot)).sanitize(mode)
    return if (applySymmetry) updated.withSymmetricCenterSlot(position, mode) else updated
}

internal fun withSymmetricCenterSlotRule(
    layout: PlayerFormFactorLayout,
    position: CenterSlotPosition,
    mode: PlayerLayoutMode,
): PlayerFormFactorLayout {
    if (position == CenterSlotPosition.Center) return layout
    val source = layout.center.slot(position)
    val opposite = position.symmetricOpposite()
    val targetConfig = CenterSlotConfig(
        action = source.action.symmetricOpposite(),
        scale = source.scale,
        showBackground = source.showBackground,
    )
    return layout.withCenterSlot(opposite, targetConfig, mode, applySymmetry = false)
}

internal fun withBarItemBackgroundRule(
    layout: PlayerFormFactorLayout,
    bar: PlayerBarSlot,
    index: Int,
    showBackground: Boolean,
    mode: PlayerLayoutMode,
): PlayerFormFactorLayout {
    val list = layout.itemsFor(bar)
    val item = list.getOrNull(index) as? PlayerPanelItem.Control ?: return layout
    val mutable = list.toMutableList()
    mutable[index] = item.copy(showBackground = showBackground)
    return when (bar) {
        PlayerBarSlot.Top -> layout.copy(topBar = mutable)
        PlayerBarSlot.Bottom -> layout.copy(bottomBar = mutable)
    }.sanitize(mode)
}

internal fun isItemAllowedInBarRule(
    item: PlayerPanelItem,
    bar: PlayerBarSlot,
    mode: PlayerLayoutMode,
): Boolean = when (item) {
    PlayerPanelItem.Spacer -> allowsSpacerIn(mode, bar)
    is PlayerPanelItem.Control -> item.id in allowedControlsFor(mode, bar)
}

internal fun addToBarRule(
    layout: PlayerFormFactorLayout,
    bar: PlayerBarSlot,
    item: PlayerPanelItem,
    mode: PlayerLayoutMode,
): PlayerFormFactorLayout {
    if (!isItemAllowedInBarRule(item, bar, mode)) return layout
    val base = if (item is PlayerPanelItem.Control) layout.removeFromBars(item.id) else layout
    val updated = when (bar) {
        PlayerBarSlot.Top -> base.copy(topBar = base.topBar + item)
        PlayerBarSlot.Bottom -> base.copy(bottomBar = base.bottomBar + item)
    }
    return updated.sanitize(mode)
}

internal fun insertIntoBarRule(
    layout: PlayerFormFactorLayout,
    bar: PlayerBarSlot,
    index: Int,
    item: PlayerPanelItem,
    mode: PlayerLayoutMode,
): PlayerFormFactorLayout {
    if (!isItemAllowedInBarRule(item, bar, mode)) return layout
    if (item is PlayerPanelItem.Control && mode.isVideo && item.id == PlayerControlId.Back) return layout
    val existingIndexInTarget = if (item is PlayerPanelItem.Control) {
        layout.itemsFor(bar).indexOfFirst { it is PlayerPanelItem.Control && it.id == item.id }
    } else {
        -1
    }
    val base = if (item is PlayerPanelItem.Control) layout.removeFromBars(item.id) else layout
    val targetList = base.itemsFor(bar).toMutableList()
    val adjusted = if (existingIndexInTarget in 0 until index) index - 1 else index
    val minIndex = minInsertIndexFor(mode, bar, targetList)
    val clampedIndex = adjusted.coerceIn(minIndex, targetList.size)
    targetList.add(clampedIndex, item)
    val updated = when (bar) {
        PlayerBarSlot.Top -> base.copy(topBar = targetList)
        PlayerBarSlot.Bottom -> base.copy(bottomBar = targetList)
    }
    return updated.sanitize(mode)
}

internal fun moveBarItemToBarAtRule(
    layout: PlayerFormFactorLayout,
    fromBar: PlayerBarSlot,
    fromIndex: Int,
    toBar: PlayerBarSlot,
    toIndex: Int,
    mode: PlayerLayoutMode,
): PlayerFormFactorLayout {
    val sourceList = layout.itemsFor(fromBar)
    val item = sourceList.getOrNull(fromIndex) ?: return layout
    if (mode.isVideo && item is PlayerPanelItem.Control && item.id == PlayerControlId.Back) return layout
    if (!isItemAllowedInBarRule(item, toBar, mode)) return layout
    if (fromBar == toBar) {
        val mutable = sourceList.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        val adjusted = if (fromIndex < toIndex) toIndex - 1 else toIndex
        val minIndex = minInsertIndexFor(mode, toBar, mutable)
        val clamped = adjusted.coerceIn(minIndex, mutable.size)
        mutable.add(clamped, moved)
        return when (toBar) {
            PlayerBarSlot.Top -> layout.copy(topBar = mutable)
            PlayerBarSlot.Bottom -> layout.copy(bottomBar = mutable)
        }.sanitize(mode)
    }
    val removedSource = sourceList.toMutableList().apply { removeAt(fromIndex) }
    val targetList = layout.itemsFor(toBar).toMutableList()
    val minIndex = minInsertIndexFor(mode, toBar, targetList)
    val clamped = toIndex.coerceIn(minIndex, targetList.size)
    targetList.add(clamped, item)
    val updated = when (fromBar) {
        PlayerBarSlot.Top -> layout.copy(topBar = removedSource, bottomBar = targetList)
        PlayerBarSlot.Bottom -> layout.copy(bottomBar = removedSource, topBar = targetList)
    }
    return updated.sanitize(mode)
}

private fun minInsertIndexFor(
    mode: PlayerLayoutMode,
    bar: PlayerBarSlot,
    list: List<PlayerPanelItem>,
): Int = if (
    mode.isVideo &&
    bar == PlayerBarSlot.Top &&
    list.firstOrNull().let { it is PlayerPanelItem.Control && it.id == PlayerControlId.Back }
) {
    1
} else {
    0
}

internal fun moveBarItemRule(
    layout: PlayerFormFactorLayout,
    bar: PlayerBarSlot,
    fromIndex: Int,
    toIndex: Int,
    mode: PlayerLayoutMode,
): PlayerFormFactorLayout {
    val list = layout.itemsFor(bar)
    if (fromIndex !in list.indices || toIndex !in list.indices || fromIndex == toIndex) return layout
    if (mode.isVideo && bar == PlayerBarSlot.Top) {
        val moving = list[fromIndex]
        val target = list[toIndex]
        if ((moving is PlayerPanelItem.Control && moving.id == PlayerControlId.Back) ||
            (target is PlayerPanelItem.Control && target.id == PlayerControlId.Back)
        ) {
            return layout
        }
    }
    val mutable = list.toMutableList()
    val moved = mutable.removeAt(fromIndex)
    mutable.add(toIndex, moved)
    return when (bar) {
        PlayerBarSlot.Top -> layout.copy(topBar = mutable)
        PlayerBarSlot.Bottom -> layout.copy(bottomBar = mutable)
    }.sanitize(mode)
}

internal fun canMoveToOtherBarRule(
    layout: PlayerFormFactorLayout,
    fromBar: PlayerBarSlot,
    index: Int,
    mode: PlayerLayoutMode,
): Boolean {
    val item = layout.itemsFor(fromBar).getOrNull(index) ?: return false
    if (item is PlayerPanelItem.Control && item.id == PlayerControlId.Back) return false
    return isItemAllowedInBarRule(item, fromBar.other(), mode)
}

internal fun moveBarItemToOtherBarRule(
    layout: PlayerFormFactorLayout,
    fromBar: PlayerBarSlot,
    index: Int,
    mode: PlayerLayoutMode,
): PlayerFormFactorLayout {
    if (!canMoveToOtherBarRule(layout, fromBar, index, mode)) return layout
    val list = layout.itemsFor(fromBar)
    val item = list[index]
    val removed = list.toMutableList().apply { removeAt(index) }
    val updated = when (fromBar) {
        PlayerBarSlot.Top -> layout.copy(topBar = removed, bottomBar = layout.bottomBar + item)
        PlayerBarSlot.Bottom -> layout.copy(bottomBar = removed, topBar = layout.topBar + item)
    }
    return updated.sanitize(mode)
}

internal fun canRemoveBarItemRule(
    layout: PlayerFormFactorLayout,
    bar: PlayerBarSlot,
    index: Int,
    mode: PlayerLayoutMode,
): Boolean {
    val list = layout.itemsFor(bar)
    val item = list.getOrNull(index) ?: return false
    if (item is PlayerPanelItem.Spacer) return true
    val id = (item as PlayerPanelItem.Control).id
    return when {
        id == PlayerControlId.Back -> false
        mode.isVideo && id == PlayerControlId.PlayPause -> false
        else -> true
    }
}

internal fun removeBarItemRule(
    layout: PlayerFormFactorLayout,
    bar: PlayerBarSlot,
    index: Int,
    mode: PlayerLayoutMode,
): PlayerFormFactorLayout {
    if (!canRemoveBarItemRule(layout, bar, index, mode)) return layout
    val list = layout.itemsFor(bar)
    val updated = list.toMutableList().apply { removeAt(index) }
    return when (bar) {
        PlayerBarSlot.Top -> layout.copy(topBar = updated)
        PlayerBarSlot.Bottom -> layout.copy(bottomBar = updated)
    }.sanitize(mode)
}

/**
 * 强校验与修复护栏：
 * 1. 秒数限制在 [MIN_SEEK_SECONDS]..[MAX_SEEK_SECONDS]；
 * 2. 过滤不属于当前模式（视频 vs 信息流）的非法控件；
 * 3. 中央簇 4 个侧槽位内去重，顶栏与底栏之间去重（严格遵守单一入口 DRY）；
 * 4. 基本可用性：视频模式顶栏保留最左端返回键、栏位中保留播放/暂停；信息流模式顶栏保留返回/关闭键。
 */
internal fun sanitizeFormFactorLayout(
    layout: PlayerFormFactorLayout,
    mode: PlayerLayoutMode,
): PlayerFormFactorLayout {
    val allowed = allowedControlsFor(mode)
    val seenCenter = mutableSetOf<PlayerControlId>()
    fun sanitizeSlot(slot: CenterSlotConfig): CenterSlotConfig {
        val clamped = slot.sanitized()
        val id = clamped.action.toControlIdOrNull() ?: return clamped
        return if (id in allowed && seenCenter.add(id)) clamped else clamped.copy(action = CenterSlotAction.None)
    }
    val cleanCenter = CenterClusterConfig(
        showCenterPlay = layout.center.showCenterPlay,
        symmetricSideSlots = layout.center.symmetricSideSlots,
        innerLeft = sanitizeSlot(layout.center.innerLeft),
        innerRight = sanitizeSlot(layout.center.innerRight),
        outerLeft = sanitizeSlot(layout.center.outerLeft),
        outerRight = sanitizeSlot(layout.center.outerRight),
    )

    val allowedTop = allowedControlsFor(mode, PlayerBarSlot.Top)
    val allowedBottom = allowedControlsFor(mode, PlayerBarSlot.Bottom)
    val seenBars = mutableSetOf<PlayerControlId>()
    val cleanTop = mutableListOf<PlayerPanelItem>()
    if (mode.isVideo) {
        val existingBack = layout.topBar.firstOrNull {
            it is PlayerPanelItem.Control && it.id == PlayerControlId.Back
        } as? PlayerPanelItem.Control
        seenBars += PlayerControlId.Back
        cleanTop += existingBack ?: PlayerPanelItem.Control(PlayerControlId.Back)
    }
    for (item in layout.topBar) {
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
    for (item in layout.bottomBar) {
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
        showIndicatorBackground = layout.showIndicatorBackground,
        indicatorPosition = layout.indicatorPosition,
    )
}

internal fun withListenSymmetricSideSlotsRule(
    config: ListenLayoutConfig,
    symmetric: Boolean,
): ListenLayoutConfig {
    val next = config.copy(symmetricSideSlots = symmetric)
    if (!symmetric) return next.sanitize()
    return next
        .withSymmetricSlot(CenterSlotPosition.InnerLeft)
        .withSymmetricSlot(CenterSlotPosition.OuterLeft)
}

internal fun withListenSlotRule(
    config: ListenLayoutConfig,
    position: CenterSlotPosition,
    slotConfig: CenterSlotConfig,
    applySymmetry: Boolean,
): ListenLayoutConfig {
    if (position == CenterSlotPosition.Center) return config
    val sanitizedSlot = slotConfig.sanitized()
    val id = sanitizedSlot.action.toControlIdOrNull()
    fun clearIfSame(existing: CenterSlotConfig): CenterSlotConfig =
        if (id != null && existing.action.toControlIdOrNull() == id) {
            existing.copy(action = CenterSlotAction.None)
        } else {
            existing
        }
    val cleared = config.copy(
        outerLeft = clearIfSame(config.outerLeft),
        innerLeft = clearIfSame(config.innerLeft),
        innerRight = clearIfSame(config.innerRight),
        outerRight = clearIfSame(config.outerRight),
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

internal fun withListenSymmetricSlotRule(
    config: ListenLayoutConfig,
    position: CenterSlotPosition,
): ListenLayoutConfig {
    if (position == CenterSlotPosition.Center) return config
    val source = config.slot(position)
    val opposite = position.symmetricOpposite()
    val targetConfig = CenterSlotConfig(
        action = source.action.symmetricOpposite(),
        scale = source.scale,
        showBackground = source.showBackground,
    )
    return config.withSlot(opposite, targetConfig, applySymmetry = false)
}

internal fun toggleListenChipRule(
    config: ListenLayoutConfig,
    chip: ListenQuickChip,
    visible: Boolean,
): ListenLayoutConfig {
    val updated = if (visible) {
        if (chip in config.visibleChips) config.visibleChips else config.visibleChips + chip
    } else {
        config.visibleChips.filterNot { it == chip }
    }
    return config.copy(visibleChips = updated).sanitize()
}

internal fun sanitizeListenLayoutConfig(config: ListenLayoutConfig): ListenLayoutConfig {
    val seen = mutableSetOf<PlayerControlId>()
    fun sanitizeSlot(slot: CenterSlotConfig): CenterSlotConfig {
        val clamped = slot.sanitized()
        val id = clamped.action.toControlIdOrNull() ?: return clamped
        return if (seen.add(id)) clamped else clamped.copy(action = CenterSlotAction.None)
    }
    val cleanInnerLeft = sanitizeSlot(config.innerLeft)
    val cleanInnerRight = sanitizeSlot(config.innerRight)
    val cleanOuterLeft = sanitizeSlot(config.outerLeft)
    val cleanOuterRight = sanitizeSlot(config.outerRight)

    return config.copy(
        outerLeft = cleanOuterLeft,
        innerLeft = cleanInnerLeft,
        innerRight = cleanInnerRight,
        outerRight = cleanOuterRight,
        visibleChips = config.visibleChips.distinct(),
    )
}

internal fun createDefaultListenLayoutConfig(): ListenLayoutConfig = ListenLayoutConfig(
    coverStyle = ListenCoverStyle.VinylDisc,
    outerLeft = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
    innerLeft = CenterSlotConfig(CenterSlotAction.Previous, CenterSlotScale.Medium),
    innerRight = CenterSlotConfig(CenterSlotAction.Next, CenterSlotScale.Medium),
    outerRight = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
    visibleChips = ListenQuickChip.entries,
)

internal fun sanitizePlayerGestureConfig(config: PlayerGestureConfig): PlayerGestureConfig = config.copy(
    verticalZoneFraction = if (config.verticalZoneFraction.isFinite()) {
        config.verticalZoneFraction.coerceIn(MIN_VERTICAL_ZONE_FRACTION, MAX_VERTICAL_ZONE_FRACTION)
    } else {
        DEFAULT_VERTICAL_ZONE_FRACTION
    },
    doubleTapZoneFraction = if (config.doubleTapZoneFraction.isFinite()) {
        config.doubleTapZoneFraction.coerceIn(MIN_DOUBLE_TAP_ZONE_FRACTION, MAX_DOUBLE_TAP_ZONE_FRACTION)
    } else {
        DEFAULT_DOUBLE_TAP_ZONE_FRACTION
    },
)

internal fun updateModeLayoutRule(
    prefs: PlayerLayoutPrefs,
    mode: PlayerLayoutMode,
    transform: (PlayerFormFactorLayout) -> PlayerFormFactorLayout,
): PlayerLayoutPrefs {
    val updated = when (mode) {
        PlayerLayoutMode.VideoLandscape -> prefs.copy(videoLandscape = transform(prefs.videoLandscape))
        PlayerLayoutMode.VideoPortrait -> prefs.copy(videoPortrait = transform(prefs.videoPortrait))
        PlayerLayoutMode.ClipsLandscape -> prefs.copy(clipsLandscape = transform(prefs.clipsLandscape))
        PlayerLayoutMode.ClipsPortrait -> prefs.copy(clipsPortrait = transform(prefs.clipsPortrait))
    }
    return updated.sanitize()
}

internal fun resetModeLayoutRule(
    prefs: PlayerLayoutPrefs,
    mode: PlayerLayoutMode,
): PlayerLayoutPrefs {
    val reset = prefs.updateLayout(mode) { PlayerLayoutPrefs.defaultLayoutFor(mode) }
    return if (mode == PlayerLayoutMode.VideoPortrait) {
        reset.copy(showPortraitFullscreenPrompt = true)
    } else {
        reset
    }
}

internal fun sanitizePlayerLayoutPrefs(prefs: PlayerLayoutPrefs): PlayerLayoutPrefs {
    val clampedSeek = prefs.seekButtonSeconds.coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS)
    val clampedDoubleTap = prefs.doubleTapSeekSeconds.coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS)
    return prefs.copy(
        version = PLAYER_LAYOUT_PREFS_VERSION,
        videoLandscape = prefs.videoLandscape.sanitize(PlayerLayoutMode.VideoLandscape),
        videoPortrait = prefs.videoPortrait.sanitize(PlayerLayoutMode.VideoPortrait),
        clipsLandscape = prefs.clipsLandscape.sanitize(PlayerLayoutMode.ClipsLandscape),
        clipsPortrait = prefs.clipsPortrait.sanitize(PlayerLayoutMode.ClipsPortrait),
        seekButtonSeconds = clampedSeek,
        doubleTapSeekSeconds = clampedDoubleTap,
        listen = prefs.listen.sanitize(),
        gestures = prefs.gestures.sanitize(),
    )
}

private fun controlItem(id: PlayerControlId, showBackground: Boolean = true): PlayerPanelItem =
    PlayerPanelItem.Control(id, showBackground)

private val spacerItem: PlayerPanelItem = PlayerPanelItem.Spacer

internal fun createDefaultCenter(): CenterClusterConfig = CenterClusterConfig(
    showCenterPlay = true,
    outerLeft = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
    innerLeft = CenterSlotConfig(CenterSlotAction.Previous, CenterSlotScale.Medium),
    innerRight = CenterSlotConfig(CenterSlotAction.Next, CenterSlotScale.Medium),
    outerRight = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
)

internal fun createMinimalCenter(): CenterClusterConfig = CenterClusterConfig(
    showCenterPlay = true,
    outerLeft = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
    innerLeft = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Medium),
    innerRight = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Medium),
    outerRight = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
)

internal fun createPodcastCenter(): CenterClusterConfig = CenterClusterConfig(
    showCenterPlay = true,
    outerLeft = CenterSlotConfig(CenterSlotAction.Previous, CenterSlotScale.Small),
    innerLeft = CenterSlotConfig(CenterSlotAction.SeekBackward(15), CenterSlotScale.Medium),
    innerRight = CenterSlotConfig(CenterSlotAction.SeekForward(15), CenterSlotScale.Medium),
    outerRight = CenterSlotConfig(CenterSlotAction.Next, CenterSlotScale.Small),
)

internal fun createDefaultVideoLandscape(defaultCenter: CenterClusterConfig): PlayerFormFactorLayout =
    PlayerFormFactorLayout(
        center = defaultCenter,
        topBar = listOf(
            controlItem(PlayerControlId.Back),
            controlItem(PlayerControlId.Title),
            spacerItem,
            controlItem(PlayerControlId.SystemStatus, showBackground = false),
            controlItem(PlayerControlId.PinOnTop),
            controlItem(PlayerControlId.Episodes),
            controlItem(PlayerControlId.Tracks),
            controlItem(PlayerControlId.Settings),
        ),
        bottomBar = listOf(
            controlItem(PlayerControlId.PlayPause),
            controlItem(PlayerControlId.TimeLabel),
            controlItem(PlayerControlId.Volume),
            spacerItem,
            controlItem(PlayerControlId.Speed),
            controlItem(PlayerControlId.Quality),
            controlItem(PlayerControlId.Rotate),
            controlItem(PlayerControlId.Fullscreen),
        ),
    )

internal fun createDefaultVideoPortrait(defaultCenter: CenterClusterConfig): PlayerFormFactorLayout =
    PlayerFormFactorLayout(
        center = defaultCenter,
        topBar = listOf(
            controlItem(PlayerControlId.Back),
            controlItem(PlayerControlId.Title),
            spacerItem,
            controlItem(PlayerControlId.Episodes),
            controlItem(PlayerControlId.Tracks),
            controlItem(PlayerControlId.Settings),
        ),
        bottomBar = listOf(
            controlItem(PlayerControlId.PlayPause),
            controlItem(PlayerControlId.TimeLabel),
            spacerItem,
            controlItem(PlayerControlId.Speed),
            controlItem(PlayerControlId.Quality),
            controlItem(PlayerControlId.Fullscreen),
        ),
    )

internal fun createDefaultClipsLandscape(defaultCenter: CenterClusterConfig): PlayerFormFactorLayout =
    PlayerFormFactorLayout(
        center = defaultCenter,
        topBar = listOf(
            controlItem(PlayerControlId.Title),
            spacerItem,
            controlItem(PlayerControlId.SystemStatus, showBackground = false),
            controlItem(PlayerControlId.HideChrome),
            controlItem(PlayerControlId.AutoAdvance),
            controlItem(PlayerControlId.Volume),
            controlItem(PlayerControlId.PinOnTop),
            controlItem(PlayerControlId.Fullscreen),
            controlItem(PlayerControlId.Back),
        ),
        bottomBar = listOf(
            controlItem(PlayerControlId.Star),
            controlItem(PlayerControlId.OpenFull),
            controlItem(PlayerControlId.OpenFolder),
        ),
    )

internal fun createDefaultClipsPortrait(minimalCenter: CenterClusterConfig): PlayerFormFactorLayout =
    PlayerFormFactorLayout(
        center = minimalCenter,
        topBar = listOf(
            controlItem(PlayerControlId.Title),
            spacerItem,
            controlItem(PlayerControlId.Rotate),
            controlItem(PlayerControlId.HideChrome),
            controlItem(PlayerControlId.AutoAdvance),
            controlItem(PlayerControlId.Volume),
            controlItem(PlayerControlId.Back),
        ),
        bottomBar = listOf(
            controlItem(PlayerControlId.Star),
            controlItem(PlayerControlId.OpenFull),
            controlItem(PlayerControlId.OpenFolder),
        ),
    )

internal fun createDefaultPlayerLayoutPrefs(
    videoLandscape: PlayerFormFactorLayout,
    videoPortrait: PlayerFormFactorLayout,
    clipsLandscape: PlayerFormFactorLayout,
    clipsPortrait: PlayerFormFactorLayout,
): PlayerLayoutPrefs = PlayerLayoutPrefs(
    version = PLAYER_LAYOUT_PREFS_VERSION,
    videoLandscape = videoLandscape,
    videoPortrait = videoPortrait,
    clipsLandscape = clipsLandscape,
    clipsPortrait = clipsPortrait,
    showCenterControlsOnTouch = true,
    seekButtonSeconds = DEFAULT_SEEK_SECONDS,
    doubleTapSeekSeconds = DEFAULT_DOUBLE_TAP_SEEK_SECONDS,
    syncDoubleTapWithSeekButton = true,
    listen = ListenLayoutConfig.Default,
    gestures = PlayerGestureConfig.Default,
).sanitize()

internal fun createMinimalPlayerLayoutPrefs(minimalCenter: CenterClusterConfig): PlayerLayoutPrefs =
    PlayerLayoutPrefs(
        version = PLAYER_LAYOUT_PREFS_VERSION,
        videoLandscape = PlayerFormFactorLayout(
            center = minimalCenter,
            topBar = listOf(
                controlItem(PlayerControlId.Back),
                controlItem(PlayerControlId.Title),
                spacerItem,
                controlItem(PlayerControlId.Settings),
            ),
            bottomBar = listOf(
                controlItem(PlayerControlId.PlayPause),
                controlItem(PlayerControlId.TimeLabel),
                controlItem(PlayerControlId.Volume),
                spacerItem,
                controlItem(PlayerControlId.Fullscreen),
            ),
        ),
        videoPortrait = PlayerFormFactorLayout(
            center = minimalCenter,
            topBar = listOf(
                controlItem(PlayerControlId.Back),
                controlItem(PlayerControlId.Title),
                spacerItem,
                controlItem(PlayerControlId.Settings),
            ),
            bottomBar = listOf(
                controlItem(PlayerControlId.PlayPause),
                controlItem(PlayerControlId.TimeLabel),
                spacerItem,
                controlItem(PlayerControlId.Fullscreen),
            ),
        ),
        clipsLandscape = PlayerFormFactorLayout(
            center = minimalCenter,
            topBar = listOf(
                controlItem(PlayerControlId.Title),
                spacerItem,
                controlItem(PlayerControlId.Volume),
                controlItem(PlayerControlId.Fullscreen),
                controlItem(PlayerControlId.Back),
            ),
            bottomBar = listOf(
                controlItem(PlayerControlId.Star),
                controlItem(PlayerControlId.OpenFull),
            ),
        ),
        clipsPortrait = PlayerFormFactorLayout(
            center = minimalCenter,
            topBar = listOf(
                controlItem(PlayerControlId.Title),
                spacerItem,
                controlItem(PlayerControlId.Volume),
                controlItem(PlayerControlId.Back),
            ),
            bottomBar = listOf(
                controlItem(PlayerControlId.Star),
                controlItem(PlayerControlId.OpenFull),
            ),
        ),
        showCenterControlsOnTouch = true,
        seekButtonSeconds = DEFAULT_SEEK_SECONDS,
        doubleTapSeekSeconds = DEFAULT_DOUBLE_TAP_SEEK_SECONDS,
        syncDoubleTapWithSeekButton = true,
        listen = ListenLayoutConfig.Default,
        gestures = PlayerGestureConfig.Default,
    ).sanitize()

internal fun createPodcastPlayerLayoutPrefs(
    podcastCenter: CenterClusterConfig,
    defaultClipsLandscape: PlayerFormFactorLayout,
    defaultClipsPortrait: PlayerFormFactorLayout,
): PlayerLayoutPrefs = PlayerLayoutPrefs(
    version = PLAYER_LAYOUT_PREFS_VERSION,
    videoLandscape = PlayerFormFactorLayout(
        center = podcastCenter,
        topBar = listOf(
            controlItem(PlayerControlId.Back),
            controlItem(PlayerControlId.Title),
            spacerItem,
            controlItem(PlayerControlId.SystemStatus, showBackground = false),
            controlItem(PlayerControlId.Episodes),
            controlItem(PlayerControlId.Listen),
            controlItem(PlayerControlId.Settings),
        ),
        bottomBar = listOf(
            controlItem(PlayerControlId.PlayPause),
            controlItem(PlayerControlId.SeekBackward),
            controlItem(PlayerControlId.SeekForward),
            controlItem(PlayerControlId.Previous),
            controlItem(PlayerControlId.Next),
            controlItem(PlayerControlId.TimeLabel),
            controlItem(PlayerControlId.Volume),
            spacerItem,
            controlItem(PlayerControlId.Speed),
            controlItem(PlayerControlId.Fullscreen),
        ),
    ),
    videoPortrait = PlayerFormFactorLayout(
        center = podcastCenter,
        topBar = listOf(
            controlItem(PlayerControlId.Back),
            controlItem(PlayerControlId.Title),
            spacerItem,
            controlItem(PlayerControlId.Episodes),
            controlItem(PlayerControlId.Listen),
            controlItem(PlayerControlId.Settings),
        ),
        bottomBar = listOf(
            controlItem(PlayerControlId.PlayPause),
            controlItem(PlayerControlId.SeekBackward),
            controlItem(PlayerControlId.SeekForward),
            controlItem(PlayerControlId.TimeLabel),
            spacerItem,
            controlItem(PlayerControlId.Speed),
            controlItem(PlayerControlId.Fullscreen),
        ),
    ),
    clipsLandscape = defaultClipsLandscape.copy(center = podcastCenter),
    clipsPortrait = defaultClipsPortrait,
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
