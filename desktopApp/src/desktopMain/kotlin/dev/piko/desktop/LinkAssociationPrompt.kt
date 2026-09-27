package dev.piko.desktop

import androidx.compose.material3.AlertDialog
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
 * [association] 为 null（非 Windows）或 state 为 Unavailable（开发版、便携版）时既不问也不记，
 * 同一台机器装上安装版后仍会问一次。
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
            LinkAssociationState.NotDefault -> isShown = true
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
    AlertDialog(
        // 点外面或按 Esc 关掉也算答过：用户已经看到了这个问题
        onDismissRequest = answer,
        title = { Text("用 Piko 打开磁力链接与种子文件？") },
        text = { Text("需在随后打开的系统设置中确认。之后也可在设置的「添加链接」中更改。") },
        confirmButton = {
            TextButton(
                onClick = {
                    answer()
                    scope.launch { association.register() }
                },
            ) { Text("设为默认") }
        },
        dismissButton = {
            TextButton(onClick = answer) { Text("暂不") }
        },
    )
}
