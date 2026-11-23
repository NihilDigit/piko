@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.InternalComposeUiApi::class,
)

package dev.piko.shots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.piko.data.auth.AppLanguage
import dev.piko.desktop.BundledMpvRuntime
import dev.piko.desktop.DesktopPikoDownloadStorage
import dev.piko.desktop.DesktopPikoPlatform
import dev.piko.desktop.DesktopPikoPreferences
import dev.piko.desktop.DesktopPikoSegmentDownloader
import dev.piko.desktop.DesktopPikoUploadSources
import dev.piko.desktop.DesktopSettingsStore
import dev.piko.desktop.desktopSessionStore
import dev.piko.desktop.installImageLoader
import dev.piko.shared.auth.PlainFileVault
import dev.piko.shared.data.DriveAppearancePrefs
import dev.piko.shared.data.FilePikoCacheStore
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.i18n.PikoLocale
import dev.piko.shared.media.FileClipCache
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.media.player.PlayerLayoutPrefs
import dev.piko.shared.net.PikoProxySelector
import dev.piko.shared.net.ProxySetting
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.PikoApp
import dev.piko.ui.PikoServices
import dev.piko.ui.VideoPlayerHost
import dev.piko.ui.components.SegmentDownloadSheet
import dev.piko.ui.components.SegmentSession
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.LocalWindowCaption
import dev.piko.ui.platform.PikoPlatform
import dev.piko.ui.screens.rename.BatchRenameDialog
import dev.piko.ui.screens.settings.DriveAppearanceCustomizationPage
import dev.piko.ui.screens.settings.PlayerCustomizationPage
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.PikoTheme
import dev.piko.ui.theme.ThemeMode
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ClipMode
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import org.jetbrains.skia.TextLine

private const val STEINS_GATE_MAGNET =
    "magnet:?xt=urn:btih:b9b5d657f154934a901c097f17f906e4de1da11d&dn=%5BDBD-Raws%5D%5B%E5%91%BD%E8%BF%90%E7%9F%B3%E4%B9%8B%E9%97%A8%2FSteins%3BGate%5D%5B01-24TV%E5%85%A8%E9%9B%86%2BSP%2B%E5%89%A7%E5%9C%BA%E7%89%88%2B%E7%89%B9%E5%85%B8%E6%98%A0%E5%83%8F%5D%5B1080P%5D%5BBDRip%5D%5BHEVC-10bit%5D%5B%E7%AE%80%E7%B9%81%E6%97%A5%E5%8F%8C%E8%AF%AD%E5%A4%96%E6%8C%82%5D%5BFLAC%5D%5BMKV%5D%28%E3%82%B7%E3%83%A5%E3%82%BF%E3%82%A4%E3%83%B3%E3%82%B9%E3%82%99%E3%83%BB%E3%82%B1%E3%82%99%E3%83%BC%E3%83%88%29&tr=http%3A%2F%2Fnyaa.tracker.wf%3A7777%2Fannounce&tr=udp%3A%2F%2Fopen.stealth.si%3A80%2Fannounce&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce&tr=udp%3A%2F%2Fexodus.desync.com%3A6969%2Fannounce&tr=udp%3A%2F%2Ftracker.torrent.eu.org%3A451%2Fannounce"

/**
 * README 全真实数据截图与动态 AVIF 帧序列生成器（只读隔离克隆 `~/.piko`）：
 * - `hero`: 桌面 + 手机并排（`Dramas` 海报墙自适应工作区）
 * - `feed`: 左瀑布流平滑滚动 + 右信息流真实 `libmpv` 播放切片段（同屏动态 AVIF）
 * - `links`: `Dramas` + Steins;Gate 全集磁力链真实树形解析预览
 * - `customize`: 文件外观自定义 + 播放器外观自定义双屏并排
 * - `tools`: 无损视频片段截取 + 批量重命名 + 跨目录查重三联展示
 */
internal fun renderReadmeShots(args: List<String>, outDir: File) {
    System.setProperty("piko.release-build", "true")
    System.setProperty("jpackage.app-version", "1.6.0")
    System.setProperty("java.net.useSystemProxies", "true")
    PikoProxySelector.install(ProxySetting())
    installImageLoader()
    BundledMpvRuntime.prepareInBackground()
    BundledMpvRuntime.ensure()

    val only = argOption(args, "--only")?.split(',')?.map { it.trim().lowercase() }?.toSet()
    val langFilter = argOption(args, "--lang")?.lowercase() ?: "all"
    val themeFilter = argOption(args, "--theme")?.lowercase() ?: "all"

    val languages = buildList {
        when (langFilter) {
            "all" -> addAll(listOf(AppLanguage.ZH_CN, AppLanguage.ZH_HANT, AppLanguage.EN, AppLanguage.JA, AppLanguage.KO))
            "zh", "zh-cn" -> add(AppLanguage.ZH_CN)
            "zh-hant", "zh-tw" -> add(AppLanguage.ZH_HANT)
            "en", "en-us" -> add(AppLanguage.EN)
            "ja", "ja-jp" -> add(AppLanguage.JA)
            "ko", "ko-kr" -> add(AppLanguage.KO)
        }
    }
    val themes = buildList {
        if (themeFilter == "all" || themeFilter == "light") add(ThemeMode.LIGHT)
        if (themeFilter == "all" || themeFilter == "dark") add(ThemeMode.DARK)
    }
    fun wanted(name: String) = only == null || name in only

    val rootOut = File(outDir, "readme").apply { mkdirs() }
    RealReadmeEnv(File(outDir, "readme-real-home")).use { real ->
        for (lang in languages) {
            for (mode in themes) {
                if (wanted("hero")) captureRealHero(real, rootOut, lang, mode)
                if (wanted("customize")) captureRealCustomize(real, rootOut, lang, mode)
                if (wanted("tools")) captureRealTools(real, rootOut, lang, mode)
                if (wanted("links")) captureRealLinks(real, rootOut, lang, mode)
                if (wanted("feed")) captureRealGalleryAndFeed(real, rootOut, lang, mode)
            }
        }
    }
}

