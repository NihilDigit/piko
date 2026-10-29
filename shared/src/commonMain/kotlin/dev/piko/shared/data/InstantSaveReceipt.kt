package dev.piko.shared.data

import dev.piko.shared.i18n.enCount
import dev.piko.shared.i18n.tr

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
        add(tr("已确认保存 ${confirmed.size} 个文件", "Confirmed saved: ${enCount(confirmed.size, "file")}", "${confirmed.size} ファイルの保存を確認しました", "파일 ${confirmed.size}개 저장 확인"))
        if (failed.isNotEmpty()) add(tr("${failed.size} 个未保存", "${enCount(failed.size, "file")} not saved", "${failed.size} 件未保存", "${failed.size}개 미저장"))
        if (notAttempted.isNotEmpty()) add(tr("${notAttempted.size} 个未执行", "${enCount(notAttempted.size, "file")} not attempted", "${notAttempted.size} 件未実行", "${notAttempted.size}개 미실행"))
        if (unconfirmed.isNotEmpty()) add(tr("${unconfirmed.size} 个结果待确认", "${enCount(unconfirmed.size, "result")} unconfirmed", "${unconfirmed.size} 件の結果が未確認", "결과 ${unconfirmed.size}개 미확인"))
        if (unconfirmedFolder != null) add(tr("目录创建结果待确认", "Folder creation unconfirmed", "フォルダーの作成結果は未確認です", "폴더 생성 결과 미확인"))
        if (namingUnconfirmed.isNotEmpty()) add(tr("${namingUnconfirmed.size} 个已保存文件的名称待确认", "Names of ${enCount(namingUnconfirmed.size, "saved file")} unconfirmed", "保存済み ${namingUnconfirmed.size} ファイルの名前が未確認", "저장된 파일 ${namingUnconfirmed.size}개의 이름 미확인"))
    }.joinToString(tr("，", "; ", "、", ", "))
}

class InstantSaveIncomplete(val receipt: InstantSaveReceipt, cause: Throwable) : Exception(receipt.message, cause)
