package dev.piko.shared.sync

import dev.piko.data.auth.PikoUserPreferences
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

/** MetaTube 地址与令牌仅以账号密码派生的密钥加密后同步，本机同步账本只保存摘要与时刻。 */
class MetaTubeSync(
    private val clients: PikoClientProvider,
    private val remote: RemoteSettingsStore,
    private val preferences: PikoUserPreferences,
    private val cacheStore: PikoCacheStore?,
    private val passwordOf: suspend (String) -> String?,
    private val cipher: SyncCipher,
    private val scope: CoroutineScope,
    private val enabled: Flow<Boolean>,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    constructor(
        clients: PikoClientManager,
        driveRepo: PikoDriveRepository,
        preferences: PikoUserPreferences,
        cacheStore: PikoCacheStore?,
        cipher: SyncCipher,
        scope: CoroutineScope,
        enabled: Flow<Boolean>,
    ) : this(
        clients,
        DriveSettingsStore(clients, driveRepo, FILE_PREFIX, TAG),
        preferences,
        cacheStore,
        clients::savedPassword,
        cipher,
        scope,
        enabled,
    )

    private val lock = Mutex()
    private var cachedKey: CachedKey? = null
    private var lastSyncedPair: Pair<String, Pair<String, String>>? = null

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            combine(clients.currentClient, enabled) { client, on -> client?.account.takeIf { on } }
                .distinctUntilChanged()
                .collectLatest { account ->
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
        if (remoteText == null && localUrl.isEmpty() && localToken.isEmpty() && known == null) return
        val secret = passwordOf(account) ?: run {
            PikoLog.i(TAG, "Account password unavailable; skipping MetaTube sync for this round")
            return
        }
        requireCurrent(account)

        val remoteResult = if (remoteText == null) {
            RemotePayloadResult.Absent
        } else {
            openEnvelope(remoteText, secret)
        }
        if (remoteResult is RemotePayloadResult.AbortCorruptOrUnsupported) return
        if (remoteResult is RemotePayloadResult.KeyChanged) {
            PikoLog.w(TAG, "Failed to decrypt remote MetaTube config (account password may have changed); re-encrypting from local state")
        }

        val remotePayload = (remoteResult as? RemotePayloadResult.Opened)?.payload
        val hasRemoteFile = remoteResult !is RemotePayloadResult.Absent
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
        val pushed = merged != remotePayload && (hasRemoteFile || merged.url.value.isNotEmpty() || merged.token.value.isNotEmpty())
        if (pushed) {
            val salt = (remoteResult as? RemotePayloadResult.Opened)?.salt ?: cipher.newSalt()
            val key = keyFor(secret, salt, ITERATIONS)
            val sealed = cipher.seal(key, json.encodeToString(Payload.serializer(), merged).encodeToByteArray())
            val text = json.encodeToString(Envelope.serializer(), Envelope(VERSION, ITERATIONS, Base64.encode(salt), Base64.encode(sealed)))
            requireCurrent(account)
            remote.write(account, text, stamp)
        }
        PikoLog.d(
            TAG,
            "MetaTube config synced: remote=${when (remoteResult) {
                is RemotePayloadResult.Absent -> "none"
                is RemotePayloadResult.KeyChanged -> "key_changed"
                is RemotePayloadResult.Opened -> "ok"
                is RemotePayloadResult.AbortCorruptOrUnsupported -> "aborted"
            }}, urlUpdated=$urlUpdated, tokenUpdated=$tokenUpdated, pushed=$pushed, elapsed=${now() - stamp} ms",
        )
    }

    private suspend fun openEnvelope(remoteText: String, secret: String): RemotePayloadResult {
        val envelope = runCatching { json.decodeFromString(Envelope.serializer(), remoteText) }
            .onFailure {
                PikoLog.w(TAG, "Remote MetaTube envelope is malformed (${it::class.simpleName}); skipping sync to preserve remote file")
            }
            .getOrNull() ?: return RemotePayloadResult.AbortCorruptOrUnsupported
        if (envelope.version > VERSION) {
            PikoLog.w(TAG, "Remote MetaTube version ${envelope.version} is newer than supported $VERSION; skipping sync")
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
        const val VERSION = 1
        const val ITERATIONS = 600_000
        const val PUSH_DELAY_MS = 3_000L
        const val TAG = "MetaTubeSync"
        val UNSAFE_KEY_CHARS = Regex("""[^A-Za-z0-9._@-]""")
        val json = Json { ignoreUnknownKeys = true }
    }
}
