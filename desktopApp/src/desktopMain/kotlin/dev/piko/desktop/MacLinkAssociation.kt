package dev.piko.desktop

import dev.piko.shared.log.PikoLog
import dev.piko.ui.platform.LinkAssociation
import dev.piko.ui.platform.LinkAssociationState
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.invoke.MethodHandle
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
        runCatching { Objc.withPool { readState() } }
            .onFailure { PikoLog.w(TAG, "读取默认打开方式失败", it) }
            .getOrDefault(LinkAssociationState.Unavailable)
    }

    override suspend fun register(): Boolean = withContext(Dispatchers.IO) {
        val requested = runCatching {
            Objc.withPool {
                val app = ownAppUrl() ?: return@withPool false
                val workspace = Objc.send(Objc.cls("NSWorkspace"), "sharedWorkspace")
                Objc.sendVoid(workspace, "setDefaultApplicationAtURL:toOpenURLsWithScheme:completionHandler:", app, Objc.string(MAGNET_SCHEME), MemorySegment.NULL)
                Objc.sendVoid(workspace, "setDefaultApplicationAtURL:toOpenContentType:completionHandler:", app, torrentType(), MemorySegment.NULL)
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
        val own = ownAppUrl()?.let(::pathOf) ?: return LinkAssociationState.Unavailable
        val workspace = Objc.send(Objc.cls("NSWorkspace"), "sharedWorkspace")
        val probe = Objc.send(Objc.cls("NSURL"), "URLWithString:", Objc.string(PROBE_MAGNET))
        val handlers = listOf(
            Objc.send(workspace, "URLForApplicationToOpenURL:", probe),
            Objc.send(workspace, "URLForApplicationToOpenContentType:", torrentType()),
        ).map { url -> url.takeIf { it != MemorySegment.NULL }?.let(::pathOf) }
        return if (handlers.all { it == own }) LinkAssociationState.Default else LinkAssociationState.NotDefault
    }

    /** 眼下运行的这个 .app；不在 .app 里（开发版）时为 null。 */
    private fun ownAppUrl(): MemorySegment? {
        val bundle = Objc.send(Objc.cls("NSBundle"), "mainBundle")
        if (Objc.send(bundle, "bundleIdentifier") == MemorySegment.NULL) return null
        val url = Objc.send(bundle, "bundleURL")
        return url.takeIf { it != MemorySegment.NULL && pathOf(it).endsWith(".app") }
    }

    private fun torrentType(): MemorySegment {
        Objc.loadFramework("UniformTypeIdentifiers")
        val type = Objc.send(Objc.cls("UTType"), "typeWithIdentifier:", Objc.string(TORRENT_TYPE))
        check(type != MemorySegment.NULL) { "系统不认得 $TORRENT_TYPE" }
        return type
    }

    /** NSURL 的文件路径，去掉符号链接与结尾的斜杠，两个 URL 才比得出是不是同一个应用。 */
    private fun pathOf(url: MemorySegment): String {
        val resolved = Objc.send(url, "URLByResolvingSymlinksInPath")
        return Objc.kotlinString(Objc.send(resolved, "path")).trimEnd('/')
    }

    private const val SETTLE_POLLS = 20
    private const val SETTLE_POLL_MILLIS = 250L

    /**
     * Objective-C 运行时的最小封装。objc_msgSend 在 arm64 上不是变参函数，必须按每个方法的真实参数签名调，
     * 所以按参数个数各取一个句柄；这里用到的参数与返回值都是指针。
     */
    private object Objc {
        private val linker = Linker.nativeLinker()
        private val libobjc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", Arena.global())

        private fun handle(name: String, descriptor: FunctionDescriptor): MethodHandle =
            linker.downcallHandle(libobjc.find(name).orElseThrow(), descriptor)

        private val getClass = handle("objc_getClass", FunctionDescriptor.of(ADDRESS, ADDRESS))
        private val registerName = handle("sel_registerName", FunctionDescriptor.of(ADDRESS, ADDRESS))
        private val poolPush = handle("objc_autoreleasePoolPush", FunctionDescriptor.of(ADDRESS))
        private val poolPop = handle("objc_autoreleasePoolPop", FunctionDescriptor.ofVoid(ADDRESS))
        private val msgSend = libobjc.find("objc_msgSend").orElseThrow()
        private val send0 = linker.downcallHandle(msgSend, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))
        private val send1 = linker.downcallHandle(msgSend, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS))
        private val sendVoid3 = linker.downcallHandle(msgSend, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS))

        private val loadedFrameworks = HashSet<String>()

        /**
         * 在一个自动释放池里做：这里拿到的对象多是 autorelease 的，后台线程默认没有池，
         * 不包一层就一直不释放。
         */
        fun <T> withPool(block: () -> T): T {
            val pool = poolPush.invokeWithArguments() as MemorySegment
            try {
                return block()
            } finally {
                poolPop.invokeWithArguments(pool)
            }
        }

        /** 类所在的框架要先载入进程，objc_getClass 才找得到。AppKit 与 Foundation 已由 AWT 载入。 */
        @Synchronized
        fun loadFramework(name: String) {
            if (!loadedFrameworks.add(name)) return
            SymbolLookup.libraryLookup("/System/Library/Frameworks/$name.framework/$name", Arena.global())
        }

        fun cls(name: String): MemorySegment = Arena.ofConfined().use { arena ->
            val cls = getClass.invokeWithArguments(arena.allocateFrom(name)) as MemorySegment
            check(cls != MemorySegment.NULL) { "找不到 Objective-C 类 $name" }
            cls
        }

        private fun sel(name: String): MemorySegment = Arena.ofConfined().use { arena ->
            registerName.invokeWithArguments(arena.allocateFrom(name)) as MemorySegment
        }

        fun send(receiver: MemorySegment, selector: String): MemorySegment =
            send0.invokeWithArguments(receiver, sel(selector)) as MemorySegment

        fun send(receiver: MemorySegment, selector: String, arg: MemorySegment): MemorySegment =
            send1.invokeWithArguments(receiver, sel(selector), arg) as MemorySegment

        fun sendVoid(receiver: MemorySegment, selector: String, a: MemorySegment, b: MemorySegment, c: MemorySegment) {
            sendVoid3.invokeWithArguments(receiver, sel(selector), a, b, c)
        }

        /** 建一个 autorelease 的 NSString，随所在的池释放。 */
        fun string(value: String): MemorySegment = Arena.ofConfined().use { arena ->
            // stringWithUTF8String: 会拷贝，C 字符串用完即可释放
            send(cls("NSString"), "stringWithUTF8String:", arena.allocateFrom(value))
        }

        fun kotlinString(nsString: MemorySegment): String {
            if (nsString == MemorySegment.NULL) return ""
            val utf8 = send(nsString, "UTF8String")
            return utf8.reinterpret(Long.MAX_VALUE).getString(0)
        }
    }
}
