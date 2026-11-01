package dev.piko.shared.data

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TreeCacheMigrationTest {
    @Test
    fun legacyVersionsStreamIntoSqliteAndRemoveOnlySuccessfullyMigratedFiles() = runBlocking {
        val directory = Files.createTempDirectory("piko-tree-migration").toFile()
        val cache = FilePikoCacheStore(directory.resolve("cache").path)
        val factory = DesktopPikoTreeDatabaseFactory(directory.resolve("db"))
        try {
            for (version in 1..2) {
                val account = "version$version@example.com"
                val key = DriveTreeIndex.snapshotKeyOf(account)
                cache.write(key, """{"schemaVersion":$version,"complete":true,"entries":[{"id":"file","n":"影片.mp4","s":"42","h":"ABC"}]}""")
                val index = DriveTreeIndex(database = factory.open(account), ownerAccount = account)
                try {
                    assertTrue(migrateLegacyTreeCache(index, account, cache))
                    assertEquals(42L, index.file("file")?.sizeBytes)
                    assertTrue(index.isComplete)
                    assertNull(cache.read(key))
                } finally { index.close() }
                val reopened = DriveTreeIndex(database = factory.open(account), ownerAccount = account)
                try { assertEquals(42L, reopened.file("file")?.sizeBytes) } finally { reopened.close() }
            }
        } finally { assertTrue(directory.deleteRecursively()) }
    }

    @Test
    fun ambiguousLegacyKeyIsDiscardedRatherThanAssignedToEitherCollidingAccount() = runBlocking {
        val directory = Files.createTempDirectory("piko-tree-ambiguous").toFile()
        val cache = FilePikoCacheStore(directory.resolve("cache").path)
        val factory = DesktopPikoTreeDatabaseFactory(directory.resolve("db"))
        try {
            for (account in listOf("a+b@example.com", "a_b@example.com")) {
                val key = DriveTreeIndex.snapshotKeyOf(account)
                cache.write(key, """{"schemaVersion":2,"complete":true,"entries":[{"id":"unowned","n":"private","s":"42"}]}""")
                val index = DriveTreeIndex(database = factory.open(account), ownerAccount = account)
                try {
                    assertFalse(migrateLegacyTreeCache(index, account, cache))
                    assertNull(index.file("unowned"))
                    assertFalse(index.isComplete)
                    assertNull(cache.read(key))
                } finally { index.close() }
            }
            assertTrue(isUnambiguousLegacyTreeAccount("safe-name@example.com"))
            assertFalse(isUnambiguousLegacyTreeAccount("name_@example.com"))
            assertFalse(isUnambiguousLegacyTreeAccount("用户@example.com"))
        } finally { assertTrue(directory.deleteRecursively()) }
    }
}
