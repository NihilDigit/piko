package dev.piko.shared.data

/** 活跃使用者保留的目录引用；仓库用它保护索引与缓存，不拥有使用者的导航状态。 */
interface FolderReferenceSource {
    val retainedPaths: List<List<PikoPathBreadcrumb>>
    val referencedPaths: List<List<PikoPathBreadcrumb>> get() = retainedPaths
    fun refreshFolderReferences()
}
