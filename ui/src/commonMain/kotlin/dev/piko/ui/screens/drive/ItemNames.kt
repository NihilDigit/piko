package dev.piko.ui.screens.drive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import dev.piko.shared.state.DriveItemName
import dev.piko.ui.components.displayTitle

/** 卡片与列表行上的名字：显示名，认不出时照原样写，扩展名随「显示扩展名」。 */
@Composable
@ReadOnlyComposable
internal fun DriveItemName.rowTitle(): String = title ?: file.displayTitle()

/** 卡片与列表行上写在显示名下面的真实名称，与显示名相同或认不出时为 null。 */
@Composable
@ReadOnlyComposable
internal fun DriveItemName.rowOriginal(): String? {
    val shown = title ?: return null
    return file.displayTitle().takeIf { it != shown && file.name != shown }
}

/** 操作面板、属性卡片、拖放与提示里的名字。认不出时是带扩展名的全名：这些地方本来就给全名，可选中复制。 */
internal val DriveItemName.headingText: String get() = heading ?: file.name

/** 写在 [headingText] 下面的真实名称，与它相同时为 null。 */
internal val DriveItemName.headingOriginal: String? get() = file.name.takeIf { heading != null && heading != it }
