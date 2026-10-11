package dev.piko.shared.smoke

import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PreviewTempFolder
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.batchMove
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreviewSessionSmokeTest {
    @Test
    fun `late preview cleanup uses its original account and preserves the new account preview`() = smoke { scope ->
        val a = FakePikPakServer("A")
        val b = FakePikPakServer("B")
        val clientA = a.client(account = "a@piko.dev")
        val clientB = b.client(account = "b@piko.dev")
        val provider = object : PikoClientProvider { override val currentClient = MutableStateFlow<io.github.nihildigit.pikpak.PikPakClient?>(clientA) }
        val repo = PikoDriveRepository(provider, MemoryPreferences())
        val temp = PreviewTempFolder(provider, repo, InstantMagnetRepository(provider), scope)
        val old = temp.session()
        val oldFile = old.put(ResolvedFile(path = "a.mp4", size = 10, gcid = "A")).getOrThrow()
        provider.currentClient.value = clientB
        val current = temp.session()
        val currentFile = current.put(ResolvedFile(path = "b.mp4", size = 10, gcid = "B")).getOrThrow()
        val newRoot = assertNotNull(b.node(currentFile)).parentId
        old.close()
        awaitUntil("旧预览在原账号清理") { a.node(oldFile) == null }
        assertNotNull(b.node(currentFile))
        assertNotNull(b.node(newRoot))
        assertEquals(0, b.count("POST /drive/v1/files:batchDelete"))
        current.close()
        awaitUntil("新会话自己的预览可独立清理") { b.node(currentFile) == null }
        assertNotNull(b.node(newRoot), "共享目录不能随会话清除")
    }

    @Test
    fun `closing one preview session preserves another session borrowed objects and already saved files`() = smoke { scope ->
        val server = FakePikPakServer()
        val provider = server.provider()
        val repo = PikoDriveRepository(provider, MemoryPreferences())
        val temp = PreviewTempFolder(provider, repo, InstantMagnetRepository(provider), scope)
        val first = temp.session()
        val second = temp.session()
        val toDelete = first.put(ResolvedFile(path = "first.mp4", size = 10, gcid = "FIRST")).getOrThrow()
        val toSave = first.put(ResolvedFile(path = "saved.mp4", size = 10, gcid = "SAVED")).getOrThrow()
        val toKeep = second.put(ResolvedFile(path = "second.mp4", size = 10, gcid = "SECOND")).getOrThrow()
        val root = assertNotNull(server.node(toKeep)).parentId
        val borrowed = server.addFile("borrowed", root)
        provider.currentClient.value!!.batchMove(listOf(toSave), "")
        first.close()
        first.close()
        awaitUntil("仅清理本会话留在临时目录的文件") { server.node(toDelete) == null }
        assertEquals("", assertNotNull(server.node(toSave)).parentId)
        assertNotNull(server.node(toKeep))
        assertNotNull(server.node(borrowed.id))
        temp.clear().getOrThrow()
        assertNotNull(server.node(toKeep), "启动清理晚到时不能删除活跃预览")
        second.close()
        awaitUntil("第二个会话清理自己的文件") { server.node(toKeep) == null }
        assertNotNull(server.node(borrowed.id))
        assertTrue(server.children(root).all { it.id == borrowed.id })
        temp.clear().getOrThrow()
        assertNull(server.node(root), "没有活跃预览时可清理上次退出残留")
        assertNotNull(server.node(toSave))
    }

    @Test
    fun `same account client reconstruction gives a new session its own client without losing active previews`() = smoke { scope ->
        val server = FakePikPakServer()
        val firstClient = server.client(account = "same@piko.dev")
        val provider = object : PikoClientProvider { override val currentClient = MutableStateFlow<io.github.nihildigit.pikpak.PikPakClient?>(firstClient) }
        val repo = PikoDriveRepository(provider, MemoryPreferences())
        val temp = PreviewTempFolder(provider, repo, InstantMagnetRepository(provider), scope)
        val first = temp.session()
        val one = first.put(ResolvedFile(path = "one.mp4", size = 10, gcid = "ONE")).getOrThrow()
        provider.currentClient.value = server.client(account = firstClient.account)
        val second = temp.session()
        val two = second.put(ResolvedFile(path = "two.mp4", size = 10, gcid = "TWO")).getOrThrow()
        assertEquals(server.node(one)?.parentId, server.node(two)?.parentId)
        first.close()
        awaitUntil("旧 client 的会话只清理自己的资源") { server.node(one) == null }
        assertNotNull(server.node(two))
        second.close()
        awaitUntil("新 client 的会话独立清理") { server.node(two) == null }
    }
}
