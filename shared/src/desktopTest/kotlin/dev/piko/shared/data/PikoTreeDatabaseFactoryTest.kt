package dev.piko.shared.data

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PikoTreeDatabaseFactoryTest {
    private fun withFactory(block: (DesktopPikoTreeDatabaseFactory) -> Unit) {
        val directory = Files.createTempDirectory("piko-tree-test").toFile()
        try { block(DesktopPikoTreeDatabaseFactory(directory)) } finally { directory.deleteRecursively() }
    }

    @Test
    fun failedPhysicalCloseSealsAccessAndReleasesLeaseExactlyOnce() {
        val directory = Files.createTempDirectory("piko-close-failure").toFile()
        val executor = Executors.newSingleThreadExecutor()
        var releases = 0
        val release = TreeDatabaseLeases.acquire(treeDatabaseFile(directory, "account"))
        val database = object : ManagedPikoTreeDatabase({ releases++; release() }) {
            override fun executeRaw(sql: String, parameters: List<Any?>) = error("Unexpected access")
            override fun queryRaw(sql: String, parameters: List<Any?>): PikoTreeCursor = error("Unexpected access")
            override fun closeRaw(): Unit = error("Injected close failure")
        }
        try {
            assertFailsWith<IllegalStateException> { database.close() }
            database.close()
            database.execute("late write")
            database.query("late read").use { assertFalse(it.next()) }
            assertEquals(1, releases)
            executor.submit { DesktopPikoTreeDatabaseFactory(directory).open("account").close() }.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedInitializationAndCleanupStillReleaseFactoryLease() {
        val directory = Files.createTempDirectory("piko-init-failure").toFile()
        val executor = Executors.newSingleThreadExecutor()
        val connection = java.lang.reflect.Proxy.newProxyInstance(
            java.sql.Connection::class.java.classLoader, arrayOf(java.sql.Connection::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "close" -> error("Injected cleanup failure")
                else -> error("Injected initialization failure")
            }
        } as java.sql.Connection
        try {
            val failure = assertFailsWith<IllegalStateException> {
                DesktopPikoTreeDatabaseFactory(directory) { connection }.open("account")
            }
            assertEquals("Injected cleanup failure", failure.suppressed.single().message)
            executor.submit { DesktopPikoTreeDatabaseFactory(directory).open("account").close() }.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }

    @Test
    fun accountNamesWithSameReadablePrefixHaveSeparateDatabases() = withFactory { factory ->
        val first = "a+b@example.com"
        val second = "a_b@example.com"
        assertNotEquals(treeDatabaseFile(java.io.File("."), first).name, treeDatabaseFile(java.io.File("."), second).name)
        assertTrue(treeDatabaseFile(java.io.File("."), first).name.matches(Regex("drive-tree-[A-Za-z0-9_]{1,24}-[0-9a-f]{16}\\.db")))
        factory.open(first).use { database ->
            database.execute("CREATE TABLE test(value TEXT)")
            database.execute("INSERT INTO test VALUES (?)", listOf("first"))
        }
        factory.open(second).use { database ->
            database.execute("CREATE TABLE test(value TEXT)")
            database.execute("INSERT INTO test VALUES (?)", listOf("second"))
        }
        factory.open(first).use { database ->
            database.query("SELECT value FROM test").use {
                assertTrue(it.next())
                assertEquals("first", it.string(0))
                assertFalse(it.next())
            }
        }
    }

    @Test
    fun separateFactoryInstancesShareTheSamePathLease() {
        val directory = Files.createTempDirectory("piko-tree-lease-test").toFile()
        val firstFactory = DesktopPikoTreeDatabaseFactory(directory)
        val secondFactory = DesktopPikoTreeDatabaseFactory(directory)
        val executor = Executors.newSingleThreadExecutor()
        val first = firstFactory.open("same-account")
        try {
            val attempted = CountDownLatch(1)
            val pending = executor.submit<PikoTreeDatabase> {
                attempted.countDown()
                secondFactory.open("same-account")
            }
            assertTrue(attempted.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { pending.get(100, TimeUnit.MILLISECONDS) }
            first.close()
            pending.get(5, TimeUnit.SECONDS).close()
        } finally {
            first.close()
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }

    @Test
    fun bindingNullsNumbersBlobsAndRollback() = withFactory { factory ->
        factory.open("bindings").use { database ->
            database.execute("CREATE TABLE test(label TEXT, number INTEGER, missing TEXT, blob BLOB)")
            assertFailsWith<IllegalStateException> {
                database.transaction {
                    database.execute("INSERT INTO test VALUES ('outer rollback', 0, NULL, NULL)")
                    error("rollback")
                }
            }
            database.transaction {
                database.execute("INSERT INTO test VALUES (?, ?, ?, ?)", listOf("quote' ? 中文", 42L, null, byteArrayOf(0, 1, 2)))
                assertFailsWith<IllegalStateException> {
                    database.transaction {
                        database.execute("INSERT INTO test VALUES ('rollback', 0, NULL, NULL)")
                        error("rollback")
                    }
                }
            }
            database.query("SELECT label, number, missing, hex(blob) FROM test WHERE number = ? AND missing IS ?", listOf(42, null)).use {
                assertTrue(it.next())
                assertEquals("quote' ? 中文", it.string(0))
                assertEquals(42L, it.long(1))
                assertNull(it.string(2))
                assertNull(it.long(2))
                assertEquals("000102", it.string(3))
                assertFalse(it.next())
            }
            database.query("PRAGMA journal_mode").use { assertTrue(it.next()); assertEquals("wal", it.string(0)) }
            database.query("PRAGMA synchronous").use { assertTrue(it.next()); assertEquals(1L, it.long(0)) }
            database.query("PRAGMA temp_store").use { assertTrue(it.next()); assertEquals(2L, it.long(0)) }
            database.query("PRAGMA busy_timeout").use { assertTrue(it.next()); assertEquals(5000L, it.long(0)) }
        }
    }

    @Test
    fun rapidABAWaitsForFirstGenerationAndDropsLateWrites() = withFactory { factory ->
        val first = factory.open("A")
        val other = factory.open("B")
        val executor = Executors.newSingleThreadExecutor()
        try {
            first.execute("CREATE TABLE test(value INTEGER)")
            first.execute("INSERT INTO test VALUES (1)")
            other.execute("CREATE TABLE test(value INTEGER)")
            val attempted = CountDownLatch(1)
            val reopened = executor.submit<PikoTreeDatabase> {
                attempted.countDown()
                factory.open("A")
            }
            assertTrue(attempted.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { reopened.get(100, TimeUnit.MILLISECONDS) }
            first.close()
            reopened.get(5, TimeUnit.SECONDS).use { next ->
                first.execute("INSERT INTO test VALUES (2)")
                first.transaction { first.execute("INSERT INTO test VALUES (3)") }
                first.query("SELECT * FROM test").use { assertFalse(it.next()); assertNull(it.long(0)) }
                next.query("SELECT count(*) FROM test").use { assertTrue(it.next()); assertEquals(1L, it.long(0)) }
            }
        } finally {
            first.close()
            other.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun closeWaitsForTransactionAndInvalidatesOutstandingCursors() = withFactory { factory ->
        val database = factory.open("transaction")
        database.execute("CREATE TABLE test(value INTEGER)")
        val executor = Executors.newFixedThreadPool(2)
        val inside = CountDownLatch(1)
        val finish = CountDownLatch(1)
        try {
            val writing = executor.submit {
                database.transaction {
                    database.execute("INSERT INTO test VALUES (1)")
                    inside.countDown()
                    check(finish.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(inside.await(5, TimeUnit.SECONDS))
            val closing = executor.submit { database.close() }
            assertFailsWith<TimeoutException> { closing.get(100, TimeUnit.MILLISECONDS) }
            finish.countDown()
            writing.get(5, TimeUnit.SECONDS)
            closing.get(5, TimeUnit.SECONDS)
            factory.open("transaction").use { next ->
                val cursor = next.query("SELECT value FROM test")
                assertTrue(cursor.next())
                next.close()
                assertFalse(cursor.next())
                assertNull(cursor.long(0))
                cursor.close()
            }
        } finally {
            finish.countDown()
            database.close()
            executor.shutdownNow()
        }
    }
}
