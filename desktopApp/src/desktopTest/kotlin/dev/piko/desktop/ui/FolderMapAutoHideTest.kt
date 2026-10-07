package dev.piko.desktop.ui

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
 * 列表这一块宽 1000dp，面板停在右上角（右沿离列表右沿 24dp），把手（打开区）在 x 968..988、y 12..68。
 */
class FolderMapAutoHideTest {
    private val area = 1000f
    private val panel = MapZone(616f, 12f, 976f, 400f)
    private val open = stripZone(DockSide.Right, top = 12f, areaWidth = area)
    private val docked = FolderMapZones(open, listOf(panel.outset(24f), open), docked = true)

    // 打开区里的一点，与列表中间的一点
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
    fun `sweeping across the strip does not open it`() {
        val t = Timeline(Collapsed, docked)
        // 每 40ms 挪 8dp，穿过打开区用了两百多毫秒，一直在动
        var time = 0L
        var y = 12f
        while (y < 68f) {
            t.at(time, PointerMoved(980f, y))
            time += 40
            y += 8f
        }
        t.at(time, inList)
        assertEquals(Collapsed, t.at(time + 2_000))
    }

    @Test
    fun `resting on the strip opens it after the open delay`() {
        val t = Timeline(Collapsed, docked)
        t.at(1_000, inStrip)
        assertEquals(Collapsed, t.at(1_199))
        assertEquals(Peeking, t.at(1_200))
    }

    @Test
    fun `moving beyond the slop restarts the timer, jitter within it does not`() {
        val t = Timeline(Collapsed, docked)
        t.at(0, inStrip)
        t.at(150, PointerMoved(980f, 47f))
        assertEquals(Collapsed, t.at(200))
        assertEquals(Collapsed, t.at(349))
        assertEquals(Peeking, t.at(350))

        // 手停下时漂的几个 dp 不重新计时，等待不比 OpenDelay 长
        val jitter = Timeline(Collapsed, docked)
        jitter.at(0, inStrip)
        jitter.at(100, PointerMoved(984f, 43f))
        jitter.at(150, PointerMoved(977f, 36f))
        assertEquals(Peeking, jitter.at(200))
    }

    @Test
    fun `leaving the strip before the delay cancels it`() {
        val t = Timeline(Collapsed, docked)
        t.at(0, inStrip)
        t.at(150, PointerMoved(960f, 40f))
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
        assertEquals(Collapsed, t.at(3_299))
        assertEquals(Peeking, t.at(3_300))
    }

    @Test
    fun `touch does not open by resting and a tap opens it`() {
        val t = Timeline(Collapsed, docked)
        t.at(0, PointerMoved(980f, 40f, mouse = false))
        assertEquals(Collapsed, t.at(3_000))
        assertEquals(Peeking, t.at(3_000, FolderMapEvent.StripActivated))
        assertEquals(false, t.state.pinned)
    }

    @Test
    fun `opening follows the pin, and nothing but the pin button changes it`() {
        assertEquals(Peeking, Timeline(Collapsed, docked).at(0, FolderMapEvent.StripActivated))
        assertEquals(Peeking, Timeline(Collapsed, docked).at(0, FolderMapEvent.Toggle))
        assertEquals(Peeking, Timeline(Closed, docked).at(0, FolderMapEvent.Toggle))
        assertEquals(Peeking, Timeline(Closed, docked).at(0, FolderMapEvent.Open))
        assertEquals(Held, Timeline(Closed, docked, pinned = true).at(0, FolderMapEvent.Open))
        assertEquals(Held, Timeline(Closed, docked, pinned = true).at(0, FolderMapEvent.Toggle))

        // 拖动、过滤这类只算忙，不顺手钉住
        val t = peeking()
        t.at(1_000, FolderMapEvent.BusyChanged(true))
        t.at(1_100, FolderMapEvent.BusyChanged(false))
        assertEquals(false, t.state.pinned)
    }

    @Test
    fun `closing keeps the pin and reopening is pinned again`() {
        val t = peeking()
        t.at(1_000, FolderMapEvent.PinToggled)
        assertEquals(Held, t.state.presence)
        assertEquals(true, t.state.pinned)
        assertEquals(Closed, t.at(2_000, FolderMapEvent.Close))
        assertEquals(true, t.state.pinned)
        assertEquals(Held, t.at(3_000, FolderMapEvent.Open))
        // 钉住时快捷键是关掉，钉住照旧
        assertEquals(Closed, t.at(4_000, FolderMapEvent.Toggle))
        assertEquals(Held, t.at(5_000, FolderMapEvent.Toggle))
    }