private fun langSuffix(lang: AppLanguage): String = when (lang) {
    AppLanguage.ZH_CN, AppLanguage.SYSTEM -> ""
    AppLanguage.ZH_HANT -> "-zh-Hant"
    AppLanguage.EN -> "-en"
    AppLanguage.JA -> "-ja"
    AppLanguage.KO -> "-ko"
}
private fun themeSuffix(mode: ThemeMode): String = if (mode == ThemeMode.DARK) "-dark" else ""

private fun applyLanguage(lang: AppLanguage) {
    Locale.setDefault(Locale.forLanguageTag(PikoLocale.resolve(lang).tag))
    PikoLocale.apply(lang)
}

private fun argOption(args: List<String>, name: String): String? =
    args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

/** 只读隔离克隆 `~/.piko` 的登录态与本地目录索引，绝不触碰原目录。 */
private class RealReadmeEnv(val workDir: File) : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings: DesktopSettingsStore
    val preferences: DesktopPikoPreferences
    val platform: PikoPlatform
    val services: PikoServices

    init {
        workDir.deleteRecursively()
        workDir.mkdirs()
        val realHome = File(System.getProperty("user.home"), ".piko")
        require(realHome.isDirectory) { "未找到正式版数据目录：${realHome.absolutePath}" }
        File(realHome, "accounts.json").takeIf { it.isFile }?.copyTo(File(workDir, "accounts.json"), overwrite = true)
        File(realHome, "accounts").takeIf { it.isDirectory }?.copyRecursively(File(workDir, "accounts"), overwrite = true)
        File(realHome, "secrets").takeIf { it.isDirectory }?.copyRecursively(File(workDir, "secrets"), overwrite = true)
        val srcCache = File(realHome, "cache")
        val dstCache = File(workDir, "cache").apply { mkdirs() }
        srcCache.listFiles()?.forEach { child ->
            if (child.name != "images" && child.name != "clip-cache" && child.name != "natives") {
                child.copyRecursively(File(dstCache, child.name), overwrite = true)
            }
        }
        val downloads = File(workDir, "Downloads").apply { mkdirs() }
        settings = DesktopSettingsStore(File(workDir, "settings.properties")).apply {
            setDownloadDirectory(downloads.path)
            set("ui.driveViewMode", "POSTER")
        }
        preferences = DesktopPikoPreferences(settings) { PlainFileVault(workDir.toPath().resolve("secrets-prefs")) }
        platform = DesktopPikoPlatform(settings)
        val clientManager = PikoClientManager(
            sessionStore = desktopSessionStore(workDir.toPath()),
            scope = scope,
        )
        val clipCache = FileClipCache(File(workDir, "clip-cache"))
        val media = PikoMediaRepository(clientManager, preferences, clipCache = clipCache)
        val cacheStore = FilePikoCacheStore(File(workDir, "cache").path)
        services = PikoServices(
            platformPreferences = preferences,
            clientManager = clientManager,
            mediaRepository = media,
            downloadManager = PikoDownloadCoordinator(
                clientManager,
                preferences,
                DesktopPikoDownloadStorage(File(workDir, "download-cache")) { downloads },
                scope,
                segmentDownloader = DesktopPikoSegmentDownloader(),
                mediaRepository = media,
                cacheStore = cacheStore,
            ),
            uploadSources = DesktopPikoUploadSources(),
            cacheStore = cacheStore,
            treeDatabaseFactory = dev.piko.shared.data.DesktopPikoTreeDatabaseFactory(),
        )
    }

    fun configure(lang: AppLanguage, viewMode: String = "POSTER") {
        applyLanguage(lang)
        runBlocking { preferences.setAppLanguage(lang) }
        settings.set("ui.driveViewMode", viewMode)
    }

    override fun close() {
        scope.cancel()
        workDir.deleteRecursively()
    }
}

