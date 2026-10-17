package dev.piko.shared.state

import dev.piko.shared.data.InstantSaveReceipt
import dev.piko.shared.data.PikoPathBreadcrumb
import io.github.nihildigit.pikpak.ResolvedFile

data class InstantSaveAttemptOutcome(val id: String, val account: String, val title: String, val message: String)

/** 保存尝试的运行期记录；已发布的成功历史格式保持不变。 */
data class InstantSaveAttempt(
    val id: String,
    val account: String,
    val title: String,
    val target: PikoPathBreadcrumb,
    val files: List<ResolvedFile>,
    val createdAtMs: Long,
    val containerName: String? = null,
    val names: Map<String, String> = emptyMap(),
    val keepStructure: Boolean = false,
    val reference: Boolean = false,
    val receipt: InstantSaveReceipt? = null,
    val finishedAtMs: Long? = null,
) {
    val isRunning: Boolean get() = finishedAtMs == null
    val isComplete: Boolean get() = !isRunning && receipt?.let {
        !it.requiresInspection && it.failed.isEmpty() && it.notAttempted.isEmpty() &&
            it.confirmed.keys.containsAll(files.filter { file -> file.gcid != null }.map { file -> file.path })
    } == true
    val locateId: String? get() = receipt?.folders?.get("")?.takeIf { it.isNotEmpty() }
        ?: receipt?.confirmed?.values?.firstOrNull() ?: target.id.takeIf { it.isNotEmpty() }
}
