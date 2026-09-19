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

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation(compose.desktop.currentOs)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(libs.fluent.ui)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.ktor.client.logging)
                implementation(libs.coil.compose)
                implementation(libs.coil.network.okhttp)
                runtimeOnly(windowsMpvRuntime)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "dev.piko.desktop.MainKt"
    }
}
