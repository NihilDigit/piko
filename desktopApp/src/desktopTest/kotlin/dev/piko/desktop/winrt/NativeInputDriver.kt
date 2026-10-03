package dev.piko.desktop.winrt

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle

/** 一个触点在某一帧里的状态。坐标是屏幕物理像素：JVM 声明了 DPI 感知，user32 收发的都是物理像素。 */
internal class TouchContact(val id: Int, val x: Int, val y: Int, val phase: TouchPhase)

internal enum class TouchPhase(val flags: Int) {
    DOWN(POINTER_FLAG_DOWN or POINTER_FLAG_INRANGE or POINTER_FLAG_INCONTACT),
    UPDATE(POINTER_FLAG_UPDATE or POINTER_FLAG_INRANGE or POINTER_FLAG_INCONTACT),
    UP(POINTER_FLAG_UP),
}

/**
 * InjectTouchInput 给本进程一个虚拟数字化仪，不需要真的触摸屏，注入的触摸与真手指一样走 WM_POINTER。
 * 每一帧必须把所有尚未抬起的触点完整地重发一遍，少一个系统就当它被取消。
 */
internal object TouchInjector {
    /** InitializeTouchInjection 每个进程只能成功一次，所以只调一次、记下结果。 */
    val available: Boolean by lazy {
        runCatching { initializeTouchInjection.invokeWithArguments(MAX_CONTACTS, TOUCH_FEEDBACK_NONE) as Int != 0 }
            .getOrDefault(false)
    }

    fun frame(vararg contacts: TouchContact) {
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(POINTER_TOUCH_INFO_BYTES * contacts.size, 8)
            contacts.forEachIndexed { index, contact ->
                val info = buffer.asSlice(POINTER_TOUCH_INFO_BYTES * index, POINTER_TOUCH_INFO_BYTES)
                info.set(JAVA_INT, 0, PT_TOUCH)
                info.set(JAVA_INT, 4, contact.id)
                info.set(JAVA_INT, 12, contact.phase.flags)
                info.set(JAVA_INT, 32, contact.x)
                info.set(JAVA_INT, 36, contact.y)
                // POINTER_INFO 之后：touchFlags、touchMask、rcContact、rcContactRaw、orientation、pressure
                info.set(JAVA_INT, 100, TOUCH_MASK_CONTACTAREA or TOUCH_MASK_ORIENTATION or TOUCH_MASK_PRESSURE)
                info.set(JAVA_INT, 104, contact.x - 2)
                info.set(JAVA_INT, 108, contact.y - 2)
                info.set(JAVA_INT, 112, contact.x + 2)
                info.set(JAVA_INT, 116, contact.y + 2)
                info.set(JAVA_INT, 136, 90)
                info.set(JAVA_INT, 140, 32_000)
            }
            val callState = arena.allocate(Linker.Option.captureStateLayout())
            val ok = injectTouchInput.invokeWithArguments(callState, contacts.size, buffer) as Int != 0
            check(ok) { "InjectTouchInput 失败，GetLastError=${callState.get(JAVA_INT, lastErrorOffset)}" }
        }
    }

    // POINTER_INFO 96 字节，其后 touchFlags、touchMask 各 4，两个 RECT 各 16，orientation、pressure 各 4
    private const val POINTER_TOUCH_INFO_BYTES = 144L
    private const val MAX_CONTACTS = 10
    private const val TOUCH_FEEDBACK_NONE = 0x3
    private const val PT_TOUCH = 2
    private const val TOUCH_MASK_CONTACTAREA = 0x1
    private const val TOUCH_MASK_ORIENTATION = 0x2
    private const val TOUCH_MASK_PRESSURE = 0x4

    private val lastErrorOffset = Linker.Option.captureStateLayout().byteOffset(groupElement("GetLastError"))
    private val initializeTouchInjection =
        NativeUser32.handle("InitializeTouchInjection", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT))
    private val injectTouchInput = NativeUser32.handle(
        "InjectTouchInput", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS), Linker.Option.captureCallState("GetLastError"),
    )
}

private const val POINTER_FLAG_INRANGE = 0x00000002
private const val POINTER_FLAG_INCONTACT = 0x00000004
private const val POINTER_FLAG_DOWN = 0x00010000
private const val POINTER_FLAG_UPDATE = 0x00020000
private const val POINTER_FLAG_UP = 0x00040000

/** 屏幕物理像素下的窗口矩形。 */
internal data class ScreenRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

internal object NativeUser32 {
    private val linker = Linker.nativeLinker()
    private val user32 = SymbolLookup.libraryLookup("user32", Arena.global())
    private val kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global())

    fun handle(name: String, descriptor: FunctionDescriptor, vararg options: Linker.Option): MethodHandle =
        linker.downcallHandle(user32.find(name).orElseThrow(), descriptor, *options)

    private val getForegroundWindow = handle("GetForegroundWindow", FunctionDescriptor.of(ADDRESS))
    private val setForegroundWindow = handle("SetForegroundWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    private val bringWindowToTop = handle("BringWindowToTop", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    private val getWindowThreadProcessId =
        handle("GetWindowThreadProcessId", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    private val attachThreadInput = handle("AttachThreadInput", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT))
    private val getWindowRect = handle("GetWindowRect", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    private val getCurrentThreadId = linker.downcallHandle(
        kernel32.find("GetCurrentThreadId").orElseThrow(), FunctionDescriptor.of(JAVA_INT),
    )

    fun foregroundWindow(): Long = (getForegroundWindow.invokeWithArguments() as MemorySegment).address()

    fun windowRect(hwnd: Long): ScreenRect = Arena.ofConfined().use { arena ->
        val rect = arena.allocate(16)
        check(getWindowRect.invokeWithArguments(MemorySegment.ofAddress(hwnd), rect) as Int != 0) { "GetWindowRect 失败" }
        ScreenRect(rect.get(JAVA_INT, 0), rect.get(JAVA_INT, 4), rect.get(JAVA_INT, 8), rect.get(JAVA_INT, 12))
    }

    /**
     * 不在前台的进程不许抢前台，Gradle 的测试进程正是如此，SetForegroundWindow 只会让任务栏闪一下。
     * 把本线程挂到当前前台线程的输入队列上就解除了这条限制，又不必点任何东西，用户眼前的窗口收不到多余的输入。
     */
    fun forceForeground(hwnd: Long) {
        val target = MemorySegment.ofAddress(hwnd)
        val foreground = getForegroundWindow.invokeWithArguments() as MemorySegment
        val foregroundThread = if (foreground.address() == 0L) 0 else
            getWindowThreadProcessId.invokeWithArguments(foreground, MemorySegment.NULL) as Int
        val currentThread = getCurrentThreadId.invokeWithArguments() as Int
        val attached = foregroundThread != 0 && foregroundThread != currentThread &&
            attachThreadInput.invokeWithArguments(currentThread, foregroundThread, 1) as Int != 0
        try {
            setForegroundWindow.invokeWithArguments(target)
            bringWindowToTop.invokeWithArguments(target)
        } finally {
            if (attached) attachThreadInput.invokeWithArguments(currentThread, foregroundThread, 0)
        }
    }
}
