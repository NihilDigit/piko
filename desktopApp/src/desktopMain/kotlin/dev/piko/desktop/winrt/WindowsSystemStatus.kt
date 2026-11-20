package dev.piko.desktop.winrt

import dev.piko.shared.log.PikoLog
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

enum class WindowsNetworkKind {
    Wifi,
    Ethernet,
    Cellular,
    Offline,
    Unknown,
}

data class WindowsSystemStatusSnapshot(
    val batteryPercent: Int?,
    val isCharging: Boolean,
    val hasBattery: Boolean,
    val networkKind: WindowsNetworkKind,
)

/**
 * Windows 电池与网络类型原生查询（纯 JDK FFM，零 JNI / 零后台常驻开销）：
 * - 电源状态：`kernel32!GetSystemPowerStatus`（`winbase.h` `SYSTEM_POWER_STATUS` 12 字节结构）
 * - 网络类型：`iphlpapi!GetIfTable2` + `FreeMibTable`（`netioapi.h` `MIB_IF_TABLE2` / `MIB_IF_ROW2`）
 *
 * [observe] 为冷流：仅在全屏播放器采集期间轮询，退出全屏立即取消，零闲置唤醒。
 */
object WindowsSystemStatus {

    private class NativeHandles(
        val getSystemPowerStatus: MethodHandle,
        val getIfTable2: MethodHandle?,
        val freeMibTable: MethodHandle?,
    )

