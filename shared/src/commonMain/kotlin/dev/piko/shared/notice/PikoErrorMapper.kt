package dev.piko.shared.notice

import io.github.nihildigit.pikpak.InstantContentUnavailableException
import dev.piko.shared.i18n.tr
import io.github.nihildigit.pikpak.PikPakException
import kotlinx.io.IOException

/** User-facing reasons; raw server descriptions belong in the diagnostic log. */
object PikoErrorMapper {
    val invalidArchive: String get() = tr(
        "无法识别压缩包格式，文件可能已损坏或为分卷压缩包",
        "Unrecognized archive format; the file may be corrupted or a multi-volume archive",
        "圧縮ファイルの形式を認識できません。破損しているか分割圧縮の可能性があります",
        "압축 파일 형식을 인식할 수 없습니다. 파일이 손상되었거나 분할 압축일 수 있습니다",
        "無法識別壓縮檔格式，檔案可能已損毀或為分割壓縮檔",
    )

    fun reason(code: String?): String? = when (code) {
        "NEED_MORE_QUOTA" -> tr("解压额度不足", "Extraction quota exceeded", "解凍の利用枠が不足しています", "압축 해제 할당량이 부족합니다", "解壓縮額度不足")
        "INVALID_FILE_FORMAT" -> invalidArchive
        "file_duplicated_name" -> tr("目标位置已有同名文件", "A file with the same name already exists at the destination", "保存先に同じ名前のファイルがあります", "대상 위치에 같은 이름의 파일이 있습니다", "目標位置已有同名檔案")
        "file_restore_own" -> tr("无法转存自己发布的分享", "You cannot save your own share link", "自分の共有リンクは保存できません", "자신의 공유 링크는 저장할 수 없습니다", "無法轉存自己發布的分享")
        "file_in_recycle_bin" -> tr("文件在回收站中", "The item is in the trash", "項目はゴミ箱にあります", "항목이 휴지통에 있습니다", "檔案位於資源回收筒中")
        "file_name_too_long" -> tr("文件名过长", "The file name is too long", "ファイル名が長すぎます", "파일 이름이 너무 깁니다", "檔案名稱過長")
        "space_not_enough" -> tr("网盘空间不足", "Insufficient cloud storage space", "クラウドストレージの空き容量が不足しています", "클라우드 저장 공간이 부족합니다", "雲端硬碟空間不足")
        else -> null
    }

    fun message(error: Throwable): String = when {
        error is InstantContentUnavailableException -> tr("云端未收录此文件，请使用离线下载", "File not indexed in the cloud. Use cloud download", "クラウドに未収録のファイルです。クラウドダウンロードを利用してください", "클라우드에 등록되지 않은 파일입니다. 클라우드 다운로드를 이용해 주세요", "雲端未收錄此檔案，請使用離線下載")
        error is PikPakException -> reason(error.errorMessage) ?: tr("操作失败，请重试", "Operation failed. Please try again", "操作に失敗しました。もう一度お試しください", "작업에 실패했습니다. 다시 시도해 주세요", "操作失敗，請重試")
        error is IOException -> tr("网络异常", "Network error", "ネットワークエラー", "네트워크 오류", "網路異常")
        else -> tr("未知错误", "Unknown error", "不明なエラー", "알 수 없는 오류", "未知錯誤")
    }

    fun archive(error: Throwable): String = when (error) {
        is PikPakException -> reason(error.errorMessage) ?: message(error)
        is IllegalStateException -> error.message ?: tr("未知错误", "Unknown error", "不明なエラー", "알 수 없는 오류", "未知錯誤")
        else -> tr("网络异常", "Network error", "ネットワークエラー", "네트워크 오류", "網路異常")
    }

    fun archiveReason(code: String?, description: String): String = reason(code) ?: tr(
        description.ifEmpty { code ?: "服务端未返回具体原因" },
        "No details returned by server",
        "サーバーから詳細が返されませんでした",
        "서버에서 세부 정보를 반환하지 않았습니다",
        "伺服器未傳回具體原因",
    )

    fun failure(action: String, error: Throwable): String {
        if (error is InstantContentUnavailableException) return message(error)
        val reason = (error as? PikPakException)?.let { reason(it.errorMessage) }
        return when {
            reason != null -> tr("${action}失败：$reason", "$action failed: $reason", "$action に失敗しました：$reason", "$action 실패: $reason", "${action}失敗：$reason")
            error is IOException -> tr("${action}失败：网络连接中断，请重试", "$action failed: network connection interrupted. Please try again", "$action に失敗しました。接続が切れました。もう一度お試しください", "$action 실패: 네트워크 연결이 끊어졌습니다. 다시 시도해 주세요", "${action}失敗：網路連線中斷，請重試")
            else -> tr("${action}失败，请重试", "$action failed. Please try again", "$action に失敗しました。もう一度お試しください", "$action 실패. 다시 시도해 주세요", "${action}失敗，請重試")
        }
    }
}
