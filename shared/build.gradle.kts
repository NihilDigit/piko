plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    android {
        namespace = "dev.piko.shared"
        compileSdk {
            version = release(37) { minorApiLevel = 1 }
        }
    }
    jvm("desktop")

    // 两个目标都是 JVM：用得到 java.net 的代码（代理选择）放在 jvmShared，只写一份
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
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // 冒烟测试用 MockEngine 顶替 PikPak 的 API 与 CDN，SDK 的请求、鉴权与解析仍走真实代码
        val desktopTest by getting {
            dependencies {
                implementation(libs.ktor.client.mock)
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}
