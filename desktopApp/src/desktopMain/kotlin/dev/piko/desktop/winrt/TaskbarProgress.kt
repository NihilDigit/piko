package dev.piko.desktop.winrt

import java.awt.Window
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 任务栏按钮上的进度条，ITaskbarList3 的最小绑定。
 *
 * 接口 ID、类 ID 与虚表顺序取自 Windows SDK 10.0.26100 的 shobjidl_core.h：
 * IUnknown 占 0 到 2；ITaskbarList 依次是 HrInit 3、AddTab 4、DeleteTab 5、ActivateTab 6、
 * SetActiveAlt 7；ITaskbarList2 加 MarkFullscreenWindow 8；ITaskbarList3 接着是
 * SetProgressValue 9、SetProgressState 10，其后的 RegisterTab 等用不到。
 *
 * 所有调用都只是投递：同一窗口尚未执行的请求合并成一份，由专用线程取最新值执行，
 * 下载进度每秒刷新多次也不会排起长队。执行结果不回报，失败时静默放弃。
 */
object TaskbarProgress {
    enum class State(internal val flag: Int) {
        NO_PROGRESS(0),
        INDETERMINATE(0x1),
        NORMAL(0x2),
        ERROR(0x4),
        PAUSED(0x8),
    }

    private val CLSID_TaskbarList = ComInterop.guid("56fdf344-fd6d-11d0-958a-006097c9a090")
    private val IID_ITaskbarList3 = ComInterop.guid("ea1afb91-9e28-4b86-90e9-9e9f8a5eefaf")

    private val setProgressValueDescriptor = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG)
    private val setProgressStateDescriptor = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)

    /**
     * 单独一条 STA 线程，不复用 WinRTSupport 的 Toast 线程：那条线程以 RoInitialize 进入的是 MTA，
     * 而 TaskbarList 登记的线程模型是 Apartment，在 MTA 里创建会由 COM 另起宿主 STA，
     * 每次调用都跨套间封送；那条线程的任务还以阻塞 get 等结果，与这里的只投递不等待也不合。
     */
    private val thread = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Piko-TaskbarProgress").also { it.isDaemon = true }
    }

    /** 每个窗口待执行的请求。state 与进度值分开存，执行时先设状态再设进度，与逐条执行的效果一致。 */
    private class Pending(val state: State?, val completed: Long, val total: Long, val hasValue: Boolean)

    private val pending = ConcurrentHashMap<Window, Pending>()
    private val drainScheduled = AtomicBoolean(false)

    // 只在专用线程上读写
    private var taskbar: MemorySegment? = null
    private var initFailed = false

    /** 显示确定进度。处于无进度或不确定状态时，系统会自动切到 NORMAL；ERROR 与 PAUSED 保持不变。 */
    fun setProgress(window: Window, completed: Long, total: Long) {
        if (!WinRTSupport.isWindows || total <= 0) return
        pending.compute(window) { _, old ->
            Pending(old?.state, completed.coerceIn(0, total), total, hasValue = true)
        }
        scheduleDrain()
    }

    fun setState(window: Window, state: State) {
        if (!WinRTSupport.isWindows) return
        pending.compute(window) { _, old ->
            // 无进度与不确定状态会丢掉之前的进度值，先前还没执行的 setProgress 不应再把它切回 NORMAL
            val keepsValue = state != State.NO_PROGRESS && state != State.INDETERMINATE
            if (old != null && old.hasValue && keepsValue) {
                Pending(state, old.completed, old.total, hasValue = true)
            } else {
                Pending(state, 0, 0, hasValue = false)
            }
        }
        scheduleDrain()
    }

    fun clear(window: Window) = setState(window, State.NO_PROGRESS)

    private fun scheduleDrain() {
        if (!drainScheduled.compareAndSet(false, true)) return
        runCatching {
            thread.execute {
                // 先放开标记再取：取走之后进来的请求会另排一轮，不会被漏掉
                drainScheduled.set(false)
                for (window in pending.keys) {
                    val request = pending.remove(window) ?: continue
                    runCatching { apply(window, request) }
                }
            }
        }.onFailure { drainScheduled.set(false) }
    }

    /** 在专用线程上执行一份请求。窗口已销毁时拿不到 HWND，直接丢弃。 */
    private fun apply(window: Window, request: Pending) {
        val list = taskbarList() ?: return
        val hwnd = Win32Window.handleOf(window) ?: return
        request.state?.let { state ->
            ComInterop.hresult(ComInterop.vtable(list, 10, setProgressStateDescriptor), list, hwnd, state.flag)
        }
        if (request.hasValue) {
            ComInterop.hresult(
                ComInterop.vtable(list, 9, setProgressValueDescriptor),
                list, hwnd, request.completed, request.total,
            )
        }
    }

    private fun taskbarList(): MemorySegment? {
        taskbar?.let { return it }
        if (initFailed) return null
        return runCatching {
            ComInterop.initializeApartmentThread()
            val list = ComInterop.createInstance(CLSID_TaskbarList, IID_ITaskbarList3)
            val hr = ComInterop.hresult(ComInterop.vtable(list, 3, ComInterop.callThis), list)
            if (hr < 0) {
                ComInterop.release(list)
                error("ITaskbarList.HrInit 失败：HRESULT 0x%08X".format(hr))
            }
            list
        }.onFailure { initFailed = true }.getOrNull().also { taskbar = it }
    }
}

