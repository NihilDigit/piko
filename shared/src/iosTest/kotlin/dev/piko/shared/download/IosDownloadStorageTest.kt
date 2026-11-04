@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.piko.shared.download

import dev.piko.shared.preferences.IosPreferenceSettings
import dev.piko.shared.storage.IosAppDirectories
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real sandbox files and isolated Foundation defaults; no network or credentials. */
class IosDownloadStorageTest {
    @Test fun downloadSelectionAndCommitSurviveASelectionChange() = fixture { defaults, dirs ->
        runBlocking {
            val settings = IosPreferenceSettings(defaults, dirs.downloads)
            val storage = createIosDownloadStorage(dirs, defaults)
            settings.setDownloadDirectory("${dirs.downloads}/selected")
            val target = storage.downloadTarget("folder/video.mp4")
            assertEquals("${dirs.downloads}/selected/folder/video.mp4", target)
            SystemFileSystem.sink(Path(target)).buffered().use { it.writeString("video") }
            assertEquals(5L, storage.existingLength("folder/video.mp4"))
            settings.setDownloadDirectory("")
            assertEquals(target, storage.commit("folder/video.mp4", target))
            assertEquals("${dirs.downloads}/next.bin", storage.pathFor("next.bin"))
            assertTrue(storage.delete(target))
            assertFalse(SystemFileSystem.exists(Path(target)))
        }
    }

    @Test fun sparseCacheIsOutsideDocumentsAndCanBeRecreatedAfterPurge() = fixture { defaults, dirs ->
        runBlocking {
            val storage = createIosDownloadStorage(dirs, defaults)
            val cache = storage.cacheTarget("content-identity")
            assertEquals("${dirs.caches}/downloads/content-identity.data", cache)
            assertFalse(SystemFileSystem.exists(Path(dirs.downloads)))
            SystemFileSystem.sink(Path(cache)).buffered().use { it.writeString("partial") }
            assertTrue(storage.delete(cache))
            check(cache == "${dirs.caches}/downloads/content-identity.data")
            SystemFileSystem.delete(Path(dirs.caches, "downloads"))
            SystemFileSystem.delete(Path(dirs.caches))
            assertFalse(SystemFileSystem.exists(Path(dirs.caches)))
            assertEquals(cache, createIosDownloadStorage(dirs, defaults).cacheTarget("content-identity"))
            assertTrue(SystemFileSystem.exists(Path(dirs.caches, "downloads")))
        }
    }

    @Test fun missingQueriesNeverCreateDocumentsOrCacheDirectories() = fixture { defaults, dirs ->
        runBlocking {
            val storage = createIosDownloadStorage(dirs, defaults)
            assertEquals(0L, storage.existingLength("missing/file"))
            assertFalse(storage.exists("missing/file"))
            assertNull(storage.locate(""))
            assertFalse(SystemFileSystem.exists(Path(dirs.downloads)))
            assertFalse(SystemFileSystem.exists(Path(dirs.caches)))
        }
    }

    @Test fun selectedFolderIsResolvedInTheCurrentContainer() = fixture { defaults, dirs ->
        runBlocking {
            IosPreferenceSettings(defaults, dirs.downloads)
                .setDownloadDirectory("${dirs.downloads}/selected")
            val relocated = IosAppDirectories(
                dirs.support + "-relocated",
                dirs.caches + "-relocated",
                dirs.downloads + "-relocated",
            )
            val storage = createIosDownloadStorage(relocated, defaults)
            val target = storage.downloadTarget("next.mp4")
            assertEquals("${relocated.downloads}/selected/next.mp4", target)
            assertFalse(target.startsWith(dirs.downloads + "/"))
            assertFalse(SystemFileSystem.exists(Path(target)))
        }
    }

    @Test fun selectedPosixFolderMayContainAColon() = fixture { defaults, dirs ->
        runBlocking {
            val selected = "${dirs.downloads}/Directors:Cut"
            IosPreferenceSettings(defaults, dirs.downloads).setDownloadDirectory(selected)
            val storage = createIosDownloadStorage(dirs, defaults)
            assertEquals("$selected/movie.mp4", storage.downloadTarget("movie.mp4"))
            assertEquals(selected, storage.locate(""))
        }
    }

    private fun fixture(block: (NSUserDefaults, IosAppDirectories) -> Unit) {
        val id = NSUUID().UUIDString
        val suite = "dev.piko.tests.downloads.$id"
        val defaults = NSUserDefaults(suiteName = suite)
        val prefix = NSTemporaryDirectory().trimEnd('/') + "/piko-downloads-"
        val root = prefix + id
        val dirs = IosAppDirectories("$root/support", "$root/cache", "$root/documents")
        try {
            block(defaults, dirs)
        } finally {
            defaults.removePersistentDomainForName(suite)
            check(root == prefix + id && root.startsWith(prefix))
            if (NSFileManager.defaultManager.fileExistsAtPath(root)) {
                check(NSFileManager.defaultManager.removeItemAtPath(root, error = null))
            }
        }
    }
}
