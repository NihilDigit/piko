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

    /** Google 官方四色 G 标志（24dp 视口），用于「使用 Google 继续」登录按钮。 */
    val Google: ImageVector by lazy {
        ImageVector.Builder("GoogleG", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                addPathNodes("M22.56,12.25 C22.56,11.47 22.49,10.72 22.36,10.0 L12.0,10.0 L12.0,14.26 L17.92,14.26 C17.66,15.63 16.88,16.79 15.71,17.57 L15.71,20.34 L19.28,20.34 C21.36,18.42 22.56,15.6 22.56,12.25 Z"),
                fill = SolidColor(Color(0xFF4285F4)),
            )
            addPath(
                addPathNodes("M12.0,23.0 C14.97,23.0 17.46,22.02 19.28,20.34 L15.71,17.57 C14.73,18.23 13.48,18.63 12.0,18.63 C9.14,18.63 6.71,16.7 5.84,14.1 L2.18,14.1 L2.18,16.94 C3.99,20.53 7.7,23.0 12.0,23.0 Z"),
                fill = SolidColor(Color(0xFF34A853)),
            )
            addPath(
                addPathNodes("M5.84,14.09 C5.62,13.43 5.49,12.73 5.49,12.0 C5.49,11.27 5.62,10.57 5.84,9.91 L5.84,7.07 L2.18,7.07 C1.43,8.55 1.0,10.22 1.0,12.0 C1.0,13.78 1.43,15.45 2.18,16.93 L5.84,14.09 Z"),
                fill = SolidColor(Color(0xFFFBBC05)),
            )
            addPath(
                addPathNodes("M12.0,5.38 C13.62,5.38 15.06,5.94 16.21,7.02 L19.36,3.87 C17.45,2.09 14.97,1.0 12.0,1.0 C7.7,1.0 3.99,3.47 2.18,7.07 L5.84,9.91 C6.71,7.31 9.14,5.38 12.0,5.38 Z"),
                fill = SolidColor(Color(0xFFEA4335)),
            )
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
