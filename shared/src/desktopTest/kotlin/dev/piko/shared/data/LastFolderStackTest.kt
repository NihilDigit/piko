package dev.piko.shared.data

import kotlin.test.Test
import kotlin.test.assertEquals

class LastFolderStackTest {

    @Test
    fun `names with the old separators survive a round trip`() {
        val stack = listOf(
            PikoDriveRepository.ROOT_BREADCRUMB,
            PikoPathBreadcrumb("a1", "Series;2026"),
            PikoPathBreadcrumb("b2", "S01::extra"),
            PikoPathBreadcrumb("c3", "[BD] 第 1 话"),
        )
        assertEquals(stack, LastFolderStack.decode(LastFolderStack.encode(stack)))
    }

    // 升级前存下的旧格式仍能读回，用户不至于升级后被送回根目录
    @Test
    fun `stacks saved in the old format still load`() {
        assertEquals(
            listOf(PikoPathBreadcrumb("", "网盘"), PikoPathBreadcrumb("piko:starred", "星标")),
            LastFolderStack.decode("::网盘;piko:starred::星标"),
        )
    }
}
