package dev.piko.data.repository

/**
 * 文件名的自然顺序：连续数字按数值比较，其余字符不分大小写。
 *
 * 按字符串比较时「10」排在「2」前面，剧集会排成 1、10、11……2。数字段先去掉前导零比位数，
 * 位数相同再逐位比，所以任意长的数字串也不会溢出。只认 ASCII 数字：全角数字与其他文字里的
 * 数字 isDigit() 也为真，但它们的码位不能直接当数值比。
 *
 * 逐字符走下标而不拆分成片段：比较器每次比较都会调用，千项目录一次排序就是几万次，
 * 不为每次比较分配字符串。
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        if (a === b) return 0
        var i = 0
        var j = 0
        // 数值相等但前导零不同（「2」与「02」）时先记下，名字其余部分都相同才用它定序
        var zeroTieBreak = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isAsciiDigit() && cb.isAsciiDigit()) {
                val startA = i
                val startB = j
                while (i < a.length && a[i] == '0') i++
                while (j < b.length && b[j] == '0') j++
                var endA = i
                while (endA < a.length && a[endA].isAsciiDigit()) endA++
                var endB = j
                while (endB < b.length && b[endB].isAsciiDigit()) endB++

                val sigLengthOrder = (endA - i).compareTo(endB - j)
                if (sigLengthOrder != 0) return sigLengthOrder
                while (i < endA) {
                    val order = a[i].compareTo(b[j])
                    if (order != 0) return order
                    i++
                    j++
                }
                // 数值相等：前导零少的在前，「1」先于「01」，免得两者比较结果为 0、顺序随输入而变
                if (zeroTieBreak == 0) {
                    zeroTieBreak = (endA - startA).compareTo(endB - startB)
                }
                i = endA
                j = endB
            } else {
                val order = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (order != 0) return order
                i++
                j++
            }
        }
        val lengthOrder = (a.length - i).compareTo(b.length - j)
        return if (lengthOrder != 0) lengthOrder else zeroTieBreak
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
}
