// 开发用的截图工具：拿假的 PikPak 服务端与真实的界面、状态与平台实现，无头渲染桌面端任意窗口尺寸、
// 深浅主题下的页面，存成 PNG。改布局后对照看，不必开真实账号。不随应用发布。
// ./gradlew :shots:run --args="all" 出一整套；用法见 src/main/kotlin/dev/piko/shots/Main.kt
plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    // desktopApp 以 25 编译（FFM），这里要读它的类
    jvmToolchain(25)
}

// Skia 的原生库按宿主取。与 desktopApp 不同，这里也要能在 Linux 上跑：没有显示器的机器（CI、云端容器）
// 正是最需要看截图的地方
val hostOs = System.getProperty("os.name").let {
    when {
        it.startsWith("Mac") -> "macos"
        it.startsWith("Windows") -> "windows"
        else -> "linux"
    }
}
val hostArch = if (System.getProperty("os.arch") == "aarch64") "arm64" else "x64"

application {
    applicationName = "piko-shots"
    mainClass = "dev.piko.shots.MainKt"
    // 无头渲染；已下载文件的缩略图由 Coil 解码，默认堆太小
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true", "-Xmx1g", "--enable-native-access=ALL-UNNAMED")
}

dependencies {
    implementation(project(":desktopApp"))
    implementation(project(":ui"))
    implementation(project(":shared"))
    implementation("org.jetbrains.compose.desktop:desktop-jvm-$hostOs-$hostArch:${libs.versions.composeMultiplatform.get()}")
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.cmp.material.icons.extended)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.okhttp)
}

// Gradle 跑在 JDK 21 上时 run 默认也用它，加载 25 编出的类即失败；理由同 desktopApp 的 run
tasks.named<JavaExec>("run") {
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    // 相对路径的输出目录落在仓库根下的 build/shots，与从哪个目录调用 Gradle 无关
    workingDir = rootProject.projectDir
}

// 同 :cli：runtime-desktop 是转发到 androidx 的空壳，与真正的 jar 同名，installDist 往 lib/ 里拷时撞名
configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.compose.runtime", module = "runtime-desktop")
}
