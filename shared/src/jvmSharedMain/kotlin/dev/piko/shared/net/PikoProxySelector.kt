package dev.piko.shared.net

import dev.piko.shared.log.PikoLog
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 进程默认的 ProxySelector，按设置里的代理模式选路。
 *
 * 不给每个 HTTP 客户端单独配代理：SDK 的 API 与 CDN 客户端、图片加载、更新检查各建各的
 * OkHttpClient，其中 SDK 的两个在它内部，拿不到构造参数。OkHttp 建客户端时取的是
 * ProxySelector.getDefault()，此后每条新连接都来问一次 [select]，所以只要在任何客户端建出来
 * 之前装上它，改设置不必重建客户端；已经连着的连接要等空闲关闭后才换路。
 */
object PikoProxySelector : ProxySelector() {
    private data class SystemProxyKey(
        val scheme: String,
        val host: String,
        val port: Int,
    )

    private class CachedSystemProxies(
        val proxies: List<Proxy>,
        val cachedAtNanos: Long,
    )

    @Volatile
    private var setting = ProxySetting()

    /** 装之前的默认选择器，跟随系统时交给它。桌面端要先打开 java.net.useSystemProxies 它才读系统设置。 */
    private var system: ProxySelector? = null

    @Volatile
    private var initialized = false

    private val systemCache = ConcurrentHashMap<SystemProxyKey, CachedSystemProxies>()
    private val cacheInvalidations = AtomicLong(0L)

    internal var systemSelectorOverride: ProxySelector? = null
    internal var nanoClock: () -> Long = System::nanoTime

    /** 在建任何网络客户端之前调用，[initial] 是同步读出的当前设置，免得最早的几个请求先按默认值走。 */
    fun install(initial: ProxySetting) {
        val firstInstall = !initialized
        if (system == null) {
            system = getDefault()
            setDefault(this)
        }
        initialized = true
        applyInternal(initial, forceLog = firstInstall)
    }

    fun apply(setting: ProxySetting) {
        applyInternal(setting, forceLog = false)
    }

    private fun applyInternal(next: ProxySetting, forceLog: Boolean) {
        if (!forceLog && next == this.setting && initialized) return
        this.setting = next
        cacheInvalidations.incrementAndGet()
        systemCache.clear()
        PikoLog.i(TAG, "Proxy mode set to ${next.logDescription()}")
    }

    override fun select(uri: URI): List<Proxy> {
        // 本机回环地址（127.x.x.x / localhost / ::1）永远直连：播放器读取的本地回环流代理不经外部代理转发
        if (isLoopback(uri.host)) return NO_PROXY
        val current = setting
        return when (current.mode) {
            ProxyMode.SYSTEM -> selectSystemProxies(uri)
            ProxyMode.NONE -> NO_PROXY
            ProxyMode.MANUAL -> if (current.isManualComplete) {
                val type = if (current.protocol == ProxyProtocol.SOCKS) Proxy.Type.SOCKS else Proxy.Type.HTTP
                // 不在这里解析主机名：交给连接时解析，代理地址写的是域名也不会在选路时阻塞
                listOf(Proxy(type, InetSocketAddress.createUnresolved(current.host.trim(), current.port)))
            } else {
                NO_PROXY
            }
        }
    }

    override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) {
        // OkHttp 在走代理读取响应体中途被调用方主动取消（如播放器 Seek 或死线切断）时，
        // 也会把 Socket closed / Canceled / unexpected end of stream 回调到这里；它们并非代理不可达。
        val msg = error.message.orEmpty()
        val benignAbort = msg.contains("Socket closed", ignoreCase = true) ||
            msg.contains("Socket is closed", ignoreCase = true) ||
            msg.contains("Canceled", ignoreCase = true) ||
            msg.contains("unexpected end of stream", ignoreCase = true) ||
            msg.contains("StreamResetException", ignoreCase = true)
        if (!benignAbort) {
            PikoLog.w(TAG, "Proxy connection to ${uri.host ?: "?"} via $address failed", error)
        }
        if (setting.mode == ProxyMode.SYSTEM) {
            cacheInvalidations.incrementAndGet()
            cacheKeyOf(uri)?.let(systemCache::remove)
            (systemSelectorOverride ?: system)?.connectFailed(uri, address, error)
        }
    }

    private fun selectSystemProxies(uri: URI): List<Proxy> {
        val selector = systemSelectorOverride ?: system ?: return NO_PROXY
        val key = cacheKeyOf(uri) ?: return selector.select(uri)?.takeIf { it.isNotEmpty() } ?: NO_PROXY
        val now = nanoClock()
        systemCache[key]?.let { cached ->
            if (now - cached.cachedAtNanos in 0L until SYSTEM_CACHE_TTL_NANOS) {
                return cached.proxies
            }
        }
        val stampBefore = cacheInvalidations.get()
        val resolved = selector.select(uri)
            ?.takeIf { it.isNotEmpty() }
            ?.let { Collections.unmodifiableList(ArrayList(it)) }
            ?: NO_PROXY
        if (cacheInvalidations.get() == stampBefore) {
            val storedAt = nanoClock()
            if (systemCache.size >= SYSTEM_CACHE_MAX_ENTRIES) {
                evictExpiredSystemCache(storedAt)
            }
            systemCache[key] = CachedSystemProxies(resolved, storedAt)
        }
        return resolved
    }

    private fun evictExpiredSystemCache(nowNanos: Long) {
        val iterator = systemCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowNanos - entry.value.cachedAtNanos !in 0L until SYSTEM_CACHE_TTL_NANOS) {
                iterator.remove()
            }
        }
        if (systemCache.size >= SYSTEM_CACHE_MAX_ENTRIES) {
            systemCache.clear()
        }
    }

    private fun cacheKeyOf(uri: URI): SystemProxyKey? {
        val host = uri.host ?: return null
        return SystemProxyKey(
            scheme = uri.scheme?.lowercase().orEmpty(),
            host = host.lowercase(),
            port = uri.port,
        )
    }

    // 只认字面的回环地址：127.x.x.x 要四段都是数字。只看前缀的话，127.example.com 这种域名也会绕过用户设的代理直连
    private fun isLoopback(host: String?): Boolean =
        host == null || host.equals("localhost", ignoreCase = true) || host == "::1" || host == "[::1]" ||
            LOOPBACK_V4.matches(host)

    private const val TAG = "Proxy"
    private const val SYSTEM_CACHE_TTL_NANOS = 5_000_000_000L
    private const val SYSTEM_CACHE_MAX_ENTRIES = 256
    private val LOOPBACK_V4 = Regex("""127\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}""")
    private val NO_PROXY = listOf(Proxy.NO_PROXY)
}
