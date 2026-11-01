package dev.piko.shared.sync

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.data.PikoCacheStore
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.log.PikoLog
import io.ktor.util.Digest
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
import kotlin.io.encoding.Base64
import kotlin.time.Clock

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
    ) : this(clients, DriveSettingsStore(clients, driveRepo, FILE_PREFIX), preferences, cacheStore,
        clients::savedPassword, cipher, scope, enabled)

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
        } catch (_: Exception) {
            // 异常消息可能包含输入或远端明文，不把异常交给日志。
            PikoLog.w(TAG, "MetaTube 加密同步失败")
            false
        }
    }

    private fun requireCurrent(account: String) {
        check(clients.currentClient.value?.account == account) { "同步途中切换账号" }
    }

    private suspend fun sync(account: String) {
        val stamp = now()
        requireCurrent(account)
        val localUrl = preferences.metaTubeUrlFlow.first()
        requireCurrent(account)
        val localToken = preferences.metaTubeTokenFlow.first()
        requireCurrent(account)
        val known = cacheStore?.read(stateKey(account))?.let {
            runCatching { json.decodeFromString(LocalState.serializer(), it) }.getOrNull()
        }
        requireCurrent(account)
        val remoteText = remote.read(account)
        requireCurrent(account)
        if (remoteText == null && localUrl.isEmpty() && localToken.isEmpty() && known == null) return
        val secret = passwordOf(account) ?: return
        requireCurrent(account)
        val envelope = remoteText?.let { runCatching { json.decodeFromString(Envelope.serializer(), it) }.getOrNull() }
        if (envelope != null && envelope.version > VERSION) return
        val remotePayload = envelope?.let { open(it, secret) }
        val urlDigest = digest(localUrl)
        val tokenDigest = digest(localToken)
        fun entry(value: String, sha256: String, last: FieldDigest?): Entry = Entry(value, when {
            last == null -> if (value.isEmpty()) 0L else if (remotePayload != null) 1L else stamp
            last.sha256 == sha256 -> last.updatedAt
            else -> stamp
        })
        fun merge(a: Entry, b: Entry?): Entry = if (b != null && b.updatedAt >= a.updatedAt) b else a
        val merged = Payload(
            merge(entry(localUrl, urlDigest, known?.url), remotePayload?.url),
            merge(entry(localToken, tokenDigest, known?.token), remotePayload?.token),
        )
        requireCurrent(account)
        if (merged.url.value != localUrl) preferences.setMetaTubeUrl(merged.url.value)
        requireCurrent(account)
        if (merged.token.value != localToken) preferences.setMetaTubeToken(merged.token.value)
        requireCurrent(account)
        lastSyncedPair = account to (merged.url.value to merged.token.value)
        val state = LocalState(
            FieldDigest(digest(merged.url.value), merged.url.updatedAt),
            FieldDigest(digest(merged.token.value), merged.token.updatedAt),
        )
        requireCurrent(account)
        cacheStore?.write(stateKey(account), json.encodeToString(LocalState.serializer(), state))
        if (merged != remotePayload && (envelope != null || merged.url.value.isNotEmpty() || merged.token.value.isNotEmpty())) {
            val salt = envelope?.takeIf { remotePayload != null }?.salt?.let(Base64::decode) ?: cipher.newSalt()
            val key = keyFor(secret, salt, ITERATIONS)
            val sealed = cipher.seal(key, json.encodeToString(Payload.serializer(), merged).encodeToByteArray())
            val text = json.encodeToString(Envelope.serializer(), Envelope(VERSION, ITERATIONS, Base64.encode(salt), Base64.encode(sealed)))
            requireCurrent(account)
            remote.write(account, text, stamp)
        }
    }

    private suspend fun open(envelope: Envelope, secret: String): Payload? {
        // 限制不可信远端的派生代价；未来提高迭代次数时需同步调整上限。
        if (envelope.iterations !in 1..ITERATIONS) return null
        val salt = runCatching { Base64.decode(envelope.salt) }.getOrNull() ?: return null
        val sealed = runCatching { Base64.decode(envelope.data) }.getOrNull() ?: return null
        val plain = cipher.open(keyFor(secret, salt, envelope.iterations), sealed) ?: return null
        return runCatching { json.decodeFromString(Payload.serializer(), plain.decodeToString()) }.getOrNull()
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
