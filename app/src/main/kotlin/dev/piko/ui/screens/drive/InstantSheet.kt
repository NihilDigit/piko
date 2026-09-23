package dev.piko.ui.screens.drive

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import dev.piko.data.repository.FileNameSanitizer
import dev.piko.shared.data.InstantFileItem
import dev.piko.shared.data.MagnetResolutionResult
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.PikoApplication
import dev.piko.data.repository.PathBreadcrumb
import dev.piko.ui.components.FolderPickerDialog
import dev.piko.ui.components.PikoLoadingIndicator
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.theme.LocalFixedColors
import dev.piko.shared.state.mainContentIndices
import io.github.nihildigit.pikpak.ResolvedFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val AUTO_RESOLVE_DEBOUNCE_MS = 350L

/**
 * 输入框里的内容归一化成可解析的磁力链，不像磁力链就返回 null，不解析也不报错。
 *
 * 只粘 infohash 的情况不少，所以补全一条磁力链；但限定 40 位十六进制，否则随手敲的
 * 任意长串都会发一次请求。
 */
private fun normalizeMagnet(raw: String): String? {
    val trimmed = raw.trim()
    return when {
        trimmed.startsWith("magnet:?xt=urn:btih:") -> trimmed
        trimmed.length == 40 && trimmed.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } ->
            "magnet:?xt=urn:btih:$trimmed"
        else -> null
    }
}

