package dev.piko.shared.data

import dev.piko.shared.i18n.enCount
import dev.piko.shared.i18n.tr

/** 一次秒传或引用保存的结果回执；路径来自提交快照，ID 只取服务端回执或已知预览副本。 */
data class InstantSaveReceipt(
    val account: String,
    val targetId: String,
    val confirmed: Map<String, String> = emptyMap(),
    val failed: Set<String> = emptySet(),
    val notAttempted: Set<String> = emptySet(),
    val folders: Map<String, String> = emptyMap(),
) {
    val message: String get() = buildList {
        if (confirmed.isNotEmpty() || (failed.isEmpty() && notAttempted.isEmpty())) {
            add(tr("已保存 ${confirmed.size} 个文件", "Saved ${enCount(confirmed.size, "file")}", "${confirmed.size} ファイルを保存しました", "파일 ${confirmed.size}개 저장됨", "已儲存 ${confirmed.size} 個檔案"))
        }
        if (failed.isNotEmpty()) add(tr("${failed.size} 个失败", "${enCount(failed.size, "file")} failed", "${failed.size} 件失敗", "${failed.size}개 실패", "${failed.size} 個失敗"))
        if (notAttempted.isNotEmpty()) add(tr("${notAttempted.size} 个未执行", "${enCount(notAttempted.size, "file")} not attempted", "${notAttempted.size} 件未実行", "${notAttempted.size}개 미실행", "${notAttempted.size} 個未執行"))
    }.joinToString(tr("，", "; ", "、", ", ", "，"))
}

class InstantSaveIncomplete(val receipt: InstantSaveReceipt, cause: Throwable) : Exception(receipt.message, cause)
