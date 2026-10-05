import groovy.json.JsonOutput
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import org.jetbrains.compose.desktop.application.dsl.AotMode
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractCheckNativeDistributionRuntime
import org.jetbrains.compose.desktop.application.tasks.AbstractJvmToolOperationTask
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
// MediaMP 0.5.0 的 mpv 运行库只有 linux-x64，没有 linux-arm64，Linux 只出 x64
val hostMpvRuntime = "org.openani.mediamp:mediamp-mpv-runtime-$hostPlatform:${libs.versions.mediamp.get()}"
// 等同 compose.desktop.currentOs，但版本跟界面库走，而不是跟打包插件走（两者版本不同，见 libs.versions.toml）
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
        val desktopMain by getting {
            dependencies {
                implementation(project(":shared"))
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
                // MP4 无损切片（流复制，不断点转码）：纯 JVM，无需捆绑 ffmpeg。
                implementation(libs.mp4parser.isobox)
                // PikoUploadSources.open 返回 RawSource，shared 只以 implementation 引入
                implementation(libs.kotlinx.io.core)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.junit)
                // 控件的鼠标悬停只能用真实的指针事件序列验证；版本跟界面库走，理由同 composeDesktopRuntime
                implementation("org.jetbrains.compose.ui:ui-test:${libs.versions.composeMultiplatform.get()}")
                // 测试进程没有打包好的资源目录，mpv 的原生库仍从类路径解压
                runtimeOnly(hostMpvRuntime)
            }
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
    tasks.withType<AbstractJvmToolOperationTask>().configureEach { javaHome.set(packagingJdkHome) }
    tasks.withType<AbstractProguardTask>().configureEach { javaHome.set(packagingJdkHome) }
    tasks.withType<AbstractSuggestModulesTask>().configureEach { javaHome.set(packagingJdkHome) }
    tasks.withType<AbstractCheckNativeDistributionRuntime>().configureEach { jdkHome.set(packagingJdkHome) }
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
// 这个 DLL 也是 CI 判断旧版客户端会不会用差分的依据，见 .github/scripts/delta-updates.sh
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

val bundledAppResources by tasks.registering(Sync::class) {
    from({ hostMpvRuntimeJar.map { zipTree(it) } }) {
        include("*.dll", "*.dylib", "*.txt")
        if (isLinuxHost) {
            val keep by lazy { linuxSonameFiles(hostMpvRuntimeJar.singleFile) }
            include { !it.isDirectory && it.name in keep }
        }
        into("mpv")
    }
    // 只有 Windows 的更新用 zstd 差分，别处不捆
    if (isWindowsHost) {
        from({ hostZstdJniJar.map { zipTree(it) } }) {
            include("**/*.dll")
            eachFile { path = "zstd/$name" }
            includeEmptyDirs = false
        }
    }
    // Toast 的 AUMID 登记要一个磁盘上的图标文件，exe 里内嵌的那份用不上
    from("src/desktopMain/resources/app-icon.png")
    into(layout.buildDirectory.dir("appResources/common"))
}

// 升级唯一标识：换了它，已安装版本会被当成另一个产品。切勿修改。
// 可覆写只为在本机测试 MSI 更新：另起一个产品，不碰已安装的 Piko
val msiUpgradeUuid = providers.gradleProperty("pikoDesktopUpgradeUuid").getOrElse("6d8d332e-f0f4-4ee0-bc2d-fb3ebf3d4267")
val desktopPackageName = providers.gradleProperty("pikoDesktopPackageName").getOrElse("Piko")
// 不传版本的是本地或非 tag 构建。默认值取 1.0.0 而不是 0.x：macOS 的 CFBundleVersion 首位必须大于 0，
// jpackage 直接拒绝。它与正式版可能同号，所以是不是发布构建另由 piko.release-build 标明，见 DesktopAppUpdater
val releaseVersion = providers.gradleProperty("pikoDesktopVersion").map { it.trim() }.filter { it.isNotEmpty() }
val desktopPackageVersion = releaseVersion.getOrElse("1.0.0")

// X11 窗口的 WM_CLASS 由 AWT 按主类名生成（dev-piko-desktop-MainKt），桌面环境拿它对 .desktop 文件，
// 对不上时 Dock 与任务栏认不出 Piko，显示成一个没有图标的 java 窗口。它没有公开的设置方法，
// LinuxDesktop.setWmClass 经反射改 XToolkit 的字段，要开这个包。只在 Linux 上加：别的系统的 JDK
// 没有这个包，启动时会打一行警告
val linuxJvmArgs = if (isLinuxHost) listOf("--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED") else emptyList()