/**
 * 经典 COM（非 WinRT）调用的公共部分：套间初始化、CoCreateInstance、虚表取函数与 GUID 编码。
 * 写法与 WindowsToast 相同，那边的同名函数是私有的，并且只认 combase 的 WinRT 入口。
 */
internal object ComInterop {
    private const val COINIT_APARTMENTTHREADED = 0x2
    private const val COINIT_DISABLE_OLE1DDE = 0x4
    private const val CLSCTX_INPROC_SERVER = 0x1
    private const val RPC_E_CHANGED_MODE = 0x80010106.toInt()

    private val linker = Linker.nativeLinker()

    // 全部延迟：guid 会触发本对象初始化，非 Windows 上找不到 ole32，不能让它在初始化时就抛出
    private val ole32 by lazy { SymbolLookup.libraryLookup("ole32", Arena.global()) }
    private val coInitializeEx by lazy { downcall("CoInitializeEx", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)) }
    private val coCreateInstance by lazy {
        downcall("CoCreateInstance", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS))
    }
    private val coTaskMemFree by lazy { downcall("CoTaskMemFree", FunctionDescriptor.ofVoid(ADDRESS)) }

    val callThis: FunctionDescriptor = FunctionDescriptor.of(JAVA_INT, ADDRESS)
    val callWithPointer: FunctionDescriptor = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)

    private val initializedThreads = ThreadLocal.withInitial { false }

    /**
     * 让当前线程进入单线程套间。线程不退出，所以不配对调用 CoUninitialize。
     * 已是多线程套间时沿用，COM 会把 Apartment 模型的对象放进宿主 STA，调用仍然可用。
     */
    fun initializeApartmentThread() {
        if (initializedThreads.get()) return
        val hr = coInitializeEx.invokeWithArguments(MemorySegment.NULL, COINIT_APARTMENTTHREADED or COINIT_DISABLE_OLE1DDE) as Int
        if (hr < 0 && hr != RPC_E_CHANGED_MODE) check(hr, "CoInitializeEx")
        initializedThreads.set(true)
    }

    fun createInstance(clsid: ByteArray, iid: ByteArray): MemorySegment = Arena.ofConfined().use { arena ->
        val out = arena.allocate(ADDRESS)
        val hr = coCreateInstance.invokeWithArguments(
            arena.allocateFrom(JAVA_BYTE, *clsid), MemorySegment.NULL, CLSCTX_INPROC_SERVER,
            arena.allocateFrom(JAVA_BYTE, *iid), out,
        ) as Int
        check(hr, "CoCreateInstance")
        out.get(ADDRESS, 0)
    }

    fun release(obj: MemorySegment) {
        if (obj.address() != 0L) hresult(vtable(obj, 2, callThis), obj)
    }

    fun taskMemFree(pointer: MemorySegment) {
        coTaskMemFree.invokeWithArguments(pointer)
    }

    /** 取 COM 对象虚表的第 [index] 项。对象指针所指的第一个字就是虚表地址。 */
    fun vtable(obj: MemorySegment, index: Int, descriptor: FunctionDescriptor): MethodHandle {
        val table = obj.reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0)
        val function = table.reinterpret(ADDRESS.byteSize() * (index + 1)).getAtIndex(ADDRESS, index.toLong())
        return linker.downcallHandle(function, descriptor)
    }

    // invokeWithArguments 的理由同 WindowsToast：invokeExact 要求调用点静态类型与描述符逐位一致
    fun hresult(handle: MethodHandle, vararg args: Any): Int = handle.invokeWithArguments(*args) as Int

    fun check(hr: Int, what: String) {
        if (hr < 0) error("$what 失败：HRESULT 0x%08X".format(hr))
    }

    /** GUID 的内存布局：前三段按小端存放，后两段按字节原样。 */
    fun guid(text: String): ByteArray {
        val hex = text.replace("-", "")
        val bytes = ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        bytes.reverse(0, 4)
        bytes.reverse(4, 6)
        bytes.reverse(6, 8)
        return bytes
    }

    private fun downcall(name: String, descriptor: FunctionDescriptor): MethodHandle =
        linker.downcallHandle(ole32.find(name).orElseThrow(), descriptor)
}
