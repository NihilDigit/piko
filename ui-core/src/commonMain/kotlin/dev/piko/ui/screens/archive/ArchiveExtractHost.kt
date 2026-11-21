package dev.piko.ui.screens.archive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.state.ArchiveExtractSession

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
