import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
}

val windowsMpvRuntime = if (System.getProperty("os.arch") == "aarch64") {
    "org.openani.mediamp:mediamp-mpv-runtime-windows-arm64:${libs.versions.mediamp.get()}"
} else {
    "org.openani.mediamp:mediamp-mpv-runtime-windows-x64:${libs.versions.mediamp.get()}"
}

kotlin {
    jvm("desktop")

    // WinRT 原生集成经 kotlin-winrt 的 FFM 桥实现，java.lang.foreign 需要 JDK 22+
    //（JDK 21 上是 preview API，不带 --enable-preview 直接抛异常）。钉死 25，
    // 本地即使 Gradle 跑在 JDK 21 上，desktop 的编译/测试/运行也会走自动供给的 JDK 25。
    jvmToolchain(25)

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation(project(":ui"))
                implementation(compose.desktop.currentOs)
                implementation(libs.cmp.material.icons.extended)
                implementation(libs.mediamp.all)
                implementation(libs.winrt.runtime)
                implementation(libs.winrt.projections.windows.sdk)
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
                runtimeOnly(windowsMpvRuntime)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.junit)
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

compose.desktop {
    application {
        mainClass = "dev.piko.desktop.MainKt"
        // 写进 exe/.cfg 启动器：跟 run/test 的 jvmArgs 对齐，否则 FFM 受限方法告警。
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        nativeDistributions {
            // MSI 安装器是 Windows 原生能力地基：开始菜单快捷方式（Toast AUMID 前提）、
            // magnet: 协议注册、bundle JDK 25 运行时（WinRT FFM 要求）。
            // packageVersion 与 release tag（vMAJOR.MINOR.PATCH）对齐，CI 打包时可覆写：
            //   ./gradlew :desktopApp:packageMsi -PpikoDesktopVersion=1.2.3
            targetFormats(TargetFormat.Msi)
            packageName = "Piko"
            packageVersion = providers.gradleProperty("pikoDesktopVersion").getOrElse("0.1.0")
            vendor = "NihilDigit"
            description = "轻量、极速、现代的第三方 PikPak 跨平台客户端"
            copyright = "Copyright (C) NihilDigit"
            windows {
                menuGroup = "Piko"
                // exe/快捷方式/“添加或删除程序”图标：docs/icon.svg 渲染的多尺寸 .ico。
                // 生成命令见 desktopApp/package/windows/README.md（改 SVG 后重跑）。
                iconFile.set(project.file("package/windows/icon.ico"))
                // 升级唯一标识：换了它，已安装版本会被当成另一个产品。切勿修改。
                upgradeUuid = "6d8d332e-f0f4-4ee0-bc2d-fb3ebf3d4267"
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
// compose 的 run 任务在 afterEvaluate 里重写 jvmArgs，会盖掉上面的配置，
// 这里后注册、后执行，把 flag 补回去（注册顺序：插件先、脚本后）。
project.afterEvaluate {
    tasks.named<JavaExec>("run") {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}
