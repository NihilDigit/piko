package dev.piko.ui.screens.clips

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackdropBlurTest {
    @Test
    fun solidColorRemainsExactWithoutDarkEdgeBleed() {
        val solid = 0xFFE6A15C.toInt()
        val width = 48
        val height = 27
        val input = IntArray(width * height) { solid }
        val output = BackdropBlurKernel.blurArgb(input, width, height, radius = 4)
        assertEquals(input.size, output.size)
        // 边缘钳位（Clamp-to-Edge）下纯色图像四个角与中心均不得掺入透明或黑边晕染
        assertTrue(output.all { it == solid })
    }

    @Test
    fun sharpImpulseDiffusesSmoothlyAndSymmetrically() {
        val size = 21
        val center = size / 2
        val input = IntArray(size * size) { 0xFF000000.toInt() }
        input[center * size + center] = 0xFFFFFFFF.toInt()

        val blurred = BackdropBlurKernel.blurArgb(input, size, size, radius = 3)
        fun red(x: Int, y: Int) = (blurred[y * size + x] ushr 16) and 0xFF

        val peak = red(center, center)
        val near = red(center + 1, center)
        val mid = red(center + 3, center)
        val far = red(0, 0)
        assertTrue(peak in 1..254, "peak=$peak should diffuse below 255")
        assertTrue(peak >= near && near >= mid && mid >= far, "expected monotonic falloff: $peak, $near, $mid, $far")
        assertEquals(red(center - 2, center), red(center + 2, center))
        assertEquals(red(center, center - 2), red(center, center + 2))
    }

    @Test
    fun equivalentRadiusScalesWithCropMagnificationAndAspect() {
        // 竖屏容器 (1080x2400) 裁切 16:9 横图 (64x36)：放大倍率大，小图半径收敛到最小平滑下限 2px
        val portraitCrop = BackdropBlurKernel.equivalentRadiusPx(64, 36, 1080, 2400, targetBlurPx = 96f)
        // 桌面宽容器 (960x540) 裁切同比例横图 (64x36)：放大倍率仅 15x，小图需要更大的像素模糊半径才等价于 64px 屏幕模糊
        val landscapeFit = BackdropBlurKernel.equivalentRadiusPx(64, 36, 960, 540, targetBlurPx = 64f)
        assertEquals(2, portraitCrop)
        assertTrue(landscapeFit > portraitCrop, "landscapeFit=$landscapeFit should exceed portraitCrop=$portraitCrop")
    }
}
