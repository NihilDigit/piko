package dev.piko.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import dev.piko.desktop.winrt.TaskbarProgress
import dev.piko.desktop.winrt.WindowChrome
import dev.piko.download.DownloadStatus
import dev.piko.shared.download.PikoDownloadCoordinator
import java.awt.Window

/**
 * 标题栏跟随应用主题。系统只按自己的深浅色画 Win32 标题栏，应用选了深色而系统是浅色时，
 * 深色界面顶着一条白标题栏。
 *
 * 用 DisposableEffect 而不是 LaunchedEffect：前者在组合提交时同步执行，赶在窗口首次显示之前，
 * 后者要等下一帧，窗口会先闪一下浅色标题栏。
 */
@Composable
fun TitleBarThemeEffect(window: Window, dark: Boolean) {
    DisposableEffect(window, dark) {
        WindowChrome.setDarkTitleBar(window, dark)
        onDispose {}
    }
}

/** 任务栏按钮上显示正在进行的下载的总进度。暂停与失败的任务不计入，没有进行中的任务时清除。 */
@Composable
fun TaskbarDownloadProgress(window: Window, downloads: PikoDownloadCoordinator) {
    LaunchedEffect(window, downloads) {
        downloads.tasks.collect { tasks ->
            val active = tasks.values.filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING }
            val total = active.sumOf { it.totalBytes }
            when {
                active.isEmpty() -> TaskbarProgress.clear(window)
                // 总大小未知，给不出百分比
                total <= 0 -> TaskbarProgress.setState(window, TaskbarProgress.State.INDETERMINATE)
                else -> {
                    TaskbarProgress.setState(window, TaskbarProgress.State.NORMAL)
                    TaskbarProgress.setProgress(window, active.sumOf { it.downloadedBytes }, total)
                }
            }
        }
    }
}
