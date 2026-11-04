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
