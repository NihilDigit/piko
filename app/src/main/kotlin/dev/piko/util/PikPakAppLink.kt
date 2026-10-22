package dev.piko.util

import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.state.extractLinks
import java.net.URI
import java.net.URLDecoder

/**
 * 官方「在 App 中打开」链接：https://toapp.mypikpak.com/toapp?deepLink=…
 *
 * PikPak 的 Telegram 机器人存完文件后发的就是这种链接，deepLink 是官方 App 的内部路由，
 * 形如 /drive/main_tab?tab=1&uid=…&from=other%2Fbot&result=success。
 *
 * 内部路由不对应 Piko 的导航，普通路由一律打开网盘；分享与添加磁力的路由留给添加链接面板。
 */
object PikPakAppLink {

    sealed interface Target {
        /** 网盘主界面。官方路由的 tab 参数对应它自己的底栏，Piko 的底栏不同，一律落到文件页。 */
        data object Drive : Target

        /** 带待添加链接或分享的官方路由，转交添加链接面板。 */
        data class Import(val text: String) : Target
    }

    private val TO_APP_HOSTS = setOf("toapp.mypikpak.com", "toapp.mypikpak.net", "toapp.pickpackapp.com")
    private val OFFICIAL_HOSTS = setOf("mypikpak.com", "mypikpak.net", "pikpak.me", "pikpakdrive.com", "pickpackapp.com")

    fun parse(link: String): Target? {
        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        val customScheme = uri.scheme.equals("pikpakapp", ignoreCase = true)
        val host = uri.host?.lowercase()
        val officialRoute = host?.removePrefix("www.") in OFFICIAL_HOSTS &&
            (uri.path.orEmpty().startsWith("/toapp") || uri.path.orEmpty().startsWith("/deeplink"))
        if (!customScheme && !(uri.scheme.equals("https", ignoreCase = true) &&
                (host in TO_APP_HOSTS || officialRoute))) return null

        val params = queryParams(uri.rawQuery)
        if (customScheme && "share_id" in params && InstantSheetState.findShareLink(link) != null) {
            return Target.Import(link)
        }
        val deepParams = params["deepLink"]?.let(::decode)?.substringAfter('?', "")?.let(::queryParams).orEmpty()
        val additions = listOfNotNull(
            params["add_url"],
            params["__add_url"],
            deepParams["add_url"],
            deepParams["__add_url"],
        )
        additions.firstNotNullOfOrNull { value ->
            val decoded = decode(value)
            decoded.takeIf { InstantSheetState.findShareLink(it) != null || extractLinks(it).any { link -> link.isMagnet } }
        }?.let { return Target.Import(it) }
        return Target.Drive
    }

    private fun queryParams(rawQuery: String?): Map<String, String> =
        rawQuery.orEmpty().split('&').filter { '=' in it }.associate { pair ->
            decode(pair.substringBefore('=')) to decode(pair.substringAfter('='))
        }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, Charsets.UTF_8) }.getOrDefault(value)
}
