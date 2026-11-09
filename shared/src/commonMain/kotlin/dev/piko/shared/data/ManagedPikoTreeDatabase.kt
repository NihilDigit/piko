package dev.piko.shared.data

import dev.piko.shared.util.PikoLock
import dev.piko.shared.util.withPikoLock

/** Serializes transactions, cursor access and close on the same connection. */
internal abstract class ManagedPikoTreeDatabase(private val release: () -> Unit) : PikoTreeDatabase {
    private val lock = PikoLock()
    private var closed = false
    private var transactionDepth = 0
    private val cursors = mutableSetOf<PikoTreeCursor>()

    protected abstract fun executeRaw(sql: String, parameters: List<Any?>)
    protected abstract fun queryRaw(sql: String, parameters: List<Any?>): PikoTreeCursor
    protected abstract fun closeRaw()
    protected open fun beginOuterTransaction() = executeRaw("BEGIN IMMEDIATE", emptyList())
    protected open fun commitOuterTransaction() = executeRaw("COMMIT", emptyList())
    protected open fun rollbackOuterTransaction() = executeRaw("ROLLBACK", emptyList())
    protected open fun scalarStringRaw(sql: String, parameters: List<Any?>): String? {
        val raw = queryRaw(sql, parameters)
        return try {
            if (raw.next()) raw.string(0) else null
        } finally {
            raw.close()
        }
    }

    override fun execute(sql: String, parameters: List<Any?>) = withPikoLock(lock) {
        if (!closed) executeRaw(sql, parameters)
    }

    override fun scalarString(sql: String, parameters: List<Any?>): String? = withPikoLock(lock) {
        if (closed) null else scalarStringRaw(sql, parameters)
    }

    override fun <T> transaction(block: () -> T): T = withPikoLock(lock) {
        // Late account work still returns its result, but all database access is inert.
        if (closed) return@withPikoLock block()
        val depth = transactionDepth
        val savepoint = "piko_tree_$depth"
        if (depth == 0) beginOuterTransaction() else executeRaw("SAVEPOINT $savepoint", emptyList())
        transactionDepth++
        try {
            val result = block()
            if (depth == 0) commitOuterTransaction() else executeRaw("RELEASE SAVEPOINT $savepoint", emptyList())
            result
        } catch (error: Throwable) {
            try {
                if (depth == 0) {
                    rollbackOuterTransaction()
                } else {
                    executeRaw("ROLLBACK TO SAVEPOINT $savepoint", emptyList())
                    executeRaw("RELEASE SAVEPOINT $savepoint", emptyList())
                }
            } catch (rollback: Throwable) {
                error.addSuppressed(rollback)
            }
            throw error
        } finally {
            transactionDepth--
        }
    }

    override fun query(sql: String, parameters: List<Any?>): PikoTreeCursor = withPikoLock(lock) {
        if (closed) return@withPikoLock EmptyTreeCursor
        val raw = queryRaw(sql, parameters)
        val cursor = object : PikoTreeCursor {
            private var cursorClosed = false
            override fun next(): Boolean = withPikoLock(lock) { !cursorClosed && !closed && raw.next() }
            override fun string(index: Int): String? = withPikoLock(lock) {
                if (cursorClosed || closed) null else raw.string(index)
            }
            override fun long(index: Int): Long? = withPikoLock(lock) {
                if (cursorClosed || closed) null else raw.long(index)
            }
            override fun close() = withPikoLock(lock) {
                if (!cursorClosed) {
                    cursorClosed = true
                    cursors.remove(this)
                    raw.close()
                }
            }
        }
        cursors.add(cursor)
        cursor
    }

    override fun close(): Unit = withPikoLock(lock) {
        if (closed) return@withPikoLock
        check(transactionDepth == 0) { "Cannot close a tree database from inside its transaction" }
        closed = true
        var failure: Throwable? = null
        fun record(error: Throwable) {
            val previous = failure
            if (previous == null) failure = error else previous.addSuppressed(error)
        }
        cursors.toList().forEach { cursor ->
            try { cursor.close() } catch (error: Throwable) { record(error) }
        }
        // Access is already sealed, so even a failing physical close must end the lease.
        try {
            try { closeRaw() } finally { release() }
        } catch (error: Throwable) {
            record(error)
        }
        failure?.let { throw it }
        Unit
    }
}
