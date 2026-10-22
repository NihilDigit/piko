package dev.piko.ui.screens.drive

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.ui.adaptive.WidthClass
import dev.piko.ui.components.ActionGroup
import dev.piko.ui.components.SheetAction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 列表栏宽与海报墙、图库的卡片大小。名字存进偏好（PikoUserPreferences.driveTileSizeFlow），不要改名；
 * 先后即从小到大，主修饰键加滚轮沿着它走。
 */
internal enum class TileSize {
    SMALL,
    MEDIUM,
    LARGE,
    ;

    fun step(larger: Boolean): TileSize = entries[(ordinal + if (larger) 1 else -1).coerceIn(entries.indices)]

    companion object {
        /** 没存过时是中档，即加入大小之前的卡宽。 */
        fun of(name: String): TileSize = entries.firstOrNull { it.name == name } ?: MEDIUM
    }
}

internal val TileSize.label
    get() = when (this) {
        TileSize.LARGE -> "大"
        TileSize.MEDIUM -> "中"
        TileSize.SMALL -> "小"
    }

internal const val GALLERY_TILE_PREF_KEY = "GALLERY"

internal fun galleryTileColumns(size: TileSize, width: WidthClass): Int {
    val (small, medium, large) = when (width) {
        WidthClass.Compact -> Triple(3, 2, 1)
        WidthClass.Medium -> Triple(4, 3, 2)
        WidthClass.Expanded -> Triple(6, 4, 3)
    }
    return when (size) {
        TileSize.SMALL -> small
        TileSize.MEDIUM -> medium
        TileSize.LARGE -> large
    }
}

internal fun galleryTileMaxWidth(size: TileSize): Dp = when (size) {
    TileSize.SMALL -> 240.dp
    TileSize.MEDIUM -> 320.dp
    TileSize.LARGE -> 480.dp
}

/**
 * 每一档一行排几栏，按窗口宽度档位取，卡宽由网格宽度除出来（见 DriveFileGrid 的 TileColumns）。
 * 按栏数而不按固定卡宽分档：固定卡宽时栏数随宽度跳，平板竖握（700dp 上下）大档只剩一栏，某些宽度下两档又撞成同样的栏数；
 * 列表在窄窗口下允许多档共用一栏，避免行内容过窄。窗口宽到卡片超过 [tileMaxWidth] 时才多排几栏，超宽屏上卡片不至于大得离谱。
 */
internal fun tileColumns(mode: DriveViewMode, size: TileSize, width: WidthClass): Int {
    val (small, medium, large) = when (mode) {
        DriveViewMode.LIST -> when (width) {
            WidthClass.Compact -> Triple(1, 1, 1)
            WidthClass.Medium -> Triple(2, 2, 1)
            WidthClass.Expanded -> Triple(3, 2, 1)
        }
        DriveViewMode.POSTER -> when (width) {
            WidthClass.Compact -> Triple(3, 2, 1)
            WidthClass.Medium -> Triple(4, 3, 2)
            WidthClass.Expanded -> Triple(6, 4, 3)
        }
    }
    return when (size) {
        TileSize.SMALL -> small
        TileSize.MEDIUM -> medium
        TileSize.LARGE -> large
    }
}

/**
 * 卡片宽度的上限，只在超宽屏上起作用，每档各一个：只给一个上限时，2560 宽下中档与大档都被它挤成同样的栏数。
 * 各档的上限要比 1440 宽的窗口里这一档的卡宽大一截（大档海报墙那里约 370dp），否则平常的窗口里也多挤出一栏。
 */
internal fun tileMaxWidth(mode: DriveViewMode, size: TileSize): Dp = when (mode) {
    DriveViewMode.LIST -> when (size) {
        TileSize.SMALL -> 420.dp
        TileSize.MEDIUM -> 640.dp
        TileSize.LARGE -> 960.dp
    }
    DriveViewMode.POSTER -> when (size) {
        TileSize.SMALL -> 280.dp
        TileSize.MEDIUM -> 400.dp
        TileSize.LARGE -> 560.dp
    }
}

/**
 * 主修饰键加滚轮的档位链：列表小、中、大，再到海报墙小、中、大。两端停住，不循环。
 */
private val ZoomChain = TileSize.entries.map { DriveViewMode.LIST to it } + TileSize.entries.map { DriveViewMode.POSTER to it }

/**
 * 视图与卡片大小的读写。缩放每一步先从偏好里读出眼前的档位、再写下一档，前后串起来：滚轮一格一个事件，
 * 一帧里可能来好几个，界面上的值要等下一帧重组才更新，拿它算的话连滚几格只走一步。
 */
internal class DriveZoom(private val preferences: PikoUserPreferences) {
    private val lock = Mutex()

    suspend fun step(larger: Boolean) = lock.withLock {
        val mode = DriveViewMode.of(preferences.driveViewModeFlow.first())
        val here = ZoomChain.indexOf(mode to sizeOf(mode))
        val (nextMode, nextSize) = ZoomChain[(here + if (larger) 1 else -1).coerceIn(ZoomChain.indices)]
        if (nextMode != mode) preferences.setDriveViewMode(nextMode.name)
        preferences.setDriveTileSize(nextMode.name, nextSize.name)
    }

    /** 菜单里直接挑一档：换到 [mode]，并按需设定大小。 */
    suspend fun select(mode: DriveViewMode, size: TileSize? = null) = lock.withLock {
        preferences.setDriveViewMode(mode.name)
        if (size != null) preferences.setDriveTileSize(mode.name, size.name)
    }

    private suspend fun sizeOf(mode: DriveViewMode) = TileSize.of(preferences.driveTileSizeFlow(mode.name).first())
}

/** 页眉窄到连视图按钮也收起时，仍可从「更多」选择尺寸。 */
internal fun tileSizeActions(mode: DriveViewMode, size: TileSize, onSelect: (TileSize) -> Unit): List<SheetAction> =
    TileSize.entries.map { option ->
        SheetAction(mode.icon(), "${option.label}尺寸", { onSelect(option) }, group = ActionGroup.ViewSize, checked = option == size)
    }
