package dev.piko.shared.data

import dev.piko.shared.i18n.tr

/**
 * 网盘页里的「库」：按某种条件从全盘挑出来的一批条目，当作一个位置来看，而不是各自一页。
 * 列表、视图、详情栏、多选与右键菜单都与文件夹相同，各库只差从哪里取、多出或少掉哪几个操作。
 *
 * 在路径栈里占第一级，取代根目录：停在库里是 [星标]，从库里进了子文件夹是 [星标, 某文件夹]。
 * 所以后退、前进、标签与恢复上次的位置都照常工作，地址栏读作「星标 › 某文件夹」。
 * ID 带保留前缀，PikPak 的文件 ID 只有字母与数字，不会与它相撞。
 */
enum class DriveLibrary(val id: String, private val titleText: () -> String) {
    RECENT("piko:recent", { tr("最近添加", "Recently added", "最近追加した項目", "최근 추가") }),
    STARRED("piko:starred", { tr("星标", "Starred", "スター付き", "별표") }),
    HISTORY("piko:history", { tr("播放历史", "Playback history", "再生履歴", "재생 기록") }),
    TRASH("piko:trash", { tr("回收站", "Trash", "ゴミ箱", "휴지통") }),

    /**
     * 查找重复的结果，按组列出，每组一个分区标题。内容来自进程级的 DuplicateSession，不经网络；
     * 不在侧边栏里，由「查找重复」进来。做成网盘页的位置而不是面板：要挑留哪一份，得看得到缩略图与详情。
     */
    DUPLICATES("piko:duplicates", { tr("查找重复", "Find duplicates", "重複を検索", "중복 찾기") }),
    ;

    val title: String get() = titleText()

    val crumb: PikoPathBreadcrumb get() = PikoPathBreadcrumb(id, title)

    /** 条目来自服务端的事件记录：能从列表里移除一条记录，文件本身不动。 */
    val isEventLog: Boolean get() = this == RECENT || this == HISTORY

    /**
     * 内容来自进程级的会话、不经网络（见 SessionListing）。标签与返回栈只是结果视图，关闭视图不结束会话。
     */
    val isSession: Boolean get() = this == DUPLICATES

    companion object {
        fun of(id: String): DriveLibrary? = entries.firstOrNull { it.id == id }
    }
}

/** 路径栈停在哪个库里，库本身与从库里进的子文件夹都算；在网盘里为 null。 */
val List<PikoPathBreadcrumb>.library: DriveLibrary? get() = firstOrNull()?.let { DriveLibrary.of(it.id) }
