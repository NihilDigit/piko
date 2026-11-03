package dev.piko.shared.data

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.log.logFile
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.smoke.FakePikPakServer
import dev.piko.shared.smoke.MemoryPreferences
import dev.piko.shared.smoke.MemoryCacheStore
import dev.piko.shared.smoke.awaitUntil
import dev.piko.shared.presentation.DriveWorkspace
import dev.piko.shared.presentation.FolderHistory
import dev.piko.shared.presentation.retainingValid
import dev.piko.shared.state.CLIP_LENGTH_MS
import dev.piko.shared.state.localClipMaxWindowMs
import dev.piko.shared.smoke.smoke
import dev.piko.shared.state.GallerySession
import dev.piko.shared.state.VideoDiscovery
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.StreamRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.io.encoding.Base64
import kotlin.test.*

class LocalMountsTest {
    private fun preferences(): PikoUserPreferences = object : PikoUserPreferences by MemoryPreferences() {
        val mounts = MutableStateFlow("")
        override val localMountsFlow = mounts
        override suspend fun saveLocalMounts(serialized: String) { mounts.value = serialized }
    }

    @Test fun locationsRoundTripAndRejectTraversal() {
        for (directory in listOf(false, true)) {
            val location = LocalLocation("mount-1", listOf("私人 文件夹", "episode 01.mkv"), directory)
            assertEquals(location, LocalLocation.of(location.id))
            assertFalse(isDriveFolderId(location.id))
            assertFalse(location.id.contains("私人"))
        }
        for (segment in listOf("..", ".", "", "/etc", "a/b", "a\\b", "C:", "\u0000")) {
            assertFailsWith<IllegalArgumentException> { LocalLocation("mount-1", listOf(segment)) }
            val encoded = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(segment.encodeToByteArray())
            if (segment.isNotEmpty()) assertNull(LocalLocation.of("piko-local-dir:mount-1/$encoded"))
        }
        assertNull(LocalLocation.of("piko-local-dir:../"))
        assertNull(LocalLocation.of("piko-local-file:mount-1/%%%"))
        assertNull(LocalLocation.of("piko-local-dir:mount-1/YQ=="))
        assertTrue(isDriveFolderId(""))
        assertTrue(isDriveFolderId("cloud-folder"))
        assertFalse(isDriveFolderId("piko-local-unknown:private"))
    }

    @Test fun localClipMaximumWindowAllowsExtensionToThePhysicalEnd() {
        assertEquals(105_000L, localClipMaxWindowMs(15_000, 120_000))
        assertTrue(assertNotNull(localClipMaxWindowMs(15_000, 120_000)) > CLIP_LENGTH_MS)
        assertNull(localClipMaxWindowMs(15_000, 0))
        assertNull(localClipMaxWindowMs(0, -1))
        assertEquals(18_000L, localClipMaxWindowMs(0, 18_000))
        assertEquals(18_000L, localClipMaxWindowMs(-5_000, 18_000))
        assertEquals(1_000L, localClipMaxWindowMs(130_000, 120_000))
    }

    @Test fun historyDropsWholeInvalidFramesAndCollapsesAdjacentDuplicates() = smoke { scope ->
        val mounts = LocalMounts(preferences(), { "account-a" }, {}, scope)
        val mount = mounts.mount("host", "/local", "Local")
        val cloud = listOf(PikoDriveRepository.ROOT_BREADCRUMB, PikoPathBreadcrumb("host", "Cloud"))
        val other = listOf(PikoDriveRepository.ROOT_BREADCRUMB, PikoPathBreadcrumb("other", "Other"))
        val local = cloud + PikoPathBreadcrumb(mount.location.id, "Local")
        val nested = local + PikoPathBreadcrumb(mount.location.child("season", true).id, "season")
        val history = FolderHistory(
            back = listOf(cloud, local, cloud, nested, other),
            forward = listOf(other, nested, other, local, cloud),
        )
        assertTrue(mounts.isMountedCached(nested.last().id))
        mounts.unmount(mount.location.id)
        assertTrue(mounts.isMountedCached("host"))
        assertFalse(mounts.isMountedCached(nested.last().id))
        assertFalse(mounts.isMountedCached("piko-local-dir:invalid/%%%"))
        val cleaned = history.retainingValid(cloud, mounts::isMountedCached)
        assertEquals(listOf(cloud, other), cleaned.back)
        assertEquals(listOf(other), cleaned.forward)
    }

