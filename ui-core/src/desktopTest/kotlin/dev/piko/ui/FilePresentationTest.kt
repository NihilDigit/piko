package dev.piko.ui

import dev.piko.ui.components.*
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.ResolvedFile
import kotlin.test.*

class FilePresentationTest {
    @Test
    fun sourceIdentityAndOriginalNamesSurviveSharedVisualClassification() {
        val first = ResolvedFile("字幕/同名.简体中文.ass", 123, null).filePresentation("draft-a:0")
        val second = ResolvedFile("另一个目录/同名.简体中文.ass", 123, "A".repeat(40)).filePresentation("draft-a:1")
        assertEquals(first.name, second.name)
        assertNotEquals(first.key, second.key)
        assertEquals(first.visual.typeIcon(), second.visual.typeIcon())
        assertEquals("未收录", first.status?.text)
        assertNull(second.status)
        assertEquals("同名.简体中文.ass", first.name.fullName)
    }

    @Test
    fun cloudAndResolvedFilesUseTheSameVisualAndExtensionPolicy() {
        val cloud = FileStat(id = "real-id", name = "作品.mkv", kind = FileKind.FILE).fileVisual()
        val source = ResolvedFile("作品.mkv", 100, "A".repeat(40)).filePresentation("draft-b:0").visual
        assertEquals(cloud.typeIcon(), source.typeIcon())
        assertEquals(cloud.extensionLabel(), source.extensionLabel())
        assertEquals(cloud.watermarkIcon(), source.watermarkIcon())
        val mimeOnly = FileStat(id = "mime-only", name = "blob", kind = FileKind.FILE, mimeType = "video/mp4").fileVisual()
        assertEquals(cloud.typeIcon(), mimeOnly.typeIcon())
    }
}
