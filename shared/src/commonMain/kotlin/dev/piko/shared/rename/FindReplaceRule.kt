/*
 * 查找替换的语义照搬 PowerToys PowerRename（MIT 许可，Copyright (c) Microsoft Corporation），
 * 对照的是 src/modules/powerrename/lib 下的 Renaming（DoRename）、PowerRenameRegEx（Replace）与
 * Helpers（GetTransformedFileName），以及 unittests 里的期望值。代码是按行为重写的，不是逐行翻译。
 */
package dev.piko.shared.rename

import kotlin.random.Random
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable

/** 查找替换作用于名称的哪一部分。文件夹没有扩展名，除 [EXTENSION] 外都作用于整个文件夹名。 */
@Serializable
enum class RenameScope {
    /** 只改扩展名之前的部分。 */
    NAME,

    /** 只改扩展名（不含句点）。文件夹不参与。 */
    EXTENSION,

    /** 名称连同扩展名。 */
    FULL,
}

/** 最后套用的大小写格式。 */
@Serializable
enum class TextCase {
    NONE,
    UPPER,
    LOWER,

    /** 标题式：每个词首字母大写，但 a、the、of 这类短词除首尾外小写。 */
    TITLE,

    /** 每个词首字母大写。 */
    CAPITALIZED,
}

/** 日期占位符取条目的哪个时间。PowerRename 另有访问时间，网盘没有。 */
@Serializable
enum class TimeSource { CREATED, MODIFIED }

@Serializable
data class FindReplaceOptions(
    val search: String = "",
    val replacement: String = "",
    val useRegex: Boolean = false,
    val caseSensitive: Boolean = false,
    val matchAll: Boolean = true,
    val scope: RenameScope = RenameScope.NAME,
    val includeFiles: Boolean = true,
    val includeFolders: Boolean = true,
    val textCase: TextCase = TextCase.NONE,
    val timeSource: TimeSource = TimeSource.CREATED,
)

/** 正则写错时编译得到的错误说明，交给界面显示。 */
class InvalidPatternException(message: String) : IllegalArgumentException(message)

/**
 * PowerRename 的查找替换，含计数器、随机串、日期占位符与大小写格式。
 *
 * 逐项的处理顺序与 PowerRename 相同：按范围取出要改的部分，查找替换，拼回名称，再套大小写格式。
 * 查找为空时不替换，但大小写格式照样生效。首尾空白与结尾句点由 [planRenames] 统一去掉，这里不管。
 *
 * 计数器只在查找真的匹配到时才加一，没匹配到的项、被范围或类型排除的项都不占号，与 PowerRename 相同：
 * 选中 10 项、只有其中 5 项匹配时，编号是连续的 0 到 4。
 *
 * [randomSeed] 决定随机串，同一种子下同一批条目每次算出的随机串相同，预览不会随打字闪动。
 * 编译正则失败时构造即抛 [InvalidPatternException]。
 */
