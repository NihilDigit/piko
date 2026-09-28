package dev.piko.ui.screens.drive

import dev.piko.shared.data.isArchiveVolume
import dev.piko.shared.data.isExtractableArchive
import dev.piko.shared.upload.isUploading
import io.github.nihildigit.pikpak.FileStat

/*
 * 宽窗口网盘页命令栏的显隐规则。命令栏上的每一样东西都要在眼下真有意义：做不了的不摆，别处已经摆着的不重复。
 * 规则只写在这里，命令栏照 [DriveCommands] 画，不再在各处零散地判断。
 *
 * 眼下的状态由四样东西决定，全在 [CommandInputs] 里：
 * - 在哪（[CommandPlace]）：网盘根目录、文件夹、搜索结果、星标这类库、最近添加与播放历史、回收站。
 * - 作用于哪几项：选中的几项；没有选中时是焦点所在（鼠标点过）的一项；都没有时为空，这时只剩作用于整个位置的操作。
 * - 右侧那一栏里是什么（[PanelContent]）：空着、详情、信息流、停进来的面板。那一栏同一时刻只放一样东西，
 *   谁进来原来的让出去；详情栏开着时，条目的操作已经整列摆在那里。
 * - 剪贴板里有没有东西，眼前的列表有几项、有几类。
 */

/** 眼前在哪。库里的子文件夹是真的文件夹，算 [FOLDER]。 */
internal enum class CommandPlace {
    ROOT,
    FOLDER,

    /** 目录内过滤或全盘搜索的结果：不是一个目录，往里新建、粘贴、查重都说不清落在哪。 */
    SEARCH,

    /** 星标：条目是散在全盘的真文件，能改能移，只是这里不是一个能往里放东西的目录。 */
    LIBRARY,

    /** 最近添加、播放历史：同 [LIBRARY]，多一个移除记录。 */
    EVENT_LOG,

    /** 回收站：只能恢复与彻底删除。 */
    TRASH,
}

/** 右侧那一栏眼下放着什么，见 SidePanelHost。 */
internal enum class PanelContent { NONE, DETAILS, FEED, SHEET }

internal class CommandInputs(
    val place: CommandPlace,
    /** 人在网盘根目录上（路径栈只有根）。 */
    val atRoot: Boolean,
    /** 作用于的几项：选中的，没有选中时焦点所在的那一项，都没有时为空。 */
    val targets: List<FileStat>,
    /** 在多选中（有选中的项）。 */
    val selecting: Boolean,
    val panel: PanelContent,
    val clipboardFull: Boolean,
    /** 眼前列表里的条目数。 */
    val itemCount: Int,
    /** 列表里的条目已经全选上了。 */
    val allSelected: Boolean,
    /** 列表里有几类文件，以及是否正按类型筛着。 */
    val typeCount: Int,
    val filtering: Boolean,
    /** 平台放得了信息流的片段。 */
    val feedSupported: Boolean,
)

/** 命令栏上每一样东西显不显示。 */
internal class DriveCommands(
    val home: Boolean,
    /** 「新建」菜单：新建文件夹、上传。 */
    val create: Boolean,
    val cutCopy: Boolean,
    val paste: Boolean,
    val rename: Boolean,
    val share: Boolean,
    val moveToTrash: Boolean,
    /** 回收站的恢复与彻底删除，取代上面那一组。 */
    val restoreOrDelete: Boolean,
    // 以下收在「更多」里：用得少，又都作用于条目
    val moveCopyTo: Boolean,
    val download: Boolean,
    val extract: Boolean,
    val removeRecord: Boolean,
    /** 清空回收站、清空播放历史。 */
    val emptyPlace: Boolean,
    // 以下作用于整个位置
    val selectAll: Boolean,
    val findDuplicates: Boolean,
    val sort: Boolean,
    val filter: Boolean,
    val feed: Boolean,
    val addLink: Boolean,
) {
    val moreMenu: Boolean get() = moveCopyTo || download || extract || removeRecord || emptyPlace
}

internal fun driveCommands(input: CommandInputs): DriveCommands = with(input) {
    val folder = place == CommandPlace.ROOT || place == CommandPlace.FOLDER
    val inTrash = place == CommandPlace.TRASH
    val hasTargets = targets.isNotEmpty()
    // 上传中的文件改名、移动、分享都会失败，作用对象里有它就不给这几样
    val settled = hasTargets && targets.none { it.isUploading }
    // 详情栏开着时它已整列摆出这几项的全部操作，命令栏不再重复一遍；剪切、复制、粘贴除外：
    // 它们是键盘上的习惯动作，详情栏里也没有
    val itemActionsHere = hasTargets && panel != PanelContent.DETAILS
    DriveCommands(
        home = !atRoot,
        // 多选时左端换成「已选 N 项」；搜索结果不是目录
        create = folder && !selecting,
        cutCopy = settled && !inTrash,
        paste = clipboardFull && folder,
        rename = itemActionsHere && settled && !inTrash,
        share = itemActionsHere && settled && !inTrash,
        moveToTrash = itemActionsHere && !inTrash,
        restoreOrDelete = itemActionsHere && inTrash,
        moveCopyTo = itemActionsHere && settled && !inTrash,
        download = itemActionsHere && !inTrash && targets.any { !it.isFolder && !it.isUploading },
        extract = itemActionsHere && !inTrash && targets.any { it.isExtractableArchive || it.isArchiveVolume },
        removeRecord = itemActionsHere && place == CommandPlace.EVENT_LOG,
        // 清空只在有东西可清时
        emptyPlace = (inTrash || place == CommandPlace.EVENT_LOG) && itemCount > 0,
        selectAll = itemCount > 0 && !allSelected,
        // 查重的范围是眼前这个目录，搜索结果与库都不是目录
        findDuplicates = folder && itemCount > 0,
        // 库里按各自的先后（添加、播放的时间）排；一项以下没得排
        sort = (folder || place == CommandPlace.SEARCH) && itemCount >= 2,
        // 只有一类时筛了等于没筛；筛着的时候要留着，才撤得掉
        filter = typeCount >= 2 || filtering,
        // 信息流刷的是一个文件夹
        feed = feedSupported && folder,
        // 存进哪里由添加链接自己选，与眼前在哪无关；只有回收站里放它说不通
        addLink = !inTrash,
    )
}
