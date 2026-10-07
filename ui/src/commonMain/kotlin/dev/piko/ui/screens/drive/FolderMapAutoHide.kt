package dev.piko.ui.screens.drive

import androidx.compose.ui.unit.dp
import kotlin.math.hypot

/**
 * 目录图自动收起的时机。时长是意图过滤，不随「减少动画」归零，只有动画本身跳切。
 *
 * 上一版细轨乱在时机：指针路过即弹，只补了延时，没有停住判定、没有打开区与保持区之分，收起后指针还停在边沿又立刻弹回。
 * 这一版的三道过滤：停住满 [OpenDelayMillis] 才弹（挪动超过 [HoverSlop] 重新计时）；保持区比打开区大（迟滞）；
 * 收起后指针要先离开打开区一次才重新武装。
 */
object FolderMapTiming {
    /**
     * 原取 Windows 悬停时间的默认值 400ms，手测嫌慢。把手现在是看得见的入口，指针停上去多半是有意的，误弹代价随之变小。
     */
    const val OpenDelayMillis = 200L

    /**
     * 停住的判定半径。原取 Windows 悬停矩形的 4 像素，手停下时还会漂两三个像素，换算成 dp 后常越过它而重新计时，
     * 实际等待比 [OpenDelayMillis] 长；放宽到 6dp。只看位移，不另设速度阈值：停住与慢慢划过靠计时区分。
     */
    val HoverSlop = 6.dp

    /**
     * 离开保持区后等这么久才收起，期间回来即取消。任务栏与 Dock 都是半秒级；
     * 比 M3 提示的 1.5 秒短，面板挡着列表，多等一秒就多挡一秒。
     */
    const val LeaveDelayMillis = 500L

    /** 把手的宽度，也就是鼠标的打开区。触屏轻点要更宽的命中区，见 [TouchOpenZoneWidth]，触屏不走悬停。 */
    val OpenZoneWidth = 20.dp
    val TouchOpenZoneWidth = 32.dp

    /** 保持区是面板外扩这么多：指针冲过头一点再回来，不算离开。 */
    val KeepMargin = 24.dp

    /** 松手时面板左沿或右沿离列表同侧边沿不超过它，算停靠；只有停靠的面板自动收起。默认位置离右沿 24dp，算停靠。 */
    val DockThreshold = 48.dp

    /** 把手的高度，约是面板标题行加一截；上沿与面板上沿对齐。 */
    val StripHeight = 56.dp

    /** 右侧的把手让出列表滚动条（2dp 边距、6dp 粗、2dp 边距）再多留 2dp，两者的热区不重叠。 */
    val ScrollbarClearance = 12.dp
}

/** 目录图眼下的样子。展开中、收起中只是动画，事件按目标状态处理。钉住与否另记在 [FolderMapAutoHide.pinned]。 */
enum class FolderMapPresence {
    /** × 关掉，导航栏上的树形按钮重新出现。 */
    Closed,

    /** 只剩贴边的把手。 */
    Collapsed,

    /** 临时展开，离开即收。 */
    Peeking,

    /** 常驻展开：钉住了，或面板停在列表中间、收不起来。 */
    Held,
}

/** 面板停靠在列表的哪一侧。按屏幕左右算，不随书写方向翻转：面板的位置本来就是按左右记的。 */
enum class DockSide { Left, Right }

/** 列表这一块里的一个矩形，坐标单位 dp，原点是列表这一块的左上角。 */
data class MapZone(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom

    fun outset(by: Float): MapZone = MapZone(left - by, top - by, right + by, bottom + by)
}

/**
 * 判定用的几块区域。[open] 是把手（打开区），[keep] 是保持区（面板外扩 [FolderMapTiming.KeepMargin]，连同打开区），
 * [docked] 为 false 时面板不在边沿，整套自动收起不生效。
 */
data class FolderMapZones(val open: MapZone?, val keep: List<MapZone>, val docked: Boolean)

/** 面板停在哪一侧：离左右哪边近、且不超过 [FolderMapTiming.DockThreshold]；都不近为 null，即停在中间。 */
fun dockSideOf(panel: MapZone, areaWidth: Float): DockSide? {
    val toLeft = panel.left
    val toRight = areaWidth - panel.right
    val side = if (toRight <= toLeft) DockSide.Right else DockSide.Left
    return side.takeIf { minOf(toLeft, toRight) <= FolderMapTiming.DockThreshold.value }
}

/** 把手占的地方，也就是打开区：贴着停靠那一侧的列表边沿，右侧让出滚动条；上沿与面板上沿对齐。 */
fun stripZone(side: DockSide, top: Float, areaWidth: Float, width: Float = FolderMapTiming.OpenZoneWidth.value): MapZone {
    val bottom = top + FolderMapTiming.StripHeight.value
    return when (side) {
        DockSide.Left -> MapZone(0f, top, width, bottom)
        DockSide.Right -> {
            val right = areaWidth - FolderMapTiming.ScrollbarClearance.value
            MapZone(right - width, top, right, bottom)
        }
    }
}

