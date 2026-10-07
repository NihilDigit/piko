package dev.piko.shared.rename

import dev.piko.shared.data.VaultEntry
import dev.piko.shared.naming.av.AvTreeItem
import dev.piko.shared.naming.av.canonicalAvTree

/**
 * 按番号规范命名，命名规则见 [canonicalAvTree]。[titles] 是番号到片名，有的用它，没有的用原名里的。
 * 多选的几项与文件夹的整棵树走同一条规则：所选项互不相干时，每项的上级都在所选之外，树的规则退化为逐目录的
 * canonicalAvNames，与原先的多选结果相同；选中的文件夹连同其中的文件一起出现时，按资源文件夹的规则命名。
 *
 * 归档的虚拟条目不改：它们的名字记在归档清单里，改名走的是另一条路。
 */
class CanonicalAvRule(private val titles: Map<String, String> = emptyMap()) : RenameRule {
    override fun apply(items: List<RenameSource>, names: List<String>): List<String> {
        val canonical = canonicalAvTree(items.mapIndexed { index, item -> AvTreeItem(item.id, item.parentId, names[index], item.isFolder) }, titles)
        return items.mapIndexed { index, item -> if (VaultEntry.isVaulted(item.id)) names[index] else canonical[index].name }
    }
}
