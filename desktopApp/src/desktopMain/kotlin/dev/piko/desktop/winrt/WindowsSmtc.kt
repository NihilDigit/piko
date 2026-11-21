package dev.piko.desktop.winrt

import dev.piko.shared.log.PikoLog
import java.awt.Window
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_CHAR
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities

/** Windows SMTC 媒体卡片呈现状态。 */
data class SmtcState(
    val active: Boolean,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val title: String = "",
    val subtitle: String = "",
    val canPrevious: Boolean = false,
    val canNext: Boolean = false,
    val isVideo: Boolean = true,
)

/** Windows 媒体键 / 系统媒体卡片按键回调。 */
interface SmtcActionListener {
    fun onPlay()
    fun onPause()
    fun onStop() = onPause()
    fun onNext() {}
    fun onPrevious() {}
}

/**
 * Windows 10/11 原生 `SystemMediaTransportControls`（SMTC）桥接：
 * 将系统媒体卡片、键盘多媒体键与蓝牙耳机控制接入桌面端播放器，并同步播放状态与标题信息。
 *
 * 全部接口 ID 与虚表槽位均逐项对照 Windows SDK `10.0.26100.0`（`winrt/windows.media.h` 与
 * `um/SystemMediaTransportControlsInterop.h`）验证，特别修正 `ISystemMediaTransportControls`
 * 在 `put_IsPauseEnabled (17)` 之后的正确槽位：
 * - `put_IsPreviousEnabled = 25`（而非 23 `put_IsRewindEnabled`）
 * - `put_IsNextEnabled = 27`（而非 25 `put_IsPreviousEnabled`）
 * - `add_ButtonPressed = 32`（而非 30 `get_IsChannelDownEnabled`，误调 30 会静默返回 `S_OK` 且从未注册回调）
 * - `remove_ButtonPressed = 33`（而非 31 `put_IsChannelDownEnabled`）
 *
 * 所有 COM/WinRT 虚表调用复用 [ComInterop.invokeVtable] 缓存下调句柄，零热路径桩分配；
 * 全部操作收敛在单条 MTA 守护线程上。
 */
