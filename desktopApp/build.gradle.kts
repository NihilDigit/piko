import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.support.serviceOf
import org.jetbrains.compose.desktop.application.dsl.AotMode
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractCheckNativeDistributionRuntime
import org.jetbrains.compose.desktop.application.tasks.AbstractJvmToolOperationTask
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.compose.desktop.application.tasks.AbstractProguardTask
import org.jetbrains.compose.desktop.application.tasks.AbstractSuggestModulesTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
}

// 打包不能交叉构建：jpackage 只出宿主系统的安装包，mpv 与 skiko 的原生库也按宿主取
val hostOs = System.getProperty("os.name").let { name ->
    when {
        name.startsWith("Mac") -> "macos"
        name.startsWith("Windows") -> "windows"
        else -> "linux"
    }
}
val isMacHost = hostOs == "macos"
val isWindowsHost = hostOs == "windows"
val isLinuxHost = hostOs == "linux"
val hostArch = if (System.getProperty("os.arch") == "aarch64") "arm64" else "x64"
val hostPlatform = "$hostOs-$hostArch"
// MediaMP 0.5.3 的 mpv 运行库只有 linux-x64，没有 linux-arm64，Linux 只出 x64
val hostMpvRuntime = "org.openani.mediamp:mediamp-mpv-runtime-$hostPlatform:${libs.versions.mediamp.get()}"
// 桌面运行库按宿主架构取，版本与界面库一致（CMP 1.13.0-rc01）
val composeDesktopRuntime = "org.jetbrains.compose.desktop:desktop-jvm-$hostPlatform:${libs.versions.composeMultiplatform.get()}"
// 应用内差分更新要的 libzstd。只取 zstd-jni 按平台的 jar 里那个原生库捆进资源目录，解码经 FFM 直调它的
// C API（update/ZstdPatch.kt），运行时不依赖 zstd-jni 的类。只有 Windows 的更新用 zstd 差分，
// Linux 走 zsync，macOS 整包替换
val hostZstdClassifier = if (hostArch == "arm64") "win_aarch64" else "win_amd64"
// Linux 的应用 ID：.desktop、AppStream、图标与 WM_CLASS 都用它，Flathub 以它为包名。域名 nihildigit.dev 归作者，
// Flathub 据此验证。上线之后改名代价很大，定了不再改
val linuxAppId = "dev.nihildigit.Piko"
val hostZstdJni = "com.github.luben:zstd-jni:${libs.versions.zstdJni.get()}:$hostZstdClassifier"

kotlin {
    jvm("desktop")

    // Windows 原生能力经 JDK 的 FFM（java.lang.foreign）直调，需要 JDK 22+
    //（JDK 21 上是 preview API，不带 --enable-preview 直接抛异常）。钉死 25，
    // 本地即使 Gradle 跑在 JDK 21 上，desktop 的编译/测试/运行也会走自动供给的 JDK 25。
    jvmToolchain(25)

    sourceSets {
        getByName("desktopMain").dependencies {
            implementation(project(":shared"))
            implementation(project(":ui-core"))
            implementation(project(":ui-player"))
            implementation(project(":ui-reader"))
            implementation(project(":ui"))
            implementation(libs.windows.touch)
            implementation(composeDesktopRuntime)
            implementation(libs.cmp.material.icons.extended)
            implementation(libs.mediamp.all)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.logging)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.okhttp)
            // PikoUploadSources.open 返回 RawSource，shared 只以 implementation 引入
            implementation(libs.kotlinx.io.core)
        }
        getByName("desktopTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.junit)
            implementation(project(":ui-drive"))
            // 控件的鼠标悬停只能用真实的指针事件序列验证；版本跟界面库走，理由同 composeDesktopRuntime
            implementation("org.jetbrains.compose.ui:ui-test:${libs.versions.composeMultiplatform.get()}")
            // 测试里按键不经窗口的返回输入，要从组合里取到返回事件的分发器自己发。版本取界面库已经解析到的那一个
            implementation("org.jetbrains.androidx.navigationevent:navigationevent-compose:1.1.0")
            // 测试进程没有打包好的资源目录，mpv 的原生库仍从类路径解压
            runtimeOnly(hostMpvRuntime)
        }
    }
}

// Compose 的 run 默认用 Gradle 自身所在的 JDK 启动，不看 jvmToolchain，Gradle 跑在 JDK 21 上时
// 加载 25 编出的类即报 UnsupportedClassVersionError。不在 application 里写 javaHome：它是 String，
// 赋值即在配置期解析工具链，而只装了 JDK 21 的 Android CI job 也会配置本工程，找不到 25 便失败。
// run 由 Compose 在 afterEvaluate 中注册，其注册动作会覆盖先登记的 configureEach，故在其后追加；
// 该动作只在 run 被实际请求时执行。也不能推迟到 doFirst：执行前 javaLauncher 已按旧的
// executable 定值，届时再改会报两者不匹配。
val desktopJavaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
afterEvaluate {
    tasks.named<JavaExec>("run") {
        executable(desktopJavaLauncher.get().executablePath.asFile)
    }
}

// jlink、jpackage 与 ProGuard 默认用运行 Gradle 的那个 JDK。本机的 Gradle 跑在 JBR 21 上，打不了包；
// 而 JDK 25 里 Temurin 的发行包不带 jmods，ProGuard 读不到 java.lang.Object（release.yml 选 Zulu 也是因此）。
// 这里按 Azul 的 25 取工具链，缺了由 foojay 下载。用 Provider 绑定而不写 application.javaHome：
// 后者是 String，配置期就要解析，只装了 JDK 21 的 Android CI 也会配置本工程。
// 放进 afterEvaluate：插件在它自己的 afterEvaluate 里给这些任务设 javaHome，先登记的会被盖掉
val packagingJdkHome = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(25)
    vendor = JvmVendorSpec.AZUL
}.map { it.metadata.installationPath.asFile.absolutePath }
afterEvaluate {
    val packagingCcReason = "Compose Desktop packaging/ProGuard/AOT tasks run outside Configuration Cache"
    tasks.withType<AbstractJvmToolOperationTask>().configureEach {
        javaHome.set(packagingJdkHome)
        notCompatibleWithConfigurationCache(packagingCcReason)
    }
    tasks.withType<AbstractProguardTask>().configureEach {
        javaHome.set(packagingJdkHome)
        notCompatibleWithConfigurationCache(packagingCcReason)
    }
    tasks.withType<AbstractSuggestModulesTask>().configureEach {
        javaHome.set(packagingJdkHome)
        notCompatibleWithConfigurationCache(packagingCcReason)
    }
    tasks.withType<AbstractCheckNativeDistributionRuntime>().configureEach {
        jdkHome.set(packagingJdkHome)
        notCompatibleWithConfigurationCache(packagingCcReason)
    }
}

