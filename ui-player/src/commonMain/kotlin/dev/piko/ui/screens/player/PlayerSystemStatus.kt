package dev.piko.ui.screens.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.piko.shared.media.player.PlayerStatusBarConfig
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.PlayerNetworkType
import dev.piko.ui.platform.PlayerSystemStatusInfo
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.player_net_cellular
import dev.piko.ui.resources.player_net_ethernet
import dev.piko.ui.resources.player_net_offline
import dev.piko.ui.resources.player_net_wifi
import dev.piko.ui.theme.LocalFixedColors
import dev.piko.ui.theme.tabular
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

private val StatusClusterHorizontalPadding = 10.dp
private val StatusClusterHeight = 32.dp
private val StatusNetworkIconSize = 13.dp
private val StatusIconLabelGap = 3.dp
private val BatteryGlyphWidth = 18.dp
private val BatteryGlyphChargingWidth = 25.dp
private val BatteryGlyphHeight = 11.dp
private val BatteryBodyWidth = 15.5.dp
private val BatteryStrokeWidth = 1.15.dp
private val BatteryBodyCorner = 2.4.dp
private val BatteryFillInset = 2.1.dp
private val BatteryFillCorner = 1.dp
private val BatteryCapGap = 0.8.dp
private val BatteryCapWidth = 1.4.dp
private val BatteryCapHeight = 4.2.dp
private val BatteryCapCorner = 0.8.dp
private val BatteryBoltStart = 18.2.dp
private val BatteryBoltWidth = 6.5.dp
private const val LowBatteryThreshold = 15
private const val StatusSecondaryAlpha = 0.88f
private val ChargingAccentColor = Color(0xFF8CE99A)
private val StatusBorderlessShadow = Shadow(color = Color.Black.copy(alpha = 0.6f), offset = Offset(0f, 1f), blurRadius = 6f)

/**
 * 全屏顶栏系统状态胶囊（对齐 Bilby `PlayerStatusCluster`）：
 * 支持独立开关时间（[PlayerStatusBarConfig.showClock]）、网络（[PlayerStatusBarConfig.showNetwork]）、
 * 电量（[PlayerStatusBarConfig.showBattery]）与图标显示（[PlayerStatusBarConfig.showIcons]）。
 * 仅在全屏且至少一项开启时挂载，关闭的项零协程、零系统监听开销。
 */
