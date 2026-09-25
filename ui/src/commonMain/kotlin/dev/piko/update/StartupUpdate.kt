package dev.piko.update

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import dev.piko.data.auth.PikoUserPreferences
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

/**
 * 开屏检查一次新版本，有就弹 [UpdateDialog]。
 *
 * 检查本身一次进程一次，见 [AppUpdateService.checkOnStartup]；这里每次进入组合都调，
 * 重复调用什么也不做。「忽略此版本」按版本号记住，更新的版本出来照常提示。
 */
@Composable
fun StartupUpdatePrompt(
    updater: AppUpdateService,
    preferences: PikoUserPreferences,
) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(updater) {
        updater.checkOnStartup(isIgnored = { it == preferences.getIgnoredUpdateVersion() })
    }
    val update = updater.startupUpdate ?: return
    UpdateDialog(
        updater = updater,
        update = update,
        onDismiss = updater::dismissStartupUpdate,
        onIgnore = {
            updater.dismissStartupUpdate()
            // 对话框随即离开组合，写盘不能跟着它的作用域一起取消
            scope.launch(NonCancellable) { preferences.setIgnoredUpdateVersion(update.version) }
        },
    )
}
