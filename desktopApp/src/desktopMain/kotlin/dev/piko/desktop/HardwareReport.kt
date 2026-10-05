package dev.piko.desktop

import dev.piko.desktop.winrt.ComInterop
import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.shared.log.PikoLog
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.util.concurrent.TimeUnit
import kotlin.text.Charsets.UTF_16LE

/**
 * 启动时把这台机器的处理器、内存与显卡记进日志一次。播放出画面问题的反馈要靠它判断是哪家的卡、哪版驱动、
 * 有没有两块卡：MediaMP 的 mpv 在 DXGI 排第一的那块卡上建设备，Skiko 不一定也在那块上。不含能认出用户的信息。
 *
 * 在后台线程里读：macOS 的 system_profiler 要一秒上下，不拖慢启动。读不到的项省掉，整行读不出就记一条警告。
 */
internal object HardwareReport {
    fun logInBackground() {
        Thread({
            runCatching { PikoLog.i(TAG, describe()) }.onFailure { PikoLog.w(TAG, "读取硬件信息失败", it) }
        }, "Piko-Hardware").apply { isDaemon = true }.start()
    }

    private fun describe(): String {
        val (cpu, memoryBytes, gpus) = when {
            WinRTSupport.isWindows -> Triple(Windows.cpu(), Windows.memoryBytes(), Windows.gpus())
            isMacOs -> Triple(Mac.cpu(), Mac.memoryBytes(), Mac.gpus())
            isLinux -> Triple(Linux.cpu(), Linux.memoryBytes(), Linux.gpus())
            else -> Triple(null, null, emptyList())
        }
        val parts = listOfNotNull(
            cpu?.let { "处理器 $it" },
            memoryBytes?.let { "内存 ${(it + GIB / 2) / GIB} GiB" },
            if (gpus.isEmpty()) "显卡 未知" else gpus.joinToString("；") { "显卡 $it" },
        )
        return parts.joinToString("，")
    }

    private object Windows {
        private val linker = Linker.nativeLinker()

        /**
         * 同一块卡可能被枚举出几次，各有各的 LUID：只有一块 RTX 4060（集显已关）的笔记本上实测是两次（2026-10-06），
         * 原因没查清。不去重，合成一条注明次数：mpv 建在第一个上，Skiko 若拿到另一个，就成了跨适配器共享。
         */
        fun gpus(): List<String> = runCatching { dxgiAdapters() }
            .onFailure { PikoLog.w(TAG, "枚举显卡失败", it) }
            .getOrDefault(emptyList())
            // groupBy 保持首次出现的先后，第一组含第一个适配器，即默认的那块
            .groupBy { it }
            .entries.mapIndexed { index, (summary, same) ->
                summary + (if (index == 0) "，默认" else "") + (if (same.size > 1) "，枚举到 ${same.size} 个" else "") + "）"
            }

        /**
         * DXGI 的枚举顺序即 D3D 选卡的顺序：第一块是传 null 适配器时用的那块，mpv 的 D3D11 设备就建在它上面。
         * 驱动版本取 CheckInterfaceSupport 报的用户态驱动版本，与设备管理器里显示的一致，不必去注册表按名字对。
         * 跳过软件适配器（Microsoft Basic Render Driver），它每台机器都有。
         */
        private fun dxgiAdapters(): List<String> = Arena.ofConfined().use { arena ->
            val dxgi = SymbolLookup.libraryLookup("dxgi", arena)
            val createFactory = linker.downcallHandle(
                dxgi.find("CreateDXGIFactory1").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
            )
            val factoryOut = arena.allocate(ADDRESS)
            ComInterop.check(createFactory.invokeWithArguments(arena.allocateFrom(JAVA_BYTE, *IID_IDXGI_FACTORY1), factoryOut) as Int, "CreateDXGIFactory1")
            val factory = factoryOut.get(ADDRESS, 0)
            try {
                buildList {
                    var index = 0
                    while (true) {
                        val adapterOut = arena.allocate(ADDRESS)
                        val hr = ComInterop.hresult(ComInterop.vtable(factory, ENUM_ADAPTERS1, enumDescriptor), factory, index, adapterOut)
                        if (hr == DXGI_ERROR_NOT_FOUND) break
                        ComInterop.check(hr, "EnumAdapters1")
                        val adapter = adapterOut.get(ADDRESS, 0)
                        try {
                            describeAdapter(arena, adapter)?.let(::add)
                        } finally {
                            ComInterop.release(adapter)
                        }
                        index++
                    }
                }
            } finally {
                ComInterop.release(factory)
            }
        }

