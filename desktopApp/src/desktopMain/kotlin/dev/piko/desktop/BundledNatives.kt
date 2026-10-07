package dev.piko.desktop

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.openani.mediamp.mpv.MpvMediampPlayer

private val bundledResources: File?
    get() = System.getProperty("compose.application.resources.dir")?.let(::File)

/**
 * JNA 的 jnidispatch 默认每次启动都从 jar 解压到 `%TEMP%\jna-*` 再载入（mediamp 准备 mpv 时经它调 SetDllDirectoryW）。
 * 安装包把同一个 jar 里的那份放在资源目录的 jna 下（见 build.gradle.kts 的 bundledAppResources），这里指过去并禁止解压。
 * 在代码里设而不写进 jvmArgs：jvmArgs 也给 `:desktopApp:run`，那里没有 $APPDIR 可展开，禁了解压 JNA 就整个载不起来。
 * 必须赶在任何 JNA 类初始化之前：Native 在静态初始化时读这两个属性。资源目录里没有时（开发版、测试）沿用默认行为。
 */
internal fun useBundledJnaDispatch() {
    val dir = bundledResources?.resolve("jna") ?: return
    if (!dir.resolve(System.mapLibraryName("jnidispatch")).isFile) return
    System.setProperty("jna.boot.library.path", dir.absolutePath)
    System.setProperty("jna.nounpack", "true")
}

/**
 * 安装包把 mpv 与 FFmpeg 的原生库放在资源目录的 mpv 子目录里，指给 mediamp，免得它把库从 jar 解压到新的临时目录。
 *
 * 指过去时 mediamp 会校验并加载封装层，连带 mpv 与 FFmpeg 一串依赖，实测约 100 至 150 毫秒，所以启动时放后台线程做，
 * 不挡首帧；建播放器之前再经 [ensure] 等它做完。不等的话，后台还没做完时建出的播放器找不到运行库：
 * 安装包的类路径里没有 mpv 运行库的 jar，mediamp 退不到解压那条路，直接报错。
 */
internal object BundledMpvRuntime {
    private val started = AtomicBoolean(false)
    private val done = CountDownLatch(1)

    fun prepareInBackground() {
        if (!started.compareAndSet(false, true)) return
        Thread({
            try {
                prepare()
            } finally {
                done.countDown()
            }
        }, "Piko-Mpv-Setup").apply { isDaemon = true; start() }
    }

    private fun prepare() {
        val dir = bundledResources?.resolve("mpv") ?: return
        // Windows 上是 mediampv.dll，macOS 上是 libmediampv.dylib，Linux 上是 libmediampv.so
        if (!dir.resolve(System.mapLibraryName("mediampv")).isFile) return
        runCatching { MpvMediampPlayer.prepareLibraries(dir.absolutePath, false) }
    }

    /**
     * 等到运行库准备好（或确认没有捆绑的运行库）。会在组合期间的界面线程上调：通常早已做完、当场返回；
     * 限时等待是怕磁盘或杀毒扫描把加载拖得很久，超时就照常建播放器，由 mediamp 按原来的错误报出来，而不是卡住界面。
     */
    fun ensure(timeoutMillis: Long = 3_000) {
        prepareInBackground()
        done.await(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    val isPrepared: Boolean get() = done.count == 0L
}
