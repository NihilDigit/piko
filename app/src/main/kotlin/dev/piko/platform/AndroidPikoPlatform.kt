package dev.piko.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import dev.piko.BuildConfig
import dev.piko.shared.media.player.PlaybackBackend
import dev.piko.ui.platform.DownloadLocationPicker
import dev.piko.ui.platform.LocalFileActions
import dev.piko.ui.platform.PikoPlatform
import dev.piko.ui.platform.PreviewBackend
import dev.piko.ui.platform.UploadPicker
import dev.piko.ui.platform.VideoPreviewSupport
import dev.piko.ui.screens.player.MpvPlaybackBackend
import dev.piko.ui.screens.player.MpvVideoSurface
import dev.piko.update.AppUpdateService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 共享界面在 Android 上的平台能力。动作都从 Application 的 Context 发出，所以启动 Activity
 * 一律带 FLAG_ACTIVITY_NEW_TASK；原先在屏幕里用 Activity 的 Context，界面搬进共享模块后
 * 拿不到它。
 */
class AndroidPikoPlatform(
    private val context: Context,
    updater: () -> AppUpdateService,
) : PikoPlatform {
    override val appVersion: String = BuildConfig.VERSION_NAME

    // 开屏检查时才建，不挡 Application.onCreate
    private val lazyUpdater by lazy(updater)
    override val updater: AppUpdateService get() = lazyUpdater

    override val supportsDynamicColor: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * 按 context 与深浅记住：dynamic*ColorScheme 每次调用都新建对象，而 ColorScheme 不覆写
     * equals，不记住的话每次重组都像是换了一套配色，过渡动画反复重启。
     */
    @Composable
    override fun dynamicColorScheme(dark: Boolean): ColorScheme {
        val context = LocalContext.current
        return remember(context, dark) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
    }

    override val supportsBlur: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    override fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun isImeVisible(): Boolean = WindowInsets.isImeVisible

    private val clipboard get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    override fun readClipboardText(): String? = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

    override fun copyToClipboard(label: String, text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    override val localFiles: LocalFileActions = AndroidLocalFiles()

    override val downloadLocation: DownloadLocationPicker = AndroidDownloadLocation()

    override val uploadPicker: UploadPicker = AndroidUploadPicker()

    override val videoPreview: VideoPreviewSupport = MpvPreviewSupport()

    @Composable
    override fun FullscreenDialog(
        onDismiss: () -> Unit,
        immersive: Boolean,
        systemBarsVisible: Boolean,
        content: @Composable () -> Unit,
    ) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            if (immersive) ImmersiveSystemBars(visible = systemBarsVisible)
            content()
        }
    }

    @Composable
    override fun ListScrollbar(state: LazyListState, modifier: Modifier) = Unit

    @Composable
    override fun ListScrollbar(state: LazyStaggeredGridState, modifier: Modifier) = Unit

    override val deviceSummary: String =
        "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}），${Build.MANUFACTURER} ${Build.MODEL}，${Build.SUPPORTED_ABIS.firstOrNull()}"

    override suspend fun exportLog(fileName: String, content: String): Boolean = runCatching {
        // 放在缓存目录的 logs 下，由 file_paths.xml 单独授权给 FileProvider；下次导出时覆盖
        val file = withContext(Dispatchers.IO) {
            File(context.cacheDir, "logs").apply { mkdirs() }.resolve(fileName).apply { writeText(content) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            // 选择器经 ClipData 把读权限转给最终选中的应用
            clipData = ClipData.newRawUri(fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "导出日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    private fun startActivity(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private inner class AndroidLocalFiles : LocalFileActions {
        override fun thumbnailModel(path: String): Any? = when {
            path.startsWith("content:") -> path
            File(path).exists() -> File(path)
            else -> null
        }

        override fun exists(path: String): Boolean = File(path).exists()

        override fun openExternally(path: String, isMedia: Boolean) {
            val uri = downloadUri(path) ?: return
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mimeType(isMedia))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }

        override val canShare: Boolean = true

        override fun share(path: String, isMedia: Boolean) {
            val uri = downloadUri(path) ?: return
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mimeType(isMedia)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "分享文件"))
        }

        override fun openContainingFolder(path: String) {
            val uri = if (path.startsWith("content:")) {
                val fileUri = Uri.parse(path)
                val authority = fileUri.authority ?: return
                val documentId = runCatching { DocumentsContract.getDocumentId(fileUri) }.getOrNull() ?: return
                val parentId = documentId.substringBeforeLast('/', documentId)
                DocumentsContract.buildTreeDocumentUri(authority, parentId)
            } else {
                val file = File(path)
                if (!file.exists()) return
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file.parentFile ?: file)
            }
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "vnd.android.document/directory")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
            )
        }

        private fun mimeType(isMedia: Boolean) = if (isMedia) "video/*" else "*/*"

        private fun downloadUri(path: String): Uri? {
            if (path.startsWith("content:")) return Uri.parse(path)
            val file = File(path)
            if (!file.exists()) return null
            return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
    }

    /** 下载位置是 SAF 目录树，选中后持久化授权，偏好里存 content: URI。 */
    private inner class AndroidDownloadLocation : DownloadLocationPicker {
        override val description = "默认保存到应用私有目录。选择公共目录后，文件保存到你授权的文件夹。"

        override fun displayName(storedPath: String): String {
            if (storedPath.startsWith("content:")) {
                return DocumentFile.fromTreeUri(context, Uri.parse(storedPath))?.name ?: "已选择的文件夹"
            }
            return storedPath.ifBlank {
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.absolutePath
                    ?: context.filesDir.resolve("Piko").absolutePath
            }
        }

        @Composable
        override fun rememberLauncher(onPicked: (String) -> Unit): () -> Unit {
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val selected = result.data?.data ?: return@rememberLauncherForActivityResult
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching { context.contentResolver.takePersistableUriPermission(selected, flags) }
                onPicked(selected.toString())
            }
            return {
                val initialUri = DocumentsContract.buildTreeDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:Download/Piko",
                )
                launcher.launch(
                    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
                        addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                        )
                    },
                )
            }
        }
    }

    /** 选中后持久化读授权：任务表里存的是 URI，进程被杀后续传还要凭它读文件。 */
    private inner class AndroidUploadPicker : UploadPicker {
        @Composable
        override fun rememberFilesLauncher(onPicked: (List<String>) -> Unit): () -> Unit {
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                if (uris.isEmpty()) return@rememberLauncherForActivityResult
                uris.forEach(::persistReadPermission)
                onPicked(uris.map(Uri::toString))
            }
            return { launcher.launch(arrayOf("*/*")) }
        }

        @Composable
        override fun rememberFolderLauncher(onPicked: (String) -> Unit): () -> Unit {
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                persistReadPermission(uri)
                onPicked(uri.toString())
            }
            return { launcher.launch(null) }
        }

        private fun persistReadPermission(uri: Uri) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    /** 片段预览用段落模式的 libmpv：不出声、缓存小；画面走 TextureView，能随面板圆角裁切。 */
    private inner class MpvPreviewSupport : VideoPreviewSupport {
        @Composable
        override fun rememberPreviewBackend(): PreviewBackend =
            remember { MpvPreviewBackend(MpvPlaybackBackend(context, preview = true)) }

        @Composable
        override fun Surface(backend: PreviewBackend, modifier: Modifier) {
            MpvVideoSurface((backend as MpvPreviewBackend).mpv, modifier, useTextureView = true)
        }
    }

    private class MpvPreviewBackend(val mpv: MpvPlaybackBackend) : PreviewBackend, PlaybackBackend by mpv {
        override fun release() = mpv.release()
    }
}

/**
 * 界面隐藏时一并收起系统栏，看图不被状态栏与导航条压住；从边缘滑动可临时唤出。
 * Dialog 有自己的窗口，要从 DialogWindowProvider 取，改 Activity 的窗口不起作用。
 * 状态栏图标固定为浅色：背景总是黑的，跟随应用浅色主题会是黑字压黑底。
 */
@Composable
private fun ImmersiveSystemBars(visible: Boolean) {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    LaunchedEffect(window, visible) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
