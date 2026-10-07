package dev.piko.ui.screens.drive

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.OndemandVideo
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.Timelapse
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import dev.piko.ui.components.ActionGroup
import dev.piko.ui.components.ActionTier
import dev.piko.ui.components.SheetAction

/**
 * 网盘条目的每一样操作只在这里定义一次：图标、标签、分档与分组。单项的面板与右键菜单、多选的右键菜单、
 * 命令栏、移动端多选顶栏都从这里取，同一个操作在各处的叫法与图标一致。做不做得了由 DriveCommands.kt 的
 * [itemCommands] 决定，不在这里判断。
 *
 * 省略号照 Windows 与 macOS 菜单的惯例：点了之后还要先挑目标或参数（目录、段落）才执行的加，
 * 只是确认、就地改名或直接执行的不加。「下载」不加：只有文件时直接下，有视频时弹出的画质对话框已选好默认的一档，
 * 一次确认即下，还可以设成不再弹；同一项的名字也不该随选中的是不是视频而变。
 *
 * 分档见 [ActionTier]：图标行按平台排，见 [quickRow]；常驻的是移动、复制与各类条目自己的打开方式；其余收进「更多」。
 */
internal object DriveActions {
    /** 点了经 DownloadLauncher 定画质，见那里。[onPrepare] 给单个视频提前查各档大小。 */
    fun download(onPrepare: (() -> Unit)? = null, onClick: () -> Unit) = SheetAction(
        Icons.Outlined.Download,
        "下载到本地",
        onClick,
        group = ActionGroup.Open,
        tier = ActionTier.Quick,
        onPrepare = onPrepare,
        shortLabel = "下载",
    )

    fun share(onClick: () -> Unit) =
        SheetAction(Icons.Outlined.Share, "分享", onClick, group = ActionGroup.Open, tier = ActionTier.Quick)

    // 图标画的是现状，与信息流的星标按钮一致：已加星标时实心，未加时描边
    fun star(starred: Boolean, onClick: () -> Unit, tier: ActionTier = ActionTier.Quick) = SheetAction(
        icon = if (starred) Icons.Filled.Star else Icons.Outlined.StarOutline,
        label = if (starred) "取消星标" else "添加星标",
        onClick = onClick,
        group = ActionGroup.Organize,
        tier = tier,
    )

    // 剪切与复制只放进应用内剪贴板，粘贴在命令栏与空白处的右键菜单里，与 Ctrl+X、Ctrl+C 同一处（DriveScreenState.putOnClipboard）
    fun cut(onClick: () -> Unit) =
        SheetAction(Icons.Outlined.ContentCut, "剪切", onClick, group = ActionGroup.Organize, tier = ActionTier.Quick)

    fun copy(onClick: () -> Unit) =
        SheetAction(Icons.Outlined.ContentCopy, "复制", onClick, group = ActionGroup.Organize, tier = ActionTier.Quick)

    /**
     * 图标行的几样，按平台排，为 null 的不给。桌面照 Win11 资源管理器右键菜单顶上那一排：剪切、复制、重命名、分享、下载；
     * 星标不在其中，由调用方放进整理组的列表：它是开关，列表里写得出「添加星标」「取消星标」，菜单的图标行只有图标。
     * 移动端是下载、分享、星标、重命名，没有剪切与复制：粘贴只在命令栏与空白处的右键菜单里，移动端没有这两处。
     */
    fun quickRow(
        desktop: Boolean,
        cut: SheetAction?,
        copy: SheetAction?,
        download: SheetAction?,
        share: SheetAction?,
        rename: SheetAction?,
        star: SheetAction?,
    ): List<SheetAction> =
        if (desktop) listOfNotNull(cut, copy, rename, share, download) else listOfNotNull(download, share, star, rename)

    fun rename(onClick: () -> Unit) =
        SheetAction(Icons.Outlined.DriveFileRenameOutline, "重命名", onClick, group = ActionGroup.Organize, tier = ActionTier.Quick)

    fun batchRename(onClick: () -> Unit) =
        SheetAction(Icons.Outlined.DriveFileRenameOutline, "批量重命名", onClick, group = ActionGroup.Organize, tier = ActionTier.Quick)

    // 打开批量重命名并换上番号规则，见 docs/development/av-naming.md
    fun canonicalName(onClick: () -> Unit) =
        SheetAction(Icons.Outlined.DriveFileRenameOutline, "按番号规范命名", onClick, group = ActionGroup.Organize, tier = ActionTier.More)

    fun moveTo(onClick: () -> Unit) = SheetAction(Icons.Outlined.DriveFileMove, "移动到…", onClick, group = ActionGroup.Organize)

    // 叠放的文件夹，与「移动到…」的文件夹箭头成对；ContentCopy 留给放进剪贴板的「复制」，同在一个菜单里只看图标分不清
    fun copyTo(onClick: () -> Unit) = SheetAction(Icons.Outlined.FolderCopy, "复制到…", onClick, group = ActionGroup.Organize)

