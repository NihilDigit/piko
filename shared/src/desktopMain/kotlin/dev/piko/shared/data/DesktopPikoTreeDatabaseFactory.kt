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
    private val statementCache = object : LinkedHashMap<String, PreparedStatement>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PreparedStatement>): Boolean {
            if (size <= MAX_CACHED_STATEMENTS) return false
            runCatching { eldest.value.close() }
            return true
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
        val statement = prepareUncached(sql, parameters)
        try {
            val result = statement.executeQuery()
            return object : PikoTreeCursor {
                override fun next() = result.next()
                override fun string(index: Int): String? = result.getString(index + 1)
                override fun long(index: Int): Long? {
                    val value = result.getLong(index + 1)
                    return if (result.wasNull()) null else value
                }
                override fun close() {
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
        connection.close()
    }

    private companion object {
        const val MAX_CACHED_STATEMENTS = 64
    }
}
