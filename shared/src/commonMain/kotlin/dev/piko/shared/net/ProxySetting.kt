package dev.piko.shared.net

import dev.piko.shared.i18n.tr

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
        ProxyMode.SYSTEM -> tr("跟随系统", "System", "システムに従う", "시스템 설정 따르기")
        ProxyMode.NONE -> tr("不使用代理", "No proxy", "プロキシを使用しない", "프록시 사용 안 함")
        ProxyMode.MANUAL -> if (isManualComplete) "${protocol.label} $host:$port" else tr("手动（未填写地址）", "Manual (no address)", "手動（アドレス未入力）", "수동 (주소 없음)")
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
