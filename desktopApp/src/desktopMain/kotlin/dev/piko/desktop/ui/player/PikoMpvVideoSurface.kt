package dev.piko.desktop.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import dev.piko.desktop.isLinux
import dev.piko.desktop.isMacOs
import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.shared.log.PikoLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ContentChangeMode
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.GLBackendState
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Pixmap
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skiko.SkiaLayer
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.mpv.JvmMpvMediampPlayer
import org.openani.mediamp.mpv.MPVHandle
import org.openani.mediamp.mpv.MpvMediampPlayer
import org.openani.mediamp.mpv.nAckRetiredBuffersD3D11
import org.openani.mediamp.mpv.nAckRetiredBuffersMacos
import org.openani.mediamp.mpv.nAckRetiredBuffersOpenGL
import org.openani.mediamp.mpv.nCanShareFramesD3D11
import org.openani.mediamp.mpv.nCopyLatestFrameD3D11
import org.openani.mediamp.mpv.nCopyLatestFrameMacos
import org.openani.mediamp.mpv.nCreateOpenGLConsumerFbo
import org.openani.mediamp.mpv.nDeleteOpenGLConsumerFbo
import org.openani.mediamp.mpv.nDestroyRenderContextOpenGL
import org.openani.mediamp.mpv.nGetBufferTextureD3D11
import org.openani.mediamp.mpv.nGetBufferTextureMacos
import org.openani.mediamp.mpv.nGetBufferTextureOpenGL
import org.openani.mediamp.mpv.nGetFrameStateD3D11
import org.openani.mediamp.mpv.nGetFrameStateMacos
import org.openani.mediamp.mpv.nGetFrameStateOpenGL
import org.openani.mediamp.mpv.nHasOpenGLSurface
import org.openani.mediamp.mpv.nReadSurfacePixelsOpenGL
import org.openani.mediamp.mpv.nSetConsumerDeviceHintD3D11
import org.openani.mediamp.mpv.nSetReadbackSurfaceConfigD3D11
import org.openani.mediamp.mpv.nSetReadbackSurfaceConfigMacos
import org.openani.mediamp.mpv.nSetSurfaceConfigD3D11
import org.openani.mediamp.mpv.nSetSurfaceConfigMacos
import org.openani.mediamp.mpv.nSetSurfaceConfigOpenGL
import java.awt.Component
import java.awt.Container
import java.awt.Frame
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.WindowStateListener
import java.awt.Window
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.math.min

/**
 * 供播放自检读取的轻量观测器，普通播放传 `null` 零额外开销。
 */
internal class VideoSurfaceProbe {
    @Volatile
    var drawnFrames: Long = 0L

    @Volatile
    var distinctStates: Int = 0

    @Volatile
    var nonBlackObserved: Boolean = false

    @Volatile
    var latestNonBlack: Boolean = false

    @Volatile
    var activeMode: String = "NONE"

    private var lastRecordedState: Long = 0L

    fun onFrameReady(state: Long, image: Image, directContext: DirectContext?) {
        drawnFrames += 1L
        val stateAdvanced = state != 0L && state != lastRecordedState
        if (stateAdvanced) {
            lastRecordedState = state
            distinctStates += 1
        }
        if (distinctStates >= 2 && (!nonBlackObserved || (stateAdvanced && distinctStates % 8 == 0))) {
            val nonBlack = sampleNonBlack(image, directContext)
            latestNonBlack = nonBlack
            if (nonBlack) nonBlackObserved = true
        }
    }

    private fun sampleNonBlack(image: Image, directContext: DirectContext?): Boolean {
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0) return false
        val sw = min(w, 8)
        val sh = min(h, 8)
        val anchors = intArrayOf(
            (w - sw) / 2, (h - sh) / 2,
            ((w - sw) / 4).coerceAtLeast(0), ((h - sh) / 4).coerceAtLeast(0),
            ((w - sw) * 3 / 4).coerceAtLeast(0), ((h - sh) / 4).coerceAtLeast(0),
            ((w - sw) / 4).coerceAtLeast(0), ((h - sh) * 3 / 4).coerceAtLeast(0),
            ((w - sw) * 3 / 4).coerceAtLeast(0), ((h - sh) * 3 / 4).coerceAtLeast(0),
        )
        val bmp = Bitmap()
        return try {
            val info = ImageInfo(sw, sh, ColorType.RGBA_8888, image.imageInfo.colorAlphaType, image.imageInfo.colorSpace)
            if (!bmp.allocPixels(info)) return false
            for (p in anchors.indices step 2) {
                if (!image.readPixels(directContext, bmp, anchors[p], anchors[p + 1], false)) continue
                val pixmap = bmp.peekPixels() ?: continue
                try {
                    val bytes = MemorySegment.ofAddress(pixmap.addr).reinterpret((sw * sh * 4).toLong())
                    for (i in 0 until sw * sh) {
                        val off = (i * 4).toLong()
                        val r = bytes.get(java.lang.foreign.ValueLayout.JAVA_BYTE, off).toInt() and 0xFF
                        val g = bytes.get(java.lang.foreign.ValueLayout.JAVA_BYTE, off + 1).toInt() and 0xFF
                        val b = bytes.get(java.lang.foreign.ValueLayout.JAVA_BYTE, off + 2).toInt() and 0xFF
                        if (r > 16 || g > 16 || b > 16) return true
                    }
                } finally {
                    pixmap.close()
                }
            }
            false
        } catch (_: Throwable) {
            false
        } finally {
            bmp.close()
        }
    }
}

@OptIn(InternalMediampApi::class)
internal fun hasActiveLinuxVideoSurface(player: MediampPlayer): Boolean {
    val mpvPlayer = player as? MpvMediampPlayer ?: return false
    val ptr = MediampAccess.getHandlePtr(mpvPlayer)
    return ptr != 0L && nHasOpenGLSurface(ptr)
}

internal fun currentMpvVo(player: MediampPlayer): String? {
    val mpvPlayer = player as? MpvMediampPlayer ?: return null
    return MediampAccess.getPropertyString(mpvPlayer, "current-vo")
}

/**
 * 在 AWT EDT 上查询 Linux `LinuxOpenGLRenderer` 当前窗口上下文绑定的 `GL_DRAW_FRAMEBUFFER_BINDING`。
 * 若 Skiko 并非运行于硬件 `LINUX_OPENGL` 后端，返回 `null`。
 */
internal fun queryLinuxWindowDrawFbo(window: Window): Int? {
    if (!isLinux || !SwingUtilities.isEventDispatchThread()) return null
    val layer = findSkiaLayer(window) ?: return null
    val env = SkikoAccess.inspect(layer)
    if (env.kind != SkikoBackendKind.LINUX_OPENGL || !env.isReady) return null
    var binding: Int? = null
    val ok = SkikoAccess.withLinuxGlCurrent(env, restoreDefaultFramebuffer = false) {
        binding = LinuxGl.getDrawFramebufferBinding()
    }
    return if (ok) binding else null
}

private object LinuxGl {
    const val GL_FRAMEBUFFER = 0x8D40
    const val GL_DRAW_FRAMEBUFFER_BINDING = 0x8CA6

    private val libGl: SymbolLookup? by lazy {
        if (!isLinux) return@lazy null
        runCatching { SymbolLookup.libraryLookup("libGL.so.1", Arena.global()) }
            .onFailure { PikoLog.w(TAG, "Failed to load libGL.so.1", it) }
            .getOrNull()
    }

    private val glxGetProcAddress: MethodHandle? by lazy {
        val lookup = libGl ?: return@lazy null
        val sym = lookup.find("glXGetProcAddressARB").or { lookup.find("glXGetProcAddress") }.orElse(null)
            ?: return@lazy null
        Linker.nativeLinker().downcallHandle(sym, FunctionDescriptor.of(ADDRESS, ADDRESS))
    }

    private fun resolveGlSymbol(name: String): MemorySegment {
        val lookup = libGl ?: return MemorySegment.NULL
        val viaProc = runCatching {
            val getProc = glxGetProcAddress ?: return@runCatching MemorySegment.NULL
            Arena.ofConfined().use { arena ->
                getProc.invokeExact(arena.allocateFrom(name)) as MemorySegment
            }
        }.getOrDefault(MemorySegment.NULL)
        if (viaProc != MemorySegment.NULL) return viaProc
        return lookup.find(name).orElse(MemorySegment.NULL)
    }

    private val glBindFramebufferHandle: MethodHandle? by lazy {
        val addr = resolveGlSymbol("glBindFramebuffer")
            .takeIf { it != MemorySegment.NULL }
            ?: resolveGlSymbol("glBindFramebufferEXT")
        if (addr == MemorySegment.NULL) null
        else Linker.nativeLinker().downcallHandle(addr, FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT))
    }

    private val glGetIntegervHandle: MethodHandle? by lazy {
        val addr = resolveGlSymbol("glGetIntegerv")
        if (addr == MemorySegment.NULL) null
        else Linker.nativeLinker().downcallHandle(addr, FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS))
    }

    fun bindDefaultFramebuffer(): Boolean {
        val handle = glBindFramebufferHandle ?: return false
        return runCatching {
            handle.invokeExact(GL_FRAMEBUFFER, 0)
            true
        }.getOrDefault(false)
    }

    fun getDrawFramebufferBinding(): Int? {
        val handle = glGetIntegervHandle ?: return null
        return runCatching {
            Arena.ofConfined().use { arena ->
                val out = arena.allocate(JAVA_INT)
                handle.invokeExact(GL_DRAW_FRAMEBUFFER_BINDING, out)
                out.get(JAVA_INT, 0L)
            }
        }.getOrNull()
    }
}

