package dev.piko.desktop

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.piko.shared.PikoHome
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import java.nio.file.AccessDeniedException

// 记的是提示过的便携目录：换个位置再遇到同样的情况仍要说一次
private const val NOTICED_KEY = "portableFallback.noticed"

/** 便携版退回了用户目录、且还没就这个便携目录提示过时返回它。 */
internal fun pendingPortableFallback(settings: DesktopSettingsStore): PikoHome.PortableFallback? =
    PikoHome.portableFallback?.takeUnless { settings.get(NOTICED_KEY) == it.portableData.toString() }

/**
 * 便携版写不进程序目录时数据悄悄去了 `~/.piko`，与安装版共用，用户以为数据跟着程序走，实际不是。提示一次去了哪里、为什么。
 */
@Composable
internal fun PortableFallbackNotice(fallback: PikoHome.PortableFallback, settings: DesktopSettingsStore, onDismiss: () -> Unit) {
    val reason = if (fallback.problem is AccessDeniedException) tr("没有写入权限", "has no write permission", "書き込み権限がありません", "쓰기 권한이 없습니다", "沒有寫入權限") else tr("无法写入", "cannot be written", "書き込めません", "쓸 수 없습니다", "無法寫入")
    val dismiss = {
        settings.set(NOTICED_KEY, fallback.portableData.toString())
        onDismiss()
    }
    PikoDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(Res.string.text_data_is_not_stored_in_portable_folder)) },
        text = { Text(tr("Piko 所在的文件夹$reason，账号、设置与缓存已改存于 ${PikoHome.root}。将 Piko 移至可写入的文件夹即可恢复便携模式。", "Piko’s folder $reason. Accounts, settings and cache are stored in ${PikoHome.root}. Move Piko to a writable folder to restore portable mode.", "Pikoのフォルダーは$reason。アカウント・設定・キャッシュは ${PikoHome.root} に保存します。書き込み可能な場所に移動するとポータブルモードに戻ります。", "Piko 폴더에 $reason. 계정, 설정 및 캐시는 ${PikoHome.root}에 저장됩니다. 쓰기 가능한 폴더로 이동하면 포터블 모드가 복원됩니다.", "Piko 所在的資料夾$reason，帳號、設定與快取改存於 ${PikoHome.root}。將 Piko 移至可寫入的資料夾即可還原可攜模式。")) },
        confirmButton = { PikoDialogConfirm(label = tr("确定", "OK", "OK", "확인", "確定"), onClick = dismiss) },
    )
}