        /** 型号与括号里的各项，括号不封口：默认与否、枚举到几次由 [gpus] 合并后接上。软件适配器为 null。 */
        private fun describeAdapter(arena: Arena, adapter: MemorySegment): String? {
            val desc = arena.allocate(ADAPTER_DESC1_BYTES)
            ComInterop.check(ComInterop.hresult(ComInterop.vtable(adapter, GET_DESC1, ComInterop.callWithPointer), adapter, desc), "GetDesc1")
            if (desc.get(JAVA_INT, FLAGS_OFFSET) and DXGI_ADAPTER_FLAG_SOFTWARE != 0) return null
            val name = String(desc.asSlice(0, DESCRIPTION_BYTES).toArray(JAVA_BYTE), UTF_16LE).substringBefore('\u0000').trim()
            val vendor = desc.get(JAVA_INT, VENDOR_OFFSET)
            val device = desc.get(JAVA_INT, DEVICE_OFFSET)
            val dedicated = desc.get(JAVA_LONG, DEDICATED_VIDEO_MEMORY_OFFSET)
            val version = arena.allocate(JAVA_LONG)
            val hr = ComInterop.hresult(
                ComInterop.vtable(adapter, CHECK_INTERFACE_SUPPORT, checkSupportDescriptor),
                adapter, arena.allocateFrom(JAVA_BYTE, *IID_IDXGI_DEVICE), version,
            )
            // LARGE_INTEGER 里是四个 16 位段，高位在前：31.0.15.5222 这种写法
            val driver = if (hr >= 0) {
                (3 downTo 0).joinToString(".") { (version.get(JAVA_SHORT, it * 2L).toInt() and 0xFFFF).toString() }
            } else {
                null
            }
            return buildString {
                append(name)
                append("（%04x:%04x".format(vendor, device))
                if (dedicated > 0) append("，显存 ${(dedicated + MIB / 2) / MIB} MiB")
                driver?.let { append("，驱动 $it") }
            }
        }

        fun cpu(): String? = registryString(HKEY_LOCAL_MACHINE, """HARDWARE\DESCRIPTION\System\CentralProcessor\0""", "ProcessorNameString")?.trim()

        fun memoryBytes(): Long? = Arena.ofConfined().use { arena ->
            // MEMORYSTATUSEX：dwLength、dwMemoryLoad 两个 DWORD，之后是 ullTotalPhys
            val status = arena.allocate(MEMORYSTATUSEX_BYTES)
            status.set(JAVA_INT, 0, MEMORYSTATUSEX_BYTES.toInt())
            val kernel32 = SymbolLookup.libraryLookup("kernel32", arena)
            val call = linker.downcallHandle(kernel32.find("GlobalMemoryStatusEx").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS))
            if (call.invokeWithArguments(status) as Int == 0) null else status.get(JAVA_LONG, 8)
        }

        private fun registryString(root: MemorySegment, subKey: String, name: String): String? = Arena.ofConfined().use { arena ->
            val advapi32 = SymbolLookup.libraryLookup("advapi32", arena)
            // LSTATUS RegGetValueW(HKEY, LPCWSTR lpSubKey, LPCWSTR lpValue, DWORD dwFlags, LPDWORD pdwType, PVOID pvData, LPDWORD pcbData)
            val getValue = linker.downcallHandle(
                advapi32.find("RegGetValueW").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
            )
            val size = arena.allocate(JAVA_INT).also { it.set(JAVA_INT, 0, REGISTRY_VALUE_BYTES) }
            val buffer = arena.allocate(REGISTRY_VALUE_BYTES.toLong())
            val status = getValue.invokeWithArguments(
                root, arena.allocateFrom(subKey, UTF_16LE), arena.allocateFrom(name, UTF_16LE),
                RRF_RT_REG_SZ, MemorySegment.NULL, buffer, size,
            ) as Int
            if (status != 0) return@use null
            String(buffer.asSlice(0, size.get(JAVA_INT, 0).toLong()).toArray(JAVA_BYTE), UTF_16LE).trimEnd('\u0000')
        }