/**
 * 桌面端 mpv 视频表面，直接对接 Skiko 0.156.1 的 `FrameDriver` / `Direct3DRenderer` / `MetalRenderer` /
 * `LinuxOpenGLRenderer` 与 MediaMP 0.5.3 的 `MPVHandleDesktop` 共享纹理/回读 JNI，不再依赖 MediaMP
 * 自带表面对旧 Skiko 0.150 `SkiaLayer.getRedrawer$skiko()` 的反射。
 *
 * - **Windows**：从 Skiko `Direct3DRenderer` 读取 `ID3D12Device*`（`+0x10`）与 `ID3D12CommandQueue*`（`+0x20`），
 *   构造 72 字节（`0x48`）离堆 shim 结构供 `mediampv.dll` 走 D3D11→D3D12 三缓冲零拷贝环；跨卡或非 Direct3D
 *   渲染器自动回退至 D3D11 CPU readback。
 * - **macOS**：从 Skiko `MetalRenderer` 读取 `MetalAdapter.ptr` 与 `DirectContext` 走 Metal 共享纹理环，
 *   非 Metal 时回退至 macOS CPU readback。
 * - **Linux**：从 Skiko `LinuxOpenGLRenderer` 读取共享上下文、AWT 组件与 X11 句柄，经 MediaMP
 *   `OpenGLRenderContextLifecycle.attachRenderEnvironment` 走 GLX 零拷贝 FBO 环，并在每次创建/销毁消费者
 *   FBO 及快照采样前显式绑定当前窗口的 GLX 上下文以隔离多窗口 EDT 上下文切换；当 Skiko 退回软件渲染
 *   （`--disable-gpu`、`SOFTWARE_FAST` 或 `llvmpipe`）时自动创建离屏 GLX 上下文并经 `nReadSurfacePixelsOpenGL` 回读渲染。
 * - 窗口最小化（`Frame.ICONIFIED`）时跳过递增 `frameTick`，避免向不可见窗口提交 GPU 帧。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PikoMpvVideoSurface(
    player: MediampPlayer,
    modifier: Modifier = Modifier,
    probe: VideoSurfaceProbe? = null,
) {
    val mpvPlayer = player as? MpvMediampPlayer ?: return
    val window = LocalAwtWindow.current
    val layer = remember(window) { window?.let(::findSkiaLayer) }
    val controller = remember(mpvPlayer) { PikoSurfaceController(mpvPlayer) }

    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    var rendererGeneration by remember(layer) { mutableLongStateOf(0L) }
    val frameTick = remember { mutableLongStateOf(0L) }
    val minimized = remember(window) {
        AtomicBoolean((window as? Frame)?.let { (it.extendedState and Frame.ICONIFIED) != 0 } ?: false)
    }
    val missedFrameWhileMinimized = remember(controller) { AtomicBoolean(false) }
    val envCache = remember(layer) { CachedRenderEnv() }

    fun inspectCached(generation: Long): SkikoRenderEnvironment =
        envCache.inspect(layer, generation)

    DisposableEffect(window, controller) {
        val frame = window as? Frame ?: return@DisposableEffect onDispose {}
        fun updateMinimized(isMinimized: Boolean) {
            val wasMinimized = minimized.getAndSet(isMinimized)
            if (wasMinimized && !isMinimized && missedFrameWhileMinimized.getAndSet(false)) {
                if (SwingUtilities.isEventDispatchThread()) {
                    frameTick.longValue += 1L
                } else {
                    SwingUtilities.invokeLater { frameTick.longValue += 1L }
                }
            }
        }
        fun syncMinimized() = updateMinimized((frame.extendedState and Frame.ICONIFIED) != 0)
        syncMinimized()
        val stateListener = WindowStateListener { syncMinimized() }
        val windowListener = object : WindowAdapter() {
            override fun windowIconified(e: WindowEvent?) = updateMinimized(true)
            override fun windowDeiconified(e: WindowEvent?) = updateMinimized(false)
        }
        frame.addWindowStateListener(stateListener)
        frame.addWindowListener(windowListener)
        onDispose {
            frame.removeWindowStateListener(stateListener)
            frame.removeWindowListener(windowListener)
        }
    }

    LaunchedEffect(layer) {
        if (layer == null) return@LaunchedEffect
        var probeCount = 0
        while (SkikoAccess.currentRenderer(layer) == null && probeCount < MAX_INITIAL_PROBES) {
            delay(INITIAL_PROBE_DELAY_MS)
            probeCount++
        }
        rendererGeneration++
        SkikoAccess.rendererChanges(layer).collect {
            rendererGeneration++
        }
    }

    DisposableEffect(controller, minimized) {
        val listenerAttached = controller.attachRenderUpdateListener {
            if (minimized.get()) {
                missedFrameWhileMinimized.set(true)
            } else {
                missedFrameWhileMinimized.set(false)
                frameTick.longValue += 1L
            }
        }
        if (!listenerAttached) {
            PikoLog.w(TAG, "Failed to attach mpv render update listener")
        }
        onDispose {
            controller.release()
        }
    }

    LaunchedEffect(controller, layer, surfaceSize, rendererGeneration) {
        val width = surfaceSize.width
        val height = surfaceSize.height
        if (width <= 0 || height <= 0) return@LaunchedEffect

        repeat(MAX_SURFACE_CONFIG_ATTEMPTS) {
            val env = inspectCached(rendererGeneration)
            if (controller.configureIfReady(width, height, env)) return@LaunchedEffect
            delay(SURFACE_CONFIG_RETRY_DELAY_MS)
        }
    }

    Canvas(
        modifier = modifier.onSizeChanged { newSize ->
            surfaceSize = newSize
        },
    ) {
        frameTick.longValue
        val gen = rendererGeneration
        if (controller.isTeardownStarted()) return@Canvas

        val width = size.width.toInt()
        val height = size.height.toInt()
        if (width <= 0 || height <= 0) return@Canvas

        val env = inspectCached(gen)
        controller.drawCurrentFrame(width, height, env, probe) { image ->
            val srcWidth = image.width.toFloat()
            val srcHeight = image.height.toFloat()
            if (srcWidth <= 0f || srcHeight <= 0f) return@drawCurrentFrame

            val scale = min(size.width / srcWidth, size.height / srcHeight)
            val dstWidth = srcWidth * scale
            val dstHeight = srcHeight * scale
            val dstLeft = (size.width - dstWidth) * 0.5f
            val dstTop = (size.height - dstHeight) * 0.5f

            drawContext.canvas.skiaCanvas.drawImageRect(
                image,
                Rect.makeWH(srcWidth, srcHeight),
                Rect.makeXYWH(dstLeft, dstTop, dstWidth, dstHeight),
                SamplingMode.LINEAR,
                null,
                true,
            )
        }
    }
}

/**
 * 非 Compose 状态的渲染环境缓存。Skiko 0.156.1 开启 `direct3DSynchronousLiveResize` 时，
 * Canvas 绘制会在 Win32 工具包线程（`AWT-Windows`）同步执行，严禁在该路径写 Compose `MutableState`。
 */
private class CachedRenderEnv {
    private val lock = Any()
    private var cachedGen = -1L
    private var cached: SkikoRenderEnvironment? = null

    fun inspect(layer: SkiaLayer?, generation: Long): SkikoRenderEnvironment {
        synchronized(lock) {
            val existing = cached
            if (existing != null && cachedGen == generation && existing.isReady) {
                return existing
            }
        }
        val fresh = SkikoAccess.inspect(layer)
        if (fresh.isReady) {
            synchronized(lock) {
                cached = fresh
                cachedGen = generation
            }
        }
        return fresh
    }
}

private enum class SkikoBackendKind {
    DIRECT3D,
    METAL,
    LINUX_OPENGL,
    OTHER,
}

private data class SkikoRenderEnvironment(
    val hasRenderer: Boolean,
    val kind: SkikoBackendKind,
    val renderer: Any? = null,
    val nativeDevicePtr: Long = 0L,
    val directContext: DirectContext? = null,
    val glComponent: Component? = null,
    val glShareContext: Long = 0L,
    val glDrawable: Long = 0L,
    val glWindow: Long = 0L,
) {
    val isReady: Boolean
        get() = hasRenderer && when (kind) {
            SkikoBackendKind.DIRECT3D,
            SkikoBackendKind.METAL,
            -> nativeDevicePtr != 0L && directContext != null && !directContext.isClosed
            SkikoBackendKind.LINUX_OPENGL ->
                glComponent != null && glShareContext != 0L && glDrawable != 0L &&
                    directContext != null && !directContext.isClosed
            SkikoBackendKind.OTHER -> !isLinux || (glComponent != null && glDrawable != 0L)
        }
}

