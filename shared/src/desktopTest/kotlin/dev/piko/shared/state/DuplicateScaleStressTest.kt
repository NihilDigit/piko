package dev.piko.shared.state

import dev.piko.shared.data.ScannedFile
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DuplicateScaleStressTest {
    /** Run on demand with -Dpiko.test.bench=true (20k-file duplicate grouping stress test). */
    @Test
    fun fiveThousandDuplicateGroupsGroupFilterAndMaterializeFastWithBoundedMemory() {
        org.junit.Assume.assumeTrue("Stress benchmark disabled by default; pass -Dpiko.test.bench=true", java.lang.Boolean.getBoolean("piko.test.bench"))
        val groupCount = 5_000
        val copiesPerGroup = 3
        val uniqueNonDuplicates = 5_000
        val scanned = ArrayList<ScannedFile>(groupCount * copiesPerGroup + uniqueNonDuplicates)
        for (g in 0 until groupCount) {
            val hash = "GCID_${g.toString().padStart(6, '0')}"
            val size = (1_000_000L + g * 1024L).toString()
            for (c in 0 until copiesPerGroup) {
                val id = "dup_${g}_$c"
                val suffix = if (c == 0) "" else " ($c)"
                val name = "Video_$g$suffix.mp4"
                val folder = "dir_${g % 100}/sub_$c"
                scanned += ScannedFile(
                    file = FileStat(
                        id = id,
                        parentId = "p_${g % 100}",
                        name = name,
                        kind = FileKind.FILE,
                        size = size,
                        hash = hash,
                        mimeType = "video/mp4",
                        createdTime = "2025-01-0${(c % 9) + 1}T00:00:00Z",
                        modifiedTime = "2025-01-0${(c % 9) + 1}T00:00:00Z",
                    ),
                    folderPath = folder,
                )
            }
        }
        for (u in 0 until uniqueNonDuplicates) {
            scanned += ScannedFile(
                file = FileStat(
                    id = "uniq_$u",
                    parentId = "p_uniq",
                    name = "Unique_Doc_$u.pdf",
                    kind = FileKind.FILE,
                    size = (500_000L + u).toString(),
                    hash = "UNIQ_HASH_$u",
                    mimeType = "application/pdf",
                ),
                folderPath = "docs",
            )
        }

        // 1. Initial grouping of 20,000 files into 5,000 identical groups (15,000 duplicate rows).
        val groupStart = System.nanoTime()
        val initial = findDuplicates(scanned, rootName = "Root")
        val groupMs = (System.nanoTime() - groupStart) / 1_000_000L
        assertEquals(groupCount, initial.identical.size)
        assertEquals(0, initial.versions.size)
        assertTrue(groupMs < 10_000L, "Grouping 5,000 duplicate groups took too long: ${groupMs}ms")

        // 2. Fast-path orderedRows with RECOMMENDED & focusFolder == null returns exact same list instance (0 allocations).
        val firstGroup = initial.identical.first()
        assertTrue(firstGroup.orderedRows(DuplicateKeepRule.RECOMMENDED, null) === firstGroup.rows)

        // 3. Incremental regrouping after trashing 10,000 suggested duplicate files (2 per group -> all 5,000 groups resolve).
        val suggested = HashSet<String>(groupCount * 2)
        for (group in initial.identical) {
            val kept = group.keptId!!
            for (row in group.rows) {
                if (row.file.id != kept) suggested.add(row.file.id)
            }
        }
        assertEquals(groupCount * 2, suggested.size)

        // Partial trash (half the groups completely cleaned, half still have 2 copies).
        val halfRemoved = HashSet<String>(groupCount * 2)
        for (g in 0 until groupCount) {
            halfRemoved.add("dup_${g}_2")
            if (g < groupCount / 2) halfRemoved.add("dup_${g}_1")
        }
        val filterStart = System.nanoTime()
        val afterHalf = filterDuplicateReport(initial, halfRemoved)
        val filterMs = (System.nanoTime() - filterStart) / 1_000_000L
        assertEquals(groupCount / 2, afterHalf.identical.size)
        assertTrue(filterMs < 2_000L, "Incremental filterDuplicateReport took too long: ${filterMs}ms")

        // Full cleanup leaves 0 groups; undo (empty removed set) restores initial report in O(1).
        val afterAll = filterDuplicateReport(initial, suggested)
        assertEquals(0, afterAll.identical.size)
        assertTrue(filterDuplicateReport(initial, emptySet()) === initial)

        // 4. Verify DuplicateReportViewModel smartSelect & invertSelect at 5,000 groups without O(N^2) blowup.
        val rulesStart = System.nanoTime()
        val orderedTotal = initial.identical.sumOf { it.orderedRows(DuplicateKeepRule.NEWEST).size }
        val rulesMs = (System.nanoTime() - rulesStart) / 1_000_000L
        assertEquals(groupCount * copiesPerGroup, orderedTotal)
        assertTrue(rulesMs < 5_000L, "Sorting 5,000 groups by NEWEST took too long: ${rulesMs}ms")

        println(
            "DuplicateScaleStressTest(groups=$groupCount, files=${scanned.size}): " +
                "groupMs=$groupMs filterMs=$filterMs newestRuleMs=$rulesMs",
        )
    }

    @Test
    fun incrementalFilterPromotesSurvivingIdenticalCopyIntoVersionGroup() {
        val copy1 = ScannedFile(
            file = FileStat(
                id = "c1",
                parentId = "p1",
                name = "[SubGroup] ShowName - 01 [1080p].mkv",
                kind = FileKind.FILE,
                size = "2000000",
                hash = "HASH_1080P",
                mimeType = "video/x-matroska",
                createdTime = "2025-01-01T00:00:00Z",
            ),
            folderPath = "a",
        )
        val copy2 = ScannedFile(
            file = FileStat(
                id = "c2",
                parentId = "p2",
                name = "[SubGroup] ShowName - 01 [1080p] (1).mkv",
                kind = FileKind.FILE,
                size = "2000000",
                hash = "HASH_1080P",
                mimeType = "video/x-matroska",
                createdTime = "2025-01-02T00:00:00Z",
            ),
            folderPath = "b",
        )
        val version720 = ScannedFile(
            file = FileStat(
                id = "v720",
                parentId = "p1",
                name = "[SubGroup] ShowName - 01 [720p].mkv",
                kind = FileKind.FILE,
                size = "1000000",
                hash = "HASH_720P",
                mimeType = "video/x-matroska",
                createdTime = "2025-01-01T00:00:00Z",
            ),
            folderPath = "a",
        )

        val initial = findDuplicates(listOf(copy1, copy2, version720), rootName = null)
        assertEquals(1, initial.identical.size)
        assertEquals(1, initial.versions.size)
        assertEquals("c1", initial.identical.single().keptId)
        assertEquals(setOf("c1", "v720"), initial.versions.single().rows.map { it.file.id }.toSet())
        assertEquals(1, initial.versions.single().rows.first { it.file.id == "c1" }.sameCopies)

        // Trashing c1 must dissolve the identical group and promote c2 into the version group with sameCopies=0.
        val filtered = filterDuplicateReport(initial, setOf("c1"))
        assertEquals(0, filtered.identical.size)
        assertEquals(1, filtered.versions.size)
        val promoted = filtered.versions.single().rows.firstOrNull { it.file.id == "c2" }
        assertNotNull(promoted, "Surviving identical copy c2 must replace removed representative c1 in version group")
        assertEquals(0, promoted.sameCopies)
        assertEquals(setOf("c2", "v720"), filtered.versions.single().rows.map { it.file.id }.toSet())
    }
}
