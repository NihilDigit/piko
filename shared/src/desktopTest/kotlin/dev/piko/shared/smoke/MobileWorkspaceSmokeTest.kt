package dev.piko.shared.smoke

import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.OfflinePackTracker
import dev.piko.shared.data.PikoCacheStore
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.PreviewTempFolder
import dev.piko.shared.data.ScrollAnchor
import dev.piko.shared.presentation.DriveWorkspace
import dev.piko.shared.presentation.MobileFileWorkspace
import dev.piko.shared.state.DriveBrowserSession
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.DuplicateSession
import dev.piko.shared.state.InstantSaveRecords
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.state.SaveTargetBinding
import dev.piko.shared.state.TrashSession
import io.github.nihildigit.pikpak.PikPakClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MobileWorkspaceSmokeTest {
    private val root = PikoDriveRepository.ROOT_BREADCRUMB

    @Test
    fun `mobile browsing and clipboard do not follow desktop tabs but share file facts`() = smoke { scope ->
        val server = FakePikPakServer()
        val phone = server.addFolder("phone")
        val desktopFolder = server.addFolder("desktop")
        val file = server.addFile("phone.txt", phone.id)
        val prefs = MemoryPreferences()
        val provider = server.provider()
        val repo = PikoDriveRepository(provider, prefs)
        val mobile = MobileFileWorkspace(repo, repo.currentAccount!!, parentScope = scope)
        val desktop = DriveWorkspace(repo, backgroundScope = scope)
        val duplicates = DuplicateSession(
            { CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])) },
            { child, folder -> DuplicateFinderState(provider, repo, child, folder) },
        )
        val cleanup = TrashSession(repo, scope)
        val browser = DriveBrowserSession(repo, prefs, duplicates, null, cleanup, scope,
            fileNavigation = mobile, clipboard = mobile.clipboard, operationScope = scope)
        mobile.navigateToFolder(PikoPathBreadcrumb(phone.id, phone.name))
        browser.state.load()
        awaitUntil("移动端加载自己的目录") { browser.state.loadedFolderId == phone.id }
        browser.state.selectOnly(setOf(file.id))
        browser.state.openSearch()
        browser.state.updateSearchQuery("phone")
        browser.state.putOnClipboard(listOf(file.id), cut = true)
        desktop.openTab(listOf(root, PikoPathBreadcrumb(desktopFolder.id, desktopFolder.name)))
        assertEquals(phone.id, mobile.folderStackFlow.value.last().id)
        assertEquals(setOf(file.id), browser.state.selectedFileIds.toSet())
        assertEquals("phone", browser.state.searchQuery)
        assertTrue(browser.state.isSearchOpen)
        assertNotNull(mobile.clipboard.contents.value)
        assertNull(desktop.clipboard.contents.value)

        val library = DriveBrowserSession(repo, prefs, duplicates, null, cleanup, scope, library = DriveLibrary.STARRED,
            clipboard = mobile.clipboard, operationScope = scope)
        library.state.load()
        awaitUntil("资料页拥有独立目录") { !library.state.isLoading }
        assertEquals(phone.id, mobile.folderStackFlow.value.last().id)
        assertEquals(mobile.clipboard.contents.value, library.state.clipboardFlow.value)
        repo.rename(file.id, "phone-renamed.txt").getOrThrow()
        awaitUntil("业务事实仍由文件仓库共享") { browser.state.files.any { it.name == "phone-renamed.txt" } }
        repo.rename(phone.id, "renamed-folder").getOrThrow()
        awaitUntil("移动路径随文件索引改名") { mobile.folderStackFlow.value.last().name == "renamed-folder" }
        assertEquals(desktopFolder.id, desktop.folderStackFlow.value.last().id)
        library.close()
        browser.close()
        mobile.close()
        desktop.close()
    }

    @Test
    fun `mobile history and temporary browsing restore without a tab identity`() = smoke { scope ->
        val server = FakePikPakServer()
        val repo = PikoDriveRepository(server.provider(), MemoryPreferences())
        val mobile = MobileFileWorkspace(repo, repo.currentAccount!!, parentScope = scope)
        val desktop = DriveWorkspace(repo, backgroundScope = scope)
        val source = PikoPathBreadcrumb(server.addFolder("source").id, "source")
        val elsewhere = PikoPathBreadcrumb(server.addFolder("elsewhere").id, "elsewhere")
        mobile.navigateToFolder(source)
        mobile.saveScrollAnchor(source.id, ScrollAnchor(3, 17))
        val before = mobile.currentLocation()
        mobile.navigateToFolder(elsewhere)
        mobile.returnTo(before)
        assertEquals(listOf(root, source), mobile.folderStackFlow.value)
        assertEquals(listOf(listOf(root)), mobile.historyFlow.value.back)
        assertEquals(ScrollAnchor(3, 17), mobile.scrollAnchor(source.id))
        assertTrue(mobile.goBack())
        assertEquals(listOf(root), mobile.folderStackFlow.value)
        assertTrue(mobile.goForward())
        assertEquals(source.id, mobile.folderStackFlow.value.last().id)
        mobile.returnTo(desktop.currentLocation())
        desktop.returnTo(before)
        assertEquals(source.id, mobile.folderStackFlow.value.last().id)
        assertEquals(listOf(root), desktop.folderStackFlow.value)
        mobile.updateFolderStack(listOf(DriveLibrary.TRASH.crumb))
        assertEquals(source.id, mobile.folderStackFlow.value.last().id, "资料页不能成为移动主文件位置")
        mobile.close()
        desktop.close()
    }

    @Test
    fun `published active location migrates once and later mobile saves do not rewrite desktop tabs`() = smoke { scope ->
        val server = FakePikPakServer()
        val repo = PikoDriveRepository(server.provider(), MemoryPreferences())
        val account = repo.currentAccount!!
        val cache = MemoryCacheStore()
        val first = server.addFolder("first")
        val second = server.addFolder("second")
        val legacyKey = "drive-tabs-$account.json"
        val ownKey = "mobile-drive-location-$account.json"
        val legacy = """{"tabs":[[{"id":"","name":"网盘"},{"id":"${first.id}","name":"first"}],[{"id":"","name":"网盘"},{"id":"${second.id}","name":"second"}]],"active":1}"""
        cache.write(legacyKey, legacy)
        val mobile = MobileFileWorkspace(repo, account, cache, scope)
        assertTrue(mobile.restoreLocation())
        assertEquals(second.id, mobile.folderStackFlow.value.last().id)
        assertFalse(mobile.historyFlow.value.canGoBack)
        mobile.navigateToFolder(PikoPathBreadcrumb(first.id, first.name))
        withTimeout(5_000) { while (cache.read(ownKey) == null) delay(20) }
        assertEquals(legacy, cache.read(legacyKey))
        mobile.close()
        cache.write(legacyKey, legacy.replace("\"active\":1", "\"active\":0"))
        val restored = MobileFileWorkspace(repo, account, cache, scope)
        assertTrue(restored.restoreLocation())
        assertEquals(first.id, restored.folderStackFlow.value.last().id)
        restored.close()
    }

    @Test
    fun `a consumed locate request wins over location restoration in progress`() = smoke { scope ->
        val server = FakePikPakServer()
        val repo = PikoDriveRepository(server.provider(), MemoryPreferences())
        val account = repo.currentAccount!!
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cache = object : PikoCacheStore by MemoryCacheStore() {
            override suspend fun read(key: String): String? {
                if (!key.startsWith("mobile-drive-location-")) return null
                reading.complete(Unit)
                release.await()
                return """{"path":[{"id":"","name":"网盘"},{"id":"saved","name":"saved"}]}"""
            }
        }
        val mobile = MobileFileWorkspace(repo, account, cache, scope)
        val restored = scope.async { mobile.restoreLocation() }
        withTimeout(5_000) { reading.await() }
        mobile.requestHighlight(setOf("new-file"))
        assertEquals(setOf("new-file"), mobile.takePendingHighlight())
        release.complete(Unit)
        assertFalse(restored.await())
        assertEquals(listOf(root), mobile.folderStackFlow.value)
        mobile.close()
    }

    @Test
    fun `save target follows the bound frontend and respects a chosen target`() = smoke { scope ->
        val server = FakePikPakServer()
        val provider = server.provider()
        val prefs = MemoryPreferences()
        val repo = PikoDriveRepository(provider, prefs)
        val mobile = MobileFileWorkspace(repo, repo.currentAccount!!, parentScope = scope)
        val desktop = DriveWorkspace(repo, backgroundScope = scope)
        val first = PikoPathBreadcrumb(server.addFolder("first").id, "first")
        val second = PikoPathBreadcrumb(server.addFolder("second").id, "second")
        mobile.navigateToFolder(first)
        val binding = SaveTargetBinding({ repo.currentAccount }, repo.accountChanges)
        val release = binding.bind(repo.currentAccount!!, mobile.saveTargetContext)
        val instant = InstantMagnetRepository(provider)
        val sheet = InstantSheetState(instant, repo, prefs, PreviewTempFolder(provider, repo, instant, scope),
            OfflinePackTracker(instant, repo, prefs), InstantSaveRecords(provider, null, scope), scope, saveTargetContext = binding.context)
        awaitUntil("默认保存到移动文件位置") { sheet.target?.id == first.id }
        desktop.navigateToFolder(second)
        assertEquals(first, binding.context.currentFolder(), "桌面标签不能影响移动保存目标")
        mobile.navigateToFolder(second)
        awaitUntil("未手选时跟随移动导航") { sheet.target?.id == second.id }
        sheet.changeTarget(first)
        mobile.navigateToFolder(root)
        assertEquals(root, binding.context.currentFolder(), "外部上传仍取当前前端位置")
        assertEquals(first, sheet.target, "链接草稿自己的手选目标不随导航改变")
        release()
        mobile.close()
        desktop.close()
    }

    @Test
    fun `old account navigation and binding release cannot change the new frontend`() = smoke { scope ->
        val firstServer = FakePikPakServer("A")
        val secondServer = FakePikPakServer("B")
        val provider = object : PikoClientProvider {
            override val currentClient = MutableStateFlow<PikPakClient?>(firstServer.client(account = "a@piko.dev"))
        }
        val repo = PikoDriveRepository(provider, MemoryPreferences())
        val old = MobileFileWorkspace(repo, "a@piko.dev", parentScope = scope)
        val source = PikoPathBreadcrumb(firstServer.addFolder("old").id, "old")
        old.navigateToFolder(source)
        val bookmark = old.currentLocation()
        val binding = SaveTargetBinding({ repo.currentAccount }, repo.accountChanges)
        val releaseOld = binding.bind("a@piko.dev", old.saveTargetContext)
        assertEquals(source, binding.context.currentFolder())
        provider.currentClient.value = secondServer.client(account = "b@piko.dev")
        repo.enterAccount("b@piko.dev")
        assertEquals(root, binding.context.currentFolder())
        val current = MobileFileWorkspace(repo, "b@piko.dev", parentScope = scope)
        val target = PikoPathBreadcrumb(secondServer.addFolder("new").id, "new")
        current.navigateToFolder(target)
        val releaseCurrent = binding.bind("b@piko.dev", current.saveTargetContext)
        releaseOld()
        old.navigateToFolder(target)
        current.returnTo(bookmark)
        assertEquals(target, binding.context.currentFolder())
        assertEquals(target.id, current.folderStackFlow.value.last().id)
        releaseCurrent()
        assertEquals(root, binding.context.currentFolder())
        old.close()
        current.close()
    }
}
