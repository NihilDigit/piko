package dev.piko.ui.screens.drive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.state.ArchiveExtractSession
import dev.piko.shared.state.ArchiveJobStatus
import dev.piko.shared.state.FolderVaultSession
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.screens.archive.ArchiveExtractStatus

/**
 * 网盘页底部的后台工作：服务端解压、归档与取消归档。只有一项时照原样画那一条；多于一项时收成一行摘要，
 * 点开才逐条列出，各条的停止与进度不变。
 *
 * 原来三条各占一行叠在底部，几样同时在跑时吃掉列表小半个屏幕。Fluent 的进度指引是同类的进程合成一条汇总，
 * 细节放进行内展开的抽屉；这里照做。
 */
@Composable
internal fun BackgroundActivityStrip(archive: ArchiveExtractSession, vault: FolderVaultSession, modifier: Modifier = Modifier) {
    val jobs = archive.jobs
    val archiving = vault.progress
    val restoring = vault.restoreProgress
    val parts = buildList {
        if (jobs.isNotEmpty()) add(if (jobs.size == 1) "解压 1 个压缩包" else "解压 ${jobs.size} 个压缩包")
        archiving?.let { add("归档「${it.folderName}」") }
        restoring?.let { add("取消归档「${it.folderName}」") }
    }
    val strips: List<@Composable () -> Unit> = buildList {
        if (jobs.isNotEmpty()) add { ArchiveExtractStatus(archive, Modifier.fillMaxWidth()) }
        if (archiving != null) add { VaultFolderStatus(vault, Modifier.fillMaxWidth()) }
        if (restoring != null) add { VaultRestoreStatus(vault, Modifier.fillMaxWidth()) }
    }
    when (strips.size) {
        0 -> return
        1 -> {
            Column(modifier) { strips.single()() }
            return
        }
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    // 加密包等着输入密码时摘要也要说出来：收起的一行里看不到那把锁，人就不知道它停着
    val waitingForPassword = jobs.any { it.status is ArchiveJobStatus.NeedsPassword }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier
                    .clickable(onClickLabel = if (expanded) "收起" else "展开") { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (waitingForPassword) {
                    Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                } else {
                    InlineLoadingIndicator()
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("${strips.size} 项在后台进行", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = (if (waitingForPassword) listOf("有压缩包需要密码") + parts else parts).joinToString("，"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
            }
        }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { strips.forEach { it() } }
        }
    }
}