    @Test fun unmountRemovesLocalHistoryFromEveryTabAndRecentFolders() = smoke { scope ->
        val server = FakePikPakServer()
        val host = server.addFolder("host")
        val other = server.addFolder("other")
        val repo = PikoDriveRepository(server.provider(), preferences())
        val mount = repo.localMounts.mount(host.id, "/local", "Local")
        val store = MemoryCacheStore()
        val workspace = DriveWorkspace(repo, store, scope)
        val cloud = listOf(PikoDriveRepository.ROOT_BREADCRUMB, PikoPathBreadcrumb(host.id, "host"))
        val elsewhere = listOf(PikoDriveRepository.ROOT_BREADCRUMB, PikoPathBreadcrumb(other.id, "other"))
        val local = cloud + PikoPathBreadcrumb(mount.location.id, "Local")
        val nested = local + PikoPathBreadcrumb(mount.location.child("season", true).id, "season")
        workspace.restoreFolderStack(cloud)
        workspace.updateFolderStack(local)
        workspace.updateFolderStack(nested)
        workspace.updateFolderStack(elsewhere)
        repeat(3) { assertTrue(workspace.goBack()) }
        val forwardTab = workspace.activeTabId.value
        val backTab = workspace.openTab(cloud)
        workspace.updateFolderStack(local)
        workspace.updateFolderStack(nested)
        workspace.updateFolderStack(elsewhere)
        assertTrue(workspace.historyFlow.value.back.any { frame -> frame.any { LocalLocation.isLocal(it.id) } })
        assertTrue(workspace.recentFoldersFlow.value.any { frame -> frame.any { LocalLocation.isLocal(it.id) } })
        val localTab = workspace.openTab(nested, activate = false)

        repo.localMounts.unmount(mount.location.id)
        fun valid(stack: List<PikoPathBreadcrumb>) = stack.none { LocalLocation.isLocal(it.id) }
        awaitUntil("所有标签和最近访问移除已取消挂载目录") {
            workspace.tabsFlow.value.all { tab -> valid(tab.stack) && (tab.history.back + tab.history.forward).all(::valid) } &&
                workspace.recentFoldersFlow.value.all(::valid)
        }
        assertEquals(cloud, workspace.tabsFlow.value.single { it.id == localTab }.stack)
        assertTrue(workspace.goBack())
        assertEquals(cloud, workspace.folderStackFlow.value)
        workspace.switchTab(forwardTab)
        assertTrue(workspace.goForward())
        assertEquals(elsewhere, workspace.folderStackFlow.value)
        assertFalse(workspace.goForward())
        workspace.switchTab(backTab)
        assertTrue(workspace.retainedPaths.all(::valid))
        val accountKey = assertNotNull(repo.currentAccount).replace(Regex("[^A-Za-z0-9._@-]"), "_")
        kotlinx.coroutines.withTimeout(5_000) {
            while (store.read("recent-folders-$accountKey.json")?.contains("piko-local-") != false) kotlinx.coroutines.delay(20)
        }
        workspace.close()
    }

    @Test fun immediateBackAfterUnmountCannotRaceTheMountCollector() = smoke { scope ->
        val repo = PikoDriveRepository(FakePikPakServer().provider(), preferences())
        val mount = repo.localMounts.mount("", "/local", "Local")
        val workspace = DriveWorkspace(repo, backgroundScope = scope)
        val root = listOf(PikoDriveRepository.ROOT_BREADCRUMB)
        workspace.updateFolderStack(root + PikoPathBreadcrumb(mount.location.id, "Local"))
        workspace.updateFolderStack(root + PikoPathBreadcrumb(mount.location.child("season", true).id, "season"))
        workspace.updateFolderStack(root + PikoPathBreadcrumb("cloud", "Cloud"))
        repo.localMounts.unmount(mount.location.id)
        // 不等待 flow 收集器，直接消费历史。
        assertTrue(workspace.goBack())
        assertEquals(root, workspace.folderStackFlow.value)
        assertTrue(workspace.goForward())
        assertEquals("cloud", workspace.folderStackFlow.value.last().id)
        workspace.close()
    }

    @Test fun initialRestorePrunesUnmountedTabsAndAsynchronouslyLoadedRecentFolders() = smoke { scope ->
        val server = FakePikPakServer()
        val repo = PikoDriveRepository(server.provider(), preferences())
        val mount = repo.localMounts.mount("", "/local", "Local")
        repo.localMounts.unmount(mount.location.id)
        val accountKey = assertNotNull(repo.currentAccount).replace(Regex("[^A-Za-z0-9._@-]"), "_")
        val store = MemoryCacheStore()
        val staleStack = """[{"id":"","name":"Root"},{"id":"${mount.location.id}","name":"Local"}]"""
        store.write("drive-tabs-$accountKey.json", """{"tabs":[$staleStack],"active":0}""")
        store.write("recent-folders-$accountKey.json", """[$staleStack]""")
        val workspace = DriveWorkspace(repo, store, scope)
        assertTrue(workspace.restoreLocation())
        assertEquals(listOf(PikoDriveRepository.ROOT_BREADCRUMB.id), workspace.folderStackFlow.value.map { it.id })
        kotlinx.coroutines.withTimeout(5_000) {
            while (store.read("recent-folders-$accountKey.json") != "[]" ||
                store.read("drive-tabs-$accountKey.json")?.contains("piko-local-") != false
            ) kotlinx.coroutines.delay(20)
        }
        assertTrue(workspace.recentFoldersFlow.value.isEmpty())
        assertFalse(workspace.goBack())
        assertFalse(workspace.goForward())
        workspace.close()
    }

