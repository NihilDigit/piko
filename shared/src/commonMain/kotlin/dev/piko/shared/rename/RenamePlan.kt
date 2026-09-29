package dev.piko.shared.rename

import dev.piko.shared.data.DriveNames

enum class RenameProblem {
    /** 规则应用后什么都不剩。 */
    EMPTY,

    /** 含 PikPak 不收的字符，见 [DriveNames]。 */
    INVALID_CHARS,

    /** 超过 PikPak 的长度上限，见 [MAX_NAME_UTF8_BYTES]。 */
    TOO_LONG,

    /** 与同目录里原样留着的名称重名：未选中的项，或所选里不改名的项。 */
    TAKEN,

    /**
     * 新名称是所选另一项的原名，那一项本要改走，却因为自己有问题改不了，名称就一直占着。
     * 与 [TAKEN] 分开：部分选中重新编号时，真正撞上未选中项的往往只有一两项，其余都是被它牵连，
     * 都写成「与现有名称重复」会让人以为每一项都要处理。
     */
    BLOCKED,

    /** 与所选另一项的新名称相同。 */
    DUPLICATE,
}

data class RenameRow(
    val source: RenameSource,
    val newName: String,
    val problem: RenameProblem?,
) {
    val isChanged: Boolean get() = newName != source.name
}

/** 执行时的一次改名请求。循环互换的项先改成 [to] 这样的临时名称，之后再改成新名称，所以一项可能有两步。 */
data class RenameStep(val source: RenameSource, val from: String, val to: String)

/**
 * [rows] 与所选顺序一致，供预览。[steps] 是可执行的改名请求及其顺序：A 改成 B、B 改成 C 时先改 B，
 * 逐个改名的每一步都不与当时的名称冲突。
 */
data class RenamePlan(val rows: List<RenameRow>, val steps: List<RenameStep>) {
    val problemCount: Int get() = rows.count { it.problem != null }
    val changeCount: Int get() = rows.count { it.isChanged && it.problem == null }
}

/**
 * 按规则算出的 [newNames] 定出改名计划。[siblingNames] 是每个目录里未选中的项的名称，按 parentId 分组。
 * 所选项可能来自不同目录（全盘搜索的结果），冲突只在同一目录内判断。名称区分大小写，与网盘列表里的比较方式一致。
 *
 * 新名称先去掉首尾空白与结尾的句点，与 PowerRename 相同，也正是 PikPak 拒收的几种写法。不收的字符不替用户删，
 * 标成问题：批量改名时用户看不到每一项，悄悄删掉字符得到的名称未必是他要的。
 *
 * PowerRename 遇到 A 与 B 互换名称时逐个改名，第一步就撞上还没改走的那个而失败；这里让环上的一项先改成临时名称，
 * 环就解开了。
 */
fun planRenames(
    items: List<RenameSource>,
    newNames: List<String>,
    siblingNames: Map<String, Set<String>>,
): RenamePlan {
    require(newNames.size == items.size)
    val targets = items.indices.map { index ->
        val name = newNames[index]
        if (name == items[index].name) name else trimName(name)
    }
    val problems = arrayOfNulls<RenameProblem>(items.size)
    fun isChanged(index: Int) = targets[index] != items[index].name
    fun isPending(index: Int) = isChanged(index) && problems[index] == null
    fun targetKey(index: Int) = items[index].parentId to targets[index]
    fun originalKey(index: Int) = items[index].parentId to items[index].name

    for (index in items.indices) {
        if (!isChanged(index)) continue
        problems[index] = when {
            targets[index].isEmpty() -> RenameProblem.EMPTY
            DriveNames.clean(targets[index]) != targets[index] -> RenameProblem.INVALID_CHARS
            targets[index].encodeToByteArray().size > MAX_NAME_UTF8_BYTES -> RenameProblem.TOO_LONG
            else -> null
        }
    }
    items.indices.filter(::isPending)
        .groupBy(::targetKey)
        .values
        .filter { it.size > 1 }
        .flatten()
        .forEach { problems[it] = RenameProblem.DUPLICATE }

    // 不改名的项占着原名。每标出一个问题，那一项也不改名了，它的原名随之变成占用，所以要反复查到不再变化。
    // 本批里要改走的原名不算占用，链式与互换由 orderSteps 排好顺序、经临时名称执行
    val unchangedKeys = items.indices.filterNot(::isChanged).map(::originalKey).toSet()
    while (true) {
        val kept = items.indices.filterNot(::isPending).map(::originalKey).toSet()
        val blocked = items.indices.mapNotNull { index ->
            if (!isPending(index)) return@mapNotNull null
            when {
                targets[index] in siblingNames[items[index].parentId].orEmpty() || targetKey(index) in unchangedKeys -> index to RenameProblem.TAKEN
                targetKey(index) in kept -> index to RenameProblem.BLOCKED
                else -> null
            }
        }
        if (blocked.isEmpty()) break
        blocked.forEach { (index, problem) -> problems[index] = problem }
    }

    val rows = items.mapIndexed { index, item -> RenameRow(item, targets[index], problems[index]) }
    return RenamePlan(rows = rows, steps = orderSteps(items, targets, items.indices.filter(::isPending), siblingNames))
}

