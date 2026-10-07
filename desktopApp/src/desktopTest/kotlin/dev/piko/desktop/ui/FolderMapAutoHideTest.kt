package dev.piko.desktop.ui

import dev.piko.data.auth.FolderMapMode
import dev.piko.ui.screens.drive.DockSide
import dev.piko.ui.screens.drive.FolderMapAutoHide
import dev.piko.ui.screens.drive.FolderMapEvent
import dev.piko.ui.screens.drive.FolderMapEvent.PointerMoved
import dev.piko.ui.screens.drive.FolderMapPresence
import dev.piko.ui.screens.drive.FolderMapPresence.Closed
import dev.piko.ui.screens.drive.FolderMapPresence.Collapsed
import dev.piko.ui.screens.drive.FolderMapPresence.Held
import dev.piko.ui.screens.drive.FolderMapPresence.Peeking
import dev.piko.ui.screens.drive.FolderMapZones
import dev.piko.ui.screens.drive.MapZone
import dev.piko.ui.screens.drive.dockSideOf
import dev.piko.ui.screens.drive.stripZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 目录图自动收起的时机，在虚拟时间线上走：每一步先把到期的唤醒按时刻发完，再发这一步的事件，
 * 与界面里「wakeAt 到了发 Tick」的做法一致。
 *
 * 列表这一块宽 1000dp，面板停在右上角（右沿离列表右沿 24dp），细条的打开区在 x 972..988、y 12..68。
 */
class FolderMapAutoHideTest {
    private val area = 1000f
    private val panel = MapZone(616f, 12f, 976f, 400f)
    private val open = stripZone(DockSide.Right, top = 12f, areaWidth = area)
    private val docked = FolderMapZones(open, listOf(panel.outset(24f), open), docked = true)

    // 打开区里的一点，与列表中间的一点
    private val inStrip = PointerMoved(980f, 40f)
    private val inList = PointerMoved(300f, 300f)

    private class Timeline(start: FolderMapPresence, var zones: FolderMapZones) {
        var state = FolderMapAutoHide(start)
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
    fun `sweeping across the strip does not open it`() {
        val t = Timeline(Collapsed, docked)
        // 每 40ms 挪 6dp，穿过打开区用了两百多毫秒，一直在动
        var time = 0L
        var y = 12f
        while (y < 68f) {
            t.at(time, PointerMoved(980f, y))
            time += 40
            y += 6f
        }
        t.at(time, inList)
        assertEquals(Collapsed, t.at(time + 2_000))
    }

    @Test
    fun `resting on the strip opens it after the open delay`() {
        val t = Timeline(Collapsed, docked)
        t.at(1_000, inStrip)
        assertEquals(Collapsed, t.at(1_399))
        assertEquals(Peeking, t.at(1_400))
    }

    @Test
    fun `moving beyond the slop restarts the timer, jitter within it does not`() {
        val t = Timeline(Collapsed, docked)
        t.at(0, inStrip)
        t.at(300, PointerMoved(980f, 45f))
        assertEquals(Collapsed, t.at(400))
        assertEquals(Collapsed, t.at(699))
        assertEquals(Peeking, t.at(700))

        val jitter = Timeline(Collapsed, docked)
        jitter.at(0, inStrip)
        jitter.at(200, PointerMoved(982f, 42f))
        jitter.at(300, PointerMoved(978f, 38f))
        assertEquals(Peeking, jitter.at(400))
    }

    @Test
    fun `leaving the strip before the delay cancels it`() {
        val t = Timeline(Collapsed, docked)
        t.at(0, inStrip)
        t.at(300, PointerMoved(960f, 40f))
        assertEquals(Collapsed, t.at(5_000))
    }

    @Test
    fun `a pressed button does not count as resting`() {
        val t = Timeline(Collapsed, docked)
        t.at(0, PointerMoved(980f, 40f, pressed = true))
        assertEquals(Collapsed, t.at(2_000))
    }

    @Test
    fun `peeking collapses only after the pointer stays out for the leave delay`() {
        val t = peeking()
        t.at(1_000, inList)
        assertEquals(Peeking, t.at(1_499))
        assertEquals(Collapsed, t.at(1_500))
    }

    @Test
    fun `coming back within the leave delay keeps it open`() {
        val t = peeking()
        t.at(1_000, inList)
        t.at(1_300, PointerMoved(700f, 200f))
        assertEquals(Peeking, t.at(5_000))
    }

    @Test
    fun `overshooting the panel by less than the keep margin is not leaving`() {
        val t = peeking()
        t.at(1_000, PointerMoved(panel.left - 20f, 200f))
        t.at(1_100, PointerMoved(700f, panel.bottom + 20f))
        assertEquals(Peeking, t.at(5_000))
    }

    @Test
    fun `wandering outside does not keep postponing the collapse`() {
        val t = peeking()
        t.at(1_000, inList)
        t.at(1_200, PointerMoved(200f, 300f))
        t.at(1_400, PointerMoved(100f, 300f))
        assertEquals(Collapsed, t.at(1_500))
    }

    @Test
    fun `leaving the list area counts as leaving`() {
        val t = peeking()
        t.at(1_000, FolderMapEvent.PointerLeft)
        assertEquals(Collapsed, t.at(1_500))
    }

