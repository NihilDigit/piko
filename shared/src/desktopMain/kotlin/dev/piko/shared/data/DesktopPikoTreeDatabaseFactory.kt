package dev.piko.shared.data

import dev.piko.shared.PikoHome
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Types

class DesktopPikoTreeDatabaseFactory internal constructor(
    private val directory: File,
    private val connect: (String) -> Connection,
) : PikoTreeDatabaseFactory {
    constructor(directory: File = PikoHome.root.resolve("cache/trees").toFile()) :
        this(directory, DriverManager::getConnection)
    override fun open(account: String): PikoTreeDatabase {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create tree database directory" }
        val file = treeDatabaseFile(directory, account)
        val release = TreeDatabaseLeases.acquire(file)
        var connection: Connection? = null
        try {
            loadDriver()
            val opened = connect("jdbc:sqlite:${file.absolutePath}")
            connection = opened
            val database = JdbcTreeDatabase(opened, release)
            database.execute("PRAGMA busy_timeout=5000")
            database.query("PRAGMA journal_mode=WAL").use { while (it.next()) Unit }
            database.execute("PRAGMA synchronous=NORMAL")
            database.execute("PRAGMA temp_store=MEMORY")
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

    companion object {
        private val driver by lazy {
            val natives = PikoHome.root.resolve("cache/natives").toFile()
            check(natives.isDirectory || natives.mkdirs()) { "Cannot create SQLite native directory" }
            System.setProperty("org.sqlite.tmpdir", natives.absolutePath)
            // Direct reference also preserves the driver through release shrinking.
            Class.forName(org.sqlite.JDBC::class.java.name)
        }
        private fun loadDriver() { driver }
    }
}

private class JdbcTreeDatabase(
    private val connection: Connection,
    release: () -> Unit,
) : ManagedPikoTreeDatabase(release) {
    private class CachedQueryStatement(
        val sql: String,
        val statement: PreparedStatement,
        var inUse: Boolean = false,
        var retired: Boolean = false,
    )

    private val statementCache = object : LinkedHashMap<String, PreparedStatement>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PreparedStatement>): Boolean {
            if (size <= MAX_CACHED_STATEMENTS) return false
            runCatching { eldest.value.close() }
            return true
        }
    }

    private val queryCache = LinkedHashMap<String, CachedQueryStatement>(64, 0.75f, true)

    private fun evictQueryCacheIfNeeded() {
        if (queryCache.size <= MAX_CACHED_STATEMENTS) return
        val iter = queryCache.entries.iterator()
        while (queryCache.size > MAX_CACHED_STATEMENTS && iter.hasNext()) {
            val candidate = iter.next().value
            if (!candidate.inUse) {
                iter.remove()
                runCatching { candidate.statement.close() }
            }
        }
        if (queryCache.size > MAX_CACHED_STATEMENTS) {
            val fallbackIter = queryCache.entries.iterator()
            while (queryCache.size > MAX_CACHED_STATEMENTS && fallbackIter.hasNext()) {
                val active = fallbackIter.next().value
                active.retired = true
                fallbackIter.remove()
            }
        }
    }

    private fun clearIdleQueryCache() {
        val iter = queryCache.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next().value
            if (entry.inUse) {
                entry.retired = true
            } else {
                runCatching { entry.statement.close() }
            }
            iter.remove()
        }
    }

    private fun bindTo(statement: PreparedStatement, parameters: List<Any?>) {
        statement.clearParameters()
        parameters.forEachIndexed { index, value ->
            when (value) {
                null -> statement.setNull(index + 1, Types.NULL)
                is ByteArray -> statement.setBytes(index + 1, value)
                is Boolean -> statement.setLong(index + 1, if (value) 1 else 0)
                is Byte, is Short, is Int, is Long -> statement.setLong(index + 1, (value as Number).toLong())
                is Float, is Double -> statement.setDouble(index + 1, (value as Number).toDouble())
                is String -> statement.setString(index + 1, value)
                else -> error("Unsupported SQLite parameter type")
            }
        }
    }

    private fun prepareUncached(sql: String, parameters: List<Any?>): PreparedStatement {
        val statement = connection.prepareStatement(sql)
        try {
            bindTo(statement, parameters)
            return statement
        } catch (error: Throwable) {
            statement.close()
            throw error
        }
    }

    override fun executeRaw(sql: String, parameters: List<Any?>) {
        if (sql.startsWith("DROP ") || sql.startsWith("ALTER ")) {
            val statements = statementCache.values.toList()
            statementCache.clear()
            for (statement in statements) runCatching { statement.close() }
            clearIdleQueryCache()
        }
        // DDL / PRAGMA / dynamic statements with no bind params may alter schema; cache parameterized DML hot paths.
        if (parameters.isEmpty() && !sql.startsWith("DELETE ") && !sql.startsWith("INSERT ")) {
            prepareUncached(sql, parameters).use { it.execute() }
            return
        }
        val cached = statementCache.getOrPut(sql) { connection.prepareStatement(sql) }
        try {
            bindTo(cached, parameters)
            cached.execute()
        } finally {
            runCatching { cached.clearParameters() }
        }
    }

    override fun queryRaw(sql: String, parameters: List<Any?>): PikoTreeCursor {
        if (sql.startsWith("PRAGMA ") || sql.startsWith("EXPLAIN ")) {
            return executeUncachedQuery(sql, parameters)
        }
        val existing = queryCache[sql]
        if (existing != null && existing.inUse) {
            // Re-entrant / concurrent open cursor on the same SQL: isolate in a temporary statement.
            return executeUncachedQuery(sql, parameters)
        }
        val entry = if (existing != null) {
            existing.inUse = true
            existing
        } else {
            val created = CachedQueryStatement(sql, connection.prepareStatement(sql), inUse = true)
            queryCache[sql] = created
            evictQueryCacheIfNeeded()
            created
        }
        try {
            bindTo(entry.statement, parameters)
            val result = entry.statement.executeQuery()
            return object : PikoTreeCursor {
                private var closedCursor = false
                override fun next() = result.next()
                override fun string(index: Int): String? = result.getString(index + 1)
                override fun long(index: Int): Long? {
                    val value = result.getLong(index + 1)
                    return if (result.wasNull()) null else value
                }
                override fun close() {
                    if (closedCursor) return
                    closedCursor = true
                    var closeError: Throwable? = null
                    try {
                        result.close()
                    } catch (err: Throwable) {
                        closeError = err
                    } finally {
                        entry.inUse = false
                        if (entry.retired || closeError != null) {
                            if (queryCache[sql] === entry) queryCache.remove(sql)
                            runCatching { entry.statement.close() }
                        } else {
                            runCatching { entry.statement.clearParameters() }
                                .onFailure {
                                    if (queryCache[sql] === entry) queryCache.remove(sql)
                                    runCatching { entry.statement.close() }
                                }
                        }
                    }
                    closeError?.let { throw it }
                }
            }
        } catch (error: Throwable) {
            entry.inUse = false
            entry.retired = true
            if (queryCache[sql] === entry) queryCache.remove(sql)
            runCatching { entry.statement.close() }
            throw error
        }
    }

    private fun executeUncachedQuery(sql: String, parameters: List<Any?>): PikoTreeCursor {
        val statement = prepareUncached(sql, parameters)
        try {
            val result = statement.executeQuery()
            return object : PikoTreeCursor {
                private var closedCursor = false
                override fun next() = result.next()
                override fun string(index: Int): String? = result.getString(index + 1)
                override fun long(index: Int): Long? {
                    val value = result.getLong(index + 1)
                    return if (result.wasNull()) null else value
                }
                override fun close() {
                    if (closedCursor) return
                    closedCursor = true
                    try { result.close() } finally { statement.close() }
                }
            }
        } catch (error: Throwable) {
            statement.close()
            throw error
        }
    }

    override fun closeRaw() {
        val statements = statementCache.values.toList()
        statementCache.clear()
        for (statement in statements) {
            runCatching { statement.close() }
        }
        val queries = queryCache.values.toList()
        queryCache.clear()
        for (entry in queries) {
            entry.retired = true
            runCatching { entry.statement.close() }
        }
        connection.close()
    }

    private companion object {
        const val MAX_CACHED_STATEMENTS = 64
    }
}