compose.desktop {
    application {
        mainClass = "dev.piko.desktop.MainKt"
        // 写进 exe/.cfg 启动器：跟 run/test 的 jvmArgs 对齐，否则 FFM 受限方法告警。
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        // 应用内更新据此在 Windows Installer 的登记里认出自己是不是 MSI 装的，见 DesktopAppUpdater
        jvmArgs += "-Dpiko.upgrade-code=$msiUpgradeUuid"
        if (releaseVersion.isPresent) jvmArgs += "-Dpiko.release-build=true"
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
            // Compose 默认的 jlink 模块集之外只补 jdk.unsupported（sun.misc.Unsafe）。
            // suggestRuntimeModules 还列了 java.instrument 与 java.management，只有协程调试代理用到
            modules("jdk.unsupported")
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
tasks.withType<Test> {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    jvmArgs(linuxJvmArgs)
    // 播放冒烟读仓库里的样片，路径由这里给出，不依赖测试进程的工作目录
    systemProperty("piko.testdata", rootProject.file("testdata/media").absolutePath)
    // 差分还原的用例载入与安装包同一份 libzstd：release.yml 在 arm64 的打包机上也跑这些测试，验的就是那一份
    if (isWindowsHost) {
        dependsOn(bundledAppResources)
        systemProperty("piko.test.zstd", bundledAppResources.get().destinationDir.resolve("zstd").absolutePath)
    }
}
tasks.withType<JavaExec> {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
// AOT 缓存按 jar 的修改时间校验，差一毫秒也整份作废。MSI 的 cab 与 zip 只存到偶数秒，
// 安装后的 jar 时间被取整，缓存随之失效（实测 msiexec /a 解出的 jar 比训练时晚了两秒）。
// 训练之前先把 jar 的时间取整到偶数秒，打包前后就是同一个值
tasks.matching { it.name == "createReleaseAotArchive" }.configureEach {
    doFirst {
        layout.buildDirectory.dir("compose/binaries/main-release/app").get().asFile
            .walkTopDown()
            .filter { it.isFile && it.extension == "jar" }
            .forEach { it.setLastModified(it.lastModified() / 2000 * 2000) }
    }
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(bundledAppResources) }

// jpackage 的 MSI 先卸旧版、单独提交，再装新版：新版装失败时旧版已经没了。打完即把卸载挪进
// 安装事务，失败时整体回滚，理由见脚本。用 pwsh 跑：Windows PowerShell 按 ANSI 读无 BOM 的脚本
val transactionalUpgradeScript = file("package/windows/transactional-upgrade.ps1")
val releaseMsiDir = layout.buildDirectory.dir("compose/binaries/main-release/msi").get().asFile
tasks.matching { it.name == "packageReleaseMsi" }.configureEach {
    doLast {
        releaseMsiDir.listFiles { f -> f.extension == "msi" }.orEmpty().forEach { msi ->
            val exit = ProcessBuilder("pwsh", "-NoProfile", "-File", transactionalUpgradeScript.absolutePath, "-Msi", msi.absolutePath)
                .inheritIO()
                .start()
                .waitFor()
            check(exit == 0) { "改写 ${msi.name} 的安装序列失败（pwsh 退出码 $exit）" }
        }
    }
}

/**
 * 应用内增量更新的两个 Release 附件：应用目录里每个文件的清单（路径、大小、SHA-256、修改时间），
 * 与只含易变文件的 app.zip。实测两次构建之间只有 exe（版本资源）、jar、AOT 缓存、Piko.cfg 与
 * .jpackage.xml 不同，运行时与 mpv 等 180MB 逐字节相同，客户端据清单判断能否只换这几个。
 * 修改时间记在清单里而不是只靠 zip：zip 的时间戳按本地时区存，CI 与用户的时区不同。
 */
abstract class UpdateArtifactsTask : DefaultTask() {
    @get:InputDirectory
    abstract val appImage: DirectoryProperty

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val artifactPrefix: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val root = appImage.get().asFile
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val files = root.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath to it }
            .sortedBy { it.first }
            .toList()
        val entries = files.map { (path, file) ->
            linkedMapOf(
                "path" to path,
                "size" to file.length(),
                "sha256" to sha256(file),
                "mtime" to file.lastModified(),
                "patch" to isPatch(path),
            )
        }
        val prefix = artifactPrefix.get()
        out.resolve("$prefix-files.json").writeText(
            JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("version" to version.get(), "files" to entries))),
        )
        ZipOutputStream(out.resolve("$prefix-app.zip").outputStream().buffered()).use { zip ->
            files.filter { isPatch(it.first) }.forEach { (path, file) ->
                zip.putNextEntry(ZipEntry(path).apply {
                    lastModifiedTime = FileTime.fromMillis(file.lastModified())
                })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

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

// CI 在打出 MSI 的同一次调用里跑它：同一份应用目录，jar 的修改时间与 AOT 训练时一致
tasks.register<UpdateArtifactsTask>("packageReleaseUpdate") {
    dependsOn("createReleaseDistributable")
    appImage = layout.buildDirectory.dir("compose/binaries/main-release/app/$desktopPackageName")
    version = desktopPackageVersion
    artifactPrefix = "piko-$hostPlatform-$desktopPackageVersion"
    outputDir = layout.buildDirectory.dir("compose/binaries/main-release/update")
}
// compose 的 run 任务在 afterEvaluate 里重写 jvmArgs，会盖掉上面的配置，
// 这里后注册、后执行，把 flag 补回去（注册顺序：插件先、脚本后）。
project.afterEvaluate {
    tasks.named<JavaExec>("run") {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        jvmArgs(linuxJvmArgs)
        // 开发版的数据放在 ~/.piko-dev，与安装版的 ~/.piko 分开，见 PikoHome。不放进 build 目录：clean 一次就要重新登录
        jvmArgs("-Dpiko.home=" + File(System.getProperty("user.home"), ".piko-dev").path)
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
