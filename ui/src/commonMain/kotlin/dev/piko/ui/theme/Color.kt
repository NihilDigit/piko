package dev.piko.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * M3 色彩角色里没有「成功」，这里按深浅主题各给一档。
 *
 * 不能直接用 FixedColors.InstantMatchGreen：#10B981 在浅色 surface 上只有约 2.5:1，
 * 做文字不到 4.5:1，做图标也不到 3:1。那一档是给压在深色媒体遮罩上的元素用的。
 * 浅色档 #1B6C3C 在 surface 上约 6:1，深色档 #8AD6A0 在深色 surface 上约 10:1。
 */
@Immutable
data class PikoStatusColors(val success: Color)

val PikoLightStatusColors = PikoStatusColors(success = Color(0xFF1B6C3C))
val PikoDarkStatusColors = PikoStatusColors(success = Color(0xFF8AD6A0))

/**
 * 不受系统深浅主题影响的固定色彩。
 * 用于压在视频、图片、封面、播放控制条上的元素，保证高对比度与绝对可读性。
 */
object FixedColors {
    /** 压在媒体封面或画面上的半透明遮罩 (0.55 alpha，过雪景/亮底保 4.5:1 对比度) */
    val ScrimOnMedia = Color(0x8C000000)

    /** 压在媒体遮罩上的文字与图标 */
    val OnMedia = Color(0xFFF0F4F8)

    /** 播放器主控件的控制层衬底 */
    val PlayerControlScrim = Color(0x99000000)

    /** 链接与高亮色 (按明度分深浅两档以保读性) */
    val MentionLight = Color(0xFF026AA7)
    val MentionDark = Color(0xFF58B6FF)

    /** 磁力秒传命中标记绿 */
    val InstantMatchGreen = Color(0xFF10B981)

    /** 待下载/离线下载状态蓝 */
    val OfflinePendingBlue = Color(0xFF3B82F6)
}
