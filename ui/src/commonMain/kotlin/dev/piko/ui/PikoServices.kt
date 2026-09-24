package dev.piko.ui

import androidx.compose.runtime.staticCompositionLocalOf
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.repository.DriveRepository
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.PikoAccountRepository
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.data.TaskRepository
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.state.InstantSession
import dev.piko.shared.state.InstantSheetState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
    val instantSession: InstantSession by lazy {
        InstantSession(
            newScope = { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) },
            newState = { scope, magnet ->
                InstantSheetState(instantMagnetRepository, driveRepository, preferences, scope, magnet)
            },
        )
    }
}

val LocalPikoServices = staticCompositionLocalOf<PikoServices> {
    error("PikoServices 未提供，入口要用 PikoApp 包一层")
}
