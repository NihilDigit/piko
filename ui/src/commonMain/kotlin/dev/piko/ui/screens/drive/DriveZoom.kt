package dev.piko.ui.screens.drive

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoSizeSelectActual
import androidx.compose.material.icons.outlined.PhotoSizeSelectLarge
import androidx.compose.material.icons.outlined.PhotoSizeSelectSmall
import androidx.compose.ui.graphics.vector.ImageVector
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
 * 海报墙与图库的卡片大小。名字存进偏好（PikoUserPreferences.driveTileSizeFlow），不要改名；
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
        TileSize.LARGE -> "大尺寸"
        TileSize.MEDIUM -> "中尺寸"
        TileSize.SMALL -> "小尺寸"
    }

internal val TileSize.icon: ImageVector
    get() = when (this) {
        TileSize.LARGE -> Icons.Outlined.PhotoSizeSelectLarge
        TileSize.MEDIUM -> Icons.Outlined.PhotoSizeSelectActual
        TileSize.SMALL -> Icons.Outlined.PhotoSizeSelectSmall
    }

/**
 * 每一档一行排几栏，按窗口宽度档位取，卡宽由网格宽度除出来（见 DriveFileGrid 的 TileColumns）。
 * 按栏数而不按固定卡宽分档：固定卡宽时栏数随宽度跳，平板竖握（700dp 上下）大档只剩一栏，某些宽度下两档又撞成同样的栏数；
 * 按栏数分，任何宽度下三档都各差一截。窗口宽到卡片超过 [tileMaxWidth] 时才多排几栏，超宽屏上卡片不至于大得离谱。
 */
internal fun tileColumns(mode: DriveViewMode, size: TileSize, width: WidthClass): Int {
    val (small, medium, large) = when (mode) {
        DriveViewMode.LIST -> error("列表没有卡片大小")
        DriveViewMode.POSTER -> when (width) {
            WidthClass.Compact -> Triple(3, 2, 1)
            WidthClass.Medium -> Triple(4, 3, 2)
            WidthClass.Expanded -> Triple(6, 4, 3)
        }
        DriveViewMode.GALLERY -> when (width) {
            WidthClass.Compact -> Triple(4, 3, 2)
            WidthClass.Medium -> Triple(6, 5, 3)
            WidthClass.Expanded -> Triple(10, 8, 5)
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
    DriveViewMode.LIST -> error("列表没有卡片大小")
    DriveViewMode.POSTER -> when (size) {
        TileSize.SMALL -> 280.dp
        TileSize.MEDIUM -> 400.dp
        TileSize.LARGE -> 560.dp
    }
    DriveViewMode.GALLERY -> when (size) {
        TileSize.SMALL -> 160.dp
        TileSize.MEDIUM -> 220.dp
        TileSize.LARGE -> 320.dp
    }
}

/**
 * 主修饰键加滚轮的档位链：列表、海报墙小、中、大。图库不在链上：它只列有缩略图的文件，滚进去文件像是没了；
 * 人在图库里时只在图库自己的几档里走。两端停住，不循环。
 */
private val ZoomChain = listOf(DriveViewMode.LIST to null) + TileSize.entries.map { DriveViewMode.POSTER to it }

/**
 * 视图与卡片大小的读写。缩放每一步先从偏好里读出眼前的档位、再写下一档，前后串起来：滚轮一格一个事件，
 * 一帧里可能来好几个，界面上的值要等下一帧重组才更新，拿它算的话连滚几格只走一步。
 */
internal class DriveZoom(private val preferences: PikoUserPreferences) {
    private val lock = Mutex()

    suspend fun step(larger: Boolean) = lock.withLock {
        val mode = DriveViewMode.of(preferences.driveViewModeFlow.first())
        if (mode == DriveViewMode.GALLERY) {
            val size = sizeOf(mode)
            val next = size.step(larger)
            if (next != size) preferences.setDriveTileSize(mode.name, next.name)
            return@withLock
        }
        val here = if (mode == DriveViewMode.LIST) 0 else ZoomChain.indexOf(mode to sizeOf(mode))
        val (nextMode, nextSize) = ZoomChain[(here + if (larger) 1 else -1).coerceIn(ZoomChain.indices)]
        if (nextMode != mode) preferences.setDriveViewMode(nextMode.name)
        if (nextSize != null) preferences.setDriveTileSize(nextMode.name, nextSize.name)
    }

    /** 菜单里直接挑一档：换到 [mode]，有卡片的视图再定大小。 */
    suspend fun select(mode: DriveViewMode, size: TileSize? = null) = lock.withLock {
        preferences.setDriveViewMode(mode.name)
        if (size != null) preferences.setDriveTileSize(mode.name, size.name)
    }

    private suspend fun sizeOf(mode: DriveViewMode) = TileSize.of(preferences.driveTileSizeFlow(mode.name).first())
}

/** 视图菜单与页眉「更多」里的大小几项，眼下这一档打勾。列表没有大小，给空表。 */
internal fun tileSizeActions(mode: DriveViewMode, size: TileSize, onSelect: (TileSize) -> Unit): List<SheetAction> =
    if (!mode.isGrid) {
        emptyList()
    } else {
        TileSize.entries.reversed().map { option ->
            SheetAction(option.icon, option.label, { onSelect(option) }, group = ActionGroup.ViewSize, checked = option == size)
        }
    }
