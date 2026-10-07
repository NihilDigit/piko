package dev.piko.shared.state

import io.github.nihildigit.pikpak.FileStat
import kotlin.random.Random

internal fun FileStat.clipContentKey(): String =
    if (hash.isNotBlank()) "hash:${hash.lowercase()}:$size" else "id:$id"

/**
 * 挑段时一个视频的归属：所在文件夹、作品（文件名解析出的同一部）与内容（gcid，同一内容的几份副本算一个）。
 * [atRoot] 是放在信息流打开的那一层，[original] 是查过没有 720P 转码、只能放原画。
 */
internal class FeedCandidate(
    val id: String,
    val folder: String,
    val work: String,
    val content: String,
    val atRoot: Boolean = true,
    val original: Boolean = false,
)

/**
 * 从 [pool] 里挑出接下来的至多 [limit] 段，按放的先后排好。
 *
 * 先筛：同一内容只留一份；已在队列里的、最近看过的不挑；遍历还没走完时只挑一轮里还没出过的，等新列出的目录。
 * 再排：逐个挑，视频少的文件夹按比例隔开（[folderPace]），其余之中挑代价最小的，代价是几项倾向之和，见 [FeedCost]。
 *
 * [watched] 是看过的，[queued] 是排在当前之后、已挑好的，都按先后排列；两者连同这一批挑中的一起，
 * 决定一个文件夹、一部作品「最近出现过几次」。
 *
 * [hold] 不为空时，队列里已有它规定段数的文件夹先不再挑，留着位置等后面列出的目录，见 [FeedHold]。
 */
internal fun selectFeedCandidates(
    pool: List<FeedCandidate>,
    queued: List<FeedCandidate>,
    watched: List<FeedCandidate>,
    counts: Map<String, Int>,
    collecting: Boolean,
    limit: Int,
    hold: FeedHold? = null,
    random: Random = Random,
): List<FeedCandidate> {
    val unique = pool.shuffled(random).distinctBy { it.content }
    val queuedContent = queued.mapTo(HashSet()) { it.content }
    // 同一内容至少隔这么多段才再出，视频不多时至多隔一半，否则一个几十个视频的文件夹第二轮就挑不出来
    val contentSpacing = minOf(MAX_CONTENT_SPACING, unique.size / 2)
    val avoid = watched.takeLast(contentSpacing).mapTo(HashSet()) { it.content }
    val eligible = unique.filterTo(ArrayList()) {
        it.content !in queuedContent && it.content !in avoid && (!collecting || counts.getOrElse(it.content) { 0 } == 0)
    }
    val pace = folderPace(unique, contentSpacing)
    val history = ArrayList(watched.takeLast(MAX_CONTENT_SPACING) + queued)
    val picked = mutableListOf<FeedCandidate>()
    // 抖动在挑之前给定：同一批里每一步比较的是同一组随机数，排序才前后一致
    val jitter = eligible.associate { it.id to random.nextDouble() }
    while (picked.size < limit && eligible.isNotEmpty()) {
        val ahead = queued.size + picked.size
        val folderHeat = heat(history, FeedCost.FOLDER_SPAN) { it.folder }
        val workHeat = heat(history, FeedCost.WORK_SPAN) { it.work }
        val sinceFolder = distanceSinceLast(history) { it.folder }
        val aheadPerFolder = (queued + picked).groupingBy { it.folder }.eachCount()
        val held = eligible.filter { hold == null || ahead < hold.from || aheadPerFolder.getOrElse(it.folder) { 0 } < hold.perFolder }
        val paced = held.filter { (sinceFolder[it.folder] ?: Int.MAX_VALUE) >= pace.getValue(it.folder) }.ifEmpty { held }
        val choice = paced
            .minByOrNull {
                FeedCost.FOLDER * folderHeat.getOrElse(it.folder) { 0.0 } +
                    FeedCost.WORK * workHeat.getOrElse(it.work) { 0.0 } +
                    FeedCost.REUSE * counts.getOrElse(it.content) { 0 } +
                    (if (it.original) FeedCost.ORIGINAL else 0.0) +
                    (if (it.atRoot) 0.0 else FeedCost.SUBFOLDER) +
                    jitter.getValue(it.id)
            } ?: break
        eligible -= choice
        picked += choice
        history += choice
    }
    return picked
}

private const val MAX_CONTENT_SPACING = 50

/**
 * 每个文件夹至少隔几段才再出：同一内容要隔 [contentSpacing] 段才能再出，文件夹里有 k 个视频，
 * 它平均每 [contentSpacing]/k 段才出得起一次。比这更密地出，它的视频很快放完，随后在同一内容的间隔里
 * 一直轮不到，留下的一长串全出自大文件夹。只靠热度罚不住：大文件夹多是同一部番，每连出一段，
 * 文件夹与作品的热度一起涨，小文件夹于是被一段接一段地提前用光（40 集对 4 部时连出 17 段，2026-10-08）。
 */
