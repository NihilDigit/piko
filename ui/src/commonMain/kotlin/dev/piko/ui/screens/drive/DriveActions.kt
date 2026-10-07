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
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.HighQuality
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
 * 省略号照 Windows 与 macOS 菜单的惯例：点了之后还要先挑目标或参数（目录、画质、段落）才执行的加，
 * 只是确认、就地改名或直接执行的不加。
 *
 * 分档见 [ActionTier]：图标行是下载、分享、星标、重命名，按这个先后；常驻的是移动、复制与各类条目自己的
 * 打开方式；其余收进「更多」。
 */
internal object DriveActions {
    fun download(onClick: () -> Unit) =
        SheetAction(Icons.Outlined.Download, "下载到本地", onClick, group = ActionGroup.Open, tier = ActionTier.Quick, shortLabel = "下载")

    fun share(onClick: () -> Unit) =
        SheetAction(Icons.Outlined.Share, "分享", onClick, group = ActionGroup.Open, tier = ActionTier.Quick)

    // 图标画的是现状，与信息流的星标按钮一致：已加星标时实心，未加时描边
    fun star(starred: Boolean, onClick: () -> Unit) = SheetAction(
        icon = if (starred) Icons.Filled.Star else Icons.Outlined.StarOutline,
        label = if (starred) "取消星标" else "添加星标",
        onClick = onClick,
        group = ActionGroup.Organize,
        tier = ActionTier.Quick,
    )

    fun rename(onClick: () -> Unit) =
        SheetAction(Icons.Outlined.DriveFileRenameOutline, "重命名", onClick, group = ActionGroup.Organize, tier = ActionTier.Quick)

    fun batchRename(onClick: () -> Unit) =
        SheetAction(Icons.Outlined.DriveFileRenameOutline, "批量重命名", onClick, group = ActionGroup.Organize, tier = ActionTier.Quick)

    // 打开批量重命名并换上番号规则，见 docs/development/av-naming.md
    fun canonicalName(onClick: () -> Unit) =
        SheetAction(Icons.Outlined.DriveFileRenameOutline, "按番号规范命名", onClick, group = ActionGroup.Organize, tier = ActionTier.More)

    fun moveTo(onClick: () -> Unit) = SheetAction(Icons.Outlined.DriveFileMove, "移动到…", onClick, group = ActionGroup.Organize)

    fun copyTo(onClick: () -> Unit) = SheetAction(Icons.Outlined.ContentCopy, "复制到…", onClick, group = ActionGroup.Organize)

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

    fun downloadQuality(onClick: () -> Unit, onPrepare: (() -> Unit)?) = SheetAction(
        Icons.Outlined.HighQuality,
        "选择画质下载…",
        onClick,
        group = ActionGroup.Open,
        tier = ActionTier.More,
        onPrepare = onPrepare,
        // 播放器设置面板的图标行里用，与同排各项一律两个字，竖屏手机上一格放不下四个字
        shortLabel = "画质",
    )

    fun downloadSegment(onClick: () -> Unit, onPrepare: (() -> Unit)?) = SheetAction(
        Icons.Outlined.ContentCut,
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
