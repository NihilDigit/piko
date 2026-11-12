package dev.piko.shared.sync

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.data.EncryptedSyncKey
import dev.piko.shared.data.PikoCacheStore
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.TaskPhase
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** 同步途中活动账号已切换时抛出的受控信号，不继承 [CancellationException]，避免误取消外部调用方协程。 */
internal class SyncAccountSwitchedException : RuntimeException("Active account changed during sync")

/**
 * 同步到网盘的一项偏好。值一律存成字符串，[read] 与 [write] 负责换算。
 * 加一项只在 [SyncedSettings] 里加一行；窗口大小、下载目录、代理这类每台设备各自的不要加。
 */
class SyncedSetting(
    val key: String,
    val read: (PikoUserPreferences) -> Flow<String>,
    val write: suspend (PikoUserPreferences, String) -> Unit,
)

private fun bool(key: String, read: (PikoUserPreferences) -> Flow<Boolean>, write: suspend PikoUserPreferences.(Boolean) -> Unit) =
    SyncedSetting(key, { read(it).map(Boolean::toString) }, { prefs, value -> value.toBooleanStrictOrNull()?.let { prefs.write(it) } })

val SyncedSettings: List<SyncedSetting> = listOf(
    // 关掉自动检查是不想被打扰，换一台设备也一样
    bool("autoCheckUpdates", { it.autoCheckUpdatesFlow }) { setAutoCheckUpdates(it) },
    bool("heuristicFilter", { it.heuristicFilterFlow }) { setHeuristicFilterEnabled(it) },
    bool("nameParsing", { it.nameParsingFlow }) { setNameParsingEnabled(it) },
    bool("bundleSubtitles", { it.bundleSubtitlesFlow }) { setBundleSubtitlesEnabled(it) },
    bool("autoCleanNames", { it.autoCleanNamesFlow }) { setAutoCleanNamesEnabled(it) },
    bool("syncPlayHistory", { it.syncPlayHistoryFlow }) { setSyncPlayHistoryEnabled(it) },
    // 批量重命名默认使用正则文本还是规则积木属于用户的操作习惯，随账号跨设备同步
    bool("renameRegexTextMode", { it.renameRegexTextModeFlow }) { setRenameRegexTextMode(it) },
    // 空串是「跟随系统」。设置接口没有写回跟随系统的入口，远端的空值不往本机写
    SyncedSetting("themeMode", { prefs -> prefs.themeModeFlow.map { it.orEmpty() } }, { prefs, value -> if (value.isNotEmpty()) prefs.setThemeMode(value) }),
    SyncedSetting("themeSeed", { prefs -> prefs.themeSeedFlow.map { it.orEmpty() } }, { prefs, value -> prefs.setThemeSeed(value.ifEmpty { null }) }),
    SyncedSetting("driveViewMode", { it.driveViewModeFlow }, { prefs, value -> prefs.setDriveViewMode(value) }),
    // 最近移动到的目录：换一台设备也用得上
    SyncedSetting("recentMoveTargets", { it.recentMoveTargetsFlow }, { prefs, value -> prefs.saveRecentMoveTargets(value) }),
    // 快速访问是 Piko 自己的，PikPak 没有这一项，只能靠这里带到别的设备
    SyncedSetting("pinnedFolders", { it.pinnedFoldersFlow }, { prefs, value -> prefs.savePinnedFolders(value) }),
    bool("foldersFirst", { it.foldersFirstFlow }) { setFoldersFirst(it) },
    bool("perFolderSort", { it.perFolderSortFlow }) { setPerFolderSort(it) },
    SyncedSetting("folderSortOrders", { it.folderSortOrdersFlow }, { prefs, value -> prefs.saveFolderSortOrders(value) }),
    SyncedSetting(
        "playerSeekStepSeconds",
        { prefs -> prefs.playerSeekStepSecondsFlow.map(Int::toString) },
        { prefs, value -> value.toIntOrNull()?.let { prefs.setPlayerSeekStepSeconds(it) } },
    ),
    SyncedSetting(
        "playerBoostSpeed",
        { prefs -> prefs.playerBoostSpeedFlow.map(Float::toString) },
        { prefs, value -> value.toFloatOrNull()?.let { prefs.setPlayerBoostSpeed(it) } },
    ),
    bool("clipAutoExtend", { it.clipAutoExtendFlow }) { setClipAutoExtend(it) },
    bool("galleryShowImages", { it.galleryShowImagesFlow }) { setGalleryShowImages(it) },
)

