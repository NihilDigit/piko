package dev.piko.desktop.winrt

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle

/**
 * 发 Toast 所需的那一小段 WinRT，经 FFM 直接调 combase 与 COM 虚表。
 *
 * 原先用 kotlin-winrt 的投影：投影包六十多兆，每个类型的静态初始化都会拉起整个 Windows SDK
 * 投影的注册，ProGuard 裁不下去；而且 0.1.0-SNAPSHOT 在 ToastNotification 的静态初始化里
 * 给 ParameterizedInterfaceId 传了空签名，直接抛 NullPointerException，Toast 从未发出去过。
 * 这里只用到四个接口、五个方法，接口 ID 与虚表顺序取自 Windows SDK 10.0.26100 的
 * windows.ui.notifications.h 与 windows.data.xml.dom.h。
 *
 * 虚表下标：IUnknown 占 0 到 2，IInspectable 再占 3 到 5，各接口自己的方法从 6 起。
 * 调用方负责在同一条已初始化 WinRT 的线程上调用，见 WinRTSupport 的专用线程。
 */
internal object WindowsToast {
    private const val RO_INIT_MULTITHREADED = 1
    private const val RPC_E_CHANGED_MODE = 0x80010106.toInt()

    private val IID_IXmlDocument = guid("f7f3a506-1e87-42d6-bcfb-b8c809fa5494")
    private val IID_IXmlDocumentIO = guid("6cd0e74e-ee65-4489-9ebf-ca43e87ba637")
    private val IID_IToastNotificationFactory = guid("04124b20-82c6-4229-b109-fd9ed4662b53")
    private val IID_IToastNotificationManagerStatics = guid("50ac103f-d235-4598-bbef-98fe4d1a3ad4")

    private val linker = Linker.nativeLinker()
    private val combase = SymbolLookup.libraryLookup("combase", Arena.global())