private object SkikoAccess {
    private const val FRAME_DRIVER_CLASS = "org.jetbrains.skiko.renderer.FrameDriver"
    private const val D3D_RENDERER_CLASS = "org.jetbrains.skiko.renderer.Direct3DRenderer"
    private const val METAL_RENDERER_CLASS = "org.jetbrains.skiko.renderer.MetalRenderer"
    private const val METAL_ADAPTER_CLASS = "org.jetbrains.skiko.MetalAdapter"
    private const val ABSTRACT_OPENGL_RENDERER_CLASS = "org.jetbrains.skiko.renderer.AbstractOpenGLRenderer"
    private const val LINUX_OPENGL_RENDERER_CLASS = "org.jetbrains.skiko.renderer.LinuxOpenGLRenderer"
    private const val LINUX_OPENGL_RENDERER_KT_CLASS = "org.jetbrains.skiko.renderer.LinuxOpenGLRendererKt"
    private const val AWT_LINUX_DRAWING_SURFACE_KT_CLASS = "org.jetbrains.skiko.AWTLinuxDrawingSurfaceKt"
    private const val HARDWARE_LAYER_CLASS = "org.jetbrains.skiko.HardwareLayer"
    private const val LINUX_DRAWING_SURFACE_CLASS = "org.jetbrains.skiko.LinuxDrawingSurface"
    private const val OPENGL_LIBRARY_KT_CLASS = "org.jetbrains.skiko.OpenGLLibrary_jvmKt"

    private val getFrameDriver: Method? by lazy {
        runCatching {
            SkiaLayer::class.java.getMethod("getFrameDriver\$org_jetbrains_skiko_skiko")
        }.onFailure { PikoLog.w(TAG, "Failed to reflect SkiaLayer.getFrameDriver", it) }.getOrNull()
    }

    private val getRenderer: Method? by lazy {
        runCatching {
            Class.forName(FRAME_DRIVER_CLASS).getDeclaredMethod("getRenderer").apply { isAccessible = true }
        }.onFailure { PikoLog.w(TAG, "Failed to reflect FrameDriver.getRenderer", it) }.getOrNull()
    }

    private val d3dDeviceField: Field? by lazy {
        runCatching {
            Class.forName(D3D_RENDERER_CLASS).getDeclaredField("device").apply { isAccessible = true }
        }.onFailure { PikoLog.w(TAG, "Failed to reflect Direct3DRenderer.device", it) }.getOrNull()
    }

    private val d3dContextField: Field? by lazy {
        runCatching {
            Class.forName(D3D_RENDERER_CLASS).getDeclaredField("context").apply { isAccessible = true }
        }.onFailure { PikoLog.w(TAG, "Failed to reflect Direct3DRenderer.context", it) }.getOrNull()
    }

    private val metalAdapterField: Field? by lazy {
        runCatching {
            Class.forName(METAL_RENDERER_CLASS).getDeclaredField("adapter").apply { isAccessible = true }
        }.onFailure { PikoLog.w(TAG, "Failed to reflect MetalRenderer.adapter", it) }.getOrNull()
    }

    private val metalAdapterGetPtr: Method? by lazy {
        runCatching {
            Class.forName(METAL_ADAPTER_CLASS).getMethod("getPtr")
        }.onFailure { PikoLog.w(TAG, "Failed to reflect MetalAdapter.getPtr", it) }.getOrNull()
    }

    private val metalContextField: Field? by lazy {
        runCatching {
            Class.forName(METAL_RENDERER_CLASS).getDeclaredField("context").apply { isAccessible = true }
        }.onFailure { PikoLog.w(TAG, "Failed to reflect MetalRenderer.context", it) }.getOrNull()
    }

    private val linuxGlShareContextField: Field? by lazy {
        runCatching {
            Class.forName(LINUX_OPENGL_RENDERER_CLASS).getDeclaredField("context").apply { isAccessible = true }
        }.onFailure { PikoLog.w(TAG, "Failed to reflect LinuxOpenGLRenderer.context", it) }.getOrNull()
    }

    private val linuxLockedSurfaceField: Field? by lazy {
        runCatching {
            Class.forName(LINUX_OPENGL_RENDERER_CLASS).getDeclaredField("lockedDrawingSurface").apply { isAccessible = true }
        }.onFailure { PikoLog.w(TAG, "Failed to reflect LinuxOpenGLRenderer.lockedDrawingSurface", it) }.getOrNull()
    }

    private val linuxLockDrawingSurfaceMethod: Method? by lazy {
        runCatching {
            Class.forName(LINUX_OPENGL_RENDERER_CLASS).getMethod("lockDrawingSurface\$org_jetbrains_skiko_skiko")
        }.onFailure { PikoLog.w(TAG, "Failed to reflect LinuxOpenGLRenderer.lockDrawingSurface", it) }.getOrNull()
    }

    private val linuxUnlockDrawingSurfaceMethod: Method? by lazy {
        runCatching {
            Class.forName(LINUX_OPENGL_RENDERER_CLASS).getMethod("unlockDrawingSurface\$org_jetbrains_skiko_skiko")
        }.onFailure { PikoLog.w(TAG, "Failed to reflect LinuxOpenGLRenderer.unlockDrawingSurface", it) }.getOrNull()
    }

    private val linuxMakeCurrentMethod: Method? by lazy {
        runCatching {
            Class.forName(LINUX_OPENGL_RENDERER_CLASS).getMethod("makeCurrent\$org_jetbrains_skiko_skiko")
        }.onFailure { PikoLog.w(TAG, "Failed to reflect LinuxOpenGLRenderer.makeCurrent", it) }.getOrNull()
    }

    private val openGlContextField: Field? by lazy {
        runCatching {
            Class.forName(ABSTRACT_OPENGL_RENDERER_CLASS).getDeclaredField("glContext").apply { isAccessible = true }
        }.onFailure { PikoLog.w(TAG, "Failed to reflect AbstractOpenGLRenderer.glContext", it) }.getOrNull()
    }

    private val getBackedLayer: Method? by lazy {
        runCatching {
            SkiaLayer::class.java.getMethod("getBackedLayer\$org_jetbrains_skiko_skiko")
        }.onFailure { PikoLog.w(TAG, "Failed to reflect SkiaLayer.getBackedLayer", it) }.getOrNull()
    }

    private val loadOpenGlLibraryMethod: Method? by lazy {
        runCatching {
            Class.forName(OPENGL_LIBRARY_KT_CLASS).getMethod("loadOpenGLLibrary")
        }.getOrNull()
    }

    private val lockHardwareSurfaceMethod: Method? by lazy {
        runCatching {
            val hwClass = Class.forName(HARDWARE_LAYER_CLASS)
            Class.forName(AWT_LINUX_DRAWING_SURFACE_KT_CLASS).getMethod("lockLinuxDrawingSurface", hwClass)
        }.getOrNull()
    }

    private val unlockHardwareSurfaceMethod: Method? by lazy {
        runCatching {
            val surfClass = Class.forName(LINUX_DRAWING_SURFACE_CLASS)
            Class.forName(AWT_LINUX_DRAWING_SURFACE_KT_CLASS).getMethod("unlockLinuxDrawingSurface", surfClass)
        }.getOrNull()
    }

    private val createGlxContextMethod: Method? by lazy {
        runCatching {
            val surfClass = Class.forName(LINUX_DRAWING_SURFACE_CLASS)
            Class.forName(LINUX_OPENGL_RENDERER_KT_CLASS)
                .getDeclaredMethod("access\$createContext", surfClass, java.lang.Boolean.TYPE)
                .apply { isAccessible = true }
        }.getOrNull()
    }

    private val destroyGlxContextMethod: Method? by lazy {
        runCatching {
            val surfClass = Class.forName(LINUX_DRAWING_SURFACE_CLASS)
            Class.forName(LINUX_OPENGL_RENDERER_KT_CLASS)
                .getDeclaredMethod("access\$destroyContext", surfClass, java.lang.Long.TYPE)
                .apply { isAccessible = true }
        }.getOrNull()
    }

    private val rendererFlows = WeakHashMap<SkiaLayer, MutableStateFlow<Long>>()

    fun rendererChanges(layer: SkiaLayer): MutableStateFlow<Long> = synchronized(rendererFlows) {
        rendererFlows.getOrPut(layer) {
            val flow = MutableStateFlow(0L)
            val weakFlow = WeakReference(flow)
            layer.onStateChanged(SkiaLayer.PropertyKind.Renderer) {
                weakFlow.get()?.let { target -> target.value += 1L }
            }
            flow
        }
    }

    fun currentRenderer(layer: SkiaLayer?): Any? {
        if (layer == null) return null
        val driver = runCatching { getFrameDriver?.invoke(layer) }.getOrNull() ?: return null
        return runCatching { getRenderer?.invoke(driver) }.getOrNull()
    }

