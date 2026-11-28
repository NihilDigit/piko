package dev.piko.shared.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File

/**
 * Android 端统一使用 AndroidX `sqlite-bundled`（内置现代 SQLite 原生引擎），
 * 彻底绕开不同 Android 版本系统 SQLite（3.18 / 3.22）差异与 Framework 2 MB `CursorWindow` 限制。
 */
class AndroidPikoTreeDatabaseFactory(private val directory: File) : PikoTreeDatabaseFactory {
    override fun open(account: String): PikoTreeDatabase {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create tree database directory" }
        val file = treeDatabaseFile(directory, account)
        val release = TreeDatabaseLeases.acquire(file)
        var connection: SQLiteConnection? = null
        try {
            val opened = driver.open(file.absolutePath)
            connection = opened
            val database = BundledAndroidTreeDatabase(opened, release)
            database.execute("PRAGMA busy_timeout=5000")
            val journalMode = database.scalarString("PRAGMA journal_mode=WAL")?.lowercase()
            check(journalMode == "wal") { "Cannot enable SQLite WAL (got: $journalMode)" }
            database.execute("PRAGMA synchronous=NORMAL")
            database.execute("PRAGMA temp_store=DEFAULT")
            database.execute("PRAGMA cache_size=-8192")
            return database
        } catch (error: Throwable) {
            try {
                try { connection?.close() } finally { release() }
            } catch (cleanup: Throwable) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    private companion object {
        val driver = BundledSQLiteDriver()
    }
}

private fun SQLiteStatement.bind(parameters: List<Any?>) {
    parameters.forEachIndexed { index, value ->
        val slot = index + 1
        when (value) {
            null -> bindNull(slot)
            is ByteArray -> bindBlob(slot, value)
            is Boolean -> bindLong(slot, if (value) 1L else 0L)
            is Byte, is Short, is Int, is Long -> bindLong(slot, (value as Number).toLong())
            is Float, is Double -> bindDouble(slot, (value as Number).toDouble())
            is String -> bindText(slot, value)
            else -> error("Unsupported SQLite parameter type")
        }
    }
}

private class BundledAndroidTreeDatabase(
    private val connection: SQLiteConnection,
    release: () -> Unit,
) : ManagedPikoTreeDatabase(release) {
    private val statementCache = object : LinkedHashMap<String, SQLiteStatement>(96, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SQLiteStatement>): Boolean {
            if (size <= MAX_CACHED_STATEMENTS) return false
            runCatching { eldest.value.close() }
            return true
        }
    }

    private fun isCacheableWrite(sql: String): Boolean {
        if (sql.length > 512) return false
        val trimmed = sql.trimStart()
        return trimmed.startsWith("INSERT", ignoreCase = true) ||
            trimmed.startsWith("UPDATE", ignoreCase = true) ||
            trimmed.startsWith("DELETE", ignoreCase = true)
    }

    override fun executeRaw(sql: String, parameters: List<Any?>) {
        if (!isCacheableWrite(sql)) {
            val stmt = connection.prepare(sql)
            try {
                stmt.bind(parameters)
                while (stmt.step()) Unit
            } finally {
                stmt.close()
            }
            return
        }
        val stmt = statementCache.remove(sql) ?: connection.prepare(sql)
        var healthy = false
        try {
            stmt.reset()
            stmt.clearBindings()
            stmt.bind(parameters)
            while (stmt.step()) Unit
            stmt.reset()
            stmt.clearBindings()
            healthy = true
        } finally {
            if (healthy) {
                statementCache[sql] = stmt
            } else {
                runCatching { stmt.close() }
            }
        }
    }

    override fun queryRaw(sql: String, parameters: List<Any?>): PikoTreeCursor {
        val stmt = connection.prepare(sql)
        try {
            stmt.bind(parameters)
        } catch (error: Throwable) {
            runCatching { stmt.close() }
            throw error
        }
        return object : PikoTreeCursor {
            private var closed = false
            override fun next(): Boolean = !closed && stmt.step()
            override fun string(index: Int): String? = if (closed || stmt.isNull(index)) null else stmt.getText(index)
            override fun long(index: Int): Long? = if (closed || stmt.isNull(index)) null else stmt.getLong(index)
            override fun close() {
                if (!closed) {
                    closed = true
                    runCatching { stmt.close() }
                }
            }
        }
    }

    override fun closeRaw() {
        val cached = statementCache.values.toList()
        statementCache.clear()
        for (stmt in cached) {
            runCatching { stmt.close() }
        }
        connection.close()
    }

    private companion object {
        const val MAX_CACHED_STATEMENTS = 96
    }
}
