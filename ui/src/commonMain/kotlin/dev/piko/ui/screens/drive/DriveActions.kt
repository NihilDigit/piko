package dev.piko.ui.screens.drive

import dev.piko.shared.i18n.tr

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
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.OndemandVideo
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.AddToPhotos
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
 * 只是确认、就地改名或直接执行的不加。普通下载直接入队，指定画质与指定段落分别作为显式参数操作。
 *
 * 分档见 [ActionTier]：图标行按平台排，见 [quickRow]；其余都在列表里，按组排：
 * 打开（各类条目自己的打开方式、下载指定段落），整理（移动、复制、规范命名），管理（预览遮蔽、归档）。
 * 作用于一个位置而非这一项的（查找重复、文件夹的规范命名）与来源链接只进桌面的右键菜单，
 * 移动端的面板没有「更多」可收，要一屏平铺放得下：位置命令在进文件夹后的页眉菜单里，来源在面板头部。
 */
internal object DriveActions {
    /** 普通下载直接入队；指定画质另有显式入口。 */
    fun download(onClick: () -> Unit) = SheetAction(
        Icons.Outlined.Download,
        tr("下载到本地", "Download to device", "この端末にダウンロード", "기기에 다운로드", "下載至本機"),
        onClick,
        group = ActionGroup.Open,
        tier = ActionTier.Quick,
        shortLabel = tr("下载", "Downloads", "ダウンロード", "다운로드", "下載"),
    )

    fun downloadAtQuality(onPrepare: (() -> Unit)? = null, onClick: () -> Unit) = SheetAction(
        Icons.Outlined.HighQuality,
        tr("下载指定画质…", "Download at chosen quality…", "画質を指定してダウンロード…", "화질을 선택하여 다운로드…", "下載指定畫質…"),
        onClick,
        group = ActionGroup.Open,
        onPrepare = onPrepare,
        shortLabel = tr("指定画质", "Choose quality", "画質を指定", "화질 선택", "指定畫質"),
    )

    fun copyDownloadLink(onClick: () -> Unit) = SheetAction(
        Icons.Outlined.Link,
        tr("复制下载链接", "Copy download link", "ダウンロードリンクをコピー", "다운로드 링크 복사", "複製下載連結"),
        onClick,
        group = ActionGroup.Open,
        shortLabel = tr("复制直链", "Copy direct link", "直リンクをコピー", "직접 링크 복사", "複製直連連結"),
    )

    fun share(onClick: () -> Unit) =
        SheetAction(Icons.Outlined.Share, tr("分享", "Share", "共有", "공유", "分享"), onClick, group = ActionGroup.Open, tier = ActionTier.Quick)

    // 图标画的是现状，与信息流的星标按钮一致：已加星标时实心，未加时描边
    fun star(starred: Boolean, onClick: () -> Unit, tier: ActionTier = ActionTier.Quick) = SheetAction(
        icon = if (starred) Icons.Filled.Star else Icons.Outlined.StarOutline,
        label = if (starred) tr("取消星标", "Remove star", "スターを外す", "별표 해제", "取消星號") else tr("添加星标", "Add star", "スターを付ける", "별표 추가", "新增星號"),
        onClick = onClick,
        group = ActionGroup.Organize,
        tier = tier,
        shortLabel = if (starred) tr("已星标", "Starred", "スター付き", "별표 있음", "已加星號") else tr("星标", "Starred", "スター付き", "별표", "星號"),
    )

    // 剪切与复制只放进应用内剪贴板，粘贴在命令栏与空白处的右键菜单里，与 Ctrl+X、Ctrl+C 同一处（DriveScreenState.putOnClipboard）
    fun cut(onClick: () -> Unit) =
        SheetAction(Icons.Outlined.ContentCut, tr("剪切", "Cut", "切り取り", "잘라내기", "剪下"), onClick, group = ActionGroup.Manage)

    fun copy(onClick: () -> Unit) =
        SheetAction(Icons.Outlined.ContentCopy, tr("复制", "Copy", "コピー", "복사", "複製"), onClick, group = ActionGroup.Manage)

