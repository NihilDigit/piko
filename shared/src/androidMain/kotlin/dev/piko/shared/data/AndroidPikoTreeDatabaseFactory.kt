package dev.piko.shared.data

import android.annotation.TargetApi
import android.database.CursorWindow
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteCursorDriver
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDoneException
import android.database.sqlite.SQLiteProgram
import android.database.sqlite.SQLiteQuery
import android.database.sqlite.SQLiteStatement
import android.os.Build
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
            // SqliteTreeStore 固定使用约 60 条 SQL 模板，Android 默认缓存上限仅 25 会导致 queryRaw 反复重编译 SQLiteQuery
            opened.setMaxSqlCacheSize(SQLiteDatabase.MAX_SQL_CACHE_SIZE)
            check(opened.enableWriteAheadLogging()) { "Cannot enable SQLite WAL" }
            val database = AndroidTreeDatabase(opened, release)
            // Android SQLiteDatabase.compileStatement("PRAGMA ...") 在返回结果集型 PRAGMA 上可能抛异常，走 rawQuery 消费最稳妥。
            // 锁等待契约：进程内由 TreeDatabaseLeases 保证同一库文件单实例互斥打开，Android SQLiteConnectionPool
            // 内置 WAL 连接池与原生 sqlite3_busy_timeout（默认 2500ms）等待；显式对齐桌面/iOS 设为 5000ms 以防外部工具短暂持锁。
            database.query("PRAGMA busy_timeout=5000").close()
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

@TargetApi(Build.VERSION_CODES.P)
private object Api28SqliteWindow {
    fun createWindow(name: String, windowSizeBytes: Long): CursorWindow = CursorWindow(name, windowSizeBytes)

    fun enableForwardOnly(cursor: SQLiteCursor) {
        cursor.setFillWindowForwardOnly(true)
    }
}

private class ReusableWindowCursor(
    driver: SQLiteCursorDriver,
    editTable: String?,
    query: SQLiteQuery,
    reusableWindow: CursorWindow,
) : SQLiteCursor(driver, editTable, query) {
    init {
        mWindow = reusableWindow
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Api28SqliteWindow.enableForwardOnly(this)
        }
    }

    fun closeAndDetachWindow(): CursorWindow? {
        val detached = mWindow
        mWindow = null
        super.close()
        return detached
    }
}

