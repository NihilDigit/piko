package dev.piko.ui.screens.instant

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.PikoApplication
import dev.piko.shared.data.MagnetResolutionResult
import dev.piko.ui.components.PikoLoadingIndicator
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.theme.LocalFixedColors
import kotlinx.coroutines.launch

@Composable
fun InstantMagnetScreen(
    onSavedSuccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val instantRepo = PikoApplication.instance.instantMagnetRepository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    var magnetInput by remember { mutableStateOf("") }
    var isResolving by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var resolutionResult by remember { mutableStateOf<MagnetResolutionResult?>(null) }
    var selectedIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            PikoTopBar(title = "秒传与磁力解析")
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            // 磁力输入卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = magnetInput,
                        onValueChange = { magnetInput = it },
                        label = { Text("输入或粘贴 Magnet 磁力链接") },
                        placeholder = { Text("magnet:?xt=urn:btih:...") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.largeIncreased,
                        maxLines = 3,
                        trailingIcon = {
                            IconButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                                if (!clip.isNullOrBlank()) {
                                    magnetInput = clip.trim()
                                }
                            }) {
                                Icon(Icons.Outlined.ContentPaste, contentDescription = "Paste")
                            }
                        },
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            val magnet = magnetInput.trim()
                            if (magnet.startsWith("magnet:?xt=urn:btih:") || magnet.length == 40) {
                                isResolving = true
                                scope.launch {
                                    val formattedMagnet = if (!magnet.startsWith("magnet:")) "magnet:?xt=urn:btih:$magnet" else magnet
                                    val result = instantRepo.resolve(formattedMagnet)
                                    isResolving = false
                                    result.onSuccess { data ->
                                        if (data == null) {
                                            snackbarHost.showSnackbar("PikPak 未收录该资源，可直接添加为离线任务")
                                        } else {
                                            resolutionResult = data
                                            selectedIndices = data.items.mapIndexedNotNull { index, item ->
                                                if (item.isSelected) index else null
                                            }.toSet()
                                        }
                                    }.onFailure { err ->
                                        snackbarHost.showSnackbar("解析失败: ${err.localizedMessage}")
                                    }
                                }
                            } else {
                                scope.launch {
                                    snackbarHost.showSnackbar("请输入合法的 Magnet 磁力链接或 40 位哈希")
                                }
                            }
                        },
                        enabled = !isResolving && magnetInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        if (isResolving) {
                            PikoLoadingIndicator(size = 20.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("正在毫秒级探测云端索引...")
                        } else {
                            Icon(Icons.Outlined.Bolt, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("秒级解析 (约 150ms)")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 解析结果展示
            resolutionResult?.let { result ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = result.resource.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = LocalFixedColors.current.InstantMatchGreen,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "秒传命中率: ${result.instantReadyCount} / ${result.totalCount} 个文件",
                                style = MaterialTheme.typography.labelLarge,
                                color = LocalFixedColors.current.InstantMatchGreen,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 文件树勾选列表
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    itemsIndexed(result.items) { index, item ->
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
                                    .padding(vertical = 10.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        selectedIndices = if (checked) selectedIndices + index else selectedIndices - index
                                    },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.file.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = item.file.size.toReadableSize(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Surface(
                                    shape = MaterialTheme.shapes.extraSmall,
                                    color = if (item.isInstantReady) LocalFixedColors.current.InstantMatchGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                                ) {
                                    Text(
                                        text = if (item.isInstantReady) "秒传就绪" else "需离线",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (item.isInstantReady) LocalFixedColors.current.InstantMatchGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // 底部操作区
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = {
                            val readyItems = selectedIndices
                                .map { result.items[it] }
                                .filter { it.isInstantReady }
                            if (readyItems.isNotEmpty()) {
                                isSaving = true
                                scope.launch {
                                    val saveRes = instantRepo.instantSave(readyItems)
                                    isSaving = false
                                    saveRes.onSuccess {
                                        snackbarHost.showSnackbar("成功秒传 ${it.size} 个文件到云盘！")
                                        onSavedSuccess()
                                    }.onFailure {
                                        snackbarHost.showSnackbar("秒传失败: ${it.localizedMessage}")
                                    }
                                }
                            }
                        },
                        enabled = !isSaving && selectedIndices.any { result.items[it].isInstantReady },
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(Icons.Outlined.Bolt, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("一键秒传到网盘")
                    }

                    OutlinedButton(
                        onClick = {
                            isSaving = true
                            scope.launch {
                                val taskRes = instantRepo.enqueueOfflineTask(magnetInput.trim())
                                isSaving = false
                                taskRes.onSuccess {
                                    snackbarHost.showSnackbar("已加入云端离线任务队列！")
                                    onSavedSuccess()
                                }.onFailure {
                                    snackbarHost.showSnackbar("提交任务失败: ${it.localizedMessage}")
                                }
                            }
                        },
                        enabled = !isSaving,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("离线下载")
                    }
                }
            }
        }
    }
}
