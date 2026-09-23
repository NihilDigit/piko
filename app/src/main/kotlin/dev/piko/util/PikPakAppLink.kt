package dev.piko.util

import java.net.URI
import java.net.URLDecoder

/**
 * 官方「在 App 中打开」链接：https://toapp.mypikpak.com/toapp?deepLink=…
 *
 * PikPak 的 Telegram 机器人存完文件后发的就是这种链接，deepLink 是官方 App 的内部路由，
 * 形如 /drive/main_tab?tab=1&uid=…&from=other%2Fbot&result=success。
 *
 * deepLink 的值被编码了两层（%252F 解一层是 %2F，再解一层才是 /），所以 query 解码之后
 * 还要再解一次。路由里目前只用到路径：tab 指官方 App 自己的底栏，uid 与当前账号不符时
 * 也只能照常打开网盘。
 */
object PikPakAppLink {

    sealed interface Target {
        /** 网盘主界面。官方路由的 tab 参数对应它自己的底栏，Piko 的底栏不同，一律落到文件页。 */
        data object Drive : Target
    }

    private val HOSTS = setOf("toapp.mypikpak.com", "toapp.mypikpak.net")

    fun parse(link: String): Target? {
        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host !in HOSTS) return null
        val route = queryParams(uri.rawQuery)["deepLink"]?.let(::decode) ?: return null
        val path = route.substringBefore('?')
        return when {
            path.startsWith("/drive") -> Target.Drive
            else -> null
        }
    }

    private fun queryParams(rawQuery: String?): Map<String, String> =
        rawQuery.orEmpty().split('&').filter { '=' in it }.associate { pair ->
            decode(pair.substringBefore('=')) to decode(pair.substringAfter('='))
        }

    private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8)
}
