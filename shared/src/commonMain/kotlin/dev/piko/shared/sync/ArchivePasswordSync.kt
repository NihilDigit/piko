package dev.piko.shared.sync

import dev.piko.shared.auth.VaultUnavailableException
import dev.piko.shared.data.ArchivePasswordEntry
import dev.piko.shared.data.ArchivePasswordVault
import dev.piko.shared.data.EncryptedSyncKey
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * 密钥优先级：独立同步口令（`syncSecret`，写入 `version = 2` 信封以阻止旧版本客户端误用账号密码覆盖）优先，
 * 未设置时回退到已保存的账号密码（`version = 1`）。纯 Google 登录且未配置同步口令时记为 [EncryptedSyncStatus.LockedNoKey]。
 *
 * 远端密文与本机密钥不匹配时（改过密码或另一台设备配置了同步口令）记为 [EncryptedSyncStatus.LockedKeyMismatch]，
 * **绝不自动覆盖远端密文**；仅当用户显式调用 [resetRemoteWithCurrentKey] 时才在写好 `backup-archive-passwords-<时间戳>.json`
 * 备份后用本机记录重新加密写入。
 */
class ArchivePasswordSync(
    private val clients: PikoClientProvider,
    private val remote: RemoteSettingsStore,
    private val vault: ArchivePasswordVault,
    private val keyProvider: SyncKeyProvider,
    private val cipher: SyncCipher,
    private val scope: CoroutineScope,
    private val enabled: Flow<Boolean>,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    constructor(
        clients: PikoClientProvider,
        remote: RemoteSettingsStore,
        vault: ArchivePasswordVault,
        passwordOf: suspend (account: String) -> String?,
        cipher: SyncCipher,
        scope: CoroutineScope,
        enabled: Flow<Boolean>,
        now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    ) : this(
        clients = clients,
        remote = remote,
        vault = vault,
        keyProvider = SyncKeyProvider { account ->
            passwordOf(account)?.takeIf { it.isNotEmpty() }?.let { EncryptedSyncKey(it, isPassphrase = false) }
        },
        cipher = cipher,
        scope = scope,
        enabled = enabled,
        now = now,
    )

    constructor(
        clients: PikoClientManager,
        driveRepo: PikoDriveRepository,
        vault: ArchivePasswordVault,
        cipher: SyncCipher,
        scope: CoroutineScope,
        enabled: Flow<Boolean>,
    ) : this(
        clients = clients,
        remote = DriveSettingsStore(clients, driveRepo, FILE_PREFIX, TAG),
        vault = vault,
        keyProvider = SyncKeyProvider(clients::syncKeyFor),
        cipher = cipher,
        scope = scope,
        enabled = enabled,
    )

    private val _status = MutableStateFlow(EncryptedSyncStatus.Idle)
    val status: StateFlow<EncryptedSyncStatus> = _status.asStateFlow()

    private val lock = Mutex()

    // 上一次派生出的密钥。派生故意算得慢，每次同步都算一遍不划算；口令/密码或 salt 变了才重算
    private var cachedKey: CachedKey? = null

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            combine(clients.currentClient, enabled) { client, on -> client?.account.takeIf { on } }
                .distinctUntilChanged()
                .collectLatest { account ->
                    _status.value = EncryptedSyncStatus.Idle
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
        } catch (_: VaultUnavailableException) {
            PikoLog.w(TAG, "Secret vault temporarily unavailable; skipping archive password sync round")
            false
        } catch (e: Throwable) {
            PikoLog.w(TAG, "Archive password sync failed: ${e::class.simpleName}")
            false
        }
    }

    /**
     * 预检新口令是否可解开或经 [previousKey] 无损轮换当前远端密文（不写入本机或云端）。
     */
    suspend fun checkPassphrase(
        expectedAccount: String,
        newPassphrase: String,
        previousKey: EncryptedSyncKey?,
    ): EncryptedSyncApplyOutcome = lock.withLock {
        if (expectedAccount.isBlank() || newPassphrase.isEmpty()) return@withLock EncryptedSyncApplyOutcome.DEFERRED
        try {
            requireCurrent(expectedAccount)
            val remoteText = remote.read(expectedAccount) ?: return@withLock EncryptedSyncApplyOutcome.UNLOCKED
            requireCurrent(expectedAccount)
            when (readRemoteEnvelope(remoteText, newPassphrase)) {
                is RemoteReadResult.Opened -> EncryptedSyncApplyOutcome.UNLOCKED
                RemoteReadResult.AbortCorruptOrUnsupported -> {
                    _status.value = EncryptedSyncStatus.CorruptOrUnsupported
                    EncryptedSyncApplyOutcome.DEFERRED
                }
                RemoteReadResult.Absent -> EncryptedSyncApplyOutcome.UNLOCKED
                is RemoteReadResult.KeyChanged -> {
                    val prevSecret = previousKey?.secret?.takeIf { it.isNotEmpty() && it != newPassphrase }
                    if (prevSecret != null && readRemoteEnvelope(remoteText, prevSecret) is RemoteReadResult.Opened) {
                        EncryptedSyncApplyOutcome.UNLOCKED
                    } else {
                        _status.value = EncryptedSyncStatus.LockedKeyMismatch
                        EncryptedSyncApplyOutcome.KEY_MISMATCH
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: SyncAccountSwitchedException) {
            EncryptedSyncApplyOutcome.DEFERRED
        } catch (_: VaultUnavailableException) {
            EncryptedSyncApplyOutcome.DEFERRED
        } catch (e: Throwable) {
            PikoLog.w(TAG, "Archive password passphrase preflight failed: ${e::class.simpleName}")
            EncryptedSyncApplyOutcome.DEFERRED
        }
    }

    /**
     * 应用新口令（或清除口令）并完成与远端的解锁合并或从 [previousKey] 的无损重加密轮换（始终升级为 `version = 2`）。
     */
    suspend fun applyPassphrase(
        expectedAccount: String,
        newPassphrase: String,
        previousKey: EncryptedSyncKey?,
    ): EncryptedSyncApplyOutcome = lock.withLock {
        if (expectedAccount.isBlank()) return@withLock EncryptedSyncApplyOutcome.DEFERRED
        try {
            requireCurrent(expectedAccount)
            if (newPassphrase.isEmpty()) {
                sync(expectedAccount)
                return@withLock when (_status.value) {
                    EncryptedSyncStatus.Unlocked -> EncryptedSyncApplyOutcome.UNLOCKED
                    EncryptedSyncStatus.LockedKeyMismatch -> EncryptedSyncApplyOutcome.KEY_MISMATCH
                    else -> EncryptedSyncApplyOutcome.DEFERRED
                }
            }
            val local = vault.current(expectedAccount)
            val remoteText = remote.read(expectedAccount)
            requireCurrent(expectedAccount)
            val direct = if (remoteText == null) RemoteReadResult.Absent else readRemoteEnvelope(remoteText, newPassphrase)
            val remoteEntries: Map<String, ArchivePasswordEntry>?
            val rotatedFromPrevious: Boolean
            when (direct) {
                RemoteReadResult.Absent -> {
                    remoteEntries = null
                    rotatedFromPrevious = false
                }
                is RemoteReadResult.Opened -> {
                    remoteEntries = direct.entries
                    rotatedFromPrevious = false
                }
                RemoteReadResult.AbortCorruptOrUnsupported -> {
                    _status.value = EncryptedSyncStatus.CorruptOrUnsupported
                    return@withLock EncryptedSyncApplyOutcome.DEFERRED
                }
                is RemoteReadResult.KeyChanged -> {
                    val prevSecret = previousKey?.secret?.takeIf { it.isNotEmpty() && it != newPassphrase }
                    val prevOpened = prevSecret?.let { readRemoteEnvelope(remoteText!!, it) } as? RemoteReadResult.Opened
                    if (prevOpened == null) {
                        _status.value = EncryptedSyncStatus.LockedKeyMismatch
                        return@withLock EncryptedSyncApplyOutcome.KEY_MISMATCH
                    }
                    remoteEntries = prevOpened.entries
                    rotatedFromPrevious = true
                }
            }
            val merged = mergeArchivePasswords(local, remoteEntries.orEmpty())
            requireCurrent(expectedAccount)
            if (merged != local) vault.merge(expectedAccount, merged)
            val hasRemoteFile = direct !is RemoteReadResult.Absent
            val versionNeedsUpgrade = direct is RemoteReadResult.Opened && direct.envelope.version < VERSION_PASSPHRASE
            val mustPush = rotatedFromPrevious || versionNeedsUpgrade ||
                (merged != remoteEntries && (hasRemoteFile || merged.isNotEmpty()))
            if (mustPush && (hasRemoteFile || merged.isNotEmpty())) {
                val writeStamp = maxOf(now(), remote.latestStamp(expectedAccount) + 1L)
                requireCurrent(expectedAccount)
                val salt = if (!rotatedFromPrevious && !versionNeedsUpgrade && direct is RemoteReadResult.Opened) {
                    direct.salt
                } else {
                    cipher.newSalt()
                }
                val key = keyFor(newPassphrase, salt, ITERATIONS)
                val sealed = cipher.seal(key, encodeArchivePasswords(merged).encodeToByteArray())
                val text = json.encodeToString(
                    Envelope.serializer(),
                    Envelope(VERSION_PASSPHRASE, ITERATIONS, Base64.encode(salt), Base64.encode(sealed)),
                )
                requireCurrent(expectedAccount)
                remote.write(expectedAccount, text, writeStamp)
                PikoLog.i(
                    TAG,
                    "Applied archive password passphrase (rotated=$rotatedFromPrevious, entries=${merged.size}, version=$VERSION_PASSPHRASE)",
                )
            }
            _status.value = EncryptedSyncStatus.Unlocked
            EncryptedSyncApplyOutcome.UNLOCKED
        } catch (e: CancellationException) {
            throw e
        } catch (_: SyncAccountSwitchedException) {
            EncryptedSyncApplyOutcome.DEFERRED
        } catch (_: VaultUnavailableException) {
            EncryptedSyncApplyOutcome.DEFERRED
        } catch (e: Throwable) {
            PikoLog.w(TAG, "Failed to apply archive password passphrase: ${e::class.simpleName}")
            EncryptedSyncApplyOutcome.DEFERRED
        }
    }

    /**
     * 显式重置云端加密副本：先将当前远端密文保存至 `backup-archive-passwords-<时间戳>.json`，
     * 再用当前账号的有效同步密钥将本机解压密码重新加密上传。
     */
    suspend fun resetRemoteWithCurrentKey(expectedAccount: String = clients.currentClient.value?.account.orEmpty()): Boolean = lock.withLock {
        val account = expectedAccount.takeIf { it.isNotBlank() } ?: return@withLock false
        try {
            requireCurrent(account)
            val keyInfo = keyProvider.keyFor(account) ?: run {
                _status.value = EncryptedSyncStatus.LockedNoKey
                return@withLock false
            }
            requireCurrent(account)
            val remoteText = remote.read(account)
            requireCurrent(account)
            val writeStamp = maxOf(now(), remote.latestStamp(account) + 1L)
            requireCurrent(account)
            if (remoteText != null) {
                remote.writeBackup(account, remoteText, writeStamp)
                requireCurrent(account)
            }
            val local = vault.current(account)
            val salt = cipher.newSalt()
            val key = keyFor(keyInfo.secret, salt, ITERATIONS)
            val sealed = cipher.seal(key, encodeArchivePasswords(local).encodeToByteArray())
            val remoteVersion = remoteText
                ?.let { runCatching { json.decodeFromString(Envelope.serializer(), it).version }.getOrNull() }
                ?.takeIf { it in VERSION_PASSWORD..MAX_VERSION }
                ?: 0
            val outVersion = maxOf(if (keyInfo.isPassphrase) VERSION_PASSPHRASE else VERSION_PASSWORD, remoteVersion)
            val text = json.encodeToString(
                Envelope.serializer(),
                Envelope(outVersion, ITERATIONS, Base64.encode(salt), Base64.encode(sealed)),
            )
            requireCurrent(account)
            remote.write(account, text, writeStamp)
            _status.value = EncryptedSyncStatus.Unlocked
            PikoLog.i(TAG, "Reset remote encrypted archive passwords with current key (entries=${local.size}, version=$outVersion)")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: SyncAccountSwitchedException) {
            false
        } catch (e: Throwable) {
            PikoLog.w(TAG, "Failed to reset remote archive passwords: ${e::class.simpleName}")
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
        val keyInfo = keyProvider.keyFor(account) ?: run {
            if (_status.value != EncryptedSyncStatus.LockedNoKey) {
                _status.value = EncryptedSyncStatus.LockedNoKey
                PikoLog.i(TAG, "No sync passphrase or saved account password; archive password sync is locked")
            }
            return
        }
        requireCurrent(account)
        val local = vault.current(account)
        val remoteText = remote.read(account)
        requireCurrent(account)

        val opened = if (remoteText == null) {
            RemoteReadResult.Absent
        } else {
            readRemoteEnvelope(remoteText, keyInfo.secret)
        }
        when (opened) {
            RemoteReadResult.AbortCorruptOrUnsupported -> {
                _status.value = EncryptedSyncStatus.CorruptOrUnsupported
                return
            }
            is RemoteReadResult.KeyChanged -> {
                _status.value = EncryptedSyncStatus.LockedKeyMismatch
                PikoLog.w(
                    TAG,
                    "Cannot decrypt remote archive passwords (envelopeVersion=${opened.envelope.version}, usingPassphrase=${keyInfo.isPassphrase}); preserving remote ciphertext",
                )
                return
            }
            RemoteReadResult.Absent, is RemoteReadResult.Opened -> Unit
        }

        val remoteEntries = (opened as? RemoteReadResult.Opened)?.entries
        val hasRemoteFile = opened !is RemoteReadResult.Absent
        val baseVersion = if (keyInfo.isPassphrase) VERSION_PASSPHRASE else VERSION_PASSWORD
        // 一旦远端已升级为 v2（独立口令信封），即便另一台设备凑巧用相同字符串作为登录密码解开，也绝不降级回 v1
        val outVersion = if (opened is RemoteReadResult.Opened) maxOf(baseVersion, opened.envelope.version) else baseVersion
        val versionChanged = opened is RemoteReadResult.Opened && opened.envelope.version != outVersion

        val merged = mergeArchivePasswords(local, remoteEntries.orEmpty())
        requireCurrent(account)
        if (merged != local) vault.merge(account, merged)
        PikoLog.d(
            TAG,
            "Archive passwords merged: local=${local.size}, remote=${remoteEntries?.size?.toString() ?: "none"}, " +
                "merged=${merged.size} (removed=${merged.count { it.value.removed }}), localUpdated=${merged != local}",
        )
        if ((merged != remoteEntries || versionChanged) && (hasRemoteFile || merged.isNotEmpty())) {
            val salt = (opened as? RemoteReadResult.Opened)?.takeUnless { versionChanged }?.salt ?: cipher.newSalt()
            val key = keyFor(keyInfo.secret, salt, ITERATIONS)
            val sealed = cipher.seal(key, encodeArchivePasswords(merged).encodeToByteArray())
            val text = json.encodeToString(Envelope.serializer(), Envelope(outVersion, ITERATIONS, Base64.encode(salt), Base64.encode(sealed)))
            requireCurrent(account)
            remote.write(account, text, now())
            PikoLog.d(TAG, "Pushed encrypted archive passwords: active=${merged.count { !it.value.removed }}, version=$outVersion")
        }
        _status.value = EncryptedSyncStatus.Unlocked
    }

    private suspend fun readRemoteEnvelope(remoteText: String, secret: String): RemoteReadResult {
        val envelope = runCatching { json.decodeFromString(Envelope.serializer(), remoteText) }
            .onFailure {
                PikoLog.w(TAG, "Remote archive password envelope is malformed (${it::class.simpleName}); skipping sync to preserve remote file")
            }
            .getOrNull() ?: return RemoteReadResult.AbortCorruptOrUnsupported
        if (envelope.version !in VERSION_PASSWORD..MAX_VERSION) {
            PikoLog.w(TAG, "Remote archive password version ${envelope.version} outside supported $VERSION_PASSWORD..$MAX_VERSION; skipping sync")
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
        const val VERSION_PASSWORD = 1
        const val VERSION_PASSPHRASE = 2
        const val MAX_VERSION = VERSION_PASSPHRASE

        /** OWASP 给 PBKDF2-HMAC-SHA256 的建议值。随文件存，以后调高不影响解旧文件。 */
        const val ITERATIONS = 600_000
        const val PUSH_DELAY_MS = 3_000L
        const val TAG = "ArchivePasswordSync"
        val json = Json { ignoreUnknownKeys = true }
    }
}