private class ReadmeScene(
    val services: PikoServices,
    val platform: PikoPlatform,
    val width: Int,
    val height: Int,
    val mode: ThemeMode,
    val mobile: Boolean,
    val caption: Boolean,
    val motion: Boolean,
) : AutoCloseable {
    private val start = System.nanoTime()
    private val viewport = mutableStateOf(IntSize(width, height))
    private val scene: ImageComposeScene
    private var lastFrame: Image

    init {
        val effectivePlatform = if (mobile) MobileShotPlatform(platform) else platform
        val host = if (mobile) {
            VideoPlayerHost.InApp { _, _ -> }
        } else {
            val feedOpen = mutableStateOf(false)
            VideoPlayerHost.Detached(
                open = {},
                openClipFeed = { feedOpen.value = true },
                isClipFeedOpen = { feedOpen.value },
                closeClipFeed = { feedOpen.value = false },
            )
        }
        val context = Dispatchers.Main + object : MotionDurationScale {
            override val scaleFactor: Float = if (motion) 1f else 0f
        }
        scene = edt {
            ImageComposeScene(width, height, Density(1f), coroutineContext = context) {
                val baseWindow = androidx.compose.ui.platform.LocalWindowInfo.current
                val win = androidx.compose.runtime.remember(baseWindow) {
                    object : androidx.compose.ui.platform.WindowInfo by baseWindow {
                        override val containerSize get() = viewport.value
                        override val containerDpSize get() = androidx.compose.ui.unit.DpSize(viewport.value.width.dp, viewport.value.height.dp)
                    }
                }
                CompositionLocalProvider(
                    LocalCursorBlinkEnabled provides false,
                    androidx.compose.ui.platform.LocalWindowInfo provides win,
                    LocalWindowCaption provides if (caption && !mobile) ShotWindowCaption else null,
                ) {
                    PikoApp(services, effectivePlatform, Appearance(mode = mode), host)
                }
            }
        }
        lastFrame = edt { scene.render(0L) }
    }

    fun stepFrame(): Image {
        val next = edt { scene.render(System.nanoTime() - start) }
        lastFrame.close()
        lastFrame = next
        return next
    }

    fun pumpMs(ms: Long, intervalMs: Long = 33L) {
        val deadline = System.nanoTime() + ms * 1_000_000L
        while (System.nanoTime() < deadline) {
            stepFrame()
            Thread.sleep(intervalMs)
        }
    }

    fun waitForText(text: String, maxMs: Long = 18_000L): Boolean {
        val deadline = System.nanoTime() + maxMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            stepFrame()
            if (findNode(text) != null) return true
            Thread.sleep(80)
        }
        return false
    }

    fun findNode(
        text: String,
        topmost: Boolean = false,
        preferLowest: Boolean = false,
        maxX: Float? = null,
        minX: Float? = null,
        minY: Float? = null,
    ): SemanticsNode? = edt {
        val owners = if (topmost) listOfNotNull(scene.semanticsOwners.lastOrNull()) else scene.semanticsOwners
        val all = owners.flatMap { flatten(it.unmergedRootSemanticsNode) }.filter { n ->
            val cx = (n.boundsInRoot.left + n.boundsInRoot.right) / 2f
            val cy = (n.boundsInRoot.top + n.boundsInRoot.bottom) / 2f
            (maxX == null || cx <= maxX) && (minX == null || cx >= minX) && (minY == null || cy >= minY)
        }
        val exact = all.filter { n -> nodeLabels(n).any { it.equals(text, ignoreCase = true) } }
        val matches = exact.ifEmpty { all.filter { n -> nodeLabels(n).any { it.contains(text, ignoreCase = true) } } }
        if (preferLowest) matches.maxByOrNull { it.boundsInRoot.top } else matches.firstOrNull()
    }

    fun clickText(
        text: String,
        doubleClick: Boolean = false,
        rightClick: Boolean = false,
        topmost: Boolean = false,
        preferLowest: Boolean = false,
        maxX: Float? = null,
        minX: Float? = null,
        minY: Float? = null,
    ): Boolean {
        val node = findNode(text, topmost = topmost, preferLowest = preferLowest, maxX = maxX, minX = minX, minY = minY) ?: return false
        val rect = edt { node.boundsInRoot }
        val center = Offset((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f)
        clickAt(center, doubleClick = doubleClick, rightClick = rightClick)
        return true
    }

    fun longPressText(text: String, preferLowest: Boolean = false): Boolean {
        val node = findNode(text, preferLowest = preferLowest) ?: return false
        val rect = edt { node.boundsInRoot }
        val center = Offset((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f)
        edt { scene.sendPointerEvent(PointerEventType.Press, center, type = PointerType.Touch) }
        pumpMs(850)
        edt { scene.sendPointerEvent(PointerEventType.Release, center, type = PointerType.Touch) }
        stepFrame()
        return true
    }

    fun clickAt(at: Offset, doubleClick: Boolean = false, rightClick: Boolean = false) {
        val pt = if (mobile && !rightClick) PointerType.Touch else PointerType.Mouse
        val btn = when {
            rightClick -> PointerButton.Secondary
            mobile -> null
            else -> PointerButton.Primary
        }
        repeat(if (doubleClick) 2 else 1) {
            edt {
                scene.sendPointerEvent(PointerEventType.Press, at, type = pt, button = btn)
                scene.sendPointerEvent(PointerEventType.Release, at, type = pt, button = btn)
            }
            stepFrame()
            if (doubleClick) Thread.sleep(60)
        }
    }

    fun scrollWheel(at: Offset, deltaY: Float, render: Boolean = true, clearHover: Boolean = false) {
        edt {
            scene.sendPointerEvent(
                eventType = PointerEventType.Scroll,
                position = at,
                scrollDelta = Offset(0f, deltaY),
                type = PointerType.Mouse,
            )
            if (clearHover) {
                scene.sendPointerEvent(
                    eventType = PointerEventType.Exit,
                    position = Offset(-10f, -10f),
                    type = PointerType.Mouse,
                )
            }
        }
        if (render) stepFrame()
    }

    fun pressKey(key: Key, ctrl: Boolean = false) {
        edt {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = ctrl))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isCtrlPressed = ctrl))
        }
        stepFrame()
    }

    fun snapshotCopy(): Image {
        val img = stepFrame()
        val surface = Surface.makeRasterN32Premul(width, height)
        surface.canvas.drawImage(img, 0f, 0f)
        val out = surface.makeImageSnapshot()
        surface.close()
        return out
    }

    fun dumpTexts(limit: Int = 50): List<String> = edt {
        scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
            .flatMap(::nodeLabels)
            .distinct()
            .take(limit)
    }

    override fun close() {
        edt {
            lastFrame.close()
            scene.close()
        }
    }
}

private fun flatten(node: SemanticsNode): List<SemanticsNode> =
    listOf(node) + node.children.flatMap(::flatten)

private fun nodeLabels(node: SemanticsNode): List<String> = buildList {
    node.config.getOrNull(SemanticsProperties.Text)?.forEach { add(it.text) }
    node.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { add(it) }
}

private fun navigateToDramas(app: ReadmeScene): Boolean {
    // 若当前停在设置/外观自定义等页面，先点侧边栏左上「文件」回到网盘主界面
    if (app.findNode("Dramas") == null && app.findNode("Yuru Camp") == null) {
        val filesLabel = listOf("文件", "Files", "ファイル", "파일", "檔案")
        filesLabel.firstOrNull { app.clickText(it) }
        app.pumpMs(800)
    }
    if (!app.waitForText("Dramas", 18_000)) {
        System.err.println("等待 Dramas 超时，当前文本：${app.dumpTexts()}")
        return false
    }
    app.pumpMs(800)
    // 已处于 Dramas 目录内（同时能看到 Yuru Camp 或 Steins;Gate 海报卡）则直接返回
    if (app.findNode("Yuru Camp", minY = 180f) != null || app.findNode("Steins;Gate", minY = 180f) != null) {
        return true
    }
    // 若当前停在更深子目录（如 Silicon Valley），先点「我的网盘」回到根目录
    if (app.findNode("Dramas", minY = 180f) == null) {
        val rootLabels = listOf("我的网盘", "My Drive", "My drive", "マイドライブ", "내 드라이브", "我的雲端硬碟")
        rootLabels.firstOrNull { app.clickText(it) }
        app.pumpMs(1_500)
    }
    // 手机竖屏根目录条目较多时，Dramas 可能在首屏下方，向下滚动直到出现
    for (s in 0 until 12) {
        if (app.findNode("Dramas", minY = 180f) != null) break
        app.scrollWheel(Offset(app.width * 0.5f, app.height * 0.6f), deltaY = 4f, clearHover = true)
        app.pumpMs(250)
    }
    // 仅点击内容区（minY = 180f）的 Dramas 文件夹卡片，绝不误点顶栏标签或面包屑下拉菜单
    app.clickText("Dramas", doubleClick = !app.mobile, preferLowest = true, minY = 180f)
    app.pumpMs(2_000)
    return app.findNode("Yuru Camp", minY = 180f) != null || app.findNode("Steins;Gate", minY = 180f) != null
}