class FindReplaceRule(
    private val options: FindReplaceOptions,
    private val randomSeed: Long,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : RenameRule {

    private val template = ReplaceTemplate.parse(options.replacement)

    private val regex: Regex? = if (options.useRegex && options.search.isNotEmpty()) {
        try {
            Regex(UNICODE_CLASSES_FLAG + options.search, if (options.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
        } catch (error: IllegalArgumentException) {
            // JVM 与 Android 都抛 PatternSyntaxException，它是 IllegalArgumentException 的子类。
            // 报错里的位置算上了前面加的标志，对用户是错的，去掉
            val message = error.message?.lineSequence()?.firstOrNull().orEmpty()
            throw InvalidPatternException(message.replace(ERROR_INDEX, "").trim())
        }
    } else {
        null
    }

    override fun apply(items: List<RenameSource>, names: List<String>): List<String> {
        val random = Random(randomSeed)
        var counter = 0
        return items.mapIndexed { index, item ->
            val name = names[index]
            if (!appliesTo(item)) return@mapIndexed name
            val (stem, extension) = splitExtension(name, item.isFolder)
            val part = when {
                item.isFolder -> name
                options.scope == RenameScope.NAME -> stem
                options.scope == RenameScope.EXTENSION -> extension.removePrefix(".")
                else -> name
            }
            // 没有扩展名的文件在「只改扩展名」下没有可改的部分，PowerRename 也原样保留
            if (options.scope == RenameScope.EXTENSION && extension.isEmpty()) return@mapIndexed name

            val pieces = template.evaluate(counter, random, if (template.usesTime) timeOf(item) else null)
            val replaced = if (options.search.isNotEmpty()) replace(part, pieces) else null
            if (replaced != null && replaced.second) counter++
            val newPart = replaced?.first ?: part.takeIf { options.textCase != TextCase.NONE } ?: return@mapIndexed name

            val joined = when {
                item.isFolder -> newPart
                // 主名删光时给空名交给冲突检查，不拼出「.mkv」：它对服务端是合法名称，但显然不是用户要的。PowerRename 照拼
                options.scope == RenameScope.NAME -> if (newPart.isBlank()) "" else newPart + extension
                options.scope == RenameScope.EXTENSION -> "$stem.$newPart"
                else -> newPart
            }
            transformCase(joined, item.isFolder)
        }
    }

    private fun appliesTo(item: RenameSource): Boolean = when {
        item.isFolder -> options.includeFolders && options.scope != RenameScope.EXTENSION
        else -> options.includeFiles
    }

    private fun timeOf(item: RenameSource): LocalDateTime? {
        val text = if (options.timeSource == TimeSource.MODIFIED) item.modifiedTime else item.createdTime
        if (text.isEmpty()) return null
        return runCatching { Instant.parse(text).toLocalDateTime(timeZone) }.getOrNull()
    }

    /** 替换后的文字，以及是否匹配到了。 */
    private fun replace(source: String, pieces: List<ReplaceTemplate.Piece>): Pair<String, Boolean> {
        if (source.isEmpty()) return source to false
        val regex = regex ?: return replacePlain(source, render(pieces, null, source))
        val result = StringBuilder()
        var copiedUpTo = 0
        var previousEnd = -1
        var matched = false
        var match = regex.find(source)
        while (match != null) {
            val start = match.range.first
            val end = match.range.last + 1
            // 紧接在上一个匹配之后的空匹配不算：`.*` 替换「AAAAAA」得到一个 Foo，而不是 JVM 与 JS 那样的 FooFoo。
            // PowerRename 的单元测试就是这个期望，它用的 MSVC std::regex 如此
            if (!(start == end && start == previousEnd)) {
                result.append(source, copiedUpTo, start).append(render(pieces, match, source))
                copiedUpTo = end
                previousEnd = end
                matched = true
                if (!options.matchAll) break
            }
            match = match.next()
        }
        result.append(source, copiedUpTo, source.length)
        return result.toString() to matched
    }

    private fun replacePlain(source: String, replacement: String): Pair<String, Boolean> {
        val search = options.search
        val ignoreCase = !options.caseSensitive
        val result = StringBuilder()
        var copiedUpTo = 0
        var found = source.indexOf(search, 0, ignoreCase)
        if (found < 0) return source to false
        while (found >= 0) {
            result.append(source, copiedUpTo, found).append(replacement)
            copiedUpTo = found + search.length
            if (!options.matchAll) break
            found = source.indexOf(search, copiedUpTo, ignoreCase)
        }
        result.append(source, copiedUpTo, source.length)
        return result.toString() to true
    }

    /**
     * 大写与小写按范围作用：只改主名时只改主名，名称连同扩展名时扩展名一起改。标题式与每词首字母大写只作用于主名，
     * 只改扩展名时不做，这两条都与 PowerRename 相同。PowerRename 在「只改扩展名」下遇到没有扩展名的文件会把整个
     * 名称改成大写，是个意外，这里前面已经原样返回。
     */
    private fun transformCase(name: String, isFolder: Boolean): String {
        val textCase = options.textCase
        if (textCase == TextCase.NONE) return name
        if (isFolder) return applyCase(name, textCase)
        val (stem, extension) = splitExtension(name, isFolder = false)
        return when (textCase) {
            TextCase.UPPER, TextCase.LOWER -> when (options.scope) {
                RenameScope.NAME -> applyCase(stem, textCase) + extension
                RenameScope.EXTENSION -> stem + applyCase(extension, textCase)
                RenameScope.FULL -> applyCase(name, textCase)
            }
            else -> if (options.scope == RenameScope.EXTENSION) name else applyCase(stem, textCase) + extension
        }
    }
}

/**
 * 让 \w、\d、\b 这些字符类按 Unicode 算，与 Android 一致：Android 的正则是 ICU，字符类一直按 Unicode 算，
 * 传 UNICODE_CHARACTER_CLASS 标志直接抛异常；HotSpot 默认只认 ASCII（PowerRename 的 std::regex 也是）。
 * 不按平台分支，试编译一次内联的 (?U)：HotSpot 接受，就加上；ICU 的内联标志表里没有 U，编译失败就不加，
 * 那里本来就按 Unicode 算。哪天 Android 接受了它，含义也是 Unicode 字符类，结果不变。
 * 这样用户写的 \d+ 在两端都能抓到「２」「𝟐」，预览结果不因设备而异；抓到的片段只拼进名称，从不当数字解析。
 */
internal val UNICODE_CLASSES_FLAG: String = if (runCatching { Regex("(?U)a") }.isSuccess) "(?U)" else ""

private val ERROR_INDEX = Regex("near index [0-9]+")

internal fun applyCase(text: String, textCase: TextCase): String = when (textCase) {
    TextCase.NONE -> text
    TextCase.UPPER -> text.uppercase()
    TextCase.LOWER -> text.lowercase()
    TextCase.TITLE -> capitalizeWords(text, keepMinorWordsLower = true)
    TextCase.CAPITALIZED -> capitalizeWords(text, keepMinorWordsLower = false)
}

/**
 * 词由空白与标点隔开，词首大写、其余小写，结尾的空白与标点不动。撇号后面的字母不算词首（don't 不变成 Don'T），
 * 撇号前面是空白或标点时才算，'quoted' 里的 q 仍大写。标题式另把 [MINOR_WORDS] 里的词小写，第一个词与最后一个词除外。
 * 汉字算字母，与相邻的汉字连成一个词，大小写对它们不起作用。
 */
private fun capitalizeWords(text: String, keepMinorWordsLower: Boolean): String {
    val chars = text.toCharArray()
    var length = chars.size
    while (length > 0 && isBreak(chars[length - 1])) length--
    var firstWord = true
    for (index in 0 until length) {
        if (!startsWord(chars, index)) {
            chars[index] = chars[index].lowercaseChar()
            continue
        }
        if (isBreak(chars[index])) continue
        var wordLength = 0
        while (index + wordLength < length && !isBreak(chars[index + wordLength])) wordLength++
        val word = chars.concatToString(index, index + wordLength).lowercase()
        val lastWord = index + wordLength == length
        val upper = !keepMinorWordsLower || firstWord || lastWord || word !in MINOR_WORDS
        chars[index] = if (upper) chars[index].uppercaseChar() else chars[index].lowercaseChar()
        firstWord = false
    }
    return chars.concatToString()
}

private fun startsWord(chars: CharArray, index: Int): Boolean {
    if (index == 0 || chars[index - 1].isWhitespace()) return true
    if (!isPunctuation(chars[index - 1])) return false
    return chars[index - 1] != '\'' || index == 1 || isBreak(chars[index - 2])
}

private fun isBreak(char: Char): Boolean = char.isWhitespace() || isPunctuation(char)

// 对应 C 的 iswpunct：可见、非空白、非字母数字
private fun isPunctuation(char: Char): Boolean = !char.isLetterOrDigit() && !char.isWhitespace() && !char.isISOControl()

private val MINOR_WORDS = setOf("a", "an", "to", "the", "at", "by", "for", "in", "of", "on", "up", "and", "as", "but", "or", "nor")
