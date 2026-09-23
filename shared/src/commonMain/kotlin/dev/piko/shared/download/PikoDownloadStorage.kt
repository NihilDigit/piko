package dev.piko.shared.download

/**
 * 下载落盘的平台边界。调度器不接触 Context 或 java.io.File。
 *
 * 写入由 SDK 的 downloadTo 完成：它只接受文件系统路径，按顺序追加写入，文件长度即续传点。
 * 所以存储层只负责给出写入路径和完成后的交付，不再自己搬运字节。
 */
interface PikoDownloadStorage {
    /** 完成后文件所在的位置，用于展示与播放。 */
    fun pathFor(fileName: String): String

    /**
     * 下载过程中写入的本地文件。普通目录下就是最终文件；Android 的 SAF 目录没有文件系统
     * 路径，返回应用私有目录里的暂存文件，写满后由 [commit] 复制过去。
     */
    suspend fun downloadTarget(fileName: String): String

    /** 把写满的 [downloadedPath] 交付到最终位置，返回最终路径。 */
    suspend fun commit(fileName: String, downloadedPath: String): String

    /** 已落盘的字节数：完成的文件取最终文件长度，未完成的取暂存文件长度。 */
    suspend fun existingLength(fileName: String): Long
    suspend fun exists(fileName: String): Boolean
    suspend fun delete(path: String): Boolean
}
