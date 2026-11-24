package dev.piko.shared.media.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerLayoutPrefsTest {

    @Test
    fun `default four-state layouts are valid and strictly DRY across bars`() {
        val prefs = PlayerLayoutPrefs.Default
        for (mode in PlayerLayoutMode.entries) {
            val layout = prefs.layoutFor(mode)
            val allBarIds = (layout.topBar + layout.bottomBar)
                .filterIsInstance<PlayerPanelItem.Control>()
                .map { it.id }
            assertEquals(allBarIds.size, allBarIds.toSet().size, "Duplicate control in $mode")
            assertTrue(allBarIds.all { it in allowedControlsFor(mode) }, "Disallowed control in $mode")
            assertTrue(layout.hasInTopBar(PlayerControlId.Back), "Back missing in $mode")
            if (mode.isVideo) {
                assertTrue(layout.hasControl(PlayerControlId.PlayPause), "PlayPause missing in $mode")
            }
        }
    }

    @Test
    fun `four states update and reset independently`() {
        val base = PlayerLayoutPrefs.Default
        val updated = base.updateLayout(PlayerLayoutMode.ClipsPortrait) { layout ->
            layout.addToBar(PlayerBarSlot.Bottom, PlayerPanelItem.Control(PlayerControlId.PlayPause), PlayerLayoutMode.ClipsPortrait)
        }
        assertTrue(updated.clipsPortrait.hasInBottomBar(PlayerControlId.PlayPause))
        assertEquals(base.videoLandscape, updated.videoLandscape)
        assertEquals(base.videoPortrait, updated.videoPortrait)
        assertEquals(base.clipsLandscape, updated.clipsLandscape)

        val reset = updated.resetMode(PlayerLayoutMode.ClipsPortrait)
        assertEquals(base.clipsPortrait, reset.clipsPortrait)
    }

    @Test
    fun `clips layout enforces bar-specific supported controls and restricts spacer to top bar`() {
        val dirtyClips = PlayerFormFactorLayout(
            center = PlayerLayoutPrefs.MinimalCenter,
            topBar = listOf(
                PlayerPanelItem.Control(PlayerControlId.Title),
                PlayerPanelItem.Spacer,
                PlayerPanelItem.Control(PlayerControlId.Star), // rail-only, must be stripped from top
                PlayerPanelItem.Control(PlayerControlId.Speed), // unsupported in clips
                PlayerPanelItem.Control(PlayerControlId.Volume),
            ),
            bottomBar = listOf(
                PlayerPanelItem.Spacer, // not allowed in clips action rail
                PlayerPanelItem.Control(PlayerControlId.TimeLabel), // unsupported in clips
                PlayerPanelItem.Control(PlayerControlId.Vault), // unsupported in clips
                PlayerPanelItem.Control(PlayerControlId.Download), // unsupported in clips
                PlayerPanelItem.Control(PlayerControlId.Lock), // unsupported in clips
                PlayerPanelItem.Control(PlayerControlId.Volume), // top-only, must be stripped from rail
                PlayerPanelItem.Control(PlayerControlId.Star),
                PlayerPanelItem.Control(PlayerControlId.PlayPause),
            ),
        ).sanitize(PlayerLayoutMode.ClipsLandscape)

        assertTrue(dirtyClips.hasInTopBar(PlayerControlId.Title))
        assertTrue(dirtyClips.hasInTopBar(PlayerControlId.Volume))
        assertTrue(dirtyClips.hasInTopBar(PlayerControlId.Back))
        assertFalse(dirtyClips.hasInTopBar(PlayerControlId.Star))
        assertFalse(dirtyClips.hasControl(PlayerControlId.Speed))
        assertFalse(dirtyClips.hasControl(PlayerControlId.TimeLabel))
        assertFalse(dirtyClips.hasControl(PlayerControlId.Vault))
        assertFalse(dirtyClips.hasControl(PlayerControlId.Download))
        assertFalse(dirtyClips.hasControl(PlayerControlId.Lock))
        assertFalse(dirtyClips.bottomBar.any { it == PlayerPanelItem.Spacer })
        assertTrue(dirtyClips.topBar.any { it == PlayerPanelItem.Spacer })

        // PlayPause is supported in both Clips top bar and action rail, while Star cannot move to top bar
        val starIdx = dirtyClips.bottomBar.indexOfFirst { it is PlayerPanelItem.Control && it.id == PlayerControlId.Star }
        val playIdx = dirtyClips.bottomBar.indexOfFirst { it is PlayerPanelItem.Control && it.id == PlayerControlId.PlayPause }
        assertFalse(dirtyClips.canMoveToOtherBar(PlayerBarSlot.Bottom, starIdx, PlayerLayoutMode.ClipsLandscape))
        assertTrue(dirtyClips.canMoveToOtherBar(PlayerBarSlot.Bottom, playIdx, PlayerLayoutMode.ClipsLandscape))
        val movedPlay = dirtyClips.moveBarItemToOtherBar(PlayerBarSlot.Bottom, playIdx, PlayerLayoutMode.ClipsLandscape)
        assertTrue(movedPlay.hasInTopBar(PlayerControlId.PlayPause))
        assertFalse(movedPlay.hasInBottomBar(PlayerControlId.PlayPause))
    }

    @Test
    fun `sanitize removes disallowed controls, deduplicates slots, and preserves essential controls`() {
        val dirtyVideo = PlayerFormFactorLayout(
            center = CenterClusterConfig(
                showCenterPlay = true,
                outerLeft = CenterSlotConfig(CenterSlotAction.Previous),
                innerLeft = CenterSlotConfig(CenterSlotAction.Previous),
                innerRight = CenterSlotConfig(CenterSlotAction.SeekForward(9999)),
                outerRight = CenterSlotConfig(CenterSlotAction.SeekBackward(0)),
            ),
            topBar = listOf(
                PlayerPanelItem.Control(PlayerControlId.AutoAdvance), // Clips-only, must be stripped
                PlayerPanelItem.Control(PlayerControlId.Settings),
                PlayerPanelItem.Control(PlayerControlId.Settings), // duplicate
            ),
            bottomBar = listOf(
                PlayerPanelItem.Control(PlayerControlId.Settings), // duplicate across bars
            ),
        ).sanitize(PlayerLayoutMode.VideoLandscape)

        // Back pinned at topBar[0], PlayPause restored in bottomBar
        assertEquals(PlayerPanelItem.Control(PlayerControlId.Back), dirtyVideo.topBar.first())
        assertTrue(dirtyVideo.hasInBottomBar(PlayerControlId.PlayPause))
        assertFalse(dirtyVideo.hasControl(PlayerControlId.AutoAdvance))
        assertEquals(1, (dirtyVideo.topBar + dirtyVideo.bottomBar).count { it is PlayerPanelItem.Control && it.id == PlayerControlId.Settings })

        // Center deduplication & seek clamping
        assertEquals(CenterSlotAction.Previous, dirtyVideo.center.innerLeft.action)
        assertEquals(CenterSlotAction.None, dirtyVideo.center.outerLeft.action)
        assertEquals(CenterSlotAction.SeekForward(MAX_SEEK_SECONDS), dirtyVideo.center.innerRight.action)
        assertEquals(CenterSlotAction.SeekBackward(MIN_SEEK_SECONDS), dirtyVideo.center.outerRight.action)
    }

    @Test
    fun `json round-trip and corrupt fallback preserve preferences cleanly`() {
        val custom = PlayerLayoutPrefs.Podcast.updateGestures {
            it.copy(
                leftVerticalSwipe = VerticalSwipeAction.Volume,
                rightVerticalSwipe = VerticalSwipeAction.Brightness,
                swipeSeekSensitivity = SwipeSeekSensitivity.High,
                verticalZoneFraction = 0.35f,
            )
        }.updateListen {
            it.copy(coverStyle = ListenCoverStyle.StaticCover)
                .toggleChip(ListenQuickChip.SleepTimer, visible = false)
        }
        val encoded = encodePlayerLayoutPrefs(custom)
        val decoded = decodePlayerLayoutPrefs(encoded)
        assertEquals(custom, decoded)

        assertEquals(PlayerLayoutPrefs.Default, decodePlayerLayoutPrefs(null))
        assertEquals(PlayerLayoutPrefs.Default, decodePlayerLayoutPrefs(""))
        assertEquals(PlayerLayoutPrefs.Default, decodePlayerLayoutPrefs("{not-valid-json"))
    }

    @Test
    fun `isHiddenByCenter hides duplicated bar transport controls only when touch center is active`() {
        val layout = PlayerLayoutPrefs.DefaultVideoLandscape
        assertTrue(layout.isHiddenByCenter(PlayerControlId.PlayPause, centerActive = true))
        assertFalse(layout.isHiddenByCenter(PlayerControlId.PlayPause, centerActive = false))
        assertFalse(layout.isHiddenByCenter(PlayerControlId.Speed, centerActive = true))
    }

    @Test
    fun `symmetricSideSlots mirrors opposite slot by default and allows independent edits when disabled`() {
        val base = PlayerLayoutPrefs.Default.videoLandscape
        assertTrue(base.center.symmetricSideSlots)

        // 对称开启时设置内左为快退 15s，内右自动镜像为快进 15s
        val mirrored = base.withCenterSlot(
            CenterSlotPosition.InnerLeft,
            CenterSlotConfig(CenterSlotAction.SeekBackward(15)),
            PlayerLayoutMode.VideoLandscape,
        )
        assertEquals(CenterSlotAction.SeekBackward(15), mirrored.center.innerLeft.action)
        assertEquals(CenterSlotAction.SeekForward(15), mirrored.center.innerRight.action)

        // 关闭对称后可独立设置左侧而不改动右侧
        val independent = mirrored
            .withSymmetricSideSlots(false, PlayerLayoutMode.VideoLandscape)
            .withCenterSlot(
                CenterSlotPosition.OuterLeft,
                CenterSlotConfig(CenterSlotAction.Previous),
                PlayerLayoutMode.VideoLandscape,
            )
        assertFalse(independent.center.symmetricSideSlots)
        assertEquals(CenterSlotAction.Previous, independent.center.outerLeft.action)
        assertEquals(CenterSlotAction.None, independent.center.outerRight.action)
    }

    @Test
    fun `statusBar config toggles round-trip through JSON`() {
        val custom = PlayerLayoutPrefs.Default.updateStatusBar {
            it.copy(showClock = true, showNetwork = false, showBattery = true, showIcons = false)
        }
        val decoded = decodePlayerLayoutPrefs(encodePlayerLayoutPrefs(custom))
        assertEquals(custom.statusBar, decoded.statusBar)
        assertTrue(decoded.statusBar.showClock)
        assertFalse(decoded.statusBar.showNetwork)
        assertTrue(decoded.statusBar.showBattery)
        assertFalse(decoded.statusBar.showIcons)
        assertFalse(PlayerStatusBarConfig(false, false, false, false).hasAnyItem)
    }

    @Test
    fun `isItemAllowedInBar enforces per-bar allowlists across video and clips modes`() {
        val clips = PlayerLayoutPrefs.Default.clipsPortrait
        assertTrue(clips.isItemAllowedInBar(PlayerPanelItem.Spacer, PlayerBarSlot.Top, PlayerLayoutMode.ClipsPortrait))
        assertFalse(clips.isItemAllowedInBar(PlayerPanelItem.Spacer, PlayerBarSlot.Bottom, PlayerLayoutMode.ClipsPortrait))
        assertFalse(clips.isItemAllowedInBar(PlayerPanelItem.Control(PlayerControlId.Star), PlayerBarSlot.Top, PlayerLayoutMode.ClipsPortrait))
        assertTrue(clips.isItemAllowedInBar(PlayerPanelItem.Control(PlayerControlId.Star), PlayerBarSlot.Bottom, PlayerLayoutMode.ClipsPortrait))
        assertTrue(clips.isItemAllowedInBar(PlayerPanelItem.Control(PlayerControlId.Back), PlayerBarSlot.Top, PlayerLayoutMode.ClipsPortrait))
        assertFalse(clips.isItemAllowedInBar(PlayerPanelItem.Control(PlayerControlId.Back), PlayerBarSlot.Bottom, PlayerLayoutMode.ClipsPortrait))
        assertTrue(clips.isItemAllowedInBar(PlayerPanelItem.Control(PlayerControlId.PlayPause), PlayerBarSlot.Top, PlayerLayoutMode.ClipsPortrait))
        assertTrue(clips.isItemAllowedInBar(PlayerPanelItem.Control(PlayerControlId.PlayPause), PlayerBarSlot.Bottom, PlayerLayoutMode.ClipsPortrait))

        val video = PlayerLayoutPrefs.Default.videoLandscape
        assertTrue(video.isItemAllowedInBar(PlayerPanelItem.Spacer, PlayerBarSlot.Top, PlayerLayoutMode.VideoLandscape))
        assertTrue(video.isItemAllowedInBar(PlayerPanelItem.Spacer, PlayerBarSlot.Bottom, PlayerLayoutMode.VideoLandscape))
        assertFalse(video.isItemAllowedInBar(PlayerPanelItem.Control(PlayerControlId.Star), PlayerBarSlot.Bottom, PlayerLayoutMode.VideoLandscape))
    }

    @Test
    fun `insertIntoBar and moveBarItemToBarAt support drag-and-drop across video and clips modes`() {
        // Video: Back pinned at topBar[0], inserting at index 0 clamps to 1
        val videoBase = PlayerLayoutPrefs.Default.videoLandscape
        val insertedInTop = videoBase.insertIntoBar(
            bar = PlayerBarSlot.Top,
            index = 0,
            item = PlayerPanelItem.Control(PlayerControlId.PinOnTop),
            mode = PlayerLayoutMode.VideoLandscape,
        )
        assertEquals(PlayerPanelItem.Control(PlayerControlId.Back), insertedInTop.topBar[0])
        assertEquals(PlayerPanelItem.Control(PlayerControlId.PinOnTop), insertedInTop.topBar[1])

        // Video: moving Back from topBar[0] is ignored
        val unchangedBack = insertedInTop.moveBarItemToBarAt(
            fromBar = PlayerBarSlot.Top,
            fromIndex = 0,
            toBar = PlayerBarSlot.Bottom,
            toIndex = 0,
            mode = PlayerLayoutMode.VideoLandscape,
        )
        assertEquals(insertedInTop, unchangedBack)

        // ClipsPortrait: insert into bottom rail at index 0, reject Spacer in bottom rail
        val clipsBase = PlayerLayoutPrefs.Default.clipsPortrait
        val insertedRail = clipsBase.insertIntoBar(
            bar = PlayerBarSlot.Bottom,
            index = 0,
            item = PlayerPanelItem.Control(PlayerControlId.PlayPause),
            mode = PlayerLayoutMode.ClipsPortrait,
        )
        assertEquals(PlayerPanelItem.Control(PlayerControlId.PlayPause), insertedRail.bottomBar[0])
        val rejectedSpacer = insertedRail.insertIntoBar(
            bar = PlayerBarSlot.Bottom,
            index = 0,
            item = PlayerPanelItem.Spacer,
            mode = PlayerLayoutMode.ClipsPortrait,
        )
        assertEquals(insertedRail, rejectedSpacer)

        // ClipsPortrait: reorder within bottom rail & move PlayPause across bars to topBar[0]
        val reorderedRail = insertedRail.moveBarItemToBarAt(
            fromBar = PlayerBarSlot.Bottom,
            fromIndex = 0,
            toBar = PlayerBarSlot.Bottom,
            toIndex = insertedRail.bottomBar.size,
            mode = PlayerLayoutMode.ClipsPortrait,
        )
        assertEquals(PlayerPanelItem.Control(PlayerControlId.PlayPause), reorderedRail.bottomBar.last())

        val movedToTop = reorderedRail.moveBarItemToBarAt(
            fromBar = PlayerBarSlot.Bottom,
            fromIndex = reorderedRail.bottomBar.lastIndex,
            toBar = PlayerBarSlot.Top,
            toIndex = 0,
            mode = PlayerLayoutMode.ClipsPortrait,
        )
        assertEquals(PlayerPanelItem.Control(PlayerControlId.PlayPause), movedToTop.topBar[0])
        assertFalse(movedToTop.hasInBottomBar(PlayerControlId.PlayPause))

        // ClipsLandscape: Back can be reordered within topBar, but cannot move to bottomBar
        val clipsLand = PlayerLayoutPrefs.Default.clipsLandscape
        val backIdx = clipsLand.topBar.indexOfFirst { it is PlayerPanelItem.Control && it.id == PlayerControlId.Back }
        assertTrue(backIdx >= 0)
        val reorderedBack = clipsLand.moveBarItemToBarAt(
            fromBar = PlayerBarSlot.Top,
            fromIndex = backIdx,
            toBar = PlayerBarSlot.Top,
            toIndex = 0,
            mode = PlayerLayoutMode.ClipsLandscape,
        )
        assertEquals(PlayerPanelItem.Control(PlayerControlId.Back), reorderedBack.topBar[0])
        val blockedCrossBar = reorderedBack.moveBarItemToBarAt(
            fromBar = PlayerBarSlot.Top,
            fromIndex = 0,
            toBar = PlayerBarSlot.Bottom,
            toIndex = 0,
            mode = PlayerLayoutMode.ClipsLandscape,
        )
        assertEquals(reorderedBack, blockedCrossBar)
    }

    @Test
    fun `bulk control background and indicator background toggles work per mode and preserve individual overrides`() {
        val base = PlayerLayoutPrefs.Default
        for (mode in PlayerLayoutMode.entries) {
            assertTrue(base.layoutFor(mode).hasAnyControlBackground(mode), "Default should have control bg in $mode")
            assertTrue(base.layoutFor(mode).showIndicatorBackground, "Default should have indicator bg in $mode")
        }

        // 关闭横屏视频的全部控件背景与指示器背景，不影响竖屏视频或信息流
        val stripped = base.updateLayout(PlayerLayoutMode.VideoLandscape) { layout ->
            layout
                .withAllControlBackgrounds(false, PlayerLayoutMode.VideoLandscape)
                .withIndicatorBackground(false, PlayerLayoutMode.VideoLandscape)
        }
        assertFalse(stripped.videoLandscape.hasAnyControlBackground(PlayerLayoutMode.VideoLandscape))
        assertFalse(stripped.videoLandscape.showIndicatorBackground)
        assertFalse(stripped.videoLandscape.center.innerLeft.showBackground)
        assertFalse(stripped.videoLandscape.center.innerRight.showBackground)
        assertTrue(stripped.videoPortrait.hasAnyControlBackground(PlayerLayoutMode.VideoPortrait))
        assertTrue(stripped.videoPortrait.showIndicatorBackground)
        assertTrue(stripped.clipsLandscape.hasAnyControlBackground(PlayerLayoutMode.ClipsLandscape))

        // 单个控件可独立重新开启背景，状态反映为已开启，且再次一键开启会恢复全部控件背景
        val singleOn = stripped.updateLayout(PlayerLayoutMode.VideoLandscape) { layout ->
            layout.withBarItemBackground(PlayerBarSlot.Top, 0, true, PlayerLayoutMode.VideoLandscape)
        }
        assertTrue(singleOn.videoLandscape.hasAnyControlBackground(PlayerLayoutMode.VideoLandscape))
        assertFalse(singleOn.videoLandscape.center.innerLeft.showBackground)

        val allOn = singleOn.updateLayout(PlayerLayoutMode.VideoLandscape) { layout ->
            layout.withAllControlBackgrounds(true, PlayerLayoutMode.VideoLandscape)
        }
        assertTrue(allOn.videoLandscape.center.innerLeft.showBackground)
        assertTrue(allOn.videoLandscape.center.innerRight.showBackground)

        // 重置当前模式恢复默认背景状态
        val reset = stripped.resetMode(PlayerLayoutMode.VideoLandscape)
        assertTrue(reset.videoLandscape.hasAnyControlBackground(PlayerLayoutMode.VideoLandscape))
        assertTrue(reset.videoLandscape.showIndicatorBackground)
    }

    @Test
    fun `legacy json without showIndicatorBackground deserializes losslessly with default true`() {
        val custom = PlayerLayoutPrefs.Default.updateLayout(PlayerLayoutMode.VideoPortrait) {
            it.withIndicatorBackground(false, PlayerLayoutMode.VideoPortrait)
        }
        val encoded = encodePlayerLayoutPrefs(custom)
        val roundTripped = decodePlayerLayoutPrefs(encoded)
        assertFalse(roundTripped.videoPortrait.showIndicatorBackground)
        assertTrue(roundTripped.videoLandscape.showIndicatorBackground)

        // 模拟老版本保存的 JSON（移除 showIndicatorBackground 字段）
        val legacyJson = encoded.replace(Regex(""",?"showIndicatorBackground":(true|false)"""), "")
        assertFalse(legacyJson.contains("showIndicatorBackground"))
        val decodedLegacy = decodePlayerLayoutPrefs(legacyJson)
        for (mode in PlayerLayoutMode.entries) {
            assertTrue(decodedLegacy.layoutFor(mode).showIndicatorBackground, "Legacy JSON must default showIndicatorBackground=true for $mode")
        }
    }
}
