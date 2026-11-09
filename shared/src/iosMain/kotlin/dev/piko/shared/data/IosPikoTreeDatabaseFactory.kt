@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.piko.shared.data

import dev.piko.shared.sqlite.*
import dev.piko.shared.storage.ensureIosDirectory
import dev.piko.shared.sync.PortableDigest
import kotlinx.cinterop.*
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSCondition

/** The same account-isolated WAL store as desktop; SqliteTreeStore validates owner_account. */
class IosPikoTreeDatabaseFactory(private val directory: String) : PikoTreeDatabaseFactory {
    override fun open(account: String): PikoTreeDatabase {
        ensureIosDirectory(directory)
        val prefix = account.map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9') it else '_' }
            .joinToString("").take(24).ifEmpty { "account" }
        val hash = PortableDigest.sha256(account.encodeToByteArray()).take(8)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val file = Path(SystemFileSystem.resolve(Path(directory)), "drive-tree-$prefix-$hash.db").toString()
        val release = IosTreeDatabaseLeases.acquire(file)
        var opened: NativeTreeDatabase? = null
        try {
            val database = NativeTreeDatabase(openSqlite(file), release)
            opened = database
            database.execute("PRAGMA busy_timeout=5000")
            database.query("PRAGMA journal_mode=WAL").use { while (it.next()) Unit }
            database.execute("PRAGMA synchronous=NORMAL")
            database.execute("PRAGMA temp_store=MEMORY")
            return database
        } catch (error: Throwable) {
            try {
                if (opened != null) opened.close() else release()
            } catch (cleanup: Throwable) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
    }
}

/** A connection may close on another thread, so a thread-owned mutex cannot represent its lease. */
private object IosTreeDatabaseLeases {
    private val condition = NSCondition()
    private val paths = mutableSetOf<String>()

    fun acquire(path: String): () -> Unit {
        condition.lock()
        try {
            while (path in paths) condition.wait()
            paths.add(path)
        } finally {
            condition.unlock()
        }
        return {
            condition.lock()
            try {
                paths.remove(path)
                condition.broadcast()
            } finally {
                condition.unlock()
            }
        }
    }
}

private fun openSqlite(path: String): CPointer<sqlite3> = memScoped {
    val result = alloc<CPointerVar<sqlite3>>()
    result.value = null
    val code = sqlite3_open_v2(path, result.ptr, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE or SQLITE_OPEN_FULLMUTEX, null)
    val connection = result.value
    if (code != SQLITE_OK || connection == null) {
        if (connection != null) sqlite3_close_v2(connection)
        // SQLite error text can contain account paths or stored names; do not include it in failures.
        throw IOException("Opening tree database failed (SQLite $code)")
    }
    connection
}

private class NativeTreeDatabase(
    private val connection: CPointer<sqlite3>,
    release: () -> Unit,
) : ManagedPikoTreeDatabase(release) {
    private fun checkResult(code: Int) {
        if (code != SQLITE_OK) throw IOException("Tree database operation failed (SQLite $code)")
    }

    private fun prepare(sql: String, parameters: List<Any?>): CPointer<sqlite3_stmt> = memScoped {
        val result = alloc<CPointerVar<sqlite3_stmt>>()
        result.value = null
        val code = sqlite3_prepare_v2(connection, sql, -1, result.ptr, null)
        if (code != SQLITE_OK) {
            result.value?.let { sqlite3_finalize(it) }
            checkResult(code)
        }
        val statement = result.value ?: throw IOException("Tree database statement is empty")
        try {
            require(sqlite3_bind_parameter_count(statement) == parameters.size) { "SQLite parameter count mismatch" }
            parameters.forEachIndexed { offset, value ->
                val index = offset + 1
                checkResult(when (value) {
                    null -> sqlite3_bind_null(statement, index)
                    is Boolean -> sqlite3_bind_int64(statement, index, if (value) 1L else 0L)
                    is Byte, is Short, is Int, is Long -> sqlite3_bind_int64(statement, index, (value as Number).toLong())
                    is Float, is Double -> sqlite3_bind_double(statement, index, (value as Number).toDouble())
                    is String -> {
                        val bytes = value.encodeToByteArray()
                        // Supply a non-null pointer even for the empty string and copy before unpinning.
                        (bytes + 0.toByte()).usePinned { piko_bind_text(statement, index, it.addressOf(0), bytes.size) }
                    }
                    is ByteArray -> if (value.isEmpty()) sqlite3_bind_zeroblob(statement, index, 0) else {
                        value.usePinned { piko_bind_blob(statement, index, it.addressOf(0), value.size) }
                    }
                    else -> error("Unsupported SQLite parameter type")
                })
            }
            statement
        } catch (error: Throwable) {
            sqlite3_finalize(statement)
            throw error
        }
    }

    override fun executeRaw(sql: String, parameters: List<Any?>) {
        val statement = prepare(sql, parameters)
        try {
            while (true) {
                when (val code = sqlite3_step(statement)) {
                    SQLITE_DONE -> return
                    SQLITE_ROW -> Unit // PRAGMA can produce rows even through execute.
                    else -> checkResult(code)
                }
            }
        } finally {
            sqlite3_finalize(statement)
        }
    }

    override fun queryRaw(sql: String, parameters: List<Any?>): PikoTreeCursor {
        val statement = prepare(sql, parameters)
        return object : PikoTreeCursor {
            private var done = false
            override fun next(): Boolean {
                if (done) return false
                return when (val code = sqlite3_step(statement)) {
                    SQLITE_ROW -> true
                    SQLITE_DONE -> { done = true; false }
                    else -> { checkResult(code); false }
                }
            }
            override fun string(index: Int): String? {
                if (sqlite3_column_type(statement, index) == SQLITE_NULL) return null
                val bytes = sqlite3_column_text(statement, index) ?: return null
                return bytes.reinterpret<ByteVar>().readBytes(sqlite3_column_bytes(statement, index)).decodeToString()
            }
            override fun long(index: Int): Long? =
                if (sqlite3_column_type(statement, index) == SQLITE_NULL) null else sqlite3_column_int64(statement, index)
            override fun close() { sqlite3_finalize(statement) }
        }
    }

    override fun closeRaw() { checkResult(sqlite3_close_v2(connection)) }
}
