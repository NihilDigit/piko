package dev.piko.desktop

import dev.piko.shared.log.PikoLog
import dev.piko.ui.platform.LinkAssociation
import dev.piko.ui.platform.LinkAssociationState
import java.lang.foreign.MemorySegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * macOS 上把 magnet: 链接与 .torrent 文件的默认打开方式设为 Piko，经 NSWorkspace 直接改，不必去系统设置里选。
 * 两者都要 Info.plist 先声明（CFBundleURLTypes 与 CFBundleDocumentTypes，见 desktopApp/build.gradle.kts），
 * 系统才肯把 Piko 设成默认。
 *
 * 用 macOS 12 起的 setDefaultApplicationAtURL:toOpenURLsWithScheme: 与 toOpenContentType:，不用
 * LSSetDefaultHandlerForURLScheme 一族：后者已弃用，而且按 bundle ID 认，装着两份（测试包、改过名的包）时
 * 设成的未必是眼下这一份；前者按应用所在的路径认。它只有 Objective-C 接口，经 objc_msgSend 调；
 * 完成回调是可空的 block，传 nil，结果异步生效，设完轮询读回来的状态。
 *
 * 取消关联不做，见 [LinkAssociation.canUnregister]。
 * 开发版（gradle run）的主 bundle 是 JDK 的 java，不是 .app，报 Unavailable。
 */
internal object MacLinkAssociation : LinkAssociation {
    private const val TAG = "LinkAssociation"
    private const val MAGNET_SCHEME = "magnet"
    // 读「谁打开 magnet」要一条具体的链接，内容无所谓
    private const val PROBE_MAGNET = "magnet:?xt=urn:btih:0000000000000000000000000000000000000000"
    // Transmission 导出的种子类型，别的 BitTorrent 客户端也认它；Info.plist 里以 UTImportedTypeDeclarations 声明一份，
    // 没装任何 BT 客户端的机器上也有这个类型
    private const val TORRENT_TYPE = "org.bittorrent.torrent"

    override val needsSystemConfirmation: Boolean = false
    override val canUnregister: Boolean = false

    override suspend fun state(): LinkAssociationState = withContext(Dispatchers.IO) {
        runCatching { MacObjc.withPool { readState() } }
            .onFailure { PikoLog.w(TAG, "读取默认打开方式失败", it) }
            .getOrDefault(LinkAssociationState.Unavailable)
    }

    override suspend fun register(): Boolean = withContext(Dispatchers.IO) {
        val requested = runCatching {
            MacObjc.withPool {
                val app = ownAppUrl() ?: return@withPool false
                val workspace = MacObjc.send(MacObjc.cls("NSWorkspace"), "sharedWorkspace")
                MacObjc.sendVoid(workspace, "setDefaultApplicationAtURL:toOpenURLsWithScheme:completionHandler:", app, MacObjc.string(MAGNET_SCHEME), MemorySegment.NULL)
                MacObjc.sendVoid(workspace, "setDefaultApplicationAtURL:toOpenContentType:completionHandler:", app, torrentType(), MemorySegment.NULL)
                true
            }
        }.onFailure { PikoLog.w(TAG, "设为默认打开方式失败", it) }.getOrDefault(false)
        if (!requested) return@withContext false
        // 回调传了 nil，改动在系统那边异步落定。等它读回来是 Default，最多几秒
        repeat(SETTLE_POLLS) {
            if (state() == LinkAssociationState.Default) return@withContext true
            delay(SETTLE_POLL_MILLIS)
        }
        PikoLog.w(TAG, "设为默认打开方式后读回来的仍不是 Piko")
        false
    }

    override suspend fun unregister(): Boolean = false

    private fun readState(): LinkAssociationState {
        val own = ownAppUrl()?.let(MacObjc::pathOf) ?: return LinkAssociationState.Unavailable
        val workspace = MacObjc.send(MacObjc.cls("NSWorkspace"), "sharedWorkspace")
        val probe = MacObjc.send(MacObjc.cls("NSURL"), "URLWithString:", MacObjc.string(PROBE_MAGNET))
        val handlers = listOf(
            MacObjc.send(workspace, "URLForApplicationToOpenURL:", probe),
            MacObjc.send(workspace, "URLForApplicationToOpenContentType:", torrentType()),
        ).map { url -> url.takeIf { it != MemorySegment.NULL }?.let(MacObjc::pathOf) }
        return if (handlers.all { it == own }) LinkAssociationState.Default else LinkAssociationState.NotDefault
    }

    /** 眼下运行的这个 .app；不在 .app 里（开发版）时为 null。 */
    private fun ownAppUrl(): MemorySegment? {
        val bundle = MacObjc.send(MacObjc.cls("NSBundle"), "mainBundle")
        if (MacObjc.send(bundle, "bundleIdentifier") == MemorySegment.NULL) return null
        val url = MacObjc.send(bundle, "bundleURL")
        return url.takeIf { it != MemorySegment.NULL && MacObjc.pathOf(it).endsWith(".app") }
    }

    private fun torrentType(): MemorySegment {
        MacObjc.loadFramework("UniformTypeIdentifiers")
        val type = MacObjc.send(MacObjc.cls("UTType"), "typeWithIdentifier:", MacObjc.string(TORRENT_TYPE))
        check(type != MemorySegment.NULL) { "系统不认得 $TORRENT_TYPE" }
        return type
    }

    private const val SETTLE_POLLS = 20
    private const val SETTLE_POLL_MILLIS = 250L
}