// =========================================================================
// 1. 自适应工作区（桌面 + 手机双端 Dramas 海报墙）
// =========================================================================

private fun captureRealHero(real: RealReadmeEnv, outDir: File, lang: AppLanguage, mode: ThemeMode) {
    real.configure(lang, viewMode = "POSTER")
    val suffix = "${langSuffix(lang)}${themeSuffix(mode)}"
    var desktopImg: Image? = null
    var mobileImg: Image? = null
    try {
        ReadmeScene(real.services, real.platform, 1320, 820, mode, mobile = false, caption = true, motion = false).use { app ->
            navigateToDramas(app)
            // 关闭可能展开的面包屑下拉菜单，保持工作区干净
            app.pressKey(Key.Escape)
            app.pumpMs(2_400)
            desktopImg = app.snapshotCopy()
        }
        ReadmeScene(real.services, real.platform, 390, 820, mode, mobile = true, caption = false, motion = false).use { app ->
            navigateToDramas(app)
            app.pumpMs(2_400)
            mobileImg = app.snapshotCopy()
        }
        val framed = frameDesktopAndPhone(desktopImg!!, mobileImg!!, dark = mode == ThemeMode.DARK)
        saveJpeg(framed, File(outDir, "hero$suffix.jpg"))
        framed.close()
        println("Saved hero$suffix.jpg")
    } finally {
        desktopImg?.close()
        mobileImg?.close()
    }
}

// =========================================================================
// 2. 瀑布流 + 信息流同屏动态图（左瀑布流平滑滚动，右信息流实时播放切片段）
// =========================================================================

private fun captureRealGalleryAndFeed(real: RealReadmeEnv, outDir: File, lang: AppLanguage, mode: ThemeMode) {
    real.configure(lang, viewMode = "POSTER")
    val suffix = "${langSuffix(lang)}${themeSuffix(mode)}"
    val framesDir = File(outDir, "feed$suffix-frames").apply {
        deleteRecursively()
        mkdirs()
    }
    ReadmeScene(real.services, real.platform, 1380, 820, mode, mobile = false, caption = true, motion = true).use { app ->
        navigateToDramas(app)
        // 确保真正进入 Yuru Camp 子目录（绝不留在 Dramas 根目录播到第五共和国）
        if (app.waitForText("Yuru Camp", 6_000)) {
            app.clickText("Yuru Camp", doubleClick = true, preferLowest = true)
            app.pumpMs(2_000)
            // 若单击仅选中未进入，再补一次 Enter 进目录
            if (app.findNode("Heya Camp") == null && app.findNode("Season 2") == null) {
                app.pressKey(Key.Enter)
                app.pumpMs(2_000)
            }
        }
        // 左边切瀑布流，右边同时打开信息流
        val galleryBtn = when (lang) {
            AppLanguage.EN -> "Gallery"
            AppLanguage.JA -> "ギャラリー"
            AppLanguage.KO -> "갤러리"
            AppLanguage.ZH_HANT -> "瀑布流"
            else -> "瀑布流"
        }
        val feedBtn = when (lang) {
            AppLanguage.EN -> "Feed"
            AppLanguage.JA -> "フィード"
            AppLanguage.KO -> "피드"
            AppLanguage.ZH_HANT -> "資訊流"
            else -> "信息流"
        }
        if (!app.clickText(galleryBtn)) System.err.println("未找到「$galleryBtn」：${app.dumpTexts()}")
        app.pumpMs(2_500)
        // 向下轻滚一行跳过可能存在的无缩略图 JPG 占位块，确保满屏高清动漫画面
        repeat(8) {
            app.scrollWheel(Offset(540f, 460f), deltaY = 1.8f)
            Thread.sleep(35)
        }
        app.pumpMs(600)
        if (!app.clickText(feedBtn)) System.err.println("未找到「$feedBtn」：${app.dumpTexts()}")
        // 等待信息流 libmpv 起播并出首帧
        app.pumpMs(5_500, intervalMs = 33L)

        // 严格按 30fps（33.33ms/帧）抓原始帧，装裱与 PNG 落盘全部丢给 4 线程后台池异步执行，杜绝掉帧卡顿
        val fps = 30
        val totalFrames = fps * 5
        val frameNs = 1_000_000_000L / fps
        val writerPool = java.util.concurrent.Executors.newFixedThreadPool(4)
        val dark = mode == ThemeMode.DARK
        val captureStart = System.nanoTime()
        try {
            for (f in 0 until totalFrames) {
                // 每帧均匀微步滚动（30Hz 连续丝滑推进，速度 +50%），滚动后立即触发 Exit 清除悬停态，避免卡片左上角复选框闪现
                if (f in 5..145) {
                    app.scrollWheel(Offset(540f, 460f), deltaY = 0.27f, render = false, clearHover = true)
                }
                if (f == 50 || f == 100) {
                    // 每 1.67 秒切一次 Yuru Camp 片段（切换频率 +50%）
                    app.scrollWheel(Offset(1140f, 440f), deltaY = 4f, render = false, clearHover = true)
                }
                val raw = app.snapshotCopy()
                val idx = f
                writerPool.submit {
                    try {
                        val framed = frameDesktopWindow(raw, dark = dark)
                        val bytes = framed.encodeToData(EncodedImageFormat.PNG)!!.bytes
                        File(framesDir, "frame_%04d.png".format(idx)).writeBytes(bytes)
                        if (idx == 15) saveJpeg(framed, File(outDir, "feed$suffix.jpg"))
                        framed.close()
                    } finally {
                        raw.close()
                    }
                }
                val targetNs = captureStart + (f + 1L) * frameNs
                val remainMs = (targetNs - System.nanoTime()) / 1_000_000L
                if (remainMs > 1L) Thread.sleep(remainMs)
            }
        } finally {
            writerPool.shutdown()
            writerPool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)
        }
        // 精准关闭信息流侧栏（通过多语言 Close 语义定位，不残留到后续截图）
        val closeLabels = listOf("关闭", "Close", "閉じる", "닫기", "關閉")
        closeLabels.firstOrNull { app.clickText(it, minX = 1100f) }
        app.pumpMs(600)
        println("Saved $totalFrames frames for feed$suffix -> ${framesDir.absolutePath}")
    }
}

