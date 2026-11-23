package dev.piko.ui.screens.settings

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 设置目录亮哪一类（ux-review M13）。末尾几类矮，滚到底时它们的起点到不了视口顶端：
 * 只按起点算，屏幕上是「关于」，目录却亮着更靠前的一类。
 */
class ActiveSettingsSectionTest {
    private val sections = listOf("外观", "网络", "同步与数据", "关于")
    // 后两类各只有几百像素，内容总高减去视口后最多滚到 1500
    private val offsets = mapOf("外观" to 0, "网络" to 900, "同步与数据" to 1400, "关于" to 1700)
    private val maxScroll = 1500

    private fun active(scroll: Int, pinned: Pair<String, Int?>? = null) =
        activeSettingsSection(sections, offsets, scroll, maxScroll, slack = 48, pinned = pinned)

    @Test
    fun bottomLightsLastSection() {
        assertEquals("关于", active(maxScroll))
    }

    @Test
    fun clickedSectionStaysLitWhereItCannotReachTheTop() {
        // 点「同步与数据」，滚到底停下：按位置会亮「关于」，应亮点的那一类
        assertEquals("同步与数据", active(maxScroll, pinned = "同步与数据" to maxScroll))
        // 之后自己滚开，回到按位置算
        assertEquals("网络", active(1000, pinned = "同步与数据" to maxScroll))
    }

    @Test
    fun clickedSectionLitWhileScrolling() {
        assertEquals("关于", active(300, pinned = "关于" to null))
    }

    @Test
    fun headingWithinSlackCounts() {
        assertEquals("外观", active(800))
        assertEquals("网络", active(860))
    }
}
