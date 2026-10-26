package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class DurationSortTest {
    private val repository = PikoDriveRepository(object : PikoClientProvider {
        override val currentClient = MutableStateFlow<PikPakClient?>(null)
    })

    @Test
    fun durationSortKeepsTiersAndSizeTiesAcrossInputPermutations() {
        val knownSmall = FileStat(id = "1", name = "z.mp4", size = "10", params = mapOf("duration" to "30"))
        val knownLarge = FileStat(id = "2", name = "a.mp4", size = "20", params = mapOf("duration" to "30"))
        val longer = FileStat(id = "3", name = "long.mp4", size = "1", params = mapOf("duration" to "60"))
        val unknownSmall = FileStat(id = "4", name = "z.mkv", size = "1")
        val unknownLarge = FileStat(id = "5", name = "a.mkv", size = "100")
        val text = FileStat(id = "6", name = "m.txt", size = "50")
        val files = listOf(knownSmall, knownLarge, longer, unknownSmall, unknownLarge, text)
        val ascending = listOf(knownSmall, knownLarge, longer, unknownSmall, unknownLarge, text)
        val descending = listOf(longer, knownLarge, knownSmall, unknownLarge, unknownSmall, text)
        repeat(100) { seed ->
            val shuffled = files.shuffled(Random(seed))
            assertEquals(ascending, repository.sortFiles(shuffled, PikoFileSortOrder.DURATION_ASC, ""))
            assertEquals(descending, repository.sortFiles(shuffled, PikoFileSortOrder.DURATION_DESC, ""))
        }
    }
}
