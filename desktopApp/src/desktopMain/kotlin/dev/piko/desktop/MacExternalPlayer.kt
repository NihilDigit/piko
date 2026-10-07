package dev.piko.desktop

import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.ui.platform.ExternalVideoPlayer
import java.lang.foreign.MemorySegment
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * macOS 上把视频地址交给用户为这类视频选的默认应用。
 *
 * http 地址交给 NSWorkspace openURL: 或不带 -a 的 open 会进默认浏览器，所以先按扩展名查默认应用，
 * 再以 `open -a <应用> <地址>` 指定由它打开。查询用 macOS 11 起的 UTType typeWithFilenameExtension: 与
 * macOS 12 起的 NSWorkspace URLForApplicationToOpenContentType:，与包的最低版本 12.0 相符，不必分版本。
 * 交出地址没有用 openURLs:withApplicationAtURL:configuration:completionHandler:：要知道成败就得传一个
 * Objective-C block，经 FFM 手搓 block 结构与 upcall，真机未验证时写错会直接崩掉 JVM；open -a 走的是
 * 同一条 LaunchServices 路径（给应用发 GetURL 事件或 application:openURLs:），失败时退出码非零。
 *
 * 查到的应用就是默认浏览器（某些浏览器声明能打开视频）、或查不到时，退到 public.mpeg-4 的默认应用，
 * 仍是浏览器或没有就报失败。.ts 不按扩展名查，理由同 WindowsExternalPlayer：它也是 TypeScript 的扩展名，
 * 装了代码编辑器的机器上多半归编辑器。
 *
 * 各播放器能否接下 http 地址（未在真机验证，依据为各自源码与 Info.plist）：
 * mpv.app 的 Info.plist 声明了 http、https，application:openURLs: 把非文件 URL 原样（先做一次百分号解码，
 * 代理地址只含 ASCII，无影响）交给播放队列；IINA 注册了 GetURL 事件，scheme 不是 iina 的当作网址播放；
 * VLC 处理 GetURL 事件，但设置里关了 macosx-autoplay 时只入队不播。QuickTime Player 不支持 mkv，
 * 系统默认状态下 .mkv 查不到应用而退到 .mp4 的 QuickTime，它会报格式不支持；它能否打开 http 地址、能否拖动未查到依据。
 *
 * 默认应用的查询由包冒烟的自检（SelfTest 的 external-player）在 macos-15 runner 上跑过；
 * 真的交给播放器打开、播放与拖动只能在 Mac 上手测。
 */
internal object MacExternalPlayer : ExternalVideoPlayer {
    private const val TAG = "ExternalPlayer"
    private const val FALLBACK_TYPE = "public.mpeg-4"
    // 查「谁打开 http」要一条具体的地址，内容无所谓
    private const val BROWSER_PROBE = "http://127.0.0.1/"
    private const val LAUNCH_TIMEOUT_SECONDS = 15L

    private val untrustedExtensions = setOf("ts")

    override suspend fun open(url: String, fileName: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val app = playerFor(fileName)
            if (app == null) {
                PikoLog.w(TAG, "找不到能打开此类视频的默认应用（扩展名 ${extensionOf(fileName) ?: "无"}）")
                return@runCatching false
            }
            launch(app, url)
        }.logFailure(TAG, "启动外部播放器失败").getOrDefault(false)
    }

    /** 该文件该交给的应用所在路径，查不到为 null。只读 LaunchServices，不打开任何东西，自检也调它。 */
    internal fun playerFor(fileName: String): String? = MacObjc.withPool {
        MacObjc.loadFramework("UniformTypeIdentifiers")
        val workspace = MacObjc.send(MacObjc.cls("NSWorkspace"), "sharedWorkspace")
        val probe = MacObjc.send(MacObjc.cls("NSURL"), "URLWithString:", MacObjc.string(BROWSER_PROBE))
        val browser = pathOrNull(MacObjc.send(workspace, "URLForApplicationToOpenURL:", probe))

        fun defaultAppFor(type: MemorySegment): String? {
            if (type == MemorySegment.NULL) return null
            return pathOrNull(MacObjc.send(workspace, "URLForApplicationToOpenContentType:", type))
                ?.takeIf { it != browser }
        }

        val utType = MacObjc.cls("UTType")
        val byExtension = extensionOf(fileName)?.takeIf { it !in untrustedExtensions }?.let {
            defaultAppFor(MacObjc.send(utType, "typeWithFilenameExtension:", MacObjc.string(it)))
        }
        byExtension ?: defaultAppFor(MacObjc.send(utType, "typeWithIdentifier:", MacObjc.string(FALLBACK_TYPE)))
    }

    private fun launch(app: String, url: String): Boolean {
        val process = ProcessBuilder("open", "-a", app, url)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            // stderr 会复述地址，不进日志
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        // open 在 LaunchServices 接下请求后即退出，冷启动的应用也只要几秒。超时视为仍在启动，不当失败
        if (!process.waitFor(LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return true
        val exitCode = process.exitValue()
        if (exitCode != 0) PikoLog.w(TAG, "open -a 退出码 $exitCode")
        return exitCode == 0
    }

    private fun extensionOf(fileName: String): String? =
        fileName.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }

    private fun pathOrNull(url: MemorySegment): String? =
        url.takeIf { it != MemorySegment.NULL }?.let(MacObjc::pathOf)
}