        private val enumDescriptor = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS)
        private val checkSupportDescriptor = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS)
        private val IID_IDXGI_FACTORY1 = ComInterop.guid("770aae78-f26f-4dba-a829-253c83d1b387")
        private val IID_IDXGI_DEVICE = ComInterop.guid("54ec77fa-1377-44e6-8c32-88fd5f44c84c")

        // IDXGIFactory1 虚表：IUnknown 3 项、IDXGIObject 4 项、IDXGIFactory 5 项之后是 EnumAdapters1
        private const val ENUM_ADAPTERS1 = 12

        // IDXGIAdapter1 虚表：IUnknown 3 项、IDXGIObject 4 项，IDXGIAdapter 的 EnumOutputs、GetDesc、CheckInterfaceSupport，之后是 GetDesc1
        private const val CHECK_INTERFACE_SUPPORT = 9
        private const val GET_DESC1 = 10
        private const val DXGI_ERROR_NOT_FOUND = 0x887A0002.toInt()
        private const val DXGI_ADAPTER_FLAG_SOFTWARE = 2

        // DXGI_ADAPTER_DESC1：WCHAR Description[128]，四个 UINT，三个 SIZE_T，LUID，UINT Flags
        private const val DESCRIPTION_BYTES = 256L
        private const val VENDOR_OFFSET = 256L
        private const val DEVICE_OFFSET = 260L
        private const val DEDICATED_VIDEO_MEMORY_OFFSET = 272L
        private const val FLAGS_OFFSET = 304L
        private const val ADAPTER_DESC1_BYTES = 312L

        private const val MEMORYSTATUSEX_BYTES = 64L

        // HKEY_LOCAL_MACHINE 定义为 (HKEY)(ULONG_PTR)(LONG)0x80000002，按符号扩展到指针宽度
        private val HKEY_LOCAL_MACHINE = MemorySegment.ofAddress(0x80000002L.toInt().toLong())
        private const val RRF_RT_REG_SZ = 0x00000002
        private const val REGISTRY_VALUE_BYTES = 512
    }

    private object Mac {
        fun cpu(): String? = run("sysctl", "-n", "machdep.cpu.brand_string")?.trim()?.ifEmpty { null }

        fun memoryBytes(): Long? = run("sysctl", "-n", "hw.memsize")?.trim()?.toLongOrNull()

        // Apple 芯片的显卡就是芯片本身，「Chipset Model」写的是 Apple M2 一类；Intel 机型列出集显与独显
        fun gpus(): List<String> = run("system_profiler", "SPDisplaysDataType").orEmpty().lines()
            .mapNotNull { line -> line.trim().takeIf { it.startsWith("Chipset Model:") }?.substringAfter(':')?.trim() }
    }

    private object Linux {
        fun cpu(): String? = File("/proc/cpuinfo").takeIf(File::canRead)?.useLines { lines ->
            lines.firstOrNull { it.startsWith("model name") }?.substringAfter(':')?.trim()
        }

        fun memoryBytes(): Long? = File("/proc/meminfo").takeIf(File::canRead)?.useLines { lines ->
            lines.firstOrNull { it.startsWith("MemTotal:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.times(1024)
        }

        // 不起 lspci（未必装着）：PCI 编号与内核驱动名足以查到型号与驱动
        fun gpus(): List<String> = File("/sys/class/drm").listFiles { file -> file.name.matches(Regex("card[0-9]+")) }.orEmpty()
            .sortedBy { it.name }
            .mapNotNull { card ->
                val device = File(card, "device")
                val vendorId = File(device, "vendor").readTrimmedOrNull()?.removePrefix("0x") ?: return@mapNotNull null
                val deviceId = File(device, "device").readTrimmedOrNull()?.removePrefix("0x")
                val driver = File(device, "driver").canonicalFile.name
                val version = File("/sys/module/$driver/version").readTrimmedOrNull()
                "$vendorId:$deviceId（$driver${version?.let { " $it" }.orEmpty()}）"
            }

        private fun File.readTrimmedOrNull(): String? = runCatching { readText().trim() }.getOrNull()?.ifEmpty { null }
    }

    private fun run(vararg command: String): String? = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
        output
    }.getOrNull()

    private const val TAG = "Hardware"
    private const val MIB = 1024L * 1024
    private const val GIB = MIB * 1024
    private const val COMMAND_TIMEOUT_SECONDS = 10L
}
