import groovy.json.JsonOutput
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import org.jetbrains.compose.desktop.application.dsl.AotMode
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
}

val windowsArch = if (System.getProperty("os.arch") == "aarch64") "arm64" else "x64"
val windowsMpvRuntime = "org.openani.mediamp:mediamp-mpv-runtime-windows-$windowsArch:${libs.versions.mediamp.get()}"
// 等同 compose.desktop.currentOs，但版本跟界面库走，而不是跟打包插件走（两者版本不同，见 libs.versions.toml）
val composeDesktopRuntime = "org.jetbrains.compose.desktop:desktop-jvm-windows-$windowsArch:${libs.versions.composeMultiplatform.get()}"

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
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.junit)
                // 控件的鼠标悬停只能用真实的指针事件序列验证；版本跟界面库走，理由同 composeDesktopRuntime
                implementation("org.jetbrains.compose.ui:ui-test:${libs.versions.composeMultiplatform.get()}")
                // 测试进程没有打包好的资源目录，mpv 的 DLL 仍从类路径解压
                runtimeOnly(windowsMpvRuntime)
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

// mpv 与 FFmpeg 的 DLL 解开放进应用资源目录。mediamp 默认在每次启动后首次播放时把近 40MB 的 DLL
// 从 jar 解压到新建的临时目录，DLL 被进程占用，deleteOnExit 删不掉，每次运行都在 %TEMP% 留一份。
// 改为安装时就位，启动时经 MpvMediampPlayer.prepareLibraries 指过去，不再解压
val windowsMpvRuntimeJar = configurations.detachedConfiguration(dependencies.create(windowsMpvRuntime)).apply {
    isTransitive = false
}
val bundledAppResources by tasks.registering(Sync::class) {
    from({ windowsMpvRuntimeJar.map { zipTree(it) } }) {
        include("*.dll", "*.txt")
        into("mpv")
    }
    // Toast 的 AUMID 登记要一个磁盘上的图标文件，exe 里内嵌的那份用不上
    from("src/desktopMain/resources/app-icon.png")
    into(layout.buildDirectory.dir("appResources/common"))
}

// 升级唯一标识：换了它，已安装版本会被当成另一个产品。切勿修改。
// 可覆写只为在本机测试 MSI 更新：另起一个产品，不碰已安装的 Piko
val msiUpgradeUuid = providers.gradleProperty("pikoDesktopUpgradeUuid").getOrElse("6d8d332e-f0f4-4ee0-bc2d-fb3ebf3d4267")
val desktopPackageName = providers.gradleProperty("pikoDesktopPackageName").getOrElse("Piko")
val desktopPackageVersion = providers.gradleProperty("pikoDesktopVersion").getOrElse("0.1.0")

compose.desktop {
    application {
        mainClass = "dev.piko.desktop.MainKt"
        // 写进 exe/.cfg 启动器：跟 run/test 的 jvmArgs 对齐，否则 FFM 受限方法告警。
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        // 应用内更新据此在 Windows Installer 的登记里认出自己是不是 MSI 装的，见 DesktopAppUpdater
        jvmArgs += "-Dpiko.upgrade-code=$msiUpgradeUuid"
        buildTypes.release.proguard {
            isEnabled = true
            configurationFiles.from(project.file("proguard-rules.pro"))
            // 只裁剪：优化轮次在这套依赖上耗时十几分钟，换来的体积差别很小
            optimize = false
            obfuscate = false
            joinOutputJars = true
        }
        // JDK 25 的 AOT 缓存（JEP 483/514）：打包时跑一遍训练，把启动路径上的类预先加载、链接好
        // 存进 app.aot，启动时直接映射。训练运行由 Main 在开窗后自行退出，见 AOT_TRAINING_PROPERTY
        buildTypes.release.aot {
            mode = AotMode.AotPrebuild
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
            targetFormats(TargetFormat.Msi)
            packageName = desktopPackageName
            packageVersion = desktopPackageVersion
            vendor = "NihilDigit"
            // MSI 按 en-us 生成，数据库代码页 1252 容不下汉字，WiX 报 LGHT0311；描述只能用 ASCII
            description = "Lightweight, modern PikPak client"
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
        }
    }
}

// WinRT FFM（Linker/SymbolLookup）属于受限方法，显式开 native-access：
// 不加现在只是 warning，未来 JDK 会直接拦截。exe 启动器的那份在上面的 application.jvmArgs。
tasks.withType<Test> {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // 播放冒烟读仓库里的样片，路径由这里给出，不依赖测试进程的工作目录
    systemProperty("piko.testdata", rootProject.file("testdata/media").absolutePath)
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
    artifactPrefix = "piko-windows-$windowsArch-$desktopPackageVersion"
    outputDir = layout.buildDirectory.dir("compose/binaries/main-release/update")
}
// compose 的 run 任务在 afterEvaluate 里重写 jvmArgs，会盖掉上面的配置，
// 这里后注册、后执行，把 flag 补回去（注册顺序：插件先、脚本后）。
project.afterEvaluate {
    tasks.named<JavaExec>("run") {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}