    @Test
    fun `busy holds it open and the leave delay restarts when busy ends`() {
        val t = peeking()
        t.at(1_000, FolderMapEvent.BusyChanged(true))
        t.at(1_100, inList)
        assertEquals(Peeking, t.at(4_000))
        t.at(4_000, FolderMapEvent.BusyChanged(false))
        assertEquals(Peeking, t.at(4_499))
        assertEquals(Collapsed, t.at(4_500))
    }

    @Test
    fun `busy ending with the pointer inside starts no timer`() {
        val t = peeking()
        t.at(1_000, FolderMapEvent.BusyChanged(true))
        t.at(2_000, FolderMapEvent.BusyChanged(false))
        assertNull(t.state.wakeAt)
        assertEquals(Peeking, t.at(9_000))
    }

    @Test
    fun `after collapsing it does not reopen under a pointer resting on the edge`() {
        val t = peeking()
        t.at(1_000, FolderMapEvent.Escape)
        assertEquals(Collapsed, t.state.presence)
        // 指针一直在打开区里挪来挪去，不弹
        t.at(1_100, PointerMoved(981f, 41f))
        t.at(1_300, PointerMoved(984f, 50f))
        assertEquals(Collapsed, t.at(3_000))
        // 先离开打开区，回来再停住才弹
        t.at(3_000, PointerMoved(900f, 40f))
        t.at(3_100, inStrip)
        assertEquals(Collapsed, t.at(3_499))
        assertEquals(Peeking, t.at(3_500))
    }

    @Test
    fun `touch does not open by resting and a tap pins it`() {
        val t = Timeline(Collapsed, docked)
        t.at(0, PointerMoved(980f, 40f, mouse = false))
        assertEquals(Collapsed, t.at(3_000))
        assertEquals(Held, t.at(3_000, FolderMapEvent.StripActivated))
    }

    @Test
    fun `click and shortcut go straight to held`() {
        assertEquals(Held, Timeline(Collapsed, docked).at(0, FolderMapEvent.StripActivated))
        assertEquals(Held, Timeline(Collapsed, docked).at(0, FolderMapEvent.Toggle))
        assertEquals(Held, Timeline(Closed, docked).at(0, FolderMapEvent.Toggle))
        assertEquals(Held, Timeline(Closed, docked).at(0, FolderMapEvent.Open))
    }

    @Test
    fun `engaging a peeking panel pins it, then leaving does not collapse`() {
        val t = peeking()
        t.at(1_000, FolderMapEvent.Engaged)
        assertEquals(Held, t.state.presence)
        t.at(1_100, inList)
        t.at(1_200, FolderMapEvent.PointerLeft)
        assertEquals(Held, t.at(60_000))
    }

    @Test
    fun `escape and unpinning collapse while the close button closes`() {
        assertEquals(Collapsed, Timeline(Held, docked).at(0, FolderMapEvent.Escape))
        assertEquals(Collapsed, Timeline(Held, docked).at(0, FolderMapEvent.PinToggled))
        assertEquals(Collapsed, Timeline(Held, docked).at(0, FolderMapEvent.Toggle))
        assertEquals(Closed, Timeline(Held, docked).at(0, FolderMapEvent.Close))
        assertEquals(Closed, peeking().at(1_000, FolderMapEvent.Close))
        assertEquals(Closed, Timeline(Collapsed, docked).at(0, FolderMapEvent.Close))
    }

    @Test
    fun `an undocked panel never auto hides`() {
        val middle = FolderMapZones(open = null, keep = listOf(panel.outset(24f)), docked = false)
        val t = Timeline(Held, middle)
        assertEquals(Held, t.at(0, FolderMapEvent.PinToggled))
        assertEquals(Held, t.at(0, FolderMapEvent.Escape))
        t.at(100, inList)
        assertEquals(Held, t.at(60_000))
        // 快捷键退回原来的开关：收不起来就关掉
        assertEquals(Closed, t.at(60_000, FolderMapEvent.Toggle))

        // 收着的面板因窗口变窄而不再停靠时，展开常驻
        val shrunk = Timeline(Collapsed, docked)
        shrunk.zones = middle
        assertEquals(Held, shrunk.at(0, FolderMapEvent.Relayout))
    }

    @Test
    fun `presence maps onto the saved three states`() {
        assertEquals(FolderMapMode.AutoHide, Peeking.mode)
        assertEquals(FolderMapMode.AutoHide, Collapsed.mode)
        assertEquals(FolderMapMode.Pinned, Held.mode)
        assertEquals(Collapsed, FolderMapPresence.of(FolderMapMode.AutoHide))
        assertEquals(FolderMapMode.Closed, FolderMapMode.parse("true"))
    }

    @Test
    fun `docking is judged by the nearer side within the threshold`() {
        assertEquals(DockSide.Right, dockSideOf(MapZone(600f, 0f, 976f, 300f), area))
        assertEquals(DockSide.Right, dockSideOf(MapZone(600f, 0f, 952f, 300f), area))
        assertNull(dockSideOf(MapZone(600f, 0f, 951f, 300f), area))
        assertEquals(DockSide.Left, dockSideOf(MapZone(12f, 0f, 400f, 300f), area))
        assertNull(dockSideOf(MapZone(300f, 0f, 700f, 300f), area))
    }

    private fun peeking(): Timeline {
        val t = Timeline(Collapsed, docked)
        t.at(0, inStrip)
        check(t.at(400) == Peeking)
        // 展开后指针挪进面板
        t.at(500, PointerMoved(800f, 100f))
        return t
    }
}
