// 开发用的命令行工具：列网盘目录存快照、离线跑解析流水线。不随应用发布。
// ./gradlew :cli:installDist 后执行 cli/build/install/piko-cli/bin/piko-cli，免去每次经过 Gradle
plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    // 与 shared 的字节码版本一致，本机默认的 JDK 21 即可运行
    jvmToolchain(21)
}

application {
    applicationName = "piko-cli"
    mainClass = "dev.piko.cli.MainKt"
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // SDK 只把 Ktor 声明为 runtime 依赖且不带引擎，与桌面端用同一个
    implementation(libs.ktor.client.okhttp)
}

// org.jetbrains.compose.runtime:runtime-desktop 只是转发到 androidx 的空壳（只有 manifest 与 LICENSE），
// 却与 androidx 那个真正的 jar 同名，installDist 往 lib/ 里拷时撞名
configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.compose.runtime", module = "runtime-desktop")
}
