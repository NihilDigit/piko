plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// 两端共用的 Material 3 Expressive 界面。与 shared 分开：shared 只放状态与业务，
// 冒烟测试编译它时不必带上整套 material3 与图标库。
kotlin {
    android {
        namespace = "dev.piko.ui"
        compileSdk {
            version = release(37) { minorApiLevel = 1 }
        }
        minSdk = 26
    }
    jvm("desktop")

    sourceSets {
        // BackHandler 在 CMP 里仍标着实验性，两端的返回（系统手势与 Esc）都靠它
        all { languageSettings.optIn("androidx.compose.ui.ExperimentalComposeUiApi") }
        commonMain.dependencies {
            api(project(":shared"))
            api(compose.runtime)
            api(compose.foundation)
            api(compose.ui)
            api(libs.cmp.material3)
            // 桌面端由 Esc 触发返回，Android 端接系统返回手势
            implementation(libs.cmp.ui.backhandler)
            implementation(libs.cmp.material3.adaptive.navigation.suite)
            implementation(libs.cmp.adaptive)
            implementation(libs.cmp.material.icons.extended)
            // Screen 继承 NavKey，Android 入口把播放器页交进来时要看得到它
            api(libs.androidx.navigation3.runtime)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.coil.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
