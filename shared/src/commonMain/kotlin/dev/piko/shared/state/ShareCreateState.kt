package dev.piko.shared.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import io.github.nihildigit.pikpak.CreatedShare
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class SharePassCodeMode(val label: String) {
    None("无"),
    Random("随机"),
    Custom("自定义"),
}

/**
 * 创建分享：先选提取码与有效期，点创建才请求，成功后 [created] 非空，界面换成结果。
 *
 * 结果出来后不再给创建按钮：同一组条目再创建一次得到的是另一条链接，前一条仍然有效，
 * 用户多半以为是同一条。
 */
class ShareCreateState(
    private val driveRepo: PikoDriveRepository,
    private val scope: CoroutineScope,
    val files: List<FileStat>,
) {
    // 默认要提取码：分享的多是整个文件夹，公开链接被转出去就收不回来
    var passCodeMode by mutableStateOf(SharePassCodeMode.Random)
    var customPassCode by mutableStateOf("")

    /** 天数，-1 为永久。取值见 [EXPIRATION_CHOICES]。 */
    var expirationDays by mutableStateOf(-1)

    var isCreating by mutableStateOf(false)
        private set
    var created by mutableStateOf<CreatedShare?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val isCustomPassCodeValid by derivedStateOf { CUSTOM_PASS_CODE.matches(customPassCode) }

    val canCreate by derivedStateOf {
        !isCreating && created == null && (passCodeMode != SharePassCodeMode.Custom || isCustomPassCodeValid)
    }

    /** 服务端把第一项的名字作为分享标题，多项时这里补上总数。 */
    val title: String
        get() = files.first().name + if (files.size > 1) " 等 ${files.size} 项" else ""

    /** 复制出去的整段文字。 */
    val shareText: String?
        get() = created?.let { shareText(title, it.shareUrl, it.passCode) }

    fun create() {
        if (!canCreate) return
        isCreating = true
        error = null
        val passCode = when (passCodeMode) {
            SharePassCodeMode.None -> null
            SharePassCodeMode.Random -> ""
            SharePassCodeMode.Custom -> customPassCode
        }
        scope.launch {
            // 分享 ID 就是链接的一部分，公开分享凭它即可打开，不进日志
            val summary = "${files.size} 项（文件夹 ${files.count { it.isFolder }} 个），提取码${passCodeMode.label}，" +
                "有效期 ${if (expirationDays < 0) "永久" else "$expirationDays 天"}"
            driveRepo.createShare(files.map { it.id }, passCode, expirationDays)
                .logFailure(TAG, "创建分享失败：$summary，首项 ${files.first().id}")
                .onSuccess {
                    PikoLog.i(TAG, "已创建分享：$summary")
                    created = it
                }
                .onFailure { error = "创建失败，请重试" }
            isCreating = false
        }
    }

    companion object {
        private const val TAG = "Share"

        /** 与官方网页端相同的几档。 */
        val EXPIRATION_CHOICES = listOf(-1, 7, 14, 30)

        /** 官方网页端允许的自定义提取码。 */
        private val CUSTOM_PASS_CODE = Regex("[A-Za-z0-9]{4,10}")

        /**
         * 标题、链接、提取码各占一行。piko 自己的添加链接面板从中认得出链接与提取码，
         * 别的客户端粘贴进来也是同一套写法。
         */
        fun shareText(title: String, url: String, passCode: String): String = buildString {
            appendLine(title)
            append(url)
            if (passCode.isNotEmpty()) append("\n提取码：").append(passCode)
        }
    }
}
