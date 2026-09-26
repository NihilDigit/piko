package dev.piko.shared.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

/**
 * 本机缓存：一个键一段文本。系统可以随时清掉它（Android 的 cacheDir），读不到就当没有，
 * 所以只放丢了也能重新取回的东西。Android 与桌面各有实现。
 */
interface PikoCacheStore {
    suspend fun read(key: String): String?

    suspend fun write(key: String, value: String)
}

/**
 * 每个键一个文件，放在 [directory] 下：Android 给 cacheDir 里的子目录，桌面给 ~/.piko/cache。
 * 先写临时文件再改名，写到一半进程被杀也不会留下半个 JSON；读失败一律当作没有。
 */
class FilePikoCacheStore(directory: String) : PikoCacheStore {
    private val directory = Path(directory)

    override suspend fun read(key: String): String? = withContext(Dispatchers.IO) {
        val file = Path(directory, key)
        runCatching { if (SystemFileSystem.exists(file)) SystemFileSystem.source(file).buffered().use { it.readString() } else null }.getOrNull()
    }

    override suspend fun write(key: String, value: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                SystemFileSystem.createDirectories(directory)
                val temp = Path(directory, "$key.tmp")
                SystemFileSystem.sink(temp).buffered().use { it.writeString(value) }
                SystemFileSystem.atomicMove(temp, Path(directory, key))
            }
        }
    }
}
