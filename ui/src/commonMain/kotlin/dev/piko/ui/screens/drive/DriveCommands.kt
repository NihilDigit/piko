package dev.piko.ui.screens.drive

import dev.piko.shared.data.isArchiveVolume
import dev.piko.shared.data.isExtractableArchive
import dev.piko.shared.data.isVaulted
import dev.piko.shared.upload.isUploading
import io.github.nihildigit.pikpak.FileStat

/*
 * 宽窗口网盘页命令栏的显隐规则。命令栏上的每一样东西都要在眼下真有意义：做不了的不摆，别处已经摆着的不重复。
 * 规则只写在这里，命令栏照 [DriveCommands] 画，不再在各处零散地判断。
 *
 * 眼下的状态由三样东西决定，全在 [CommandInputs] 里：
 * - 在哪（[CommandPlace]）：网盘根目录、文件夹、搜索结果、星标这类库、最近添加与播放历史、回收站、压缩包里。
 * - 作用于哪几项：选中的几项；没有选中时是焦点所在（鼠标点过）的一项；都没有时为空，这时只剩作用于整个位置的操作。
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

    /** 最近添加：同 [LIBRARY]，多一个移除记录。 */
    RECENT,

    /** 播放历史：同 [RECENT]，另能整个清空。最近添加没有清空，两者因此分开。 */
    HISTORY,

    /** 回收站：只能恢复与彻底删除。 */
    TRASH,

    /** 压缩包里（见 ArchiveLocation）：条目不在网盘里，只能打开、下载文件与解压选中的几项。 */
    ARCHIVE,
}

internal class CommandInputs(
    val place: CommandPlace,
    /** 人在网盘根目录上（路径栈只有根）。 */
    val atRoot: Boolean,
    /** 作用于的几项：选中的，没有选中时焦点所在的那一项，都没有时为空。 */
    val targets: List<FileStat>,
    /** 在多选中（有选中的项）。 */
    val selecting: Boolean,
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
    // 以下作用于整个位置
    /** 清空回收站、清空播放历史。是这一页的主操作，不在「更多」里，见 PrimaryActionButton。 */
    val emptyPlace: Boolean,
    val selectAll: Boolean,
    val findDuplicates: Boolean,
    val sort: Boolean,
    val filter: Boolean,
    val feed: Boolean,
    val addLink: Boolean,
) {
    val moreMenu: Boolean get() = moveCopyTo || download || extract || removeRecord
}

internal fun driveCommands(input: CommandInputs): DriveCommands = with(input) {
    val folder = place == CommandPlace.ROOT || place == CommandPlace.FOLDER
    val inTrash = place == CommandPlace.TRASH
    val inArchive = place == CommandPlace.ARCHIVE
    val eventLog = place == CommandPlace.RECENT || place == CommandPlace.HISTORY
    val hasTargets = targets.isNotEmpty()
    // 上传中的文件改名、移动、分享都会失败，作用对象里有它就不给这几样；归档条目与压缩包里的条目在网盘里没有文件，同理
    val settled = hasTargets && !inArchive && targets.none { it.isUploading || it.isVaulted }
    DriveCommands(
        home = !atRoot,
        // 多选时左端换成「已选 N 项」；搜索结果不是目录
        create = folder && !selecting,
        cutCopy = settled && !inTrash,
        paste = clipboardFull && folder,
        rename = hasTargets && settled && !inTrash,
        share = hasTargets && settled && !inTrash,
        moveToTrash = hasTargets && !inTrash && !inArchive,
        restoreOrDelete = hasTargets && inTrash,
        moveCopyTo = hasTargets && settled && !inTrash,
        // 文件夹整个下载，见 PikoDownloadCoordinator.enqueueFolders；压缩包里的文件夹不在网盘里，列不出内容，只下文件
        download = hasTargets && !inTrash && targets.any { !it.isUploading && !(inArchive && it.isFolder) },
        // 压缩包里解压的是选中的几项本身，不是选中项里的压缩包
        extract = hasTargets && !inTrash && (inArchive || targets.any { it.isExtractableArchive || it.isArchiveVolume }),
        removeRecord = hasTargets && eventLog,
        // 清空只在有东西可清时。与 libraryPageActions 对应：只有回收站与播放历史有清空
        emptyPlace = (inTrash || place == CommandPlace.HISTORY) && itemCount > 0,
        selectAll = itemCount > 0 && !allSelected,
        // 查重的范围是眼前这个目录，搜索结果与库都不是目录
        findDuplicates = folder && itemCount > 0,
        // 库里按各自的先后（添加、播放的时间）排；一项以下没得排
        sort = (folder || place == CommandPlace.SEARCH) && itemCount >= 2,
        // 只有一类时筛了等于没筛；筛着的时候要留着，才撤得掉
        filter = typeCount >= 2 || filtering,
        // 信息流刷的是一个文件夹
        feed = feedSupported && folder,
        // 默认存进眼前的目录（InstantSheetState.followDriveFolder）。库、查重、压缩包的栈顶是 piko: 一类的虚拟 ID，
        // 存不进去，会退到 My Packs 并提示「当前目录已不存在」。搜索时栈顶仍是搜索所在的目录，地址栏也还写着它，
        // 照常给；否则一输入搜索词命令栏右端就跳
        addLink = folder || place == CommandPlace.SEARCH,
    )
}
