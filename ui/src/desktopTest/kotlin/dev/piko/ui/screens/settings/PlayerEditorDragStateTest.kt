package dev.piko.ui.screens.settings

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.piko.shared.media.player.CenterSlotAction
import dev.piko.shared.media.player.CenterSlotPosition
import dev.piko.shared.media.player.PlayerBarSlot
import dev.piko.shared.media.player.PlayerControlId
import dev.piko.shared.media.player.PlayerLayoutMode
import dev.piko.shared.media.player.PlayerLayoutPrefs
import dev.piko.shared.media.player.PlayerPanelItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerEditorDragStateTest {

    @Test
    fun `horizontal bar insert index splits around item horizontal centers and pins video Back`() {
        val state = PlayerEditorDragState()
        val layout = PlayerLayoutPrefs.Default.videoLandscape
        state.topBarBounds = Rect(0f, 0f, 400f, 48f)
        // Place 3 items at [0..40], [50..200], [210..260]
        state.barItemBounds[PlayerBarSlot.Top to 0] = Rect(0f, 4f, 40f, 44f)
        state.barItemBounds[PlayerBarSlot.Top to 1] = Rect(50f, 4f, 200f, 44f)
        state.barItemBounds[PlayerBarSlot.Top to 2] = Rect(210f, 4f, 260f, 44f)

        // Video top bar pins Back at index 0 -> pointer at x=5f clamps to index 1
        val beforeBack = state.computeBarInsertIndex(
            bar = PlayerBarSlot.Top,
            items = layout.topBar,
            pointer = Offset(5f, 20f),
            mode = PlayerLayoutMode.VideoLandscape,
        )
        assertEquals(1, beforeBack)

        // Pointer past item 1 center (125f) and before item 2 center (235f) -> index 2
        val beforeThird = state.computeBarInsertIndex(
            bar = PlayerBarSlot.Top,
            items = layout.topBar,
            pointer = Offset(180f, 20f),
            mode = PlayerLayoutMode.VideoLandscape,
        )
        assertEquals(2, beforeThird)

        // Pointer far to the right -> appends at items.size
        val atEnd = state.computeBarInsertIndex(
            bar = PlayerBarSlot.Top,
            items = layout.topBar,
            pointer = Offset(390f, 20f),
            mode = PlayerLayoutMode.VideoLandscape,
        )
        assertEquals(layout.topBar.size, atEnd)
    }

    @Test
    fun `clips portrait right action rail computes insert index vertically by Y center`() {
        val state = PlayerEditorDragState()
        val layout = PlayerLayoutPrefs.Default.clipsPortrait
        assertTrue(isBarVerticalRail(PlayerLayoutMode.ClipsPortrait, PlayerBarSlot.Bottom))
        assertFalse(isBarVerticalRail(PlayerLayoutMode.ClipsLandscape, PlayerBarSlot.Bottom))
        assertFalse(isBarVerticalRail(PlayerLayoutMode.ClipsPortrait, PlayerBarSlot.Top))

        state.bottomBarBounds = Rect(250f, 100f, 310f, 340f)
        state.barItemBounds[PlayerBarSlot.Bottom to 0] = Rect(255f, 100f, 305f, 160f) // centerY = 130
        state.barItemBounds[PlayerBarSlot.Bottom to 1] = Rect(255f, 170f, 305f, 230f) // centerY = 200
        state.barItemBounds[PlayerBarSlot.Bottom to 2] = Rect(255f, 240f, 305f, 300f) // centerY = 270

        // Above first item midpoint -> index 0
        assertEquals(
            0,
            state.computeBarInsertIndex(PlayerBarSlot.Bottom, layout.bottomBar, Offset(280f, 110f), PlayerLayoutMode.ClipsPortrait),
        )
        // Between item 0 and item 1 midpoint -> index 1
        assertEquals(
            1,
            state.computeBarInsertIndex(PlayerBarSlot.Bottom, layout.bottomBar, Offset(280f, 165f), PlayerLayoutMode.ClipsPortrait),
        )
        // Below last item midpoint -> items.size
        assertEquals(
            layout.bottomBar.size,
            state.computeBarInsertIndex(PlayerBarSlot.Bottom, layout.bottomBar, Offset(280f, 325f), PlayerLayoutMode.ClipsPortrait),
        )
    }

    @Test
    fun `clips per-bar allowlist rejects disallowed controls and spacers during drop resolution`() {
        val state = PlayerEditorDragState()
        val layout = PlayerLayoutPrefs.Default.clipsPortrait
        state.previewCanvasBounds = Rect(0f, 0f, 320f, 400f)
        state.topBarBounds = Rect(0f, 0f, 320f, 48f)
        state.bottomBarBounds = Rect(250f, 120f, 320f, 340f)

        // Star is rail-only: hovering Clips topBar must NOT resolve Top bar drop
        state.startDrag(DragPayload.PaletteControl(PlayerControlId.Star), Offset(100f, 24f))
        assertNull(state.resolveDropTarget(layout, PlayerLayoutMode.ClipsPortrait, seekSeconds = 10))

        // Hovering Clips bottomBar (right rail) accepts Star
        state.updateDrag(Offset(280f, 150f))
        assertEquals(
            DropTarget.BarInsert(PlayerBarSlot.Bottom, layout.bottomBar.size),
            state.resolveDropTarget(layout, PlayerLayoutMode.ClipsPortrait, seekSeconds = 10),
        )

        // Spacer is top-only in Clips: hovering Clips bottomBar rejects Spacer, topBar accepts Spacer
        state.startDrag(DragPayload.PaletteSpacer, Offset(280f, 150f))
        assertNull(state.resolveDropTarget(layout, PlayerLayoutMode.ClipsPortrait, seekSeconds = 10))
        state.updateDrag(Offset(100f, 24f))
        assertEquals(
            DropTarget.BarInsert(PlayerBarSlot.Top, layout.topBar.size),
            state.resolveDropTarget(layout, PlayerLayoutMode.ClipsPortrait, seekSeconds = 10),
        )

        // Clips Back can reorder to index 0 in TopBar, cannot drop onto BottomBar
        val backIdx = layout.topBar.indexOfFirst { it is PlayerPanelItem.Control && it.id == PlayerControlId.Back }
        assertTrue(backIdx >= 0)
        state.barItemBounds[PlayerBarSlot.Top to 0] = Rect(0f, 4f, 100f, 44f)
        state.startDrag(
            DragPayload.PlacedBarItem(PlayerBarSlot.Top, backIdx, PlayerPanelItem.Control(PlayerControlId.Back)),
            Offset(10f, 24f),
        )
        assertEquals(
            DropTarget.BarInsert(PlayerBarSlot.Top, 0),
            state.resolveDropTarget(layout, PlayerLayoutMode.ClipsPortrait, seekSeconds = 10),
        )
        state.updateDrag(Offset(280f, 150f))
        assertNull(state.resolveDropTarget(layout, PlayerLayoutMode.ClipsPortrait, seekSeconds = 10))
    }

    @Test
    fun `center slot drop takes priority over bar drop for center-capable payloads in video mode`() {
        val state = PlayerEditorDragState()
        val layout = PlayerLayoutPrefs.Default.videoLandscape
        state.previewCanvasBounds = Rect(0f, 0f, 640f, 360f)
        // Mouse mode: inline transport cluster sits inside bottomBarBounds
        state.bottomBarBounds = Rect(0f, 300f, 640f, 360f)
        state.centerSlotBounds[CenterSlotPosition.OuterLeft] = Rect(20f, 312f, 52f, 344f)
        state.centerSlotBounds[CenterSlotPosition.InnerLeft] = Rect(60f, 312f, 94f, 344f)

        // Center-capable payload hits OuterLeft before falling back to bottomBar
        state.startDrag(DragPayload.PaletteCenterAction(CenterSlotAction.SeekBackward(10)), Offset(36f, 328f))
        assertEquals(
            DropTarget.CenterSlot(CenterSlotPosition.OuterLeft),
            state.resolveDropTarget(layout, PlayerLayoutMode.VideoLandscape, seekSeconds = 10),
        )

        // Non-center payload (Speed) ignores center slot and falls through to bottomBar
        state.startDrag(DragPayload.PaletteControl(PlayerControlId.Speed), Offset(36f, 328f))
        assertEquals(
            DropTarget.BarInsert(PlayerBarSlot.Bottom, layout.bottomBar.size),
            state.resolveDropTarget(layout, PlayerLayoutMode.VideoLandscape, seekSeconds = 10),
        )

        // Listen mode resolves center slot directly
        state.startDrag(DragPayload.PaletteCenterAction(CenterSlotAction.Previous), Offset(75f, 328f))
        assertEquals(
            DropTarget.CenterSlot(CenterSlotPosition.InnerLeft),
            state.resolveListenDropTarget(seekSeconds = 10),
        )
    }

    @Test
    fun `dragging placed bar item over palette or outside canvas resolves Remove while palette item does not`() {
        val state = PlayerEditorDragState()
        val layout = PlayerLayoutPrefs.Default.videoLandscape
        state.previewCanvasBounds = Rect(0f, 0f, 500f, 280f)
        state.paletteBounds = Rect(520f, 0f, 840f, 300f)

        val placed = DragPayload.PlacedBarItem(
            bar = PlayerBarSlot.Bottom,
            index = 1,
            item = PlayerPanelItem.Control(PlayerControlId.Speed),
        )

        // Inside canvas (not over any bar) -> null
        state.startDrag(placed, Offset(250f, 140f))
        assertNull(state.resolveDropTarget(layout, PlayerLayoutMode.VideoLandscape, seekSeconds = 10))

        // Over palette -> Remove
        state.updateDrag(Offset(600f, 120f))
        assertEquals(DropTarget.Remove, state.resolveDropTarget(layout, PlayerLayoutMode.VideoLandscape, seekSeconds = 10))

        // Outside preview canvas -> Remove
        state.updateDrag(Offset(250f, 350f))
        assertEquals(DropTarget.Remove, state.resolveDropTarget(layout, PlayerLayoutMode.VideoLandscape, seekSeconds = 10))

        // Dragging an unplaced palette chip over palette or outside canvas -> null (never Remove)
        state.startDrag(DragPayload.PaletteControl(PlayerControlId.Stats), Offset(600f, 120f))
        assertNull(state.resolveDropTarget(layout, PlayerLayoutMode.VideoLandscape, seekSeconds = 10))
    }

    @Test
    fun `defaultBarForQuickAdd picks valid bar for both video and clips controls`() {
        assertEquals(PlayerBarSlot.Top, defaultBarForQuickAdd(PlayerControlId.Title, PlayerLayoutMode.VideoLandscape))
        assertEquals(PlayerBarSlot.Top, defaultBarForQuickAdd(PlayerControlId.SystemStatus, PlayerLayoutMode.VideoPortrait))
        assertEquals(PlayerBarSlot.Bottom, defaultBarForQuickAdd(PlayerControlId.Speed, PlayerLayoutMode.VideoLandscape))

        assertEquals(PlayerBarSlot.Top, defaultBarForQuickAdd(PlayerControlId.AutoAdvance, PlayerLayoutMode.ClipsPortrait))
        assertEquals(PlayerBarSlot.Top, defaultBarForQuickAdd(PlayerControlId.HideChrome, PlayerLayoutMode.ClipsLandscape))
        assertEquals(PlayerBarSlot.Bottom, defaultBarForQuickAdd(PlayerControlId.Star, PlayerLayoutMode.ClipsPortrait))
        assertEquals(PlayerBarSlot.Bottom, defaultBarForQuickAdd(PlayerControlId.OpenFull, PlayerLayoutMode.ClipsLandscape))

        assertEquals(PlayerBarSlot.Bottom, defaultBarForQuickAddSpacer(PlayerLayoutMode.VideoLandscape))
        assertEquals(PlayerBarSlot.Top, defaultBarForQuickAddSpacer(PlayerLayoutMode.ClipsPortrait))
    }
}
