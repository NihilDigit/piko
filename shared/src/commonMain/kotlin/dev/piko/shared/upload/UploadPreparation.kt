package dev.piko.shared.upload

enum class UploadPreparationCompletion { SUCCEEDED, PARTIAL, FAILED, UNCONFIRMED, STOPPED }
enum class UploadPreparationIssueKind { SOURCE_UNREADABLE, EMPTY_FOLDER, DIRECTORY_REFUSED, DIRECTORY_UNCONFIRMED, NOT_ATTEMPTED }
data class UploadPreparationSource(val key: String, val title: String, val folder: Boolean)
data class UploadPreparedFile(val sourceKey: String, val path: String, val taskId: String)
data class UploadPreparedDirectory(val sourceKey: String, val path: String, val id: String, val parentId: String)
data class UploadPreparationIssue(val sourceKey: String, val path: String, val kind: UploadPreparationIssueKind)
data class UploadPreparationResult(val completion: UploadPreparationCompletion, val message: String, val finishedAtMs: Long)
data class UploadEnqueued(val account: String, val count: Int, val preparationId: String)
sealed interface UploadEnqueueResult {
    data class Accepted(val preparationId: String) : UploadEnqueueResult
    data class Rejected(val reason: String) : UploadEnqueueResult
}

/** 准备和部分结果独立于页面；只保存来源摘要、相对路径和实际回执，不保存本地 URI。 */
data class UploadPreparation(val id: String, val account: String, val parentId: String, val parentName: String,
    val selectedCount: Int, val createdAtMs: Long, val sources: List<UploadPreparationSource> = emptyList(),
    val files: List<UploadPreparedFile> = emptyList(), val directories: List<UploadPreparedDirectory> = emptyList(),
    val issues: List<UploadPreparationIssue> = emptyList(), val result: UploadPreparationResult? = null) {
    val isRunning: Boolean get() = result == null
    val hasUnconfirmedDirectory: Boolean get() = issues.any { it.kind == UploadPreparationIssueKind.DIRECTORY_UNCONFIRMED }
    val hasSideEffects: Boolean get() = files.isNotEmpty() || directories.isNotEmpty() || hasUnconfirmedDirectory
    val blockedSources: Set<String> get() = if (isRunning) sources.mapTo(HashSet()) { it.key } else
        files.mapTo(HashSet()) { it.sourceKey } + directories.map { it.sourceKey } +
            issues.filter { it.kind == UploadPreparationIssueKind.DIRECTORY_UNCONFIRMED }.map { it.sourceKey }
}