/**
 * 不再同步、要从网盘上的同步文件里抹掉的项。别的未知项照旧原样带着：那是更新的版本加的，不能被旧版本删掉。
 *
 * archivePasswords：1.1.0 把解压密码明文同步进网盘。之后本机存进机密存储，跨设备改由 ArchivePasswordSync
 * 以账号密码派生的密钥加密后单独存一份。
 */
private val RetiredSettingKeys = setOf("archivePasswords", "spoilerBlur")

/**
 * 把 [SyncedSettings] 同步到网盘根目录下的 `.piko` 文件夹（[DriveSettingsStore]），换一台设备登录同一个账号，设置跟着过来。
 *
 * 合并按项进行，每项带上最后修改的时刻，谁新用谁的：两台设备各改了一项，两项都留下，不是整份文件后写的覆盖先写的。
 * 本机这边每项上次同步时的值与时刻存在缓存目录里（按账号分），离线时改的项下次同步时以改动时刻参与比较。
 *
 * 什么时候同步：登录或换号后拉一次；本机改了设置，停几秒再合并推上去。[enabled] 关掉时什么也不做。
 */
class PikoSettingsSync(
    private val clients: PikoClientProvider,
    private val remote: RemoteSettingsStore,
    private val preferences: PikoUserPreferences,
    private val cacheStore: PikoCacheStore?,
    private val scope: CoroutineScope,
    private val enabled: Flow<Boolean>,
    private val settings: List<SyncedSetting> = SyncedSettings,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    constructor(
        clients: PikoClientProvider,
        driveRepo: PikoDriveRepository,
        preferences: PikoUserPreferences,
        cacheStore: PikoCacheStore?,
        scope: CoroutineScope,
        enabled: Flow<Boolean>,
    ) : this(clients, DriveSettingsStore(clients, driveRepo, tag = TAG), preferences, cacheStore, scope, enabled)

    enum class Status { IDLE, SYNCING, SYNCED, FAILED }

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** 上次同步成功的时刻，毫秒；还没成功过为 null。 */
    private val _lastSynced = MutableStateFlow<Long?>(null)
    val lastSynced: StateFlow<Long?> = _lastSynced.asStateFlow()

    private val lock = Mutex()

    private val json = Json { ignoreUnknownKeys = true }

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            combine(clients.currentClient, enabled) { client, on -> client?.account.takeIf { on } }
                .distinctUntilChanged()
                .collectLatest { account ->
                    if (account == null) return@collectLatest
                    syncNow()
                    // 本机的改动：头一个值是眼前的状态，不算改动；连着改几项等停下来再一起推
                    combine(settings.map { it.read(preferences) }) { it.toList() }
                        .distinctUntilChanged()
                        .drop(1)
                        .debounce(PUSH_DELAY_MS)
                        .collect { syncNow() }
                }
        }
    }

    /** 立刻同步一次，设置页的「立即同步」也走这里。 */
    suspend fun syncNow(): Boolean = lock.withLock {
        val account = clients.currentClient.value?.account ?: return@withLock false
        _status.value = Status.SYNCING
        try {
            sync(account)
            _status.value = Status.SYNCED
            _lastSynced.value = now()
            true
        } catch (e: CancellationException) {
            _status.value = Status.IDLE
            throw e
        } catch (_: SyncAccountSwitchedException) {
            PikoLog.d(TAG, "Skipped sync: active account changed")
            _status.value = Status.IDLE
            false
        } catch (e: Throwable) {
            PikoLog.w(TAG, "Settings sync failed", e)
            _status.value = Status.FAILED
            false
        }
    }

    private suspend fun sync(account: String) {
        val stamp = now()
        val local = settings.associate { it.key to it.read(preferences).first() }
        val known = loadState(account)
        // 本机与上次同步时不同的，是这之后在本机改的，算作此刻改的。这台设备从没同步过的项算作最旧：
        // 新装的设备一身默认值，不能拿它们盖掉另一台设备上调好的设置
        val mine = local.mapValues { (key, value) ->
            val last = known[key]
            when {
                last == null -> Entry(value, 0L)
                last.value == value -> last
                else -> Entry(value, stamp)
            }
        }
        val remoteText = remote.read(account)
        val remoteDoc = if (remoteText != null) {
            val parsed = runCatching { json.decodeFromString(Document.serializer(), remoteText) }
                .onFailure {
                    PikoLog.w(TAG, "Remote settings document is malformed (${it::class.simpleName}); skipping sync to preserve remote file")
                }
                .getOrNull() ?: return
            if (parsed.version > VERSION) {
                PikoLog.w(TAG, "Remote settings version ${parsed.version} is newer than supported $VERSION; skipping sync")
                return
            }
            parsed
        } else {
            null
        }
        val remoteValues = remoteDoc?.values.orEmpty()

        // 远端带着已停用的项时，合并结果与远端不同，下面随即写一份不含它的新文件，旧文件随之删掉
        val merged = (mine.keys + remoteValues.keys - RetiredSettingKeys).associateWith { key ->
            val a = mine[key]
            val b = remoteValues[key]
            when {
                a == null -> b!!
                b == null -> a
                // 同一时刻以远端为准，两台设备才会收敛到同一份
                b.updatedAt >= a.updatedAt -> b
                else -> a
            }
        }
        // 远端更新的写进本机
        val pulled = mutableListOf<String>()
        for (setting in settings) {
            val entry = merged[setting.key] ?: continue
            if (entry.value != local[setting.key]) {
                setting.write(preferences, entry.value)
                pulled += setting.key
            }
        }
        saveState(account, merged.filterKeys { key -> settings.any { it.key == key } })
        val pushed = merged != remoteValues
        if (pushed) remote.write(account, json.encodeToString(Document.serializer(), Document(VERSION, merged)), stamp)
        // 只记项名，不记值：值里有快速访问的文件夹 ID 与主题这类无关排查的内容
        PikoLog.i(
            TAG,
            "Settings synced: remote=${if (remoteText == null) "none" else "${remoteValues.size} keys"}, " +
                "localModified=${mine.count { it.value.updatedAt == stamp }}, " +
                "pulled=${pulled.size}${if (pulled.isEmpty()) "" else " $pulled"}, " +
                "pushed=${if (pushed) "${merged.size} keys" else "no"}, elapsed=${now() - stamp} ms",
        )
    }

    private suspend fun loadState(account: String): Map<String, Entry> {
        val text = cacheStore?.read(stateKey(account)) ?: return emptyMap()
        return runCatching { json.decodeFromString(StateSerializer, text) }
            .logFailure(TAG, "Failed to parse local settings sync ledger; treating as unsynced")
            .getOrDefault(emptyMap())
    }

    private suspend fun saveState(account: String, state: Map<String, Entry>) {
        cacheStore?.write(stateKey(account), json.encodeToString(StateSerializer, state))
    }

    private fun stateKey(account: String) = "settings-sync-" + account.replace(UNSAFE_KEY_CHARS, "_") + ".json"

    @Serializable
    data class Entry(val value: String, val updatedAt: Long)

    @Serializable
    private data class Document(val version: Int, val values: Map<String, Entry>)

    companion object {
        /** 网盘根目录下放同步文件的文件夹，Piko 自己的列表里不显示它，见 [isSyncFolder]。 */
        const val FOLDER_NAME = ".piko"

        /**
         * [file] 是不是 [parentId] 里放同步文件的文件夹。只认网盘根目录里的：别处同名的是用户自己的文件夹，照常列出。
         * 网盘页、目录选择器、命令面板都靠它把这个文件夹藏起来，规则只写这一处。
         */
        fun isSyncFolder(file: FileStat, parentId: String): Boolean =
            parentId.isEmpty() && file.isFolder && file.name == FOLDER_NAME
        private const val VERSION = 1
        private const val PUSH_DELAY_MS = 3_000L
        private const val TAG = "SettingsSync"
        private val UNSAFE_KEY_CHARS = Regex("""[^A-Za-z0-9._@-]""")
        private val StateSerializer = MapSerializer(String.serializer(), Entry.serializer())
    }
}

