package dev.piko.shared.data

/**
 * 网盘里剪切或复制、等着粘贴的一批条目，照资源管理器的剪贴板。只在进程内，不进系统剪贴板：
 * 别的程序拿到一串网盘 ID 也没用。
 *
 * @param sources 条目 ID 到它原来所在的文件夹。粘贴剪切的条目时，撤销要靠它移回原处。
 * @param cut 剪切为 true，粘贴即移动；复制为 false，粘贴即复制，剪贴板留着可以再粘。
 */
data class DriveClipboard(val sources: Map<String, String>, val cut: Boolean)
