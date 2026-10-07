package dev.piko.shared.smoke

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.state.Clip
import dev.piko.shared.state.ClipFeedSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 信息流挑段的分布：在一棵构造的目录树上真实地开一次信息流，逐段「看」下去，记下前 30 段出自哪里。
 * 列目录与查详情走真实的仓库与 SDK，只把服务端换成 FakePikPakServer，遍历的先后与补队列的时机都是真的。
 */
class ClipFeedSpreadSmokeTest {
    /** 一次信息流里依次放到的段，及其所在文件夹的名字。 */
    private class Run(val clips: List<Clip>, val folderOf: Map<String, String>)

    private fun tree(server: FakePikPakServer): Map<String, String> {
        val folders = mutableMapOf("" to "(根)")
        fun series(folderName: String, title: String) {
            val folder = server.addFolder(folderName)
            folders[folder.id] = folderName
            (1..12).forEach { server.addVideo("[Sub] $title - ${it.toString().padStart(2, '0')} [1080p].mkv", folder.id) }
        }
        series("葬送的芙莉莲", "Sousou no Frieren")
        series("迷宫饭", "Dungeon Meshi")
        series("孤独摇滚", "Bocchi the Rock!")
        val movies = server.addFolder("电影")
        folders[movies.id] = "电影"
        listOf("Your Name (2016)", "Spirited Away (2001)", "Perfect Blue (1997)", "Akira (1988)", "Paprika (2006)")
            .forEach { server.addVideo("$it.mkv", movies.id, durationSeconds = 6000.0) }
        listOf("VID_20240101_120000.mp4", "演唱会现场.mp4", "猫.mp4").forEach { server.addVideo(it, durationSeconds = 300.0) }
        return folders
    }

    /**
     * 打开根目录的信息流，每取好一段就接进翻页器并翻到它，翻页之间停 [watchMs]。
     * 一段真实的片段要看几秒到三十秒，这里压短了，但列目录与查详情同样按比例压短（[apiDelayMs]）。
     */
    private fun play(count: Int, apiDelayMs: Long, watchMs: Long): Run {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            return runBlocking {
                withTimeout(60_000) {
                    withContext(dispatcher) {
                        val server = FakePikPakServer()
                        val folders = tree(server)
                        server.apiDelayMs = apiDelayMs
                        val provider = server.provider()
                        val session = ClipFeedSession(PikoDriveRepository(provider, MemoryPreferences()), PikoMediaRepository(provider), null, scope)
                        session.open(listOf(PikoDriveRepository.ROOT_BREADCRUMB))
                        repeat(count) {
                            while (session.upcoming.isEmpty()) delay(5)
                            session.promote(session.upcoming.first())
                            session.moveTo(session.clips.lastIndex)
                            delay(watchMs)
                        }
                        Run(session.clips.take(count), folders)
                    }
                }
            }
        } finally {
            scope.cancel()
            dispatcher.close()
            executor.shutdown()
        }
    }

    /**
     * 目录是一层层列出来的，补队列不能把最先列出的那个文件夹一口气排满。
     * 改前头 10 段平均只出自 2.3 个文件夹，同一文件夹最多 7 段（2026-10-08）；这里的界限放宽，只抓这种退化。
     */
    @Test
    fun `the first clips spread over folders listed later`() {
        val run = play(10, apiDelayMs = 20, watchMs = 40)
        val folders = run.clips.map { it.parentId }
        assertTrue(folders.toSet().size >= 4, "头 10 段只出自 ${folders.toSet().size} 个文件夹：${folders.map(run.folderOf::get)}")
        assertTrue(maxPerFolder(run, 10) <= 4, "头 10 段里同一文件夹出了 ${maxPerFolder(run, 10)} 段：${folders.map(run.folderOf::get)}")
    }

    /** 只在本机手动跑：打印一次的前 30 段，再统计多次的分布。`PIKO_CLIP_SIM=1` 时才跑。 */
    @Test
    fun `print where the first clips come from`() {
        if (System.getenv("PIKO_CLIP_SIM") != "1") return
        val out = StringBuilder()
        val sample = play(30, apiDelayMs = 150, watchMs = 300)
        sample.clips.forEachIndexed { i, clip ->
            out.appendLine("${(i + 1).toString().padStart(2)}  ${sample.folderOf[clip.parentId]!!.padEnd(8, '　')}  ${clip.name}")
        }
        val runs = (1..10).map { play(30, apiDelayMs = 40, watchMs = 60) }
        out.appendLine()
        out.appendLine("10 次统计：")
        out.appendLine("  前 10 段中同一文件夹最多出现的次数，平均 ${runs.map { maxPerFolder(it, 10) }.average()}，最大 ${runs.maxOf { maxPerFolder(it, 10) }}")
        out.appendLine("  前 30 段中相邻两段同一文件夹的次数，平均 ${runs.map { adjacentSameFolder(it) }.average()}")
        out.appendLine("  前 30 段中相邻两段同一作品的次数，平均 ${runs.map { adjacentSameWork(it) }.average()}")
        out.appendLine("  前 10 段覆盖的文件夹数（共 5 个，含根），平均 ${runs.map { r -> r.clips.take(10).map { it.parentId }.toSet().size }.average()}")
        println(out)
        java.io.File(System.getProperty("java.io.tmpdir"), "piko-clip-sim.txt").writeText(out.toString())
    }

    private fun maxPerFolder(run: Run, first: Int) = run.clips.take(first).groupingBy { it.parentId }.eachCount().values.max()

    private fun adjacentSameFolder(run: Run) = run.clips.zipWithNext().count { (a, b) -> a.parentId == b.parentId }

    // 构造的树里一个文件夹就是一部作品，根下的散文件与电影各自成一部
    private fun adjacentSameWork(run: Run) = run.clips.zipWithNext().count { (a, b) ->
        a.parentId == b.parentId && run.folderOf[a.parentId] !in setOf("(根)", "电影")
    }
}