/** 提供指定账号的加密同步密钥；与旧版 `suspend (String) -> String?` 构造器区分 JVM 擦除签名。 */
fun interface SyncKeyProvider {
    suspend fun keyFor(account: String): EncryptedSyncKey?
}

/** 加密同步（解压密码与 MetaTube 配置）的当前密钥与远端密文状态。 */
enum class EncryptedSyncStatus {
    /** 尚未同步或已退出。 */
    Idle,
    /** 密钥可用，远端尚无密文或已正常解密合并。 */
    Unlocked,
    /** 当前账号无已保存密码且未配置独立同步口令（如纯 Google 登录尚未设置同步口令）。 */
    LockedNoKey,
    /** 远端存在加密同步文件，但当前密钥无法解开（改过账号密码或使用了独立同步口令）；绝不自动覆盖远端。 */
    LockedKeyMismatch,
    /** 远端同步文件损坏或格式版本高于当前客户端支持上限。 */
    CorruptOrUnsupported,
}

/** 更改或清除独立加密同步口令后单次校验/无损轮换的结果。 */
enum class EncryptedSyncApplyOutcome {
    /** 已成功解密、合并或完成无损重加密。 */
    UNLOCKED,
    /** 远端存在无法用新口令或旧密钥解密的密文（绝不覆盖远端）。 */
    KEY_MISMATCH,
    /** 网络暂不可达、钥匙串锁定或当前无可用密钥，本轮延后。 */
    DEFERRED,
}

