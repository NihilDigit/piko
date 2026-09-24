package dev.piko.ui

import androidx.compose.runtime.staticCompositionLocalOf
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.repository.DriveRepository
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.OfflinePackTracker
import dev.piko.shared.data.PikoAccountRepository
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.data.PreviewTempFolder
import dev.piko.shared.data.TaskRepository
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.state.InstantSession
import dev.piko.shared.state.InstantSheetState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 界面用到的进程级对象。各端在入口按自己的偏好、会话存储与下载实现拼好一份，
 * 经 [LocalPikoServices] 交给界面。这些对象的生命周期是进程，不是某个屏幕。
 */
class PikoServices(
    val preferences: PikoUserPreferences,
    val clientManager: PikoClientManager,
    val downloadManager: PikoDownloadCoordinator,
    val mediaRepository: PikoMediaRepository,
    val driveRepository: DriveRepository = DriveRepository(clientManager, preferences),
    val accountRepository: PikoAccountRepository = PikoAccountRepository(clientManager, preferences),
    val instantMagnetRepository: InstantMagnetRepository = InstantMagnetRepository(clientManager),
    val taskRepository: TaskRepository = TaskRepository(clientManager, driveRepository),
) {
    // 不随任何界面结束的后台工作：离线任务的跟踪与 Piko-Temp 的清理
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val previewTempFolder = PreviewTempFolder(driveRepository, instantMagnetRepository, backgroundScope)

    val offlinePacks = OfflinePackTracker(instantMagnetRepository, driveRepository, preferences)

    val instantSession: InstantSession by lazy {
        InstantSession(
            newScope = { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) },
            newState = { scope, magnet ->
                InstantSheetState(
                    instantMagnetRepository,
                    driveRepository,
                    preferences,
                    previewTempFolder,
                    offlinePacks,
                    scope,
                    magnet,
                )
            },
        )
    }

    init {
        backgroundScope.launch {
            val cleanedAccounts = mutableSetOf<String>()
            clientManager.currentClient.collectLatest { client ->
                if (client == null) return@collectLatest
                // 上次进程被杀时面板来不及清 Piko-Temp，登录后补上。每个账号只清一次：断线重连
                // 也会换一个新的 client，那时面板可能正开着，预览的文件还要用
                if (cleanedAccounts.add(client.account)) previewTempFolder.clear()
                // 换号或退出登录时 collectLatest 取消它，换成新账号的记录重来
                offlinePacks.run(client.account)
            }
        }
    }
}

val LocalPikoServices = staticCompositionLocalOf<PikoServices> {
    error("PikoServices 未提供，入口要用 PikoApp 包一层")
}