// Compose 的桌面运行库与 MediaMP 带进了 ui-test，连带 junit、truth、guava 与
// kotlinx-coroutines-test，全部进了安装包。coroutines-test 还注册了一个 MainDispatcherFactory。
// 运行时一个都用不到，只从打包用的运行时类路径里排除，测试类路径不受影响
configurations.named("desktopRuntimeClasspath") {
    exclude(group = "org.jetbrains.compose.ui", module = "ui-test")
    exclude(group = "org.jetbrains.compose.ui", module = "ui-test-desktop")
    exclude(group = "org.jetbrains.compose.ui", module = "ui-test-junit4")
    exclude(group = "org.jetbrains.compose.ui", module = "ui-test-junit4-desktop")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-test")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-test-jvm")
    exclude(group = "junit", module = "junit")
    exclude(group = "org.hamcrest")
    exclude(group = "com.google.truth")
}

// mpv 与 FFmpeg 的原生库（DLL 或 dylib）解开放进应用资源目录。mediamp 默认在每次启动后首次播放时把
// 近 40MB 的库从 jar 解压到新建的临时目录，Windows 上 DLL 被进程占用，deleteOnExit 删不掉，每次运行都在 %TEMP% 留一份。
// 改为安装时就位，启动时经 MpvMediampPlayer.prepareLibraries 指过去，不再解压
val hostMpvRuntimeJar = configurations.detachedConfiguration(dependencies.create(hostMpvRuntime)).apply {
    isTransitive = false
}
// libzstd 放在资源目录的 zstd 子目录里，由 ZstdPatch 载入。
// 这个 DLL 也是 CI 判断旧版客户端会不会用差分的依据，见 .github/scripts/delta-updates.sh；它原样留着，1.0.0 才走不了补丁，
// 升级 zstd-jni 换了它时 packageReleaseUpdate 会失败，见 UpdateArtifactsTask.checkRetired
val hostZstdJniJar = configurations.detachedConfiguration(dependencies.create(hostZstdJni)).apply {
    isTransitive = false
}
/**
 * jar 存不了符号链接，Linux 的运行库把同一个库以真实文件名、SONAME 与开发用的无版本名各存一份
 * （libavcodec.so.62.11.100、libavcodec.so.62、libavcodec.so，各 11 MB）。动态链接器只按 SONAME
 * 找依赖，mediamp 只加载 libmediampv.so，其余两个名字没人读，只留 SONAME 那一份：
 * 带更长版本号、以另一个带版本的名字为前缀的是真实文件名；无版本的 libX.so 另有带版本的同名库时是开发用的名字。
 */
fun linuxSonameFiles(jar: File): Set<String> {
    val library = Regex("""lib.+\.so(\.[0-9]+)*""")
    val names: Set<String> = ZipFile(jar).use { zip ->
        Collections.list(zip.entries()).map { entry -> entry.name }
            .filter { name: String -> !name.contains('/') && library.matches(name) }
            .toSet()
    }
    val versioned: List<String> = names.filter { name: String -> name.contains(".so.") }
    return names.filterNot { name: String ->
        versioned.any { other: String -> other != name && name.startsWith("$other.") } ||
            (name.endsWith(".so") && versioned.any { other: String -> other.startsWith("$name.") })
    }.toSet()
}

val bundledAppResourcesDir = layout.buildDirectory.dir("appResources/common")

val bundledAppResources = tasks.register<Sync>("bundledAppResources") {
    // 局部变量保存 ArchiveOperations 服务，避免 Provider.map 闭包捕获脚本顶层对象导致 Configuration Cache 失败。
    val archiveOps = serviceOf<ArchiveOperations>()
    from(hostMpvRuntimeJar.elements.map { files -> files.map { archiveOps.zipTree(it.asFile) } }) {
        include("*.dll", "*.dylib", "*.txt")
        if (isLinuxHost) {
            // 在任务配置期直接求出纯 Set<String>，避免 include 闭包捕获脚本或 Configuration 破坏 Configuration Cache。
            val keep: Set<String> = linuxSonameFiles(hostMpvRuntimeJar.singleFile)
            include { !it.isDirectory && it.name in keep }
        }
        into("mpv")
    }
    // 只有 Windows 的更新用 zstd 差分，别处不捆
    if (isWindowsHost) {
        from(hostZstdJniJar.elements.map { files -> files.map { archiveOps.zipTree(it.asFile) } }) {
            include("**/*.dll")
            eachFile { path = "zstd/$name" }
            includeEmptyDirs = false
        }
    }
    // JNA 的 jnidispatch 从实际打进包里的那个 jna jar 取：原生库与 Java 端版本不一致时 JNA 拒绝载入。
    // 免得它每次启动都解压到 %TEMP%，见 BundledNatives.kt 的 useBundledJnaDispatch。只做了 Windows：
    // 别的系统未验证 JNA 在禁止解压时的查找规则，照旧解压
    if (isWindowsHost) {
        val desktopRuntimeFiles = configurations.named("desktopRuntimeClasspath").flatMap { it.elements }
        from(
            desktopRuntimeFiles.map { files ->
                files.filter { Regex("""jna-[0-9][0-9.]*\.jar""").matches(it.asFile.name) }
                    .map { archiveOps.zipTree(it.asFile) }
            },
        ) {
            include("com/sun/jna/win32-${if (hostArch == "arm64") "aarch64" else "x86-64"}/jnidispatch.dll")
            eachFile { path = "jna/$name" }
            includeEmptyDirs = false
        }
        from(
            desktopRuntimeFiles.map { files ->
                files.filter { Regex("""sqlite-jdbc-[0-9][0-9.]*\.jar""").matches(it.asFile.name) }
                    .map { archiveOps.zipTree(it.asFile) }
            },
        ) {
            include("org/sqlite/native/Windows/${if (hostArch == "arm64") "aarch64" else "x86_64"}/sqlitejdbc.dll")
            eachFile { path = "sqlite/$name" }
            includeEmptyDirs = false
        }
    }
    // Toast 的 AUMID 登记要一个磁盘上的图标文件，exe 里内嵌的那份用不上；.torrent 关联另用独立文档图标
    from("src/desktopMain/resources/app-icon.png")
    if (isWindowsHost) {
        from("package/windows/torrent.ico")
    }
    into(bundledAppResourcesDir)
}