/** 同步文件放在哪。网盘里的实现是 [DriveSettingsStore]，测试换成内存里的。 */
interface RemoteSettingsStore {
    /** 最新的一份，没有时为 null。 */
    suspend fun read(account: String): String?

    /** 写一份新的，[stamp] 是这一次同步的时刻。 */
    suspend fun write(account: String, text: String, stamp: Long)

    /** 最新已完成同步文件的时间戳（毫秒），不存在时为 0L；用于重置时单调递增压过时钟偏差文件。 */
    suspend fun latestStamp(account: String): Long = 0L

    /** 显式重置远端加密同步前保存一份旧密文备份（不纳入常规清理前缀）；未实现的存储必须拒绝重置。 */
    suspend fun writeBackup(account: String, text: String, stamp: Long) {
        throw UnsupportedOperationException("RemoteSettingsStore.writeBackup is required before resetting encrypted sync")
    }
}

/**
 * 网盘 `.piko/<filePrefix><毫秒时间戳>.json` 同步文件存储，供偏好设置（`settings-`）、压缩包密码（`archive-passwords-`）、
 * MetaTube 配置（`metatube-`）、固定标签（`pinned-tabs-`）、来源账本（`sources-`）、归档星标（`vault-stars-`）与归档目录树
 * （`vault-trees-`）各自按独立前缀存取。
 *
 * PikPak 上传同名文件会自动重命名而不覆盖，因此每次写入新时间戳文件后再清理较早文件，读取时始终选取已完成上传且时间戳最大的一份。
 */
