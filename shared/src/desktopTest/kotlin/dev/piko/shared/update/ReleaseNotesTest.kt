package dev.piko.shared.update

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseNotesTest {

    @Test
    fun updateDialogStopsBeforeTheTemplatesDownloadSection() {
        // 读仓库里真实的模板：标题在模板与代码两处各写一份，改了一边另一边不会报错，
        // 弹窗只会默默显示整张下载表
        val template = File("../.github/release-notes.md").readText()
        val body = "- 修复了某个问题。\n- 新增了某项功能。\n\n" + template

        assertEquals("- 修复了某个问题。\n- 新增了某项功能。", updateNotesOf(body))
    }
}
