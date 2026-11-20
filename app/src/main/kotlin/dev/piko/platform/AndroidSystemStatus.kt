package dev.piko.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import dev.piko.shared.log.PikoLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

enum class AndroidNetworkKind {
    Wifi,
    Ethernet,
    Cellular,
    Offline,
    Unknown,
}

data class AndroidSystemStatusSnapshot(
    val batteryPercent: Int?,
    val isCharging: Boolean,
    val networkKind: AndroidNetworkKind,
)

/**
 * Android 全屏播放器状态栏原生观察器（冷流）：
 * 仅在全屏播放器采集期间注册 `ACTION_BATTERY_CHANGED` 与 `ConnectivityManager.NetworkCallback`，
 * 退出全屏即注销，零常驻广播/回调开销。
 */
object AndroidSystemStatus {

    fun observe(context: Context): Flow<AndroidSystemStatusSnapshot> = callbackFlow {
        val appContext = context.applicationContext
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        var batteryPercent: Int? = null
        var isCharging = false
        var networkKind = currentNetworkKind(connectivity)

        fun emitCurrent() {
            trySend(
                AndroidSystemStatusSnapshot(
                    batteryPercent = batteryPercent,
                    isCharging = isCharging,
                    networkKind = networkKind,
                ),
            )
        }

        fun updateBattery(intent: Intent?) {
            if (intent == null) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                batteryPercent = ((level * 100f) / scale).toInt().coerceIn(0, 100)
            }
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }

        val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                updateBattery(intent)
                emitCurrent()
            }
        }
        val initialBattery = runCatching {
            appContext.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.onFailure { PikoLog.w(TAG, "Failed to register battery status receiver", it) }.getOrNull()
        updateBattery(initialBattery)

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkKind = currentNetworkKind(connectivity)
                emitCurrent()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                networkKind = networkKindOf(networkCapabilities)
                emitCurrent()
            }

            override fun onLost(network: Network) {
                networkKind = currentNetworkKind(connectivity)
                emitCurrent()
            }
        }
        val networkRegistered = if (connectivity != null) {
            runCatching {
                connectivity.registerDefaultNetworkCallback(networkCallback)
                true
            }.onFailure { PikoLog.w(TAG, "Failed to register default network callback", it) }.getOrDefault(false)
        } else {
            false
        }

        emitCurrent()

        awaitClose {
            runCatching { appContext.unregisterReceiver(batteryReceiver) }
            if (networkRegistered) {
                runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
            }
        }
    }.distinctUntilChanged()

    private fun currentNetworkKind(connectivity: ConnectivityManager?): AndroidNetworkKind {
        if (connectivity == null) return AndroidNetworkKind.Unknown
        val active = runCatching { connectivity.activeNetwork }.getOrNull() ?: return AndroidNetworkKind.Offline
        val caps = runCatching { connectivity.getNetworkCapabilities(active) }.getOrNull()
            ?: return AndroidNetworkKind.Offline
        return networkKindOf(caps)
    }

    private fun networkKindOf(caps: NetworkCapabilities): AndroidNetworkKind = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> AndroidNetworkKind.Wifi
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> AndroidNetworkKind.Ethernet
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> AndroidNetworkKind.Cellular
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> AndroidNetworkKind.Unknown
        else -> AndroidNetworkKind.Offline
    }

    private const val TAG = "SystemStatus"
}