private fun folderPace(unique: List<FeedCandidate>, contentSpacing: Int): Map<String, Double> =
    unique.groupingBy { it.folder }.eachCount().mapValues { (_, count) -> contentSpacing.toDouble() / count }

/** 每个键最近一次出现隔了几段，前一段是 1。 */
private fun distanceSinceLast(history: List<FeedCandidate>, key: (FeedCandidate) -> String): Map<String, Int> {
    val since = HashMap<String, Int>()
    history.asReversed().forEachIndexed { index, item -> since.getOrPut(key(item)) { index + 1 } }
    return since
}

/**
 * 最近 [span] 段里每个键出现的「热度」：前一段是 1，往前逐段线性减到 1/[span]，同一个键出现几次就加几次。
 * 只看隔了多远会把「刚出现过一次」与「最近六段里出现了三次」算成一样。
 */
private fun heat(history: List<FeedCandidate>, span: Int, key: (FeedCandidate) -> String): Map<String, Double> {
    val heat = HashMap<String, Double>()
    history.takeLast(span).asReversed().forEachIndexed { distance, item ->
        heat[key(item)] = heat.getOrElse(key(item)) { 0.0 } + (span - distance).toDouble() / span
    }
    return heat
}

/**
 * 挑段代价的几项权重，单位都是「一段随机抖动」（0 到 1）。
 *
 * 文件夹与作品按最近出现的热度罚，不按文件夹轮转：轮转对每个文件夹一视同仁，十几集的番与只有一部片子的文件夹
 * 同样每轮出一次，小的很快挑完，剩下的一长串又全出自大的；热度只要求隔开，大的文件夹照样多出，只是不连着出。
 * 热度单用同样会把小文件夹提前用光，由 [folderPace] 按比例拦着。
 * 也不按文件数加权抽样：抽样只管比例，不管相邻，同一部番连出两三集的概率并不小，大文件夹占九成时更是成串地出。
 *
 * 转码与当前层也化作代价，不再分档：分档时有转码的挑完才轮到原画，一个有转码的番剧文件夹会一直排在前面。
 */
internal object FeedCost {
    /** 文件夹看最近这么多段。 */
    const val FOLDER_SPAN = 6

    /** 前一段出自同一文件夹时的代价。高于 [ORIGINAL]：宁可放一段起播慢的原画，也不连着放同一个文件夹。 */
    const val FOLDER = 4.0

    /** 作品看最近这么多段，比文件夹短：同一部番跨季分在几个文件夹时，只防它们连着出现。 */
    const val WORK_SPAN = 3

    /** 前一段是同一部作品时另加的代价，与 [FOLDER] 相加后，同一部番相邻几乎不会发生，除非没有别的可放。 */
    const val WORK = 6.0

    /**
     * 这一内容已出过一段时的代价。遍历还在进行时出过的根本不挑（等新列出的目录），走完以后才用得上：
     * 小文件夹的视频挑完了，与其让大文件夹连着放到底，不如让小文件夹里放过的再出一次。
     * 至少隔着 [selectFeedCandidates] 里避开的那一串，同一个文件的两段不会挨着。
     */
    const val REUSE = 6.0

    /** 只有原画的代价。原画起播要读 5 到 10 MB，刷快了会卡，所以比文件夹轮换的代价略低，但不至于排到最后。 */
    const val ORIGINAL = 3.0

    /** 子文件夹里的代价：站在一部番的目录里先刷这部番，子文件夹里的花絮、特典少些，但不再等正片放完。 */
    const val SUBFOLDER = 1.5
}

/**
 * 遍历还没走完时的留位：队列里已有 [perFolder] 段的文件夹先不再挑，等后面列出的目录；队列不到 [from] 段时不留，照常补。
 *
 * 补队列只认已列出的目录，而打开时一口气要补二十几段，不留位的话补进去的全是最先列出的那一两个文件夹，
 * 代价再怎么罚也无从隔开：别的文件夹还没列到（2026-10-08）。[from] 是兜底，一棵大而视频少的树要走好几十秒，
 * 其间队列至少有这么多段，不至于停在转圈上。
 */
internal class FeedHold(val perFolder: Int, val from: Int) {
    companion object {
        /** 刚打开、列出的目录还不多：每个文件夹先只排一段，队列空了才破例。首段不因此变慢，它总是第一个文件夹的头一段。 */
        val Opening = FeedHold(perFolder = 1, from = 1)

        /** 列出的目录够多以后：每个文件夹至多两段，队列不到三段（界面冷启动只备的段数）时不留。 */
        val Collecting = FeedHold(perFolder = 2, from = 3)
    }
}
