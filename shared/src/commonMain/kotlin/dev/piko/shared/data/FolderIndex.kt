package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 网盘里文件夹的名字与上级，按 ID，进程里只此一份。凡是写着路径的地方（地址栏、面包屑、标签、浏览历史、
 * 最近去过、快速访问、属性的位置、命令面板）显示的名字与层级都以它为准；路径栈里存着的名字只在这里还没有那一项时回落。
 *
 * 只收服务端说的：列目录、查详情，以及 Piko 自己做成的新建、改名、移动、删除。路径栈不当来源：
 * 栈是进入那一刻的快照，拿它反推上级，改名、移走之后旧的关系又会被写回来。
 * 唯一的例外是信息流存盘的目录与上级（[seed]），记作 provisional，只补空缺，服务端一说就被盖掉，
 * 也不拿来改写路径栈（[resolve]）。
 *
 * 收的是能停在路径栈上的条目：文件夹，以及能当文件夹打开的压缩包（以它的文件 ID 记，压缩包那一级的名字随之更新）。
 * 库与压缩包里的一层不是网盘里的条目，不进来。只在进程内有效，换账号时清空。
 */
internal class FolderIndex {
    /** [parentId] 为 null 是只知道名字、不知道在哪（改名时它不在任何列过的目录里）。根目录的 ID 是空串。 */
    class Node(val name: String, val parentId: String?, val provisional: Boolean = false)

    /** [gone] 是 Piko 自己移进回收站或删掉的，路径栈停在它们里面的退到它们之外，恢复或再列到时去掉。 */
    data class State(val nodes: Map<String, Node> = emptyMap(), val gone: Set<String> = emptySet())

    private val state = MutableStateFlow(State())
    val flow: StateFlow<State> = state.asStateFlow()

    fun node(id: String): Node? = state.value.nodes[id]

    fun clear() {
        state.value = State()
    }

    /** 以下改动都返回索引变了没有，变了由仓库把各处存着的路径重新对一遍。 */
    private inline fun edit(change: (State) -> State): Boolean {
        while (true) {
            val before = state.value
            val after = change(before)
            if (after == before) return false
            if (state.compareAndSet(before, after)) return true
        }
    }

    /**
     * [parentId] 的完整列表：其中的文件夹记下，原先记在它名下、这次不在的去掉（移走了或删了，去了哪说不清）。
     * [complete] 为假时只是一页，只记不删。
     */
    fun listed(parentId: String, files: List<FileStat>, complete: Boolean = true): Boolean {
        val children = files.filter { !it.trashed && it.isIndexed() }.associate { it.id to Node(it.name, parentId) }
        return edit { current ->
            val kept = if (complete) current.nodes.filterValues { it.parentId != parentId } else current.nodes
            current.copy(nodes = kept + children, gone = current.gone - children.keys)
        }
    }

    /** 查详情得到的一项。只收文件夹与压缩包；回收站里的不收，也不当作删掉：那是别处删的，由列表页自己发现。 */
    fun learned(id: String, name: String, parentId: String, isFolder: Boolean, trashed: Boolean): Boolean {
        if (trashed || id.isEmpty() || !(isFolder || isExtractableArchive(name))) return false
        return edit { it.copy(nodes = it.nodes + (id to Node(name, parentId)), gone = it.gone - id) }
    }

    /** 改了名。[always] 为假时只改记着的：批量改几百个文件的名，不该往这里塞几百个与路径无关的条目。 */
    fun renamed(id: String, name: String, always: Boolean): Boolean = edit { current ->
        val known = current.nodes[id]
        when {
            known != null -> current.copy(nodes = current.nodes + (id to Node(name, known.parentId)))
            always -> current.copy(nodes = current.nodes + (id to Node(name, null)))
            else -> current
        }
    }

    fun moved(ids: Collection<String>, parentId: String): Boolean = edit { current ->
        val moved = ids.mapNotNull { id -> current.nodes[id]?.let { id to Node(it.name, parentId) } }
        current.copy(nodes = current.nodes + moved)
    }

    /**
     * 移进回收站或删掉了。记着的与 [referenced]（路径栈上有、索引里却还没有的，例如恢复出来的标签）里的才记作 gone：
     * 删掉的文件与路径无关，记下只是让这张表越来越大。
     */
    fun removed(ids: Collection<String>, referenced: Set<String>): Boolean = edit { current ->
        val gone = ids.filter { it in current.nodes || it in referenced }
        current.copy(nodes = current.nodes - ids.toSet(), gone = current.gone + gone)
    }

