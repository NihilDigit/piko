package dev.piko.shared.sync

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.auth.VaultUnavailableException
import dev.piko.shared.data.EncryptedSyncKey
import dev.piko.shared.data.PikoCacheStore
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.log.PikoLog
import io.github.nihildigit.pikpak.PikPakException
import io.ktor.util.Digest
import kotlin.io.encoding.Base64
import kotlin.time.Clock
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * MetaTube 地址与令牌加密同步：优先使用独立同步口令（`version = 2`），未设置时回退到已保存的账号密码（`version = 1`）。
 * 密钥不匹配时保留远端密文并暴露 [EncryptedSyncStatus.LockedKeyMismatch]，仅在 [resetRemoteWithCurrentKey] 时先备份再重写。
 */
class MetaTubeSync(
    private val clients: PikoClientProvider,
    private val remote: RemoteSettingsStore,
    private val preferences: PikoUserPreferences,
    private val cacheStore: PikoCacheStore?,
    private val keyProvider: SyncKeyProvider,
    private val cipher: SyncCipher,
    private val scope: CoroutineScope,
    private val enabled: Flow<Boolean>,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    constructor(
        clients: PikoClientProvider,
        remote: RemoteSettingsStore,
        preferences: PikoUserPreferences,
        cacheStore: PikoCacheStore?,
        passwordOf: suspend (String) -> String?,
        cipher: SyncCipher,
        scope: CoroutineScope,
        enabled: Flow<Boolean>,
        now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    ) : this(
        clients = clients,
        remote = remote,
        preferences = preferences,
        cacheStore = cacheStore,
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
        preferences: PikoUserPreferences,
        cacheStore: PikoCacheStore?,
        cipher: SyncCipher,
        scope: CoroutineScope,
        enabled: Flow<Boolean>,
    ) : this(
        clients = clients,
        remote = DriveSettingsStore(clients, driveRepo, FILE_PREFIX, TAG),
        preferences = preferences,
        cacheStore = cacheStore,
        keyProvider = SyncKeyProvider(clients::syncKeyFor),
        cipher = cipher,
        scope = scope,
        enabled = enabled,
    )

    private val _status = MutableStateFlow(EncryptedSyncStatus.Idle)
    val status: StateFlow<EncryptedSyncStatus> = _status.asStateFlow()

    private val lock = Mutex()
    private var cachedKey: CachedKey? = null
    private var lastSyncedPair: Pair<String, Pair<String, String>>? = null

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            combine(clients.currentClient, enabled) { client, on -> client?.account.takeIf { on } }
                .distinctUntilChanged()
                .collectLatest { account ->
                    _status.value = EncryptedSyncStatus.Idle
                    if (account == null) return@collectLatest
                    syncNow()
                    combine(preferences.metaTubeUrlFlow, preferences.metaTubeTokenFlow) { url, token -> url to token }
                        .distinctUntilChanged().debounce(PUSH_DELAY_MS).collect { pair ->
                            if (lastSyncedPair != (account to pair)) syncNow()
                        }
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
            PikoLog.w(TAG, "Secret vault temporarily unavailable; skipping MetaTube sync round")
            false
        } catch (e: Exception) {
            // 仅记录异常类名或 PikPak 错误码/HTTP 状态码，严禁输出可能包含明文地址或令牌的异常 message
            val detail = if (e is PikPakException) {
                "PikPakException(code=${e.errorCode}, http=${e.httpStatus})"
            } else {
                e::class.simpleName ?: "Exception"
            }
            PikoLog.w(TAG, "Encrypted MetaTube sync failed: $detail")
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
            when (openEnvelope(remoteText, newPassphrase)) {
                is RemotePayloadResult.Opened -> EncryptedSyncApplyOutcome.UNLOCKED
                RemotePayloadResult.AbortCorruptOrUnsupported -> {
                    _status.value = EncryptedSyncStatus.CorruptOrUnsupported
                    EncryptedSyncApplyOutcome.DEFERRED
                }
                RemotePayloadResult.Absent -> EncryptedSyncApplyOutcome.UNLOCKED
                is RemotePayloadResult.KeyChanged -> {
                    val prevSecret = previousKey?.secret?.takeIf { it.isNotEmpty() && it != newPassphrase }
                    if (prevSecret != null && openEnvelope(remoteText, prevSecret) is RemotePayloadResult.Opened) {
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
        } catch (e: Exception) {
            PikoLog.w(TAG, "MetaTube passphrase preflight failed: ${e::class.simpleName}")
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
            val stamp = now()
            val localUrl = preferences.metaTubeUrlFlow.first()
            requireCurrent(expectedAccount)
            val localToken = preferences.metaTubeTokenFlow.first()
            requireCurrent(expectedAccount)
            val known = cacheStore?.read(stateKey(expectedAccount))?.let { raw ->
                runCatching { json.decodeFromString(LocalState.serializer(), raw) }.getOrNull()
            }
            requireCurrent(expectedAccount)
            val remoteText = remote.read(expectedAccount)
            requireCurrent(expectedAccount)
            if (remoteText == null && localUrl.isEmpty() && localToken.isEmpty() && known == null) {
                _status.value = EncryptedSyncStatus.Unlocked
                return@withLock EncryptedSyncApplyOutcome.UNLOCKED
            }
            val direct = if (remoteText == null) RemotePayloadResult.Absent else openEnvelope(remoteText, newPassphrase)
            val remotePayload: Payload?
            val rotatedFromPrevious: Boolean
            when (direct) {
                RemotePayloadResult.Absent -> {
                    remotePayload = null
                    rotatedFromPrevious = false
                }
                is RemotePayloadResult.Opened -> {
                    remotePayload = direct.payload
                    rotatedFromPrevious = false
                }
                RemotePayloadResult.AbortCorruptOrUnsupported -> {
                    _status.value = EncryptedSyncStatus.CorruptOrUnsupported
                    return@withLock EncryptedSyncApplyOutcome.DEFERRED
                }
                is RemotePayloadResult.KeyChanged -> {
                    val prevSecret = previousKey?.secret?.takeIf { it.isNotEmpty() && it != newPassphrase }
                    val prevOpened = prevSecret?.let { openEnvelope(remoteText!!, it) } as? RemotePayloadResult.Opened
                    if (prevOpened == null) {
                        _status.value = EncryptedSyncStatus.LockedKeyMismatch
                        return@withLock EncryptedSyncApplyOutcome.KEY_MISMATCH
                    }
                    remotePayload = prevOpened.payload
                    rotatedFromPrevious = true
                }
            }
            val urlDigest = digest(localUrl)
            val tokenDigest = digest(localToken)
            fun entry(value: String, sha256: String, last: FieldDigest?): Entry = Entry(
                value,
                when {
                    last == null -> if (value.isEmpty()) 0L else if (remotePayload != null) 1L else stamp
                    last.sha256 == sha256 -> last.updatedAt
                    else -> stamp
                },
            )
            fun merge(a: Entry, b: Entry?): Entry = if (b != null && b.updatedAt >= a.updatedAt) b else a
            val merged = Payload(
                merge(entry(localUrl, urlDigest, known?.url), remotePayload?.url),
                merge(entry(localToken, tokenDigest, known?.token), remotePayload?.token),
            )
            requireCurrent(expectedAccount)
            if (merged.url.value != localUrl) preferences.setMetaTubeUrl(merged.url.value)
            requireCurrent(expectedAccount)
            if (merged.token.value != localToken) preferences.setMetaTubeToken(merged.token.value)
            requireCurrent(expectedAccount)
            lastSyncedPair = expectedAccount to (merged.url.value to merged.token.value)
            val state = LocalState(
                FieldDigest(digest(merged.url.value), merged.url.updatedAt),
                FieldDigest(digest(merged.token.value), merged.token.updatedAt),
            )
            requireCurrent(expectedAccount)
            cacheStore?.write(stateKey(expectedAccount), json.encodeToString(LocalState.serializer(), state))
            val hasRemoteFile = direct !is RemotePayloadResult.Absent
            val versionNeedsUpgrade = direct is RemotePayloadResult.Opened && direct.envelope.version < VERSION_PASSPHRASE
            val mustPush = rotatedFromPrevious || versionNeedsUpgrade ||
                (merged != remotePayload && (hasRemoteFile || merged.url.value.isNotEmpty() || merged.token.value.isNotEmpty()))
            if (mustPush && (hasRemoteFile || merged.url.value.isNotEmpty() || merged.token.value.isNotEmpty())) {
                val writeStamp = maxOf(stamp, remote.latestStamp(expectedAccount) + 1L)
                requireCurrent(expectedAccount)
                val salt = if (!rotatedFromPrevious && !versionNeedsUpgrade && direct is RemotePayloadResult.Opened) {
                    direct.salt
                } else {
                    cipher.newSalt()
                }
                val key = keyFor(newPassphrase, salt, ITERATIONS)
                val sealed = cipher.seal(key, json.encodeToString(Payload.serializer(), merged).encodeToByteArray())
                val text = json.encodeToString(
                    Envelope.serializer(),
                    Envelope(VERSION_PASSPHRASE, ITERATIONS, Base64.encode(salt), Base64.encode(sealed)),
                )
                requireCurrent(expectedAccount)
                remote.write(expectedAccount, text, writeStamp)
                PikoLog.i(TAG, "Applied MetaTube passphrase (rotated=$rotatedFromPrevious, version=$VERSION_PASSPHRASE)")
            }
            _status.value = EncryptedSyncStatus.Unlocked
            EncryptedSyncApplyOutcome.UNLOCKED
        } catch (e: CancellationException) {
            throw e
        } catch (_: SyncAccountSwitchedException) {
            EncryptedSyncApplyOutcome.DEFERRED
        } catch (_: VaultUnavailableException) {
            EncryptedSyncApplyOutcome.DEFERRED
        } catch (e: Exception) {
            PikoLog.w(TAG, "Failed to apply MetaTube passphrase: ${e::class.simpleName}")
            EncryptedSyncApplyOutcome.DEFERRED
        }
    }

    /**
     * 显式重置云端加密副本：先将当前远端密文备份至 `backup-metatube-<时间戳>.json`，
     * 再用当前同步密钥将本机 MetaTube 配置重新加密写入。
     */
    suspend fun resetRemoteWithCurrentKey(expectedAccount: String = clients.currentClient.value?.account.orEmpty()): Boolean = lock.withLock {
        val account = expectedAccount.takeIf { it.isNotBlank() } ?: return@withLock false
        try {
            val stamp = now()
            requireCurrent(account)
            val keyInfo = keyProvider.keyFor(account) ?: run {
                _status.value = EncryptedSyncStatus.LockedNoKey
                return@withLock false
            }
            requireCurrent(account)
            val remoteText = remote.read(account)
            requireCurrent(account)
            val writeStamp = maxOf(stamp, remote.latestStamp(account) + 1L)
            requireCurrent(account)
            if (remoteText != null) {
                remote.writeBackup(account, remoteText, writeStamp)
                requireCurrent(account)
            }
            val localUrl = preferences.metaTubeUrlFlow.first()
            requireCurrent(account)
            val localToken = preferences.metaTubeTokenFlow.first()
            requireCurrent(account)
            val payload = Payload(
                url = Entry(localUrl, if (localUrl.isEmpty()) 0L else writeStamp),
                token = Entry(localToken, if (localToken.isEmpty()) 0L else writeStamp),
            )
            val state = LocalState(
                FieldDigest(digest(localUrl), payload.url.updatedAt),
                FieldDigest(digest(localToken), payload.token.updatedAt),
            )
            cacheStore?.write(stateKey(account), json.encodeToString(LocalState.serializer(), state))
            lastSyncedPair = account to (localUrl to localToken)
            if (remoteText != null || localUrl.isNotEmpty() || localToken.isNotEmpty()) {
                val salt = cipher.newSalt()
                val key = keyFor(keyInfo.secret, salt, ITERATIONS)
                val sealed = cipher.seal(key, json.encodeToString(Payload.serializer(), payload).encodeToByteArray())
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
            }
            _status.value = EncryptedSyncStatus.Unlocked
            PikoLog.i(TAG, "Reset remote encrypted MetaTube config with current key (usingPassphrase=${keyInfo.isPassphrase})")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: SyncAccountSwitchedException) {
            false
        } catch (e: Exception) {
            PikoLog.w(TAG, "Failed to reset remote MetaTube config: ${e::class.simpleName}")
            false
        }
    }

    private fun requireCurrent(account: String) {
        if (clients.currentClient.value?.account != account) throw SyncAccountSwitchedException()
    }

    private sealed interface RemotePayloadResult {
        data object Absent : RemotePayloadResult
        data object AbortCorruptOrUnsupported : RemotePayloadResult
        class KeyChanged(val envelope: Envelope) : RemotePayloadResult
        class Opened(val envelope: Envelope, val salt: ByteArray, val payload: Payload) : RemotePayloadResult
    }

    private suspend fun sync(account: String) {
        val stamp = now()
        requireCurrent(account)
        val keyInfo = keyProvider.keyFor(account) ?: run {
            if (_status.value != EncryptedSyncStatus.LockedNoKey) {
                _status.value = EncryptedSyncStatus.LockedNoKey
                PikoLog.i(TAG, "No sync passphrase or saved account password; MetaTube sync is locked")
            }
            return
        }
        requireCurrent(account)
        val localUrl = preferences.metaTubeUrlFlow.first()
        requireCurrent(account)
        val localToken = preferences.metaTubeTokenFlow.first()
        requireCurrent(account)
        val known = cacheStore?.read(stateKey(account))?.let { raw ->
            runCatching { json.decodeFromString(LocalState.serializer(), raw) }
                .onFailure { PikoLog.w(TAG, "Failed to parse local MetaTube sync ledger (${it::class.simpleName}); treating as unsynced") }
                .getOrNull()
        }
        requireCurrent(account)
        val remoteText = remote.read(account)
        requireCurrent(account)
        if (remoteText == null && localUrl.isEmpty() && localToken.isEmpty() && known == null) {
            _status.value = EncryptedSyncStatus.Unlocked
            return
        }

        val remoteResult = if (remoteText == null) {
            RemotePayloadResult.Absent
        } else {
            openEnvelope(remoteText, keyInfo.secret)
        }
        when (remoteResult) {
            RemotePayloadResult.AbortCorruptOrUnsupported -> {
                _status.value = EncryptedSyncStatus.CorruptOrUnsupported
                return
            }
            is RemotePayloadResult.KeyChanged -> {
                _status.value = EncryptedSyncStatus.LockedKeyMismatch
                PikoLog.w(
                    TAG,
                    "Cannot decrypt remote MetaTube config (envelopeVersion=${remoteResult.envelope.version}, usingPassphrase=${keyInfo.isPassphrase}); preserving remote ciphertext",
                )
                return
            }
            RemotePayloadResult.Absent, is RemotePayloadResult.Opened -> Unit
        }

        val remotePayload = (remoteResult as? RemotePayloadResult.Opened)?.payload
        val hasRemoteFile = remoteResult !is RemotePayloadResult.Absent
        val baseVersion = if (keyInfo.isPassphrase) VERSION_PASSPHRASE else VERSION_PASSWORD
        // 一旦远端已升级为 v2（独立口令信封），即便另一台设备凑巧用相同字符串作为登录密码解开，也绝不降级回 v1
        val outVersion = if (remoteResult is RemotePayloadResult.Opened) maxOf(baseVersion, remoteResult.envelope.version) else baseVersion
        val versionChanged = remoteResult is RemotePayloadResult.Opened && remoteResult.envelope.version != outVersion
        val urlDigest = digest(localUrl)
        val tokenDigest = digest(localToken)

        // 首次参与同步（last == null）时的时间戳优先级约定：
        // - 空值取 0L（最低优先级，不覆盖任何有效配置）；
        // - 非空旧值在远端已有有效配置时取 1L（让位于远端正整数时间戳，防止新登录设备的残留配置覆盖远端），
        //   远端尚无可读配置时取当前 stamp 以作为初始配置上传。
        fun entry(value: String, sha256: String, last: FieldDigest?): Entry = Entry(
            value,
            when {
                last == null -> if (value.isEmpty()) 0L else if (remotePayload != null) 1L else stamp
                last.sha256 == sha256 -> last.updatedAt
                else -> stamp
            },
        )
        fun merge(a: Entry, b: Entry?): Entry = if (b != null && b.updatedAt >= a.updatedAt) b else a
        val merged = Payload(
            merge(entry(localUrl, urlDigest, known?.url), remotePayload?.url),
            merge(entry(localToken, tokenDigest, known?.token), remotePayload?.token),
        )
        requireCurrent(account)
        val urlUpdated = merged.url.value != localUrl
        if (urlUpdated) preferences.setMetaTubeUrl(merged.url.value)
        requireCurrent(account)
        val tokenUpdated = merged.token.value != localToken
        if (tokenUpdated) preferences.setMetaTubeToken(merged.token.value)
        requireCurrent(account)
        lastSyncedPair = account to (merged.url.value to merged.token.value)
        val state = LocalState(
            FieldDigest(digest(merged.url.value), merged.url.updatedAt),
            FieldDigest(digest(merged.token.value), merged.token.updatedAt),
        )
        requireCurrent(account)
        cacheStore?.write(stateKey(account), json.encodeToString(LocalState.serializer(), state))
        val pushed = (merged != remotePayload || versionChanged) &&
            (hasRemoteFile || merged.url.value.isNotEmpty() || merged.token.value.isNotEmpty())
        if (pushed) {
            val salt = (remoteResult as? RemotePayloadResult.Opened)?.takeUnless { versionChanged }?.salt ?: cipher.newSalt()
            val key = keyFor(keyInfo.secret, salt, ITERATIONS)
            val sealed = cipher.seal(key, json.encodeToString(Payload.serializer(), merged).encodeToByteArray())
            val text = json.encodeToString(Envelope.serializer(), Envelope(outVersion, ITERATIONS, Base64.encode(salt), Base64.encode(sealed)))
            requireCurrent(account)
            remote.write(account, text, stamp)
        }
        _status.value = EncryptedSyncStatus.Unlocked
        PikoLog.d(
            TAG,
            "MetaTube config synced: remote=${if (remoteResult is RemotePayloadResult.Absent) "none" else "ok"}, " +
                "urlUpdated=$urlUpdated, tokenUpdated=$tokenUpdated, pushed=$pushed, version=$outVersion, elapsed=${now() - stamp} ms",
        )
    }

    private suspend fun openEnvelope(remoteText: String, secret: String): RemotePayloadResult {
        val envelope = runCatching { json.decodeFromString(Envelope.serializer(), remoteText) }
            .onFailure {
                PikoLog.w(TAG, "Remote MetaTube envelope is malformed (${it::class.simpleName}); skipping sync to preserve remote file")
            }
            .getOrNull() ?: return RemotePayloadResult.AbortCorruptOrUnsupported
        if (envelope.version !in VERSION_PASSWORD..MAX_VERSION) {
            PikoLog.w(TAG, "Remote MetaTube version ${envelope.version} outside supported $VERSION_PASSWORD..$MAX_VERSION; skipping sync")
            return RemotePayloadResult.AbortCorruptOrUnsupported
        }
        // 限制不可信远端的密钥派生开销；未来提高默认迭代次数时需同步调整上限
        if (envelope.iterations !in 1..ITERATIONS) {
            PikoLog.w(TAG, "Remote MetaTube KDF iterations (${envelope.iterations}) out of range 1..$ITERATIONS; skipping sync")
            return RemotePayloadResult.AbortCorruptOrUnsupported
        }
        val salt = runCatching { Base64.decode(envelope.salt) }
            .onFailure { PikoLog.w(TAG, "Remote MetaTube salt is not valid Base64; skipping sync") }
            .getOrNull() ?: return RemotePayloadResult.AbortCorruptOrUnsupported
        val sealed = runCatching { Base64.decode(envelope.data) }
            .onFailure { PikoLog.w(TAG, "Remote MetaTube ciphertext is not valid Base64; skipping sync") }
            .getOrNull() ?: return RemotePayloadResult.AbortCorruptOrUnsupported
        val plain = cipher.open(keyFor(secret, salt, envelope.iterations), sealed)
            ?: return RemotePayloadResult.KeyChanged(envelope)
        val payload = runCatching { json.decodeFromString(Payload.serializer(), plain.decodeToString(throwOnInvalidSequence = true)) }
            .onFailure { PikoLog.w(TAG, "Decrypted MetaTube payload is malformed (${it::class.simpleName}); skipping sync") }
            .getOrNull() ?: return RemotePayloadResult.AbortCorruptOrUnsupported
        return RemotePayloadResult.Opened(envelope, salt, payload)
    }

    private suspend fun digest(value: String): String {
        val digest = Digest("SHA-256")
        digest += value.encodeToByteArray()
        return digest.build().toHexString()
    }

    private suspend fun keyFor(secret: String, salt: ByteArray, iterations: Int): ByteArray {
        cachedKey?.takeIf { it.secret == secret && it.salt.contentEquals(salt) && it.iterations == iterations }?.let { return it.key }
        val key = withContext(Dispatchers.Default) { cipher.deriveKey(secret, salt, iterations) }
        cachedKey = CachedKey(secret, salt, iterations, key)
        return key
    }

    private fun stateKey(account: String) = "metatube-sync-${account.replace(UNSAFE_KEY_CHARS, "_")}.json"
    private class CachedKey(val secret: String, val salt: ByteArray, val iterations: Int, val key: ByteArray)
    @Serializable private data class Envelope(val version: Int, val iterations: Int, val salt: String, val data: String)
    @Serializable private data class Entry(val value: String, val updatedAt: Long)
    @Serializable private data class Payload(val url: Entry = Entry("", 0L), val token: Entry = Entry("", 0L))
    @Serializable private data class FieldDigest(val sha256: String, val updatedAt: Long)
    @Serializable private data class LocalState(val url: FieldDigest? = null, val token: FieldDigest? = null)

    private companion object {
        const val FILE_PREFIX = "metatube-"
        const val VERSION_PASSWORD = 1
        const val VERSION_PASSPHRASE = 2
        const val MAX_VERSION = VERSION_PASSPHRASE
        const val ITERATIONS = 600_000
        const val PUSH_DELAY_MS = 3_000L
        const val TAG = "MetaTubeSync"
        val UNSAFE_KEY_CHARS = Regex("""[^A-Za-z0-9._@-]""")
        val json = Json { ignoreUnknownKeys = true }
    }
}
