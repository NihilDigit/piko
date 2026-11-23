import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

compose {
    dependencyCompatibility {
        // 依赖版本已由 libs.versions.toml 统一锁定，跳过每次构建的兼容检查任务以提速。
        enabled = false
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf"))
}

val enableAndroid: Boolean = providers.gradleProperty("piko.enableAndroid").map { it.toBoolean() }.orNull
    ?: providers.gradleProperty("piko.desktopOnly").map { !it.toBoolean() }.orNull
    ?: (
        providers.environmentVariable("CI").orNull.equals("true", ignoreCase = true) ||
            providers.gradleProperty("android.injected.invoked.from.ide").orNull.equals("true", ignoreCase = true) ||
            gradle.startParameter.taskNames.any {
                it.startsWith(":app:") || it == ":app" ||
                    it.contains("Android", ignoreCase = true) ||
                    it.contains("Debug", ignoreCase = true) ||
                    it.contains("assembleRelease", ignoreCase = true) ||
                    it.contains("bundleRelease", ignoreCase = true)
            }
        )
if (enableAndroid) {
    pluginManager.apply("com.android.kotlin.multiplatform.library")
}
val enableIosTargets = providers.gradleProperty("piko.enableIos")
    .map { it.toBoolean() }
    .orElse(providers.systemProperty("os.name").map { it.startsWith("Mac", ignoreCase = true) })
    .get()

val defaultNamespace = if (project.name == "ui") {
    "dev.piko.ui"
} else {
    "dev.piko.ui.${project.name.removePrefix("ui-").replace('-', '.')}"
}
val enableAndroidResources = project.name == "ui-core"

val hostOs = providers.systemProperty("os.name").map {
    when {
        it.startsWith("Windows") -> "windows"
        it.startsWith("Mac") -> "macos"
        else -> "linux"
    }
}.get()
val hostArch = providers.systemProperty("os.arch").map {
    if (it == "aarch64") "arm64" else "x64"
}.get()
val cmpVersion = libs.findVersion("composeMultiplatform").get().requiredVersion

kotlin {
    if (enableAndroid) {
        (this as org.gradle.api.plugins.ExtensionAware).extensions.configure<com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget>("android") {
            namespace = defaultNamespace
            compileSdk {
                version = release(37) { minorApiLevel = 1 }
            }
            minSdk = 26
            androidResources {
                enable = enableAndroidResources
            }
        }
    }
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
    if (enableIosTargets) {
        listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
            target.binaries.configureEach {
                freeCompilerArgs += "-Xoverride-konan-properties=minVersion.ios=15.0"
            }
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("androidx.compose.ui.ExperimentalComposeUiApi")
            languageSettings.optIn("androidx.compose.material3.ExperimentalMaterial3Api")
            languageSettings.optIn("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        }
        commonMain.dependencies {
            implementation(libs.findLibrary("cmp-runtime").get())
            implementation(libs.findLibrary("cmp-foundation").get())
            implementation(libs.findLibrary("cmp-ui").get())
            implementation(libs.findLibrary("cmp-material3").get())
            implementation(libs.findLibrary("cmp-adaptive").get())
            implementation(libs.findLibrary("cmp-ui-backhandler").get())
            implementation(libs.findLibrary("cmp-material-icons-extended").get())
            implementation(libs.findLibrary("androidx-lifecycle-runtime-compose").get())
            implementation(libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())
            implementation(libs.findLibrary("coil-compose").get())
            implementation(libs.findLibrary("kotlinx-datetime").get())
            implementation(libs.findLibrary("kotlinx-coroutines-core").get())
            implementation(libs.findLibrary("kotlinx-serialization-json").get())
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        if (enableAndroid) {
            getByName("androidMain").dependencies {
                implementation(project.dependencies.platform(libs.findLibrary("androidx-compose-bom").get()))
            }
        }
        getByName("desktopTest").dependencies {
            implementation(kotlin("test"))
            runtimeOnly("org.jetbrains.compose.desktop:desktop-jvm-$hostOs-$hostArch:$cmpVersion")
        }
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
    maxHeapSize = "512m"
    jvmArgs("-XX:TieredStopAtLevel=1", "-XX:+UseParallelGC")
}
