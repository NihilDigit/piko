package dev.piko.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 大窗口的外框：标题栏、侧边栏、状态栏与网盘页的页眉同为这一种底色，连成一体；各页的内容是嵌在里面的一张卡片，
 * 取页面本色。只在有侧边栏的窗口这样画，窄窗口没有侧边栏，外框只剩标题栏一条，单独上色反而突兀。
 */
val ColorScheme.frame: Color get() = surfaceContainer

/** 内容卡片的形状。只圆左边两个角：右边与窗口边缘齐平，圆了就在窗口边上露出一小块外框色。 */
val FrameContentShape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)

/**
 * 页头下面那张内容卡片的形状，四角都圆：上面是页头，下面是底栏或离窗口底边的一截外框色，卡片浮在中间。
 * 各页经 PikoScaffold 得到它，网盘页的列表区自己裁。
 */
val FrameCardShape = RoundedCornerShape(16.dp)

/** 没有底栏时，卡片下沿离窗口底边留的外框色。 */
val FrameCardBottomMargin = 8.dp

/** 外框顶部一行的高度：侧边栏的图标行、网盘页地址栏那一行与各页顶栏同高，换页时卡片的上沿不跳。 */
val FrameTopRowHeight = 56.dp

/** 外框底部一行的高度：传输页的底栏与侧边栏底部的账号行同高，两边的中线对齐。 */
val FrameBottomRowHeight = 56.dp

/**
 * 眼前是否画着外框，由 PikoMainScaffold 在有侧边栏时提供。右侧的详情栏、信息流栏据此并进外框，
 * 与左边的侧边栏一样直接落在外框色上，不再各自是一张卡：外框里再浮一张卡，就又多了一层底色。
 */
val LocalFramed = compositionLocalOf { false }

/**
 * 窗口至少这么宽才换成侧边栏，M3 的 large 档：扣掉它之后网盘页开着信息流侧栏仍排得下两列。
 * 桌面端的标题栏按它决定是否取外框色，所以放在这里而不是界面内部。
 */
val SidebarMinWindowWidth = 1200.dp