    @Test fun localLogIdentityNeverContainsReversiblePath() {
        val id = LocalLocation("mount-1", listOf("private folder", "private movie.MKV"), false).id
        val shown = logFile(id, "private movie.MKV")
        assertEquals("文件 本地 mount-1（.mkv）", shown)
        assertFalse(shown.contains(id.substringAfter('/')))
        assertEquals("打开 本地 mount-1，失败", dev.piko.shared.log.PikoLog.redact("打开 $id，失败"))
        assertEquals("文件 本地 ?（.mkv）", logFile("piko-local-file:malformed/private", "x.mkv"))
    }

    @Test fun persistenceIsolationFallbackAndReadOnlyUnmount() = smoke { scope ->
        val prefs = preferences()
        var owner = "account-a"
        val refreshed = mutableListOf<Set<String>>()
        val mounts = LocalMounts(prefs, { owner }, { refreshed += it }, scope)
        val entry = mounts.mount("host", "/private/root", "Local")
        val nested = entry.location.child("season", true)
        val stack = listOf(PikoDriveRepository.ROOT_BREADCRUMB, PikoPathBreadcrumb("host", "Cloud"), PikoPathBreadcrumb(entry.location.id, "Local"), PikoPathBreadcrumb(nested.id, "season"))
        assertEquals(stack, mounts.restoredStack(stack))
        mounts.pin(entry.location.id, true)
        val reloaded = LocalMounts(prefs, { owner }, {}, scope)
        assertTrue(reloaded.all().single().pinned)
        assertEquals("/private/root", reloaded.all().single().localRootUriOrPath)
        owner = "account-b"
        assertFalse(mounts.isMountedCached(nested.id))
        assertTrue(mounts.all().isEmpty())
        owner = "account-a"
        assertTrue(mounts.isMountedCached(nested.id))
        assertEquals(listOf(entry.location.id), mounts.roots("host").map { it.id })
        mounts.unmount(entry.location.id)
        assertTrue(mounts.all().isEmpty())
        assertEquals(stack.take(2), mounts.restoredStack(stack))
        assertTrue(refreshed.any { "host" in it })
    }

    @Test fun browsableMergeTraversalAndUnavailableRootsStayVisibleWithoutCloudLocalIds() = smoke { _ ->
        val server = FakePikPakServer()
        val host = server.addFolder("host")
        val repo = PikoDriveRepository(server.provider(), preferences())
        val mount = repo.localMounts.mount(host.id, "/local", "Local")
        var unavailable = false
        val video = FileStat(id = mount.location.child("movie.mkv", false).id, name = "movie.mkv", parentId = mount.location.id, params = mapOf("duration" to "120.5"))
        repo.localMounts.access = object : LocalFileAccess {
            override suspend fun list(mount: LocalMount, location: LocalLocation): List<FileStat> {
                if (unavailable) error("本机文件夹不可访问")
                return if (location.segments.isEmpty()) listOf(video) else emptyList()
            }
            override suspend fun playbackPath(mount: LocalMount, location: LocalLocation) = "/local/movie.mkv"
            override suspend fun probe(mount: LocalMount, location: LocalLocation) = video
        }
        val listing = repo.listBrowsable(host.id, PikoFileSortOrder.NAME_ASC).getOrThrow()
        assertEquals(listOf(mount.location.id), listing.map { it.id })
        val before = server.count("GET /drive/v1/files")
        assertEquals(video, repo.listBrowsable(mount.location.id, PikoFileSortOrder.NAME_ASC).getOrThrow().single())
        assertEquals(before, server.count("GET /drive/v1/files"))
        val discovery = VideoDiscovery(repo, assertNotNull(repo.currentAccount), host.id)
        assertEquals(mount.location.id, discovery.next()?.files?.single()?.id)
        assertEquals(video.id, discovery.next()?.candidates?.single()?.id)
        val media = PikoMediaRepository(server.provider()).apply { localMounts = repo.localMounts }
        assertEquals(120_500L, media.clipProbe(video.id).durationMs)
        val prepared = media.prepareClip(video.id, 15_000, 120_500, StreamRole.FOREGROUND).getOrThrow()
        assertEquals("/local/movie.mkv", prepared.localPath)
        assertNull(prepared.streamBytes)
        assertFalse(prepared.sliced)
        assertEquals(15_000L, prepared.startOnPlayer)
        assertEquals(105_500L, prepared.maxWindowMs)
        assertTrue(assertNotNull(prepared.maxWindowMs) > CLIP_LENGTH_MS)
        assertNull(media.prepareClip(video.id, 15_000, 0, StreamRole.FOREGROUND).getOrThrow().maxWindowMs)
        assertEquals(18_000L, media.prepareClip(video.id, 0, 18_000, StreamRole.FOREGROUND).getOrThrow().maxWindowMs)
        media.reportPlay(video.id, 20_000, 120_500)
        assertNull(media.cloudPlaybackPosition(video.id))
        val index = FolderIndex()
        index.listed(host.id, listOf(mount.toFileStat()))
        index.seed(mapOf(mount.location.id to "Local"), mapOf(mount.location.id to host.id))
        assertTrue(index.flow.value.nodes.isEmpty())
        assertTrue(repo.getFileDetail(video.id).isFailure)
        assertTrue(repo.rename(video.id, "new.mkv").isFailure)
        unavailable = true
        assertTrue(repo.listBrowsable(mount.location.id, PikoFileSortOrder.NAME_ASC).isFailure)
        assertTrue(repo.listBrowsable(host.id, PikoFileSortOrder.NAME_ASC).getOrThrow().any { it.id == mount.location.id })
    }

