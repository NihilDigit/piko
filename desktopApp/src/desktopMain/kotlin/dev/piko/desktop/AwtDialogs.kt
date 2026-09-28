package dev.piko.desktop

import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Window
import java.io.File
import java.util.concurrent.Executors
import javax.swing.JFileChooser
import javax.swing.UIManager
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * 应用里所有 AWT 与 Swing 的模态对话框（FileDialog、JFileChooser）的唯一入口。对外只有挂起函数，
 * 里面一律换到 [dispatcher] 那条线程上弹，调用方写不出在界面线程上同步弹框的代码。
 * AwtDialogsGuardTest 扫源码，这个文件以外出现 FileDialog 或 JFileChooser 就不过。
 *
 * 为什么不能在界面线程上弹（issue #7）：模态框在调用线程上一直等到关闭。在事件线程上，它就地另起一层
 * 事件循环（WaitDispatchSupport 的「start a new event pump」）；在别的线程上只是等锁。界面协程跑在 Compose 的
 * FlushCoroutineDispatcher 上，就地弹框时，嵌套的那层事件循环里又渲染一帧、又 flush 一次调度器，而它不可重入：
 * 外层正在执行的续体被取出来再跑一遍，同一个协程恢复两次，窗口整个崩掉。macOS 上一边放视频一边改下载位置就能复现。
 * 放到这条线程上后，界面线程只是挂起等结果，照常绘制。
 *
 * Windows 的原生对话框（FolderPicker、SaveFilePicker）走 COM，本来就在自己的 STA 线程上，不经这里；
 * 这里管 macOS 与原生框弹不出来时的退路。属主窗口要在点击的当下取（焦点在 launch 之后可能已经变了），再传进来。
 */
internal object AwtDialogs {
    // 单线程：同一时刻只该有一个模态框
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Piko-AwtDialog").also { it.isDaemon = true }
    }.asCoroutineDispatcher()

    /**
     * 选文件，取消时为空。[pattern] 是 Windows 上的文件名通配（如 `*.ass;*.srt`），那里 filenameFilter 不生效；
     * [filter] 在 macOS 上生效。两者都给才能两边都筛。
     */
    suspend fun chooseFiles(
        owner: Window?,
        title: String,
        multiple: Boolean = true,
        pattern: String? = null,
        filter: ((String) -> Boolean)? = null,
    ): List<File> = withContext(dispatcher) {
        val dialog = fileDialog(owner, title, FileDialog.LOAD)
        dialog.isMultipleMode = multiple
        pattern?.let { dialog.file = it }
        filter?.let { accept -> dialog.setFilenameFilter { _, name -> accept(name) } }
        try {
            dialog.isVisible = true
            dialog.files.toList()
        } finally {
            dialog.dispose()
        }
    }

    /** 保存框，取消时为 null。 */
    suspend fun chooseSaveFile(owner: Window?, title: String, directory: File, fileName: String): File? = withContext(dispatcher) {
        val dialog = fileDialog(owner, title, FileDialog.SAVE)
        dialog.directory = directory.absolutePath
        dialog.file = fileName
        try {
            dialog.isVisible = true
            dialog.file?.let { File(dialog.directory, it) }
        } finally {
            dialog.dispose()
        }
    }

    /**
     * 选目录，取消时为 null。macOS 上 FileDialog 就是原生面板，一个系统属性让它改选目录；
     * 属性在弹出时读取，用完放回，免得之后的选文件框也只能选目录。
     * 别处（Windows 原生框弹不出来时）AWT 的 FileDialog 选不了目录，只能用 Swing 的，换成系统外观，免得弹出 Metal 风格的窗口。
     */
    suspend fun chooseDirectory(owner: Window?, initial: File?, title: String): File? = withContext(dispatcher) {
        if (isMacOs) {
            System.setProperty(MAC_DIRECTORY_DIALOG_PROPERTY, "true")
            val dialog = fileDialog(owner, title, FileDialog.LOAD)
            initial?.let { dialog.directory = it.absolutePath }
            try {
                dialog.isVisible = true
                dialog.file?.let { File(dialog.directory, it) }
            } finally {
                dialog.dispose()
                System.setProperty(MAC_DIRECTORY_DIALOG_PROPERTY, "false")
            }
        } else {
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
            val chooser = JFileChooser(initial).apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                dialogTitle = title
            }
            if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        }
    }

    // FileDialog 的构造要区分属主是 Frame 还是 Dialog
    private fun fileDialog(owner: Window?, title: String, mode: Int): FileDialog = when (owner) {
        is Dialog -> FileDialog(owner, title, mode)
        else -> FileDialog(owner as? Frame, title, mode)
    }

    private const val MAC_DIRECTORY_DIALOG_PROPERTY = "apple.awt.fileDialogForDirectories"
}
