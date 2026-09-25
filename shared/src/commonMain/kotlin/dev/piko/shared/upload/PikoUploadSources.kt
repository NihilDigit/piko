package dev.piko.shared.upload

import kotlinx.io.RawSource

/** 一个待上传的本机文件在此刻的样子。续传前拿它与任务里记下的比对，判断文件是否改过。 */
data class UploadSourceInfo(val name: String, val size: Long, val lastModifiedMs: Long)

/** 选中的文件夹展开后的一个文件。[relativeDir] 是它的上级目录相对所选文件夹的路径，以 / 分隔，顶层为空串。 */
data class UploadTreeEntry(val uri: String, val relativeDir: String)

/** 选中的文件夹：名字与其下全部文件（递归）。空文件夹不在其中，也就不会在网盘里建出来。 */
data class UploadFolder(val name: String, val files: List<UploadTreeEntry>)

/**
 * 读本机文件的平台边界，上传调度器不接触 java.io.File 或 ContentResolver。
 *
 * uri 由平台自己产生与解释：桌面端是绝对路径，Android 是 content: URI。调度器只把它原样存进
 * 任务表，下次启动再交回来。调用都发生在 IO 线程上，实现可以阻塞。
 */
interface PikoUploadSources {
    /** 文件已不存在或已无权读取时为 null。 */
    fun describe(uri: String): UploadSourceInfo?

    /** 从 [offset] 起恰好 [length] 字节。CID 只读三段 20 KB，要随机读，顺序流得先读过文件的三分之二。 */
    fun readAt(uri: String, offset: Long, length: Int): ByteArray

    /** 从 [offset] 起到文件末尾的内容。续传从缺的第一个分片读起，同样要能直接定位。 */
    fun open(uri: String, offset: Long): RawSource

    /** 无法读取时为 null。 */
    fun listFolder(uri: String): UploadFolder?

    /**
     * 不再需要读 [uri] 了：任务完成或被移除，且没有别的任务用它。Android 在这里交还选文件时
     * 取得的持久授权，一个应用能持有的持久授权有上限，只取不还迟早选不了文件。
     */
    fun release(uri: String) {}
}

/** 一次上传请求选中的东西：若干文件与若干文件夹，都是 [PikoUploadSources] 认得的 uri。 */
data class UploadSelection(val files: List<String> = emptyList(), val folders: List<String> = emptyList()) {
    val isEmpty: Boolean get() = files.isEmpty() && folders.isEmpty()
    val count: Int get() = files.size + folders.size
}