private class AndroidTreeDatabase(
    private val connection: SQLiteDatabase,
    release: () -> Unit,
) : ManagedPikoTreeDatabase(release) {
    private val poolLock = Any()
    private var rawClosed = false

    private val statementCache = object : LinkedHashMap<String, SQLiteStatement>(96, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SQLiteStatement>): Boolean {
            if (size <= MAX_CACHED_STATEMENTS) return false
            runCatching { eldest.value.close() }
            return true
        }
    }

    private val pointWindowPool = ArrayDeque<CursorWindow>(MAX_POOLED_WINDOWS_PER_TIER)
    private val scanWindowPool = ArrayDeque<CursorWindow>(MAX_POOLED_WINDOWS_PER_TIER)

    private inline fun <T> withStatement(sql: String, cacheable: Boolean, block: (SQLiteStatement) -> T): T {
        if (!cacheable) {
            return connection.compileStatement(sql).use(block)
        }
        val statement = synchronized(poolLock) {
            if (rawClosed) null else statementCache.remove(sql)
        } ?: connection.compileStatement(sql)
        return try {
            statement.clearBindings()
            block(statement)
        } finally {
            runCatching { statement.clearBindings() }
            val discard = synchronized(poolLock) {
                if (rawClosed || statementCache.containsKey(sql)) {
                    true
                } else {
                    statementCache[sql] = statement
                    false
                }
            }
            if (discard) runCatching { statement.close() }
        }
    }

    private fun acquireWindow(pointQuery: Boolean): CursorWindow {
        synchronized(poolLock) {
            val pool = if (pointQuery) pointWindowPool else scanWindowPool
            pool.removeLastOrNull()?.let { window ->
                window.clear()
                return window
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val bytes = if (pointQuery) POINT_WINDOW_BYTES else SCAN_WINDOW_BYTES
            Api28SqliteWindow.createWindow(WINDOW_NAME, bytes)
        } else {
            CursorWindow(WINDOW_NAME)
        }
    }

    private fun releaseWindow(window: CursorWindow?, pointQuery: Boolean) {
        if (window == null) return
        val returned = synchronized(poolLock) {
            val pool = if (pointQuery) pointWindowPool else scanWindowPool
            if (!rawClosed && pool.size < MAX_POOLED_WINDOWS_PER_TIER) {
                runCatching { window.clear() }.isSuccess.also { ok ->
                    if (ok) pool.addLast(window)
                }
            } else {
                false
            }
        }
        if (!returned) {
            runCatching { window.close() }
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
        val cacheable = parameters.size <= MAX_CACHED_PARAMETER_ARITY
        return withStatement(sql, cacheable) { statement ->
            try {
                statement.bind(parameters)
                statement.simpleQueryForString()
            } catch (_: SQLiteDoneException) {
                null
            }
        }
    }

    override fun executeRaw(sql: String, parameters: List<Any?>) {
        val cacheable = parameters.size <= MAX_CACHED_PARAMETER_ARITY &&
            (parameters.isNotEmpty() || sql.startsWith("DELETE ") || sql.startsWith("INSERT "))
        withStatement(sql, cacheable) { statement ->
            statement.bind(parameters)
            statement.execute()
        }
    }

    override fun queryRaw(sql: String, parameters: List<Any?>): PikoTreeCursor {
        // rawQuery's String[] arguments lose NULL and blob types. Bind the compiled query instead.
        // 同时按查询形态池化复用 CursorWindow（点查 256 KiB / 扫描 4 MiB）并开启单向前填，避免每次查询反复
        // 分配与销毁 2 MiB ashmem 共享内存窗口，以及大结果集跨窗时回退 1/3 重扫带来的 O(W^2) 放大。
        val pointQuery = isPointOrSmallQuery(sql)
        var borrowedWindow: CursorWindow? = null
        var reusableCursor: ReusableWindowCursor? = null
        val cursor = try {
            connection.rawQueryWithFactory(
                { _, driver, editTable, query ->
                    query.bind(parameters)
                    val window = acquireWindow(pointQuery)
                    borrowedWindow = window
                    ReusableWindowCursor(driver, editTable, query, window).also { reusableCursor = it }
                },
                sql,
                emptyArray<String>(),
                "",
            )
        } catch (error: Throwable) {
            val created = reusableCursor
            if (created != null) {
                val detached = runCatching { created.closeAndDetachWindow() }.getOrNull()
                releaseWindow(detached, pointQuery)
            } else {
                releaseWindow(borrowedWindow, pointQuery)
            }
            throw error
        }
        return object : PikoTreeCursor {
            private var closed = false
            override fun next() = cursor.moveToNext()
            override fun string(index: Int): String? = if (cursor.isNull(index)) null else cursor.getString(index)
            override fun long(index: Int): Long? = if (cursor.isNull(index)) null else cursor.getLong(index)
            override fun close() {
                if (closed) return
                closed = true
                val reusable = reusableCursor
                if (reusable != null && reusable === cursor) {
                    val detached = reusable.closeAndDetachWindow()
                    releaseWindow(detached, pointQuery)
                } else {
                    cursor.close()
                }
            }
        }
    }

    override fun closeRaw() {
        val statements: List<SQLiteStatement>
        val windows: List<CursorWindow>
        synchronized(poolLock) {
            rawClosed = true
            statements = statementCache.values.toList()
            statementCache.clear()
            windows = buildList(pointWindowPool.size + scanWindowPool.size) {
                addAll(pointWindowPool)
                addAll(scanWindowPool)
            }
            pointWindowPool.clear()
            scanWindowPool.clear()
        }
        for (statement in statements) {
            runCatching { statement.close() }
        }
        for (window in windows) {
            runCatching { window.close() }
        }
        connection.close()
    }

    private companion object {
        const val WINDOW_NAME = "piko-tree"
        const val POINT_WINDOW_BYTES = 256L * 1024L
        const val SCAN_WINDOW_BYTES = 4L * 1024L * 1024L
        const val MAX_POOLED_WINDOWS_PER_TIER = 2
        const val MAX_CACHED_STATEMENTS = 96
        const val MAX_CACHED_PARAMETER_ARITY = 32

        fun isPointOrSmallQuery(sql: String): Boolean =
            sql.startsWith("PRAGMA ", ignoreCase = true) ||
                sql.endsWith("LIMIT 1", ignoreCase = true) ||
                sql.endsWith("LIMIT 128", ignoreCase = true) ||
                sql.endsWith("LIMIT 256", ignoreCase = true) ||
                sql.contains("WHERE generation=? AND id=?") ||
                sql.contains("WHERE generation=0 AND id=?") ||
                sql.contains("WHERE generation = ? AND id = ?") ||
                sql.contains("WHERE id=?") ||
                sql.contains("WHERE id = ?") ||
                sql.contains("FROM tree_subtree WHERE folder=?") ||
                sql.contains("FROM tree_subtree WHERE folder = ?") ||
                sql.contains("WHERE key=?") ||
                sql.contains("WHERE key = ?") ||
                sql.contains("AND gcid=? AND bytes=?") ||
                sql.contains("WHERE gcid=? AND bytes=?") ||
                sql.contains("WHERE gcid = ? AND bytes = ?")
    }
}
