package dev.piko.shared.download

/**
 * 下载落盘的平台边界。调度器不接触 Context 或 java.io.File。
 *
 * 写入由 SDK 的 downloadTo 完成：它只接受文件系统路径，按顺序追加写入，文件长度即续传点。
 * 所以存储层只负责给出写入路径和完成后的交付，不再自己搬运字节。
 *
 * 各方法的 fileName 是相对下载目录的路径，以 / 分隔，每一段都已清理过（文件夹下载的文件是
 * 「文件夹/子文件夹/文件」）。中间的文件夹由实现在写入与交付时建出，查询时逐级解析、不建。
 * 不另设「子目录」参数：已存的任务表只有 fileName 一个字段，续传、核对与删除都凭它找回同一个文件，
 * 路径放在名字里，旧任务（不带 /）照旧落在下载目录根下。
 */
interface PikoDownloadStorage {
    /** 完成后文件所在的位置，用于展示与播放。 */
    fun pathFor(fileName: String): String

    /**
     * 下载过程中写入的本地文件，所在的文件夹已建好。普通目录下就是最终文件；Android 的 SAF 目录没有文件系统
     * 路径，返回应用私有目录里的暂存文件，写满后由 [commit] 复制过去。
     */
    suspend fun downloadTarget(fileName: String): String

    /** 把写满的 [downloadedPath] 交付到最终位置，返回最终路径。 */
    suspend fun commit(fileName: String, downloadedPath: String): String

    /** 已落盘的字节数：完成的文件取最终文件长度，未完成的取暂存文件长度。 */
    suspend fun existingLength(fileName: String): Long
    suspend fun exists(fileName: String): Boolean
    suspend fun delete(path: String): Boolean

    /**
     * [fileName] 所指的文件或文件夹在最终位置上的路径（SAF 目录下是 content: URI），还不存在时为 null。
     * 文件夹下载的「打开文件夹」用它：[pathFor] 在 SAF 目录下给不出子文件夹的 URI。
     */
    suspend fun locate(fileName: String): String?

    /** 删掉 [folder] 与其中所有空的子文件夹，还有文件的留着。取消整个文件夹下载后收拾空壳用。 */
    suspend fun pruneEmptyFolders(folder: String)
}
