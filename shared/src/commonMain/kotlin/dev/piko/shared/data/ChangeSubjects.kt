package dev.piko.shared.data

/** 供交互入口判断在途撤销会影响哪些条目；结束后不在结果记录中保留这些 ID。 */
internal fun DriveChangeJournal.Change.affectedFileIds(): Set<String> = when (this) {
    is DriveChangeJournal.Change.Move -> from.keys
    is DriveChangeJournal.Change.Rename -> renames.mapTo(HashSet()) { it.id }
    is DriveChangeJournal.Change.Trash -> ids.toSet()
    is DriveChangeJournal.Change.Vault -> buildSet {
        addAll(trashOnRevert)
        addAll(untrashOnRevert)
        trashByFolder.values.forEach(::addAll)
        untrashByFolder.values.forEach { map -> map.values.forEach { add(it.id) } }
    }
}

internal fun DriveChangeJournal.Change.affectedManifestFolders(): Set<String> =
    if (this is DriveChangeJournal.Change.Vault) {
        reverts.keys + recreateOnRevert.keys + untrashByFolder.keys + trashByFolder.keys
    } else {
        emptySet()
    }

/** 名称撤销不改变接收目录的身份；位置和存在状态的撤销需要保护其后代。 */
internal fun DriveChangeJournal.Change.affectedTreeIds(): Set<String> =
    if (this is DriveChangeJournal.Change.Rename) emptySet() else affectedFileIds()