    fun inspect(layer: SkiaLayer?): SkikoRenderEnvironment {
        val renderer = currentRenderer(layer)
            ?: return SkikoRenderEnvironment(hasRenderer = false, kind = SkikoBackendKind.OTHER)
        return when (renderer.javaClass.name) {
            D3D_RENDERER_CLASS -> {
                val devicePtr = runCatching { d3dDeviceField?.getLong(renderer) ?: 0L }.getOrDefault(0L)
                val directContext = runCatching { d3dContextField?.get(renderer) as? DirectContext }.getOrNull()
                SkikoRenderEnvironment(
                    hasRenderer = true,
                    kind = SkikoBackendKind.DIRECT3D,
                    renderer = renderer,
                    nativeDevicePtr = devicePtr,
                    directContext = directContext,
                )
            }
            METAL_RENDERER_CLASS -> {
                val adapter = runCatching { metalAdapterField?.get(renderer) }.getOrNull()
                val mtlDevicePtr = runCatching {
                    adapter?.let { metalAdapterGetPtr?.invoke(it) as? Long } ?: 0L
                }.getOrDefault(0L)
                val directContext = runCatching { metalContextField?.get(renderer) as? DirectContext }.getOrNull()
                SkikoRenderEnvironment(
                    hasRenderer = true,
                    kind = SkikoBackendKind.METAL,
                    renderer = renderer,
                    nativeDevicePtr = mtlDevicePtr,
                    directContext = directContext,
                )
            }
            LINUX_OPENGL_RENDERER_CLASS -> {
                val shareContext = runCatching { linuxGlShareContextField?.getLong(renderer) ?: 0L }.getOrDefault(0L)
                val directContext = runCatching { openGlContextField?.get(renderer) as? DirectContext }.getOrNull()
                val backedLayer = runCatching { layer?.let { getBackedLayer?.invoke(it) as? Component } }.getOrNull()
                val drawable = runCatching { layer?.contentHandle ?: 0L }.getOrDefault(0L)
                val window = runCatching { layer?.windowHandle ?: 0L }.getOrDefault(0L)
                SkikoRenderEnvironment(
                    hasRenderer = true,
                    kind = SkikoBackendKind.LINUX_OPENGL,
                    renderer = renderer,
                    directContext = directContext,
                    glComponent = backedLayer,
                    glShareContext = shareContext,
                    glDrawable = drawable,
                    glWindow = window,
                )
            }
            else -> {
                val backedLayer = if (isLinux) {
                    runCatching { layer?.let { getBackedLayer?.invoke(it) as? Component } }.getOrNull()
                } else {
                    null
                }
                val drawable = if (isLinux && backedLayer?.isDisplayable == true) {
                    runCatching { layer?.contentHandle ?: 0L }.getOrDefault(0L)
                } else {
                    0L
                }
                val window = if (isLinux && backedLayer?.isDisplayable == true) {
                    runCatching { layer?.windowHandle ?: 0L }.getOrDefault(0L)
                } else {
                    0L
                }
                SkikoRenderEnvironment(
                    hasRenderer = true,
                    kind = SkikoBackendKind.OTHER,
                    renderer = renderer,
                    glComponent = backedLayer,
                    glDrawable = drawable,
                    glWindow = window,
                )
            }
        }
    }

    /**
     * 在当前窗口所属的 `LinuxOpenGLRenderer` GLX 上下文绑定状态下执行 [block]。
     *
     * Skiko 在 Linux 上于同一条 AWT EDT 上串联所有窗口的 `SkiaLayer.update`（录制 `Picture`）与
     * `renderFrame`（`makeCurrent` + 绘制）；`Canvas` 回调位于 `update` 阶段，此时线程上绑定的往往是
     * 另一个窗口（如主窗口）的 `GLXContext`。显式锁定当前窗口的 `LinuxDrawingSurface` 并 `makeCurrent`
     * 可彻底隔离多窗口 GLX 上下文串扰，亦防止关窗释放时误删其他窗口的同名 FBO。
     *
     * 退出前默认将 `GL_FRAMEBUFFER` 恢复为 `0` 并重置 Skia `GLBackendState.RENDER_TARGET`：
     * `blitSurface` 离屏拷贝 flush 后会把 `GL_DRAW_FRAMEBUFFER_BINDING` 留在非零离屏 FBO 上，若不归零，
     * 同帧紧随其后的 `AbstractOpenGLRenderer.initSurface()` 在窗口缩放或进出全屏（`isSizeChanged == true`）时
     * 会误将离屏 FBO ID 捕获为窗口默认渲染目标，导致窗口后备缓冲停止更新并在前后缓冲间乒乓跳帧（issue #60）。
     */
    fun withLinuxGlCurrent(
        env: SkikoRenderEnvironment?,
        restoreDefaultFramebuffer: Boolean = true,
        block: () -> Unit,
    ): Boolean {
        if (env == null || env.kind != SkikoBackendKind.LINUX_OPENGL) return false
        if (!SwingUtilities.isEventDispatchThread()) return false
        val renderer = env.renderer ?: return false
        val component = env.glComponent ?: return false
        if (!component.isDisplayable) return false
        val directContext = env.directContext
        if (directContext == null || directContext.isClosed) return false
        val lockField = linuxLockedSurfaceField ?: return false
        val lockMethod = linuxLockDrawingSurfaceMethod ?: return false
        val unlockMethod = linuxUnlockDrawingSurfaceMethod ?: return false
        val makeCurrentMethod = linuxMakeCurrentMethod ?: return false
        val liveCtx = runCatching { linuxGlShareContextField?.getLong(renderer) ?: 0L }.getOrDefault(0L)
        if (liveCtx == 0L) return false

        val alreadyLocked = runCatching { lockField.get(renderer) != null }.getOrDefault(false)
        var lockedHere = false
        try {
            if (!alreadyLocked) {
                if (lockMethod.invoke(renderer) == null) return false
                lockedHere = true
            }
            makeCurrentMethod.invoke(renderer)
        } catch (_: Throwable) {
            if (lockedHere) runCatching { unlockMethod.invoke(renderer) }
            return false
        }
        return try {
            block()
            true
        } finally {
            if (restoreDefaultFramebuffer) {
                runCatching {
                    if (!directContext.isClosed) {
                        directContext.flush()
                        if (LinuxGl.bindDefaultFramebuffer()) {
                            directContext.resetGL(GLBackendState.RENDER_TARGET)
                        }
                    } else {
                        LinuxGl.bindDefaultFramebuffer()
                    }
                }
            }
            if (lockedHere) {
                runCatching { unlockMethod.invoke(renderer) }
            }
        }
    }

    fun createOffscreenLinuxGlxContext(component: Component): Long {
        if (!SwingUtilities.isEventDispatchThread() || !component.isDisplayable) return 0L
        val lockMethod = lockHardwareSurfaceMethod ?: return 0L
        val unlockMethod = unlockHardwareSurfaceMethod ?: return 0L
        val createMethod = createGlxContextMethod ?: return 0L
        return runCatching {
            loadOpenGlLibraryMethod?.invoke(null)
            val surface = lockMethod.invoke(null, component) ?: return 0L
            try {
                createMethod.invoke(null, surface, false) as Long
            } finally {
                runCatching { unlockMethod.invoke(null, surface) }
            }
        }.onFailure { PikoLog.w(TAG, "Failed to create fallback GLX context for software renderer", it) }
            .getOrDefault(0L)
    }

    fun destroyOffscreenLinuxGlxContext(component: Component?, contextSlot: Long) {
        if (contextSlot == 0L || component == null || !component.isDisplayable) return
        if (!SwingUtilities.isEventDispatchThread()) return
        val lockMethod = lockHardwareSurfaceMethod ?: return
        val unlockMethod = unlockHardwareSurfaceMethod ?: return
        val destroyMethod = destroyGlxContextMethod ?: return
        runCatching {
            val surface = lockMethod.invoke(null, component) ?: return
            try {
                destroyMethod.invoke(null, surface, contextSlot)
            } finally {
                runCatching { unlockMethod.invoke(null, surface) }
            }
        }
    }
}

/**
 * 维护给 `mediampv.dll` 使用的 72 字节（`0x48`）离堆 D3D12 设备兼容结构。
 *
 * Skiko 0.156.1 的 `DirectXDevice` 在 `+0x10` 放 `ID3D12Device*`、`+0x20` 放 `ID3D12CommandQueue*`，
 * `+0x30` 已改为 `backbuffer[1]`；而 `mediampv.dll` 仍校验 `*(ptr+0x10) == *(ptr+0x30)` 与
 * `*(ptr+0x18) == *(ptr+0x40)`。使用与 `MpvMediampPlayer` 生命周期绑定的 `Arena.ofAuto()` 托管，
 * 避免表面先于 mpv 渲染线程卸载（如切换听视频模式或显卡重置）时提前 `Arena.close()` 引发原生 UAF。
 */
private class DirectXDeviceShim(private val owner: MpvMediampPlayer) {
    private var lastNativeDevicePtr = 0L
    private var lastD3d12Device = 0L
    private var lastD3d12Queue = 0L
    var shimPtr: Long = 0L
        private set

