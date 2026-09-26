package dev.piko.shared.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class ProxyMode { SYSTEM, NONE, MANUAL }

enum class ProxyProtocol(val label: String) { HTTP("HTTP"), SOCKS("SOCKS5") }

/**
 * 应用内所有网络请求用的代理。[host] 与 [port] 只在 [ProxyMode.MANUAL] 下有意义，切走时照样保留，
 * 切回手动不必重填。
 */
@Serializable
data class ProxySetting(
    val mode: ProxyMode = ProxyMode.SYSTEM,
    val protocol: ProxyProtocol = ProxyProtocol.HTTP,
    val host: String = "",
    val port: Int = 0,
) {
    /** 手动模式下地址是否填全；没填全时按直连处理，而不是连向一个空地址。 */
    val isManualComplete: Boolean get() = host.isNotBlank() && port in 1..65535

    fun summary(): String = when (mode) {
        ProxyMode.SYSTEM -> "跟随系统"
        ProxyMode.NONE -> "不使用代理"
        ProxyMode.MANUAL -> if (isManualComplete) "${protocol.label} $host:$port" else "手动（未填写地址）"
    }

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** 偏好里存的 JSON。空串或读不懂时回到默认的跟随系统。 */
        fun decode(serialized: String): ProxySetting =
            serialized.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
                ?: ProxySetting()
    }
}
