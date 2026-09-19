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
            implementation(compose.runtime)
            api(libs.pikpak.kotlin)
            api(libs.mediamp.all)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
