package dev.piko.desktop.winrt

import java.awt.Frame
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Windows SMTC 原生集成测试：
 * 1. 验证真实 WinRT `ISystemMediaTransportControlsInterop.GetForWindow` 与 `add_ButtonPressed`（槽位 32）注册成功、
 *    获得非零 `EventRegistrationToken` 且 WinRT 实际回调了我们 COM 对象的 `QueryInterface`（Bilby 误调槽位 30 时不会发生）；
 * 2. 验证 `VideoProperties` / `MusicProperties`、`IsPreviousEnabled`（25）、`IsNextEnabled`（27）同步无异常；
 * 3. 通过 FFM 构造模拟 `ISystemMediaTransportControlsButtonPressedEventArgs` COM 对象，逐一验证 Play / Pause / Stop / Next / Previous 回调派发；
 * 4. 验证 `remove_ButtonPressed`（槽位 33）与关闭释放干净无崩溃。
 */
class WindowsSmtcNativeTest {

    @Test
    fun smtcRegistersButtonPressedHandlerAndDispatchesAllButtons() {
        if (!WinRTSupport.isWindows) return

        val events = CopyOnWriteArrayList<String>()
        val listener = object : SmtcActionListener {
            override fun onPlay() {
                events += "play"
            }

            override fun onPause() {
                events += "pause"
            }

            override fun onStop() {
                events += "stop"
            }

            override fun onNext() {
                events += "next"
            }

            override fun onPrevious() {
                events += "previous"
            }
        }

        val frame = createHiddenHostFrame()
        try {
            val hwnd = Win32Window.handleOf(frame)?.address() ?: 0L
            assertNotEquals(0L, hwnd, "Windows 宿主窗口必须具有有效 HWND")

            val session = WindowsSmtcSession(listener = listener, dispatchOnEdt = false)
            try {
                session.attach(hwnd)
                assertTrue(session.awaitIdle(5_000L), "SMTC 初始化超时")
                assertTrue(
                    session.lastAddButtonPressedHr >= 0,
                    "add_ButtonPressed (slot 32) 必须返回 S_OK，实际 HRESULT=0x%08X".format(session.lastAddButtonPressedHr),
                )
                assertTrue(session.isHandlerRegistered, "SMTC ButtonPressed 处理器必须已登记并拿到非零 EventRegistrationToken")
                assertNotEquals(0L, session.registeredToken, "EventRegistrationToken 不能为 0")
                assertTrue(
                    session.queryInterfaceHits > 0,
                    "WinRT add_ButtonPressed 必须实际调用过回调对象的 QueryInterface",
                )

                // 同步视频态与音频态，验证 IsEnabled/Play/Pause/Previous(25)/Next(27) 与 DisplayUpdater
                session.sync(
                    SmtcState(
                        active = true,
                        isPlaying = true,
                        title = "Piko SMTC Video Test",
                        subtitle = "1080p",
                        canPrevious = true,
                        canNext = true,
                        isVideo = true,
                    ),
                )
                session.sync(
                    SmtcState(
                        active = true,
                        isPlaying = false,
                        title = "Piko SMTC Audio Test",
                        subtitle = "Artist",
                        canPrevious = false,
                        canNext = true,
                        isVideo = false,
                    ),
                )
                assertTrue(session.awaitIdle(5_000L), "SMTC 状态同步超时")

                val callback = assertNotNull(session.callbackForTest, "SMTC 回调对象必须强引用存活")
                Arena.ofConfined().use { arena ->
                    val fakeArgs = FakeButtonPressedEventArgs(arena)
                    for (button in listOf(BUTTON_PLAY, BUTTON_PAUSE, BUTTON_STOP, BUTTON_NEXT, BUTTON_PREVIOUS)) {
                        fakeArgs.nextButton = button
                        val hr = callback.invoke(callback.segment, MemorySegment.NULL, fakeArgs.segment)
                        assertEquals(0, hr, "invoke 必须返回 S_OK")
                    }
                }
                assertEquals(listOf("play", "pause", "stop", "next", "previous"), events.toList())
            } finally {
                session.close()
            }
            assertFalse(session.isHandlerRegistered, "close() 后必须通过 slot 33 注销事件令牌")
        } finally {
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }

    private fun createHiddenHostFrame(): Frame {
        lateinit var frame: Frame
        SwingUtilities.invokeAndWait {
            frame = Frame("Piko-SMTC-Test").apply {
                isUndecorated = true
                setSize(1, 1)
                setLocation(-32000, -32000)
                isVisible = true
            }
        }
        return frame
    }

    /** 构造符合 `ISystemMediaTransportControlsButtonPressedEventArgs`（槽位 6 = `get_Button`）的最小 COM 桩。 */
    private class FakeButtonPressedEventArgs(arena: Arena) {
        var nextButton: Int = BUTTON_PAUSE
        val segment: MemorySegment

        init {
            val linker = Linker.nativeLinker()
            val getButtonStub = linker.upcallStub(
                MethodHandles.lookup().findVirtual(
                    FakeButtonPressedEventArgs::class.java,
                    "getButton",
                    MethodType.methodType(Int::class.javaPrimitiveType, MemorySegment::class.java, MemorySegment::class.java),
                ).bindTo(this),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
                arena,
            )
            val vtbl = arena.allocate(ADDRESS.byteSize() * 7L)
            vtbl.setAtIndex(ADDRESS, 6L, getButtonStub)
            segment = arena.allocate(ADDRESS).apply { set(ADDRESS, 0L, vtbl) }
        }

        @Suppress("unused")
        fun getButton(@Suppress("UNUSED_PARAMETER") thisPtr: MemorySegment, outButton: MemorySegment): Int {
            outButton.reinterpret(JAVA_INT.byteSize()).set(JAVA_INT, 0L, nextButton)
            return 0
        }
    }
}
