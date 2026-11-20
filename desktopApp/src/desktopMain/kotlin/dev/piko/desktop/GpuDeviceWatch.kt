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
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import org.jetbrains.skiko.SkiaLayer

/**
 * 显卡设备失效（device removed）时重建窗口，而不是等 Skiko 崩掉整个进程。
 *
 * 驱动复位 GPU 引擎（NVIDIA 事件 153、Windows TDR）、热升级或休眠异常时，系统把当前进程的 D3D12 设备
 * 判为失效。Skiko 的 Direct3DRenderer 不检查 HRESULT：失效后普通帧失败被吞掉、画面冻住；下一次改尺寸时
 * 交换链调整失败可能触发空指针崩溃。
 *
 * 在 Skiko 0.156.1 上从 `SkiaLayer.getFrameDriver$org_jetbrains_skiko_skiko()` → `FrameDriver.getRenderer()`
 * 取出 `Direct3DRenderer.device`，读取第 2 个指针槽（`+0x10`）上的 `ID3D12Device*`，经缓存的 FFM 虚表句柄
 * 调用第 37 项 `GetDeviceRemovedReason`。一旦返回非 0（非 `S_OK`），递增 [generation] 驱动 [PikoWindow]
 * 重建窗口与新设备。
 */
internal object GpuDeviceWatch {
    /** 设备失效一次加一。所有窗口共用：GPU 复位时各窗口的设备一起失效。 */
    var generation by mutableIntStateOf(0)
        private set

    private var lastReportMillis = 0L
    private var attached = false

    // 反射或读内存出错一次就不再问，免得每次布局都抛；只影响这个兜底，不影响绘制
    private var disabled = !WinRTSupport.isWindows

    private val getFrameDriver: Method by lazy {
        SkiaLayer::class.java.getMethod("getFrameDriver\$org_jetbrains_skiko_skiko")
    }
    private val getRenderer: Method by lazy {
        Class.forName(FRAME_DRIVER).getDeclaredMethod("getRenderer").apply { isAccessible = true }
    }
    private val deviceField: Field by lazy {
        Class.forName(D3D_RENDERER).getDeclaredField("device").apply { isAccessible = true }
    }

    // 缓存每个窗口对应的 SkiaLayer 弱引用，避免 1Hz 定时检查与窗口缩放布局反复遍历 AWT 组件树
    private val layerCache = WeakHashMap<Window, WeakReference<SkiaLayer>>()

    /** 这个窗口的显卡设备失效了没有。失效时顺带报告，由调用方决定不再碰画布。 */
    fun checkLost(window: Window?): Boolean {
        if (disabled || window == null) return false
        val hr = try {
            removedReason(window) ?: return false
        } catch (e: Throwable) {
            disabled = true
            PikoLog.w(TAG, "Failed to inspect Skiko D3D12 device; automatic window recovery on GPU reset disabled", e)
            return false
        }
        if (!attached) {
            // 问不到设备时（别的渲染后端、skiko 布局变了）这里一直安静，这一行说明兜底确实在起作用
            attached = true
            PikoLog.d(TAG, "Attached Skiko D3D12 device-removed watch")
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
        PikoLog.w(TAG, "D3D12 device removed (HRESULT 0x%08X); recreating window".format(hr))
        generation++
    }

    /** GetDeviceRemovedReason 的返回值，S_OK 为 0；拿不到 D3D12 设备（别的渲染后端、还没建好）时为 null。 */
    private fun removedReason(window: Window): Int? {
        val layer = cachedSkiaLayer(window) ?: return null
        val driver = getFrameDriver.invoke(layer) ?: return null
        val renderer = getRenderer.invoke(driver) ?: return null
        if (renderer.javaClass.name != D3D_RENDERER) return null
        val devicePtr = deviceField.getLong(renderer)
        if (devicePtr == 0L) return null
        val d3d12 = MemorySegment.ofAddress(devicePtr)
            .reinterpret(ADDRESS.byteSize() * (DEVICE_SLOT + 1))
            .getAtIndex(ADDRESS, DEVICE_SLOT.toLong())
        if (d3d12.address() == 0L) return null
        return ComInterop.invokeVtable(d3d12, GET_DEVICE_REMOVED_REASON, ComInterop.callThis, d3d12)
    }

    private fun cachedSkiaLayer(window: Window): SkiaLayer? {
        val cached = synchronized(layerCache) { layerCache[window]?.get() }
        if (cached != null && cached.isDisplayable) return cached
        val found = findSkiaLayer(window) ?: return null
        synchronized(layerCache) { layerCache[window] = WeakReference(found) }
        return found
    }

    private fun findSkiaLayer(container: Container): SkiaLayer? {
        for (child in container.components) {
            if (child is SkiaLayer) return child
            if (child is Container) findSkiaLayer(child)?.let { return it }
        }
        return null
    }

    private const val TAG = "Gpu"
    private const val FRAME_DRIVER = "org.jetbrains.skiko.renderer.FrameDriver"
    private const val D3D_RENDERER = "org.jetbrains.skiko.renderer.Direct3DRenderer"
    private const val DEVICE_SLOT = 2

    // ID3D12Device 虚表：IUnknown 3 项、ID3D12Object 4 项，之后数到 CreateFence 是 36，GetDeviceRemovedReason 是 37
    private const val GET_DEVICE_REMOVED_REASON = 37
    private const val REPORT_DEBOUNCE_MS = 3_000L
}
