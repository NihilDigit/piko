package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakClient
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TreeAccountGenerationTest {
    @Test
    fun failedOpenClearsOldAccountAndCanRetry() = failedBindingCanRetry(failSchema = false)

    @Test
    fun failedSchemaClosesNewHandleClearsOldAccountAndCanRetry() = failedBindingCanRetry(failSchema = true)

    private fun failedBindingCanRetry(failSchema: Boolean) {
        val directory = Files.createTempDirectory("piko-binding-failure").toFile()
        val provider = object : PikoClientProvider {
            override val currentClient = MutableStateFlow<PikPakClient?>(null)
        }
        val real = DesktopPikoTreeDatabaseFactory(directory)
        var attempts = 0
        var failedHandleClosed = false
        val factory = object : PikoTreeDatabaseFactory {
            override fun open(account: String): PikoTreeDatabase = if (account == "B" && attempts++ == 0) {
                if (!failSchema) error("Injected open failure")
                val database = real.open(account)
                object : PikoTreeDatabase by database {
                    override fun execute(sql: String, parameters: List<Any?>) { error("Injected schema failure") }
                    override fun close() { failedHandleClosed = true; database.close() }
                }
            } else real.open(account)
        }
        val repository = PikoDriveRepository(provider, treeDatabaseFactory = factory)
        val executor = Executors.newSingleThreadExecutor()
        try {
            repository.enterAccount("A")
            repository.driveTreeIndex.putFolderListing("", listOf(FileStat(id = "old", name = "old", size = "1")))
            repository.folderIndex.learned("old-folder", "old", "", true, false)
            repository.enterAccount("B")
            assertNull(repository.driveTreeIndex.file("old"))
            assertTrue(repository.driveTreeIndex.flow.value.entries.isEmpty())
            assertNull(repository.cachedFiles("", PikoFileSortOrder.TIME_DESC))
            assertTrue(repository.folderIndex.flow.value.nodes.isEmpty())
            if (failSchema) assertTrue(failedHandleClosed)
            executor.submit { repository.enterAccount("B") }.get(5, TimeUnit.SECONDS)
            assertTrue(repository.driveTreeIndex.isPersistent)
            assertEquals(2, attempts)
            repository.driveTreeIndex.putFiles(listOf(FileStat(id = "new", name = "new", size = "2")))
            assertEquals("new", repository.driveTreeIndex.file("new")?.id)
        } finally {
            executor.shutdownNow()
            repository.enterAccount(null)
            assertTrue(directory.deleteRecursively())
        }
    }

    @Test
    fun lateStreamingPagesCannotWriteIntoAnotherAccountOrReopenedGeneration() {
        val directory = Files.createTempDirectory("piko-account-generations").toFile()
        val provider = object : PikoClientProvider {
            override val currentClient = MutableStateFlow<PikPakClient?>(null)
        }
        val repository = PikoDriveRepository(provider, treeDatabaseFactory = DesktopPikoTreeDatabaseFactory(directory))
        val executor = Executors.newSingleThreadExecutor()
        val firstPage = CountDownLatch(1)
        val latePage = CountDownLatch(1)
        try {
            repository.enterAccount("a+b@example.com")
            val old = repository.driveTreeIndex
            old.putFiles(listOf(FileStat(id = "saved-a", name = "saved", size = "1")))
            val writing = executor.submit {
                val candidate = old.beginCandidate()
                old.appendCandidate(candidate, List(500) { FileStat(id = "first-$it", name = "first", size = "1") })
                firstPage.countDown()
                check(latePage.await(10, TimeUnit.SECONDS))
                old.appendCandidate(candidate, List(500) { FileStat(id = "late-$it", name = "late", size = "1") })
                old.putFiles(listOf(FileStat(id = "late-direct", name = "late", size = "1")))
                old.commitCandidate(candidate, 1000)
                old.abortCandidate(candidate)
            }
            assertTrue(firstPage.await(10, TimeUnit.SECONDS))
            repository.enterAccount("a_b@example.com")
            val other = repository.driveTreeIndex
            assertNotSame(old, other)
            assertNull(other.file("saved-a"))
            other.putFiles(listOf(FileStat(id = "saved-b", name = "saved", size = "2")))
            repository.enterAccount("a+b@example.com")
            val reopened = repository.driveTreeIndex
            assertNotSame(old, reopened)
            latePage.countDown()
            writing.get(10, TimeUnit.SECONDS)
            assertEquals("saved-a", reopened.file("saved-a")?.id)
            assertNull(reopened.file("saved-b"))
            assertNull(reopened.file("late-direct"))
            assertNull(reopened.file("late-0"))
            assertNull(reopened.file("first-0"))
            repository.enterAccount("a_b@example.com")
            assertEquals("saved-b", repository.driveTreeIndex.file("saved-b")?.id)
            assertNull(repository.driveTreeIndex.file("late-direct"))
        } finally {
            latePage.countDown()
            executor.shutdownNow()
            repository.enterAccount(null)
            assertTrue(directory.deleteRecursively(), "All SQLite handles must be closed before cleanup")
        }
    }
}
