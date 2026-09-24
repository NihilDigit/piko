package dev.piko.shared.naming

/**
 * 测试夹具：desktopTest/resources/naming/ 下的 TSV，每行「种子内路径<TAB>字节数」，# 开头的是来源说明。
 * 全部取自 piko-name-corpus 的 nyaa 部分，见各文件首行。
 */
internal object NamingFixtures {
    fun load(name: String): List<MediaFileInput> {
        val stream = NamingFixtures::class.java.getResourceAsStream("/naming/$name.tsv") ?: error("缺少夹具 $name")
        return stream.bufferedReader(Charsets.UTF_8).readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val (path, size) = line.split('\t')
                MediaFileInput(path, size.toLong())
            }
    }

    /** 去掉目录，模拟网盘里被拍平的文件。 */
    fun flatten(files: List<MediaFileInput>) = files.map { it.copy(path = it.path.substringAfterLast('/')) }
}

/** 作品 → 分区 → 行标题的文字轮廓，断言整体结构时比逐字段比较更易读。 */
internal fun MediaBatch.outline(): String = works.joinToString("\n") { work ->
    (listOf(work.title ?: "<无标题>") + work.sections.map { section ->
        "  ${section.section.label}: " + section.entries.joinToString(", ") { it.label ?: "<未识别>" }
    }).joinToString("\n")
}

internal fun MediaBatch.work(title: String): MediaWork = works.single { it.title == title }

internal fun MediaWork.section(section: Section): WorkSection = sections.single { it.section == section }

internal fun MediaWork.entry(label: String): MediaEntry = sections.flatMap { it.entries }.single { it.label == label }
