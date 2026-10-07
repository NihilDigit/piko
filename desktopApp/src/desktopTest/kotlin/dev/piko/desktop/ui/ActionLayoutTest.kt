package dev.piko.desktop.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import dev.piko.ui.components.ActionGroup
import dev.piko.ui.components.ActionTier
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.layoutActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 面板与右键菜单共用的分档规则。边界都落在「非危险项连同属性」的计数上：六项平铺、七项分档，
 * 危险项不计入；数错一项，普通文件的菜单就在有图标行与没有之间来回换。
 */
class ActionLayoutTest {
    private fun action(label: String, tier: ActionTier = ActionTier.Standard, group: ActionGroup = ActionGroup.Default, destructive: Boolean = false) =
        SheetAction(Icons.Outlined.Info, label, {}, destructive = destructive, group = group, tier = tier)

    private val properties = action("属性", group = ActionGroup.Properties)
    private val trash = action("移入回收站", tier = ActionTier.Quick, destructive = true)

    @Test
    fun `six non-destructive items counting properties stay flat`() {
        val actions = listOf(
            action("下载", ActionTier.Quick),
            action("分享", ActionTier.Quick),
            action("移动到", group = ActionGroup.Organize),
            action("隐藏预览"),
            action("归档"),
            properties,
            trash,
        )
        val layout = layoutActions(actions)
        assertTrue(layout.quick.isEmpty())
        assertEquals(listOf("下载", "分享", "隐藏预览", "归档", "移动到"), layout.sections.flatten().map { it.label })
        assertEquals(listOf(properties), layout.properties)
        assertEquals(listOf(trash), layout.danger)
    }

    @Test
    fun `the seventh item brings the icon row`() {
        val actions = listOf(
            action("下载", ActionTier.Quick),
            action("分享", ActionTier.Quick),
            action("移动到"),
            action("复制到"),
            action("隐藏预览"),
            action("归档"),
            properties,
            trash,
        )
        val layout = layoutActions(actions)
        assertEquals(listOf("下载", "分享"), layout.quick.map { it.label })
        assertEquals(listOf("移动到", "复制到", "隐藏预览", "归档"), layout.sections.flatten().map { it.label })
    }

    @Test
    fun `the icon row holds at most four and the rest fall back to the list`() {
        val quick = (1..5).map { action("快$it", ActionTier.Quick) }
        val layout = layoutActions(quick + action("移动到") + properties)
        assertEquals(listOf("快1", "快2", "快3", "快4"), layout.quick.map { it.label })
        assertEquals(listOf("快5", "移动到"), layout.sections.flatten().map { it.label })
    }

    @Test
    fun `a group gathers its items even when they were added apart`() {
        val actions = listOf(
            action("下载", ActionTier.Quick, ActionGroup.Open),
            action("移动到", group = ActionGroup.Organize),
            action("用外部播放器打开", group = ActionGroup.Open),
            action("复制到", group = ActionGroup.Organize),
            action("A"),
            action("B"),
            properties,
        )
        val layout = layoutActions(actions)
        assertEquals(listOf(listOf("移动到", "复制到"), listOf("用外部播放器打开"), listOf("A", "B")), layout.sections.map { s -> s.map { it.label } })
    }
}
