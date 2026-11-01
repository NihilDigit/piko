package dev.piko.shared.data

/** Legacy cache keys had no owner marker; '_' could represent any rejected character. */
internal fun isUnambiguousLegacyTreeAccount(account: String): Boolean = account.isNotEmpty() && account.all {
    it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '@' || it == '-'
}

internal suspend fun migrateLegacyTreeCache(index: DriveTreeIndex, account: String, cache: PikoCacheStore?): Boolean {
    if (cache == null) return false
    val key = DriveTreeIndex.snapshotKeyOf(account)
    if (index.isComplete) {
        // SQLite 索引已完备（含自愈恢复），顺手清理遗留的旧版大体积 JSON 快照，杜绝后续重复解析
        runCatching { cache.delete(key) }
        return false
    }
    if (!isUnambiguousLegacyTreeAccount(account)) {
        // There is no reliable way to assign a collided legacy cache to either account.
        cache.delete(key)
        return false
    }
    val imported = cache.readStream(key, index::importLegacySnapshot) == true
    if (imported) cache.delete(key)
    return imported
}
