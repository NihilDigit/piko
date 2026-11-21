plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

val enableIosTargets = providers.gradleProperty("piko.enableIos")
    .map { it.toBoolean() }
    .orElse(providers.systemProperty("os.name").map { it.startsWith("Mac", ignoreCase = true) })
    .get()

kotlin {
    android {
        namespace = "dev.piko.shared"
        compileSdk {
            version = release(37) { minorApiLevel = 1 }
        }
        minSdk = 26
    }
    jvm("desktop")
    if (enableIosTargets) {
        listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
            target.binaries.configureEach {
                freeCompilerArgs += "-Xoverride-konan-properties=minVersion.ios=15.0"
            }
            target.compilations.getByName("main").cinterops.create("pikoSqlite") {
                definitionFile.set(project.file("src/nativeInterop/cinterop/pikoSqlite.def"))
            }
        }
    }

    // Android 与 Desktop 都是 JVM：用得到 java.net 的代码（代理选择）放在 jvmShared，只写一份
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withCompilations {
                    it.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm ||
                        it.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            // 下载任务表以 JSON 存入偏好。SDK 以 implementation 声明它，不会传递过来
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
            // PikPakClient 的构造参数里有 HttpClient，SDK 却只把 Ktor 声明为 runtime 依赖
            implementation(libs.ktor.client.core)
            // state holder 的公开 API 直接暴露 Compose 的 State，消费方要拿得到这些类型
            api(libs.cmp.runtime)
            api(libs.pikpak.kotlin)
            // 本机回环代理的服务端：只需 GET/HEAD 加 Range，原始 socket 足够
            implementation(libs.ktor.network)
            // 纯 Kotlin Multiplatform Markdown AST 解析器（GFM 表格、删除线、任务列表、自动链接）
            implementation(libs.jetbrains.markdown)
        }
        getByName("desktopMain").dependencies {
            implementation(libs.sqlite.jdbc)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
        }
        if (enableIosTargets) {
            iosMain.dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
        // 冒烟测试用 MockEngine 顶替 PikPak 的 API 与 CDN，SDK 的请求、鉴权与解析仍走真实代码
        getByName("desktopTest").dependencies {
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.serialization.json)
            // 只给本机手动跑的 MetaTube 联调（MetaTubeLiveProbe）用，与两端传入的引擎相同
            implementation(libs.ktor.client.okhttp)
        }
    }
}

compose {
    dependencyCompatibility {
        // 依赖版本已由 libs.versions.toml 统一锁定，跳过每次构建的兼容检查任务以提速。
        enabled = false
    }
}

if (providers.gradleProperty("pikoTestLowHeap").isPresent) {
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        maxHeapSize = "256m"
        systemProperty("piko.test.lowheap", "true")
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf"))
}
