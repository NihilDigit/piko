package dev.piko.shared.sync

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.data.PikoCacheStore
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.TaskPhase
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
    // 关掉自动检查是不想被打扰，换一台设备也一样
    bool("autoCheckUpdates", { it.autoCheckUpdatesFlow }) { setAutoCheckUpdates(it) },
    bool("heuristicFilter", { it.heuristicFilterFlow }) { setHeuristicFilterEnabled(it) },
    bool("nameParsing", { it.nameParsingFlow }) { setNameParsingEnabled(it) },
    bool("bundleSubtitles", { it.bundleSubtitlesFlow }) { setBundleSubtitlesEnabled(it) },
    bool("autoCleanNames", { it.autoCleanNamesFlow }) { setAutoCleanNamesEnabled(it) },
    bool("syncPlayHistory", { it.syncPlayHistoryFlow }) { setSyncPlayHistoryEnabled(it) },
    // 批量重命名用积木还是写正则，看的是这个人的水平，不是这台设备
    bool("renameRegexTextMode", { it.renameRegexTextModeFlow }) { setRenameRegexTextMode(it) },
    // 空串是「跟随系统」。设置接口没有写回跟随系统的入口，远端的空值不往本机写
    SyncedSetting("themeMode", { prefs -> prefs.themeModeFlow.map { it.orEmpty() } }, { prefs, value -> if (value.isNotEmpty()) prefs.setThemeMode(value) }),
    SyncedSetting("themeSeed", { prefs -> prefs.themeSeedFlow.map { it.orEmpty() } }, { prefs, value -> prefs.setThemeSeed(value.ifEmpty { null }) }),
    SyncedSetting("driveViewMode", { it.driveViewModeFlow }, { prefs, value -> prefs.setDriveViewMode(value) }),
    // 最近移动到的目录：换一台设备也用得上
    SyncedSetting("recentMoveTargets", { it.recentMoveTargetsFlow }, { prefs, value -> prefs.saveRecentMoveTargets(value) }),
    // 快速访问是 Piko 自己的，PikPak 没有这一项，只能靠这里带到别的设备
    SyncedSetting("pinnedFolders", { it.pinnedFoldersFlow }, { prefs, value -> prefs.savePinnedFolders(value) }),
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
)

/**
 * 不再同步、要从网盘上的同步文件里抹掉的项。别的未知项照旧原样带着：那是更新的版本加的，不能被旧版本删掉。
 *
 * archivePasswords：1.1.0 把解压密码明文同步进网盘。之后本机存进机密存储，跨设备改由 ArchivePasswordSync
 * 以账号密码派生的密钥加密后单独存一份。
 */