    /**
     * 调整常用操作的顺序，完整能力集合保持一致。图标行放不下的操作由 layoutActions 放回列表。
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
        listOfNotNull(download, rename, share, star, cut, copy)

    fun rename(onClick: () -> Unit) =
        SheetAction(Icons.Outlined.DriveFileRenameOutline, tr("重命名", "Rename", "名前を変更", "이름 변경", "重新命名"), onClick, group = ActionGroup.Organize, tier = ActionTier.Quick)

    fun batchRename(onClick: () -> Unit) =
        SheetAction(Icons.Outlined.DriveFileRenameOutline, tr("批量重命名", "Batch rename", "一括で名前を変更", "이름 일괄 변경", "批次重新命名"), onClick, group = ActionGroup.Organize, tier = ActionTier.Quick, shortLabel = tr("重命名", "Rename", "名前を変更", "이름 변경", "重新命名"))

    // 几项是批量重命名换上番号规则，一个文件夹是整理它的子树；单个文件并进了重命名对话框，见 docs/development/av-naming.md
    fun canonicalName(onClick: () -> Unit) =
        SheetAction(Icons.Outlined.DriveFileRenameOutline, tr("规范命名", "Normalize names", "名前を整理", "이름 정리", "規範命名"), onClick, group = ActionGroup.Organize)

    fun moveTo(onClick: () -> Unit) = SheetAction(Icons.Outlined.DriveFileMove, tr("移动到…", "Move to…", "移動先…", "이동 위치…", "移動至…"), onClick, group = ActionGroup.Organize)

    // 叠放的文件夹，与「移动到…」的文件夹箭头成对；ContentCopy 留给放进剪贴板的「复制」，同在一个菜单里只看图标分不清
    fun copyTo(onClick: () -> Unit) = SheetAction(Icons.Outlined.FolderCopy, tr("复制到…", "Copy to…", "コピー先…", "복사 위치…", "複製至…"), onClick, group = ActionGroup.Organize)

    // 开着的样子用实心图钉，与侧边栏里固定的文件夹一致
    fun pin(pinned: Boolean, onClick: () -> Unit) = SheetAction(
        icon = if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
        label = if (pinned) tr("从快速访问取消固定", "Unpin from quick access", "クイックアクセスから外す", "빠른 액세스에서 고정 해제", "從快速存取取消釘選") else tr("固定到快速访问", "Pin to quick access", "クイックアクセスに固定", "빠른 액세스에 고정", "釘選至快速存取"),
        onClick = onClick,
        group = ActionGroup.Organize,
    )

    fun openInNewTab(onClick: () -> Unit) = SheetAction(Icons.Outlined.AddToPhotos, tr("在新标签页打开", "Open in new tab", "新しいタブで開く", "새 탭에서 열기", "在新分頁開啟"), onClick, group = ActionGroup.Open)

    fun openInExternalPlayer(onClick: () -> Unit) =
        SheetAction(Icons.Outlined.OndemandVideo, tr("用外部播放器打开", "Open in external player", "外部プレーヤーで開く", "외부 플레이어에서 열기", "使用外部播放器開啟"), onClick, group = ActionGroup.Open)

    fun extract(onClick: () -> Unit) = SheetAction(Icons.Outlined.Unarchive, tr("解压到当前位置", "Extract here", "ここに解凍", "여기에 압축 해제", "解壓縮至目前位置"), onClick, group = ActionGroup.Open)

    /** 在压缩包里解压选中的几项。不写「到当前位置」：当前位置是包里，解压出来的落在压缩包所在的文件夹。 */
    fun extractEntries(onClick: () -> Unit) = SheetAction(Icons.Outlined.Unarchive, tr("解压到压缩包旁", "Extract beside archive", "圧縮ファイルと同じ場所に解凍", "압축 파일 옆에 해제", "解壓縮至壓縮檔所在資料夾"), onClick, group = ActionGroup.Open)

    fun restoreFromVault(onClick: () -> Unit) = SheetAction(Icons.Outlined.CloudDownload, tr("恢复到网盘", "Restore to drive", "ドライブに復元", "드라이브로 복원", "還原至雲端硬碟"), onClick, group = ActionGroup.Open)