// =========================================================================
// 3. 链接解析（Steins;Gate 全集磁力链真实树形解析预览）
// =========================================================================

private fun captureRealLinks(real: RealReadmeEnv, outDir: File, lang: AppLanguage, mode: ThemeMode) {
    real.configure(lang, viewMode = "POSTER")
    val suffix = "${langSuffix(lang)}${themeSuffix(mode)}"
    ReadmeScene(real.services, real.platform, 1360, 820, mode, mobile = false, caption = true, motion = false).use { app ->
        navigateToDramas(app)
        app.pumpMs(1_000)
        // 直接向应用送入 Steins;Gate 真实磁力链并等待解析出完整文件树
        edt { real.services.instantMagnetRepository.onIncomingMagnet(STEINS_GATE_MAGNET) }
        app.pumpMs(800)
        // 如果信息流仍在后台挂着导致弹出确认框，自动点确认按钮
        val confirmAdd = when (lang) {
            AppLanguage.EN -> "Add link"
            AppLanguage.JA -> "リンクを追加"
            AppLanguage.KO -> "링크 추가"
            AppLanguage.ZH_HANT -> "新增連結"
            else -> "添加链接"
        }
        val endFeedHints = listOf("结束信息流", "結束資訊流", "End Feed", "フィード", "피드")
        if (endFeedHints.any { app.findNode(it, topmost = true) != null }) {
            app.clickText(confirmAdd, topmost = true)
        }
        app.waitForText("01", 15_000)
        app.pumpMs(3_500)
        // 左边仅在主文件区（maxX = 880f）精准双击打开「待保存」的命运石之门虚拟文件夹
        val openedPending = app.clickText("命运石之门", doubleClick = true, preferLowest = true, maxX = 880f) ||
            app.clickText("Steins;Gate", doubleClick = true, preferLowest = true, maxX = 880f)
        app.pumpMs(1_500)
        // 若双击仅选中而未进入，再补一次 Enter 进入待保存目录
        if (openedPending && app.findNode("Fonts", maxX = 880f) == null && app.findNode(".mkv", maxX = 880f) == null) {
            app.pressKey(Key.Enter)
            app.pumpMs(1_500)
        }
        // 右边原始文件树向下滚动，直到露出已勾选的正片 Gate][01]/Gate][02] .mkv 列表（跳过 Fonts / NCOP / PV）
        for (step in 0 until 240) {
            if (step >= 20 && (app.findNode("Gate][01]", minX = 900f) != null || app.findNode("Gate][02]", minX = 900f) != null)) {
                repeat(5) {
                    app.scrollWheel(Offset(1150f, 560f), deltaY = 3.0f)
                    Thread.sleep(20)
                }
                break
            }
            app.scrollWheel(Offset(1150f, 560f), deltaY = 4.0f)
            Thread.sleep(16)
        }
        app.pumpMs(800)
        val raw = app.snapshotCopy()
        val framed = frameDesktopWindow(raw, dark = mode == ThemeMode.DARK)
        saveJpeg(framed, File(outDir, "links$suffix.jpg"))
        raw.close()
        framed.close()
        // 精准关闭右侧添加链接面板（兼容各语种「取消添加 / 关闭」语义），不残留到后续截图
        val closeLabels = listOf("取消添加", "取消新增", "Cancel add", "追加をキャンセル", "추가 취소", "关闭", "Close", "閉じる", "닫기", "關閉")
        closeLabels.firstOrNull { app.clickText(it, minX = 900f) }
        app.pumpMs(500)
        println("Saved links$suffix.jpg")
    }
}

// =========================================================================
// 4. 外观自定义（直达渲染：左手机「文件外观」+ 右桌面「播放器自定义」，零菜单点击、五语通用）
// =========================================================================

