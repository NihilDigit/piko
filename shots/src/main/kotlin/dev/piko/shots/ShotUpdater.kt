package dev.piko.shots

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.ui.platform.PikoPlatform
import dev.piko.update.AppUpdateService
import dev.piko.update.AvailableUpdate
import dev.piko.update.UpdateStatus
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** 截图里的新版本。真实更新器走 GitHub，用的是自建的 HttpClient，假服务端的拦截器接不到。 */
class ShotUpdate(override val canInstallInApp: Boolean = true) : AvailableUpdate {
    override val version = "1.3.0"
    override val notes = """
        信息流支持横屏，归档条目可直接播放。

        ## 修复
        - 修复窄窗口下地址栏被窗口按钮遮住的问题
        - 修复部分 MKV 字幕不显示的问题

        ## 变化
        - 传输页按进行中、需要处理、已完成分段显示
    """.trimIndent()
    override val pageUrl = "https://github.com/NihilDigit/piko/releases/tag/v1.3.0"
    override val downloadSize = 48L shl 20
}

/**
 * 假的更新服务：开屏检查即发现 [ShotUpdate]，状态由 [initial] 定。点「下载并安装」后停在下载中，
 * 取消时退回可更新，与真实实现一致。
 */
class ShotUpdater(private val update: ShotUpdate, initial: (ShotUpdate) -> UpdateStatus, private val onStartup: Boolean) : AppUpdateService {
    override var status: UpdateStatus by mutableStateOf(initial(update))
    override val messages: SharedFlow<String> = MutableSharedFlow()
    override var startupUpdate: AvailableUpdate? by mutableStateOf(null)
        private set

    override suspend fun check(silent: Boolean) = Unit

    override suspend fun checkOnStartup(isIgnored: suspend (version: String) -> Boolean) {
        if (onStartup) startupUpdate = update
    }

    override fun dismissStartupUpdate() {
        startupUpdate = null
    }

    override suspend fun downloadAndInstall(update: AvailableUpdate) {
        try {
            status = UpdateStatus.Downloading(update, 0.42f)
            awaitCancellation()
        } finally {
            status = UpdateStatus.Available(update)
        }
    }
}

/** 换上 [updater] 的平台。版本号写成发布版的样子，对话框头部才有「当前版本 X」；不开浏览器。 */
class UpdateShotPlatform(base: PikoPlatform, override val updater: AppUpdateService) : PikoPlatform by base {
    override val appVersion = "1.2.1"
    override fun openUrl(url: String) = Unit
}
