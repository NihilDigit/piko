package dev.piko.shared.data

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

internal fun treeDatabaseFile(directory: File, account: String): File {
    val prefix = account.map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9') it else '_' }
        .joinToString("").take(24).ifEmpty { "account" }
    val hash = MessageDigest.getInstance("SHA-256").digest(account.toByteArray(Charsets.UTF_8))
        .take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return File(directory, "drive-tree-$prefix-$hash.db")
}

/** A lease spans open through physical close, including across factory instances. */
internal object TreeDatabaseLeases {
    private val leases = ConcurrentHashMap<String, Semaphore>()

    fun acquire(file: File): () -> Unit {
        val semaphore = leases.computeIfAbsent(file.canonicalPath) { Semaphore(1, true) }
        semaphore.acquire()
        return { semaphore.release() }
    }
}

/** Serializes transactions, cursor access and close on the same connection. */
internal abstract class ManagedPikoTreeDatabase(private val release: () -> Unit) : PikoTreeDatabase {
    private val lock = Any()
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

    override fun execute(sql: String, parameters: List<Any?>) = synchronized(lock) {
        if (!closed) executeRaw(sql, parameters)
    }

    override fun scalarString(sql: String, parameters: List<Any?>): String? = synchronized(lock) {
        if (closed) null else scalarStringRaw(sql, parameters)
    }

    override fun <T> transaction(block: () -> T): T = synchronized(lock) {
        // Late account work still returns its result, but all database access is inert.
        if (closed) return@synchronized block()
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

    override fun query(sql: String, parameters: List<Any?>): PikoTreeCursor = synchronized(lock) {
        if (closed) return@synchronized EmptyTreeCursor
        val raw = queryRaw(sql, parameters)
        val cursor = object : PikoTreeCursor {
            private var cursorClosed = false
            override fun next(): Boolean = synchronized(lock) { !cursorClosed && !closed && raw.next() }
            override fun string(index: Int): String? = synchronized(lock) {
                if (cursorClosed || closed) null else raw.string(index)
            }
            override fun long(index: Int): Long? = synchronized(lock) {
                if (cursorClosed || closed) null else raw.long(index)
            }
            override fun close() = synchronized(lock) {
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

    override fun close(): Unit = synchronized(lock) {
        if (closed) return@synchronized
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
