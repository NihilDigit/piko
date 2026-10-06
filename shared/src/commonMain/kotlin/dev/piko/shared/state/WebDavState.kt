package dev.piko.shared.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.log.failureText
import dev.piko.shared.log.logFailure
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.WebDavClient
import io.github.nihildigit.pikpak.WebDavSettings
import io.github.nihildigit.pikpak.createWebDavClient
import io.github.nihildigit.pikpak.deleteWebDavClient
import io.github.nihildigit.pikpak.getWebDav
import io.github.nihildigit.pikpak.renewWebDavPassword
import io.github.nihildigit.pikpak.setWebDavEnabled
import io.github.nihildigit.pikpak.updateWebDavClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 账号的 PikPak WebDAV：总开关与各应用的登录凭据，与官方客户端的设置页是同一份。
 *
 * 每次请求取当时的 client，结果回来时账号已换就丢掉，不写进状态：切换账号后若写入，
 * 这一页会列出上一个账号的用户名与密码。
 */
class WebDavState(
    private val clients: PikoClientProvider,
    private val scope: CoroutineScope,
) {
    /** 最近一次读到的设置，未读到时为 null。 */
    var settings by mutableStateOf<WebDavSettings?>(null)
        private set
    var isLoading by mutableStateOf(true)
        private set
    var isRefreshing by mutableStateOf(false)
        private set

    /** 上次读取失败的原因，成功后清空。[settings] 此时仍是旧数据。 */
    var loadError by mutableStateOf<String?>(null)
        private set

    /** 正在改动的应用，按 ID。界面据此禁用它的按钮，免得同一项连发几次。 */
    var busyIds by mutableStateOf<Set<Long>>(emptySet())
        private set
    var isToggling by mutableStateOf(false)
        private set
    var isCreating by mutableStateOf(false)
        private set

    /**
     * 刚添加或刚重置密码的应用，界面据此弹出它的凭据，看过后经 [dismissIssued] 清掉。
     * 列表里同样能看到密码，弹出来是为了让人当场填进要用它的应用。
     */
    var issued by mutableStateOf<IssuedCredentials?>(null)
        private set

    val webDavClients: List<WebDavClient> by derivedStateOf { settings?.clients.orEmpty() }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var loadJob: Job? = null

    /** 跟着当前账号：进入时读一次，账号换了清空再读。挂起到调用方的协程取消为止。 */
    suspend fun followAccount() {
        clients.currentClient.map { it?.account }.distinctUntilChanged().collect {
            loadJob?.cancel()
            settings = null
            issued = null
            busyIds = emptySet()
            isToggling = false
            isCreating = false
            isRefreshing = false
            loadError = null
            load()
        }
    }

    fun load(refresh: Boolean = false) {
        val client = clients.currentClient.value ?: return
        if (refresh) isRefreshing = true else isLoading = true
        loadJob?.cancel()
        loadJob = scope.launch {
            val result = runCatching { client.getWebDav() }.logFailure(TAG, "读取 WebDAV 设置失败")
            if (!isCurrent(client)) return@launch
            result
                .onSuccess {
                    // 入口只对会员显示，这里兜底：等级识别与 WebDAV 接口说法不一时，至少说清开关为何开不了
                    if (!it.isPremium && settings?.isPremium != false) _messages.tryEmit("WebDAV 仅限 PikPak 会员使用")
                    settings = it
                    loadError = null
                }
                .onFailure {
                    loadError = "读取 WebDAV 设置失败"
                    if (refresh || settings != null) _messages.tryEmit(failureText("刷新", it))
                }
            isLoading = false
            isRefreshing = false
        }
    }

    /** 先改开关再请求，失败时拨回。 */
    fun setEnabled(enabled: Boolean) {
        val client = clients.currentClient.value ?: return
        val before = settings ?: return
        if (isToggling) return
        isToggling = true
        settings = before.copy(enabled = enabled)
        scope.launch {
            val result = runCatching { client.setWebDavEnabled(enabled) }.logFailure(TAG, "切换 WebDAV 失败")
            if (!isCurrent(client)) return@launch
            isToggling = false
            result.onFailure {
                settings = settings?.copy(enabled = before.enabled)
                _messages.tryEmit(failureText(if (enabled) "开启" else "关闭", it))
            }
        }
    }

    fun create(name: String) {
        val client = clients.currentClient.value ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty() || isCreating) return
        isCreating = true
        scope.launch {
            val result = runCatching { client.createWebDavClient(trimmed) }.logFailure(TAG, "添加 WebDAV 应用失败")
            if (!isCurrent(client)) return@launch
            isCreating = false
            result
                .onSuccess { created ->
                    issued = IssuedCredentials(created, renewed = false)
                    load(refresh = true)
                }
                .onFailure { _messages.tryEmit(failureText("添加", it)) }
        }
    }

    /** 只改名称。「为媒体播放优化」不展示也不改：实测开关对列目录与读文件没有可观察影响，2026-10-06。 */
    fun rename(target: WebDavClient, name: String) {
        val newName = name.trim()
        if (newName.isEmpty() || newName == target.name) return
        mutate(
            target, action = "保存", logMessage = "修改 WebDAV 应用失败",
            request = { it.updateWebDavClient(target, name = newName) },
            apply = { _messages.tryEmit("已保存") },
        )
    }

    fun renewPassword(target: WebDavClient) {
        mutate(
            target, action = "重置密码", logMessage = "重置 WebDAV 密码失败",
            request = { it.renewWebDavPassword(target) },
            apply = { renewed ->
                settings = settings?.let { s -> s.copy(clients = s.clients.map { if (it.id == renewed.id) renewed else it }) }
                issued = IssuedCredentials(renewed, renewed = true)
            },
        )
    }

    fun delete(target: WebDavClient) {
        mutate(
            target, action = "删除", logMessage = "删除 WebDAV 应用失败",
            request = { it.deleteWebDavClient(target) },
            apply = {
                settings = settings?.let { s -> s.copy(clients = s.clients.filterNot { it.id == target.id }) }
                _messages.tryEmit("已删除「${target.name}」")
            },
        )
    }

    fun dismissIssued() {
        issued = null
    }

    /**
     * 改动一个应用，成功后重读一遍。重读会取消还在路上的上一次读取：那份列表发在改动之前，
     * 晚到的话会把刚删掉的一项放回去。
     */
    private fun <T> mutate(
        target: WebDavClient,
        action: String,
        logMessage: String,
        request: suspend (PikPakClient) -> T,
        apply: (T) -> Unit,
    ) {
        val client = clients.currentClient.value ?: return
        if (target.id in busyIds) return
        busyIds = busyIds + target.id
        scope.launch {
            val result = runCatching { request(client) }.logFailure(TAG, logMessage)
            if (!isCurrent(client)) return@launch
            busyIds = busyIds - target.id
            result
                .onSuccess {
                    apply(it)
                    load(refresh = true)
                }
                .onFailure { _messages.tryEmit(failureText(action, it)) }
        }
    }

    private fun isCurrent(client: PikPakClient) = clients.currentClient.value?.account == client.account

    class IssuedCredentials(val client: WebDavClient, val renewed: Boolean)

    private companion object {
        const val TAG = "WebDav"
    }
}