    private val roInitialize = downcall("RoInitialize", FunctionDescriptor.of(JAVA_INT, JAVA_INT))
    private val windowsCreateString =
        downcall("WindowsCreateString", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS))
    private val windowsDeleteString = downcall("WindowsDeleteString", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    private val roActivateInstance =
        downcall("RoActivateInstance", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    private val roGetActivationFactory =
        downcall("RoGetActivationFactory", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))

    // 用到的虚表方法都是 this 加一到两个指针参数、返回 HRESULT
    private val callWithPointer = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)
    private val callWithTwoPointers = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS)
    private val release = FunctionDescriptor.of(JAVA_INT, ADDRESS)

    private var initialized = false

    /** 在当前线程上初始化 WinRT。重复调用无害；线程已是单线程套间时沿用。 */
    fun initializeThread() {
        if (initialized) return
        val hr = hresult(roInitialize, RO_INIT_MULTITHREADED)
        if (hr < 0 && hr != RPC_E_CHANGED_MODE) check(hr, "RoInitialize")
        initialized = true
    }

    /** 以 [appUserModelId] 的名义弹出一条 Toast，[xml] 是完整的 toast 模板。失败时抛异常。 */
    fun show(appUserModelId: String, xml: String) = Arena.ofConfined().use { arena ->
        val owned = mutableListOf<MemorySegment>()
        fun keep(pointer: MemorySegment) = pointer.also { owned += it }
        try {
            val document = keep(activateInstance(arena, "Windows.Data.Xml.Dom.XmlDocument"))
            val documentIo = keep(queryInterface(arena, document, IID_IXmlDocumentIO))
            withHString(arena, xml) { text ->
                check(hresult(vtable(documentIo, 6, callWithPointer), documentIo, text), "LoadXml")
            }
            val xmlDocument = keep(queryInterface(arena, document, IID_IXmlDocument))

            val toastFactory = keep(activationFactory(arena, "Windows.UI.Notifications.ToastNotification", IID_IToastNotificationFactory))
            val toast = keep(outPointer(arena, "CreateToastNotification") { out ->
                hresult(vtable(toastFactory, 6, callWithTwoPointers), toastFactory, xmlDocument, out)
            })

            val manager = keep(
                activationFactory(arena, "Windows.UI.Notifications.ToastNotificationManager", IID_IToastNotificationManagerStatics),
            )
            val notifier = keep(withHString(arena, appUserModelId) { id ->
                outPointer(arena, "CreateToastNotifierWithId") { out ->
                    hresult(vtable(manager, 7, callWithTwoPointers), manager, id, out)
                }
            })
            check(hresult(vtable(notifier, 6, callWithPointer), notifier, toast), "IToastNotifier.Show")
        } finally {
            owned.asReversed().forEach { pointer ->
                hresult(vtable(pointer, 2, release), pointer)
            }
        }
    }

    private fun activateInstance(arena: Arena, className: String): MemorySegment =
        withHString(arena, className) { name ->
            outPointer(arena, "RoActivateInstance $className") { out -> hresult(roActivateInstance, name, out) }
        }

    private fun activationFactory(arena: Arena, className: String, iid: ByteArray): MemorySegment =
        withHString(arena, className) { name ->
            val iidSegment = arena.allocateFrom(JAVA_BYTE, *iid)
            outPointer(arena, "RoGetActivationFactory $className") { out ->
                hresult(roGetActivationFactory, name, iidSegment, out)
            }
        }

    private fun queryInterface(arena: Arena, obj: MemorySegment, iid: ByteArray): MemorySegment {
        val iidSegment = arena.allocateFrom(JAVA_BYTE, *iid)
        return outPointer(arena, "QueryInterface") { out ->
            hresult(vtable(obj, 0, callWithTwoPointers), obj, iidSegment, out)
        }
    }

    private inline fun outPointer(arena: Arena, what: String, call: (MemorySegment) -> Int): MemorySegment {
        val out = arena.allocate(ADDRESS)
        check(call(out), what)
        return out.get(ADDRESS, 0)
    }

    private inline fun <T> withHString(arena: Arena, text: String, block: (MemorySegment) -> T): T {
        val chars = text.toCharArray()
        val buffer = arena.allocate(JAVA_SHORT, chars.size.toLong() + 1)
        chars.forEachIndexed { index, c -> buffer.setAtIndex(JAVA_SHORT, index.toLong(), c.code.toShort()) }
        val out = arena.allocate(ADDRESS)
        check(hresult(windowsCreateString, buffer, chars.size, out), "WindowsCreateString")
        val hstring = out.get(ADDRESS, 0)
        try {
            return block(hstring)
        } finally {
            hresult(windowsDeleteString, hstring)
        }
    }

    /** 取 COM 对象虚表的第 [index] 项。对象指针所指的第一个字就是虚表地址。 */
    private fun vtable(obj: MemorySegment, index: Int, descriptor: FunctionDescriptor): MethodHandle {
        val table = obj.reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0)
        val function = table.reinterpret(ADDRESS.byteSize() * (index + 1)).getAtIndex(ADDRESS, index.toLong())
        return linker.downcallHandle(function, descriptor)
    }

    // invokeWithArguments 而不是 invokeExact：后者要求调用点的静态类型与描述符逐位一致，
    // Kotlin 的装箱与可空类型很容易让它在运行时抛 WrongMethodTypeException，这点开销对一条 Toast 无所谓
    private fun hresult(handle: MethodHandle, vararg args: Any): Int = handle.invokeWithArguments(*args) as Int

    private fun downcall(name: String, descriptor: FunctionDescriptor): MethodHandle =
        linker.downcallHandle(combase.find(name).orElseThrow(), descriptor)

    private fun check(hr: Int, what: String) {
        if (hr < 0) error("$what 失败：HRESULT 0x%08X".format(hr))
    }

    /** GUID 的内存布局：前三段按小端存放，后两段按字节原样。 */
    private fun guid(text: String): ByteArray {
        val hex = text.replace("-", "")
        val bytes = ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        bytes.reverse(0, 4)
        bytes.reverse(4, 6)
        bytes.reverse(6, 8)
        return bytes
    }

}
