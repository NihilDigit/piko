package dev.piko.shared.net

import dev.piko.shared.log.PikoLog
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * 进程默认的 ProxySelector，按设置里的代理模式选路。
 *
 * 不给每个 HTTP 客户端单独配代理：SDK 的 API 与 CDN 客户端、图片加载、更新检查各建各的
 * OkHttpClient，其中 SDK 的两个在它内部，拿不到构造参数。OkHttp 建客户端时取的是
 * ProxySelector.getDefault()，此后每条新连接都来问一次 [select]，所以只要在任何客户端建出来
 * 之前装上它，改设置不必重建客户端；已经连着的连接要等空闲关闭后才换路。
 */
object PikoProxySelector : ProxySelector() {
    @Volatile
    private var setting = ProxySetting()

    /** 装之前的默认选择器，跟随系统时交给它。桌面端要先打开 java.net.useSystemProxies 它才读系统设置。 */
    private var system: ProxySelector? = null

    /** 在建任何网络客户端之前调用，[initial] 是同步读出的当前设置，免得最早的几个请求先按默认值走。 */
    fun install(initial: ProxySetting) {
        if (system == null) {
            system = getDefault()
            setDefault(this)
        }
        apply(initial)
    }

    fun apply(setting: ProxySetting) {
        if (setting == this.setting && system != null) return
        this.setting = setting
        PikoLog.i("Proxy", "代理：${setting.summary()}")
    }

    override fun select(uri: URI): List<Proxy> {
        // 本机回环代理与局域网里的自己永远直连：播放器读的就是 127.0.0.1
        if (isLoopback(uri.host)) return NO_PROXY
        val current = setting
        return when (current.mode) {
            ProxyMode.SYSTEM -> system?.select(uri)?.takeIf { it.isNotEmpty() } ?: NO_PROXY
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
        PikoLog.w("Proxy", "经代理 $address 连接 ${uri.host} 失败", error)
        if (setting.mode == ProxyMode.SYSTEM) system?.connectFailed(uri, address, error)
    }

    private fun isLoopback(host: String?): Boolean =
        host == null || host == "localhost" || host.startsWith("127.") || host == "::1" || host == "[::1]"

    private val NO_PROXY = listOf(Proxy.NO_PROXY)
}
