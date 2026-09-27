package dev.piko.shared.sync

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.data.PikoCacheStore
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import io.github.nihildigit.pikpak.FileStat
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
import kotlin.time.Clock

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
    bool("spoilerBlur", { it.spoilerBlurFlow }) { setSpoilerBlurEnabled(it) },
    bool("heuristicFilter", { it.heuristicFilterFlow }) { setHeuristicFilterEnabled(it) },
    bool("nameParsing", { it.nameParsingFlow }) { setNameParsingEnabled(it) },
    bool("bundleSubtitles", { it.bundleSubtitlesFlow }) { setBundleSubtitlesEnabled(it) },
    bool("autoCleanNames", { it.autoCleanNamesFlow }) { setAutoCleanNamesEnabled(it) },
    bool("syncPlayHistory", { it.syncPlayHistoryFlow }) { setSyncPlayHistoryEnabled(it) },
    // 空串是「跟随系统」。设置接口没有写回跟随系统的入口，远端的空值不往本机写
    SyncedSetting("themeMode", { prefs -> prefs.themeModeFlow.map { it.orEmpty() } }, { prefs, value -> if (value.isNotEmpty()) prefs.setThemeMode(value) }),
    SyncedSetting("themeSeed", { prefs -> prefs.themeSeedFlow.map { it.orEmpty() } }, { prefs, value -> prefs.setThemeSeed(value.ifEmpty { null }) }),
    SyncedSetting("driveViewMode", { it.driveViewModeFlow }, { prefs, value -> prefs.setDriveViewMode(value) }),
    // 解压成功过的密码与最近移动到的目录：换一台设备也用得上
    SyncedSetting("archivePasswords", { it.archivePasswordsFlow }, { prefs, value -> prefs.saveArchivePasswords(value) }),
    SyncedSetting("recentMoveTargets", { it.recentMoveTargetsFlow }, { prefs, value -> prefs.saveRecentMoveTargets(value) }),
)

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
    ) : this(clients, DriveSettingsStore(driveRepo), preferences, cacheStore, scope, enabled)

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
        val ok = runCatching { sync(account) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .logFailure(TAG, "设置同步失败")
            .isSuccess
        _status.value = if (ok) Status.SYNCED else Status.FAILED
        if (ok) _lastSynced.value = now()
        ok
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
        val remoteValues = remoteText?.let { json.decodeFromString(Document.serializer(), it).values }.orEmpty()

        val merged = (mine.keys + remoteValues.keys).associateWith { key ->
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
        for (setting in settings) {
            val entry = merged[setting.key] ?: continue
            if (entry.value != local[setting.key]) setting.write(preferences, entry.value)
        }
        saveState(account, merged.filterKeys { key -> settings.any { it.key == key } })
        if (merged != remoteValues) {
            remote.write(account, json.encodeToString(Document.serializer(), Document(VERSION, merged)), stamp)
            PikoLog.d(TAG, "已推送设置，${merged.size} 项")
        }
    }

    private suspend fun loadState(account: String): Map<String, Entry> {
        val text = cacheStore?.read(stateKey(account)) ?: return emptyMap()
        return runCatching { json.decodeFromString(StateSerializer, text) }.getOrDefault(emptyMap())
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
        /** 网盘根目录下放同步文件的文件夹，Piko 自己的列表里不显示它。 */
        const val FOLDER_NAME = ".piko"
        private const val VERSION = 1
        private const val PUSH_DELAY_MS = 3_000L
        private const val TAG = "SettingsSync"
        private val UNSAFE_KEY_CHARS = Regex("""[^A-Za-z0-9._@-]""")
        private val StateSerializer = MapSerializer(String.serializer(), Entry.serializer())
    }
}

/** 同步文件放在哪。网盘里的实现是 [DriveSettingsStore]，测试换成内存里的。 */
interface RemoteSettingsStore {
    /** 最新的一份，没有时为 null。 */
    suspend fun read(account: String): String?

    /** 写一份新的，[stamp] 是这一次同步的时刻。 */
    suspend fun write(account: String, text: String, stamp: Long)
}

/**
 * 网盘里的同步文件：`.piko/settings-<毫秒时间戳>.json`。PikPak 上传同名文件不覆盖，另起一个带序号的，
 * 所以每次写一个新文件、再删掉旧的；读的时候取时间戳最大的一个。删旧文件失败也无妨，下次读的仍是最新的那个。
 */
class DriveSettingsStore(private val driveRepo: PikoDriveRepository) : RemoteSettingsStore {
    // 各账号的 .piko 文件夹 ID：找它要列整个根目录，每次同步都找一遍太贵
    private val folderIds = HashMap<String, String>()

    override suspend fun read(account: String): String? {
        val file = latestFile(folderOf(account)) ?: return null
        return driveRepo.readBytes(file.id).getOrThrow().decodeToString()
    }

    override suspend fun write(account: String, text: String, stamp: Long) {
        val folder = folderOf(account)
        val name = "$FILE_PREFIX$stamp$FILE_SUFFIX"
        driveRepo.uploadBytes(folder, name, text.encodeToByteArray()).getOrThrow()
        val stale = driveRepo.listAllFiles(folder).getOrNull().orEmpty().filter { it.isSettingsFile() && it.name != name }
        if (stale.isNotEmpty()) driveRepo.delete(stale.map { it.id }).logFailure(TAG, "删除旧的设置文件失败")
    }

    // 记着的文件夹可能已被删掉或移走：列不出来就忘掉它，重新找一次
    private suspend fun folderOf(account: String): String {
        folderIds[account]?.let { known ->
            if (driveRepo.listAllFiles(known).isSuccess) return known
            folderIds.remove(account)
        }
        val root = driveRepo.listAllFiles("").getOrThrow()
        val id = root.firstOrNull { it.isFolder && it.name == PikoSettingsSync.FOLDER_NAME && !it.trashed }?.id
            ?: driveRepo.createFolder("", PikoSettingsSync.FOLDER_NAME).getOrThrow()
        folderIds[account] = id
        return id
    }

    private suspend fun latestFile(folderId: String): FileStat? =
        driveRepo.listAllFiles(folderId).getOrThrow()
            .filter { it.isSettingsFile() }
            .maxByOrNull { it.name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX).toLongOrNull() ?: 0L }

    private fun FileStat.isSettingsFile() = !isFolder && name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX)

    private companion object {
        const val FILE_PREFIX = "settings-"
        const val FILE_SUFFIX = ".json"
        const val TAG = "SettingsSync"
    }
}