/** 目录图收到的事件。指针坐标与 [MapZone] 同一套。 */
sealed interface FolderMapEvent {
    /** 指针移动或按下、抬起。[mouse] 为 false 是触屏或笔，不走悬停；[pressed] 是有键按着（拖滚动条、框选、拖文件）。 */
    data class PointerMoved(val x: Float, val y: Float, val mouse: Boolean = true, val pressed: Boolean = false) : FolderMapEvent

    /** 指针离开了列表这一块（移到页眉、侧边栏或窗口外）。 */
    data object PointerLeft : FolderMapEvent

    /** 「忙」：正在拖标题行或改大小、面板内有键盘焦点、过滤框非空或有焦点。忙时不收起，解除后从头计时。 */
    data class BusyChanged(val busy: Boolean) : FolderMapEvent

    /** 到了 [FolderMapAutoHide.wakeAt]。 */
    data object Tick : FolderMapEvent

    /** 点击、轻点把手，或 Tab 到把手后回车、空格。 */
    data object StripActivated : FolderMapEvent

    /** 标题行的钉住按钮，唯一改 [FolderMapAutoHide.pinned] 的事件。 */
    data object PinToggled : FolderMapEvent

    /** 焦点在树里时按 Esc。 */
    data object Escape : FolderMapEvent

    /** 快捷键或命令面板。 */
    data object Toggle : FolderMapEvent

    /** 导航栏的树形按钮，只在关着时有。 */
    data object Open : FolderMapEvent

    /** 面板上的 ×。 */
    data object Close : FolderMapEvent

    /** 停靠或面板位置变了，只为重新套用「不停靠就不收起」。 */
    data object Relayout : FolderMapEvent
}

/**
 * 目录图展开与收起的状态机，不依赖 Compose，时间由调用方给（毫秒，单调）。
 * 界面在 [wakeAt] 到时发一次 [FolderMapEvent.Tick]。
 *
 * 开着与否（[presence] 是不是 Closed）与 [pinned] 是两个独立的偏好。[pinned] 只表示人按过钉住按钮，别的动作一律不改它：
 * × 关掉再打开照旧钉着；拖动、改大小、在过滤框里打字只算「忙」，不顺手钉住，否则没按过钉住的人也被记成钉住。
 * 钉住时打开即常驻；没钉住时打开即临时展开，停在边沿的面板离开即收。
 * 快捷键在钉住时是关掉（同 ×，钉住照旧），没钉住时是展开或收起：钉住就是不想让它自己收，
 * 快捷键若把钉住的面板收成把手，看上去像取消了钉住，再悬停又会弹出临时展开。
 *
 * [armed] 为 false 时悬停不计时：收起之后指针多半还停在把手上，要先离开打开区一次。
 * [hover] 是这一轮停住的起点，挪出 [FolderMapTiming.HoverSlop] 换新的起点重新计时。
 * [entered] 是这一次临时展开以来指针进过保持区没有（或忙过）：点把手、快捷键、导航栏按钮打开时指针多半在别处，
 * 不等它进来过就按离开计时的话，从导航栏按钮那里打开，半秒就收了。
 * [pointerOutside] 是指针在不在保持区外，忙解除、取消钉住时据此决定收不收。
 */
