package dev.piko.shared.smoke

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.state.ShareSaveState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShareDestinationSmokeTest {
    @Test fun selectedFolderAndExplicitRootOverrideTheServerDefault() = smoke { scope ->
        val server = FakePikPakServer()
        val owner = server.addFolder("Owner")
        val source = server.addFile("episode.mkv", owner.id, hash = "SHARED")
        val target = server.addFolder("Dramas")
        server.shares["S1"] = listOf(source.id)
        val state = ShareSaveState(PikoDriveRepository(server.provider(), MemoryPreferences()), scope, "S1")
        awaitUntil("分享已打开") { !state.isLoading && state.info != null }
        state.toggle(state.entries.single())
        state.save(PikoPathBreadcrumb(target.id, target.name))
        awaitUntil("转存完成") { !state.isSaving }
        assertEquals(listOf(source.name), server.children(target.id).map { it.name })
        state.toggle(state.entries.single())
        state.save(PikoDriveRepository.ROOT_BREADCRUMB)
        awaitUntil("根目录转存完成") { !state.isSaving }
        assertTrue(server.children("").any { it.name == source.name })
        assertTrue(server.children("").none { it.name == "Pack From Shared" })
    }
}
