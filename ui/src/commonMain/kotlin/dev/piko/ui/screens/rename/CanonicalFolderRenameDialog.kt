package dev.piko.ui.screens.rename

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.ScanStop
import dev.piko.shared.rename.BatchRenameState
import dev.piko.shared.rename.BatchRenameState.Phase
import dev.piko.shared.rename.CanonicalTreeScan
import dev.piko.shared.rename.RenameRow
import dev.piko.shared.rename.splitExtension
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm

/**
 * 按番号规范命名一个文件夹：在批量重命名的对话框里先扫描这棵树（[CanonicalTreeScan]），扫完就是批量重命名的预览，
 * 每一项是树里的一个文件或文件夹，新名称按资源文件夹的规则算好（[BatchRenameState] 的一棵树）。
 * 执行、进度、停止与整批一条撤销都是批量重命名的。
 *
 * 扫描与预览同在这一个对话框里，关掉即放弃，与批量重命名相同；扫描中可以提前停止，已扫到的部分照常进预览。
 * 曾经做成查找重复那样的进程级会话，宽窗口开后台标签、窄窗口占底部 sheet，结果页是网盘里的一个位置；改名只是换个名字，
 * 不需要缩略图与属性来挑，那一套选中、结果页与离开确认对它太重。
 */
@Composable
fun CanonicalFolderRenameDialog(
    root: PikoPathBreadcrumb,
    onDismiss: () -> Unit,
    onFinished: (message: String) -> Unit,
) {
    val services = LocalPikoServices.current
    val (memory, textMode) = rememberRenameMemory() ?: return
    val scope = rememberCoroutineScope()
    val scan = remember(root.id) { CanonicalTreeScan(services.clientManager, services.driveRepository, scope, root) }
    val title = "按番号规范命名「${root.name}」"
    val tree = scan.tree
    if (tree == null) {
        BatchRenameWindow(onDismiss) { _, fullscreen -> ScanContent(scan, title, onDismiss, fullscreen) }
        return
    }
    val state = remember(tree) {
        BatchRenameState(
            services.driveRepository, services.preferences, scope, scan.files, memory, textMode,
            metaTube = services.metaTube, tree = tree,
        )
    }
    CloseWhenFinished(state, onDismiss, onFinished)
    val dismiss = { if (state.phase != Phase.RUNNING) onDismiss() }
    BatchRenameWindow(dismiss) { twoPane, fullscreen ->
        BatchRenameContent(state, title, dismiss, twoPane, fullscreen, treeSummary = scanSummary(scan))
    }
}

/** 扫描中与扫描失败时对话框里的样子：进度在中间，底栏是停止与取消。 */
@Composable
private fun ScanContent(scan: CanonicalTreeScan, title: String, onClose: () -> Unit, fullscreen: Boolean) {
    val colors = MaterialTheme.colorScheme
    val failed = scan.phase == CanonicalTreeScan.Phase.FAILED
    Column(modifier = Modifier.fillMaxSize()) {
        RenameTopBar(title, onClose, fullscreen)
        Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (failed) {
                    Text("扫描失败", style = MaterialTheme.typography.titleMedium)
                    Text(scan.errorMessage.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                } else {
                    LinearProgressIndicator(Modifier.widthIn(max = 240.dp).fillMaxWidth())
                    Text("正在扫描", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "已扫描 ${scan.scannedFolders} 个文件夹、${scan.scannedFiles} 个文件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        ) {
            if (!fullscreen) TextButton(onClick = onClose) { Text("取消") }
            if (failed) {
                TextButton(onClick = scan::start) { Text("重试") }
            } else {
                // 停止不丢已扫到的部分，文案据此写明
                TextButton(onClick = scan::stop) { Text("停止并查看结果") }
            }
        }
    }
}

/**
 * 一棵树在规则区的位置上写什么：扫描的范围，片名的来源，与「仅显示变更项」。没有查找替换可调，
 * 规范名之外要改的逐项手改。
 */
@Composable
internal fun CanonicalTreeOptions(
    state: BatchRenameState,
    summary: String,
    enabled: Boolean,
    changedOnly: Boolean,
    onChangedOnlyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "有冲突的项默认不勾选。可取消勾选不想改的项，或点行尾的按钮单独修改新名称",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column {
            // 只在用户配了 MetaTube 时出现
            if (state.metaTubeAvailable) {
                CheckboxRow("片名取自 MetaTube", state.useMetaTubeTitles, enabled, onChange = state::updateUseMetaTubeTitles)
            }
            CheckboxRow("仅显示变更项", changedOnly, enabled = true, onChange = onChangedOnlyChange)
        }
    }
}

/** 手改一项的新名称。打开时选中主名，照重命名对话框的习惯，扩展名多半不改。 */
@Composable
internal fun EditNewNameDialog(row: RenameRow, overridden: Boolean, onConfirm: (String?) -> Unit, onDismiss: () -> Unit) {
    var value by remember(row) {
        val stem = splitExtension(row.newName, row.source.isFolder).first
        mutableStateOf(TextFieldValue(row.newName, selection = TextRange(0, stem.length)))
    }
    PikoDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改新名称") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("原名：${row.source.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { PikoDialogConfirm("确定", { onConfirm(value.text) }, enabled = value.text.isNotBlank()) },
        dismissButton = {
            Row {
                if (overridden) TextButton(onClick = { onConfirm(null) }) { Text("恢复规范名") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

private fun scanSummary(scan: CanonicalTreeScan): String = buildString {
    append("已扫描 ${scan.scannedFolders} 个文件夹、${scan.scannedFiles} 个文件")
    when (scan.scanStop) {
        ScanStop.CANCELLED -> append("，扫描已停止")
        ScanStop.FOLDER_LIMIT, ScanStop.FILE_LIMIT -> append("，已达扫描上限")
        ScanStop.TIMEOUT -> append("，扫描超时")
        null -> Unit
    }
    if (scan.failedFolders > 0) append("，${scan.failedFolders} 个文件夹读取失败")
}
