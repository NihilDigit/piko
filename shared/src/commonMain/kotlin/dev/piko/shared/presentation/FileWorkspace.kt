package dev.piko.shared.presentation

import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.state.SaveTargetContext
import kotlinx.coroutines.flow.StateFlow

/** 文件浏览工作区的公共能力；桌面标签不是移动端导航的组成部分。 */
interface FileWorkspace : DriveNavigation {
    val clipboard: DriveClipboardState
    val recentFoldersFlow: StateFlow<List<List<PikoPathBreadcrumb>>>
    val saveTargetContext: SaveTargetContext
    fun removeRecentFolder(folderId: String)
    fun currentLocation(): FileLocation
    fun returnTo(location: FileLocation)
}

/** 只交回生成它的工作区，用于恢复临时浏览之前的位置与历史。 */
interface FileLocation
