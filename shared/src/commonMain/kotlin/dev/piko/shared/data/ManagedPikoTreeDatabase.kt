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

    internal inner class ManagedCursor(private val raw: PikoTreeCursor) : PikoTreeCursor {
        private var cursorClosed = false

        @PublishedApi
        internal val dbLock: PikoLock
            get() = lock

        @PublishedApi
        internal val directView: PikoTreeCursor = object : PikoTreeCursor {
            override fun next(): Boolean = stepUnlocked()
            override fun string(index: Int): String? = if (cursorClosed || closed) null else raw.string(index)
            override fun long(index: Int): Long? = if (cursorClosed || closed) null else raw.long(index)
            override fun close() = closeUnlocked()
        }

        @PublishedApi
        internal fun stepUnlocked(): Boolean = !cursorClosed && !closed && raw.next()

        @PublishedApi
        internal fun isOpenUnlocked(): Boolean = !cursorClosed && !closed

        @PublishedApi
        internal fun closeUnlocked() {
            if (!cursorClosed) {
                cursorClosed = true
                cursors.remove(this)
                raw.close()
            }
        }

        override fun next(): Boolean = withPikoLock(lock) { stepUnlocked() }
        override fun string(index: Int): String? = withPikoLock(lock) { directView.string(index) }
        override fun long(index: Int): Long? = withPikoLock(lock) { directView.long(index) }
        override fun close() = withPikoLock(lock) { closeUnlocked() }

        internal inline fun <R> useLocked(block: (PikoTreeCursor) -> R): R = withPikoLock(dbLock) {
            try {
                block(directView)
            } finally {
                closeUnlocked()
            }
        }

        internal inline fun forEachRow(chunkSize: Int = 256, consume: (PikoTreeCursor) -> Unit) {
            try {
                while (true) {
                    val hasMore = withPikoLock(dbLock) {
                        var count = 0
                        var advanced = false
                        while (count < chunkSize) {
                            if (!stepUnlocked()) {
                                advanced = false
                                break
                            }
                            consume(directView)
                            count++
                            advanced = true
                        }
                        advanced && isOpenUnlocked()
                    }
                    if (!hasMore) break
                }
            } finally {
                close()
            }
        }
    }

    override fun query(sql: String, parameters: List<Any?>): PikoTreeCursor = withPikoLock(lock) {
        if (closed) return@withPikoLock EmptyTreeCursor
        val cursor = ManagedCursor(queryRaw(sql, parameters))
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
