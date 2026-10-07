package dev.piko.desktop

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
    val reason = if (fallback.problem is AccessDeniedException) "没有写入权限" else "无法写入"
    val dismiss = {
        settings.set(NOTICED_KEY, fallback.portableData.toString())
        onDismiss()
    }
    PikoDialog(
        onDismissRequest = dismiss,
        title = { Text("数据未存入便携目录") },
        text = { Text("Piko 所在的文件夹$reason，账号、设置与缓存改存于 ${PikoHome.root}。将 Piko 移至可写入的文件夹即可恢复便携。") },
        confirmButton = { PikoDialogConfirm(label = "知道了", onClick = dismiss) },
    )
}
