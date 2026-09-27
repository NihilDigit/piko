package dev.piko.desktop.winrt

import dev.piko.shared.log.logFailure
import dev.piko.ui.platform.ExternalVideoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把视频地址交给用户为这类视频选的默认播放器。
 *
 * http 地址交给 ShellExecute 会进浏览器，所以按扩展名向系统查「打开」命令，把地址代入 %1 后自己启动。
 * AssocQueryString 遵从用户在设置里选的默认应用（UserChoice），本机实测 .mp4 与 .mkv 返回 mpv.net，
 * .rmvb 返回 PotPlayer，与资源管理器双击的结果一致。
 *
 * 没有采用临时 .m3u 交给系统打开：多数播放器能解析，但用户改了视频的默认播放器，.m3u 往往仍归系统
 * 自带的「媒体播放器」。本机即是如此，.mp4 归 mpv.net，.m3u 归 Microsoft.ZuneMusic，视频会进
 * 一个用户没选的播放器，还要另管临时文件的清理。
 *
 * 默认程序是商店应用时，注册表里只有 DelegateExecute，没有可代入的命令行，查询回
 * ERROR_NO_ASSOCIATION（本机 .m3u 即是）。这时退到 .mp4 的关联，仍没有就报失败。
 */
internal object WindowsExternalPlayer : ExternalVideoPlayer {
    private const val FALLBACK_EXTENSION = ".mp4"

    // .ts 常被代码编辑器认作 TypeScript，按它查到的多半不是播放器
    private val untrustedExtensions = setOf(".ts")

    override suspend fun open(url: String, fileName: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val extension = fileName.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }?.let { ".$it" }
            val template = extension?.takeIf { it !in untrustedExtensions }?.let { openCommandOf(it) }
                ?: openCommandOf(FALLBACK_EXTENSION)
                ?: return@runCatching false
            ProcessBuilder(commandLineWithUrl(template, url))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            true
        }.logFailure("ExternalPlayer", "启动外部播放器失败").getOrDefault(false)
    }
}

/**
 * 把注册表里的命令行模板拆成参数，并代入地址。
 *
 * 模板形如 "C:\...\mpvnet.exe" "%1" 或 "...\wmplayer.exe" /prefetch:6 /Open "%L"。
 * %1、%L、%V 都代表目标，换成地址；其余占位符（%*、%2 之类、%I）在这里没有对应物，整项去掉。
 * 模板里没有占位符时把地址追加在末尾，与 Shell 的做法相同。
 * 拆分只认引号与空白：注册表里的播放器命令不含转义引号，不必完整实现 CommandLineToArgvW。
 */
internal fun commandLineWithUrl(template: String, url: String): List<String> {
    val tokens = splitCommandLine(template)
    var substituted = false
    val args = tokens.mapNotNull { token ->
        val replaced = targetPlaceholder.replace(token) {
            substituted = true
            Regex.escapeReplacement(url)
        }
        replaced.takeUnless { otherPlaceholder.matches(it) }
    }
    return if (substituted) args else args + url
}

private val targetPlaceholder = Regex("%[1LlVv]")
private val otherPlaceholder = Regex("%[*0-9Ii~]")

private fun splitCommandLine(command: String): List<String> {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    var hasToken = false
    for (char in command) {
        when {
            char == '"' -> {
                inQuotes = !inQuotes
                hasToken = true
            }
            char.isWhitespace() && !inQuotes -> {
                if (hasToken) tokens += current.toString()
                current.clear()
                hasToken = false
            }
            else -> {
                current.append(char)
                hasToken = true
            }
        }
    }
    if (hasToken) tokens += current.toString()
    return tokens
}
