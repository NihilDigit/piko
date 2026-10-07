package dev.piko.shared.sync

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.data.ArchivePasswordEntry
import dev.piko.shared.data.ArchivePasswordVault
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.decodeArchivePasswords
import dev.piko.shared.data.encodeArchivePasswords
import dev.piko.shared.data.mergeArchivePasswords
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.time.Clock

/**
 * 把解压用过的密码（[ArchivePasswordVault]）加密后存进网盘 `.piko` 下的 `archive-passwords-<时间戳>.json`，
 * 换一台设备登录同一个账号，输过的密码照样能挑。
 *
 * 密钥由账号密码派生（PBKDF2，salt 随文件存）：每台登录过的设备都有账号密码，不必另设口令。防得住拿到这个文件
 * 却不知道账号密码的人（会话令牌泄露、文件被分享出去），防不住 PikPak 本身，它在登录时本来就看得到账号密码。
 * 改了账号密码后旧文件解不开，以本机的记录重新加密写回；只在别的设备上有的那几条随之丢掉。
 * 钥匙串一时读不出账号密码的设备，这一轮不同步。
 *
 * 1.1.0 把这张表明文放在设置同步里，那一项已停用，见 PikoSettingsSync 的 RetiredSettingKeys。
 * 合并逐个密码比时刻，见 [mergeArchivePasswords]。跟着设置同步的开关（[enabled]）。
 */
class ArchivePasswordSync(
    private val clients: PikoClientProvider,
    private val remote: RemoteSettingsStore,
    private val vault: ArchivePasswordVault,
    private val passwordOf: suspend (account: String) -> String?,
    private val cipher: SyncCipher,
    private val scope: CoroutineScope,
    private val enabled: Flow<Boolean>,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    constructor(
        clients: PikoClientManager,
        driveRepo: PikoDriveRepository,
        preferences: PikoUserPreferences,
        cipher: SyncCipher,
        scope: CoroutineScope,
        enabled: Flow<Boolean>,
    ) : this(clients, DriveSettingsStore(clients, driveRepo, FILE_PREFIX), ArchivePasswordVault(preferences), clients::savedPassword, cipher, scope, enabled)

    private val lock = Mutex()

    // 上一次派生出的密钥。派生故意算得慢，每次同步都算一遍不划算；账号密码或 salt 变了才重算
    private var cachedKey: CachedKey? = null

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            combine(clients.currentClient, enabled) { client, on -> client?.account.takeIf { on } }
                .distinctUntilChanged()
                .collectLatest { account ->
                    if (account == null) return@collectLatest
                    syncNow()
                    // 本机记下或删掉一个密码：头一个值是眼前的状态，不算改动
                    vault.entries.drop(1).distinctUntilChanged().debounce(PUSH_DELAY_MS).collect { syncNow() }
                }
        }
    }

    suspend fun syncNow(): Boolean = lock.withLock {
        val account = clients.currentClient.value?.account ?: return@withLock false
        runCatching { sync(account) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .logFailure(TAG, "压缩包密码同步失败")
            .isSuccess
    }

    private suspend fun sync(account: String) {
        val secret = passwordOf(account) ?: run {
            PikoLog.i(TAG, "读不出账号密码，这一轮不同步压缩包密码")
            return
        }
        val local = vault.current()
        val envelope = remote.read(account)?.let { text ->
            runCatching { json.decodeFromString(Envelope.serializer(), text) }.logFailure(TAG, "网盘上的压缩包密码文件格式不对，按没有处理").getOrNull()
        }
        if (envelope != null && envelope.version > VERSION) {
            // 更新的版本写的，读不懂也不能拿旧格式盖掉它
            PikoLog.i(TAG, "网盘上的压缩包密码是更新的版本写的（${envelope.version}），这一轮不同步")
            return
        }
        val remoteEntries = envelope?.let { open(it, secret) }
        if (envelope != null && remoteEntries == null) PikoLog.i(TAG, "网盘上的压缩包密码解不开（账号密码改过？），以本机的记录重写")
        val merged = mergeArchivePasswords(local, remoteEntries.orEmpty())
        if (merged != local) vault.replace(merged)
        // 只记条数，密码与它对应的压缩包都不进日志
        PikoLog.d(TAG, "压缩包密码合并：本机 ${local.size} 条，远端 ${remoteEntries?.size?.toString() ?: if (envelope == null) "无文件" else "解不开"}，" +
            "合并后 ${merged.size} 条（其中已删 ${merged.count { it.value.removed }}），本机${if (merged != local) "已更新" else "未变"}")
        if (merged != remoteEntries && (envelope != null || merged.isNotEmpty())) {
            // 解得开就沿用原来的 salt，省一次派生；解不开的换新的
            val salt = envelope?.takeIf { remoteEntries != null }?.salt?.let(Base64::decode) ?: cipher.newSalt()
            val key = keyFor(secret, salt, ITERATIONS)
            val sealed = cipher.seal(key, encodeArchivePasswords(merged).encodeToByteArray())
            val text = json.encodeToString(Envelope.serializer(), Envelope(VERSION, ITERATIONS, Base64.encode(salt), Base64.encode(sealed)))
            remote.write(account, text, now())
            PikoLog.d(TAG, "已推送压缩包密码，${merged.count { !it.value.removed }} 个")
        }
    }

    private suspend fun open(envelope: Envelope, secret: String): Map<String, ArchivePasswordEntry>? {
        val salt = runCatching { Base64.decode(envelope.salt) }.logFailure(TAG, "压缩包密码文件的 salt 不是 Base64").getOrNull() ?: return null
        val sealed = runCatching { Base64.decode(envelope.data) }.logFailure(TAG, "压缩包密码文件的密文不是 Base64").getOrNull() ?: return null
        val plain = cipher.open(keyFor(secret, salt, envelope.iterations), sealed) ?: run {
            PikoLog.d(TAG, "压缩包密码文件解密失败（密钥不符或内容损坏），迭代 ${envelope.iterations} 次")
            return null
        }
        return decodeArchivePasswords(plain.decodeToString())
    }

    private suspend fun keyFor(secret: String, salt: ByteArray, iterations: Int): ByteArray {
        cachedKey?.takeIf { it.secret == secret && it.salt.contentEquals(salt) && it.iterations == iterations }?.let { return it.key }
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        val key = withContext(Dispatchers.Default) { cipher.deriveKey(secret, salt, iterations) }
        PikoLog.d(TAG, "派生同步密钥：$iterations 次迭代，历时 ${started.elapsedNow().inWholeMilliseconds} ms")
        cachedKey = CachedKey(secret, salt, iterations, key)
        return key
    }

    private class CachedKey(val secret: String, val salt: ByteArray, val iterations: Int, val key: ByteArray)

    /** 网盘上那份文件。[data] 是加密后的整份记录，明文格式同本机，见 encodeArchivePasswords。 */
    @Serializable
    private data class Envelope(val version: Int, val iterations: Int, val salt: String, val data: String)

    private companion object {
        const val FILE_PREFIX = "archive-passwords-"
        const val VERSION = 1

        /** OWASP 给 PBKDF2-HMAC-SHA256 的建议值。随文件存，以后调高不影响解旧文件。 */
        const val ITERATIONS = 600_000
        const val PUSH_DELAY_MS = 3_000L
        const val TAG = "ArchivePasswordSync"
        val json = Json { ignoreUnknownKeys = true }
    }
}