class DriveSettingsStore(
    private val clients: PikoClientProvider,
    private val driveRepo: PikoDriveRepository,
    private val filePrefix: String = "settings-",
    private val tag: String = DEFAULT_TAG,
) : RemoteSettingsStore {
    private data class CachedRemoteFile(val fileId: String, val stamp: Long, val text: String)

    // 各账号的 .piko 文件夹 ID：找它要列整个根目录，每次同步都找一遍太贵
    private val folderIds = HashMap<String, String>()
    private val lastRead = HashMap<String, CachedRemoteFile>()
    private val cacheLock = Mutex()

    override suspend fun read(account: String): String? {
        val (_, entries) = folderAndEntriesOf(account)
        // 上传分创建元数据与传输内容两步；进程若在两步之间中断会留下 phase != COMPLETE 的空壳文件且无下载直链，
        // 必须过滤掉未完成条目，避免因其时间戳最新而阻塞后续读取与清理。
        val file = entries
            .filter { it.isSettingsFile() && it.phase == TaskPhase.COMPLETE }
            .maxByOrNull { it.stamp() }
            ?: return null
        val stamp = file.stamp()
        val cached = cacheLock.withLock {
            lastRead[account]?.takeIf { it.fileId == file.id && it.stamp == stamp }?.text
        }
        if (cached != null) {
            requireCurrent(account)
            return cached
        }
        val text = driveRepo.readBytes(file.id).getOrThrow().decodeToString()
        // 读回来的要并进本机的那一份，那时本机已换成别的账号的，就并错了地方
        requireCurrent(account)
        cacheLock.withLock {
            lastRead[account] = CachedRemoteFile(file.id, stamp, text)
        }
        return text
    }

    override suspend fun write(account: String, text: String, stamp: Long) {
        val folder = folderOf(account)
        val name = "$filePrefix$stamp$FILE_SUFFIX"
        requireCurrent(account)
        val createdId = driveRepo.uploadBytes(folder, name, text.encodeToByteArray()).getOrThrow()
        cacheLock.withLock {
            lastRead[account] = CachedRemoteFile(createdId, stamp, text)
        }
        // 只删比这一份旧的：另一台设备同时在同步时，它更新的那份（可能还在上传）留给它自己收拾
        val stale = driveRepo.listAllFiles(folder)
            .logFailure(tag, "Failed to list .piko after writing $name; stale $filePrefix files will be cleaned up next run")
            .getOrNull().orEmpty().filter { it.isSettingsFile() && it.stamp() < stamp }
        if (stale.isNotEmpty()) {
            requireCurrent(account)
            driveRepo.delete(stale.map { it.id })
                .logFailure(tag, "Failed to delete ${stale.size} stale $filePrefix files")
        }
    }

    override suspend fun latestStamp(account: String): Long {
        val (_, entries) = folderAndEntriesOf(account)
        requireCurrent(account)
        return entries
            .filter { it.isSettingsFile() && it.phase == TaskPhase.COMPLETE }
            .maxOfOrNull { it.stamp() } ?: 0L
    }

    override suspend fun writeBackup(account: String, text: String, stamp: Long) {
        val folder = folderOf(account)
        val name = "backup-$filePrefix$stamp$FILE_SUFFIX"
        requireCurrent(account)
        driveRepo.uploadBytes(folder, name, text.encodeToByteArray()).getOrThrow()
        PikoLog.i(tag, "Saved encrypted sync backup before reset: $name")
    }

    // 记着的文件夹可能已被删掉或移走：列不出来就忘掉它，重新找一次。多个同步器在登录后并发启动，
    // 查找与创建共用静态 folderLock，防止并发在根目录建出重复的 .piko 文件夹。
    private suspend fun folderAndEntriesOf(account: String): Pair<String, List<FileStat>> = folderLock.withLock {
        requireCurrent(account)
        folderIds[account]?.let { known ->
            val listed = driveRepo.listAllFiles(known)
                .logFailure(tag, "Failed to list cached .piko folder $known; re-resolving from root")
            if (listed.isSuccess) return@withLock known to listed.getOrThrow()
            folderIds.remove(account)
        }
        val root = driveRepo.listAllFiles("").getOrThrow()
        requireCurrent(account)
        val existingId = root.firstOrNull { it.isFolder && it.name == PikoSettingsSync.FOLDER_NAME && !it.trashed }?.id
        if (existingId != null) {
            folderIds[account] = existingId
            val entries = driveRepo.listAllFiles(existingId).getOrThrow()
            return@withLock existingId to entries
        }
        val created = driveRepo.createFolder("", PikoSettingsSync.FOLDER_NAME).getOrThrow()
            .also { PikoLog.i(tag, "Created missing .piko sync folder: $it") }
        folderIds[account] = created
        created to emptyList()
    }

    private suspend fun folderOf(account: String): String = folderAndEntriesOf(account).first

    /**
     * 网盘读写走的是仓库眼前的活动账号而非 [account] 参数：每步操作前后均须核对当前账号，
     * 一旦切换账号立即抛出 [SyncAccountSwitchedException] 中止本轮同步，防止跨账号污染 `.piko` 数据。
     */
    private fun requireCurrent(account: String) {
        if (clients.currentClient.value?.account != account) {
            throw SyncAccountSwitchedException()
        }
    }

    // 前缀后面紧跟时间戳：各同步器的文件名前缀互不包含，不会误认彼此的同步文件
    private fun FileStat.isSettingsFile() = !isFolder && name.startsWith(filePrefix) && name.endsWith(FILE_SUFFIX) && stampOrNull() != null

    private fun FileStat.stampOrNull() = name.removePrefix(filePrefix).removeSuffix(FILE_SUFFIX).toLongOrNull()

    private fun FileStat.stamp() = stampOrNull() ?: 0L

    private companion object {
        val folderLock = Mutex()
        const val FILE_SUFFIX = ".json"
        const val DEFAULT_TAG = "SettingsSync"
    }
}
