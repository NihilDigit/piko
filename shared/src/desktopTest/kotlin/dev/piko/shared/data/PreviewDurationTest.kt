package dev.piko.shared.data

import dev.piko.shared.smoke.FakePikPakServer
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreviewDurationTest {
    @Test fun missingListingDurationUsesDetailAndCachesResult() = runBlocking {
        val server = FakePikPakServer()
        val node = server.addVideo("sample.m2ts", durationSeconds = 2510.0)
        val repo = PikoDriveRepository(server.provider())
        val file = FileStat(id = node.id, name = node.name)
        assertEquals(2510L, repo.previewDurationSeconds(file))
        val calls = server.calls.size
        assertEquals(2510L, repo.previewDurationSeconds(file))
        assertEquals(calls, server.calls.size)
    }
    @Test fun existingDurationAvoidsNetworkAndMissingDetailIsNotInvented() = runBlocking {
        val server = FakePikPakServer()
        val repo = PikoDriveRepository(server.provider())
        assertEquals(90L, repo.previewDurationSeconds(FileStat(id = "known", name = "known.mp4", params = mapOf("duration" to "90"))))
        assertEquals(0, server.calls.size)
        val node = server.addFile("unknown.mp4")
        assertNull(repo.previewDurationSeconds(FileStat(id = node.id, name = node.name)))
        val calls = server.calls.size
        assertNull(repo.previewDurationSeconds(FileStat(id = node.id, name = node.name)))
        assertEquals(calls, server.calls.size)
    }
}