data class FolderMapAutoHide(
    val presence: FolderMapPresence,
    val pinned: Boolean = false,
    val armed: Boolean = true,
    val hover: HoverAnchor? = null,
    val entered: Boolean = true,
    val pointerOutside: Boolean = false,
    val busy: Boolean = false,
    val collapseAt: Long? = null,
) {
    data class HoverAnchor(val x: Float, val y: Float, val since: Long)

    val openAt: Long? get() = hover?.let { it.since + FolderMapTiming.OpenDelayMillis }

    /** 下一次要被叫醒的时刻；没有在计时的为 null。 */
    val wakeAt: Long? get() = openAt ?: collapseAt

    fun step(event: FolderMapEvent, zones: FolderMapZones, now: Long): FolderMapAutoHide {
        val next = when (presence) {
            FolderMapPresence.Closed -> closedStep(event)
            FolderMapPresence.Collapsed -> collapsedStep(event, zones, now)
            FolderMapPresence.Peeking -> peekingStep(event, zones, now)
            FolderMapPresence.Held -> heldStep(event, zones)
        }
        return next.settled(zones, now)
    }

    /**
     * 停靠决定能不能自动收起：拖到中间的面板常驻（收起之后把手贴在哪一侧都说不通）；
     * 没钉住的面板拖回边沿后又按临时展开算，指针此刻就在面板上。
     */
    private fun settled(zones: FolderMapZones, now: Long): FolderMapAutoHide = when {
        !zones.docked && (presence == FolderMapPresence.Collapsed || presence == FolderMapPresence.Peeking) ->
            copy(presence = FolderMapPresence.Held, hover = null, collapseAt = null)
        zones.docked && presence == FolderMapPresence.Held && !pinned ->
            peeking(entered = true).pointerAt(pointerOutside, now)
        else -> this
    }

    private fun closedStep(event: FolderMapEvent): FolderMapAutoHide = when (event) {
        FolderMapEvent.Open, FolderMapEvent.Toggle -> opened()
        is FolderMapEvent.BusyChanged -> copy(busy = event.busy)
        else -> this
    }

    private fun collapsedStep(event: FolderMapEvent, zones: FolderMapZones, now: Long): FolderMapAutoHide = when (event) {
        is FolderMapEvent.PointerMoved -> hovered(event, zones.open, now)
        FolderMapEvent.PointerLeft -> copy(hover = null, armed = true)
        FolderMapEvent.Tick -> {
            val due = openAt
            // 悬停展开时指针就在把手上，把手在保持区里
            if (due != null && now >= due) peeking(entered = true) else this
        }
        FolderMapEvent.StripActivated, FolderMapEvent.Toggle, FolderMapEvent.Open -> opened()
        FolderMapEvent.Close -> closed()
        is FolderMapEvent.BusyChanged -> copy(busy = event.busy)
        else -> this
    }

    private fun hovered(event: FolderMapEvent.PointerMoved, open: MapZone?, now: Long): FolderMapAutoHide {
        if (!event.mouse) return copy(hover = null)
        if (open == null || !open.contains(event.x, event.y)) return copy(hover = null, armed = true)
        if (!armed || event.pressed) return copy(hover = null)
        val anchor = hover
        val still = anchor != null && hypot(event.x - anchor.x, event.y - anchor.y) <= FolderMapTiming.HoverSlop.value
        return if (still) this else copy(hover = HoverAnchor(event.x, event.y, now))
    }

    private fun peekingStep(event: FolderMapEvent, zones: FolderMapZones, now: Long): FolderMapAutoHide = when (event) {
        is FolderMapEvent.PointerMoved -> pointerAt(zones.keep.none { it.contains(event.x, event.y) }, now)
        FolderMapEvent.PointerLeft -> pointerAt(outside = true, now)
        is FolderMapEvent.BusyChanged -> when {
            // 忙过（键盘打开后焦点在树里、拖过标题行）等于进来过：焦点离开时指针若在外面，照常计时收起
            event.busy -> copy(busy = true, entered = true, collapseAt = null)
            pointerOutside && entered -> copy(busy = false, collapseAt = now + FolderMapTiming.LeaveDelayMillis)
            else -> copy(busy = false)
        }
        FolderMapEvent.Tick -> {
            val due = collapseAt
            if (due != null && now >= due) collapsed() else this
        }
        FolderMapEvent.PinToggled -> copy(presence = FolderMapPresence.Held, pinned = true, hover = null, collapseAt = null)
        FolderMapEvent.Escape, FolderMapEvent.Toggle -> collapsed()
        FolderMapEvent.Close -> closed()
        FolderMapEvent.StripActivated, FolderMapEvent.Open, FolderMapEvent.Relayout -> this
    }

    private fun pointerAt(outside: Boolean, now: Long): FolderMapAutoHide = when {
        !outside -> copy(pointerOutside = false, entered = true, collapseAt = null)
        !entered || busy -> copy(pointerOutside = true, collapseAt = null)
        // 已在计时的不重新起算：在保持区外来回挪不该一直拖着不收
        else -> copy(pointerOutside = true, collapseAt = collapseAt ?: (now + FolderMapTiming.LeaveDelayMillis))
    }

    private fun heldStep(event: FolderMapEvent, zones: FolderMapZones): FolderMapAutoHide = when (event) {
        // 记着指针在不在保持区里，取消钉住时据此决定转为临时展开还是收起
        is FolderMapEvent.PointerMoved -> copy(pointerOutside = zones.keep.none { it.contains(event.x, event.y) })
        FolderMapEvent.PointerLeft -> copy(pointerOutside = true)
        FolderMapEvent.PinToggled -> when {
            !pinned -> copy(pinned = true)
            // 停在中间的取消钉住后照旧常驻，拖回边沿才按临时展开算（见 settled）
            !zones.docked -> copy(pinned = false)
            pointerOutside -> collapsed().copy(pinned = false)
            else -> copy(pinned = false).peeking(entered = true)
        }
        FolderMapEvent.Toggle, FolderMapEvent.Close -> closed()
        is FolderMapEvent.BusyChanged -> copy(busy = event.busy)
        // Esc 不改钉住，界面只把焦点还给列表
        else -> this
    }

    /** 打开：钉着的常驻，没钉的临时展开、等指针进来过再按离开计时。 */
    private fun opened(): FolderMapAutoHide =
        if (pinned) copy(presence = FolderMapPresence.Held, hover = null, collapseAt = null) else peeking(entered = false)

    private fun peeking(entered: Boolean) =
        copy(presence = FolderMapPresence.Peeking, hover = null, entered = entered, pointerOutside = !entered, collapseAt = null)

    // 收起时多半指针还在把手上或刚按过快捷键，先不武装
    private fun collapsed() = FolderMapAutoHide(FolderMapPresence.Collapsed, pinned = pinned, armed = false)

    private fun closed() = FolderMapAutoHide(FolderMapPresence.Closed, pinned = pinned)
}
