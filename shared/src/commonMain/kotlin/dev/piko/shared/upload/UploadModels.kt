package dev.piko.shared.upload

import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.TaskPhase
import io.github.nihildigit.pikpak.UploadSession
import kotlinx.serialization.Serializable

/**
 * 上传还没完成的文件。开始上传时服务端就建好了它，在传完或放弃之前一直列在目录里，
 * 内容不完整：打不开、下不了，交给解压服务会被回以 file not complete（2026-09-25 实测）。
 */
val FileStat.isUploading: Boolean get() = !isFolder && phase == TaskPhase.PENDING

@Serializable
enum class UploadStatus {
    /** 排队等前面的任务传完。 */
    QUEUED,

    /** 查 CID 或整份计算 gcid。 */
    HASHING,
    UPLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    ;

    /** 还在调度器手里：排队或正在传。暂停与失败要用户再点一次。 */
    val isActive: Boolean get() = this == QUEUED || this == HASHING || this == UPLOADING
}

@Serializable
data class UploadTask(
    val taskId: String,
    /** 任务属于哪个账号。只跑、只列当前账号的，换号后留着等换回来。 */
    val account: String,
    /** [PikoUploadSources] 认得的 uri。 */
    val sourceUri: String,
    val fileName: String,
    val size: Long,
    /** 入队时源文件的修改时间，续传前据此判断文件是否改过。 */
    val lastModifiedMs: Long,
    val parentId: String,
    /** 目标目录的名字，只用于展示。 */
    val parentName: String,
    val status: UploadStatus = UploadStatus.QUEUED,
    /** 算好后记下，暂停后继续不必再读一遍整个文件。 */
    val gcid: String? = null,
    /** 真传时的 OSS 会话，带着 12 小时有效的凭据。完成或放弃后清掉。 */
    val session: UploadSession? = null,
    /** 已校验或已上传的字节数，视 [status] 而定。 */
    val processedBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val errorMessage: String? = null,
    /** 完成后网盘里的文件 id。 */
    val fileId: String? = null,
    /** 服务端已有同样内容，没有传字节。 */
    val isInstant: Boolean = false,
    val createdAtMs: Long = 0L,
    /** 排队次序。继续与重试排到队尾，但列表里的位置仍按 [createdAtMs]，不跟着跳。 */
    val queuedAtMs: Long = createdAtMs,
) {
    val progress: Float
        get() = if (size > 0) (processedBytes.toFloat() / size).coerceIn(0f, 1f) else 0f
}
