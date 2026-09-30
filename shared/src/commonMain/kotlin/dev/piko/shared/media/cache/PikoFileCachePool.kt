package dev.piko.shared.media.cache

import dev.piko.shared.log.logRangeAttempt
import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakFileCache
import io.github.nihildigit.pikpak.PikPakFileHandle
import io.github.nihildigit.pikpak.fileCache
import io.github.nihildigit.pikpak.fileHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 同一账号的同一原文件共用句柄、缓存与稀疏存储；每个使用者单独释放引用。 */
class PikoFileCachePool(
    private val scope: CoroutineScope,
    private val target: suspend (name: String) -> String,
) {
    private val lock = Mutex()
    private val entries = mutableMapOf<String, Entry>()
    private val paths = MutableStateFlow<Map<String, String>>(emptyMap())
    private val owners = mutableMapOf<String, MutableSet<String>>()
    private val cleanupScope = CoroutineScope(scope.coroutineContext.minusKey(Job) + SupervisorJob())

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion {
            cleanupScope.launch {
                try { lock.withLock {
                    entries.values.forEach { entry ->
                        entry.cache.close()
                        entry.handle.close()
                        runCatching { if (entry.retained) entry.store.close() else entry.store.delete() }
                    }
                    entries.clear()
                } } finally { cleanupScope.cancel() }
            }
        }
    }

    suspend fun remember(account: String, gcid: String, size: Long, path: String, owner: String) = lock.withLock {
        val key = identity(account, gcid, size)
        paths.update { it + (key to path) }
        owners.getOrPut(key) { mutableSetOf() }.add(owner)
        entries[key]?.retained = true
    }

    class Entry internal constructor(
        internal val key: String,
        internal val client: PikPakClient,
        val handle: PikPakFileHandle,
        val cache: PikPakFileCache,
        val store: PersistentFileStore,
        internal var references: Int = 0,
        internal var retained: Boolean = false,
    )

    class Lease internal constructor(val entry: Entry, private val release: suspend () -> Unit) : AutoCloseable {
        private val closed = MutableStateFlow(false)
        internal var releaseInBackground: (() -> Unit)? = null
        suspend fun release() {
            if (closed.compareAndSet(false, true)) withContext(NonCancellable) { release.invoke() }
        }
        override fun close() { releaseInBackground?.invoke() }
    }

    suspend fun acquire(
        client: PikPakClient,
        fileId: String,
        gcid: String,
        size: Long,
        name: String,
        parentId: String = "",
        leased: Boolean = false,
        detail: FileDetail? = null,
        retained: Boolean = false,
        concurrency: Int = client.connectionBudget,
        savedPath: String? = null,
        owner: String = fileId,
    ): Lease {
        val key = identity(client.account, gcid, size)
        return lock.withLock {
            val old = entries[key]
            // 换号后的旧客户端不能用于新请求；旧读者会随账号切换结束。
            if (old != null && old.client !== client) {
                check(old.references == 0) { "账号会话正在切换，请稍后重试" }
            }
            val entry = old ?: run {
                val store = persistentFileStore(savedPath ?: paths.value[key] ?: target(fileCacheName(key)), key, size, scope)
                val handle = if (detail != null) client.fileHandle(detail, leased = leased, onRangeAttempt = ::logRangeAttempt)
                else PikPakFileHandle(
                    client, gcid, size, name,
                    initialFileId = fileId.takeUnless { leased }, parentId = parentId,
                    leased = leased, connectionBudget = concurrency, onRangeAttempt = ::logRangeAttempt,
                )
                try {
                    Entry(key, client, handle, client.fileCache(
                        source = handle, size = size, storeKey = handle.contentKey,
                        blockStore = store, connectionBudget = concurrency, coroutineContext = scope.coroutineContext + Dispatchers.IO,
                    ), store).also { entries[key] = it }
                } catch (e: Throwable) {
                    handle.close()
                    withContext(NonCancellable) { store.close() }
                    throw e
                }
            }
            entry.references++
            entry.retained = entry.retained || retained || key in paths.value
            if (retained) {
                paths.update { it + (key to entry.store.path) }
                owners.getOrPut(key) { mutableSetOf() }.add(owner)
            }
            Lease(entry) { release(entry) }.also { lease ->
                lease.releaseInBackground = { cleanupScope.launch { lease.release() } }
            }
        }
    }

    suspend fun progress(account: String, gcid: String, size: Long, path: String): Long {
        val key = identity(account, gcid, size)
        return lock.withLock { entries[key]?.store?.heldBytes?.value }
            ?: persistedFileBytes(path, key, size)
    }

    data class Snapshot(val heldBytes: Long, val deliveredBytes: Long)

    suspend fun progressSnapshot(): Map<String, Snapshot> = lock.withLock {
        entries.values.associate { it.store.path to Snapshot(it.store.heldBytes.value, it.cache.deliveredBytes) }
    }

    suspend fun complete(lease: Lease, owner: String) = lock.withLock {
        unpin(lease.entry.key, owner)
        lease.entry.retained = owners[lease.entry.key]?.isNotEmpty() == true
    }

    /** 正在播放的缓存延迟到最后一个读者释放再删。 */
    suspend fun discard(account: String, gcid: String, size: Long, path: String, owner: String) {
        val key = identity(account, gcid, size)
        lock.withLock {
            unpin(key, owner)
            if (owners[key]?.isNotEmpty() == true) return
            entries[key]?.let { it.retained = false; return }
            val store = persistentFileStore(path, key, size, scope)
            store.delete()
        }
    }

    private fun unpin(key: String, owner: String) {
        owners[key]?.remove(owner)
        if (owners[key].isNullOrEmpty()) {
            owners.remove(key)
            paths.update { it - key }
        }
    }

    suspend fun prune() {
        val candidates = ownedCacheFiles(target("probe"))
        lock.withLock {
            val retained = paths.value.values.toSet() + entries.values.map { it.store.path }
            for (path in candidates) if (path !in retained) deleteCacheFiles(path)
        }
    }

    private suspend fun release(entry: Entry) = lock.withLock {
        if (entries[entry.key] !== entry) return@withLock
        entry.references--
        if (entry.references != 0) return@withLock
        entry.cache.close()
        entry.handle.close()
        try {
            if (entry.retained) entry.store.close() else entry.store.delete()
        } finally {
            if (entries[entry.key] === entry) entries.remove(entry.key)
        }
    }

    companion object {
        fun identity(account: String, gcid: String, size: Long): String = "$account\u0000${gcid.uppercase()}\u0000$size\u0000origin"
    }
}
