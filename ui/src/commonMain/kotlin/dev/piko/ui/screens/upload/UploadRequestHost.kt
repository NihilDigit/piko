package dev.piko.ui.screens.upload

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.state.SaveTargetContext
import dev.piko.shared.presentation.ActionAvailability
import dev.piko.shared.upload.UploadEnqueueResult
import dev.piko.ui.LocalPikoServices
import dev.piko.shared.upload.PikoUploadCoordinator
import dev.piko.shared.upload.UploadSelection
import dev.piko.ui.components.FolderPickerDialog

/**
 * 应用外进来的上传（系统分享、拖进窗口）先问传到哪里。默认是网盘页眼下所在的目录：
 * 拖进窗口时用户多半正看着它。
 */
@Composable
fun UploadRequestHost(uploads: PikoUploadCoordinator, saveTarget: SaveTargetContext) {
    val selection by uploads.pendingRequest.collectAsStateWithLifecycle()
    val request = selection ?: return
    val services = LocalPikoServices.current
    val shownAccount = services.clientManager.currentClient.collectAsStateWithLifecycle().value?.account
    // 每个请求重新取默认目标，上一次在这里改过的目标不带到下一次
    val current = remember(request, shownAccount) { saveTarget.currentFolder() }
    var target by remember(request, shownAccount) { mutableStateOf(current.id to current.name) }
    var isPickingTarget by remember(request, shownAccount) { mutableStateOf(false) }
    var rulesVersion by remember { mutableIntStateOf(0) }
    var rejection by remember(request, shownAccount) { mutableStateOf<String?>(null) }
    val preparations by uploads.preparations.collectAsStateWithLifecycle()
    val restoring by uploads.isRestoring.collectAsStateWithLifecycle()
    LaunchedEffect(saveTarget) { saveTarget.actionChanges.collect { rulesVersion++ } }
    val blocked = remember(target, rulesVersion, saveTarget, preparations, restoring, shownAccount, request) {
        (saveTarget.availability(target.first) as? ActionAvailability.Blocked)?.reason ?: uploads.preparationBlockReason(request, target.first, preparations)
    }

    if (isPickingTarget) {
        FolderPickerDialog(
            title = "上传到",
            confirmLabel = "选择此目录",
            onDismiss = { isPickingTarget = false },
            onConfirm = { id, name ->
                target = id to name
                rejection = null
                isPickingTarget = false
            },
        )
        return
    }

    PikoDialog(
        onDismissRequest = uploads::clearRequest,
        title = { Text("上传 ${request.describe()}") },
        text = {
            Column {
                Text("保存到", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Folder, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = target.second,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { isPickingTarget = true }) { Text("更改") }
                }
                (blocked ?: rejection)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            PikoDialogConfirm(
                label = "上传",
                enabled = blocked == null,
                onClick = {
                    val currentBlock = saveTarget.availability(target.first) as? ActionAvailability.Blocked
                    if (services.driveRepository.currentAccount != shownAccount) rejection = "账号已改变，请重新选择上传目标"
                    else if (currentBlock != null) rejection = currentBlock.reason else {
                        when (val accepted = uploads.enqueue(request, target.first, target.second)) {
                            is UploadEnqueueResult.Accepted -> uploads.clearRequest()
                            is UploadEnqueueResult.Rejected -> rejection = accepted.reason
                        }
                    }
                },
            )
        },
        dismissButton = { TextButton(onClick = uploads::clearRequest) { Text("取消") } },
    )
}

private fun UploadSelection.describe(): String = when {
    folders.isEmpty() -> "${files.size} 个文件"
    files.isEmpty() -> "${folders.size} 个文件夹"
    else -> "$count 项"
}