    fun resolveShimPtr(nativeDevicePtr: Long): Long {
        if (nativeDevicePtr == 0L) return 0L
        val nativeSegment = MemorySegment.ofAddress(nativeDevicePtr).reinterpret(SKIKO_DEVICE_READ_BYTES)
        val d3d12Device = nativeSegment.get(JAVA_LONG, SKIKO_OFFSET_D3D12_DEVICE)
        val d3d12Queue = nativeSegment.get(JAVA_LONG, SKIKO_OFFSET_D3D12_QUEUE)
        if (d3d12Device == 0L || d3d12Queue == 0L) return 0L

        if (
            shimPtr != 0L &&
            nativeDevicePtr == lastNativeDevicePtr &&
            d3d12Device == lastD3d12Device &&
            d3d12Queue == lastD3d12Queue
        ) {
            return shimPtr
        }

        val nextArena = Arena.ofAuto()
        val segment = nextArena.allocate(SHIM_STRUCT_BYTES)
        segment.set(JAVA_LONG, SHIM_OFFSET_FDEVICE, d3d12Device)
        segment.set(JAVA_LONG, SHIM_OFFSET_FQUEUE, d3d12Queue)
        segment.set(JAVA_LONG, SHIM_OFFSET_DEVICE, d3d12Device)
        segment.set(JAVA_LONG, SHIM_OFFSET_QUEUE, d3d12Queue)

        retainArenaForPlayer(owner, nextArena)
        lastNativeDevicePtr = nativeDevicePtr
        lastD3d12Device = d3d12Device
        lastD3d12Queue = d3d12Queue
        shimPtr = segment.address()
        return shimPtr
    }

    fun reset() {
        shimPtr = 0L
        lastNativeDevicePtr = 0L
        lastD3d12Device = 0L
        lastD3d12Queue = 0L
    }

    private companion object {
        const val SKIKO_OFFSET_D3D12_DEVICE = 0x10L
        const val SKIKO_OFFSET_D3D12_QUEUE = 0x20L
        const val SKIKO_DEVICE_READ_BYTES = 0x28L

        const val SHIM_STRUCT_BYTES = 0x48L
        const val SHIM_OFFSET_FDEVICE = 0x10L
        const val SHIM_OFFSET_FQUEUE = 0x18L
        const val SHIM_OFFSET_DEVICE = 0x30L
        const val SHIM_OFFSET_QUEUE = 0x40L

        private val playerArenas = WeakHashMap<MpvMediampPlayer, MutableList<Arena>>()

        fun retainArenaForPlayer(player: MpvMediampPlayer, arena: Arena) = synchronized(playerArenas) {
            playerArenas.getOrPut(player) { mutableListOf() }.add(arena)
        }
    }
}

@OptIn(InternalMediampApi::class)
private class PikoSurfaceController(private val mediamp: MpvMediampPlayer) {
    // 串行化 Win32 live-resize 同步绘制线程（AWT-Windows）与 Swing EDT（LaunchedEffect/onDispose）对
    // Surface/Arena/Image 生命周期的并发访问，避免窗口拖拽缩放时发生竞态或原生句柄 use-after-close。
    private val stateLock = Any()
    private val handlePtr: Long by lazy { MediampAccess.getHandlePtr(mediamp) }

    private val shim = DirectXDeviceShim(mediamp)
    private val d3d11Ring by lazy { D3D11RingConsumer(handlePtr) }
    private val d3d11Readback by lazy { CpuReadbackConsumer(handlePtr, isMacos = false) }
    private val macosRing by lazy { MacosRingConsumer(handlePtr) }
    private val macosReadback by lazy { CpuReadbackConsumer(handlePtr, isMacos = true) }
    private val openGlRing by lazy { OpenGLRingConsumer(handlePtr) }
    private val linuxReadback by lazy { LinuxOpenGLReadbackConsumer(handlePtr) }

    private var activeMode: SurfaceMode? = null
    private var hintedShimPtr = 0L
    private var attachedOpenGlShareContext = 0L
    private var attachedOpenGlDrawable = 0L
    private var attachedOpenGlWindow = 0L
    private var lastLinuxEnv: SkikoRenderEnvironment? = null
    private var fallbackLinuxComponent: Component? = null
    private var fallbackLinuxGlxContext = 0L
    private var renderContextRequested = false

    @Volatile
    private var released = false

    private enum class SurfaceMode {
        D3D11_RING,
        D3D11_READBACK,
        MACOS_RING,
        MACOS_READBACK,
        OPENGL_RING,
        LINUX_OPENGL_READBACK,
    }

    private fun ensureRenderContext(): Boolean {
        if (renderContextRequested) return true
        val created = mediamp.createRenderContext()
        if (created) renderContextRequested = true
        return created
    }

    fun attachRenderUpdateListener(onUpdate: () -> Unit): Boolean =
        MediampAccess.setRenderUpdateListener(mediamp, onUpdate)

    fun isTeardownStarted(): Boolean =
        released || MediampAccess.isSurfaceTeardownStarted(mediamp)

    fun configureIfReady(width: Int, height: Int, env: SkikoRenderEnvironment): Boolean =
        synchronized(stateLock) { configureIfReadyLocked(width, height, env) }

    private fun configureIfReadyLocked(width: Int, height: Int, env: SkikoRenderEnvironment): Boolean {
        if (isTeardownStarted() || width <= 0 || height <= 0 || handlePtr == 0L) return false
        if (!env.hasRenderer && !java.awt.GraphicsEnvironment.isHeadless()) return false

        return when {
            WinRTSupport.isWindows -> configureWindows(width, height, env)
            isMacOs -> configureMacos(width, height, env)
            isLinux -> configureLinux(width, height, env)
            else -> false
        }
    }

    private fun configureWindows(width: Int, height: Int, env: SkikoRenderEnvironment): Boolean {
        if (env.kind == SkikoBackendKind.DIRECT3D) {
            if (env.nativeDevicePtr == 0L || env.directContext == null) return false
            val shimPtr = shim.resolveShimPtr(env.nativeDevicePtr)
            if (shimPtr == 0L) return false
            if (hintedShimPtr != shimPtr) {
                nSetConsumerDeviceHintD3D11(handlePtr, shimPtr)
                hintedShimPtr = shimPtr
            }
            if (!ensureRenderContext()) return false
            val useRing = nCanShareFramesD3D11(handlePtr, shimPtr)
            return if (useRing) {
                switchTo(SurfaceMode.D3D11_RING)
                d3d11Ring.requestSurface(width, height, shimPtr)
            } else {
                switchTo(SurfaceMode.D3D11_READBACK)
                d3d11Readback.requestSurface(width, height)
            }
        }
        if (!ensureRenderContext()) return false
        switchTo(SurfaceMode.D3D11_READBACK)
        return d3d11Readback.requestSurface(width, height)
    }

    private fun configureMacos(width: Int, height: Int, env: SkikoRenderEnvironment): Boolean {
        if (env.kind == SkikoBackendKind.METAL) {
            if (env.nativeDevicePtr == 0L || env.directContext == null) return false
            if (!ensureRenderContext()) return false
            switchTo(SurfaceMode.MACOS_RING)
            return macosRing.requestSurface(width, height, env.nativeDevicePtr)
        }
        if (!ensureRenderContext()) return false
        switchTo(SurfaceMode.MACOS_READBACK)
        return macosReadback.requestSurface(width, height)
    }

    private fun configureLinux(width: Int, height: Int, env: SkikoRenderEnvironment): Boolean {
        val component = env.glComponent ?: return false
        if (env.glDrawable == 0L) return false

        if (env.kind == SkikoBackendKind.LINUX_OPENGL) {
            if (env.glShareContext == 0L || env.directContext == null || env.directContext.isClosed) return false
            lastLinuxEnv = env
            if (
                attachedOpenGlShareContext != env.glShareContext ||
                attachedOpenGlDrawable != env.glDrawable ||
                attachedOpenGlWindow != env.glWindow ||
                !renderContextRequested
            ) {
                if (!MediampAccess.attachLinuxRenderEnvironment(mediamp, component, env.glShareContext, env.glDrawable, env.glWindow)) {
                    return false
                }
                attachedOpenGlShareContext = env.glShareContext
                attachedOpenGlDrawable = env.glDrawable
                attachedOpenGlWindow = env.glWindow
                renderContextRequested = true
            }
            switchTo(SurfaceMode.OPENGL_RING)
            return openGlRing.requestSurface(width, height, env.glShareContext)
        }

        // Skiko 处于软件渲染（--disable-gpu、SOFTWARE_FAST 或 llvmpipe）时，创建独立 GLX 上下文经 CPU 回读出帧。
        if (!SwingUtilities.isEventDispatchThread() || !component.isDisplayable) return false
        if (fallbackLinuxGlxContext == 0L || fallbackLinuxComponent !== component) {
            destroyFallbackLinuxGlxContext()
            fallbackLinuxGlxContext = SkikoAccess.createOffscreenLinuxGlxContext(component)
            if (fallbackLinuxGlxContext == 0L) return false
            fallbackLinuxComponent = component
        }
        if (
            attachedOpenGlShareContext != fallbackLinuxGlxContext ||
            attachedOpenGlDrawable != env.glDrawable ||
            attachedOpenGlWindow != env.glWindow ||
            !renderContextRequested
        ) {
            if (!MediampAccess.attachLinuxRenderEnvironment(mediamp, component, fallbackLinuxGlxContext, env.glDrawable, env.glWindow)) {
                return false
            }
            attachedOpenGlShareContext = fallbackLinuxGlxContext
            attachedOpenGlDrawable = env.glDrawable
            attachedOpenGlWindow = env.glWindow
            renderContextRequested = true
        }
        switchTo(SurfaceMode.LINUX_OPENGL_READBACK)
        return linuxReadback.requestSurface(width, height, fallbackLinuxGlxContext)
    }