/** 保存位置胶囊。目标还没取到时不可点，也不拿 My Packs 顶替，免得闪一个可能是错的名字。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstantTargetChip(
    target: PathBreadcrumb?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled && target != null,
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (target == null) "正在确认保存位置" else "目标：${target.name}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (target != null) {
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = "更换保存位置",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

/**
 * 嵌入在 BottomSheet 里的秒传与磁力确认工作台
 * 粘上磁力链自动解析，保存目标可点胶囊更换并被记住，未配置过时默认 My Packs
 *
 * 这里只保存、不导航：目标目录随回调交给调用方，由它经状态类切过去，顺带清掉
 * 搜索与选中。自己调仓库切目录会绕过这一步。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstantSheetContent(
    initialMagnet: String = "",
    onDismiss: () -> Unit,
    onSuccess: (createdIds: List<String>, targetBread: PathBreadcrumb) -> Unit,
    onOfflineTaskCreated: (targetBread: PathBreadcrumb) -> Unit,
) {
    val driveRepo = PikoApplication.instance.driveRepository
    val instantRepo = PikoApplication.instance.instantMagnetRepository
    val sessionManager = PikoApplication.instance.sessionManager
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var magnetInput by remember { mutableStateOf(initialMagnet) }
    var showTargetPicker by remember { mutableStateOf(false) }
    var isResolving by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var resolutionResult by remember { mutableStateOf<MagnetResolutionResult?>(null) }
    var items by remember { mutableStateOf<List<InstantFileItem>>(emptyList()) }
    var selectedIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var targetBreadcrumb by remember { mutableStateOf<PathBreadcrumb?>(null) }
    var targetNotice by remember { mutableStateOf<String?>(null) }

    // 勾选项里只要有一项云端没收录，整单就走离线，不做「能秒传的先秒传、其余离线」。
    // createUrlFile 只收整条磁力 URL，ResolvedFile 也不带文件索引，离线任务没法只取
    // 选中的那几个；两者并用会把刚秒传的文件再下一遍，目录里留下重复项。取舍是用户
    // 定的：宁可放弃那几项的秒传，也不要重复。
    val selectedItems = selectedIndices.sorted().map { items[it] }
    val canInstantSaveAll = selectedItems.isNotEmpty() && selectedItems.all { it.isInstantReady }

    // 只在这次保存真的会建目录时才让人改名字。走离线那条路目录是 PikPak 自己建的，摆一个
    // 可编辑的名字只会让人以为能生效。
    val willCreateFolder = canInstantSaveAll && selectedItems.size > 1
    var folderNameInput by remember { mutableStateOf("") }
    val resourceName = resolutionResult?.resource?.name
    // 换一条磁力链要重新预填，否则输入框里留着上一条资源的名字
    LaunchedEffect(resourceName) {
        folderNameInput = resourceName?.let { FileNameSanitizer.sanitize(it) }.orEmpty()
    }

    // 外部分享进来的磁力链已经在用户手上，输入框只是让他把同一件事再确认一遍，所以收起。
    // 解析失败时再放出来：否则他既看不到那串链接，也没法改、没法重试。
    var showMagnetEditor by remember { mutableStateOf(initialMagnet.isBlank()) }

    // 归一化后的磁力链兼作解析的触发键：同一条链不会重复解析，改成别的链会取消上一次。
    // 非磁力的输入（http 直链、ed2k）在这里是 null，自动解析不触发，由按钮手动提交。
    val pendingMagnet = remember(magnetInput) { normalizeMagnet(magnetInput) }
    var resolveTrigger by remember { mutableStateOf(0) }

    // 记住过的目标优先；没配置过才退回 My Packs。只取一次而不是持续收集，
    // 否则用户在本次会话里改完目标，写回 DataStore 的那次发射会再盖一遍。
    suspend fun resolveTarget(): PathBreadcrumb {
        val saved = sessionManager.instantTargetFlow.first()
        if (saved != null) {
            // 记下的目录可能已经被删或进了回收站。不验的话要等保存时才暴露，报的还是
            // 一句原始 API 错误。根目录是空 id，没有对应的 FileDetail，不验。
            val alive = saved.folderId.isEmpty() ||
                driveRepo.getFileDetail(saved.folderId).map { !it.trashed }.getOrDefault(false)
            if (alive) return PathBreadcrumb(saved.folderId, saved.folderName)
            targetNotice = "原保存目标已不存在，已切换到 My Packs"
        }
        return driveRepo.getOrCreateMyPacksFolder().getOrDefault(PathBreadcrumb("", "My Packs"))
    }

    LaunchedEffect(Unit) {
        targetBreadcrumb = resolveTarget()
    }

    // 把当前输入整条交给云端离线任务。磁力以外的链接只有这一条路：createUrlFile 收任意
    // URL，但 resolveMagnet 只认磁力，所以这些输入不会有文件列表可勾。
    fun submitOfflineTask() {
        isSaving = true
        scope.launch {
            val targetBread = targetBreadcrumb ?: resolveTarget()
            instantRepo.enqueueOfflineTask(magnetInput.trim(), targetBread.id)
                .onSuccess { onOfflineTaskCreated(targetBread) }
                .onFailure { errorMsg = "保存失败: ${it.localizedMessage}" }
            isSaving = false
        }
    }

    // 解析的唯一实现。外部唤起、手动粘贴、按钮重试都走这里：resolveTrigger 让按钮能对
    // 同一条链再来一次，key 不变时不会重复解析。
    LaunchedEffect(pendingMagnet, resolveTrigger) {
        resolutionResult = null
        items = emptyList()
        selectedIndices = emptySet()
        errorMsg = null
        if (pendingMagnet == null) return@LaunchedEffect
        // 防抖。粘贴一次就是一条完整的链，等待只为压掉手敲时中途的半条链接，所以取短值。
        delay(AUTO_RESOLVE_DEBOUNCE_MS)
        isResolving = true
        try {
            instantRepo.resolve(pendingMagnet)
                .onSuccess { data ->
                    if (data == null) {
                        errorMsg = "PikPak 索引暂未收录该资源，可直接提交云端离线任务"
                        showMagnetEditor = true
                    } else {
                        resolutionResult = data
                        items = data.items
                        // 与网盘列表的启发式折叠同一套判据：剔掉 sample/subs 这类次要目录里的
                        // 文件，再按最大文件的十分之一卡一道门槛。用户仍可手改。
                        selectedIndices = mainContentIndices(
                            data.items.map { it.file.path },
                            data.items.map { it.file.size },
                        )
                    }
                }
                .onFailure { err ->
                    errorMsg = "解析失败: ${err.localizedMessage}"
                    showMagnetEditor = true
                }
        } finally {
            // 取消也要走到这里，否则换链后指示器会一直转
            isResolving = false
        }
    }

    // 外部唤起的链不合法时自动解析不会发生，而输入框又是收起的，不兜住就是一个空 Sheet。
    LaunchedEffect(initialMagnet) {
        if (initialMagnet.isNotBlank() && normalizeMagnet(initialMagnet) == null) {
            errorMsg = "这不是一条可解析的磁力链接，可直接提交云端离线任务"
            showMagnetEditor = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "秒传与磁力直通",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "毫秒级探测云端秒传与离线下载",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (showMagnetEditor) {
            // 磁力输入框
            OutlinedTextField(
                value = magnetInput,
                onValueChange = { magnetInput = it },
                label = { Text("磁力链接或下载地址") },
                placeholder = { Text("magnet:?xt=urn:btih:... 或 http://...") },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.largeIncreased,
                maxLines = 2,
                trailingIcon = {
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                        if (!clip.isNullOrBlank()) magnetInput = clip.trim()
                    }) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = "从剪贴板粘贴")
                    }
                },
            )

            // 已经出结果时按钮没有可触发的东西。解析中与解析失败都留着，否则没有重试手段。
            if (resolutionResult == null) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { if (pendingMagnet != null) resolveTrigger++ else submitOfflineTask() },
                    enabled = !isResolving && !isSaving && magnetInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    if (isResolving) {
                        PikoLoadingIndicator(size = 20.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("正在毫秒级探测云端索引...")
                    } else {
                        Icon(
                            imageVector = if (pendingMagnet != null) Icons.Outlined.Bolt else Icons.Outlined.CloudDownload,
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (pendingMagnet != null) "重新解析磁力资源" else "提交离线下载")
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InstantTargetChip(
                target = targetBreadcrumb,
                enabled = !isSaving,
                onClick = { showTargetPicker = true },
            )
            // 外部那条路没有解析按钮，进度只能落在这里
            if (isResolving && !showMagnetEditor) {
                Spacer(modifier = Modifier.width(10.dp))
                PikoLoadingIndicator(size = 16.dp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "正在探测云端索引...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        targetNotice?.let { notice ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        errorMsg?.let { err ->
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // 解析成功展示文件确认界面
        resolutionResult?.let { result ->
            Spacer(modifier = Modifier.height(14.dp))

            // 资源标题卡片。多文件秒传会以这个名字建一层目录，所以这一行本身就是那个
            // 目录名，直接在原地改，不另起一个输入框。
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (willCreateFolder) {
                        val isBlank = folderNameInput.isBlank()
                        val nameColor = if (isBlank) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                        BasicTextField(
                            value = folderNameInput,
                            onValueChange = { folderNameInput = it },
                            enabled = !isSaving,
                            textStyle = MaterialTheme.typography.titleMedium.copy(color = nameColor),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                if (isBlank) {
                                    Text(
                                        text = "目录名不能为空",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                inner()
                            },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = "修改新建目录的名称",
                            tint = nameColor,
                            modifier = Modifier.size(16.dp),
                        )
                    } else {
                        Text(
                            text = result.resource.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 文件选择控制栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "待保存文件 (${selectedIndices.size} / ${items.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(
                    onClick = {
                        selectedIndices = if (selectedIndices.size == items.size) emptySet() else items.indices.toSet()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(if (selectedIndices.size == items.size) "全不选" else "全选")
                }
            }

            // 文件列表（可滚动）
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                itemsIndexed(items) { index, item ->
                    val isChecked = selectedIndices.contains(index)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedIndices = if (isChecked) selectedIndices - index else selectedIndices + index
                            },
                        color = if (isChecked) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { chk ->
                                    selectedIndices = if (chk) selectedIndices + index else selectedIndices - index
                                },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.file.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = item.file.size.toReadableSize(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            Icon(
                                imageVector = if (item.isInstantReady) Icons.Outlined.Check else Icons.Outlined.ErrorOutline,
                                contentDescription = if (item.isInstantReady) "云端已有，可秒传" else "云端没有，需下载",
                                tint = if (item.isInstantReady) {
                                    LocalFixedColors.current.InstantMatchGreen
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            suspend fun runInstantSave(target: PathBreadcrumb, toSave: List<InstantFileItem>) {
                // 多个文件平铺进目标目录会把它和别的资源混在一起，先建一层再存。名字取自
                // 输入框，仍要过一遍 sanitize：用户可能敲进 / : * 这类建不出来的字符。
                val saveTarget = if (toSave.size > 1) {
                    val folderName = FileNameSanitizer.sanitize(folderNameInput)
                    val folderId = driveRepo.createNewFolder(target.id, folderName).getOrElse { err ->
                        isSaving = false
                        errorMsg = "新建文件夹失败: ${err.localizedMessage}"
                        return
                    }
                    PathBreadcrumb(folderId, folderName)
                } else {
                    target
                }
                val saveRes = instantRepo.instantSave(toSave, saveTarget.id)
                isSaving = false
                saveRes
                    .onSuccess { createdIds -> onSuccess(createdIds, saveTarget) }
                    .onFailure { errorMsg = "保存失败: ${it.localizedMessage}" }
            }

            Button(
                onClick = {
                    isSaving = true
                    scope.launch {
                        val targetBread = targetBreadcrumb ?: resolveTarget()
                        if (canInstantSaveAll) {
                            runInstantSave(targetBread, selectedItems)
                        } else {
                            val taskRes = instantRepo.enqueueOfflineTask(magnetInput.trim(), targetBread.id)
                            isSaving = false
                            taskRes
                                .onSuccess { onOfflineTaskCreated(targetBread) }
                                .onFailure { errorMsg = "保存失败: ${it.localizedMessage}" }
                        }
                    }
                },
                enabled = !isSaving && selectedItems.isNotEmpty() && targetBreadcrumb != null &&
                    !(willCreateFolder && folderNameInput.isBlank()),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                if (isSaving) {
                    PikoLoadingIndicator(size = 18.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("保存中...")
                } else {
                    Icon(
                        imageVector = if (canInstantSaveAll) Icons.Outlined.Bolt else Icons.Outlined.CloudDownload,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("保存 ${selectedItems.size} 项到 ${targetBreadcrumb?.name.orEmpty()}")
                }
            }
        } ?: run {
            // 云端没有收录时，磁力本身仍然可以直接交给离线下载。非磁力的输入不在这里出口：
            // 顶部那个按钮已经是它唯一的提交入口，两个一样的按钮只会让人犹豫点哪个。
            if (pendingMagnet != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { submitOfflineTask() },
                    enabled = !isSaving && !isResolving && targetBreadcrumb != null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    if (isSaving) {
                        PikoLoadingIndicator(size = 18.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("保存中...")
                    } else {
                        Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("保存到 ${targetBreadcrumb?.name.orEmpty()}")
                    }
                }
            }
        }
    }

    if (showTargetPicker) {
        FolderPickerDialog(
            title = "选择保存位置",
            confirmLabel = "存到这里",
            onDismiss = { showTargetPicker = false },
            onConfirm = { targetId, targetName ->
                showTargetPicker = false
                targetNotice = null
                targetBreadcrumb = PathBreadcrumb(targetId, targetName)
                scope.launch { sessionManager.saveInstantTarget(targetId, targetName) }
            },
        )
    }
}
