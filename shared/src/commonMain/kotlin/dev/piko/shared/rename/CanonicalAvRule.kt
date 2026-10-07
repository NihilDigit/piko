package dev.piko.shared.rename

import dev.piko.shared.data.VaultEntry
import dev.piko.shared.naming.av.AvNamingItem
import dev.piko.shared.naming.av.canonicalAvNames

/**
 * 按番号规范命名，命名规则见 [canonicalAvNames]。[titles] 是番号到片名，有的用它，没有的用原名里的片名。
 *
 * 归档的虚拟条目不改：它们的名字记在归档清单里，改名走的是另一条路。
 */
class CanonicalAvRule(private val titles: Map<String, String> = emptyMap()) : RenameRule {
    override fun apply(items: List<RenameSource>, names: List<String>): List<String> {
        val canonical = canonicalAvNames(items.mapIndexed { index, item -> AvNamingItem(names[index], item.parentId, item.isFolder) }, titles)
        return items.mapIndexed { index, item -> if (VaultEntry.isVaulted(item.id)) names[index] else canonical[index] }
    }
}