    fun drawCurrentFrame(
        width: Int,
        height: Int,
        env: SkikoRenderEnvironment,
        probe: VideoSurfaceProbe?,
        draw: (Image) -> Unit,
    ) = synchronized(stateLock) {
        if (isTeardownStarted()) return@synchronized
        configureIfReadyLocked(width, height, env)
        val mode = activeMode ?: return@synchronized
        probe?.activeMode = mode.name
        val image: Image? = when (mode) {
            SurfaceMode.D3D11_RING -> d3d11Ring.currentFrameImage(env.directContext)?.also { img ->
                probe?.onFrameReady(d3d11Ring.lastState, img, env.directContext)
            }
            SurfaceMode.D3D11_READBACK -> d3d11Readback.currentFrameImage()?.also { img ->
                probe?.onFrameReady(d3d11Readback.lastState, img, null)
            }
            SurfaceMode.MACOS_RING -> macosRing.currentFrameImage(env.directContext)?.also { img ->
                probe?.onFrameReady(macosRing.lastState, img, env.directContext)
            }
            SurfaceMode.MACOS_READBACK -> macosReadback.currentFrameImage()?.also { img ->
                probe?.onFrameReady(macosReadback.lastState, img, null)
            }
            SurfaceMode.OPENGL_RING -> {
                lastLinuxEnv = env
                var resolved: Image? = null
                val bound = SkikoAccess.withLinuxGlCurrent(env) {
                    resolved = openGlRing.currentFrameImage(env.directContext)
                    resolved?.let { img ->
                        probe?.onFrameReady(openGlRing.lastState, img, env.directContext)
                    }
                    env.directContext?.flush()
                }
                if (!bound) openGlRing.peekCachedFrame() else resolved
            }
            SurfaceMode.LINUX_OPENGL_READBACK -> linuxReadback.currentFrameImage()?.also { img ->
                probe?.onFrameReady(linuxReadback.lastState, img, null)
            }
        }
        image?.let(draw)
    }

    private fun switchTo(mode: SurfaceMode) {
        if (activeMode == mode) return
        releaseActiveMode()
        activeMode = mode
    }

    private fun releaseActiveMode() {
        when (activeMode) {
            SurfaceMode.D3D11_RING -> d3d11Ring.release()
            SurfaceMode.D3D11_READBACK -> d3d11Readback.release()
            SurfaceMode.MACOS_RING -> macosRing.release()
            SurfaceMode.MACOS_READBACK -> macosReadback.release()
            SurfaceMode.OPENGL_RING -> {
                val env = lastLinuxEnv
                val bound = SkikoAccess.withLinuxGlCurrent(env) {
                    openGlRing.release(canDeleteGlObjects = true)
                    env?.directContext?.takeUnless { it.isClosed }?.let { ctx ->
                        ctx.flush()
                        ctx.resetGLAll()
                    }
                }
                if (!bound) {
                    openGlRing.release(canDeleteGlObjects = false)
                }
            }
            SurfaceMode.LINUX_OPENGL_READBACK -> linuxReadback.release()
            null -> Unit
        }
    }

    private fun destroyFallbackLinuxGlxContext() {
        if (fallbackLinuxGlxContext == 0L) return
        val ctx = fallbackLinuxGlxContext
        val comp = fallbackLinuxComponent
        fallbackLinuxGlxContext = 0L
        fallbackLinuxComponent = null
        if (handlePtr != 0L) {
            runCatching { nDestroyRenderContextOpenGL(handlePtr) }
        }
        SkikoAccess.destroyOffscreenLinuxGlxContext(comp, ctx)
    }

    fun release() {
        released = true
        MediampAccess.setRenderUpdateListener(mediamp, null)
        synchronized(stateLock) {
            releaseActiveMode()
            activeMode = null
            destroyFallbackLinuxGlxContext()
            lastLinuxEnv = null
            if (WinRTSupport.isWindows && hintedShimPtr != 0L && handlePtr != 0L) {
                runCatching { nSetConsumerDeviceHintD3D11(handlePtr, 0L) }
            }
            hintedShimPtr = 0L
            attachedOpenGlShareContext = 0L
            attachedOpenGlDrawable = 0L
            attachedOpenGlWindow = 0L
            renderContextRequested = false
            shim.reset()
        }
    }
}

private object MediampAccess {
    private val getHandleMethod: Method by lazy {
        JvmMpvMediampPlayer::class.java.getMethod("getHandle\$mediamp_mpv")
    }

    private val getPtrMethod: Method by lazy {
        MPVHandle::class.java.getMethod("getPtr\$mediamp_mpv")
    }

    private val renderUpdateListenerClass: Class<*>? by lazy {
        runCatching {
            Class.forName("org.openani.mediamp.mpv.RenderUpdateListener")
        }.onFailure { PikoLog.w(TAG, "Failed to load RenderUpdateListener", it) }.getOrNull()
    }

    private val setRenderUpdateListenerMethod: Method? by lazy {
        val listenerClass = renderUpdateListenerClass ?: return@lazy null
        runCatching {
            MpvMediampPlayer::class.java.getMethod("setRenderUpdateListener\$mediamp_mpv", listenerClass)
        }.onFailure { PikoLog.w(TAG, "Failed to reflect setRenderUpdateListener", it) }.getOrNull()
    }

    private val isSurfaceTeardownStartedMethod: Method? by lazy {
        runCatching {
            MpvMediampPlayer::class.java.getMethod("isSurfaceTeardownStarted\$mediamp_mpv")
        }.onFailure { PikoLog.w(TAG, "Failed to reflect isSurfaceTeardownStarted", it) }.getOrNull()
    }

    private val getRenderContextLifecycleMethod: Method? by lazy {
        runCatching {
            MpvMediampPlayer::class.java.getMethod("getRenderContextLifecycle\$mediamp_mpv")
        }.onFailure { PikoLog.w(TAG, "Failed to reflect getRenderContextLifecycle", it) }.getOrNull()
    }

    private val openGlEnvConstructor: Constructor<*>? by lazy {
        runCatching {
            Class.forName("org.openani.mediamp.mpv.utils.OpenGLRenderEnvironment").getConstructor(
                Component::class.java,
                java.lang.Long.TYPE,
                java.lang.Long.TYPE,
                java.lang.Long.TYPE,
            )
        }.onFailure { PikoLog.w(TAG, "Failed to reflect OpenGLRenderEnvironment constructor", it) }.getOrNull()
    }

    private val attachOpenGlEnvMethod: Method? by lazy {
        val envClass = openGlEnvConstructor?.declaringClass ?: return@lazy null
        runCatching {
            Class.forName("org.openani.mediamp.mpv.internal.OpenGLRenderContextLifecycle")
                .getMethod("attachRenderEnvironment", envClass)
        }.onFailure { PikoLog.w(TAG, "Failed to reflect OpenGLRenderContextLifecycle.attachRenderEnvironment", it) }
            .getOrNull()
    }

    fun getHandlePtr(mediamp: MpvMediampPlayer): Long = runCatching {
        val handle = getHandleMethod.invoke(mediamp) as? MPVHandle ?: return 0L
        getPtrMethod.invoke(handle) as Long
    }.onFailure { PikoLog.w(TAG, "Failed to reflect MPVHandle pointer", it) }.getOrDefault(0L)

    fun getPropertyString(mediamp: MpvMediampPlayer, name: String): String? = runCatching {
        val handle = getHandleMethod.invoke(mediamp) as? MPVHandle ?: return null
        handle.getPropertyString(name)
    }.getOrNull()

    fun attachLinuxRenderEnvironment(
        mediamp: MpvMediampPlayer,
        component: Component,
        shareContext: Long,
        drawable: Long,
        window: Long,
    ): Boolean {
        val getLifecycle = getRenderContextLifecycleMethod ?: return false
        val ctor = openGlEnvConstructor ?: return false
        val attach = attachOpenGlEnvMethod ?: return false
        return runCatching {
            val lifecycle = getLifecycle.invoke(mediamp) ?: return false
            val env = ctor.newInstance(component, shareContext, drawable, window)
            attach.invoke(lifecycle, env) as Boolean
        }.onFailure { PikoLog.w(TAG, "Failed to attach Linux OpenGLRenderEnvironment", it) }.getOrDefault(false)
    }

