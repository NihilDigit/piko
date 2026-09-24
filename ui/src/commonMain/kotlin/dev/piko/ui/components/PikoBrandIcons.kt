package dev.piko.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Piko 的品牌图形，与 docs/icon.svg、Android 启动图标同源。
 *
 * 写成 ImageVector 而不走 Compose 资源：只有两张图，为它们引入资源插件与生成的 Res 类，
 * 两端都要多一套打包配置，桌面端启动时还要多读一次资源索引。
 */
object PikoBrandIcons {
    private const val EAR_LEFT = "M10.5,16.8 C10.5,14.0 12.2,11.0 13.5,9.8 C14.2,9.0 15.5,9.2 16.0,10.2 L20.8,15.2 Z"
    private const val EAR_RIGHT = "M37.5,16.8 C37.5,14.0 35.8,11.0 34.5,9.8 C33.8,9.0 32.5,9.2 32.0,10.2 L27.2,15.2 Z"
    private const val FACE =
        "M15,15 H33 A7.5,7.5 0 0 1 40.5,22.5 V32.5 A7.5,7.5 0 0 1 33,40 H15 A7.5,7.5 0 0 1 7.5,32.5 V22.5 A7.5,7.5 0 0 1 15,15 Z"
    private const val EYE_LEFT =
        "M17.5,22.5 H17.5 A1.7,1.7 0 0 1 19.2,24.2 V27.0 A1.7,1.7 0 0 1 17.5,28.7 H17.5 A1.7,1.7 0 0 1 15.8,27.0 V24.2 A1.7,1.7 0 0 1 17.5,22.5 Z"
    private const val EYE_RIGHT =
        "M30.5,22.5 H30.5 A1.7,1.7 0 0 1 32.2,24.2 V27.0 A1.7,1.7 0 0 1 30.5,28.7 H30.5 A1.7,1.7 0 0 1 28.8,27.0 V24.2 A1.7,1.7 0 0 1 30.5,22.5 Z"
    private const val SMILE = "M20.8,31.8 Q24,35.5 27.2,31.8"
    private const val BADGE =
        "M12,0 H36 A12,12 0 0 1 48,12 V36 A12,12 0 0 1 36,48 H12 A12,12 0 0 1 0,36 V12 A12,12 0 0 1 12,0 Z"

    private val BrandBlue = Color(0xFF306EFF)

    /** 圆角品牌蓝底上的白色猫脸，登录页用。 */
    val Logo: ImageVector by lazy {
        ImageVector.Builder("PikoLogo", 48.dp, 48.dp, 48f, 48f).apply {
            addPath(addPathNodes(BADGE), fill = SolidColor(BrandBlue))
            cat(Color.White)
        }.build()
    }

    /**
     * 单色标志，随所在位置的内容色着色。去掉了自适应图标的安全区留白，
     * 否则直接当 24dp 图标用只剩约 15dp。
     */
    val Glyph: ImageVector by lazy {
        ImageVector.Builder("PikoGlyph", 24.dp, 24.dp, 48f, 48f).apply {
            addGroup(translationY = -1f)
            cat(Color.Black)
            clearGroup()
        }.build()
    }

    private fun ImageVector.Builder.cat(color: Color) {
        val brush = SolidColor(color)
        addPath(addPathNodes(EAR_LEFT), fill = brush)
        addPath(addPathNodes(EAR_RIGHT), fill = brush)
        addPath(addPathNodes(FACE), stroke = brush, strokeLineWidth = 2.5f, strokeLineJoin = StrokeJoin.Round)
        addPath(addPathNodes(EYE_LEFT), fill = brush)
        addPath(addPathNodes(EYE_RIGHT), fill = brush)
        addPath(addPathNodes(SMILE), stroke = brush, strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round)
    }
}
