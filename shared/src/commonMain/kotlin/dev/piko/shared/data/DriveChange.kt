package dev.piko.shared.data

/**
 * 网盘里的一次改动。凡是改了网盘的都经 [PikoDriveRepository.applyChange] 收口：文件夹索引、列表缓存、
 * 各处存着的路径与订阅者（网盘页、目录图）都从这一处得知，不再各自清各自的缓存。
 *
 * [folders] 是内容变了的文件夹，订阅者据此只刷新受影响的几层；为 null 时说不清是哪几个，凡是列过的都算。
 */
sealed interface DriveChange {
    val folders: Set<String>?

    fun affects(folderId: String): Boolean = folders?.contains(folderId) ?: true

    class Created(val parentId: String, val id: String, val name: String, val isFolder: Boolean) : DriveChange {
        override val folders: Set<String> = setOf(parentId)
    }

    /** [parentId] 为 null 是不知道它在哪个文件夹里（改的是全盘搜索里的一项）。 */
    class Renamed(val id: String, val name: String, parentId: String?) : DriveChange {
        override val folders: Set<String>? = parentId?.let(::setOf)
    }

    /** [from] 为 null 是有几项不知道原来在哪。 */
    class Moved(val ids: List<String>, from: Set<String>?, val to: String) : DriveChange {
        override val folders: Set<String>? = from?.plus(to)
    }

    class Trashed(val ids: List<String>, override val folders: Set<String>?) : DriveChange

    /** 回到哪里只有回收站里那一份列表知道，仓库不记，所以说不清。 */
    class Restored(val ids: List<String>) : DriveChange {
        override val folders: Set<String>? = null
    }

    /** 彻底删除。回收站里的条目不在任何列过的文件夹里，[folders] 只含知道的那几个。 */
    class Deleted(val ids: List<String>, override val folders: Set<String>) : DriveChange

    class Copied(val ids: List<String>, val to: String) : DriveChange {
        override val folders: Set<String> = setOf(to)
    }

    /** 文件夹里多了或少了东西、条目的属性变了，具体是哪几项不重要：上传、解压、转存、归档、星标。 */
    class ContentsChanged(override val folders: Set<String>?) : DriveChange
}