class WindowsSmtcSession(
    private val listener: SmtcActionListener,
    private val dispatchOnEdt: Boolean = true,
) : AutoCloseable {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Piko-SMTC").apply { isDaemon = true }
    }

    private var smtc: MemorySegment = MemorySegment.NULL
    private var updater: MemorySegment = MemorySegment.NULL
    private var eventToken: Long = 0L
    private var hasEventToken = false
    private var callbackArena: Arena? = null
    private var callback: SmtcButtonPressedCallback? = null
    private var initialized = false
    private var closed = false

    private var lastEnabled: Boolean? = null
    private var lastStatus: Int? = null
    private var lastCanPrev: Boolean? = null
    private var lastCanNext: Boolean? = null
    private var lastTitle: String? = null
    private var lastSubtitle: String? = null
    private var lastIsVideo: Boolean? = null
    private var pendingState: SmtcState? = null

    @Volatile
    internal var lastAddButtonPressedHr: Int = Int.MIN_VALUE
        private set

    @Volatile
    internal var registeredToken: Long = 0L
        private set

    internal val isHandlerRegistered: Boolean
        get() = hasEventToken && registeredToken != 0L

    internal val queryInterfaceHits: Int
        get() = callback?.queryInterfaceHits?.get() ?: 0

    internal val callbackForTest: SmtcButtonPressedCallback?
        get() = callback

    fun attach(window: Window) {
        if (!WinRTSupport.isWindows) return
        val hwnd = Win32Window.handleOf(window)?.address() ?: return
        attach(hwnd)
    }

    fun attach(hwnd: Long) {
        if (!WinRTSupport.isWindows || hwnd == 0L) return
        executor.execute {
            if (closed || initialized) return@execute
            runCatching { initOnWorker(hwnd) }
                .onFailure { PikoLog.w(TAG, "Failed to initialize Windows SMTC", it) }
            pendingState?.let { latest ->
                runCatching { applyStateOnWorker(latest) }
                    .onFailure { PikoLog.w(TAG, "Failed to apply initial SMTC state", it) }
            }
        }
    }

    fun sync(state: SmtcState) {
        if (!WinRTSupport.isWindows) return
        executor.execute {
            if (closed) return@execute
            pendingState = state
            if (!initialized || smtc == MemorySegment.NULL) return@execute
            runCatching { applyStateOnWorker(state) }
                .onFailure { PikoLog.w(TAG, "Failed to sync Windows SMTC state", it) }
        }
    }

    /** 等待当前已入队的所有 SMTC 操作执行完毕（供原生集成测试确定性断言使用）。 */
    internal fun awaitIdle(timeoutMs: Long = 5_000L): Boolean {
        val latch = CountDownLatch(1)
        runCatching { executor.execute { latch.countDown() } }
            .onFailure { return true }
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        if (!WinRTSupport.isWindows) {
            executor.shutdown()
            return
        }
        val latch = CountDownLatch(1)
        runCatching {
            executor.execute {
                try {
                    if (!closed) {
                        closed = true
                        runCatching { releaseOnWorker() }
                            .onFailure { PikoLog.w(TAG, "Failed to release Windows SMTC", it) }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }.onFailure { latch.countDown() }
        latch.await(2, TimeUnit.SECONDS)
        executor.shutdown()
    }

    private fun initOnWorker(hwnd: Long) {
        val native = SmtcCombase.instance ?: return
        val hrInit = native.roInitialize.invokeExact(RO_INIT_MULTITHREADED) as Int
        if (hrInit < 0 && hrInit != RPC_E_CHANGED_MODE) {
            PikoLog.w(TAG, "RoInitialize failed for SMTC: HRESULT 0x%08X".format(hrInit))
            return
        }

        Arena.ofConfined().use { local ->
            val classHstr = native.createHString(local, RUNTIME_CLASS_SMTC) ?: return
            try {
                val iidInterop = local.allocateFrom(JAVA_BYTE, *IID_SMTC_INTEROP)
                val outFactory = local.allocate(ADDRESS)
                val hrFactory = native.roGetActivationFactory.invokeExact(classHstr, iidInterop, outFactory) as Int
                if (hrFactory < 0) {
                    PikoLog.w(TAG, "RoGetActivationFactory(SMTC) failed: HRESULT 0x%08X".format(hrFactory))
                    return
                }
                val factory = outFactory.get(ADDRESS, 0L)
                if (factory == MemorySegment.NULL) return
                try {
                    val iidSmtc = local.allocateFrom(JAVA_BYTE, *IID_SMTC)
                    val outSmtc = local.allocate(ADDRESS)
                    val hrGet = ComInterop.invokeVtable(
                        factory,
                        SLOT_INTEROP_GET_FOR_WINDOW,
                        DESC_GET_FOR_WINDOW,
                        factory,
                        MemorySegment.ofAddress(hwnd),
                        iidSmtc,
                        outSmtc,
                    )
                    if (hrGet < 0) {
                        PikoLog.w(TAG, "ISystemMediaTransportControlsInterop.GetForWindow failed: HRESULT 0x%08X".format(hrGet))
                        return
                    }
                    smtc = outSmtc.get(ADDRESS, 0L)
                } finally {
                    ComInterop.release(factory)
                }
            } finally {
                native.windowsDeleteString.invokeExact(classHstr) as Int
            }

            if (smtc == MemorySegment.NULL) return

            val outUpdater = local.allocate(ADDRESS)
            if (ComInterop.invokeVtable(smtc, SLOT_SMTC_GET_DISPLAY_UPDATER, ComInterop.callWithPointer, smtc, outUpdater) >= 0) {
                updater = outUpdater.get(ADDRESS, 0L)
            }

            val cbArena = Arena.ofShared()
            val cb = SmtcButtonPressedCallback(listener, dispatchOnEdt, cbArena)
            val outToken = local.allocate(JAVA_LONG)
            val hrAdd = ComInterop.invokeVtable(
                smtc,
                SLOT_SMTC_ADD_BUTTON_PRESSED,
                DESC_ADD_EVENT,
                smtc,
                cb.segment,
                outToken,
            )
            lastAddButtonPressedHr = hrAdd
            if (hrAdd >= 0) {
                callbackArena = cbArena
                callback = cb
                eventToken = outToken.get(JAVA_LONG, 0L)
                registeredToken = eventToken
                hasEventToken = true
            } else {
                PikoLog.w(TAG, "ISystemMediaTransportControls.add_ButtonPressed failed: HRESULT 0x%08X".format(hrAdd))
                cbArena.close()
            }
            initialized = true
        }
    }

    private fun applyStateOnWorker(state: SmtcState) {
        val native = SmtcCombase.instance ?: return
        val enabled = state.active
        if (lastEnabled != enabled) {
            putBool(smtc, SLOT_SMTC_PUT_IS_ENABLED, enabled)
            putBool(smtc, SLOT_SMTC_PUT_IS_PLAY_ENABLED, enabled)
            putBool(smtc, SLOT_SMTC_PUT_IS_PAUSE_ENABLED, enabled)
            putBool(smtc, SLOT_SMTC_PUT_IS_STOP_ENABLED, enabled)
            lastEnabled = enabled
        }
        if (!enabled) {
            if (lastStatus != MEDIA_STATUS_CLOSED) {
                putInt(smtc, SLOT_SMTC_PUT_PLAYBACK_STATUS, MEDIA_STATUS_CLOSED)
                lastStatus = MEDIA_STATUS_CLOSED
            }
            if (updater != MemorySegment.NULL && (lastTitle != null || lastSubtitle != null)) {
                ComInterop.invokeVtable(updater, SLOT_UPDATER_CLEAR_ALL, ComInterop.callThis, updater)
                ComInterop.invokeVtable(updater, SLOT_UPDATER_UPDATE, ComInterop.callThis, updater)
                lastTitle = null
                lastSubtitle = null
                lastIsVideo = null
            }
            return
        }

        if (lastCanPrev != state.canPrevious) {
            putBool(smtc, SLOT_SMTC_PUT_IS_PREVIOUS_ENABLED, state.canPrevious)
            lastCanPrev = state.canPrevious
        }
        if (lastCanNext != state.canNext) {
            putBool(smtc, SLOT_SMTC_PUT_IS_NEXT_ENABLED, state.canNext)
            lastCanNext = state.canNext
        }

        val status = when {
            state.isPlaying -> MEDIA_STATUS_PLAYING
            state.isBuffering -> MEDIA_STATUS_CHANGING
            else -> MEDIA_STATUS_PAUSED
        }
        if (lastStatus != status) {
            putInt(smtc, SLOT_SMTC_PUT_PLAYBACK_STATUS, status)
            lastStatus = status
        }

        if (updater != MemorySegment.NULL &&
            (state.title != lastTitle || state.subtitle != lastSubtitle || state.isVideo != lastIsVideo)
        ) {
            Arena.ofConfined().use { local ->
                if (state.isVideo) {
                    putInt(updater, SLOT_UPDATER_PUT_TYPE, MEDIA_TYPE_VIDEO)
                    val outVideo = local.allocate(ADDRESS)
                    if (ComInterop.invokeVtable(updater, SLOT_UPDATER_GET_VIDEO_PROPS, ComInterop.callWithPointer, updater, outVideo) >= 0) {
                        val video = outVideo.get(ADDRESS, 0L)
                        if (video != MemorySegment.NULL) {
                            try {
                                putHString(native, local, video, SLOT_VIDEO_PUT_TITLE, state.title)
                                putHString(native, local, video, SLOT_VIDEO_PUT_SUBTITLE, state.subtitle)
                            } finally {
                                ComInterop.release(video)
                            }
                        }
                    }
                } else {
                    putInt(updater, SLOT_UPDATER_PUT_TYPE, MEDIA_TYPE_MUSIC)
                    val outMusic = local.allocate(ADDRESS)
                    if (ComInterop.invokeVtable(updater, SLOT_UPDATER_GET_MUSIC_PROPS, ComInterop.callWithPointer, updater, outMusic) >= 0) {
                        val music = outMusic.get(ADDRESS, 0L)
                        if (music != MemorySegment.NULL) {
                            try {
                                putHString(native, local, music, SLOT_MUSIC_PUT_TITLE, state.title)
                                putHString(native, local, music, SLOT_MUSIC_PUT_ARTIST, state.subtitle)
                            } finally {
                                ComInterop.release(music)
                            }
                        }
                    }
                }
                ComInterop.invokeVtable(updater, SLOT_UPDATER_UPDATE, ComInterop.callThis, updater)
                lastTitle = state.title
                lastSubtitle = state.subtitle
                lastIsVideo = state.isVideo
            }
        }
    }

    private fun releaseOnWorker() {
        var removeSucceeded = true
        if (smtc != MemorySegment.NULL && hasEventToken) {
            val hrRemove = ComInterop.invokeVtable(smtc, SLOT_SMTC_REMOVE_BUTTON_PRESSED, DESC_REMOVE_EVENT, smtc, eventToken)
            removeSucceeded = hrRemove >= 0
            hasEventToken = false
            registeredToken = 0L
        }
        if (updater != MemorySegment.NULL) {
            ComInterop.invokeVtable(updater, SLOT_UPDATER_CLEAR_ALL, ComInterop.callThis, updater)
            ComInterop.invokeVtable(updater, SLOT_UPDATER_UPDATE, ComInterop.callThis, updater)
            ComInterop.release(updater)
            updater = MemorySegment.NULL
        }
        if (smtc != MemorySegment.NULL) {
            putBool(smtc, SLOT_SMTC_PUT_IS_ENABLED, false)
            ComInterop.release(smtc)
            smtc = MemorySegment.NULL
        }
        // 仅在 COM 引用计数归零且 remove_ButtonPressed 成功后再释放回调 Arena，避免在途 MTA 回调触发 UAF
        val cb = callback
        callback = null
        callbackArena = null
        cb?.releaseOwner(removeSucceeded)
    }

    private companion object {
        const val TAG = "SMTC"
    }
}

/**
 * 手写 WinRT 事件回调对象：
 * 实现 `ITypedEventHandler<SystemMediaTransportControls, SystemMediaTransportControlsButtonPressedEventArgs>`
 * （参数化 IID `0557e996-7b23-5bae-aa81-ea0d671143a4`）与 `IAgileObject`，避免 MTA 跨套间代理封送。
 *
 * 4 个 upcall 方法由 `desktopApp/proguard-rules.pro` 显式 `-keepclassmembers` 保留。
 */
internal class SmtcButtonPressedCallback(
    listener: SmtcActionListener,
    private val dispatchOnEdt: Boolean,
    private val arena: Arena,
) {
    @Volatile
    private var activeListener: SmtcActionListener? = listener
    private val refCount = AtomicInteger(1)
    private val arenaClosed = AtomicBoolean(false)
    internal val queryInterfaceHits = AtomicInteger(0)

    private val iidUnknown = arena.allocateFrom(JAVA_BYTE, *IID_IUNKNOWN)
    private val iidAgile = arena.allocateFrom(JAVA_BYTE, *IID_IAGILE_OBJECT)
    private val iidHandler = arena.allocateFrom(JAVA_BYTE, *IID_BUTTON_PRESSED_HANDLER)

    val segment: MemorySegment

    init {
        val linker = Linker.nativeLinker()
        val lookup = MethodHandles.lookup()
        val qiStub = linker.upcallStub(
            lookup.findVirtual(
                SmtcButtonPressedCallback::class.java,
                "queryInterface",
                MethodType.methodType(
                    Int::class.javaPrimitiveType,
                    MemorySegment::class.java,
                    MemorySegment::class.java,
                    MemorySegment::class.java,
                ),
            ).bindTo(this),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
            arena,
        )
        val addRefStub = linker.upcallStub(
            lookup.findVirtual(
                SmtcButtonPressedCallback::class.java,
                "addRef",
                MethodType.methodType(Int::class.javaPrimitiveType, MemorySegment::class.java),
            ).bindTo(this),
            FunctionDescriptor.of(JAVA_INT, ADDRESS),
            arena,
        )
        val releaseStub = linker.upcallStub(
            lookup.findVirtual(
                SmtcButtonPressedCallback::class.java,
                "release",
                MethodType.methodType(Int::class.javaPrimitiveType, MemorySegment::class.java),
            ).bindTo(this),
            FunctionDescriptor.of(JAVA_INT, ADDRESS),
            arena,
        )
        val invokeStub = linker.upcallStub(
            lookup.findVirtual(
                SmtcButtonPressedCallback::class.java,
                "invoke",
                MethodType.methodType(
                    Int::class.javaPrimitiveType,
                    MemorySegment::class.java,
                    MemorySegment::class.java,
                    MemorySegment::class.java,
                ),
            ).bindTo(this),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
            arena,
        )

        val vtbl = arena.allocate(ADDRESS.byteSize() * 4L)
        vtbl.setAtIndex(ADDRESS, 0L, qiStub)
        vtbl.setAtIndex(ADDRESS, 1L, addRefStub)
        vtbl.setAtIndex(ADDRESS, 2L, releaseStub)
        vtbl.setAtIndex(ADDRESS, 3L, invokeStub)

        val obj = arena.allocate(ADDRESS)
        obj.set(ADDRESS, 0L, vtbl)
        segment = obj
    }

    @Suppress("unused")
    fun queryInterface(thisPtr: MemorySegment, riidPtr: MemorySegment, ppvObject: MemorySegment): Int {
        queryInterfaceHits.incrementAndGet()
        if (ppvObject == MemorySegment.NULL) return E_POINTER
        val out = ppvObject.reinterpret(ADDRESS.byteSize())
        if (riidPtr == MemorySegment.NULL) {
            out.set(ADDRESS, 0L, MemorySegment.NULL)
            return E_NOINTERFACE
        }
        val riid = riidPtr.reinterpret(16L)
        val matches = guidEquals(riid, iidHandler) || guidEquals(riid, iidUnknown) || guidEquals(riid, iidAgile)
        return if (matches) {
            refCount.incrementAndGet()
            out.set(ADDRESS, 0L, thisPtr)
            S_OK
        } else {
            out.set(ADDRESS, 0L, MemorySegment.NULL)
            E_NOINTERFACE
        }
    }

    @Suppress("unused")
    fun addRef(@Suppress("UNUSED_PARAMETER") thisPtr: MemorySegment): Int =
        refCount.incrementAndGet()

    private fun closeArenaOnce() {
        if (arenaClosed.compareAndSet(false, true)) {
            runCatching { arena.close() }
                .onFailure { PikoLog.w("SMTC", "Failed to close SMTC callback Arena", it) }
        }
    }

    fun releaseOwner(removeSucceeded: Boolean) {
        activeListener = null
        if (!removeSucceeded) return
        if (refCount.decrementAndGet() <= 0) {
            closeArenaOnce()
        }
    }

    @Suppress("unused")
    fun release(@Suppress("UNUSED_PARAMETER") thisPtr: MemorySegment): Int {
        val remaining = refCount.decrementAndGet().coerceAtLeast(0)
        // 不能在当前 FFM upcall 栈帧内直接关闭所属 Arena，延迟到回调返回原生栈后再关闭
        if (remaining == 0 && activeListener == null) {
            CompletableFuture.runAsync(::closeArenaOnce)
        }
        return remaining
    }

    @Suppress("unused")
    fun invoke(
        @Suppress("UNUSED_PARAMETER") thisPtr: MemorySegment,
        @Suppress("UNUSED_PARAMETER") senderPtr: MemorySegment,
        argsPtr: MemorySegment,
    ): Int {
        val target = activeListener ?: return S_OK
        if (argsPtr == MemorySegment.NULL) return S_OK
        runCatching {
            Arena.ofConfined().use { local ->
                val outButton = local.allocate(JAVA_INT)
                val hr = ComInterop.invokeVtable(
                    argsPtr,
                    SLOT_ARGS_GET_BUTTON,
                    ComInterop.callWithPointer,
                    argsPtr,
                    outButton,
                )
                if (hr >= 0) {
                    val button = outButton.get(JAVA_INT, 0L)
                    val deliver = {
                        when (button) {
                            BUTTON_PLAY -> target.onPlay()
                            BUTTON_PAUSE -> target.onPause()
                            BUTTON_STOP -> target.onStop()
                            BUTTON_NEXT -> target.onNext()
                            BUTTON_PREVIOUS -> target.onPrevious()
                        }
                    }
                    if (dispatchOnEdt) {
                        SwingUtilities.invokeLater(deliver)
                    } else {
                        deliver()
                    }
                }
            }
        }.onFailure { PikoLog.w("SMTC", "Failed to dispatch SMTC button event", it) }
        return S_OK
    }
}

private class SmtcCombase(
    val roInitialize: MethodHandle,
    val roGetActivationFactory: MethodHandle,
    val windowsCreateString: MethodHandle,
    val windowsDeleteString: MethodHandle,
) {
    fun createHString(arena: Arena, text: String): MemorySegment? {
        val chars = arena.allocate(JAVA_CHAR, (text.length + 1).toLong())
        for (i in text.indices) {
            chars.setAtIndex(JAVA_CHAR, i.toLong(), text[i])
        }
        chars.setAtIndex(JAVA_CHAR, text.length.toLong(), '\u0000')
        val out = arena.allocate(ADDRESS)
        val hr = windowsCreateString.invokeExact(chars, text.length, out) as Int
        if (hr < 0) return null
        return out.get(ADDRESS, 0L)
    }

    companion object {
        val instance: SmtcCombase? by lazy {
            if (!WinRTSupport.isWindows) return@lazy null
            runCatching {
                val linker = Linker.nativeLinker()
                val combase = SymbolLookup.libraryLookup("combase", Arena.global())
                SmtcCombase(
                    roInitialize = linker.downcallHandle(
                        combase.find("RoInitialize").orElseThrow(),
                        FunctionDescriptor.of(JAVA_INT, JAVA_INT),
                    ),
                    roGetActivationFactory = linker.downcallHandle(
                        combase.find("RoGetActivationFactory").orElseThrow(),
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
                    ),
                    windowsCreateString = linker.downcallHandle(
                        combase.find("WindowsCreateString").orElseThrow(),
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS),
                    ),
                    windowsDeleteString = linker.downcallHandle(
                        combase.find("WindowsDeleteString").orElseThrow(),
                        FunctionDescriptor.of(JAVA_INT, ADDRESS),
                    ),
                )
            }.onFailure { PikoLog.w("SMTC", "Failed to load combase.dll for Windows SMTC", it) }.getOrNull()
        }
    }
}

private fun putBool(instance: MemorySegment, slot: Int, value: Boolean) {
    ComInterop.invokeVtable(instance, slot, DESC_PUT_BOOL, instance, if (value) 1.toByte() else 0.toByte())
}

private fun putInt(instance: MemorySegment, slot: Int, value: Int) {
    ComInterop.invokeVtable(instance, slot, DESC_PUT_INT, instance, value)
}

private fun putHString(native: SmtcCombase, arena: Arena, instance: MemorySegment, slot: Int, value: String) {
    val hstr = native.createHString(arena, value) ?: MemorySegment.NULL
    try {
        ComInterop.invokeVtable(instance, slot, ComInterop.callWithPointer, instance, hstr)
    } finally {
        if (hstr != MemorySegment.NULL) {
            native.windowsDeleteString.invokeExact(hstr) as Int
        }
    }
}

private fun guidEquals(a: MemorySegment, b: MemorySegment): Boolean =
    a.get(JAVA_LONG, 0L) == b.get(JAVA_LONG, 0L) && a.get(JAVA_LONG, 8L) == b.get(JAVA_LONG, 8L)

private const val RUNTIME_CLASS_SMTC = "Windows.Media.SystemMediaTransportControls"

private val IID_IUNKNOWN = ComInterop.guid("00000000-0000-0000-c000-000000000046")
private val IID_IAGILE_OBJECT = ComInterop.guid("94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90")
private val IID_SMTC_INTEROP = ComInterop.guid("ddb0472d-c911-4a1f-86d9-dc3d71a95f5a")
private val IID_SMTC = ComInterop.guid("99fa3ff4-1742-42a6-902e-087d41f965ec")
// WinRT parameterized UUIDv5 for ITypedEventHandler<SystemMediaTransportControls, SystemMediaTransportControlsButtonPressedEventArgs>
private val IID_BUTTON_PRESSED_HANDLER = ComInterop.guid("0557e996-7b23-5bae-aa81-ea0d671143a4")

private val DESC_GET_FOR_WINDOW = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS)
private val DESC_ADD_EVENT = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS)
private val DESC_REMOVE_EVENT = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG)
private val DESC_PUT_BOOL = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_BYTE)
private val DESC_PUT_INT = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)

