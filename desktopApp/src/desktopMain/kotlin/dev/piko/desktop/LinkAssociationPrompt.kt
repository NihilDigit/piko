package dev.piko.desktop

import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.piko.ui.platform.LinkAssociation
import dev.piko.ui.platform.LinkAssociationState
import kotlinx.coroutines.launch

// 只有桌面端会问，所以记在 settings.properties 里，不进跨平台的偏好接口
private const val ASKED_KEY = "linkAssociation.asked"

/**
 * 首次启动时问一次要不要把磁力链接与种子文件交给 Piko 打开。无论怎么答都记下，不再弹出；
 * 之后要改走设置页的同一项。
 *
 * [association] 为 null（系统不支持）或 state 为 Unavailable（开发版）时既不问也不记，
 * 同一台机器装上正式的包后仍会问一次。
 */
@Composable
internal fun LinkAssociationPrompt(association: LinkAssociation?, settings: DesktopSettingsStore) {
    if (association == null) return
    var isShown by remember { mutableStateOf(false) }
    // 放在提前返回之前：对话框一关，之后才取的作用域会离开组合，正在进行的登记随之取消
    val scope = rememberCoroutineScope()
    LaunchedEffect(association) {
        if (settings.get(ASKED_KEY) == "true") return@LaunchedEffect
        when (association.state()) {
            // 只登记、不是默认的也问：可能是挪了位置的旧副本留下的，眼下这一份没被问过
            LinkAssociationState.NotDefault, LinkAssociationState.Registered -> isShown = true
            // 已经是默认就不必再问，日后被别的应用抢走也不追着弹
            LinkAssociationState.Default -> settings.set(ASKED_KEY, "true")
            LinkAssociationState.Unavailable -> Unit
        }
    }
    if (!isShown) return

    val answer = {
        settings.set(ASKED_KEY, "true")
        isShown = false
    }
    PikoDialog(
        // 点外面或按 Esc 关掉也算答过：用户已经看到了这个问题
        onDismissRequest = answer,
        title = { Text("用 Piko 打开磁力链接与种子文件？") },
        text = {
            Text(
                if (association.needsSystemConfirmation) {
                    "需在随后打开的系统设置中确认。之后也可在设置的「链接」中更改。"
                } else {
                    "之后也可在设置的「链接」中更改。"
                },
            )
        },
        confirmButton = {
            PikoDialogConfirm(
                label = "设为默认",
                onClick = {
                    answer()
                    scope.launch { association.register() }
                },
            )
        },
        dismissButton = {
            TextButton(onClick = answer) { Text("暂不") }
        },
    )
}