    fun setRenderUpdateListener(mediamp: MpvMediampPlayer, onUpdate: (() -> Unit)?): Boolean {
        val listenerClass = renderUpdateListenerClass ?: return false
        val method = setRenderUpdateListenerMethod ?: return false
        val proxy = onUpdate?.let { callback ->
            Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
            ) { _, called, _ ->
                if (called.name == "onRenderUpdate") callback()
                null
            }
        }
        return runCatching {
            method.invoke(mediamp, proxy) as Boolean
        }.onFailure { PikoLog.w(TAG, "Failed to set mpv RenderUpdateListener", it) }.getOrDefault(false)
    }

    fun isSurfaceTeardownStarted(mediamp: MpvMediampPlayer): Boolean {
        val method = isSurfaceTeardownStartedMethod ?: return false
        return runCatching { method.invoke(mediamp) as Boolean }.getOrDefault(false)
    }
}

@OptIn(InternalMediampApi::class)
private abstract class BaseRingConsumer(protected val handlePtr: Long) {
    private val wrappedSurfaces = arrayOfNulls<Surface>(RING_BUFFER_COUNT)
    private val renderTargets = arrayOfNulls<BackendRenderTarget>(RING_BUFFER_COUNT)
    private val extraCleanups = arrayOfNulls<((Boolean) -> Unit)>(RING_BUFFER_COUNT)
    private var blitSurface: Surface? = null
    private var wrappedGeneration = -1
    private var surfaceContext: DirectContext? = null
    private var cachedFrame: Image? = null
    private var cachedState = 0L

    val lastState: Long get() = cachedState

    private var requestedWidth = 0
    private var requestedHeight = 0
    private var requestedDevicePtr = 0L
    private val loggedErrors = mutableSetOf<String>()

    protected abstract val surfaceOrigin: SurfaceOrigin
    protected abstract val colorFormat: SurfaceColorFormat
    protected open val resetsGlStateOnWrap: Boolean get() = false

    protected abstract fun setSurfaceConfig(width: Int, height: Int, devicePtr: Long): Boolean
    protected abstract fun getFrameState(): Long
    protected abstract fun getBufferTexture(index: Int): Long
    protected abstract fun ackRetiredBuffers(): Boolean
    protected abstract fun makeRenderTarget(
        width: Int,
        height: Int,
        texturePtr: Long,
    ): Pair<BackendRenderTarget, ((Boolean) -> Unit)?>?

    fun peekCachedFrame(): Image? = cachedFrame

    fun requestSurface(width: Int, height: Int, devicePtr: Long): Boolean {
        if (width == requestedWidth && height == requestedHeight && devicePtr == requestedDevicePtr) {
            return true
        }
        if (!setSurfaceConfig(width, height, devicePtr)) return false
        requestedWidth = width
        requestedHeight = height
        requestedDevicePtr = devicePtr
        return true
    }

    fun currentFrameImage(directContext: DirectContext?): Image? {
        if (directContext == null || directContext.isClosed) return null
        val state = getFrameState()
        if (state == cachedState && surfaceContext === directContext) {
            cachedFrame?.let { return it }
        }
        val generation = ((state ushr 48) and 0xFFFF).toInt()
        val index = ((state ushr 44) and 0xF).toInt()
        val width = ((state ushr 30) and 0x3FFF).toInt()
        val height = ((state ushr 16) and 0x3FFF).toInt()
        if (index == 0xF || width <= 0 || height <= 0) return cachedFrame

        if (generation != wrappedGeneration || surfaceContext !== directContext || blitSurface == null) {
            if (!rewrapBuffers(generation, width, height, directContext)) return cachedFrame
        }
        val source = wrappedSurfaces.getOrNull(index) ?: return cachedFrame
        val blit = blitSurface ?: return cachedFrame
        source.notifyContentWillChange(ContentChangeMode.DISCARD)
        source.draw(blit.canvas, 0, 0, null)
        cachedFrame?.close()
        cachedFrame = blit.makeImageSnapshot()
        cachedState = state
        return cachedFrame
    }

    private fun rewrapBuffers(
        generation: Int,
        width: Int,
        height: Int,
        directContext: DirectContext,
    ): Boolean {
        if (surfaceContext !== null && surfaceContext !== directContext) {
            cachedFrame?.close()
            cachedFrame = null
            cachedState = 0L
        }
        closeWraps(canDeleteGlObjects = true)
        ackRetiredBuffers()

        for (i in 0 until RING_BUFFER_COUNT) {
            val texture = getBufferTexture(i)
            if (texture == 0L) {
                logOnce("buffer $i has no shared texture")
                closeWraps(canDeleteGlObjects = true)
                return false
            }
            val created = makeRenderTarget(width, height, texture)
            if (created == null) {
                logOnce("failed to create backend render target for buffer $i")
                closeWraps(canDeleteGlObjects = true)
                return false
            }
            val (target, cleanup) = created
            if (resetsGlStateOnWrap) directContext.resetGLAll()
            val surface = runCatching {
                Surface.makeFromBackendRenderTarget(
                    context = directContext,
                    rt = target,
                    origin = surfaceOrigin,
                    colorFormat = colorFormat,
                    colorSpace = ColorSpace.sRGB,
                )
            }.onFailure { logOnce("wrapping buffer $i as Skia surface failed", it) }.getOrNull()
            if (surface == null) {
                logOnce("Surface.makeFromBackendRenderTarget returned null")
                target.close()
                cleanup?.invoke(true)
                if (resetsGlStateOnWrap) directContext.resetGLAll()
                closeWraps(canDeleteGlObjects = true)
                return false
            }
            renderTargets[i] = target
            wrappedSurfaces[i] = surface
            extraCleanups[i] = cleanup
        }

        blitSurface = runCatching {
            Surface.makeRenderTarget(directContext, false, ImageInfo.makeN32Premul(width, height))
        }.onFailure { logOnce("creating blit surface failed", it) }.getOrNull()
        if (blitSurface == null) {
            closeWraps(canDeleteGlObjects = true)
            return false
        }

        val currentGeneration = ((getFrameState() ushr 48) and 0xFFFF).toInt()
        if (currentGeneration != generation) {
            closeWraps(canDeleteGlObjects = true)
            return false
        }

        wrappedGeneration = generation
        surfaceContext = directContext
        return true
    }

    private fun closeWraps(canDeleteGlObjects: Boolean) {
        var hadCleanup = false
        for (i in 0 until RING_BUFFER_COUNT) {
            wrappedSurfaces[i]?.close()
            wrappedSurfaces[i] = null
            renderTargets[i]?.close()
            renderTargets[i] = null
            extraCleanups[i]?.let {
                hadCleanup = true
                it.invoke(canDeleteGlObjects)
            }
            extraCleanups[i] = null
        }
        blitSurface?.close()
        blitSurface = null
        if (hadCleanup && canDeleteGlObjects && resetsGlStateOnWrap) {
            surfaceContext?.takeUnless { it.isClosed }?.resetGLAll()
        }
        wrappedGeneration = -1
    }

    protected fun logOnce(message: String, throwable: Throwable? = null) {
        if (loggedErrors.add(message)) {
            if (throwable != null) PikoLog.w(TAG, message, throwable)
            else PikoLog.w(TAG, message)
        }
    }

    fun release(canDeleteGlObjects: Boolean = true) {
        cachedFrame?.close()
        cachedFrame = null
        cachedState = 0L
        closeWraps(canDeleteGlObjects)
        surfaceContext = null
        setSurfaceConfig(0, 0, 0L)
        requestedWidth = 0
        requestedHeight = 0
        requestedDevicePtr = 0L
    }

    private companion object {
        const val RING_BUFFER_COUNT = 3
    }
}

@OptIn(InternalMediampApi::class)
private class D3D11RingConsumer(handlePtr: Long) : BaseRingConsumer(handlePtr) {
    override val surfaceOrigin = SurfaceOrigin.TOP_LEFT
    override val colorFormat = SurfaceColorFormat.RGBA_8888

    override fun setSurfaceConfig(width: Int, height: Int, devicePtr: Long): Boolean =
        nSetSurfaceConfigD3D11(handlePtr, width, height, devicePtr)

    override fun getFrameState(): Long = nGetFrameStateD3D11(handlePtr)

    override fun getBufferTexture(index: Int): Long = nGetBufferTextureD3D11(handlePtr, index)

    override fun ackRetiredBuffers(): Boolean = nAckRetiredBuffersD3D11(handlePtr)

    override fun makeRenderTarget(
        width: Int,
        height: Int,
        texturePtr: Long,
    ): Pair<BackendRenderTarget, ((Boolean) -> Unit)?> =
        BackendRenderTarget.makeDirect3D(
            width = width,
            height = height,
            texturePtr = texturePtr,
            format = DXGI_FORMAT_R8G8B8A8_UNORM,
            sampleCnt = 1,
            levelCnt = 1,
        ) to null

    private companion object {
        const val DXGI_FORMAT_R8G8B8A8_UNORM = 28
    }
}

@OptIn(InternalMediampApi::class)
private class MacosRingConsumer(handlePtr: Long) : BaseRingConsumer(handlePtr) {
    override val surfaceOrigin = SurfaceOrigin.TOP_LEFT
    override val colorFormat = SurfaceColorFormat.BGRA_8888

