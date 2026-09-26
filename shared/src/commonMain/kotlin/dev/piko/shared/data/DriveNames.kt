package dev.piko.shared.data

/**
 * PikPak 对网盘里名称的限制。2026-09-26 实测重命名与新建文件夹，拒绝时都回 file_name_with_illegal_chars：
 * 含 / \ : * ? " < > | 或制表符、换行、回车，以句点结尾（含 . 与 ..），首尾有空格。
 * 以句点开头、全角空格、% # & + '、CON 之类的 Windows 保留名、256 个字符都接受；
 * \u0001 与 \u007f 也接受，所以不按控制字符整类删。
 *
 * 与 [dev.piko.data.repository.FileNameSanitizer] 分开：那边给本机文件系统用，会替换字符、改扩展名的大小写、
 * 截断长度，用户自己输入的网盘名称不该被这样改写，这里只删。
 */
object DriveNames {
    private val UNSUPPORTED = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '\t', '\n', '\r')

    /**
     * 删掉不支持的字符，去掉首尾空格与结尾的句点。两者交替出现（如 `a. .`）时要反复去，直到不再变化。
     * 结果为空说明没法自动修。
     */
    fun clean(name: String): String {
        var current = name.filterNot { it in UNSUPPORTED }
        while (true) {
            val next = current.trim(' ').trimEnd('.')
            if (next == current) return current
            current = next
        }
    }

    /**
     * [clean] 会删掉 [name] 里哪些位置的字符，供界面标出改动。删掉这些位置得到的正是 [clean] 的结果：
     * 删字符之后只从两端去，开头只去空格，所以保留下来的是去掉不支持字符后的一段连续区间。
     */
    fun removedIndices(name: String): Set<Int> {
        val kept = name.indices.filterNot { name[it] in UNSUPPORTED }
        val filtered = kept.map { name[it] }.joinToString("")
        val cleaned = clean(name)
        val start = if (cleaned.isEmpty()) filtered.length else filtered.length - filtered.trimStart(' ').length
        val keptRange = start until start + cleaned.length
        return name.indices.toSet() - keptRange.map { kept[it] }.toSet()
    }

    /**
     * 除首尾空格之外，[name] 有哪些 PikPak 不支持的地方，写成给用户看的短语。
     * 首尾空格不算：输入框里的名称本来就会先去掉首尾空格再提交，不必为此打扰用户。
     */
    fun unsupportedParts(name: String): List<String> {
        val trimmed = name.trim(' ')
        val chars = trimmed.filter { it in UNSUPPORTED }.toSet()
        return buildList {
            val visible = chars.filterNot { it.isWhitespace() }
            if (visible.isNotEmpty()) add(visible.joinToString(" ", prefix = "字符 "))
            if ('\n' in chars || '\r' in chars) add("换行")
            if ('\t' in chars) add("制表符")
            if (trimmed.filterNot { it in UNSUPPORTED }.trimEnd(' ').endsWith('.')) add("结尾的句点")
        }
    }
}
