package dev.piko.shared.data

interface PikoTreeDatabaseFactory {
    fun open(account: String): PikoTreeDatabase
}

/** Synchronous SQLite access. Column indices are zero-based; callers must close cursors. */
interface PikoTreeDatabase : AutoCloseable {
    fun execute(sql: String, parameters: List<Any?> = emptyList())
    fun <T> transaction(block: () -> T): T
    fun query(sql: String, parameters: List<Any?> = emptyList()): PikoTreeCursor
    fun scalarString(sql: String, parameters: List<Any?> = emptyList()): String? {
        val cursor = query(sql, parameters)
        return try {
            if (cursor.next()) cursor.string(0) else null
        } finally {
            cursor.close()
        }
    }
    override fun close()
}

interface PikoTreeCursor : AutoCloseable {
    fun next(): Boolean
    fun string(index: Int): String?
    fun long(index: Int): Long?
    override fun close()
}

internal object EmptyTreeCursor : PikoTreeCursor {
    override fun next() = false
    override fun string(index: Int): String? = null
    override fun long(index: Int): Long? = null
    override fun close() = Unit
}