private val RetiredSettingKeys = setOf("archivePasswords")

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
    ) : this(clients, DriveSettingsStore(clients, driveRepo), preferences, cacheStore, scope, enabled)

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
        PikoLog.i(TAG, "设置同步：远端${if (remoteText == null) "无文件" else " ${remoteValues.size} 项"}，" +
            "本机改过 ${mine.count { it.value.updatedAt == stamp }} 项，写入本机 ${pulled.size} 项${if (pulled.isEmpty()) "" else " $pulled"}，" +
            "${if (pushed) "已推送 ${merged.size} 项" else "无需推送"}，历时 ${now() - stamp} ms")
    }

    private suspend fun loadState(account: String): Map<String, Entry> {
        val text = cacheStore?.read(stateKey(account)) ?: return emptyMap()
        return runCatching { json.decodeFromString(StateSerializer, text) }
            .logFailure(TAG, "读不出本机的同步记录，按从未同步处理").getOrDefault(emptyMap())
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

/** 同步文件放在哪。网盘里的实现是 [DriveSettingsStore]，测试换成内存里的。 */
interface RemoteSettingsStore {
    /** 最新的一份，没有时为 null。 */
    suspend fun read(account: String): String?

    /** 写一份新的，[stamp] 是这一次同步的时刻。 */
    suspend fun write(account: String, text: String, stamp: Long)
}

/**
 * 网盘里的同步文件：`.piko/<filePrefix><毫秒时间戳>.json`。设置是 `settings-`，整棵归档过的文件夹是 `vault-trees-`
 * （见 VaultTreeSync），秒传文件的来源账本是 `sources-`（见 SourceLedgerSync），各管各的一份。PikPak 上传同名文件不覆盖，另起一个带序号的，
 * 所以每次写一个新文件、再删掉旧的；读的时候取时间戳最大的一个。删旧文件失败也无妨，下次读的仍是最新的那个。
 */
class DriveSettingsStore(
    private val clients: PikoClientProvider,
    private val driveRepo: PikoDriveRepository,
    private val filePrefix: String = "settings-",
) : RemoteSettingsStore {
    // 各账号的 .piko 文件夹 ID：找它要列整个根目录，每次同步都找一遍太贵
    private val folderIds = HashMap<String, String>()

    override suspend fun read(account: String): String? {
        val file = latestFile(folderOf(account)) ?: return null
        val text = driveRepo.readBytes(file.id).getOrThrow().decodeToString()
        // 读回来的要并进本机的那一份，那时本机已换成别的账号的，就并错了地方
        requireCurrent(account)
        return text
    }

    override suspend fun write(account: String, text: String, stamp: Long) {
        val folder = folderOf(account)
        val name = "$filePrefix$stamp$FILE_SUFFIX"
        requireCurrent(account)
        driveRepo.uploadBytes(folder, name, text.encodeToByteArray()).getOrThrow()
        // 只删比这一份旧的：另一台设备同时在同步时，它更新的那份（可能还在上传）留给它自己收拾
        val stale = driveRepo.listAllFiles(folder).logFailure(TAG, "写入后列出 .piko 失败，旧的 $filePrefix 文件留到下次清理")
            .getOrNull().orEmpty().filter { it.isSettingsFile() && it.stamp() < stamp }
        if (stale.isNotEmpty()) driveRepo.delete(stale.map { it.id }).logFailure(TAG, "删除旧的 $filePrefix 文件失败（${stale.size} 个）")
    }

    // 记着的文件夹可能已被删掉或移走：列不出来就忘掉它，重新找一次。找与建在全部实例共用的锁里：
    // 设置与归档树两份同步在登录后同时开始，各自没找到就各建一个 .piko
    private suspend fun folderOf(account: String): String = folderLock.withLock {
        requireCurrent(account)
        folderIds[account]?.let { known ->
            if (driveRepo.listAllFiles(known).logFailure(TAG, "列不出记着的 .piko 文件夹 $known，重新查找").isSuccess) return@withLock known
            folderIds.remove(account)
        }
        val root = driveRepo.listAllFiles("").getOrThrow()
        requireCurrent(account)
        val id = root.firstOrNull { it.isFolder && it.name == PikoSettingsSync.FOLDER_NAME && !it.trashed }?.id
            ?: driveRepo.createFolder("", PikoSettingsSync.FOLDER_NAME).getOrThrow()
                .also { PikoLog.i(TAG, "网盘根目录没有 .piko，已新建：$it") }
        folderIds[account] = id
        id
    }

    /**
     * 网盘读写走的是仓库眼前的账号，不是 [account]：同步做到一半换了号，接下来的请求就落在新账号的网盘里，
     * 记着的 .piko 列不出来，于是在新账号的根目录找到它的 .piko、记成旧账号的，把旧账号的设置、来源账本推进去。
     * 每一步之前核对一次，换了号就放弃这一轮，由新账号的同步接着做。
     */
    private fun requireCurrent(account: String) {
        check(clients.currentClient.value?.account == account) { "同步途中换了账号，放弃这一轮" }
    }

    // 上传是先建文件、再传内容，进程死在两步之间就留下一份 PENDING 的空壳，没有下载链接。
    // 它的时间戳最新，不排除掉就次次读它失败，同步走不到写新文件、删旧文件那一步，永远清不掉它
    private suspend fun latestFile(folderId: String): FileStat? =
        driveRepo.listAllFiles(folderId).getOrThrow()
            .filter { it.isSettingsFile() && it.phase == TaskPhase.COMPLETE }
            .maxByOrNull { it.stamp() }

    // 前缀后面紧跟时间戳：「settings-」不会把「vault-trees-」的文件认成自己的，反过来也一样
    private fun FileStat.isSettingsFile() = !isFolder && name.startsWith(filePrefix) && name.endsWith(FILE_SUFFIX) && stampOrNull() != null

    private fun FileStat.stampOrNull() = name.removePrefix(filePrefix).removeSuffix(FILE_SUFFIX).toLongOrNull()

    private fun FileStat.stamp() = stampOrNull() ?: 0L

    private companion object {
        val folderLock = Mutex()
        const val FILE_SUFFIX = ".json"
        const val TAG = "SettingsSync"
    }
}