    // 开着的样子用实心图钉，与侧边栏里固定的文件夹一致
    fun pin(pinned: Boolean, onClick: () -> Unit) = SheetAction(
        icon = if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
        label = if (pinned) "从快速访问取消固定" else "固定到快速访问",
        onClick = onClick,
        group = ActionGroup.Organize,
    )

    fun openInNewTab(onClick: () -> Unit) = SheetAction(Icons.Outlined.Tab, "在新标签页打开", onClick, group = ActionGroup.Open)

    fun openInExternalPlayer(onClick: () -> Unit) =
        SheetAction(Icons.Outlined.OndemandVideo, "用外部播放器打开", onClick, group = ActionGroup.Open)

    fun extract(onClick: () -> Unit) = SheetAction(Icons.Outlined.Unarchive, "解压到当前位置", onClick, group = ActionGroup.Open)

    /** 在压缩包里解压选中的几项。不写「到当前位置」：当前位置是包里，解压出来的落在压缩包所在的文件夹。 */
    fun extractEntries(onClick: () -> Unit) = SheetAction(Icons.Outlined.Unarchive, "解压到压缩包旁", onClick, group = ActionGroup.Open)

    fun restoreFromVault(onClick: () -> Unit) = SheetAction(Icons.Outlined.CloudDownload, "恢复到网盘", onClick, group = ActionGroup.Open)

    fun revealInDrive(onClick: () -> Unit) = SheetAction(Icons.Outlined.FolderOpen, "在网盘中显示", onClick, group = ActionGroup.Open)

    // 圆盘里的一角，取「时间轴上的一段」。原用 ContentCut，与桌面菜单图标行的「剪切」同形
    fun downloadSegment(onClick: () -> Unit, onPrepare: (() -> Unit)?) = SheetAction(
        Icons.Outlined.Timelapse,
        "下载指定段落…",
        onClick,
        group = ActionGroup.Open,
        tier = ActionTier.More,
        onPrepare = onPrepare,
    )

    fun findDuplicates(onClick: () -> Unit) =
        SheetAction(Icons.Outlined.FileCopy, "查找重复", onClick, group = ActionGroup.Manage, tier = ActionTier.More)

    fun vault(onClick: () -> Unit) = SheetAction(Icons.Outlined.Inventory2, "归档", onClick, group = ActionGroup.Manage, tier = ActionTier.More)

    fun unvault(onClick: () -> Unit) =
        SheetAction(Icons.Outlined.Unarchive, "取消归档", onClick, group = ActionGroup.Manage, tier = ActionTier.More)

    fun previewVisibility(hidden: Boolean, onClick: () -> Unit) = SheetAction(
        icon = if (hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
        label = if (hidden) "显示预览" else "隐藏预览",
        onClick = onClick,
        group = ActionGroup.Manage,
        tier = ActionTier.More,
    )

    /** 离线下载与分享转存来的条目，复制或打开来源链接。 */
    fun sourceActions(source: FileSource?, onCopy: () -> Unit, onOpen: () -> Unit): List<SheetAction> = when (source) {
        FileSource.Magnet -> listOf(
            SheetAction(Icons.Outlined.Link, "复制磁力链接", onCopy, group = ActionGroup.Manage, tier = ActionTier.More),
        )
        FileSource.Share -> listOf(
            SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "打开来源分享", onOpen, group = ActionGroup.Manage, tier = ActionTier.More),
            SheetAction(Icons.Outlined.Link, "复制分享链接", onCopy, group = ActionGroup.Manage, tier = ActionTier.More),
        )
        null -> emptyList()
    }

    fun restoreFromTrash(onClick: () -> Unit) = SheetAction(Icons.Outlined.RestoreFromTrash, "恢复", onClick, group = ActionGroup.Open)

    fun properties(onClick: () -> Unit) = SheetAction(Icons.Outlined.Info, "属性", onClick, group = ActionGroup.Properties)

    // 以下找不回来，垫底、用错误色。移除记录排在移入回收站之前：最危险的在最后

    /**
     * 从最近添加或播放历史里去掉这条记录，文件不动。图标用减号圈，不用垃圾桶：两项同在末组，
     * 原先「从某库中移除」与「移入回收站」的图标只差一个描边，并排时分不清。
     */
    fun removeRecord(onClick: () -> Unit) =
        SheetAction(Icons.Outlined.RemoveCircleOutline, "移除记录", onClick, destructive = true)

    fun moveToTrash(onClick: () -> Unit) = SheetAction(Icons.Outlined.Delete, "移入回收站", onClick, destructive = true)

    fun removeFromVault(onClick: () -> Unit) = SheetAction(Icons.Outlined.Delete, "从归档移除", onClick, destructive = true)

    fun deleteForever(onClick: () -> Unit) = SheetAction(Icons.Outlined.DeleteForever, "彻底删除", onClick, destructive = true)
}
