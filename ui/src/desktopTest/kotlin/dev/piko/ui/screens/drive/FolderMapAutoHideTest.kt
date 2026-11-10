package dev.piko.ui.screens.drive

import dev.piko.ui.components.DockSide
import dev.piko.ui.screens.drive.FolderMapEvent.PointerMoved
import dev.piko.ui.screens.drive.FolderMapPresence.Closed
import dev.piko.ui.screens.drive.FolderMapPresence.Collapsed
import dev.piko.ui.screens.drive.FolderMapPresence.Held
import dev.piko.ui.screens.drive.FolderMapPresence.Peeking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FolderMapAutoHideTest {
    private val area = 1000f
    private val panel = MapZone(616f, 12f, 976f, 400f)
    private val open = stripZone(DockSide.Right, top = 12f, areaWidth = area)
    private val docked = FolderMapZones(open, listOf(panel.outset(24f), open), docked = true)

    private val inStrip = PointerMoved(980f, 40f)
    private val inList = PointerMoved(300f, 300f)

    private class Timeline(start: FolderMapPresence, var zones: FolderMapZones, pinned: Boolean = false) {
        var state = FolderMapAutoHide(start, pinned = pinned)
        var now = 0L

        fun at(time: Long, event: FolderMapEvent? = null): FolderMapPresence {
            while (true) {
                val wake = state.wakeAt ?: break
                if (wake > time) break
                now = wake
                state = state.step(FolderMapEvent.Tick, zones, now)
            }
            now = time
            if (event != null) state = state.step(event, zones, now)
            return state.presence
        }
    }

    @Test
    fun `strip requires resting unpressed mouse within slop to open`() {
        val sweep = Timeline(Collapsed, docked)
        var time = 0L
        var y = 12f
        while (y < 68f) {
            sweep.at(time, PointerMoved(980f, y))
            time += 40
            y += 8f
        }
        sweep.at(time, inList)
        assertEquals(Collapsed, sweep.at(time + 2_000))

        val rest = Timeline(Collapsed, docked)
        rest.at(1_000, inStrip)
        assertEquals(Collapsed, rest.at(1_199))
        assertEquals(Peeking, rest.at(1_200))

        val movedBeyondSlop = Timeline(Collapsed, docked)
        movedBeyondSlop.at(0, inStrip)
        movedBeyondSlop.at(150, PointerMoved(980f, 47f))
        assertEquals(Collapsed, movedBeyondSlop.at(349))
        assertEquals(Peeking, movedBeyondSlop.at(350))

        val jitter = Timeline(Collapsed, docked)
        jitter.at(0, inStrip)
        jitter.at(100, PointerMoved(984f, 43f))
        jitter.at(150, PointerMoved(977f, 36f))
        assertEquals(Peeking, jitter.at(200))

        val leftEarly = Timeline(Collapsed, docked)
        leftEarly.at(0, inStrip)
        leftEarly.at(150, PointerMoved(960f, 40f))
        assertEquals(Collapsed, leftEarly.at(5_000))

        val pressed = Timeline(Collapsed, docked)
        pressed.at(0, PointerMoved(980f, 40f, pressed = true))
        assertEquals(Collapsed, pressed.at(2_000))

        val touch = Timeline(Collapsed, docked)
        touch.at(0, PointerMoved(980f, 40f, mouse = false))
        assertEquals(Collapsed, touch.at(3_000))
        assertEquals(Peeking, touch.at(3_000, FolderMapEvent.StripActivated))
        assertEquals(false, touch.state.pinned)
    }

    @Test
    fun `peeking collapses after leave delay unless pointer returns or panel is busy`() {
        val leave = peeking()
        leave.at(1_000, inList)
        assertEquals(Peeking, leave.at(1_499))
        assertEquals(Collapsed, leave.at(1_500))

        val returned = peeking()
        returned.at(1_000, inList)
        returned.at(1_300, PointerMoved(700f, 200f))
        assertEquals(Peeking, returned.at(5_000))

        val withinMargin = peeking()
        withinMargin.at(1_000, PointerMoved(panel.left - 20f, 200f))
        withinMargin.at(1_100, PointerMoved(700f, panel.bottom + 20f))
        assertEquals(Peeking, withinMargin.at(5_000))

        val wandering = peeking()
        wandering.at(1_000, inList)
        wandering.at(1_200, PointerMoved(200f, 300f))
        wandering.at(1_400, PointerMoved(100f, 300f))
        assertEquals(Collapsed, wandering.at(1_500))

        val pointerLeft = peeking()
        pointerLeft.at(1_000, FolderMapEvent.PointerLeft)
        assertEquals(Collapsed, pointerLeft.at(1_500))

        val busyOut = peeking()
        busyOut.at(1_000, FolderMapEvent.BusyChanged(true))
        busyOut.at(1_100, inList)
        assertEquals(Peeking, busyOut.at(4_000))
        busyOut.at(4_000, FolderMapEvent.BusyChanged(false))
        assertEquals(Peeking, busyOut.at(4_499))
        assertEquals(Collapsed, busyOut.at(4_500))

        val busyInside = peeking()
        busyInside.at(1_000, FolderMapEvent.BusyChanged(true))
        busyInside.at(2_000, FolderMapEvent.BusyChanged(false))
        assertNull(busyInside.state.wakeAt)
        assertEquals(Peeking, busyInside.at(9_000))
    }

    @Test
    fun `explicit open collapse pin and undocked states follow state machine transitions`() {
        val collapsedOnEdge = peeking()
        collapsedOnEdge.at(1_000, FolderMapEvent.Escape)
        assertEquals(Collapsed, collapsedOnEdge.state.presence)
        collapsedOnEdge.at(1_100, PointerMoved(981f, 41f))
        assertEquals(Collapsed, collapsedOnEdge.at(3_000))
        collapsedOnEdge.at(3_000, PointerMoved(900f, 40f))
        collapsedOnEdge.at(3_100, inStrip)
        assertEquals(Peeking, collapsedOnEdge.at(3_300))

        val explicitOpen = Timeline(Closed, docked)
        explicitOpen.at(0, FolderMapEvent.Open)
        explicitOpen.at(10, FolderMapEvent.PointerLeft)
        explicitOpen.at(50, inList)
        assertEquals(Peeking, explicitOpen.at(10_000))
        explicitOpen.at(10_000, PointerMoved(800f, 100f))
        explicitOpen.at(11_000, inList)
        assertEquals(Collapsed, explicitOpen.at(11_500))

        val pinned = peeking()
        pinned.at(1_000, FolderMapEvent.PinToggled)
        assertEquals(Held, pinned.state.presence)
        assertEquals(Closed, pinned.at(2_000, FolderMapEvent.Close))
        assertEquals(true, pinned.state.pinned)
        assertEquals(Held, pinned.at(3_000, FolderMapEvent.Open))
        assertEquals(Held, pinned.at(3_500, FolderMapEvent.Escape))
        assertEquals(Closed, pinned.at(4_000, FolderMapEvent.Toggle))
        assertEquals(Held, pinned.at(5_000, FolderMapEvent.Toggle))

        val unpinAway = Timeline(Held, docked, pinned = true)
        unpinAway.at(0, inList)
        assertEquals(Collapsed, unpinAway.at(10, FolderMapEvent.PinToggled))

        val middle = FolderMapZones(open = null, keep = listOf(panel.outset(24f)), docked = false)
        val undocked = peeking()
        undocked.zones = middle
        assertEquals(Held, undocked.at(1_000, FolderMapEvent.Relayout))
        undocked.at(1_100, inList)
        assertEquals(Held, undocked.at(60_000))
        undocked.zones = docked
        undocked.at(60_100, PointerMoved(800f, 100f))
        assertEquals(Peeking, undocked.at(60_200, FolderMapEvent.Relayout))

        assertEquals(DockSide.Right, dockSideOf(MapZone(600f, 0f, 952f, 300f), area))
        assertNull(dockSideOf(MapZone(600f, 0f, 951f, 300f), area))
        assertEquals(DockSide.Left, dockSideOf(MapZone(12f, 0f, 400f, 300f), area))
    }

    private fun peeking(): Timeline {
        val t = Timeline(Collapsed, docked)
        t.at(0, inStrip)
        check(t.at(200) == Peeking)
        t.at(500, PointerMoved(800f, 100f))
        return t
    }
}