/**
 * 目标名称仍是某个待改项的当前名称时，等那一项先改。一轮挑不出任何一项，剩下的项里必有环，
 * 挑第一项改成临时名称，它原来的名称空出来，环上的下一项就能改了。
 */
private fun orderSteps(
    items: List<RenameSource>,
    targets: List<String>,
    pendingIndices: List<Int>,
    siblingNames: Map<String, Set<String>>,
): List<RenameStep> {
    val current = pendingIndices.associateWith { items[it].name }.toMutableMap()
    val pending = pendingIndices.toMutableList()
    val steps = mutableListOf<RenameStep>()
    val temporaryNames = mutableSetOf<Pair<String, String>>()
    while (pending.isNotEmpty()) {
        val occupied = pending.map { items[it].parentId to current.getValue(it) }.toSet()
        val ready = pending.filter { (items[it].parentId to targets[it]) !in occupied }
        if (ready.isNotEmpty()) {
            ready.forEach { steps += RenameStep(items[it], current.getValue(it), targets[it]) }
            pending -= ready.toSet()
            continue
        }
        val index = pending.first()
        val parentId = items[index].parentId
        // 临时名称不能与目录里任何一个名称相撞：未选中的、所选项的原名与新名、之前取过的临时名称
        val used = siblingNames[parentId].orEmpty() +
            items.indices.filter { items[it].parentId == parentId }.flatMap { listOf(items[it].name, targets[it]) } +
            temporaryNames.filter { it.first == parentId }.map { it.second }
        // 带上原名，停在半路时认得出是哪一项；原名已贴着长度上限时只能不带
        val temporary = generateSequence(1) { it + 1 }
            .map { n -> "${items[index].name}.piko-swap$n".takeIf { it.encodeToByteArray().size <= MAX_NAME_UTF8_BYTES } ?: "piko-swap$n" }
            .first { it !in used }
        temporaryNames += parentId to temporary
        steps += RenameStep(items[index], current.getValue(index), temporary)
        current[index] = temporary
    }
    return steps
}

/**
 * PikPak 名称的长度上限，按 UTF-8 字节计，文件与文件夹相同。2026-09-29 在活账号上对重命名二分实测：
 * ASCII 最多 1024 个，汉字 341 个（1023 字节），补充平面字符 256 个（UTF-16 是 512 个单元，UTF-8 是 1024 字节）；
 * 再多一个即回 file_name_too_long（error_code=3，HTTP 400）。三种写法都落在 1024 字节上，所以不是按字符或 UTF-16 计。
 */
const val MAX_NAME_UTF8_BYTES = 1024

/** 去掉开头的空白与结尾的空白、句点，照 PowerRename 的 GetTrimmedFileName。 */
private fun trimName(name: String): String = name.trimStart().trimEnd { it.isWhitespace() || it == '.' }
