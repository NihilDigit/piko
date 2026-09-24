package dev.piko.update

import kotlinx.coroutines.flow.SharedFlow

/** 可供更新的版本。[apkUrl] 已按本机 ABI 选好。 */
data class AvailableUpdate(
    val version: String,
    val notes: String,
    val pageUrl: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val checksumsUrl: String?,
)

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Available(val update: AvailableUpdate) : UpdateStatus
    data class Downloading(val update: AvailableUpdate, val progress: Float) : UpdateStatus

    /** 已交给系统安装器，等用户确认。 */
    data class Installing(val update: AvailableUpdate) : UpdateStatus
    data class Failed(val message: String, val update: AvailableUpdate? = null) : UpdateStatus
}

/**
 * 应用内更新。只有 Android 有：APK 由 PackageInstaller 安装。桌面端的安装包走 MSI，
 * 平台不提供它时设置页不显示检查更新一行。
 */
interface AppUpdateService {
    /** 由 Compose State 支撑，设置页直接读。 */
    val status: UpdateStatus

    /** 失败原因等一次性提示。 */
    val messages: SharedFlow<String>

    /** 为 false 时只给下载页链接，例如 debug 包与 Release 包名不同、装上去是另一个应用。 */
    val canInstallInApp: Boolean

    suspend fun check(silent: Boolean = false)

    suspend fun downloadAndInstall(update: AvailableUpdate)
}