private fun renderDirectComposable(
    services: PikoServices,
    platform: PikoPlatform,
    width: Int,
    height: Int,
    mode: ThemeMode,
    mobile: Boolean,
    pumpMs: Long = 1_000L,
    clickAfterMs: Pair<Long, String>? = null,
    content: @Composable () -> Unit,
): Image {
    val effectivePlatform = if (mobile) MobileShotPlatform(platform) else platform
    val viewport = mutableStateOf(IntSize(width, height))
    val context = Dispatchers.Main + object : MotionDurationScale {
        override val scaleFactor: Float = 0f
    }
    val scene = edt {
        ImageComposeScene(width, height, Density(1f), coroutineContext = context) {
            val baseWindow = androidx.compose.ui.platform.LocalWindowInfo.current
            val win = androidx.compose.runtime.remember(baseWindow) {
                object : androidx.compose.ui.platform.WindowInfo by baseWindow {
                    override val containerSize get() = viewport.value
                    override val containerDpSize get() = androidx.compose.ui.unit.DpSize(viewport.value.width.dp, viewport.value.height.dp)
                }
            }
            CompositionLocalProvider(
                LocalCursorBlinkEnabled provides false,
                androidx.compose.ui.platform.LocalWindowInfo provides win,
                LocalPikoServices provides services,
                LocalPikoPlatform provides effectivePlatform,
                LocalWindowCaption provides null,
            ) {
                PikoTheme(Appearance(mode = mode)) {
                    M3Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        content()
                    }
                }
            }
        }
    }
    try {
        val start = System.nanoTime()
        val deadline = start + pumpMs * 1_000_000L
        var clicked = false
        var img = edt { scene.render(0L) }
        while (System.nanoTime() < deadline) {
            val elapsedMs = (System.nanoTime() - start) / 1_000_000L
            if (!clicked && clickAfterMs != null && elapsedMs >= clickAfterMs.first) {
                clicked = true
                edt {
                    val target = scene.semanticsOwners
                        .flatMap { flatten(it.unmergedRootSemanticsNode) }
                        .firstOrNull { n -> nodeLabels(n).any { it.equals(clickAfterMs.second, ignoreCase = true) } }
                    if (target != null) {
                        val r = target.boundsInRoot
                        val c = Offset((r.left + r.right) / 2f, (r.top + r.bottom) / 2f)
                        val pt = if (mobile) PointerType.Touch else PointerType.Mouse
                        scene.sendPointerEvent(PointerEventType.Press, c, type = pt)
                        scene.sendPointerEvent(PointerEventType.Release, c, type = pt)
                    }
                }
            }
            val next = edt { scene.render(System.nanoTime() - start) }
            img.close()
            img = next
            Thread.sleep(33)
        }
        val surface = Surface.makeRasterN32Premul(width, height)
        surface.canvas.drawImage(img, 0f, 0f)
        img.close()
        val out = surface.makeImageSnapshot()
        surface.close()
        return out
    } finally {
        edt { scene.close() }
    }
}

private fun captureRealCustomize(real: RealReadmeEnv, outDir: File, lang: AppLanguage, mode: ThemeMode) {
    real.configure(lang, viewMode = "POSTER")
    val suffix = "${langSuffix(lang)}${themeSuffix(mode)}"
    var phoneFileCustomImg: Image? = null
    var desktopPlayerCustomImg: Image? = null
    try {
        phoneFileCustomImg = renderDirectComposable(
            services = real.services,
            platform = real.platform,
            width = 390,
            height = 820,
            mode = mode,
            mobile = true,
            pumpMs = 900L,
        ) {
            DriveAppearanceCustomizationPage(
                prefs = DriveAppearancePrefs(),
                onUpdate = {},
                onBackClick = {},
            )
        }
        desktopPlayerCustomImg = renderDirectComposable(
            services = real.services,
            platform = real.platform,
            width = 1260,
            height = 660,
            mode = mode,
            mobile = false,
            pumpMs = 1_100L,
            clickAfterMs = 350L to "1.0x",
        ) {
            PlayerCustomizationPage(
                prefs = PlayerLayoutPrefs(),
                onUpdate = {},
                onBackClick = {},
            )
        }
        val framed = framePhoneAndDesktop(phoneFileCustomImg!!, desktopPlayerCustomImg!!, dark = mode == ThemeMode.DARK)
        saveJpeg(framed, File(outDir, "customize$suffix.jpg"))
        framed.close()
        println("Saved customize$suffix.jpg")
    } finally {
        phoneFileCustomImg?.close()
        desktopPlayerCustomImg?.close()
    }
}

// =========================================================================
// 5. 文件处理（三部手机并排：片段截取 06:04~16:59 + 批量重命名面板 + 跨目录查重）
// =========================================================================

private fun captureRealTools(real: RealReadmeEnv, outDir: File, lang: AppLanguage, mode: ThemeMode) {
    real.configure(lang, viewMode = "POSTER")
    val suffix = "${langSuffix(lang)}${themeSuffix(mode)}"
    var clipImg: Image? = null
    var renameImg: Image? = null
    var dupImg: Image? = null
    try {
        // 从真实 Dramas 目录拉取文件列表，直接供片段截取与批量重命名使用（零菜单点击、五语通用）
        val dramasFiles = runBlocking {
            val rootItems = real.services.driveRepository.listAllFiles("").getOrDefault(emptyList())
            val dramasFolder = rootItems.firstOrNull { it.name.equals("Dramas", ignoreCase = true) }
            if (dramasFolder != null) {
                real.services.driveRepository.listAllFiles(dramasFolder.id).getOrDefault(emptyList())
            } else emptyList()
        }
        val saijoVideo = dramasFiles.firstOrNull { it.name.contains("Saijo no Osewa", ignoreCase = true) }
            ?: dramasFiles.firstOrNull { !it.isFolder && it.name.endsWith(".mkv", true) || it.name.endsWith(".mp4", true) }

        // 手机 1：无损视频片段截取（直达 SegmentDownloadSheet，设定 06:04..16:59 并切到「终点」展示 16:59 画面）
        if (saijoVideo != null) {
            val session = SegmentSession().apply {
                open(saijoVideo, initialRange = 364_000L..1_019_000L)
            }
            val endLabel = when (lang) {
                AppLanguage.EN -> "End point"
                AppLanguage.JA -> "終了点"
                AppLanguage.KO -> "끝점"
                AppLanguage.ZH_HANT -> "終點"
                else -> "终点"
            }
            clipImg = renderDirectComposable(
                services = real.services,
                platform = real.platform,
                width = 450,
                height = 840,
                mode = mode,
                mobile = true,
                pumpMs = 5_800L,
                clickAfterMs = 2_200L to endLabel,
            ) {
                Box(Modifier.fillMaxSize()) {
                    SegmentDownloadSheet(
                        session = session,
                        onConfirmDownload = { _, _, _, _, _, _, _ -> },
                        itemName = "Saijo no Osewa 08",
                    )
                }
            }
            session.end()
        } else {
            clipImg = renderDirectComposable(real.services, real.platform, 450, 840, mode, mobile = true) {}
        }

        // 手机 2：批量重命名面板（直达 BatchRenameDialog，喂入真实 Dramas 条目）
        renameImg = renderDirectComposable(
            services = real.services,
            platform = real.platform,
            width = 450,
            height = 840,
            mode = mode,
            mobile = true,
            pumpMs = 1_200L,
        ) {
            BatchRenameDialog(
                files = dramasFiles.take(8),
                onDismiss = {},
                onFinished = {},
            )
        }

        // 手机 3：跨目录查重（严格限定在 Dramas 内执行；若上一主题已停在 Dramas 查重页则直接复用，绝不误点「结束查重」）
        ReadmeScene(real.services, real.platform, 450, 840, mode, mobile = true, caption = false, motion = false).use { app ->
            app.pumpMs(1_500)
            val dupLabel = when (lang) {
                AppLanguage.EN -> "Duplicates"
                AppLanguage.JA -> "重複検索"
                AppLanguage.KO -> "중복 검색"
                AppLanguage.ZH_HANT -> "查重"
                else -> "查重"
            }
            val alreadyInDup = app.findNode("$dupLabel: Dramas") != null || app.findNode("$dupLabel：Dramas") != null
            if (!alreadyInDup) {
                val inDramas = navigateToDramas(app)
                require(inDramas) { "手机端未能进入 Dramas，拒绝在根目录查重：${app.dumpTexts()}" }
                app.pumpMs(1_200)
                if (!app.clickText(dupLabel)) {
                    val moreLabels = listOf("更多", "More", "その他", "더 보기", "更多操作")
                    moreLabels.firstOrNull { app.clickText(it) }
                    app.pumpMs(500)
                    if (!app.clickText(dupLabel, topmost = true)) {
                        System.err.println("手机端未找到「$dupLabel」：${app.dumpTexts()}")
                    }
                }
                app.pumpMs(3_500)
            } else {
                app.pumpMs(1_500)
            }
            dupImg = app.snapshotCopy()
        }
        val framed = framePhoneTriptych(clipImg!!, renameImg!!, dupImg!!, dark = mode == ThemeMode.DARK)
        saveJpeg(framed, File(outDir, "tools$suffix.jpg"))
        framed.close()
        println("Saved tools$suffix.jpg")
    } finally {
        clipImg?.close()
        renameImg?.close()
        dupImg?.close()
    }
}