private const val S_OK = 0
private const val E_NOINTERFACE = 0x80004002.toInt()
private const val E_POINTER = 0x80004003.toInt()
private const val RPC_E_CHANGED_MODE = 0x80010106.toInt()
private const val RO_INIT_MULTITHREADED = 1

// 全部槽位对照 Windows SDK 10.0.26100.0 (SystemMediaTransportControlsInterop.h & winrt/windows.media.h) 逐项核对
private const val SLOT_INTEROP_GET_FOR_WINDOW = 6

private const val SLOT_SMTC_PUT_PLAYBACK_STATUS = 7
private const val SLOT_SMTC_GET_DISPLAY_UPDATER = 8
private const val SLOT_SMTC_PUT_IS_ENABLED = 11
private const val SLOT_SMTC_PUT_IS_PLAY_ENABLED = 13
private const val SLOT_SMTC_PUT_IS_STOP_ENABLED = 15
private const val SLOT_SMTC_PUT_IS_PAUSE_ENABLED = 17
private const val SLOT_SMTC_PUT_IS_PREVIOUS_ENABLED = 25
private const val SLOT_SMTC_PUT_IS_NEXT_ENABLED = 27
private const val SLOT_SMTC_ADD_BUTTON_PRESSED = 32
private const val SLOT_SMTC_REMOVE_BUTTON_PRESSED = 33

private const val SLOT_UPDATER_PUT_TYPE = 7
private const val SLOT_UPDATER_GET_MUSIC_PROPS = 12
private const val SLOT_UPDATER_GET_VIDEO_PROPS = 13
private const val SLOT_UPDATER_CLEAR_ALL = 16
private const val SLOT_UPDATER_UPDATE = 17

private const val SLOT_MUSIC_PUT_TITLE = 7
private const val SLOT_MUSIC_PUT_ARTIST = 11
private const val SLOT_VIDEO_PUT_TITLE = 7
private const val SLOT_VIDEO_PUT_SUBTITLE = 9

private const val SLOT_ARGS_GET_BUTTON = 6

private const val MEDIA_STATUS_CLOSED = 0
private const val MEDIA_STATUS_CHANGING = 1
private const val MEDIA_STATUS_PLAYING = 3
private const val MEDIA_STATUS_PAUSED = 4
private const val MEDIA_TYPE_MUSIC = 1
private const val MEDIA_TYPE_VIDEO = 2

internal const val BUTTON_PLAY = 0
internal const val BUTTON_PAUSE = 1
internal const val BUTTON_STOP = 2
internal const val BUTTON_NEXT = 6
internal const val BUTTON_PREVIOUS = 7
