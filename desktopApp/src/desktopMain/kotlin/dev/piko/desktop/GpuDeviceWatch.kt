package dev.piko.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.piko.desktop.winrt.ComInterop
import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.shared.log.PikoLog
import java.awt.Container
import java.awt.Window
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.reflect.Field
import java.lang.reflect.Method
import org.jetbrains.skiko.SkiaLayer

/**
 * 显卡设备失效（device removed）时重建窗口，而不是等 skiko 崩掉整个进程。
 *
 * 驱动复位 GPU 引擎（NVIDIA 的 nvlddmkm 事件 153、TDR）、升级或崩溃时，系统把所有进程的 D3D 设备一律判为失效。
 * skiko 0.150 的 Direct3DRedrawer 不检查 HRESULT：失效后普通帧的失败被吞掉，画面冻住；下一次改窗口尺寸时
 * makeDirectXSurface 里 GetBuffer 失败留下空指针，接着解引用，JVM 直接崩溃（hs_err 里是 skiko-windows-x64.dll
 * 的 EXCEPTION_ACCESS_VIOLATION，栈顶 makeDirectXSurface）。升级 skiko 修不了：master 仍不检查，且升级被 MediaMP 卡住。
 *
 * 做法：问 skiko 手里那个 ID3D12Device 的 GetDeviceRemovedReason。失效了就不再碰这块画布，把 [generation] 加一，
 * [PikoWindow] 以它为 key 整个丢掉窗口再建，新窗口建新设备。skiko 公开的重建只有 SkiaLayer 的
 * removeNotify / addNotify，ComposeWindow 拆装面板的副作用没核实过，重建整个窗口更稳。
 * 两处问：改尺寸之前（[PixelAlignedContentEffect] 的布局里，正是崩溃的那一步）与每秒一次（[PikoWindow]），
 * 后者在画面冻住、用户还没去拉窗口时就发现。
 *
 * 取设备的路子与 MediaMP 的 SkiaDirectXInterop 相同：SkiaLayer.getRedrawer$skiko 取 redrawer，
 * Direct3DRedrawer.device 是 skiko 原生 DirectXDevice 结构体的指针，结构体里 ID3D12Device 在第 6 个指针槽
 * （skiko 0.150.1 directXRedrawer.cc：hWnd、GrD3DBackendContext 占 5 槽，之后是 device、swapChain）。
 * 这些是 skiko 的内部布局，升级 skiko 时要对照源码核对。
 */
internal object GpuDeviceWatch {
    /** 设备失效一次加一。所有窗口共用：GPU 复位时各窗口的设备一起失效。 */
    var generation by mutableIntStateOf(0)
        private set

    private var lastReportMillis = 0L
    private var attached = false

    // 反射或读内存出错一次就不再问，免得每次布局都抛；只影响这个兜底，不影响绘制
    private var disabled = !WinRTSupport.isWindows

    private val getRedrawer: Method by lazy { SkiaLayer::class.java.getMethod("getRedrawer\$skiko") }
    private val deviceField: Field by lazy {
        Class.forName(D3D_REDRAWER).getDeclaredField("device").apply { isAccessible = true }
    }

    /** 这个窗口的显卡设备失效了没有。失效时顺带报告，由调用方决定不再碰画布。 */
    fun checkLost(window: Window?): Boolean {
        if (disabled || window == null) return false
        val hr = try {
            removedReason(window) ?: return false
        } catch (e: Throwable) {
            disabled = true
            PikoLog.w(TAG, "读不到 skiko 的显卡设备，设备失效时无法自动重建窗口", e)
            return false
        }
        if (!attached) {
            // 问不到设备时（别的渲染后端、skiko 布局变了）这里一直安静，这一行说明兜底确实在起作用
            attached = true
            PikoLog.d(TAG, "已接上显卡设备检查")
        }
        if (hr == 0) return false
        report(hr)
        return true
    }

    // 几个窗口会在同一次失效里先后发现，只算一次
    private fun report(hr: Int) {
        val now = System.currentTimeMillis()
        if (now - lastReportMillis < REPORT_DEBOUNCE_MS) return
        lastReportMillis = now
        PikoLog.w(TAG, "显卡设备失效（HRESULT 0x%08X），重建窗口".format(hr))
        generation++
    }

    /** GetDeviceRemovedReason 的返回值，S_OK 为 0；拿不到 D3D12 设备（别的渲染后端、还没建好）时为 null。 */
    private fun removedReason(window: Window): Int? {
        val layer = findSkiaLayer(window) ?: return null
        val redrawer = getRedrawer.invoke(layer) ?: return null
        if (redrawer.javaClass.name != D3D_REDRAWER) return null
        val devicePtr = deviceField.getLong(redrawer)
        if (devicePtr == 0L) return null
        val d3d12 = MemorySegment.ofAddress(devicePtr)
            .reinterpret(ADDRESS.byteSize() * (DEVICE_SLOT + 1))
            .getAtIndex(ADDRESS, DEVICE_SLOT.toLong())
        if (d3d12.address() == 0L) return null
        return ComInterop.hresult(ComInterop.vtable(d3d12, GET_DEVICE_REMOVED_REASON, ComInterop.callThis), d3d12)
    }

    private fun findSkiaLayer(container: Container): SkiaLayer? {
        for (child in container.components) {
            if (child is SkiaLayer) return child
            if (child is Container) findSkiaLayer(child)?.let { return it }
        }
        return null
    }

    private const val TAG = "Gpu"
    private const val D3D_REDRAWER = "org.jetbrains.skiko.redrawer.Direct3DRedrawer"
    private const val DEVICE_SLOT = 6

    // ID3D12Device 虚表：IUnknown 3 项、ID3D12Object 4 项，之后数到 CreateFence 是 36，GetDeviceRemovedReason 是 37
    private const val GET_DEVICE_REMOVED_REASON = 37
    private const val REPORT_DEBOUNCE_MS = 3_000L
}
