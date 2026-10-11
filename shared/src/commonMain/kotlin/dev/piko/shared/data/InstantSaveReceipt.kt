package dev.piko.shared.data

/** 一次秒传实际确认的内容；路径来自提交快照，ID 只取服务端回执或已知预览副本。 */
data class InstantSaveReceipt(
    val account: String,
    val targetId: String,
    val confirmed: Map<String, String> = emptyMap(),
    val unconfirmed: Set<String> = emptySet(),
    val failed: Set<String> = emptySet(),
    val notAttempted: Set<String> = emptySet(),
    val folders: Map<String, String> = emptyMap(),
    val unconfirmedFolder: String? = null,
    /** 文件已经落入目标，但后续名称修改未确认；不能为重试改名再次保存文件。 */
    val namingUnconfirmed: Set<String> = emptySet(),
) {
    val requiresInspection: Boolean get() = unconfirmed.isNotEmpty() || unconfirmedFolder != null || namingUnconfirmed.isNotEmpty()
    val message: String get() = buildList {
        add("已确认保存 ${confirmed.size} 个文件")
        if (failed.isNotEmpty()) add("${failed.size} 个未保存")
        if (notAttempted.isNotEmpty()) add("${notAttempted.size} 个未执行")
        if (unconfirmed.isNotEmpty()) add("${unconfirmed.size} 个结果待确认")
        if (unconfirmedFolder != null) add("目录创建结果待确认")
        if (namingUnconfirmed.isNotEmpty()) add("${namingUnconfirmed.size} 个已保存文件的名称待确认")
    }.joinToString("，")
}

class InstantSaveIncomplete(val receipt: InstantSaveReceipt, cause: Throwable) : Exception(receipt.message, cause)