// =========================================================================
// Skia 精致装裱合成器
// =========================================================================

private fun rrect(rect: Rect, radius: Float): RRect =
    RRect.makeXYWH(rect.left, rect.top, rect.width, rect.height, radius)

private fun drawBackdrop(canvas: Canvas, w: Float, h: Float, dark: Boolean) {
    val base = Paint().apply { color = if (dark) 0xFF0B0E14.toInt() else 0xFFE8EFFC.toInt() }
    canvas.drawRect(Rect.makeWH(w, h), base)
    base.close()
    val glow = Paint().apply {
        color = if (dark) 0x4422304C.toInt() else 0xCCFAFBFD.toInt()
        imageFilter = ImageFilter.makeBlur(180f, 180f, org.jetbrains.skia.FilterTileMode.DECAL)
    }
    canvas.drawCircle(w * 0.34f, h * 0.28f, maxOf(w, h) * 0.42f, glow)
    glow.close()
}

private fun drawShadowedRoundedImage(canvas: Canvas, img: Image, x: Float, y: Float, w: Float, h: Float, radius: Float, dark: Boolean) {
    val rect = Rect.makeXYWH(x, y, w, h)
    val rr = rrect(rect, radius)
    val shadowPaint = Paint().apply {
        color = if (dark) 0x99000000.toInt() else 0x26172B4D.toInt()
        imageFilter = ImageFilter.makeBlur(24f, 24f, org.jetbrains.skia.FilterTileMode.DECAL)
    }
    canvas.drawRRect(rrect(rect.offset(0f, 16f), radius), shadowPaint)
    shadowPaint.close()

    canvas.save()
    canvas.clipRRect(rr, ClipMode.INTERSECT, true)
    canvas.drawImageRect(img, Rect.makeWH(img.width.toFloat(), img.height.toFloat()), rect, SamplingMode.MITCHELL, null, true)
    canvas.restore()

    val borderPaint = Paint().apply {
        color = if (dark) 0x33FFFFFF else 0x1A000000
        mode = PaintMode.STROKE
        strokeWidth = 1.5f
        isAntiAlias = true
    }
    canvas.drawRRect(rr, borderPaint)
    borderPaint.close()
}