    @Test fun duplicateMountReusesExistingEntryAndFormatsDisplayPaths() = smoke { scope ->
        val prefs = preferences()
        val mounts = LocalMounts(prefs, { "account-a" }, {}, scope)
        val first = mounts.mountOrExisting("host", "/data/media", "Media")
        assertFalse(first.alreadyMounted)
        val second = mounts.mountOrExisting("host", "/data/media", "MediaDup")
        assertTrue(second.alreadyMounted)
        assertEquals(first.mount.mountId, second.mount.mountId)
        assertEquals(1, mounts.roots("host").size)

        val otherFolder = mounts.mountOrExisting("other-host", "/data/media", "Media")
        assertFalse(otherFolder.alreadyMounted)
        assertNotEquals(first.mount.mountId, otherFolder.mount.mountId)

        assertEquals("C:\\Videos\\Anime", formatLocalPathForDisplay("C:\\Videos\\Anime"))
        assertEquals(
            "Movies/Sub 01",
            formatLocalPathForDisplay("content://com.android.externalstorage.documents/tree/primary%3AMovies/document/primary%3AMovies%2FSub%2001"),
        )
        assertEquals(
            "1234-5678:Photos",
            formatLocalPathForDisplay("content://com.android.externalstorage.documents/tree/1234-5678%3APhotos"),
        )
    }

    @Test fun gallerySessionIncludesImagesByDefaultAndTogglesDynamically() = smoke { scope ->
        val server = FakePikPakServer()
        val host = server.addFolder("gallery-host")
        val repo = PikoDriveRepository(server.provider(), preferences())
        val mount = repo.localMounts.mount(host.id, "/local-gallery", "LocalGallery")
        val localVideo = FileStat(
            id = mount.location.child("clip.mp4", false).id,
            name = "clip.mp4",
            parentId = mount.location.id,
            params = mapOf("duration" to "45"),
        )
        val localImage = FileStat(
            id = mount.location.child("photo.jpg", false).id,
            name = "photo.jpg",
            parentId = mount.location.id,
            thumbnailLink = "file:///local-gallery/photo.jpg",
        )
        repo.localMounts.access = object : LocalFileAccess {
            override suspend fun list(mount: LocalMount, location: LocalLocation): List<FileStat> =
                if (location.segments.isEmpty()) listOf(localVideo, localImage) else emptyList()
            override suspend fun playbackPath(mount: LocalMount, location: LocalLocation) = "/local-gallery/${location.segments.last()}"
            override suspend fun probe(mount: LocalMount, location: LocalLocation) = null
        }

        val gallery = GallerySession(repo, scope)
        assertTrue(gallery.includeImages)
        gallery.open(listOf(PikoDriveRepository.ROOT_BREADCRUMB, PikoPathBreadcrumb(host.id, "gallery-host")))
        kotlinx.coroutines.withTimeout(5_000) {
            while (!gallery.isComplete) kotlinx.coroutines.delay(20)
        }
        assertEquals(setOf(localVideo.id, localImage.id), gallery.files.map { it.id }.toSet())

        gallery.updateIncludeImages(false)
        assertEquals(listOf(localVideo.id), gallery.files.map { it.id })

        gallery.updateIncludeImages(true)
        assertEquals(setOf(localVideo.id, localImage.id), gallery.files.map { it.id }.toSet())
        gallery.dispose()
    }
}