    fun revealInDrive(onClick: () -> Unit) = SheetAction(Icons.Outlined.FolderOpen, tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기", "在雲端硬碟中顯示"), onClick, group = ActionGroup.Open)

    // 圆盘里的一角，取「时间轴上的一段」。原用 ContentCut，与桌面菜单图标行的「剪切」同形
    fun downloadSegment(onClick: () -> Unit, onPrepare: (() -> Unit)?) = SheetAction(
        Icons.Outlined.Timelapse,
        tr("下载指定段落…", "Download selected segment…", "区間を指定してダウンロード…", "구간을 선택하여 다운로드…", "下載指定段落…"),
        onClick,
        group = ActionGroup.Open,
        onPrepare = onPrepare,
        shortLabel = tr("截取段落", "Clip segment", "区間を切り出す", "구간 자르기", "擷取段落"),
    )

    fun findDuplicates(onClick: () -> Unit) =
        SheetAction(Icons.Outlined.FileCopy, tr("查重", "Duplicates", "重複検索", "중복 검색", "查重"), onClick, group = ActionGroup.Manage)

    fun vault(onClick: () -> Unit) = SheetAction(Icons.Outlined.Inventory2, tr("归档", "Archive", "アーカイブ", "보관", "歸檔"), onClick, group = ActionGroup.Open)

    fun unvault(onClick: () -> Unit) = SheetAction(Icons.Outlined.Unarchive, tr("取消归档", "Undo archive", "アーカイブを解除", "보관 취소", "取消歸檔"), onClick, group = ActionGroup.Open)

    fun previewVisibility(hidden: Boolean, onClick: () -> Unit) = SheetAction(
        icon = if (hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
        label = if (hidden) tr("显示预览", "Show preview", "プレビューを表示", "미리 보기 표시", "顯示預覽") else tr("隐藏预览", "Hide preview", "プレビューを隠す", "미리 보기 숨기기", "隱藏預覽"),
        onClick = onClick,
        group = ActionGroup.Manage,
    )

    /** 离线下载与分享转存来的条目，复制或打开来源链接。只在桌面右键菜单里，操作面板的来源写在头部，见 FileActionsSheet。 */
    fun sourceActions(source: FileSource?, onCopy: () -> Unit, onOpen: () -> Unit): List<SheetAction> = when (source) {
        FileSource.Magnet -> listOf(
            SheetAction(Icons.Outlined.Link, tr("复制磁力链接", "Copy magnet link", "マグネットリンクをコピー", "마그넷 링크 복사", "複製磁力連結"), onCopy, group = ActionGroup.Manage),
        )
        FileSource.Share -> listOf(
            SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, tr("打开来源分享", "Open original share", "元の共有を開く", "원본 공유 열기", "開啟來源分享"), onOpen, group = ActionGroup.Manage),
            SheetAction(Icons.Outlined.Link, tr("复制分享链接", "Copy share link", "共有リンクをコピー", "공유 링크 복사", "複製分享連結"), onCopy, group = ActionGroup.Manage),
        )
        null -> emptyList()
    }

    fun restoreFromTrash(onClick: () -> Unit) = SheetAction(Icons.Outlined.RestoreFromTrash, tr("恢复", "Restore", "復元", "복원", "還原"), onClick, group = ActionGroup.Open)

    fun properties(onClick: () -> Unit) = SheetAction(Icons.Outlined.Info, tr("属性", "Properties", "プロパティ", "속성", "屬性"), onClick, group = ActionGroup.Properties)

    // 以下找不回来，垫底、用错误色。移除记录排在移入回收站之前：最危险的在最后

    /**
     * 从最近添加或播放历史里去掉这条记录，文件不动。图标用减号圈，不用垃圾桶：两项同在末组，
     * 原先「从某库中移除」与「移入回收站」的图标只差一个描边，并排时分不清。
     */
    fun removeRecord(onClick: () -> Unit) =
        SheetAction(Icons.Outlined.RemoveCircleOutline, tr("移除记录", "Remove record", "記録を削除", "기록 삭제", "移除記錄"), onClick, destructive = true)

    fun moveToTrash(onClick: () -> Unit) = SheetAction(Icons.Outlined.Delete, tr("移入回收站", "Move to trash", "ゴミ箱に移動", "휴지통으로 이동", "移至資源回收筒"), onClick, destructive = true)

    fun removeFromVault(onClick: () -> Unit) = SheetAction(Icons.Outlined.Delete, tr("从归档移除", "Remove from archive", "アーカイブから削除", "보관에서 삭제", "從歸檔移除"), onClick, destructive = true)

    fun deleteForever(onClick: () -> Unit) = SheetAction(Icons.Outlined.DeleteForever, tr("彻底删除", "Delete permanently", "完全に削除", "영구 삭제", "永久刪除"), onClick, destructive = true)
}
