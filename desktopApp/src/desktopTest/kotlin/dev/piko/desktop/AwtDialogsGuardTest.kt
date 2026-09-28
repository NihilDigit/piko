package dev.piko.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 防 issue #7 再犯：AWT 与 Swing 的模态文件框只准经 AwtDialogs 弹，那里换到了专用线程上。
 * 在别处直接用，迟早有一处在界面线程上同步弹框，重入 Compose 的调度器，把窗口整个崩掉，而且只在
 * 某些时机（有画面在刷新时）复现，手测很难碰上。
 *
 * 查的是 import 而不是出现的字样：要用这两个类就得 import，注释里提到它们的名字不算。
 * 只读源码，不碰窗口，任何系统上都能跑。
 */
class AwtDialogsGuardTest {
    @Test
    fun `modal file dialogs only live in AwtDialogs`() {
        val sources = File("src/desktopMain/kotlin")
        assertTrue(sources.isDirectory, "找不到源码目录：${sources.absolutePath}")
        val offenders = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "AwtDialogs.kt" }
            .filter { file -> file.useLines { lines -> lines.any { line -> FORBIDDEN_IMPORTS.any { line.trim() == "import $it" } } } }
            .map { it.relativeTo(sources).path }
            .toList()
        assertTrue(offenders.isEmpty(), "这些文件直接用了模态文件框，改经 AwtDialogs 弹：$offenders")
    }

    private companion object {
        val FORBIDDEN_IMPORTS = listOf("java.awt.FileDialog", "javax.swing.JFileChooser")
    }
}
