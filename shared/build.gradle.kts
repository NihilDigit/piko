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
            version = release(37)
        }
    }
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            // state holder 的公开 API 直接暴露 Compose 的 State，消费方要拿得到这些类型
            api(compose.runtime)
            api(libs.pikpak.kotlin)
            api(libs.mediamp.all)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
