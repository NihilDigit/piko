package dev.piko.shared.media.cache

import io.github.nihildigit.pikpak.DurableBlockStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** 有效块与文件长度分开记录；文件中的空洞不计入下载进度。 */
interface PersistentFileStore : DurableBlockStore {
    val path: String
    val heldBytes: StateFlow<Long>
    val size: Long
    suspend fun importPrefix(source: String)
    suspend fun flush()
    suspend fun close()
    suspend fun delete()
}

expect fun persistentFileStore(path: String, key: String, size: Long, scope: CoroutineScope): PersistentFileStore
expect fun fileCacheName(key: String): String
expect suspend fun copyCachedFile(source: String, destination: String)
expect suspend fun persistedFileBytes(path: String, key: String, size: Long): Long
expect suspend fun ownedCacheFiles(examplePath: String): List<String>
expect suspend fun deleteCacheFiles(path: String)