    private val native: NativeHandles? by lazy {
        if (!WinRTSupport.isWindows) return@lazy null
        runCatching {
            val linker = Linker.nativeLinker()
            val kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global())
            val powerFn = linker.downcallHandle(
                kernel32.find("GetSystemPowerStatus").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS),
            )
            val iphlpapi = runCatching { SymbolLookup.libraryLookup("iphlpapi", Arena.global()) }.getOrNull()
            val getIfTable2 = iphlpapi?.find("GetIfTable2")?.orElse(null)?.let {
                linker.downcallHandle(it, FunctionDescriptor.of(JAVA_INT, ADDRESS))
            }
            val freeMibTable = iphlpapi?.find("FreeMibTable")?.orElse(null)?.let {
                linker.downcallHandle(it, FunctionDescriptor.ofVoid(ADDRESS))
            }
            NativeHandles(powerFn, getIfTable2, freeMibTable)
        }.onFailure { PikoLog.w(TAG, "Failed to bind Windows system status APIs", it) }.getOrNull()
    }

    fun snapshot(): WindowsSystemStatusSnapshot? {
        val handles = native ?: return null
        return runCatching {
            val (percent, charging, hasBattery) = readPowerStatus(handles)
            val network = readNetworkKind(handles)
            WindowsSystemStatusSnapshot(
                batteryPercent = percent,
                isCharging = charging,
                hasBattery = hasBattery,
                networkKind = network,
            )
        }.onFailure { PikoLog.w(TAG, "Failed to read Windows system status", it) }.getOrNull()
    }

    fun observe(pollIntervalMs: Long = 15_000L): Flow<WindowsSystemStatusSnapshot> = flow {
        while (currentCoroutineContext().isActive) {
            snapshot()?.let { emit(it) }
            delay(pollIntervalMs)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    private fun readPowerStatus(handles: NativeHandles): Triple<Int?, Boolean, Boolean> =
        Arena.ofConfined().use { arena ->
            val buf = arena.allocate(SYSTEM_POWER_STATUS_SIZE)
            val ok = handles.getSystemPowerStatus.invokeExact(buf) as Int
            if (ok == 0) return Triple(null, false, false)
            val acLineStatus = buf.get(JAVA_BYTE, 0L).toInt() and 0xFF
            val batteryFlag = buf.get(JAVA_BYTE, 1L).toInt() and 0xFF
            val batteryPercent = buf.get(JAVA_BYTE, 2L).toInt() and 0xFF
            val hasBattery = batteryFlag != BATTERY_FLAG_NO_BATTERY &&
                batteryFlag != BATTERY_FLAG_UNKNOWN &&
                batteryPercent in 0..100
            val isCharging = hasBattery && (acLineStatus == AC_LINE_ONLINE || (batteryFlag and BATTERY_FLAG_CHARGING) != 0)
            Triple(if (hasBattery) batteryPercent else null, isCharging, hasBattery)
        }

    private fun readNetworkKind(handles: NativeHandles): WindowsNetworkKind {
        val getIfTable2 = handles.getIfTable2 ?: return WindowsNetworkKind.Unknown
        val freeMibTable = handles.freeMibTable ?: return WindowsNetworkKind.Unknown
        return Arena.ofConfined().use { arena ->
            val tablePtrSlot = arena.allocate(ADDRESS)
            val status = getIfTable2.invokeExact(tablePtrSlot) as Int
            if (status != NO_ERROR) return WindowsNetworkKind.Unknown
            val rawTable = tablePtrSlot.get(ADDRESS, 0L)
            if (rawTable == MemorySegment.NULL) return WindowsNetworkKind.Unknown
            try {
                val header = rawTable.reinterpret(MIB_IF_TABLE2_HEADER_SIZE)
                val count = header.get(JAVA_INT, 0L).coerceIn(0, MAX_IF_ENTRIES)
                if (count == 0) return WindowsNetworkKind.Offline
                val fullSize = MIB_IF_TABLE2_HEADER_SIZE + count.toLong() * MIB_IF_ROW2_SIZE
                val table = rawTable.reinterpret(fullSize)

                var hasEthernet = false
                var hasWifi = false
                var hasCellular = false
                for (i in 0 until count) {
                    val base = MIB_IF_TABLE2_HEADER_SIZE + i.toLong() * MIB_IF_ROW2_SIZE
                    val operStatus = table.get(JAVA_INT, base + OFFSET_OPER_STATUS)
                    if (operStatus != IF_OPER_STATUS_UP) continue
                    val mediaConnect = table.get(JAVA_INT, base + OFFSET_MEDIA_CONNECT_STATE)
                    if (mediaConnect != MEDIA_CONNECT_STATE_CONNECTED) continue
                    val flags = table.get(JAVA_BYTE, base + OFFSET_FLAGS).toInt() and 0xFF
                    val isHardware = (flags and FLAG_HARDWARE_INTERFACE) != 0
                    val isFilter = (flags and FLAG_FILTER_INTERFACE) != 0
                    if (!isHardware || isFilter) continue

                    val ifType = table.get(JAVA_INT, base + OFFSET_IF_TYPE)
                    val physMedium = table.get(JAVA_INT, base + OFFSET_PHYSICAL_MEDIUM_TYPE)
                    when {
                        ifType == IF_TYPE_IEEE80211 ||
                            physMedium == NDIS_PHYSICAL_MEDIUM_NATIVE_802_11 ||
                            physMedium == NDIS_PHYSICAL_MEDIUM_WIRELESS_LAN -> hasWifi = true
                        ifType == IF_TYPE_WWANPP ||
                            ifType == IF_TYPE_WWANPP2 ||
                            physMedium == NDIS_PHYSICAL_MEDIUM_WIRELESS_WAN -> hasCellular = true
                        ifType == IF_TYPE_ETHERNET_CSMACD ||
                            physMedium == NDIS_PHYSICAL_MEDIUM_802_3 -> hasEthernet = true
                    }
                }
                when {
                    hasEthernet -> WindowsNetworkKind.Ethernet
                    hasWifi -> WindowsNetworkKind.Wifi
                    hasCellular -> WindowsNetworkKind.Cellular
                    else -> WindowsNetworkKind.Offline
                }
            } finally {
                freeMibTable.invokeExact(rawTable)
            }
        }
    }

    private const val TAG = "SystemStatus"
    private const val NO_ERROR = 0
    private const val SYSTEM_POWER_STATUS_SIZE = 12L
    private const val AC_LINE_ONLINE = 1
    private const val BATTERY_FLAG_CHARGING = 8
    private const val BATTERY_FLAG_NO_BATTERY = 128
    private const val BATTERY_FLAG_UNKNOWN = 255

    // 对照 Windows SDK 10.0.26100.0 shared/netioapi.h 逐字段对齐计算（x64 / arm64）
    private const val MIB_IF_TABLE2_HEADER_SIZE = 8L
    private const val MIB_IF_ROW2_SIZE = 1352L
    private const val MAX_IF_ENTRIES = 512

    private const val OFFSET_IF_TYPE = 1128L
    private const val OFFSET_PHYSICAL_MEDIUM_TYPE = 1140L
    private const val OFFSET_FLAGS = 1152L
    private const val OFFSET_OPER_STATUS = 1156L
    private const val OFFSET_MEDIA_CONNECT_STATE = 1164L

    private const val FLAG_HARDWARE_INTERFACE = 0x01
    private const val FLAG_FILTER_INTERFACE = 0x02

    private const val IF_OPER_STATUS_UP = 1
    private const val MEDIA_CONNECT_STATE_CONNECTED = 1

    private const val IF_TYPE_ETHERNET_CSMACD = 6
    private const val IF_TYPE_IEEE80211 = 71
    private const val IF_TYPE_WWANPP = 243
    private const val IF_TYPE_WWANPP2 = 244

    private const val NDIS_PHYSICAL_MEDIUM_WIRELESS_LAN = 1
    private const val NDIS_PHYSICAL_MEDIUM_WIRELESS_WAN = 8
    private const val NDIS_PHYSICAL_MEDIUM_NATIVE_802_11 = 9
    private const val NDIS_PHYSICAL_MEDIUM_802_3 = 14
}
