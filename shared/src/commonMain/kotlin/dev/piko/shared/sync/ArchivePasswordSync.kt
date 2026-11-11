package dev.piko.shared.sync

import dev.piko.shared.data.ArchivePasswordEntry
import dev.piko.shared.data.ArchivePasswordVault
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.decodeArchivePasswords
import dev.piko.shared.data.encodeArchivePasswords
import dev.piko.shared.data.mergeArchivePasswords
import dev.piko.shared.log.PikoLog
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
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

/**
 * 把当前账号解压用过的密码（[ArchivePasswordVault]）加密后存进它网盘 `.piko` 下的 `archive-passwords-<时间戳>.json`，
 * 换一台设备登录同一个账号，输过的密码照样能挑。只推这个账号的，拉下来的也只并进这个账号。
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
        vault: ArchivePasswordVault,
        cipher: SyncCipher,
        scope: CoroutineScope,
        enabled: Flow<Boolean>,
    ) : this(clients, DriveSettingsStore(clients, driveRepo, FILE_PREFIX, TAG), vault, clients::savedPassword, cipher, scope, enabled)

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
        try {
            sync(account)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: SyncAccountSwitchedException) {
            PikoLog.d(TAG, "Skipped sync: active account changed")
            false
        } catch (e: Throwable) {
            PikoLog.w(TAG, "Archive password sync failed: ${e::class.simpleName}")
            false
        }
    }

    private fun requireCurrent(account: String) {
        if (clients.currentClient.value?.account != account) throw SyncAccountSwitchedException()
    }

    private sealed interface RemoteReadResult {
        data object Absent : RemoteReadResult
        data object AbortCorruptOrUnsupported : RemoteReadResult
        class KeyChanged(val envelope: Envelope) : RemoteReadResult
        class Opened(val envelope: Envelope, val salt: ByteArray, val entries: Map<String, ArchivePasswordEntry>) : RemoteReadResult
    }

    private suspend fun sync(account: String) {
        requireCurrent(account)
        val secret = passwordOf(account) ?: run {
            PikoLog.i(TAG, "Account password unavailable; skipping archive password sync for this round")
            return
        }
        requireCurrent(account)
        val local = vault.current(account)
        val remoteText = remote.read(account)
        requireCurrent(account)

        val opened = if (remoteText == null) {
            RemoteReadResult.Absent
        } else {
            readRemoteEnvelope(remoteText, secret)
        }
        if (opened is RemoteReadResult.AbortCorruptOrUnsupported) return

        val remoteEntries = (opened as? RemoteReadResult.Opened)?.entries
        val hasRemoteFile = opened !is RemoteReadResult.Absent
        if (opened is RemoteReadResult.KeyChanged) {
            PikoLog.w(TAG, "Failed to decrypt remote archive passwords (account password may have changed); rewriting from ${local.size} local entries")
        }

        val merged = mergeArchivePasswords(local, remoteEntries.orEmpty())
        requireCurrent(account)
        if (merged != local) vault.merge(account, merged)
        // 只记条数，密码与它对应的压缩包都不进日志
        PikoLog.d(
            TAG,
            "Archive passwords merged: local=${local.size}, remote=${remoteEntries?.size?.toString() ?: if (!hasRemoteFile) "none" else "key_changed"}, " +
                "merged=${merged.size} (removed=${merged.count { it.value.removed }}), localUpdated=${merged != local}",
        )
        if (merged != remoteEntries && (hasRemoteFile || merged.isNotEmpty())) {
            // 解得开就沿用原来的 salt，省一次派生；解不开的换新的
            val salt = (opened as? RemoteReadResult.Opened)?.salt ?: cipher.newSalt()
            val key = keyFor(secret, salt, ITERATIONS)
            val sealed = cipher.seal(key, encodeArchivePasswords(merged).encodeToByteArray())
            val text = json.encodeToString(Envelope.serializer(), Envelope(VERSION, ITERATIONS, Base64.encode(salt), Base64.encode(sealed)))
            requireCurrent(account)
            remote.write(account, text, now())
            PikoLog.d(TAG, "Pushed encrypted archive passwords: active=${merged.count { !it.value.removed }}")
        }
    }

    private suspend fun readRemoteEnvelope(remoteText: String, secret: String): RemoteReadResult {
        val envelope = runCatching { json.decodeFromString(Envelope.serializer(), remoteText) }
            .onFailure {
                PikoLog.w(TAG, "Remote archive password envelope is malformed (${it::class.simpleName}); skipping sync to preserve remote file")
            }
            .getOrNull() ?: return RemoteReadResult.AbortCorruptOrUnsupported
        if (envelope.version > VERSION) {
            PikoLog.w(TAG, "Remote archive password version ${envelope.version} is newer than supported $VERSION; skipping sync")
            return RemoteReadResult.AbortCorruptOrUnsupported
        }
        if (envelope.iterations !in 1..ITERATIONS) {
            PikoLog.w(TAG, "Remote archive password KDF iterations (${envelope.iterations}) out of range 1..$ITERATIONS; skipping sync")
            return RemoteReadResult.AbortCorruptOrUnsupported
        }
        val salt = runCatching { Base64.decode(envelope.salt) }
            .onFailure { PikoLog.w(TAG, "Remote archive password salt is not valid Base64; skipping sync") }
            .getOrNull() ?: return RemoteReadResult.AbortCorruptOrUnsupported
        val sealed = runCatching { Base64.decode(envelope.data) }
            .onFailure { PikoLog.w(TAG, "Remote archive password ciphertext is not valid Base64; skipping sync") }
            .getOrNull() ?: return RemoteReadResult.AbortCorruptOrUnsupported
        val plain = cipher.open(keyFor(secret, salt, envelope.iterations), sealed)
            ?: return RemoteReadResult.KeyChanged(envelope)
        val decodedText = runCatching { plain.decodeToString(throwOnInvalidSequence = true) }
            .onFailure { PikoLog.w(TAG, "Decrypted archive password payload is not valid UTF-8; skipping sync") }
            .getOrNull() ?: return RemoteReadResult.AbortCorruptOrUnsupported
        return RemoteReadResult.Opened(envelope, salt, decodeArchivePasswords(decodedText))
    }

    private suspend fun keyFor(secret: String, salt: ByteArray, iterations: Int): ByteArray {
        cachedKey?.takeIf { it.secret == secret && it.salt.contentEquals(salt) && it.iterations == iterations }?.let { return it.key }
        val started = TimeSource.Monotonic.markNow()
        val key = withContext(Dispatchers.Default) { cipher.deriveKey(secret, salt, iterations) }
        PikoLog.d(TAG, "Derived archive password sync key: iterations=$iterations, elapsed=${started.elapsedNow().inWholeMilliseconds} ms")
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