    override fun setSurfaceConfig(width: Int, height: Int, devicePtr: Long): Boolean =
        nSetSurfaceConfigMacos(handlePtr, width, height, devicePtr)

    override fun getFrameState(): Long = nGetFrameStateMacos(handlePtr)

    override fun getBufferTexture(index: Int): Long = nGetBufferTextureMacos(handlePtr, index)

    override fun ackRetiredBuffers(): Boolean = nAckRetiredBuffersMacos(handlePtr)

    override fun makeRenderTarget(
        width: Int,
        height: Int,
        texturePtr: Long,
    ): Pair<BackendRenderTarget, ((Boolean) -> Unit)?> =
        BackendRenderTarget.makeMetal(width = width, height = height, texturePtr = texturePtr) to null
}

@OptIn(InternalMediampApi::class)
private class OpenGLRingConsumer(handlePtr: Long) : BaseRingConsumer(handlePtr) {
    override val surfaceOrigin = SurfaceOrigin.BOTTOM_LEFT
    override val colorFormat = SurfaceColorFormat.RGBA_8888
    override val resetsGlStateOnWrap = true

    override fun setSurfaceConfig(width: Int, height: Int, devicePtr: Long): Boolean =
        nSetSurfaceConfigOpenGL(handlePtr, width, height, devicePtr)

    override fun getFrameState(): Long = nGetFrameStateOpenGL(handlePtr)

    override fun getBufferTexture(index: Int): Long = nGetBufferTextureOpenGL(handlePtr, index)

    override fun ackRetiredBuffers(): Boolean = nAckRetiredBuffersOpenGL(handlePtr)

    override fun makeRenderTarget(
        width: Int,
        height: Int,
        texturePtr: Long,
    ): Pair<BackendRenderTarget, ((Boolean) -> Unit)?>? {
        val fbo = nCreateOpenGLConsumerFbo(texturePtr)
        if (fbo <= 0) return null
        val target = runCatching {
            BackendRenderTarget.makeGL(
                width = width,
                height = height,
                sampleCnt = 0,
                stencilBits = 0,
                fbId = fbo,
                fbFormat = GL_RGBA8,
            )
        }.onFailure {
            nDeleteOpenGLConsumerFbo(fbo)
            logOnce("failed to wrap OpenGL consumer FBO $fbo", it)
        }.getOrNull() ?: return null
        return target to { canDeleteGl ->
            if (canDeleteGl) {
                nDeleteOpenGLConsumerFbo(fbo)
            }
        }
    }

    private companion object {
        const val GL_RGBA8 = 0x8058
    }
}

@OptIn(InternalMediampApi::class)
private class LinuxOpenGLReadbackConsumer(private val handlePtr: Long) {
    private val dims = IntArray(2)
    private var bitmap: Bitmap? = null
    private var bitmapPixels: Pixmap? = null
    private var cachedFrame: Image? = null
    private var cachedState = 0L
    private var ackedGeneration = -1

    val lastState: Long get() = cachedState

    private var requestedWidth = 0
    private var requestedHeight = 0
    private var requestedDevicePtr = 0L

    fun requestSurface(width: Int, height: Int, devicePtr: Long): Boolean {
        if (width == requestedWidth && height == requestedHeight && devicePtr == requestedDevicePtr) {
            return true
        }
        if (!nSetSurfaceConfigOpenGL(handlePtr, width, height, devicePtr)) return false
        requestedWidth = width
        requestedHeight = height
        requestedDevicePtr = devicePtr
        return true
    }

    fun currentFrameImage(): Image? {
        val state = nGetFrameStateOpenGL(handlePtr)
        if (state == cachedState) {
            cachedFrame?.let { return it }
        }
        val generation = ((state ushr 48) and 0xFFFF).toInt()
        val index = ((state ushr 44) and 0xF).toInt()
        val width = ((state ushr 30) and 0x3FFF).toInt()
        val height = ((state ushr 16) and 0x3FFF).toInt()
        if (generation != ackedGeneration) {
            nAckRetiredBuffersOpenGL(handlePtr)
            ackedGeneration = generation
        }
        if (index == 0xF || width <= 0 || height <= 0) return cachedFrame

        val argbPixels = nReadSurfacePixelsOpenGL(handlePtr, dims) ?: return cachedFrame
        val outW = dims[0]
        val outH = dims[1]
        if (outW <= 0 || outH <= 0 || argbPixels.size < outW * outH) return cachedFrame

        val newBitmap = Bitmap()
        if (!newBitmap.allocPixels(ImageInfo(outW, outH, ColorType.BGRA_8888, ColorAlphaType.OPAQUE))) {
            newBitmap.close()
            return cachedFrame
        }
        val newPixels = newBitmap.peekPixels()
        if (newPixels == null) {
            newBitmap.close()
            return cachedFrame
        }
        val count = outW * outH
        val dst = MemorySegment.ofAddress(newPixels.addr).reinterpret(count.toLong() * 4L)
        MemorySegment.copy(argbPixels, 0, dst, JAVA_INT, 0L, count)
        newBitmap.setImmutable()
        val newFrame = Image.makeFromBitmap(newBitmap)

        cachedFrame?.close()
        bitmapPixels?.close()
        bitmap?.close()
        cachedFrame = newFrame
        bitmap = newBitmap
        bitmapPixels = newPixels
        cachedState = state
        return newFrame
    }

    fun release() {
        nSetSurfaceConfigOpenGL(handlePtr, 0, 0, 0L)
        nAckRetiredBuffersOpenGL(handlePtr)
        cachedFrame?.close()
        cachedFrame = null
        cachedState = 0L
        ackedGeneration = -1
        bitmapPixels?.close()
        bitmapPixels = null
        bitmap?.close()
        bitmap = null
        requestedWidth = 0
        requestedHeight = 0
        requestedDevicePtr = 0L
    }
}

@OptIn(InternalMediampApi::class)
private class CpuReadbackConsumer(
    private val handlePtr: Long,
    private val isMacos: Boolean,
) {
    private var bitmap: Bitmap? = null
    private var bitmapPixels: Pixmap? = null
    private var cachedFrame: Image? = null
    private var cachedState = 0L

    val lastState: Long get() = cachedState

    private var requestedWidth = 0
    private var requestedHeight = 0
    private var configured = false

    fun requestSurface(width: Int, height: Int): Boolean {
        if (configured && width == requestedWidth && height == requestedHeight) return true
        val ok = if (isMacos) {
            nSetReadbackSurfaceConfigMacos(handlePtr, width, height)
        } else {
            nSetReadbackSurfaceConfigD3D11(handlePtr, width, height)
        }
        if (!ok) return false
        requestedWidth = width
        requestedHeight = height
        configured = true
        return true
    }

    fun currentFrameImage(): Image? {
        val state = if (isMacos) nGetFrameStateMacos(handlePtr) else nGetFrameStateD3D11(handlePtr)
        if (state == cachedState) {
            cachedFrame?.let { return it }
        }
        val index = ((state ushr 44) and 0xF).toInt()
        val width = ((state ushr 30) and 0x3FFF).toInt()
        val height = ((state ushr 16) and 0x3FFF).toInt()
        if (index == 0xF || width <= 0 || height <= 0) return cachedFrame

        val newBitmap = Bitmap()
        if (!newBitmap.allocPixels(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE))) {
            newBitmap.close()
            return cachedFrame
        }
        val newPixels = newBitmap.peekPixels()
        if (newPixels == null) {
            newBitmap.close()
            return cachedFrame
        }
        val copiedState = if (isMacos) {
            nCopyLatestFrameMacos(handlePtr, newPixels.addr, width, height)
        } else {
            nCopyLatestFrameD3D11(handlePtr, newPixels.addr, width, height)
        }
        if (copiedState == 0L) {
            newPixels.close()
            newBitmap.close()
            return cachedFrame
        }
        newBitmap.setImmutable()
        val newFrame = Image.makeFromBitmap(newBitmap)

        cachedFrame?.close()
        bitmapPixels?.close()
        bitmap?.close()
        cachedFrame = newFrame
        bitmap = newBitmap
        bitmapPixels = newPixels
        cachedState = copiedState
        return newFrame
    }

    fun release() {
        if (isMacos) {
            nSetReadbackSurfaceConfigMacos(handlePtr, 0, 0)
        } else {
            nSetReadbackSurfaceConfigD3D11(handlePtr, 0, 0)
        }
        cachedFrame?.close()
        cachedFrame = null
        cachedState = 0L
        bitmapPixels?.close()
        bitmapPixels = null
        bitmap?.close()
        bitmap = null
        requestedWidth = 0
        requestedHeight = 0
        configured = false
    }
}

private fun findSkiaLayer(container: Container): SkiaLayer? {
    for (child in container.components) {
        if (child is SkiaLayer) return child
        if (child is Container) findSkiaLayer(child)?.let { return it }
    }
    return null
}

private const val TAG = "PlayerSurface"
private const val INITIAL_PROBE_DELAY_MS = 50L
private const val MAX_INITIAL_PROBES = 40
private const val SURFACE_CONFIG_RETRY_DELAY_MS = 50L
private const val MAX_SURFACE_CONFIG_ATTEMPTS = 20
