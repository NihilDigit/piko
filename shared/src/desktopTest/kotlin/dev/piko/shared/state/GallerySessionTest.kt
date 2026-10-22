package dev.piko.shared.state

import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.smoke.FakePikPakServer
import dev.piko.shared.smoke.awaitUntil
import dev.piko.shared.smoke.smoke
import io.github.nihildigit.pikpak.PikPakClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GallerySessionTest {
    @Test fun viewportDemandReceivedDuringLoadingIsRecheckedAfterThePageArrives() = smoke { scope ->
        val server = FakePikPakServer()
        val videos = (1..62).map { server.addVideo("video-$it.mkv") }
        val session = GallerySession(PikoDriveRepository(server.provider()), scope)
        session.open(listOf(PikoDriveRepository.ROOT_BREADCRUMB))
        session.onVisibleFilesChanged(videos.map { it.id }.toSet())
        awaitUntil("加载中收到的视口需求继续补齐") { !session.loading && session.isComplete }
        assertEquals(62, session.files.size)
        session.dispose()
    }

    @Test fun pagesAppendOnceAndContentCopiesAreDeduplicated() = smoke { scope ->
        val server = FakePikPakServer()
        repeat(120) { server.addVideo("video-$it.mkv", durationSeconds = 5.0) }
        server.addFile("first-copy.mkv", hash = "same")
        server.addFile("second-copy.mkv", hash = "SAME")
        val repo = PikoDriveRepository(server.provider())
        val session = GallerySession(repo, scope)
        session.open(listOf(PikoDriveRepository.ROOT_BREADCRUMB))
        awaitUntil("画廊第一页") { !session.loading }
        assertEquals(48, session.files.size)
        assertTrue(session.hasMore)
        val firstPage = session.files.toList()
        val listCalls = server.count("GET /drive/v1/files")
        session.requestMore()
        awaitUntil("画廊第二页") { !session.loading }
        assertEquals(96, session.files.size)
        assertEquals(firstPage, session.files.take(firstPage.size))
        session.requestMore()
        awaitUntil("画廊全部候选") { !session.loading }
        assertEquals(121, session.files.size)
        assertEquals(121, session.files.map { it.clipContentKey() }.distinct().size)
        assertTrue(session.isComplete)
        assertFalse(session.hasMore)
        assertEquals(listCalls, server.count("GET /drive/v1/files"), "已有候选无需再次列目录")
        session.dispose()
    }

    @Test fun aSparseTreeStopsAtTheRequestBudgetAndCanContinueExplicitly() = smoke { scope ->
        val server = FakePikPakServer()
        var parent = ""
        repeat(20) { parent = server.addFolder("folder-$it", parent).id }
        val last = server.addVideo("last.mkv", parent)
        val repo = PikoDriveRepository(server.provider())
        val session = GallerySession(repo, scope)
        session.open(listOf(PikoDriveRepository.ROOT_BREADCRUMB))
        awaitUntil("目录预算后停止") { !session.loading }
        assertTrue(session.files.isEmpty())
        assertTrue(session.hasMore)
        assertFalse(session.isComplete)
        val listed = server.count("GET /drive/v1/files")
        delay(100)
        assertEquals(listed, server.count("GET /drive/v1/files"))
        session.requestMore()
        awaitUntil("继续查到深层视频") { !session.loading }
        assertEquals(last.id, session.files.single().id)
        assertTrue(session.isComplete)
        assertEquals("folder-19", session.folderName(parent))
        assertEquals(21, session.pathTo(parent)?.size)
        session.dispose()
    }

    @Test fun changingRootDuringARequestOnlyPublishesTheNewRoot() = smoke { scope ->
        val server = FakePikPakServer()
        val old = server.addFolder("old")
        val fresh = server.addFolder("fresh")
        val freshVideo = server.addVideo("fresh.mkv", fresh.id)
        server.addVideo("old.mkv", old.id)
        server.apiDelayMs = 100
        val session = GallerySession(PikoDriveRepository(server.provider()), scope)
        session.open(listOf(PikoDriveRepository.ROOT_BREADCRUMB, PikoPathBreadcrumb(old.id, old.name)))
        delay(20)
        session.open(listOf(PikoDriveRepository.ROOT_BREADCRUMB, PikoPathBreadcrumb(fresh.id, fresh.name)))
        awaitUntil("仅显示新目录") { !session.loading && session.files.isNotEmpty() }
        assertEquals(listOf(freshVideo.id), session.files.map { it.id })
        assertEquals(fresh.id, session.root?.id)
        session.dispose()
    }

    @Test fun logoutClosesTheGalleryAndLateResultsCannotReopenIt() = smoke { scope ->
        val server = FakePikPakServer()
        server.addVideo("one.mkv")
        server.apiDelayMs = 100
        val provider = object : PikoClientProvider {
            override val currentClient = MutableStateFlow<PikPakClient?>(server.client())
        }
        val session = GallerySession(PikoDriveRepository(provider), scope)
        session.open(listOf(PikoDriveRepository.ROOT_BREADCRUMB))
        delay(20)
        provider.currentClient.value = null
        awaitUntil("退出账号清理画廊") { session.root == null }
        delay(150)
        assertNull(session.root)
        assertTrue(session.files.isEmpty())
        assertFalse(session.loading)
        session.dispose()
    }

    @Test fun releasingTheOwnerStopsBeforeTheNextFolderRequest() = smoke { scope ->
        val server = FakePikPakServer()
        var parent = ""
        repeat(20) { parent = server.addFolder("folder-$it", parent).id }
        server.apiDelayMs = 100
        val owner = SupervisorJob(scope.coroutineContext[Job])
        val ownerScope = CoroutineScope(scope.coroutineContext + owner)
        val session = GallerySession(PikoDriveRepository(server.provider()), ownerScope)
        session.open(listOf(PikoDriveRepository.ROOT_BREADCRUMB))
        awaitUntil("owner释放前目录请求已发出") { server.count("GET /drive/v1/files") > 0 }
        owner.cancelAndJoin()
        val listed = server.count("GET /drive/v1/files")
        delay(150)
        assertEquals(listed, server.count("GET /drive/v1/files"))
        assertEquals(1, listed, "共享目录请求可以完成，但已释放的画廊不能继续下一层")
        session.dispose()
    }

    @Test fun renamedAndTrashedVideosRefreshThroughTheRepositoryChangeStream() = smoke { scope ->
        val server = FakePikPakServer()
        val video = server.addVideo("before.mkv")
        val repo = PikoDriveRepository(server.provider())
        val session = GallerySession(repo, scope)
        session.open(listOf(PikoDriveRepository.ROOT_BREADCRUMB))
        awaitUntil("初始视频") { !session.loading && session.files.size == 1 }
        repo.rename(video.id, "after.mkv").getOrThrow()
        awaitUntil("画廊反映改名") { !session.loading && session.files.singleOrNull()?.name == "after.mkv" }
        repo.trash(listOf(video.id)).getOrThrow()
        awaitUntil("画廊移除回收站条目") { !session.loading && session.files.isEmpty() && session.isComplete }
        session.dispose()
    }

    @Test fun starringAndRenamingAfterLoadingMultiplePagesPreserveAllDisplayedPositions() = smoke { scope ->
        val server = FakePikPakServer()
        repeat(123) { server.addVideo("video-$it.mkv") }
        val repo = PikoDriveRepository(server.provider())
        val session = GallerySession(repo, scope)
        session.open(listOf(PikoDriveRepository.ROOT_BREADCRUMB))
        awaitUntil("图库首批") { !session.loading }
        while (session.hasMore) {
            session.requestMore()
            awaitUntil("追加后续页") { !session.loading }
        }
        val order = session.files.map { it.id }
        assertEquals(123, order.size)
        val target = session.files[110]
        repo.setStarred(listOf(target.id), true).getOrThrow()
        awaitUntil("加星只更新元数据") { !session.loading && session.files.firstOrNull { it.id == target.id }?.isStarred == true }
        assertEquals(order, session.files.map { it.id })
        repo.rename(target.id, "renamed.mkv").getOrThrow()
        awaitUntil("改名只更新元数据") { !session.loading && session.files.firstOrNull { it.id == target.id }?.name == "renamed.mkv" }
        assertEquals(order, session.files.map { it.id })
        assertTrue(session.isComplete)
        session.dispose()
    }

    @Test fun movingASubtreeOutRemovesDescendantsAndMovingItBackCanDiscoverThemAgain() = smoke { scope ->
        val server = FakePikPakServer()
        val root = server.addFolder("gallery")
        val child = server.addFolder("child", root.id)
        val grandchild = server.addFolder("grandchild", child.id)
        val outside = server.addFolder("outside")
        val one = server.addVideo("one.mkv", child.id)
        val two = server.addVideo("two.mkv", grandchild.id)
        val repo = PikoDriveRepository(server.provider())
        val session = GallerySession(repo, scope)
        session.open(listOf(PikoDriveRepository.ROOT_BREADCRUMB, PikoPathBreadcrumb(root.id, root.name)))
        awaitUntil("发现完整子树") { !session.loading && session.files.size == 2 }
        repo.move(listOf(child.id), outside.id).getOrThrow()
        awaitUntil("移出范围的子树不再显示") { !session.loading && session.files.isEmpty() && session.isComplete }
        repo.move(listOf(child.id), root.id).getOrThrow()
        awaitUntil("移回的子树重新进入待探索队列") { !session.loading && session.hasMore }
        session.requestMore()
        awaitUntil("重新发现移回子树") { !session.loading && session.files.size == 2 }
        assertEquals(setOf(one.id, two.id), session.files.map { it.id }.toSet())
        assertTrue(session.isComplete)
        session.dispose()
    }

    @Test fun replacingTheClientForTheSameAccountRefreshesWithoutLosingTheGallery() = smoke { scope ->
        val server = FakePikPakServer()
        val video = server.addVideo("before.mkv")
        val provider = object : PikoClientProvider {
            override val currentClient = MutableStateFlow<PikPakClient?>(server.client())
        }
        val session = GallerySession(PikoDriveRepository(provider), scope)
        session.open(listOf(PikoDriveRepository.ROOT_BREADCRUMB))
        awaitUntil("初始账号视频") { !session.loading && session.files.size == 1 }
        video.name = "after.mkv"
        provider.currentClient.value = server.client()
        awaitUntil("同账号新client重列元数据") { !session.loading && session.files.singleOrNull()?.name == "after.mkv" }
        assertEquals(video.id, session.files.single().id)
        assertTrue(session.isComplete)
        session.dispose()
    }

    @Test fun refreshingDuringTheFirstListingResumesTheOriginalDiscoveryDemand() = smoke { scope ->
        val server = FakePikPakServer()
        val child = server.addFolder("child")
        val video = server.addVideo("one.mkv", child.id)
        server.apiDelayMs = 100
        val provider = object : PikoClientProvider {
            override val currentClient = MutableStateFlow<PikPakClient?>(server.client())
        }
        val session = GallerySession(PikoDriveRepository(provider), scope)
        session.open(listOf(PikoDriveRepository.ROOT_BREADCRUMB))
        awaitUntil("首载请求在途") { server.count("GET /drive/v1/files") > 0 }
        provider.currentClient.value = server.client()
        awaitUntil("刷新后恢复首载子树发现") { !session.loading && session.files.singleOrNull()?.id == video.id }
        assertTrue(session.isComplete)
        session.dispose()
    }

    @Test fun selectedVideosRemainFixedWithoutTraversingFolders() = smoke { scope ->
        val server = FakePikPakServer()
        val chosen = server.addVideo("chosen.mkv")
        server.addVideo("outside.mkv")
        val nested = server.addFolder("nested")
        server.addVideo("nested.mkv", nested.id)
        val repo = PikoDriveRepository(server.provider())
        val listed = repo.listBrowsable("", dev.piko.shared.data.PikoFileSortOrder.TIME_DESC).getOrThrow()
        val before = server.count("GET /drive/v1/files")
        val session = GallerySession(repo, scope)
        session.open(listOf(PikoDriveRepository.ROOT_BREADCRUMB), listed.filter { it.id == chosen.id || it.id == nested.id })
        awaitUntil("所选视频画廊加载完成") { !session.loading && session.isComplete }
        assertEquals(listOf(chosen.id), session.files.map { it.id })
        assertEquals(1, session.selectedVideoCount)
        assertEquals("已选 1 个视频", session.scopeLabel)
        assertEquals(before, server.count("GET /drive/v1/files"))
        session.dispose()
    }
}
