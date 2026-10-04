package dev.piko.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 大窗口的外框：窗口的底色，标题栏那一行、标签栏与右侧详情栏直接落在上面；侧边栏与各页内容是浮在上面的圆角岛，
 * 只在岛与岛之间露出来，见 [IslandGap]。只在有侧边栏的窗口这样画，窄窗口没有侧边栏，外框只剩标题栏一条，单独上色反而突兀。
 *
 * 比岛深两档（岛是 surface，页眉与活动标签是 [islandHeader]）：只深一档时活动标签与外框几乎同色，
 * 开着几个标签看不出眼前是哪一个。
 */
val ColorScheme.frame: Color get() = surfaceContainerHigh

/** 内容卡片的形状。只圆左边两个角：右边与窗口边缘齐平，圆了就在窗口边上露出一小块外框色。 */
val FrameContentShape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)

/**
 * 页头下面那张内容卡片的形状，四角都圆：上面是页头，下面是底栏或离窗口底边的一截外框色，卡片浮在中间。
 * 各页经 PikoScaffold 得到它，网盘页的列表区自己裁。
 */
val FrameCardShape = RoundedCornerShape(16.dp)

/**
 * 岛与岛之间、岛与窗口边缘之间露出的外框色。有侧边栏的窗口里，侧边栏、网盘页的页眉（连同活动标签）与内容各是一块岛，
 * 浮在外框色上：原来侧边栏与页眉直接画在外框色上，标签无处可连，开着几个标签也看不出哪个是眼前这一页。
 */
val IslandGap = 8.dp

/**
 * 岛上半段页眉的底色，活动标签与它同色相连。页眉与下面的内容是同一块岛，页眉比内容高一档色阶，
 * 靠色差分出上下两段，不画分隔线：一道线横在岛中间，读起来像两样东西拼在一起。
 */
val ColorScheme.islandHeader: Color get() = surfaceContainerLow

/** 岛的页眉与内容之间，内容上沿的小圆角：露出一点页眉的底色，像内容被页眉包着。 */
val IslandInnerCorner = 12.dp

/** 岛的圆角，与 [FrameCardShape] 相同。拆成单独的值是因为网盘页那块岛由页眉与列表拼成，各自只圆一半。 */
val IslandCorner = 16.dp

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
 * 窗口至少这么宽就换成侧边栏，即 M3 compact 档的上沿：比这窄是手机的底部导航栏，此外只有侧边栏这一套。
 * 原来要到 1200dp 才换，中间一档交给导航套件，桌面半屏与手机横屏因此只剩三个去处的窄轨或一条横向底栏，
 * 快速访问、库与并进内容的标题栏都没有；侧边栏能收成窄轨之后，这一档没有理由另成一套。
 * 桌面端的标题栏按它决定是否取外框色，所以放在这里而不是界面内部。
 */
val SidebarMinWindowWidth = 600.dp

/**
 * 窗口比这窄时侧边栏只占窄轨的宽度，展开时浮在内容上面，不挤内容：展开的侧边栏 240dp，
 * 700dp 的窗口推开之后内容只剩四百多，网盘页排不下两列。
 */
val SidebarPushMinWindowWidth = 1000.dp
