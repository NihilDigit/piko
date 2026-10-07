package dev.piko.shared.data

/**
 * 网盘页里的「库」：按某种条件从全盘挑出来的一批条目，当作一个位置来看，而不是各自一页。
 * 列表、视图、详情栏、多选与右键菜单都与文件夹相同，各库只差从哪里取、多出或少掉哪几个操作。
 *
 * 在路径栈里占第一级，取代根目录：停在库里是 [星标]，从库里进了子文件夹是 [星标, 某文件夹]。
 * 所以后退、前进、标签与恢复上次的位置都照常工作，地址栏读作「星标 › 某文件夹」。
 * ID 带保留前缀，PikPak 的文件 ID 只有字母与数字，不会与它相撞。
 */
enum class DriveLibrary(val id: String, val title: String) {
    RECENT("piko:recent", "最近添加"),
    STARRED("piko:starred", "星标"),
    HISTORY("piko:history", "播放历史"),
    TRASH("piko:trash", "回收站"),

    /**
     * 查找重复的结果，按组列出，每组一个分区标题。内容来自进程级的 DuplicateSession，不经网络；
     * 不在侧边栏里，由「查找重复」进来。做成网盘页的位置而不是面板：要挑留哪一份，得看得到缩略图与详情。
     */
    DUPLICATES("piko:duplicates", "查找重复"),

    /**
     * 按番号规范命名一个文件夹的改名建议，按作品分组，每行是一个要改名的文件或文件夹。内容来自进程级的会话，
     * 与查找重复同一种形态：不在侧边栏里，由文件夹的「按番号规范命名」与命令面板进来。
     */
    CANONICAL_NAMES("piko:canonical-names", "按番号规范命名"),
    ;

    val crumb: PikoPathBreadcrumb get() = PikoPathBreadcrumb(id, title)

    /** 条目来自服务端的事件记录：能从列表里移除一条记录，文件本身不动。 */
    val isEventLog: Boolean get() = this == RECENT || this == HISTORY

    /**
     * 内容来自进程级的会话、不经网络（见 SessionListing）：有标签栏时开成会话占着的标签，关标签即结束会话；
     * 窄窗口里离开即结束，先确认。
     */
    val isSession: Boolean get() = this == DUPLICATES || this == CANONICAL_NAMES

    companion object {
        fun of(id: String): DriveLibrary? = entries.firstOrNull { it.id == id }
    }
}

/** 路径栈停在哪个库里，库本身与从库里进的子文件夹都算；在网盘里为 null。 */
val List<PikoPathBreadcrumb>.library: DriveLibrary? get() = firstOrNull()?.let { DriveLibrary.of(it.id) }
