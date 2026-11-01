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
    fun epochTimeSortHandlesOffsetsInvalidValuesAndOriginalInstanceTime() {
        val earlier = FileStat(id = "early", name = "early", createdTime = "2025-01-01T10:00:00+09:00")
        val later = FileStat(id = "late", name = "late", createdTime = "2025-01-01T02:00:00Z")
        val invalid = FileStat(id = "invalid", name = "invalid", createdTime = "invalid")
        val original = later.copy(id = "original", params = mapOf("piko_original_created_at" to "0"))
        val files = listOf(invalid, later, earlier, original)
        assertEquals(listOf(original, earlier, later, invalid), repository.sortFiles(files, PikoFileSortOrder.TIME_ASC, ""))
        assertEquals(listOf(later, earlier, original, invalid), repository.sortFiles(files, PikoFileSortOrder.TIME_DESC, ""))
    }

    @Test
    fun capturedSubtreeAndContentMetadataDriveSortWithoutChangingInputs() {
        val small = FileStat(id = "small", name = "folder 2", kind = io.github.nihildigit.pikpak.FileKind.FOLDER)
        val large = small.copy(id = "large", name = "folder 10")
        val unknown = small.copy(id = "unknown", name = "folder 1")
        val video = FileStat(id = "video", name = "video.mp4", hash = "abc", size = "10")
        val context = SortContext(7, mapOf(
            small.id to SubtreeStats(physical = ContentStats(bytes = 10, durationMillis = 1000, knownDurationFiles = 1)),
            large.id to SubtreeStats(vaulted = ContentStats(bytes = 30, durationMillis = 5000, knownDurationFiles = 1)),
        ), emptyMap(), emptyMap(), mapOf(ContentKey("ABC", 10) to ContentMetadata(durationMillis = 2000)))
        assertEquals(listOf(large, small, unknown), repository.sortFiles(listOf(small, unknown, large), PikoFileSortOrder.DURATION_DESC, "", context))
        assertEquals(listOf(small, large, unknown), repository.sortFiles(listOf(large, unknown, small), PikoFileSortOrder.DURATION_ASC, "", context))
        assertEquals(listOf(large, small), repository.sortFiles(listOf(small, large), PikoFileSortOrder.SIZE_DESC, "", context))
        val shorter = video.copy(id = "short", hash = "def", params = mapOf("duration" to "1"))
        assertEquals(listOf(video, shorter), repository.sortFiles(listOf(shorter, video), PikoFileSortOrder.DURATION_DESC, "", context))
    }

    @Test
    fun originalDetailOverridesNonEmptyListingDurationForFilesAndFolders() {
        val folder = FileStat(id = "folder", kind = io.github.nihildigit.pikpak.FileKind.FOLDER)
        val corrected = FileStat(id = "corrected", parentId = folder.id, name = "corrected.mp4", hash = "abc", size = "10", params = mapOf("duration" to "30"))
        val other = corrected.copy(id = "other", parentId = "", hash = "def", params = mapOf("duration" to "45"))
        val index = repository.driveTreeIndex
        index.putFiles(listOf(folder, corrected, other))
        index.observeDetail(io.github.nihildigit.pikpak.FileDetail(hash = "ABC", size = "10", medias = listOf(
            io.github.nihildigit.pikpak.MediaVariant(isOrigin = true, video = io.github.nihildigit.pikpak.VideoInfo(duration = 60)),
        )))
        val context = index.sortContext()
        assertEquals(60_000L, context.subtreeStats.getValue(folder.id).physical.durationMillis)
        assertEquals(listOf(corrected, other), repository.sortFiles(listOf(other, corrected), PikoFileSortOrder.DURATION_DESC, "", context))
        assertEquals(listOf(other, corrected), repository.sortFiles(listOf(corrected, other), PikoFileSortOrder.DURATION_ASC, "", context))
        assertEquals("30", corrected.params["duration"])
    }

    @Test
    fun equalNamesUseStableIdsInBothDirections() {
        val a = FileStat(id = "a", name = "same.mp4")
        val b = a.copy(id = "b")
        for (order in PikoFileSortOrder.entries) {
            assertEquals(listOf(a, b), repository.sortFiles(listOf(b, a), order, ""))
        }
    }

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
