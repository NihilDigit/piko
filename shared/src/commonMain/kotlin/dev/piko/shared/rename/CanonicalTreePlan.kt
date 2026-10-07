package dev.piko.shared.rename

import dev.piko.shared.naming.av.AvTreeItem
import dev.piko.shared.naming.av.canonicalAvTree

/**
 * 一棵树按番号规范命名的改名计划，命名见 [canonicalAvTree]，冲突与执行顺序见 [planRenames]。
 * [siblingNames] 是树外各目录（树根所在的那一层）里不在 [items] 中的条目名，按目录 ID 分组；树里的目录全部在 [items] 里，不必给。
 */
fun planCanonicalTree(
    items: List<AvTreeItem>,
    siblingNames: Map<String, Set<String>>,
    titles: Map<String, String> = emptyMap(),
    resources: Set<String> = emptySet(),
): RenamePlan {
    val names = canonicalAvTree(items, titles, resources)
    val sources = items.map { RenameSource(it.id, it.parentId, it.name, it.isFolder) }
    return planRenames(sources, names.map { it.name }, siblingNames)
}
