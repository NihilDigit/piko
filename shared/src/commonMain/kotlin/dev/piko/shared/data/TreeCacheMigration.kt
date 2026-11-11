package dev.piko.shared.data

import dev.piko.shared.log.PikoLog

private const val TAG = "TreeCacheMigration"

/** Legacy cache keys had no owner marker; '_' could represent any rejected character. */
internal fun isUnambiguousLegacyTreeAccount(account: String): Boolean = account.isNotEmpty() && account.all {
    it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '@' || it == '-'
}

internal suspend fun migrateLegacyTreeCache(index: DriveTreeIndex, account: String, cache: PikoCacheStore?): Boolean {
    if (cache == null) return false
    val key = DriveTreeIndex.snapshotKeyOf(account)
    val slot = logAccountSlot(account)
    if (index.isComplete) {
        // SQLite 索引已完备（含自愈恢复），顺手清理遗留的旧版大体积 JSON 快照，杜绝后续重复解析
        runCatching { cache.delete(key) }
        return false
    }
    if (!isUnambiguousLegacyTreeAccount(account)) {
        // There is no reliable way to assign a collided legacy cache to either account.
        runCatching { cache.delete(key) }
            .onFailure { err -> PikoLog.w(TAG, "Failed to delete ambiguous legacy tree cache (acct=$slot)", err) }
        PikoLog.d(TAG, "Cleared ambiguous legacy tree cache key if present (acct=$slot)")
        return false
    }
    var streamPresent = false
    val imported = runSuspendCatching {
        cache.readStream(key) { source ->
            streamPresent = true
            index.importLegacySnapshot(source)
        } == true
    }.onFailure { err ->
        PikoLog.w(TAG, "Failed to read legacy tree cache; removing unreadable snapshot (acct=$slot)", err)
        runCatching { cache.delete(key) }
    }.getOrDefault(false)
    if (imported) {
        runCatching { cache.delete(key) }
            .onFailure { err -> PikoLog.w(TAG, "Failed to delete migrated legacy tree cache (acct=$slot)", err) }
        PikoLog.i(TAG, "Migrated legacy JSON tree cache into SQLite (acct=$slot)")
    } else if (streamPresent && !index.isClosed && !index.isComplete) {
        PikoLog.w(TAG, "Discarding corrupted legacy JSON tree cache to avoid repeated startup migration (acct=$slot)")
        runCatching { cache.delete(key) }
    }
    return imported
}
