@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.piko.shared.data

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosPikoTreeDatabaseFactoryTest {
    @Test fun bindingsNestedRollbackAndWalUseRealSqlite() = fixture { factory, _ ->
        factory.open("bindings").use { db ->
            db.execute("CREATE TABLE test(label TEXT, number INTEGER, missing TEXT, blob BLOB)")
            assertFailsWith<IllegalStateException> {
                db.transaction {
                    db.execute("INSERT INTO test VALUES ('discard', 0, NULL, NULL)")
                    error("rollback")
                }
            }
            db.transaction {
                db.execute("INSERT INTO test VALUES (?, ?, ?, ?)", listOf("quote' ? 中文\u0000tail", 42L, null, byteArrayOf(0, 1, 2)))
                assertFailsWith<IllegalStateException> {
                    db.transaction {
                        db.execute("INSERT INTO test VALUES ('discard', 0, NULL, NULL)")
                        error("nested rollback")
                    }
                }
            }
            db.query("SELECT label, number, missing, hex(blob) FROM test").use {
                assertTrue(it.next())
                assertEquals("quote' ? 中文\u0000tail", it.string(0))
                assertEquals(42L, it.long(1))
                assertNull(it.string(2))
                assertNull(it.long(2))
                assertEquals("000102", it.string(3))
                assertFalse(it.next())
                assertFalse(it.next())
            }
            assertEquals("wal", db.scalarString("PRAGMA journal_mode"))
            assertEquals("1", db.scalarString("PRAGMA synchronous"))
            assertEquals("2", db.scalarString("PRAGMA temp_store"))
            assertEquals("5000", db.scalarString("PRAGMA busy_timeout"))
            db.execute("INSERT INTO test VALUES (?, ?, ?, ?)", listOf("", true, 1.5, byteArrayOf()))
            assertEquals("0", db.scalarString("SELECT length(blob) FROM test WHERE label = ''"))
        }
    }

    @Test fun storeInitializationActuallyTruncatesWalBeforeConnectionCloses() = fixture { factory, root ->
        factory.open("checkpoint").use { db ->
            db.execute("PRAGMA wal_autocheckpoint=0")
            db.execute("CREATE TABLE checkpoint_test(value TEXT)")
            db.execute("INSERT INTO checkpoint_test VALUES (?)", listOf("x".repeat(8192)))
            val wal = SystemFileSystem.list(Path(root)).single { it.name.endsWith(".db-wal") }
            assertTrue((SystemFileSystem.metadataOrNull(wal)?.size ?: 0L) > 0L)
            SqliteTreeStore(db, "checkpoint")
            assertEquals(0L, SystemFileSystem.metadataOrNull(wal)?.size)
            assertEquals("8192", db.scalarString("SELECT length(value) FROM checkpoint_test"))
        }
    }

    @Test fun pathHashSeparatesAccountsAndStoreChecksOwner() = fixture { factory, root ->
        for (account in listOf("a+b@example.test", "a_b@example.test")) {
            factory.open(account).use { db ->
                SqliteTreeStore(db, account)
                assertEquals(account, db.scalarString("SELECT value FROM tree_meta WHERE key='owner_account'"))
            }
        }
        val names = SystemFileSystem.list(Path(root)).map { it.name }.filter { it.endsWith(".db") }
        assertEquals(2, names.size)
        assertTrue(names.all { it.matches(Regex("drive-tree-[A-Za-z0-9_]{1,24}-[0-9a-f]{16}\\.db")) })
        factory.open("a+b@example.test").use { db ->
            SqliteTreeStore(db, "replacement-owner")
            assertEquals("replacement-owner", db.scalarString("SELECT value FROM tree_meta WHERE key='owner_account'"))
        }
    }

    @Test fun closeInvalidatesCursorsAndLateWritesAndReleasesLease() = fixture { factory, _ ->
        val first = factory.open("A")
        first.execute("CREATE TABLE test(value INTEGER)")
        first.execute("INSERT INTO test VALUES (1)")
        val cursor = first.query("SELECT value FROM test")
        assertTrue(cursor.next())
        first.close()
        first.close()
        assertFalse(cursor.next())
        assertNull(cursor.long(0))
        cursor.close()
        factory.open("A").use { next ->
            first.execute("INSERT INTO test VALUES (2)")
            first.transaction { first.execute("INSERT INTO test VALUES (3)") }
            first.query("SELECT * FROM test").use { assertFalse(it.next()) }
            assertEquals("1", next.scalarString("SELECT count(*) FROM test"))
        }
    }

    private fun fixture(block: (IosPikoTreeDatabaseFactory, String) -> Unit) {
        val root = NSTemporaryDirectory().trimEnd('/') + "/piko-tree-test-" + NSUUID().UUIDString
        try {
            block(IosPikoTreeDatabaseFactory(root), root)
        } finally {
            if (NSFileManager.defaultManager.fileExistsAtPath(root)) {
                check(NSFileManager.defaultManager.removeItemAtPath(root, error = null))
            }
        }
    }
}
