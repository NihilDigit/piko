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

        assertEquals("• 修复了某个问题。\n• 新增了某项功能。", updateNotesOf(body))
    }

    @Test
    fun handwrittenChangelogShowsWithoutMarkdownMarks() {
        // v0.11.0 正文的原样开头，按 CLAUDE.md 的更新日志格式手写：弹窗曾把「## 修复」原样显示
        val body = """
            |新增本机文件上传；界面改用新版导航与加载样式；Windows 版适配鼠标操作。
            |
            |## 修复
            |
            |- 启动时若上次所在的文件夹已被删除，显示为空文件夹。
            |
            |## 变化
            |
            |- 可上传本机文件与文件夹，中断后可继续。
            |- 支持 **分卷** 以外的 `.7z` 压缩包，见 [说明](https://example.com)。
            |
            |## 下载
            |
            || 设备 | 下载 |
        """.trimMargin().replace("\n", "\r\n")

        assertEquals(
            """
            |新增本机文件上传；界面改用新版导航与加载样式；Windows 版适配鼠标操作。
            |
            |修复
            |
            |• 启动时若上次所在的文件夹已被删除，显示为空文件夹。
            |
            |变化
            |
            |• 可上传本机文件与文件夹，中断后可继续。
            |• 支持 分卷 以外的 .7z 压缩包，见 说明。
            """.trimMargin(),
            updateNotesOf(body),
        )
    }
}
