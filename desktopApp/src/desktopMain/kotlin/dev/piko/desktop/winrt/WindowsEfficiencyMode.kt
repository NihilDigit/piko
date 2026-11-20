package dev.piko.desktop.winrt

import dev.piko.shared.log.PikoLog
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle

/**
 * Windows 进程级能效模式（EcoQoS）切换器。
 *
 * 通过 JDK 25 FFM 调用 `kernel32.dll` 的 `SetProcessInformation(ProcessPowerThrottling)` 与
 * `SetPriorityClass`：
 * - 开启时设置 `EXECUTION_SPEED | IGNORE_TIMER_RESOLUTION`（旧版 Win10 自动回退 `EXECUTION_SPEED`）与
 *   `IDLE_PRIORITY_CLASS`，将后台线程调度至能效核并忽略高分辨率时钟请求；
 * - 关闭时清零 `StateMask` 并恢复 `NORMAL_PRIORITY_CLASS`。
 */
internal object WindowsEfficiencyMode {
    private const val TAG = "EcoQoS"
    private const val PROCESS_POWER_THROTTLING = 4
    private const val PROCESS_POWER_THROTTLING_CURRENT_VERSION = 1
    private const val PROCESS_POWER_THROTTLING_EXECUTION_SPEED = 0x1
    private const val PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION = 0x4
    private const val STATE_STRUCT_BYTES = 12 // 3 * ULONG (Version, ControlMask, StateMask)

    private const val NORMAL_PRIORITY_CLASS = 0x00000020
    private const val IDLE_PRIORITY_CLASS = 0x00000040

    private class Kernel32Handles(
        val getCurrentProcess: MethodHandle,
        val setProcessInformation: MethodHandle,
        val setPriorityClass: MethodHandle,
    )

    private val handles: Kernel32Handles? by lazy {
        if (!WinRTSupport.isWindows) return@lazy null
        runCatching {
            val linker = Linker.nativeLinker()
            val kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global())
            Kernel32Handles(
                getCurrentProcess = linker.downcallHandle(
                    kernel32.find("GetCurrentProcess").orElseThrow(),
                    FunctionDescriptor.of(ADDRESS),
                ),
                setProcessInformation = linker.downcallHandle(
                    kernel32.find("SetProcessInformation").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT),
                ),
                setPriorityClass = linker.downcallHandle(
                    kernel32.find("SetPriorityClass").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT),
                ),
            )
        }.onFailure {
            PikoLog.w(TAG, "Failed to initialize Windows EcoQoS FFM handles", it)
        }.getOrNull()
    }

    private var currentEnabled: Boolean? = null
    private var ignoreTimerResolutionSupported: Boolean = true

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        if (!WinRTSupport.isWindows) return
        if (currentEnabled == enabled) return
        val api = handles ?: return
        runCatching {
            val process = api.getCurrentProcess.invokeExact() as MemorySegment
            val throttlingOk = applyPowerThrottling(api, process, enabled)
            val priorityClass = if (enabled) IDLE_PRIORITY_CLASS else NORMAL_PRIORITY_CLASS
            val priorityOk = (api.setPriorityClass.invokeExact(process, priorityClass) as Int) != 0
            if (!throttlingOk || !priorityOk) {
                PikoLog.w(TAG, "Partial failure toggling Windows EcoQoS (enabled=$enabled, throttlingOk=$throttlingOk, priorityOk=$priorityOk)")
            }
            currentEnabled = enabled
        }.onFailure {
            PikoLog.w(TAG, "Failed to toggle Windows EcoQoS (enabled=$enabled)", it)
        }
    }

    private fun applyPowerThrottling(
        api: Kernel32Handles,
        process: MemorySegment,
        enabled: Boolean,
    ): Boolean {
        val fullMask = PROCESS_POWER_THROTTLING_EXECUTION_SPEED or PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION
        if (ignoreTimerResolutionSupported) {
            if (invokeSetPowerThrottling(api, process, fullMask, if (enabled) fullMask else 0)) {
                return true
            }
            ignoreTimerResolutionSupported = false
        }
        val basicMask = PROCESS_POWER_THROTTLING_EXECUTION_SPEED
        return invokeSetPowerThrottling(api, process, basicMask, if (enabled) basicMask else 0)
    }

    private fun invokeSetPowerThrottling(
        api: Kernel32Handles,
        process: MemorySegment,
        controlMask: Int,
        stateMask: Int,
    ): Boolean {
        Arena.ofConfined().use { arena ->
            val state = arena.allocate(STATE_STRUCT_BYTES.toLong())
            state.set(JAVA_INT, 0L, PROCESS_POWER_THROTTLING_CURRENT_VERSION)
            state.set(JAVA_INT, 4L, controlMask)
            state.set(JAVA_INT, 8L, stateMask)
            val result = api.setProcessInformation.invokeExact(
                process,
                PROCESS_POWER_THROTTLING,
                state,
                STATE_STRUCT_BYTES,
            ) as Int
            return result != 0
        }
    }
}
