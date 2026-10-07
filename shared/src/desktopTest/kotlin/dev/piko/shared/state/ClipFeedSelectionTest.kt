package dev.piko.shared.state

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClipFeedSelectionTest {
    private fun item(id: String, folder: String = "", work: String = id, content: String = id, atRoot: Boolean = folder == "", original: Boolean = false) =
        FeedCandidate(id, folder, work, content, atRoot, original)

    @Test fun duplicateContentIsSelectedOnce() {
        val files = listOf(item("a", content = "same"), item("b", content = "same"), item("c"))
        val picked = choose(files).map { it.content }
        assertEquals(2, picked.size)
        assertEquals(setOf("same", "c"), picked.toSet())
    }

    @Test fun queuedCopiesAndRecentlyWatchedContentAreExcluded() {
        val files = listOf(item("a"), item("b"), item("c"), item("d"))
        val result = choose(files, queued = listOf(files[0]), watched = listOf(files[1]))
        assertEquals(setOf("c", "d"), result.map { it.id }.toSet())
    }

    @Test fun leastSelectedContentPrecedesPreviouslyRepeatedContent() {
        val files = listOf(item("a"), item("b"), item("c"))
        assertEquals(listOf("c", "b", "a"), choose(files, counts = mapOf("a" to 4, "b" to 1)).map { it.id })
    }

    @Test fun collectionWaitsForNewContentBeforeRepeating() {
        val files = listOf(item("a"), item("b"))
        assertEquals(listOf("b"), choose(files, counts = mapOf("a" to 1), collecting = true).map { it.id })
    }

    @Test fun singleVideoCanRepeatWithoutDuplicatingPendingClips() {
        val files = listOf(item("a"))
        assertEquals(1, choose(files, watched = listOf(files[0])).size)
        assertTrue(choose(files, queued = listOf(files[0])).isEmpty())
    }

    /** 根下三部番各 12 集、一个 5 部电影的文件夹、3 个散文件：前 30 段要散开。 */
    @Test fun consecutiveClipsComeFromDifferentFoldersAndWorks() = repeat(SEEDS) { seed ->
        val pool = seriesTree()
        val feed = watch(pool, 30, Random(seed))
        val windows = feed.windowed(10)
        assertTrue(windows.all { w -> w.groupingBy { it.folder }.eachCount().values.max() <= 3 }, "第 $seed 次：10 段里同一文件夹超过 3 段\n${feed.map { it.id }}")
        assertTrue(feed.zipWithNext().none { (a, b) -> a.work == b.work }, "第 $seed 次：同一作品相邻\n${feed.map { it.id }}")
        assertTrue(feed.zipWithNext().none { (a, b) -> a.folder == b.folder }, "第 $seed 次：同一文件夹相邻\n${feed.map { it.id }}")
    }

    /** 同一部番分在两季的文件夹里，作品相同、文件夹不同，也不相邻。 */
    @Test fun aWorkSplitAcrossFoldersIsNotPlayedBackToBack() = repeat(SEEDS) { seed ->
        val pool = (1..8).map { item("s1-$it", folder = "S1", work = "frieren", atRoot = false) } +
            (1..8).map { item("s2-$it", folder = "S2", work = "frieren", atRoot = false) } +
            (1..8).map { item("m-$it", folder = "Movies", atRoot = false) }
        val feed = watch(pool, 16, Random(seed))
        assertTrue(feed.zipWithNext().none { (a, b) -> a.work == b.work }, "第 $seed 次：${feed.map { it.id }}")
    }

    /** 小文件夹挑完以后，大文件夹不连着放到底：同一个文件的两段之间仍隔着至少一半的视频。 */
    @Test fun aLargeFolderDoesNotRunOnOnceSmallOnesAreUsedUp() = repeat(SEEDS) { seed ->
        val pool = (1..40).map { item("big-$it", folder = "Big", work = "big", atRoot = false) } +
            (1..4).map { item("small-$it", folder = "Small", atRoot = false) }
        val feed = watch(pool, 120, Random(seed))
        var run = 1
        var longestRun = 1
        feed.zipWithNext().forEach { (a, b) ->
            run = if (a.folder == b.folder) run + 1 else 1
            longestRun = maxOf(longestRun, run)
        }
        assertTrue(longestRun <= 6, "第 $seed 次：同一文件夹连着 $longestRun 段")
        val gaps = feed.withIndex().groupBy({ it.value.content }, { it.index }).values.flatMap { it.zipWithNext { a, b -> b - a } }
        assertTrue(gaps.all { it >= pool.size / 2 }, "第 $seed 次：同一文件隔得太近，最近 ${gaps.min()} 段")
    }

    /** 同一文件夹里有转码的先放；不同文件夹之间只有原画的也会轮到，不等有转码的全放完。 */
    @Test fun transcodedClipsAreAPreferenceNotATier() = repeat(SEEDS) { seed ->
        val mixed = (1..10).map { item("t-$it", folder = "A", work = "t$it") } + (1..10).map { item("o-$it", folder = "A", work = "o$it", original = true) }
        assertTrue(watch(mixed, 10, Random(seed)).none { it.original }, "第 $seed 次：同一文件夹里原画先于转码")
        val split = (1..10).map { item("t-$it", folder = "A", atRoot = false) } + (1..10).map { item("o-$it", folder = "B", atRoot = false, original = true) }
        assertTrue(watch(split, 6, Random(seed)).any { it.original }, "第 $seed 次：只有原画的文件夹一直轮不到")
    }

    /** 遍历刚开始、只列出一个文件夹时，队列里先只放它一段，等别的文件夹列出来；队列空了才破例。 */
    @Test fun openingLeavesRoomForFoldersNotListedYet() {
        val listed = (1..12).map { item("a-$it", folder = "A", atRoot = false) }
        assertEquals(1, choose(listed, collecting = true, limit = 8, hold = FeedHold.Opening).size)
        assertTrue(choose(listed, queued = listOf(listed[0]), collecting = true, limit = 8, hold = FeedHold.Opening).isEmpty())
        val more = listed + (1..12).map { item("b-$it", folder = "B", atRoot = false) }
        assertEquals(listOf("B"), choose(more, queued = listOf(listed[0]), collecting = true, limit = 8, hold = FeedHold.Opening).map { it.folder })
    }

    private fun seriesTree(): List<FeedCandidate> =
        listOf("frieren", "meshi", "bocchi").flatMap { show -> (1..12).map { item("$show-$it", folder = show, work = show, atRoot = false) } } +
            (1..5).map { item("movie-$it", folder = "movies", atRoot = false) } +
            (1..3).map { item("loose-$it") }

    /** 遍历走完以后一段一段地挑、一段一段地看，与 fillAhead 补满之后每翻一页补一段相同。 */
    private fun watch(pool: List<FeedCandidate>, count: Int, random: Random): List<FeedCandidate> {
        val watched = mutableListOf<FeedCandidate>()
        val counts = HashMap<String, Int>()
        repeat(count) {
            val next = selectFeedCandidates(pool, emptyList(), watched, counts, collecting = false, limit = 1, random = random).single()
            counts[next.content] = counts.getOrElse(next.content) { 0 } + 1
            watched += next
        }
        return watched
    }

    private fun choose(
        files: List<FeedCandidate>, queued: List<FeedCandidate> = emptyList(), watched: List<FeedCandidate> = emptyList(),
        counts: Map<String, Int> = emptyMap(), collecting: Boolean = false, limit: Int = 100, hold: FeedHold? = null,
    ) = selectFeedCandidates(files, queued, watched, counts, collecting, limit, hold, Random(1))

    private companion object {
        const val SEEDS = 50
    }
}
