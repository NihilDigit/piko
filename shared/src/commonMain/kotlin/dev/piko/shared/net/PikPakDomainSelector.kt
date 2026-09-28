package dev.piko.shared.net

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.log.PikoLog
import io.github.nihildigit.pikpak.DomainProbe
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakDomain
import io.github.nihildigit.pikpak.probeDomain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * PikPak 的 API 有四个根域名（mypikpak.com、mypikpak.net、pikpak.me、pikpakdrive.com），指向同一组服务器，令牌通用，
 * 直链也跟着根域名走（SDK 的 PikPakDomain 有实测）。各地运营商对不同域名的限速不同，换一个有时就快了。
 *
 * 用户可以固定一个，也可以交给这里自动挑：登录后各测一次，挑最快的。设置页显示每个根域名的延迟。
 * - 只在已登录之后换：别的根域名下的密码登录 SDK 没有实测过，刷新令牌测过。登录本身总是走 mypikpak.com。
 * - 自动时只在明显更快（快过 [SWITCH_RATIO]）才换。多数时候几个根域名的差别在噪声里，来回换没有意义。
 * - 比的是连接复用后的一次请求（DomainProbe.warmRequest）：首次请求含握手，正在用的根域名可能复用了 TLS 会话，显得便宜。
 * - 测速结果不存盘，每次启动重测：换了网络上次的结论就不作数了。
 */
class PikPakDomainSelector(
    private val clients: PikoClientManager,
    private val preferences: PikoUserPreferences,
    private val scope: CoroutineScope,
) {
    private val _probes = MutableStateFlow<Map<PikPakDomain, DomainProbe>>(emptyMap())

    /** 最近一次测速，按根域名。还没测过为空。 */
    val probes: StateFlow<Map<PikPakDomain, DomainProbe>> = _probes.asStateFlow()

    private val _probing = MutableStateFlow(false)
    val probing: StateFlow<Boolean> = _probing.asStateFlow()

    private val _active = MutableStateFlow<PikPakDomain?>(null)

    /** 眼下 API 请求用的根域名；未登录时为 null。 */
    val active: StateFlow<PikPakDomain?> = _active.asStateFlow()

    private val reprobes = MutableStateFlow(0)

    fun start() {
        // 测试注入的是 MockEngine，测不出东西，也不该往外发请求
        if (!clients.reachesRealNetwork) return
        scope.launch {
            combine(clients.currentClient, preferences.pikpakDomainFlow, reprobes) { client, choice, _ -> client to choice }
                .collectLatest { (client, choice) ->
                    if (client == null) {
                        _active.value = null
                        return@collectLatest
                    }
                    apply(client, PikPakDomain.entries.firstOrNull { it.root == choice })
                }
        }
    }

    /** 设置页的「重新测速」。 */
    fun probeAgain() {
        reprobes.value++
    }

    private suspend fun apply(client: PikPakClient, fixed: PikPakDomain?) {
        // 用户固定的先换过去再测，测速只为显示；不等测完，免得换了之后几秒内还走旧的
        if (fixed != null) client.domain = fixed
        _active.value = client.domain
        _probing.value = true
        val results = try {
            coroutineScope { PikPakDomain.entries.map { domain -> async { client.probeDomain(domain) } }.awaitAll() }
        } finally {
            _probing.value = false
        }
        _probes.value = results.associateBy { it.domain }
        val switched = fixed == null && switchToFastest(client, results)
        _active.value = client.domain
        val summary = results.joinToString("，") { "${it.domain.root} ${it.latencyText()}" }
        val how = when {
            fixed != null -> "固定用"
            switched -> "改用"
            else -> "沿用"
        }
        PikoLog.i(TAG, "根域名测速：$summary，$how ${client.domain.root}")
    }

    private fun switchToFastest(client: PikPakClient, results: List<DomainProbe>): Boolean {
        val usable = results.filter { it.usable && it.warmRequest != null }
        val best = usable.minByOrNull { it.warmRequest!! } ?: return false
        if (best.domain == client.domain) return false
        val current = usable.firstOrNull { it.domain == client.domain }?.warmRequest
        if (current != null && best.warmRequest!! >= current * SWITCH_RATIO) return false
        client.domain = best.domain
        return true
    }

    private fun DomainProbe.latencyText(): String =
        warmRequest?.takeIf { usable }?.let { "${it.inWholeMilliseconds} ms" } ?: "不可用"

    private companion object {
        const val TAG = "Domain"
        const val SWITCH_RATIO = 0.8
    }
}
