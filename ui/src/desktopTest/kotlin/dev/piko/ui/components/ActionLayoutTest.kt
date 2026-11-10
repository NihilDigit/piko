package dev.piko.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActionLayoutTest {
    private fun action(label: String, tier: ActionTier = ActionTier.Standard, group: ActionGroup = ActionGroup.Default, destructive: Boolean = false) =
        SheetAction(Icons.Outlined.Info, label, {}, destructive = destructive, group = group, tier = tier)

    private val properties = action("属性", group = ActionGroup.Properties)
    private val trash = action("移入回收站", tier = ActionTier.Quick, destructive = true)

    @Test
    fun `six non-destructive items counting properties stay flat and seventh brings icon row`() {
        val six = listOf(
            action("下载", ActionTier.Quick),
            action("分享", ActionTier.Quick),
            action("移动到", group = ActionGroup.Organize),
            action("隐藏预览"),
            action("归档"),
            properties,
            trash,
        )
        val flatLayout = layoutActions(six)
        assertTrue(flatLayout.quick.isEmpty())
        assertEquals(listOf("下载", "分享", "隐藏预览", "归档", "移动到"), flatLayout.sections.flatten().map { it.label })
        assertEquals(listOf(properties), flatLayout.properties)
        assertEquals(listOf(trash), flatLayout.danger)

        val seven = listOf(
            action("下载", ActionTier.Quick),
            action("分享", ActionTier.Quick),
            action("移动到"),
            action("复制到"),
            action("隐藏预览"),
            action("归档"),
            properties,
            trash,
        )
        val tieredLayout = layoutActions(seven)
        assertEquals(listOf("下载", "分享"), tieredLayout.quick.map { it.label })
        assertEquals(listOf("移动到", "复制到", "隐藏预览", "归档"), tieredLayout.sections.flatten().map { it.label })
    }

    @Test
    fun `icon row caps at four and groups gather non-contiguous items`() {
        val quick = (1..5).map { action("快$it", ActionTier.Quick) }
        val capped = layoutActions(quick + action("移动到") + properties)
        assertEquals(listOf("快1", "快2", "快3", "快4"), capped.quick.map { it.label })
        assertEquals(listOf("快5", "移动到"), capped.sections.flatten().map { it.label })

        val grouped = layoutActions(
            listOf(
                action("下载", ActionTier.Quick, ActionGroup.Open),
                action("移动到", group = ActionGroup.Organize),
                action("用外部播放器打开", group = ActionGroup.Open),
                action("复制到", group = ActionGroup.Organize),
                action("A"),
                action("B"),
                properties,
            ),
        )
        assertEquals(listOf(listOf("移动到", "复制到"), listOf("用外部播放器打开"), listOf("A", "B")), grouped.sections.map { s -> s.map { it.label } })
    }

    @Test
    fun `pointerMenuPosition flips per overflowing axis and hugs window edge when oversized`() {
        val window = IntSize(1440, 900)
        val menu = IntSize(280, 400)
        assertEquals(IntOffset(300, 200), pointerMenuPosition(IntOffset(300, 200), menu, window))
        assertEquals(IntOffset(300, 800 - 400), pointerMenuPosition(IntOffset(300, 800), menu, window))
        assertEquals(IntOffset(1300 - 280, 200), pointerMenuPosition(IntOffset(1300, 200), menu, window))
        assertEquals(IntOffset(1400 - 280, 850 - 400), pointerMenuPosition(IntOffset(1400, 850), menu, window))
        assertEquals(IntOffset(300, 900 - 700), pointerMenuPosition(IntOffset(300, 450), IntSize(280, 700), window))
        assertEquals(IntOffset(300, 0), pointerMenuPosition(IntOffset(300, 450), IntSize(280, 1000), window))
    }
}
