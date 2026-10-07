package dev.piko.desktop

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.invoke.MethodHandle

/**
 * Objective-C 运行时的最小封装，供 macOS 上经 objc_msgSend 调 AppKit、LaunchServices 的地方共用。
 * objc_msgSend 在 arm64 上不是变参函数，必须按每个方法的真实参数签名调，所以按参数个数各取一个句柄；
 * 这里用到的参数与返回值都是指针。
 */
internal object MacObjc {
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

    /** NSURL 的文件路径，去掉符号链接与结尾的斜杠，两个 URL 才比得出是不是同一个应用。 */
    fun pathOf(url: MemorySegment): String {
        val resolved = send(url, "URLByResolvingSymlinksInPath")
        return kotlinString(send(resolved, "path")).trimEnd('/')
    }
}
