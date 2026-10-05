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
import dev.piko.ui.components.PikoDialog
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
    val groups = listOf(
        "全局" to listOf(
            "${primary}K" to "命令面板：跳到文件夹或执行命令",
            "${primary}1 / 2 / 3" to "切到文件、传输、我的",
            "F1 或 $primary/" to "快捷键一览",
            "${primary}," to "设置",
            "${primary}B" to "收起或展开侧边栏",
        ),
        "网盘" to listOf(
            "方向键" to "在条目间移动",
            (if (mac) "⌘↓" else "Enter") to "打开",
            (if (mac) "⌘↑" else "Alt+↑") to "上一级",
            (if (mac) "⌘[ / ⌘]" else "Backspace 或 Alt+← / Alt+→") to "后退、前进（鼠标侧键也行）",
            (if (mac) "⌘L" else "Ctrl+L、Alt+D 或 F4") to "在地址栏输入路径",
            "Tab" to "地址栏中补全当前一段",
            "Delete" to "地址栏中删除所选的最近记录",
            (if (mac) "⌘⇧E" else "Ctrl+Shift+E") to "钉出或收起目录图",
            "${primary}F" to "搜索",
            "F5" to "刷新",
            "${primary}A" to "全选",
            (if (mac) "⌘⌫" else "Delete") to "将所选条目移入回收站",
            (if (mac) "回车 或 F2" else "F2") to "重命名，选了几项时批量重命名",
            "${primary}Z" to "撤销上一次移动、删除、重命名或归档改动",
            "${primary}I" to "详情栏",
            "菜单键 或 Shift+F10" to "操作菜单",
        ),
        "传输" to listOf(
            "单击 / 双击" to "选中、执行这一项的主操作",
            "${primary.removeSuffix("+")} 点选 / Shift 点选" to "加选、连选",
            "${primary}A" to "全选眼前列出的任务",
            (if (mac) "⌘⌫ 或 Delete" else "Delete") to "删除所选",
            "Esc" to "取消选择",
        ),
        "标签页" to listOf(
            "${primary}T" to "新建标签页",
            "${primary}W" to "关闭标签页",
            "Ctrl+Tab / Ctrl+Shift+Tab" to "下一个、上一个标签页",
            "中键点文件夹" to "在后台的新标签页打开",
        ),
        "鼠标" to listOf(
            "单击 / 双击" to "选中、打开",
            "右键" to "操作菜单",
            "${primary.removeSuffix("+")} 点选 / Shift 点选" to "加选、连选",
            "在空白处拖动" to "框选",
            "把条目拖到文件夹上" to "移动（按着 ${if (mac) "⌥" else "Ctrl"} 是复制）",
        ),
        "播放器" to listOf(
            "空格 / K" to "播放、暂停",
            "← / → 或 J / L" to "后退、快进 10 秒",
            "按住 ← / →" to "快退、快进",
            "Shift+← / →" to "后退、快进 1 分钟",
            "0 至 9" to "跳到全片的对应成数处",
            "Home" to "回到开头",
            "↑ / ↓ 或滚轮" to "音量",
            "M" to "静音",
            "[ / ]" to "减速、加速",
            "Backspace" to "恢复原速",
            "C" to "开关字幕",
            "PageUp / PageDown" to "上一集、下一集",
            "F" to "全屏",
            "R" to "画面顺时针旋转 90 度",
        ),
    )
    PikoDialog(
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
                                // 两栏按比例分，不按内容撑：原先键位一栏只设了最小宽度，最长的「Backspace 或 Alt+← / Alt+→」
                                // 超出后那一行的说明往右错开。窄窗口里键位放不下时在键帽里折行
                                KeyCap(keys, Modifier.weight(0.4f))
                                Text(action, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f).padding(start = 12.dp))
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