// Windows 应用内更新器：纯 java.base 单类打包为独立可执行 jar 并以 .bin 资源名塞进类路径，
// 既彻底消除运行期释放 .ps1 调 powershell.exe -ExecutionPolicy Bypass，又避免 ProGuard 过滤嵌套 .jar。
val compileWindowsUpdateApplier = tasks.register<JavaCompile>("compileWindowsUpdateApplier") {
    source = fileTree("updaterHelper/src/main/java") { include("**/*.java") }
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("updaterHelper/classes"))
    javaCompiler.set(javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(25) })
    options.encoding = "UTF-8"
    options.release.set(21)
}

val packageWindowsUpdateApplier = tasks.register<Jar>("packageWindowsUpdateApplier") {
    dependsOn(compileWindowsUpdateApplier)
    from(compileWindowsUpdateApplier.map { it.destinationDirectory })
    manifest {
        attributes("Main-Class" to "dev.piko.updater.WindowsUpdateApplier")
    }
    destinationDirectory.set(layout.buildDirectory.dir("updaterHelper/resources/update"))
    archiveFileName.set("piko-update-applier.bin")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

kotlin.sourceSets.named("desktopMain") {
    resources.srcDir(packageWindowsUpdateApplier.map { layout.buildDirectory.dir("updaterHelper/resources") })
}
tasks.matching { it.name == "desktopProcessResources" || it.name == "assembleDesktopMainResources" }.configureEach {
    dependsOn(packageWindowsUpdateApplier)
}

// 升级唯一标识：换了它，已安装版本会被当成另一个产品。切勿修改。
// 可覆写只为在本机测试 MSI 更新：另起一个产品，不碰已安装的 Piko
val msiUpgradeUuid = providers.gradleProperty("pikoDesktopUpgradeUuid").getOrElse("6d8d332e-f0f4-4ee0-bc2d-fb3ebf3d4267")
val desktopPackageName = providers.gradleProperty("pikoDesktopPackageName").getOrElse("Piko")
// 写进 HKCU 的名字（通知的 AUMID、打开方式的 ProgID、Capabilities）由它派生，见 ShellIdentity。测试包用包名，
// 本机跑安装冒烟时不改、不删装着的 Piko 的登记；正式包不传，名字与发过的版本相同
val shellId = desktopPackageName
// 不传版本的是本地或非 tag 构建。默认值取 1.0.0 而不是 0.x：macOS 的 CFBundleVersion 首位必须大于 0，
// jpackage 直接拒绝。它与正式版可能同号，所以是不是发布构建另由 piko.release-build 标明，见 DesktopAppUpdater
val releaseVersion = providers.gradleProperty("pikoDesktopVersion").map { it.trim() }.filter { it.isNotEmpty() }
val desktopPackageVersion = releaseVersion.getOrElse("1.0.0")

// X11 窗口的 WM_CLASS 由 AWT 按主类名生成（dev-piko-desktop-MainKt），桌面环境拿它对 .desktop 文件，
// 对不上时 Dock 与任务栏认不出 Piko，显示成一个没有图标的 java 窗口。它没有公开的设置方法，
// LinuxDesktop.setWmClass 经反射改 XToolkit 的字段，要开这个包。只在 Linux 上加：别的系统的 JDK
// 没有这个包，启动时会打一行警告
val linuxJvmArgs = if (isLinuxHost) listOf("--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED") else emptyList()

composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf"))
}

compose {
    dependencyCompatibility {
        // 依赖版本已由 libs.versions.toml 统一锁定，跳过每次构建的兼容检查任务以提速。
        enabled = false
    }
}

