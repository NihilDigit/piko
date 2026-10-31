package dev.piko.shared.update

import dev.piko.data.auth.AppLanguage
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseNotesTest {

    private val localized = linkedMapOf(
        "zh-Hans" to "更新概述\n\n## 修复\n\n- 修复问题。",
        "zh-Hant" to "更新概述\n\n## 修復\n\n- 修復問題。",
        "en" to "Summary\n\n## Fixes\n\n- Fix a problem.",
        "ja" to "更新内容\n\n## 変更\n\n- 問題を修正。",
        "ko" to "업데이트\n\n## 변경\n\n- 문제 수정.",
    )
    private val markdown get() = "<!-- Editorial comment -->\n\n" + localized.entries.joinToString("\n\n") { (tag, notes) -> "# $tag\n\n$notes" }

    @Test
    fun localizedSectionsPreserveMarkdownAndResolveFallbacks() {
        val parsed = parseLocalizedChangelog(markdown)
        val languages = listOf(AppLanguage.ZH_CN, AppLanguage.ZH_HANT, AppLanguage.EN, AppLanguage.JA, AppLanguage.KO)
        val release = LatestRelease("2.0.0", "Legacy notes", "", emptyList(), parsed)
        assertEquals(5, parsed.size)
        languages.zip(localized.values).forEach { (language, notes) ->
            assertEquals(notes, release.notesFor(language))
            assertEquals(3, releaseNoteBlocks(notes).size)
            assertTrue(releaseNoteBlocks(notes)[1] is ReleaseNoteBlock.Heading)
            assertTrue(releaseNoteBlocks(notes)[2] is ReleaseNoteBlock.Bullet)
        }
        assertEquals(localized.getValue("zh-Hans"), release.copy(localizedNotes = parsed - AppLanguage.JA).notesFor(AppLanguage.JA))
        assertEquals("Legacy notes", release.copy(localizedNotes = emptyMap()).notesFor(AppLanguage.JA))
        assertEquals("Legacy notes", release.copy(localizedNotes = mapOf(AppLanguage.JA to " ")).notesFor(AppLanguage.JA))
    }

    @Test
    fun aliasesAndDownloadSafetyApplyToBothFormats() {
        val aliases = mapOf("zh-CN" to AppLanguage.ZH_CN, "zh" to AppLanguage.ZH_CN,
            "zh-TW" to AppLanguage.ZH_HANT, "zh-HK" to AppLanguage.ZH_HANT,
            "en-US" to AppLanguage.EN, "ja-JP" to AppLanguage.JA, "ko-KR" to AppLanguage.KO)
        for ((tag, language) in aliases) {
            val expected = mapOf(language to "## Changes\n- Note")
            val notes = "\n## Changes\n- Note\n## 下载\nDo not show"
            assertEquals(expected, parseLocalizedChangelog("# $tag\n$notes"))
            assertEquals(expected, parseLocalizedNotesMap(mapOf(tag to notes)))
        }
        assertEquals(emptyMap(), parseLocalizedNotesMap(mapOf("fr" to "Unknown", "en" to " ")))
    }

    @Test
    fun rendererIncludesAllLanguages() = testRenderer("2.0.0", markdown) { text, bundle ->
        assertEquals(localized.getValue("zh-Hans") + "\n\n## 下载\nDownload 2.0.0\n", text)
        assertEquals(localized.getValue("zh-Hans"), bundle.getValue("body").jsonPrimitive.content)
        assertEquals(localized, bundle.getValue("localized_notes").jsonObject.mapValues { it.value.jsonPrimitive.content })
    }

    @Test
    fun rendererMissingChangelogNeverFailsRelease() = testRenderer("9.9.9", null) { text, bundle ->
        assertEquals("## 下载\nDownload 9.9.9\n", text)
        assertEquals("", bundle.getValue("body").jsonPrimitive.content)
        assertTrue(bundle.getValue("localized_notes").jsonObject.isEmpty())
    }

    @Test
    fun strictRendererAcceptsAllFiveLanguages() = testRenderer("2.0.0", markdown, requireAll = true) { _, bundle ->
        assertEquals(5, bundle.getValue("localized_notes").jsonObject.size)
    }

    @Test
    fun strictRendererRejectsMissingFile() = testRenderer("9.9.9", null, requireAll = true, expectedExit = 1) { _, _ -> }

    @Test
    fun strictRendererRejectsMissingLanguage() = testRenderer("2.0.0", markdown.substringBefore("# ko"), requireAll = true, expectedExit = 1) { _, _ -> }

    private fun testRenderer(
        version: String,
        changelog: String?,
        requireAll: Boolean = false,
        expectedExit: Int = 0,
        verify: (String, kotlinx.serialization.json.JsonObject) -> Unit,
    ) {
        val script = File("../.github/scripts/render-changelog.py").canonicalFile
        val python = listOf("python3", "python").firstOrNull { command ->
            runCatching { ProcessBuilder(command, "--version").redirectErrorStream(true).start().let { process ->
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor() == 0 && output.startsWith("Python 3.")
            } }.getOrDefault(false)
        } ?: error("Python 3 is required to test the release renderer")
        val dir = Files.createTempDirectory("piko-changelog-test").toFile()
        try {
            File(dir, "template.md").writeText("## 下载\nDownload {{version}}\n")
            if (changelog != null) File(dir, ".github/changelogs/$version.md").apply { parentFile.mkdirs(); writeText(changelog) }
            val command = listOf(python, script.path, "--version", version,
                "--template", "template.md", "--out-release-notes", "release-notes.md",
                "--out-notes-json", "notes.json") + if (requireAll) listOf("--require-all") else emptyList()
            val process = ProcessBuilder(command).directory(dir).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(expectedExit, process.waitFor(), output)
            if (changelog == null) assertTrue(output.contains("::warning::Missing changelog"), output)
            if (expectedExit != 0) {
                assertTrue(output.contains("::error::") && output.contains("ko"), output)
            } else {
                verify(File(dir, "release-notes.md").readText(), Json.parseToJsonElement(File(dir, "notes.json").readText()).jsonObject)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun updateDialogStopsBeforeTheTemplatesDownloadSection() {
        // 读仓库里真实的模板：标题在模板与代码两处各写一份，改了一边另一边不会报错，
        // 弹窗只会默默显示整张下载表
        val template = File("../.github/release-notes.md").readText()
        val body = "- 修复了某个问题。\n- 新增了某项功能。\n\n" + template

        assertEquals("- 修复了某个问题。\n- 新增了某项功能。", updateNotesOf(body))
    }

    @Test
    fun handwrittenChangelogSplitsIntoHeadingsBulletsAndParagraphs() {
        // v0.11.0 正文的原样开头，按 CLAUDE.md 的更新日志格式手写，GitHub 返回的是 CRLF
        val body = """
            |新增本机文件上传；界面改用新版导航与加载样式；Windows 版适配鼠标操作。
            |
            |## 修复
            |
            |- 启动时若上次所在的文件夹已被删除，显示为空文件夹。
            |
            |## 变化
            |
            |- 可上传本机文件与文件夹，
            |  中断后可继续。
            |- Supports **split** archives
            |  other than `.7z`.
            |
            |## 下载
            |
            || 设备 | 下载 |
        """.trimMargin().replace("\n", "\r\n")

        assertEquals(
            listOf(
                ReleaseNoteBlock.Paragraph("新增本机文件上传；界面改用新版导航与加载样式；Windows 版适配鼠标操作。"),
                ReleaseNoteBlock.Heading("修复"),
                ReleaseNoteBlock.Bullet("启动时若上次所在的文件夹已被删除，显示为空文件夹。"),
                ReleaseNoteBlock.Heading("变化"),
                // 折行接回：中文直接相连，西文补空格
                ReleaseNoteBlock.Bullet("可上传本机文件与文件夹，中断后可继续。"),
                ReleaseNoteBlock.Bullet("Supports **split** archives other than `.7z`."),
            ),
            releaseNoteBlocks(updateNotesOf(body)),
        )
    }
}
