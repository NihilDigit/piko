package dev.piko.shared.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.Sink
import kotlin.random.Random
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
    /** Real platform stores inject the JVM streaming codec; in-memory test stores may omit it. */
    val jsonCodec: StreamingJsonCodec? get() = null

    suspend fun read(key: String): String?

    suspend fun write(key: String, value: String)

    /** 流仅在回调期间有效。旧实现通过文本兼容，文件实现不建立完整文本。 */
    suspend fun <T> readStream(key: String, decode: (Source) -> T): T? =
        read(key)?.let { text -> Buffer().use { buffer -> buffer.writeString(text); decode(buffer) } }

    suspend fun writeStream(key: String, encode: (Sink) -> Unit) {
        val value = Buffer().use { buffer -> encode(buffer); buffer.readString() }
        write(key, value)
    }

    /** 删掉 [key]。默认写成空串，读回来解析失败也就当没有了。 */
    suspend fun delete(key: String) = write(key, "")
}

/**
 * 每个键一个文件，放在 [directory] 下：Android 给 cacheDir 里的子目录，桌面给 ~/.piko/cache。
 * 先写临时文件再改名，写到一半进程被杀也不会留下半个 JSON；读失败一律当作没有。
 */
class FilePikoCacheStore(
    directory: String,
    override val jsonCodec: StreamingJsonCodec? = null,
) : PikoCacheStore {
    private val directory = Path(directory)
    private val stripes = Array(64) { Mutex() }
    private fun lock(key: String) = stripes[key.hashCode() and 63]

    override suspend fun read(key: String): String? = readStream(key) { it.readString() }

    override suspend fun <T> readStream(key: String, decode: (Source) -> T): T? =
        withContext(Dispatchers.IO) {
            lock(key).withLock {
                val file = Path(directory, key)
                try {
                    currentCoroutineContext().ensureActive()
                    if (SystemFileSystem.exists(file)) {
                        SystemFileSystem.source(file).buffered().use(decode)
                    } else null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
        }

    override suspend fun write(key: String, value: String) = writeStream(key) { it.writeString(value) }

    override suspend fun writeStream(key: String, encode: (Sink) -> Unit) {
        withContext(Dispatchers.IO) {
            lock(key).withLock {
                SystemFileSystem.createDirectories(directory)
                val temp = Path(directory, "$key.${Random.nextLong().toULong()}.tmp")
                try {
                    SystemFileSystem.sink(temp).buffered().use(encode)
                    currentCoroutineContext().ensureActive()
                    SystemFileSystem.atomicMove(temp, Path(directory, key))
                } finally {
                    // Cleanup must not replace the actual encode/commit failure.
                    try { SystemFileSystem.delete(temp, mustExist = false) } catch (_: Exception) { }
                }
            }
        }
    }

    override suspend fun delete(key: String) {
        withContext(Dispatchers.IO) {
            lock(key).withLock {
                currentCoroutineContext().ensureActive()
                SystemFileSystem.delete(Path(directory, key), mustExist = false)
            }
        }
    }
}
