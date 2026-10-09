package dev.piko.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piko.ui.components.sidePanelFits

/** 内容区已扣除导航占位；移动端高度不足 480dp 时保持全屏，横握手机不因宽度足够而分屏。 */
internal fun feedPanelFits(availableWidth: Dp?, availableHeight: Dp?, desktop: Boolean, panelMinWidth: Dp): Boolean =
    availableWidth != null && sidePanelFits(availableWidth, panelMinWidth) &&
        (desktop || (availableHeight != null && availableHeight >= 480.dp))

/** 移动端分屏仍与添加链接、查重互斥；桌面只将信息流的临时浏览登记为任务。 */
internal fun feedTaskActive(desktop: Boolean, shown: Boolean, suspended: Boolean): Boolean =
    shown && (!desktop || suspended)