    @Test
    fun `an explicit open waits for the pointer to come in before counting the leave delay`() {
        val t = Timeline(Closed, docked)
        // 导航栏按钮在列表这一块外面，打开那一刻指针不在面板附近
        t.at(0, FolderMapEvent.Open)
        t.at(10, FolderMapEvent.PointerLeft)
        t.at(50, inList)
        assertEquals(Peeking, t.at(10_000))
        // 进来过一次之后照常
        t.at(10_000, PointerMoved(800f, 100f))
        t.at(11_000, inList)
        assertEquals(Peeking, t.at(11_499))
        assertEquals(Collapsed, t.at(11_500))
    }

    @Test
    fun `keyboard focus after an explicit open counts as having come in`() {
        val t = Timeline(Collapsed, docked)
        t.at(0, FolderMapEvent.Toggle)
        t.at(10, FolderMapEvent.BusyChanged(true))
        t.at(20, inList)
        assertEquals(Peeking, t.at(5_000))
        t.at(5_000, FolderMapEvent.BusyChanged(false))
        assertEquals(Collapsed, t.at(5_500))
    }

    @Test
    fun `dragging holds it open and leaving after release collapses`() {
        val t = peeking()
        t.at(1_000, FolderMapEvent.BusyChanged(true))
        // 拖着时指针可能冲出保持区
        t.at(1_100, inList)
        assertEquals(Peeking, t.at(3_000))
        t.at(3_000, PointerMoved(800f, 100f))
        t.at(3_010, FolderMapEvent.BusyChanged(false))
        assertEquals(Peeking, t.at(6_000))
        t.at(6_000, inList)
        assertEquals(Collapsed, t.at(6_500))
    }

    @Test
    fun `unpinning peeks while the pointer is near and collapses when it is away`() {
        val near = Timeline(Held, docked, pinned = true)
        near.at(0, PointerMoved(800f, 100f))
        assertEquals(Peeking, near.at(10, FolderMapEvent.PinToggled))
        assertEquals(false, near.state.pinned)
        near.at(100, inList)
        assertEquals(Collapsed, near.at(600))

        val away = Timeline(Held, docked, pinned = true)
        away.at(0, inList)
        assertEquals(Collapsed, away.at(10, FolderMapEvent.PinToggled))
        assertEquals(false, away.state.pinned)
    }

    @Test
    fun `escape and the shortcut collapse a peek while the close button closes`() {
        assertEquals(Collapsed, peeking().at(1_000, FolderMapEvent.Escape))
        assertEquals(Collapsed, peeking().at(1_000, FolderMapEvent.Toggle))
        assertEquals(Closed, peeking().at(1_000, FolderMapEvent.Close))
        assertEquals(Closed, Timeline(Collapsed, docked).at(0, FolderMapEvent.Close))
        // 钉住时 Esc 只还焦点，不改状态
        val pinned = Timeline(Held, docked, pinned = true)
        assertEquals(Held, pinned.at(0, FolderMapEvent.Escape))
        assertEquals(true, pinned.state.pinned)
        assertEquals(Closed, pinned.at(0, FolderMapEvent.Close))
        assertEquals(true, pinned.state.pinned)
    }

    @Test
    fun `an undocked panel never auto hides`() {
        val middle = FolderMapZones(open = null, keep = listOf(panel.outset(24f)), docked = false)
        // 临时展开的面板拖到中间、松手判为不停靠：常驻，钉住照旧是否
        val t = peeking()
        t.zones = middle
        assertEquals(Held, t.at(1_000, FolderMapEvent.Relayout))
        assertEquals(false, t.state.pinned)
        assertEquals(Held, t.at(1_000, FolderMapEvent.Escape))
        t.at(1_100, inList)
        assertEquals(Held, t.at(60_000))
        // 在中间也能钉住、取消钉住，只记偏好
        assertEquals(Held, t.at(60_000, FolderMapEvent.PinToggled))
        assertEquals(true, t.state.pinned)
        assertEquals(Held, t.at(60_000, FolderMapEvent.PinToggled))
        assertEquals(false, t.state.pinned)
        // 拖回边沿：没钉住的又按临时展开算
        t.zones = docked
        t.at(60_100, PointerMoved(800f, 100f))
        assertEquals(Peeking, t.at(60_200, FolderMapEvent.Relayout))
        assertEquals(Collapsed, t.at(60_300, FolderMapEvent.Toggle))

        // 收着的面板因窗口变窄而不再停靠时，展开常驻
        val shrunk = Timeline(Collapsed, docked)
        shrunk.zones = middle
        assertEquals(Held, shrunk.at(0, FolderMapEvent.Relayout))
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
        check(t.at(200) == Peeking)
        // 展开后指针挪进面板
        t.at(500, PointerMoved(800f, 100f))
        return t
    }
}
