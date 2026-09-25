package dev.piko.ui.screens.archive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.state.ArchiveExtractSession
import dev.piko.shared.state.ArchiveJobStatus

/**
 * 加密压缩包的密码框。放在一直在组合里的地方：离开网盘页后密码框仍要能弹出，
 * 否则加密包会一直停在待输入。
 */
@Composable
fun ArchiveExtractHost(session: ArchiveExtractSession) {
    val job = session.passwordPrompt ?: return
    val saved by session.savedPasswords.collectAsStateWithLifecycle()
    ArchivePasswordDialog(
        job = job,
        savedPasswords = saved,
        onSubmit = { session.submitPassword(job.id, it) },
        onSkip = { session.skip(job.id) },
    )
}

/**
 * 解压进行中的状态条：当前压缩包的名字与进度，排队的只计数。队列空时不占位。
 * 完成与失败不在这里显示，经 session.messages 以 Snackbar 提示。
 */
@Composable
fun ArchiveExtractStatus(session: ArchiveExtractSession, modifier: Modifier = Modifier) {
    val jobs = session.jobs
    val current = jobs.firstOrNull { it.status !is ArchiveJobStatus.NeedsPassword } ?: jobs.firstOrNull() ?: return
    val status = current.status
    val others = jobs.size - 1
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (status is ArchiveJobStatus.NeedsPassword) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
            } else {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = current.file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(
                            when (status) {
                                ArchiveJobStatus.Waiting, ArchiveJobStatus.Submitting -> "正在提交"
                                is ArchiveJobStatus.Extracting -> "正在解压 ${status.progress}%"
                                is ArchiveJobStatus.NeedsPassword -> "需要密码"
                            },
                        )
                        if (others > 0) append("，另有 $others 个待解压")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (status is ArchiveJobStatus.Extracting) {
                    LinearProgressIndicator(
                        progress = { status.progress / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }
        }
    }
}
