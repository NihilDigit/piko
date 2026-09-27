package dev.piko.ui.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.piko.ui.platform.ShortcutModifier

/**
 * 快捷键一览（F1 或主修饰键 + /）。快捷键散在各处的提示里，这里汇到一起；
 * 改了哪处的快捷键，这张表跟着改。按键写法随平台：mac 上是 ⌘ 与 ⌥。
 */
@Composable
internal fun ShortcutsDialog(modifier: ShortcutModifier, onDismiss: () -> Unit) {
    val mac = modifier == ShortcutModifier.Command
    val primary = if (mac) "⌘" else "Ctrl+"
    val alt = if (mac) "⌥" else "Alt+"
    val groups = listOf(
        "全局" to listOf(
            "${primary}K" to "命令面板：跳到文件夹或执行命令",
            "${primary}1 / 2 / 3" to "切到文件、传输、我的",
            "F1 或 $primary/" to "快捷键一览",
        ),
        "网盘" to listOf(
            "方向键" to "在条目间移动",
            "Enter" to "打开",
            "Backspace 或 $alt↑" to "上一级",
            (if (mac) "⌘[ / ⌘]" else "Alt+← / Alt+→") to "后退、前进（鼠标侧键也行）",
            "${primary}F" to "搜索",
            "F5" to "刷新",
            "${primary}A" to "全选",
            (if (mac) "⌘⌫" else "Delete") to "移入回收站",
            "F2" to "重命名，选了几项时批量重命名",
            "${primary}Z" to "撤销上一次移动、删除或重命名",
            "${primary}I" to "详情栏",
            "菜单键 或 Shift+F10" to "操作菜单",
        ),
        "标签页" to listOf(
            "${primary}T" to "新建标签页",
            "${primary}W" to "关闭标签页",
            "Ctrl+Tab / Ctrl+Shift+Tab" to "下一个、上一个标签页",
            "中键点文件夹" to "在后台的新标签页打开",
        ),
        "鼠标" to listOf(
            "右键" to "操作菜单",
            "${primary.removeSuffix("+")} 点选 / Shift 点选" to "加选、连选",
            "在空白处拖动" to "框选",
            "把条目拖到文件夹上" to "移动（按着 ${if (mac) "⌥" else "Ctrl"} 是复制）",
        ),
        "播放器" to listOf(
            "空格" to "播放、暂停",
            "← / →" to "后退、快进",
            "↑ / ↓" to "音量",
            "F" to "全屏",
        ),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
        title = { Text("快捷键") },
        text = {
            Column(
                modifier = Modifier.widthIn(max = 520.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                for ((group, rows) in groups) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(group, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        for ((keys, action) in rows) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                KeyCap(keys, Modifier.widthIn(min = 180.dp))
                                Text(action, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun KeyCap(keys: String, modifier: Modifier = Modifier) {
    Row(modifier) {
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Text(
                keys,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}