    fun restored(ids: Collection<String>): Boolean = edit { it.copy(gone = it.gone - ids.toSet()) }

    /** 信息流存盘的目录名与上级，见类注释。 */
    fun seed(names: Map<String, String>, parents: Map<String, String>): Boolean = edit { current ->
        val added = names.filterKeys { it.isNotEmpty() && it !in current.nodes }
            .mapValues { (id, name) -> Node(name, parents[id], provisional = true) }
        current.copy(nodes = current.nodes + added)
    }

    /**
     * 从根到 [folderId] 的路径栈，含根。中间缺一级、或那一级的上级不知道时为 null。
     * [provisional] 为假时不认信息流存盘的那些：「在网盘中显示」跳过去的路径只信服务端说的。
     */
    fun pathTo(folderId: String, provisional: Boolean = false): List<PikoPathBreadcrumb>? = pathTo(folderId, provisional, state.value.nodes)

    private fun pathTo(folderId: String, provisional: Boolean, nodes: Map<String, Node>): List<PikoPathBreadcrumb>? {
        val chain = ArrayDeque<PikoPathBreadcrumb>()
        var id = folderId
        while (id.isNotEmpty()) {
            val node = nodes[id]?.takeIf { provisional || !it.provisional } ?: return null
            chain.addFirst(PikoPathBreadcrumb(id, node.name))
            id = node.parentId ?: return null
            // 两次列目录之间文件夹被移进自己的子文件夹时，记下的上级会成环
            if (chain.size > nodes.size) return null
        }
        chain.addFirst(PikoDriveRepository.ROOT_BREADCRUMB)
        return chain
    }

    /** 路径栈上一级的名字，按索引换成眼下的。不认识的原样返回。 */
    fun withCurrentName(crumb: PikoPathBreadcrumb): PikoPathBreadcrumb {
        val name = nameOf(crumb, state.value.nodes) ?: return crumb
        return if (name == crumb.name) crumb else PikoPathBreadcrumb(crumb.id, name)
    }

    /**
     * 按索引改正一条路径栈：各级换成眼下的名字；某一级不在上一级里了（移走了），前面整段换成索引里它的新路径；
     * 停在 [State.gone] 里的，截到它之外。索引里没有的一级原样保留。没有要改的返回原来那个列表。
     */
    fun resolve(stack: List<PikoPathBreadcrumb>): List<PikoPathBreadcrumb> {
        if (stack.isEmpty()) return stack
        val current = state.value
        val out = ArrayList<PikoPathBreadcrumb>(stack.size)
        for ((index, crumb) in stack.withIndex()) {
            val nodeId = nodeIdOf(crumb.id)
            // 栈底（根或库）总留着：截光了无处可停
            if (index > 0 && nodeId != null && nodeId in current.gone) break
            val node = nodeId?.let { current.nodes[it] }?.takeUnless { it.provisional }
            val previous = out.lastOrNull()
            // 库里进的子文件夹，上级不是库，不比
            val parentKnown = node?.parentId != null && previous != null && DriveLibrary.of(previous.id) == null
            if (parentKnown && nodeIdOf(previous.id) != node.parentId) {
                pathTo(node.parentId, provisional = false, current.nodes)?.let {
                    out.clear()
                    out += it
                }
            }
            val name = nameOf(crumb, current.nodes)
            out += if (name == null || name == crumb.name) crumb else PikoPathBreadcrumb(crumb.id, name)
        }
        return if (out == stack) stack else out
    }

    private fun nameOf(crumb: PikoPathBreadcrumb, nodes: Map<String, Node>): String? {
        if (crumb.id.isEmpty()) return PikoDriveRepository.ROOT_BREADCRUMB.name
        DriveLibrary.of(crumb.id)?.let { return it.title }
        val nodeId = nodeIdOf(crumb.id) ?: return null
        return nodes[nodeId]?.takeUnless { it.provisional }?.name
    }

    companion object {
        /**
         * 路径栈上一级对应的索引条目：网盘里的文件夹是它自己，压缩包那一级是压缩包文件，根是空串。
         * 库与压缩包里的子目录没有对应的条目，为 null。
         */
        fun nodeIdOf(crumbId: String): String? {
            ArchiveLocation.of(crumbId)?.let { return if (it.path.isEmpty()) it.archiveId else null }
            return crumbId.takeIf { isDriveFolderId(it) }
        }

        private fun FileStat.isIndexed() = isFolder || isExtractableArchive(name)
    }
}