@Composable
internal fun PlayerSystemStatusCapsule(
    modifier: Modifier = Modifier,
    showBackground: Boolean = true,
    config: PlayerStatusBarConfig = LocalPlayerLayoutPrefs.current.statusBar,
    previewStatus: PlayerSystemStatusInfo? = null,
    previewClockText: String? = null,
) {
    if (!config.hasAnyItem) return

    val observeNative = previewStatus == null && (config.showNetwork || config.showBattery)
    val platform = if (observeNative) LocalPikoPlatform.current else null
    val statusFlow = remember(platform, observeNative) {
        if (observeNative && platform != null) platform.observeSystemStatus() else kotlinx.coroutines.flow.emptyFlow()
    }
    val observedStatus by statusFlow.collectAsState(initial = null)
    val status = previewStatus ?: observedStatus
    var clockText by remember(previewClockText) { mutableStateOf(previewClockText ?: currentClockText()) }

    LaunchedEffect(config.showClock, previewClockText) {
        if (!config.showClock || previewClockText != null) return@LaunchedEffect
        while (true) {
            val now = Clock.System.now()
            val local = now.toLocalDateTime(TimeZone.currentSystemDefault())
            clockText = formatHourMinute(local.hour, local.minute)
            val elapsedInMinuteMs = local.second * 1_000L + (now.toEpochMilliseconds() % 1_000L).coerceAtLeast(0L)
            val waitMs = (60_000L - elapsedInMinuteMs + 50L).coerceIn(1_000L, 60_050L)
            delay(waitMs)
        }
    }

    val fixed = LocalFixedColors.current
    val timeStyle = if (showBackground) {
        MaterialTheme.typography.labelMedium.tabular
    } else {
        MaterialTheme.typography.labelMedium.tabular.copy(shadow = StatusBorderlessShadow)
    }
    val detailStyle = if (showBackground) {
        MaterialTheme.typography.labelSmall.tabular
    } else {
        MaterialTheme.typography.labelSmall.tabular.copy(shadow = StatusBorderlessShadow)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .height(StatusClusterHeight)
            .then(
                if (showBackground) {
                    Modifier
                        .clip(CircleShape)
                        .background(playerContainerColor())
                        .border(1.dp, fixed.HudSpecularBorder, CircleShape)
                        .padding(horizontal = StatusClusterHorizontalPadding)
                } else {
                    Modifier.padding(horizontal = 4.dp)
                },
            ),
    ) {
        if (config.showClock) {
            Text(
                text = clockText,
                style = timeStyle,
                color = fixed.OnMedia,
                maxLines = 1,
                softWrap = false,
            )
        }

        if (config.showNetwork) {
            status?.networkType?.let { network ->
                val isOffline = network == PlayerNetworkType.Offline
                val netColor = if (isOffline) {
                    MaterialTheme.colorScheme.error
                } else {
                    fixed.OnMedia.copy(alpha = StatusSecondaryAlpha)
                }
                val netIcon = when (network) {
                    PlayerNetworkType.Wifi -> Icons.Outlined.Wifi
                    PlayerNetworkType.Cellular -> Icons.Outlined.SignalCellularAlt
                    PlayerNetworkType.Ethernet -> Icons.Outlined.SettingsEthernet
                    PlayerNetworkType.Offline -> Icons.Outlined.WifiOff
                }
                val netLabel = when (network) {
                    PlayerNetworkType.Wifi -> stringResource(Res.string.player_net_wifi)
                    PlayerNetworkType.Cellular -> stringResource(Res.string.player_net_cellular)
                    PlayerNetworkType.Ethernet -> stringResource(Res.string.player_net_ethernet)
                    PlayerNetworkType.Offline -> stringResource(Res.string.player_net_offline)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(StatusIconLabelGap),
                ) {
                    if (config.showIcons) {
                        Icon(
                            imageVector = netIcon,
                            contentDescription = null,
                            tint = netColor,
                            modifier = Modifier.size(StatusNetworkIconSize),
                        )
                    }
                    Text(
                        text = netLabel,
                        style = detailStyle,
                        color = netColor,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }

        if (config.showBattery) {
            status?.batteryPercent?.let { rawPercent ->
                val percent = rawPercent.coerceIn(0, 100)
                val isCharging = status?.isCharging == true
                val isLow = !isCharging && percent <= LowBatteryThreshold
                val textAndOutlineTint = if (isLow) {
                    MaterialTheme.colorScheme.error
                } else if (isCharging && !config.showIcons) {
                    ChargingAccentColor
                } else {
                    fixed.OnMedia.copy(alpha = StatusSecondaryAlpha)
                }
                val fillTint = when {
                    isCharging -> ChargingAccentColor
                    isLow -> MaterialTheme.colorScheme.error
                    else -> fixed.OnMedia.copy(alpha = StatusSecondaryAlpha)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (config.showIcons) {
                        BatteryGlyph(
                            percent = percent,
                            isCharging = isCharging,
                            outlineColor = textAndOutlineTint,
                            fillColor = fillTint,
                        )
                    }
                    Text(
                        text = if (!config.showIcons && isCharging) "⚡$percent%" else "$percent%",
                        style = detailStyle,
                        color = textAndOutlineTint,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun BatteryGlyph(
    percent: Int,
    isCharging: Boolean,
    outlineColor: Color,
    fillColor: Color,
) {
    val boltPath = remember { Path() }
    Canvas(
        modifier = Modifier.size(
            width = if (isCharging) BatteryGlyphChargingWidth else BatteryGlyphWidth,
            height = BatteryGlyphHeight,
        ),
    ) {
        val strokePx = BatteryStrokeWidth.toPx()
        val bodyW = BatteryBodyWidth.toPx()
        val bodyH = size.height
        val cornerPx = BatteryBodyCorner.toPx()

        drawRoundRect(
            color = outlineColor,
            topLeft = Offset(strokePx / 2f, strokePx / 2f),
            size = Size(bodyW - strokePx, bodyH - strokePx),
            cornerRadius = CornerRadius(cornerPx, cornerPx),
            style = Stroke(width = strokePx),
        )

        val capW = BatteryCapWidth.toPx()
        val capH = BatteryCapHeight.toPx()
        val capCorner = BatteryCapCorner.toPx()
        drawRoundRect(
            color = outlineColor.copy(alpha = 0.75f),
            topLeft = Offset(bodyW + BatteryCapGap.toPx(), (bodyH - capH) / 2f),
            size = Size(capW, capH),
            cornerRadius = CornerRadius(capCorner, capCorner),
        )

        val inset = BatteryFillInset.toPx()
        val maxFillW = (bodyW - inset * 2f).coerceAtLeast(0f)
        val fillH = (bodyH - inset * 2f).coerceAtLeast(0f)
        val clamped = percent.coerceIn(0, 100)
        if (clamped > 0 && maxFillW > 0f && fillH > 0f) {
            val minVisiblePx = 1.5.dp.toPx().coerceAtMost(maxFillW)
            val fillW = (maxFillW * (clamped / 100f)).coerceAtLeast(minVisiblePx)
            val fillCorner = BatteryFillCorner.toPx()
            drawRoundRect(
                color = fillColor,
                topLeft = Offset(inset, inset),
                size = Size(fillW, fillH),
                cornerRadius = CornerRadius(fillCorner, fillCorner),
            )
        }

        if (isCharging) {
            val x0 = BatteryBoltStart.toPx()
            val w = BatteryBoltWidth.toPx()
            val h = bodyH
            boltPath.reset()
            boltPath.moveTo(x0 + 0.58f * w, 0.04f * h)
            boltPath.lineTo(x0 + 0.14f * w, 0.56f * h)
            boltPath.lineTo(x0 + 0.46f * w, 0.56f * h)
            boltPath.lineTo(x0 + 0.36f * w, 0.96f * h)
            boltPath.lineTo(x0 + 0.86f * w, 0.42f * h)
            boltPath.lineTo(x0 + 0.54f * w, 0.42f * h)
            boltPath.close()
            drawPath(path = boltPath, color = ChargingAccentColor)
        }
    }
}

private fun currentClockText(): String {
    val local = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    return formatHourMinute(local.hour, local.minute)
}

private fun formatHourMinute(hour: Int, minute: Int): String =
    "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
