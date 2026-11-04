package dev.piko.ui.ios

import dev.piko.shared.auth.createIosSessionStore
import dev.piko.shared.data.FilePikoCacheStore
import dev.piko.shared.data.PikoCacheStore
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.data.IosPikoTreeDatabaseFactory
import dev.piko.shared.download.IosPikoSegmentDownloader
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.download.createIosDownloadStorage
import dev.piko.shared.media.IosClipCache
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.network.createIosAuxiliaryHttpClient
import dev.piko.shared.preferences.createIosPreferences
import dev.piko.shared.scrape.MetaTubeService
import dev.piko.shared.storage.IosAppDirectories
import dev.piko.shared.storage.SandboxPaths
import dev.piko.shared.sync.PortableSyncCipher
import dev.piko.shared.upload.createIosUploadSources
import dev.piko.ui.PikoServices
import kotlinx.coroutines.CoroutineScope

/**
 * iOS 入口拼一份 [PikoServices]，与桌面 `createServices` 同构：会话存 Keychain，切片缓存落
 * 应用私有 caches，下载目录固定在本应用沙箱里，刮削走 darwin 的 HTTP 客户端。
 *
 * 同步加密用 commonMain 的 [PortableSyncCipher]：iOS 没有 JDK 的 SecretKeyFactory，但 PBKDF2 与
 * AES-GCM 都是同一份可移植代码，密文格式与桌面的 JvmSyncCipher 逐字节一致（桌面侧有对拍测试）。
 * 片段截取与转封装由 [IosPikoSegmentDownloader] 走 AVFoundation 的直通导出，已接上。
 */
fun createIosServices(
    directories: IosAppDirectories,
    appScope: CoroutineScope,
    /** Keychain 的 service 名，包名变形，跨版本必须稳定，不然读不回已存的会话。 */
    keychainService: String,
): PikoServices {
    val preferences = createIosPreferences(directories, keychainService)
    val clientManager = PikoClientManager(createIosSessionStore(keychainService), appScope)
    val mediaRepository = PikoMediaRepository(
        clientManager,
        preferences,
        clipCache = IosClipCache(SandboxPaths.resolveDirectory(directories.caches, "clip-cache")),
    )
    val cacheStore: PikoCacheStore = FilePikoCacheStore(SandboxPaths.resolveDirectory(directories.caches, "cache"))
    return PikoServices(
        platformPreferences = preferences,
        clientManager = clientManager,
        mediaRepository = mediaRepository,
        downloadManager = PikoDownloadCoordinator(
            clientManager,
            preferences,
            createIosDownloadStorage(directories),
            appScope,
            segmentDownloader = IosPikoSegmentDownloader(),
            mediaRepository = mediaRepository,
            cacheStore = cacheStore,
        ),
        uploadSources = createIosUploadSources(directories),
        cacheStore = cacheStore,
        treeDatabaseFactory = IosPikoTreeDatabaseFactory(SandboxPaths.resolveDirectory(directories.caches, "drive-tree")),
        syncCipher = PortableSyncCipher(),
        metaTube = MetaTubeService(preferences, cacheStore, appScope) { createIosAuxiliaryHttpClient() },
    )
}
