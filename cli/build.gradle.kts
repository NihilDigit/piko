// 开发用的命令行工具：列网盘目录存快照、离线跑解析流水线。不随应用发布。
// ./gradlew :cli:installDist 后执行 cli/build/install/piko-cli/bin/piko-cli，免去每次经过 Gradle
plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.serialization)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    // 统一使用 JDK 25 工具链避免拉起第二套 Kotlin Daemon 争抢内存，同时保持 JVM 21 字节码兼容
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

application {
    applicationName = "piko-cli"
    mainClass = "dev.piko.cli.MainKt"
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.core)
    runtimeOnly("org.jetbrains.kotlinx:kotlinx-coroutines-swing:${libs.versions.kotlinxCoroutines.get()}")
    implementation(libs.kotlinx.serialization.json)
    // SDK 只把 Ktor 声明为 runtime 依赖且不带引擎，与桌面端用同一个
    implementation(libs.ktor.client.okhttp)
}

// org.jetbrains.compose.runtime:runtime-desktop 只是转发到 androidx 的空壳（只有 manifest 与 LICENSE），
// 却与 androidx 那个真正的 jar 同名，installDist 往 lib/ 里拷时过滤掉空壳以保留传递依赖的真实 jar
tasks.named<Sync>("installDist") {
    eachFile {
        if (name.startsWith("runtime-desktop") && file.length() < 16384) exclude()
    }
}