compose.desktop {
    application {
        mainClass = "dev.piko.desktop.MainKt"
        // 写进 exe/.cfg 启动器：跟 run/test 的 jvmArgs 对齐，否则 FFM 受限方法告警。
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        // Skiko FrameWatcher 在连续出帧期间每 30 秒主动调 System.gc() 催收原生 Skia 对象；
        // 默认触发整堆 STW G1Full（约 50ms 停顿），改为并发收集周期避免长停顿。
        jvmArgs += "-XX:+ExplicitGCInvokesConcurrent"
        // 应用内更新据此在 Windows Installer 的登记里认出自己是不是 MSI 装的，见 DesktopAppUpdater
        jvmArgs += "-Dpiko.upgrade-code=$msiUpgradeUuid"
        if (shellId != "Piko") jvmArgs += "-Dpiko.shell-id=$shellId"
        if (releaseVersion.isPresent) jvmArgs += "-Dpiko.release-build=true"
        // 本机不带版本号打的包与 :desktopApp:run 一样用 ~/.piko-dev：否则它与装着的正式版共用 ~/.piko 与单实例锁，
        // 版本号又默认 1.0.0，日志里分不出是谁。CI 的打包与安装冒烟都传版本号，不受影响
        // 写正斜杠：jpackage 的 .cfg 把反斜杠当转义吃掉，C:\Users\x 读回来成了 C:Usersx
        else jvmArgs += "-Dpiko.home=" + File(System.getProperty("user.home"), ".piko-dev").invariantSeparatorsPath
        // 不写 %TEMP%\hsperfdata_<用户>：jps、jstat 因此看不到 Piko，jcmd 按进程号照样可用
        jvmArgs += "-XX:-UsePerfData"
        // AOT 缓存里不存机器码。JDK 25 会把训练时生成的调用适配代码与桩代码一并存进 app.aot，换一台机器
        // 也不核对 CPU 特性：CI runner 支持 AVX-512，缓存里的适配代码用了 EVEX 指令，装到不支持的 CPU
        // （例如 12 代酷睿）上随机报 EXCEPTION_ILLEGAL_INSTRUCTION，崩在 AdapterBlob。训练与运行都读这里的参数，
        // 两处一起关掉；类的加载与链接照常缓存，启动加速的大头仍在
        jvmArgs += listOf("-XX:+UnlockDiagnosticVMOptions", "-XX:-AOTAdapterCaching", "-XX:-AOTStubCaching")
        jvmArgs += linuxJvmArgs
        buildTypes.release.proguard {
            isEnabled = true
            configurationFiles.from(project.file("proguard-rules.pro"))
            // 只裁剪：优化轮次在这套依赖上耗时十几分钟，换来的体积差别很小
            optimize = false
            obfuscate = false
            joinOutputJars = true
        }
        // JDK 25 的 AOT 缓存（JEP 483/514）：打包时跑一遍训练，把启动路径上的类预先加载、链接好
        // 存进 app.aot，启动时直接映射。训练运行由 Main 在开窗后自行退出，见 AOT_TRAINING_PROPERTY。
        // macOS 不做：jpackage 建 .app 时已经签了名，训练之后才写进去的 app.aot 会破坏签名封印。
        // Linux 做：训练要开窗，CI 上套一层 xvfb-run。AppImage 每次挂载在不同的目录下，缓存照样认，
        // JDK 只比对类路径各项之间的相对位置
        if (!isMacHost) {
            buildTypes.release.aot {
                mode = AotMode.AotPrebuild
            }
        }
        nativeDistributions {
            appResourcesRootDir = layout.buildDirectory.dir("appResources")
            // Compose 默认的 jlink 模块集之外补 jdk.unsupported（sun.misc.Unsafe）与 java.sql（桌面端 sqlite-jdbc）。
            // suggestRuntimeModules 还列了 java.instrument 与 java.management，只有协程调试代理用到
            modules("jdk.unsupported", "java.sql")
            // MSI 安装器：开始菜单快捷方式、按用户安装（magnet 协议与 Toast 的 AUMID 都登记在 HKCU），
            // 并自带 JDK 25 运行时（FFM 原生调用与 AOT 缓存都要求）。
            // packageVersion 与 release tag（vMAJOR.MINOR.PATCH）对齐，CI 打包时可覆写：
            //   ./gradlew :desktopApp:packageReleaseMsi -PpikoDesktopVersion=1.2.3
            // 插件只为宿主系统能打的格式注册任务：Windows 上是 MSI，macOS 上是 DMG
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg)
            packageName = desktopPackageName
            packageVersion = desktopPackageVersion
            vendor = "NihilDigit"
            // jpackage 把它写进 exe 的 FileDescription，任务管理器与「默认应用」拿它当应用名显示，所以只写名字。
            // MSI 按 en-us 生成，数据库代码页 1252 容不下汉字，WiX 报 LGHT0311，也只能用 ASCII
            description = desktopPackageName
            copyright = "Copyright (C) NihilDigit"
            windows {
                menuGroup = desktopPackageName
                // exe/快捷方式/“添加或删除程序”图标：docs/icon.svg 渲染的多尺寸 .ico。
                // 生成命令见 desktopApp/package/windows/README.md（改 SVG 后重跑）。
                iconFile.set(project.file("package/windows/icon.ico"))
                upgradeUuid = msiUpgradeUuid
                // 按用户安装：免 UAC，装到 LocalAppData，HKCU 协议注册无需管理员权限。
                perUserInstall = true
            }
            macOS {
                bundleID = "dev.piko.desktop"
                // docs/icon.svg 按 macOS 图标网格留边后渲染，生成方式见 desktopApp/package/macos/README.md
                iconFile.set(project.file("package/macos/icon.icns"))
                // MediaMP 的 mpv 运行库以 macOS 12 为最低版本编译
                minimumSystemVersion = "12.0"
                appCategory = "public.app-category.utilities"
                // magnet: 链接与 .torrent 交给 Piko。macOS 不像 Windows 那样把它们作为启动参数传入，
                // 见 Main 的 installMacHandlers；设为默认打开方式见 MacLinkAssociation。
                // 种子类型照 Transmission 的 UTI 引入一份：没装任何 BT 客户端的机器上系统不认得 .torrent，
                // 引入之后 Piko 才出现在「打开方式」里，也才能被设为默认。Rank 取 Alternate：只声明能打开，
                // 不在安装时抢走已有客户端的默认，抢不抢由用户在设置或首次询问里定
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleURLTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleURLName</key>
                                <string>Magnet URI</string>
                                <key>CFBundleURLSchemes</key>
                                <array>
                                    <string>magnet</string>
                                </array>
                            </dict>
                        </array>
                        <key>CFBundleDocumentTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleTypeName</key>
                                <string>BitTorrent 种子文件</string>
                                <key>CFBundleTypeRole</key>
                                <string>Viewer</string>
                                <key>LSHandlerRank</key>
                                <string>Alternate</string>
                                <key>LSItemContentTypes</key>
                                <array>
                                    <string>org.bittorrent.torrent</string>
                                </array>
                            </dict>
                        </array>
                        <key>UTImportedTypeDeclarations</key>
                        <array>
                            <dict>
                                <key>UTTypeIdentifier</key>
                                <string>org.bittorrent.torrent</string>
                                <key>UTTypeDescription</key>
                                <string>BitTorrent 种子文件</string>
                                <key>UTTypeConformsTo</key>
                                <array>
                                    <string>public.data</string>
                                </array>
                                <key>UTTypeTagSpecification</key>
                                <dict>
                                    <key>public.filename-extension</key>
                                    <array>
                                        <string>torrent</string>
                                    </array>
                                    <key>public.mime-type</key>
                                    <string>application/x-bittorrent</string>
                                </dict>
                            </dict>
                        </array>
                    """.trimIndent()
                }
            }
            linux {
                // jpackage 的 app-image 放一份在 lib 下，窗口图标仍取自类路径里的 app-icon.png
                iconFile.set(project.file("src/desktopMain/resources/app-icon.png"))
            }
        }
    }
}

// WinRT FFM（Linker/SymbolLookup）属于受限方法，显式开 native-access：
// 不加现在只是 warning，未来 JDK 会直接拦截。exe 启动器的那份在上面的 application.jvmArgs。
val testdataMediaDir = rootProject.file("testdata/media").absolutePath
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    jvmArgs(linuxJvmArgs)
    // 播放冒烟读仓库里的样片，路径由这里给出，不依赖测试进程的工作目录
    systemProperty("piko.testdata", testdataMediaDir)
    // 差分还原的用例载入与安装包同一份 libzstd：release.yml 在 arm64 的打包机上也跑这些测试，验的就是那一份
    if (isWindowsHost) {
        dependsOn(bundledAppResources)
        val resourcesDir = bundledAppResourcesDir.get().asFile
        systemProperty("piko.test.zstd", resourcesDir.resolve("zstd").absolutePath)
        systemProperty("piko.test.sqlite", resourcesDir.resolve("sqlite").absolutePath)
    }
}
tasks.withType<JavaExec>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
// AOT 训练真的启动一次应用。带版本号打的包启动配置里没有 piko.home（装好后用 ~/.piko），训练进程就读写打包机上
// 真实的 ~/.piko，本机打冒烟包时它把当前账号登出了。不带版本号的 ~/.piko-dev 同样是真实数据，所以一律换成
// build 下每次清空的目录。插件不给训练单独加参数：它把启动配置备份一份、去掉运行时的 AOT 参数、追加训练参数、
// 跑完再拿备份换回。这里在它之前把一行 piko.home 加进启动配置的末尾（同名属性后写的生效），备份与训练用的都带着它，
// 换回之后再删掉，装进包里的启动配置不变
val aotTrainingHome = layout.buildDirectory.dir("aot-training-home").get().asFile
val aotTrainingHomeOption = "java-options=-Dpiko.home=" + aotTrainingHome.invariantSeparatorsPath
// jpackage 的应用目录按系统分布不同：Linux 把启动配置放在 lib/app 下，macOS 在 .app/Contents/app 下
val releaseLauncherConfig = layout.buildDirectory.file(
    "compose/binaries/main-release/app/" + when {
        isLinuxHost -> "$desktopPackageName/lib/app"
        isMacHost -> "$desktopPackageName.app/Contents/app"
        else -> "$desktopPackageName/app"
    } + "/$desktopPackageName.cfg",
).get().asFile
// AOT 缓存按类路径上 jar 的修改时间校验（秒），对不上整份作废。训练前把这些 jar 统一成同一个偶数秒的时间：
// MSI 的 cab 只存到偶数秒，不取整的话装上就差一秒。cab 存的还是不带时区的本地时间，装到与打包机不同时区的机器上
// 整体偏几个小时，这一点打包时无从避免，所以把时间记进启动配置，由应用启动时改回（ClasspathTimes.kt）
val classpathMtimeOptionPrefix = "java-options=-Dpiko.classpath-mtime="
tasks.matching { it.name == "createReleaseAotArchive" }.configureEach {
    notCompatibleWithConfigurationCache("Compose Desktop packaging/ProGuard/AOT tasks run outside Configuration Cache")
    // 配置缓存开着，doFirst/doLast 只捕获局部值，不引用脚本顶层方法或属性。
    val trainingHome = aotTrainingHome
    val trainingHomeOption = aotTrainingHomeOption
    val launcherConfig = releaseLauncherConfig
    val mtimePrefix = classpathMtimeOptionPrefix
    doFirst {
        trainingHome.deleteRecursively()
        trainingHome.mkdirs()
        val jars = launcherConfig.parentFile.listFiles { f -> f.isFile && f.extension == "jar" }.orEmpty()
        check(jars.isNotEmpty()) { "${launcherConfig.parent} 下没有 jar" }
        val stamp = jars.maxOf { it.lastModified() } / 2000 * 2000
        jars.forEach { check(it.setLastModified(stamp)) { "改不了 ${it.name} 的修改时间" } }
        val classpathMtimeOption = mtimePrefix + stamp
        // 原样保留换行符：启动配置是增量更新的补丁文件，内容变了就与没加过这一行时的构建对不上
        val text = launcherConfig.readText()
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        val all = text.split(newline).dropLastWhile { it.isEmpty() }.filter { it != trainingHomeOption }
        // 单独重跑训练时启动配置里已有上一次的时间
        val lines = all.filterNot { it.startsWith(mtimePrefix) }
        val lastOption = lines.indexOfLast { it.startsWith("java-options=") }
        check(lastOption >= 0) { "${launcherConfig.name} 里没有 java-options，jpackage 的格式可能变了" }
        val updated = lines.take(lastOption + 1) + classpathMtimeOption + trainingHomeOption + lines.drop(lastOption + 1)
        launcherConfig.writeText(updated.joinToString(newline, postfix = newline))
    }
    doLast {
        val text = launcherConfig.readText()
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.split(newline).dropLastWhile { it.isEmpty() }.filter { it != trainingHomeOption }
        launcherConfig.writeText(lines.joinToString(newline, postfix = newline))
        // 训练进程开了日志就说明它读到了这个属性；没有日志则它仍在用别处的数据目录
        check(trainingHome.resolve("logs").isDirectory) { "AOT 训练没有写进 $trainingHome，piko.home 没有生效" }
    }
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(bundledAppResources) }

// jpackage 的 MSI 先卸旧版、单独提交，再装新版：新版装失败时旧版已经没了。打完即把卸载挪进
// 安装事务，失败时整体回滚，理由见脚本。用 pwsh 跑：Windows PowerShell 按 ANSI 读无 BOM 的脚本
val transactionalUpgradeScript = file("package/windows/transactional-upgrade.ps1")
val releaseMsiDir = layout.buildDirectory.dir("compose/binaries/main-release/msi").get().asFile
tasks.matching { it.name == "packageReleaseMsi" }.configureEach {
    val scriptFile = transactionalUpgradeScript
    val msiDir = releaseMsiDir
    val identity = shellId
    doLast {
        msiDir.listFiles { f -> f.extension == "msi" }.orEmpty().forEach { msi ->
            val process = ProcessBuilder(
                "pwsh",
                "-NoProfile",
                "-NonInteractive",
                "-File",
                scriptFile.absolutePath,
                "-Msi",
                msi.absolutePath,
                "-Identity",
                identity,
            )
                .redirectErrorStream(true)
                .start()
            process.outputStream.close()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val exit = process.waitFor()
            if (output.isNotBlank()) {
                output.lineSequence().filter { it.isNotBlank() }.forEach { logger.lifecycle(it) }
            }
            check(exit == 0) { "改写 ${msi.name} 的安装序列失败（pwsh 退出码 $exit）" }
        }
    }
}

/**
 * 应用内增量更新的两个 Release 附件：应用目录里每个文件的清单（路径、大小、SHA-256、修改时间），
 * 与只含易变文件的 app.zip。实测两次构建之间只有 exe（版本资源）、jar、AOT 缓存、Piko.cfg 与
 * .jpackage.xml 不同，运行时与 mpv 等 180MB 逐字节相同，客户端据清单判断能否只换这几个。
 * 修改时间记在清单里而不是只靠 zip：zip 的时间戳按本地时区存，CI 与用户的时区不同。
 *
 * 别的文件（运行时、mpv、新加的原生库）偶尔也变。[baseManifests] 给出仍在用的已发布版本的清单，与其中任一份不同
 * 或旧版没有的文件同样标为补丁、装进 app.zip。这是老客户端唯一的路：1.1.0 及更早只认 files.json、app.zip 与 .msi，
 * 补丁对不上时便携版去找已不再发布的 .zip，只能手动更新。标在清单的 patch 上，它们认的就是这个字段。
 *
 * 另出 image.zip：整个应用目录，给本版起的客户端在补丁仍对不上时按 Range 只取不同的文件（ImageZip.kt）。
 * 不把 app.zip 扩成全量：老客户端会把 patch 之外的条目当成异常拒掉。
 */
abstract class UpdateArtifactsTask : DefaultTask() {
    @get:InputDirectory
    abstract val appImage: DirectoryProperty

    /** 旧版的 files.json，同一架构。不传时只有固定的那几类文件算补丁。 */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val baseManifests: ConfigurableFileCollection

    /**
     * 不再给补丁的已发布版本的 files.json（update-bases.sh 的 RETIRED，目前是 1.0.0）。只用来核对：
     * 它们的客户端对本版的 canPatch 必须失败，见 [checkRetired]。
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val retiredManifests: ConfigurableFileCollection

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val artifactPrefix: Property<String>

    @get:Input
    abstract val requireBaseManifests: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val baseManifestsPath: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        if (requireBaseManifests.getOrElse(false)) {
            check(!baseManifests.isEmpty) { "pikoUpdateBases=${baseManifestsPath.orNull} 下没有 *-files.json" }
        }
        val root = appImage.get().asFile
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val files = root.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath to it }
            .sortedBy { it.first }
            .toList()
        val bases = baseManifests.files.map(::hashesOf)
        val hashes = files.associate { (path, file) -> path to sha256(file) }
        val patched = files.map { it.first }.filter { path ->
            isPatch(path) || bases.any { base -> base[path] != hashes.getValue(path) }
        }.toSet()
        (patched.filterNot(::isPatch)).forEach { logger.lifecycle("补丁包另带变了的文件：$it") }
        checkRetired(hashes.filterKeys { it !in patched })
        val entries = files.map { (path, file) ->
            linkedMapOf(
                "path" to path,
                "size" to file.length(),
                "sha256" to hashes.getValue(path),
                "mtime" to file.lastModified(),
                "patch" to (path in patched),
            )
        }
        val prefix = artifactPrefix.get()
        out.resolve("$prefix-files.json").writeText(
            JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("version" to version.get(), "files" to entries))),
        )
        ZipOutputStream(out.resolve("$prefix-app.zip").outputStream().buffered()).use { zip ->
            files.filter { it.first in patched }.forEach { (path, file) ->
                zip.putNextEntry(ZipEntry(path).apply {
                    lastModifiedTime = FileTime.fromMillis(file.lastModified())
                })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        // 逐条目压缩、不固实，客户端才能按 Range 只取其中几个。修改时间不必存：客户端按清单还原
        ZipOutputStream(out.resolve("$prefix-image.zip").outputStream().buffered()).use { zip ->
            files.forEach { (path, file) ->
                zip.putNextEntry(ZipEntry(path))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * 退役版本的客户端（1.0.0）自带的 canPatch 只看清单里 patch=false 的文件：本机都有且摘要相同就走补丁，
     * 否则安装版跑 msiexec、便携版给下载页。客户端已经发出去，改不了，所以把它挡在补丁之外只能靠清单：
     * [kept] 里至少要有一个文件是它没有或与它不同的。只是不拿它作对照还不够，它与对照的版本在 app.zip 之外
     * 恰好全同时，照样会走补丁。目前靠的是 1.1.0 起才有的 app/resources/zstd/ 下的 libzstd，zstd-jni
     * 升级换了这个文件就没有了，这里让构建失败，而不是让 1.0.0 静默走回有缺陷的补丁路径。
     */
    private fun checkRetired(kept: Map<String, String>) {
        retiredManifests.files.forEach { manifest ->
            val retired = hashesOf(manifest)
            val blocking = kept.filter { (path, hash) -> retired[path] != hash }.keys
            check(blocking.isNotEmpty()) {
                "${manifest.name} 的客户端对本版仍能走补丁：补丁包之外的文件它全有且相同。它的更新脚本在 8.3 临时目录下" +
                    "静默失败、反复提示（见 shared/src/commonMain/kotlin/dev/piko/shared/update/AGENTS.md），须让补丁包之外" +
                    "留有它没有的文件"
            }
            logger.lifecycle("${manifest.name} 的客户端走不了补丁，它缺或不同的文件：${blocking.joinToString()}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun hashesOf(manifest: File): Map<String, String> =
        ((JsonSlurper().parse(manifest) as Map<String, Any>)["files"] as List<Map<String, Any>>)
            .associate { it["path"] as String to it["sha256"] as String }

    /** 每次构建都会变的文件：根目录的启动器与 app 目录下的类路径 jar、AOT 缓存和启动配置。 */
    private fun isPatch(path: String): Boolean =
        Regex("[^/]+\\.exe").matches(path) ||
            Regex("app/[^/]+\\.(jar|cfg|aot)").matches(path) ||
            path == "app/.jpackage.xml"

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

// CI 将签好的启动器放进独立的应用目录；后续 Gradle 即使重建原目录，也不会覆盖签名。
val rootProjectDir: File = rootProject.projectDir
val signedAppImage = providers.gradleProperty("pikoSignedAppImage").map { path ->
    val candidate = File(path)
    if (candidate.isAbsolute) candidate else File(rootProjectDir, path)
}
if (isWindowsHost) {
    afterEvaluate {
        tasks.named<AbstractJPackageTask>("packageReleaseMsi") {
            if (signedAppImage.isPresent) {
                val signedDir = signedAppImage.get()
                val expectedName = desktopPackageName
                // Compose 的 appImage 指向包名目录的父目录，jpackage 参数再拼上 packageName。
                appImage.set(signedDir.parentFile)
                doFirst {
                    check(signedDir.name == expectedName)
                    check(signedDir.resolve("$expectedName.exe").isFile)
                }
            }
        }
    }
}

// MSI、便携版与更新清单必须使用同一份签名后的应用目录，并保留 AOT 训练时的 jar 时间。
tasks.register<UpdateArtifactsTask>("packageReleaseUpdate") {
    dependsOn("createReleaseDistributable")
    appImage = layout.dir(signedAppImage).orElse(layout.buildDirectory.dir("compose/binaries/main-release/app/$desktopPackageName"))
    version = desktopPackageVersion
    artifactPrefix = "piko-$hostPlatform-$desktopPackageVersion"
    requireBaseManifests = false
    outputDir = layout.buildDirectory.dir("compose/binaries/main-release/update")
    // 放着旧版 files.json 的目录，CI 由 .github/scripts/update-bases.sh 取来。相对路径按仓库根目录算，与在根目录跑 gradlew
    // 时的写法一致；按本模块算的话 test.yml 传的 smoke-bases 落到 desktopApp 下，一份清单都没取到，补丁包照样打出来
    providers.gradleProperty("pikoUpdateBases").orNull?.takeIf { it.isNotBlank() }?.let { dir ->
        baseManifests.from(rootProject.fileTree(dir) { include("*-files.json") })
        requireBaseManifests = true
        baseManifestsPath = dir
    }
    providers.gradleProperty("pikoUpdateRetired").orNull?.takeIf { it.isNotBlank() }?.let { dir ->
        retiredManifests.from(rootProject.fileTree(dir) { include("*-files.json") })
    }
}
// compose 的 run 任务在 afterEvaluate 里重写 jvmArgs，会盖掉上面的配置，
// 这里后注册、后执行，把 flag 补回去（注册顺序：插件先、脚本后）。
val devHomeDir: File = File(System.getProperty("user.home"), ".piko-dev")
val keepDevInstance = providers.gradleProperty("piko.keepDevInstance").orNull == "true"

tasks.matching { it.name == "compileKotlinDesktop" }.configureEach {
    // 仅在 desktopApp 编译前尝试优雅关闭开发版实例，不跨项目配置 rootProject.allprojects 以兼容 Configuration Cache。
    val homeDir = devHomeDir
    val skipStop = keepDevInstance
    doFirst {
        if (skipStop) return@doFirst
        val root = homeDir.toPath().toAbsolutePath().normalize()
        val lockPath = root.resolve("instance.lock")
        if (!lockPath.toFile().exists()) return@doFirst
        val channel = runCatching {
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        }.getOrNull() ?: return@doFirst
        channel.use { ch ->
            val existingLock = runCatching { ch.tryLock() }.getOrNull()
            if (existingLock != null) {
                runCatching { existingLock.release() }
                return@doFirst
            }
            val socketPath = Path.of(System.getProperty("java.io.tmpdir"), "piko@${Integer.toHexString(root.toString().hashCode())}.sock")
            val sent = runCatching {
                SocketChannel.open(StandardProtocolFamily.UNIX).use { sock ->
                    sock.connect(UnixDomainSocketAddress.of(socketPath))
                    val bytes = "--dev-quit".toByteArray(Charsets.UTF_8)
                    val buf = ByteBuffer.allocate(4 + bytes.size).putInt(bytes.size).put(bytes).flip()
                    while (buf.hasRemaining()) sock.write(buf)
                }
            }.isSuccess
            if (!sent) return@doFirst
            repeat(40) {
                Thread.sleep(75)
                val acquired = runCatching { ch.tryLock() }.getOrNull()
                if (acquired != null) {
                    runCatching { acquired.release() }
                    return@doFirst
                }
            }
        }
    }
}

/**
 * 导出开发版独立启动参数文件（@argfile）与 JDK 25 可执行路径，配合 scripts/run-desktop.ps1 直接拉起桌面窗口，
 * 避免 `./gradlew :desktopApp:run` 在窗口运行期间持续占用 Gradle Daemon。
 */
abstract class WriteDevLaunchArgsTask : DefaultTask() {
    @get:Input
    abstract val javaExecutable: Property<String>

    @get:Input
    abstract val mainClass: Property<String>

    @get:Input
    abstract val jvmArgs: ListProperty<String>

    @get:Input
    abstract val devSocketPath: Property<String>

    @get:Input
    abstract val gradleUserHomePath: Property<String>

    @get:Classpath
    abstract val runtimeClasspath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val dir = outputDir.get().asFile.apply { mkdirs() }
        dir.resolve("java-exec.txt").writeText(javaExecutable.get(), Charsets.UTF_8)
        dir.resolve("dev-socket.txt").writeText(devSocketPath.get(), Charsets.UTF_8)
        val gradleHome = Path.of(gradleUserHomePath.get()).toAbsolutePath().normalize()
        val jarsDir = dir.resolve("jars").apply { mkdirs() }
        val activeSnapshots = HashSet<String>()
        // 将本仓库及复合构建产出的模块 jar 快照拷贝至 dev-launch/jars/（带时间戳与体积文件名），
        // 避免后台运行 :shared:desktopTest 等重打模块 jar 时原地覆盖正在运行的 -NoBuild 开发窗口类路径，
        // 引发懒加载类的 NoClassDefFoundError / NoSuchMethodError，同时规避 Windows 文件句柄占用冲突。
        val resolvedClasspath = runtimeClasspath.files.mapIndexed { index, file ->
            val normalized = file.toPath().toAbsolutePath().normalize()
            if (file.isFile && file.extension.equals("jar", ignoreCase = true) && !normalized.startsWith(gradleHome)) {
                val snapshotName = "%03d-%d-%d-%s".format(index, file.lastModified(), file.length(), file.name)
                val target = jarsDir.resolve(snapshotName)
                if (!target.isFile || target.length() != file.length()) {
                    file.copyTo(target, overwrite = true)
                }
                activeSnapshots.add(snapshotName)
                target
            } else {
                file
            }
        }
        jarsDir.listFiles()?.forEach { existing ->
            if (existing.name !in activeSnapshots) {
                runCatching { existing.delete() }
            }
        }
        val cp = resolvedClasspath.joinToString(File.pathSeparator) { it.absolutePath }
        val lines = buildList {
            jvmArgs.get().forEach { add(quoteArg(it)) }
            add("-cp")
            add(quoteArg(cp))
            add(mainClass.get())
        }
        dir.resolve("piko.args").writeText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }

    private fun quoteArg(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

tasks.register<WriteDevLaunchArgsTask>("prepareDevLaunch") {
    dependsOn("desktopMainClasses", "desktopJar", bundledAppResources)
    javaExecutable.set(desktopJavaLauncher.map { it.executablePath.asFile.absolutePath })
    mainClass.set("dev.piko.desktop.MainKt")
    jvmArgs.set(
        buildList {
            add("--enable-native-access=ALL-UNNAMED")
            add("-XX:+ExplicitGCInvokesConcurrent")
            addAll(linuxJvmArgs)
            add("-Dcompose.application.resources.dir=" + bundledAppResourcesDir.get().asFile.absolutePath)
            add("-Dpiko.home=" + devHomeDir.path)
            add("-Dpiko.dev-run=true")
        },
    )
    val normalizedDevRoot = devHomeDir.toPath().toAbsolutePath().normalize().toString()
    devSocketPath.set(
        Path.of(System.getProperty("java.io.tmpdir"), "piko@${Integer.toHexString(normalizedDevRoot.hashCode())}.sock").toString(),
    )
    gradleUserHomePath.set(gradle.gradleUserHomeDir.absolutePath)
    runtimeClasspath.from(tasks.named("desktopJar"))
    runtimeClasspath.from(configurations.named("desktopRuntimeClasspath"))
    outputDir.set(layout.buildDirectory.dir("dev-launch"))
}

project.afterEvaluate {
    tasks.named<JavaExec>("run") {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        jvmArgs("-XX:+ExplicitGCInvokesConcurrent")
        jvmArgs(linuxJvmArgs)
        // 开发版的数据放在 ~/.piko-dev，与安装版的 ~/.piko 分开，见 PikoHome。不放进 build 目录：clean 一次就要重新登录
        jvmArgs("-Dpiko.home=" + devHomeDir.path)
        jvmArgs("-Dpiko.dev-run=true")
    }
}

/**
 * Linux 的两个发布产物，都由 jpackage 的 app-image 得来（createReleaseDistributable）：
 * - piko-linux-x64-<版本>.tar.gz：app-image 原样打包。日后 Flathub 的 manifest 直接取它重新打包，不另建一条构建线；
 * - piko-linux-x64-<版本>.AppImage 与 .AppImage.zsync：包成 AppImage，内嵌更新信息，zsync 供差分更新。
 * 组装与打包在 package/linux/build-appimage.sh 里，本机与 CI 同一个脚本；appimagetool 与运行时按版本与摘要钉死。
 */
if (isLinuxHost) {
    tasks.register<Exec>("packageReleaseAppImage") {
        dependsOn("createReleaseDistributable")
        val appImage = layout.buildDirectory.dir("compose/binaries/main-release/app/$desktopPackageName")
        val outDir = layout.buildDirectory.dir("compose/binaries/main-release/appimage")
        inputs.dir(appImage)
        inputs.dir("package/linux")
        outputs.dir(outDir)
        commandLine(
            "bash", file("package/linux/build-appimage.sh").absolutePath,
            appImage.get().asFile.absolutePath,
            desktopPackageName,
            desktopPackageVersion,
            linuxAppId,
            "piko-$hostPlatform-$desktopPackageVersion",
            outDir.get().asFile.absolutePath,
            layout.buildDirectory.dir("appimage-tools").get().asFile.absolutePath,
        )
    }
}