private fun drawPhoneMockup(canvas: Canvas, img: Image, x: Float, y: Float, dark: Boolean) {
    val bezel = 12f
    val statusBarH = 32f
    val pillH = 22f
    val screenW = img.width.toFloat()
    val screenH = img.height.toFloat()
    val outerW = screenW + bezel * 2
    val outerH = screenH + statusBarH + pillH + bezel * 2
    val outerRect = Rect.makeXYWH(x, y, outerW, outerH)
    val outerR = 44f
    val innerR = 34f

    val shadow = Paint().apply {
        color = if (dark) 0xAA000000.toInt() else 0x30172B4D.toInt()
        imageFilter = ImageFilter.makeBlur(24f, 24f, org.jetbrains.skia.FilterTileMode.DECAL)
    }
    canvas.drawRRect(rrect(outerRect.offset(0f, 18f), outerR), shadow)
    shadow.close()

    val bodyPaint = Paint().apply {
        color = 0xFF16181D.toInt()
        isAntiAlias = true
    }
    canvas.drawRRect(rrect(outerRect, outerR), bodyPaint)
    bodyPaint.close()

    val screenRect = Rect.makeXYWH(x + bezel, y + bezel, screenW, screenH + statusBarH + pillH)
    val screenRRect = rrect(screenRect, innerR)
    canvas.save()
    canvas.clipRRect(screenRRect, ClipMode.INTERSECT, true)
    val screenBg = Paint().apply { color = if (dark) 0xFF111318.toInt() else 0xFFFAFBFD.toInt() }
    canvas.drawRect(screenRect, screenBg)
    screenBg.close()

    val fgColor = if (dark) 0xFFE6E8EE.toInt() else 0xFF1A1C20.toInt()
    val fg = Paint().apply {
        color = fgColor
        isAntiAlias = true
    }
    val fgStroke = Paint().apply {
        color = fgColor
        mode = PaintMode.STROKE
        strokeWidth = 1.5f
        strokeCap = org.jetbrains.skia.PaintStrokeCap.ROUND
        isAntiAlias = true
    }
    val tf = FontMgr.default.matchFamilyStyle("Segoe UI", FontStyle.BOLD)
    val font = Font(tf, 13f)
    val timeLine = TextLine.make("12:00", font)
    canvas.drawTextLine(timeLine, screenRect.left + 28f, screenRect.top + 22f, fg)
    timeLine.close()
    font.close()

    // 居中挖孔摄像头
    val camPaint = Paint().apply { color = 0xFF16181D.toInt(); isAntiAlias = true }
    canvas.drawCircle(screenRect.left + screenW * 0.5f, screenRect.top + 17f, 6.2f, camPaint)
    camPaint.close()

    // 右侧 Wi-Fi 三层弧 + 圆点
    val wifiCx = screenRect.right - 66f
    val wifiCy = screenRect.top + 22.5f
    canvas.drawCircle(wifiCx, wifiCy - 1.2f, 1.4f, fg)
    for (rad in floatArrayOf(4.8f, 8.2f, 11.6f)) {
        canvas.drawArc(
            wifiCx - rad,
            wifiCy - rad,
            wifiCx + rad,
            wifiCy + rad,
            -135f,
            90f,
            false,
            fgStroke,
        )
    }

    // 右侧电量胶囊（圆角外框 + 右端正极凸起 + 满电内芯）
    val batLeft = screenRect.right - 47f
    val batTop = screenRect.top + 11.5f
    val batW = 22f
    val batH = 11.5f
    canvas.drawRRect(rrect(Rect.makeXYWH(batLeft, batTop, batW, batH), 3.2f), fgStroke)
    canvas.drawRRect(rrect(Rect.makeXYWH(batLeft + batW + 1.4f, batTop + 3.2f, 2f, 5.1f), 1f), fg)
    canvas.drawRRect(rrect(Rect.makeXYWH(batLeft + 2.2f, batTop + 2.2f, batW - 4.4f, batH - 4.4f), 1.6f), fg)
    fgStroke.close()

    val dst = Rect.makeXYWH(screenRect.left, screenRect.top + statusBarH, screenW, screenH)
    canvas.drawImageRect(img, Rect.makeWH(screenW, screenH), dst, SamplingMode.LINEAR, null, true)

    val pillPaint = Paint().apply {
        color = if (dark) 0x80FFFFFF.toInt() else 0x55000000
        isAntiAlias = true
    }
    canvas.drawRRect(
        rrect(Rect.makeXYWH(screenRect.left + screenW * 0.34f, screenRect.bottom - 10f, screenW * 0.32f, 4f), 2f),
        pillPaint,
    )
    pillPaint.close()
    fg.close()
    canvas.restore()
}

private fun frameDesktopWindow(raw: Image, dark: Boolean): Image {
    val padX = 96
    val padY = 88
    val w = raw.width + padX * 2
    val h = raw.height + padY * 2
    val surface = Surface.makeRasterN32Premul(w, h)
    drawBackdrop(surface.canvas, w.toFloat(), h.toFloat(), dark)
    drawShadowedRoundedImage(surface.canvas, raw, padX.toFloat(), padY.toFloat(), raw.width.toFloat(), raw.height.toFloat(), 18f, dark)
    val out = surface.makeImageSnapshot()
    surface.close()
    return out
}

private fun frameDesktopAndPhone(desktop: Image, phone: Image, dark: Boolean): Image {
    val w = 1760
    val h = 1040
    val surface = Surface.makeRasterN32Premul(w, h)
    drawBackdrop(surface.canvas, w.toFloat(), h.toFloat(), dark)
    drawShadowedRoundedImage(surface.canvas, desktop, 76f, 84f, desktop.width.toFloat(), desktop.height.toFloat(), 18f, dark)
    drawPhoneMockup(surface.canvas, phone, 1280f, 106f, dark)
    val out = surface.makeImageSnapshot()
    surface.close()
    return out
}

private fun framePhoneAndDesktop(phone: Image, desktop: Image, dark: Boolean): Image {
    val w = 1840
    val h = 1020
    val surface = Surface.makeRasterN32Premul(w, h)
    drawBackdrop(surface.canvas, w.toFloat(), h.toFloat(), dark)
    // 手机（414px 宽）在左，桌面窗（1260px 宽）在右，中间留 38px 呼吸缝，完全不遮挡标题
    drawPhoneMockup(surface.canvas, phone, 64f, 62f, dark)
    drawShadowedRoundedImage(surface.canvas, desktop, 516f, 100f, desktop.width.toFloat(), desktop.height.toFloat(), 18f, dark)
    val out = surface.makeImageSnapshot()
    surface.close()
    return out
}

private fun framePhoneTriptych(first: Image, second: Image, third: Image, dark: Boolean): Image {
    val w = 1600
    val h = 1020
    val surface = Surface.makeRasterN32Premul(w, h)
    drawBackdrop(surface.canvas, w.toFloat(), h.toFloat(), dark)
    // 三部手机 390+24=414px 宽，等距排满 1600px 横幅（边距 96px，间距 83px）
    drawPhoneMockup(surface.canvas, first, 96f, 62f, dark)
    drawPhoneMockup(surface.canvas, second, 593f, 62f, dark)
    drawPhoneMockup(surface.canvas, third, 1090f, 62f, dark)
    val out = surface.makeImageSnapshot()
    surface.close()
    return out
}

private fun saveJpeg(image: Image, file: File) {
    file.parentFile?.mkdirs()
    file.writeBytes(image.encodeToData(EncodedImageFormat.JPEG, 92)!!.bytes)
}
