package dev.piko.shared.rename

/**
 * 把 [group] 里的几块一起挪到 [target] 那块旁边，组内保持原来的先后：往左挪时落在目标前面，往右挪时落在后面，
 * 与单块拖动（add(to, removeAt(from))）的落点一致。目标在组内时不动。批量重命名的块条框选后整组拖动用它。
 */
fun <T> List<T>.moveGroup(group: Set<Int>, target: Int): List<T> {
    if (target in group || group.isEmpty()) return this
    val moving = group.sorted().map { this[it] }
    val rest = indices.filter { it !in group }
    val at = rest.indexOf(target) + if (target > group.min()) 1 else 0
    return rest.map { this[it] }.toMutableList().apply { addAll(at, moving) }
}
