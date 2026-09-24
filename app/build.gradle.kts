plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Release signing is injected by CI or a local release shell. Keeping the
// values outside the project lets unsigned CI builds stay useful without
// putting a keystore or passwords in source control.
val releaseSigningValues = listOf(
    providers.environmentVariable("ANDROID_KEYSTORE_PATH"),
    providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD"),
    providers.environmentVariable("ANDROID_KEY_ALIAS"),
    providers.environmentVariable("ANDROID_KEY_PASSWORD"),
)
val releaseSigningConfigured = releaseSigningValues.any { it.isPresent }
require(!releaseSigningConfigured || releaseSigningValues.all { it.isPresent }) {
    "Release signing requires ANDROID_KEYSTORE_PATH, ANDROID_KEYSTORE_PASSWORD, " +
        "ANDROID_KEY_ALIAS, and ANDROID_KEY_PASSWORD together."
}
val appVersionName = providers.environmentVariable("PIKO_VERSION_NAME").orElse("0.2.1")
val appVersionCode = providers.environmentVariable("PIKO_VERSION_CODE")
    .map { it.toInt() }
    .orElse(2001)
require(appVersionCode.get() > 0) { "PIKO_VERSION_CODE must be greater than zero." }

val enableAbiSplits = providers.gradleProperty("piko.enableAbiSplits")
    .map { it.toBoolean() }
    .orElse(true)

android {
    namespace = "dev.piko"
    // Compose 1.13 的 alpha 要求 37.1
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    splits {
        abi {
            isEnable = enableAbiSplits.get()
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    defaultConfig {
        applicationId = "dev.piko"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode.get()
        versionName = appVersionName.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseSigningValues[0].get())
                storePassword = releaseSigningValues[1].get()
                keyAlias = releaseSigningValues[2].get()
                keyPassword = releaseSigningValues[3].get()
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        // 播放冒烟的样片与 generate.sh 在仓库根目录，JVM 与 Android 两边的测试共用
        getByName("androidTest").assets.srcDir(rootProject.file("testdata/media"))
    }

    packaging {
        jniLibs {
            // libmpv 自带 x86 的 .so，而 splits 只发三种 ABI，不排除就会混进通用包
            excludes += "lib/x86/**"
            // APK 从 GitHub 直接分发，没有商店的传输压缩；libav* 约 20MB 一套，
            // 压缩存放能让安装包小一半，代价是安装时解压一次
            useLegacyPackaging = true
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // 状态与业务在 shared，界面在 ui；这里只剩 Activity 入口、平台实现与播放器
    implementation(project(":shared"))
    implementation(project(":ui"))

    // PikPak SDK (KMP)
    implementation(libs.pikpak.kotlin)

    // 播放后端：预编译的 libmpv 与 FFmpeg（含 arm64/armv7/x86_64）
    implementation(libs.libmpv.android)

    // AndroidX & Lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Compose (Material 3 Expressive 1.5.0-alpha28 via BOM)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Ktor (Required by PikPak SDK compileOnly)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)

    // Kotlinx
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Coil 3
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)

    // 冒烟测试：只验证必须在真实 Android 上才能观察的行为（播放链路、前台服务、Intent 路由、
    // Keystore、FileProvider、播放器手势）
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // 冒烟里要一个真实 Activity 挂 SurfaceView；它把 ComponentActivity 声明进 debug 清单
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
