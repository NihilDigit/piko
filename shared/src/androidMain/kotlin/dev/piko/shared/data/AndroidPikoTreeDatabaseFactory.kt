package dev.piko.shared.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDoneException
import android.database.sqlite.SQLiteProgram
import android.database.sqlite.SQLiteStatement
import java.io.File

class AndroidPikoTreeDatabaseFactory(private val directory: File) : PikoTreeDatabaseFactory {
    override fun open(account: String): PikoTreeDatabase {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create tree database directory" }
        val file = treeDatabaseFile(directory, account)
        val release = TreeDatabaseLeases.acquire(file)
        var connection: SQLiteDatabase? = null
        try {
            val opened = SQLiteDatabase.openOrCreateDatabase(file, null)
            connection = opened
            check(opened.enableWriteAheadLogging()) { "Cannot enable SQLite WAL" }
            val database = AndroidTreeDatabase(opened, release)
            // Android SQLiteDatabase.compileStatement("PRAGMA ...") 在返回结果集型 PRAGMA 上可能抛异常，走 rawQuery 消费最稳妥
            database.query("PRAGMA synchronous=NORMAL").close()
            database.query("PRAGMA temp_store=MEMORY").close()
            database.query("PRAGMA cache_size=-8192").close()
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
}

private fun SQLiteProgram.bind(parameters: List<Any?>) {
    parameters.forEachIndexed { index, value ->
        when (value) {
            null -> bindNull(index + 1)
            is ByteArray -> bindBlob(index + 1, value)
            is Boolean -> bindLong(index + 1, if (value) 1 else 0)
            is Byte, is Short, is Int, is Long -> bindLong(index + 1, (value as Number).toLong())
            is Float, is Double -> bindDouble(index + 1, (value as Number).toDouble())
            is String -> bindString(index + 1, value)
            else -> error("Unsupported SQLite parameter type")
        }
    }
}

private class AndroidTreeDatabase(
    private val connection: SQLiteDatabase,
    release: () -> Unit,
) : ManagedPikoTreeDatabase(release) {
    private val statementCache = object : LinkedHashMap<String, SQLiteStatement>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SQLiteStatement>): Boolean {
            if (size <= MAX_CACHED_STATEMENTS) return false
            runCatching { eldest.value.close() }
            return true
        }
    }

    override fun beginOuterTransaction() {
        connection.beginTransactionNonExclusive()
    }

    override fun commitOuterTransaction() {
        connection.setTransactionSuccessful()
        connection.endTransaction()
    }

    override fun rollbackOuterTransaction() {
        connection.endTransaction()
    }

    override fun scalarStringRaw(sql: String, parameters: List<Any?>): String? {
        // 复用预编译 SQLiteStatement 直接读标量字符串，彻底省去每次查询分配 2 MiB CursorWindow 与重新编译 SQL 的开销
        val statement = statementCache.getOrPut(sql) { connection.compileStatement(sql) }
        return try {
            statement.clearBindings()
            statement.bind(parameters)
            statement.simpleQueryForString()
        } catch (_: SQLiteDoneException) {
            null
        } finally {
            runCatching { statement.clearBindings() }
        }
    }

    override fun executeRaw(sql: String, parameters: List<Any?>) {
        if (parameters.isEmpty() && !sql.startsWith("DELETE ") && !sql.startsWith("INSERT ")) {
            connection.compileStatement(sql).use { it.execute() }
            return
        }
        val statement = statementCache.getOrPut(sql) { connection.compileStatement(sql) }
        try {
            statement.clearBindings()
            statement.bind(parameters)
            statement.execute()
        } finally {
            runCatching { statement.clearBindings() }
        }
    }

    override fun queryRaw(sql: String, parameters: List<Any?>): PikoTreeCursor {
        // rawQuery's String[] arguments lose NULL and blob types. Bind the compiled query instead.
        val cursor = connection.rawQueryWithFactory(
            { _, driver, editTable, query ->
                query.bind(parameters)
                android.database.sqlite.SQLiteCursor(driver, editTable, query)
            },
            sql,
            emptyArray<String>(),
            "",
        )
        return object : PikoTreeCursor {
            override fun next() = cursor.moveToNext()
            override fun string(index: Int): String? = if (cursor.isNull(index)) null else cursor.getString(index)
            override fun long(index: Int): Long? = if (cursor.isNull(index)) null else cursor.getLong(index)
            override fun close() = cursor.close()
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
