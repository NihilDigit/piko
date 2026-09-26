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
        all {
            // BackHandler 在 CMP 里仍标着实验性，两端的返回（系统手势与 Esc）都靠它
            languageSettings.optIn("androidx.compose.ui.ExperimentalComposeUiApi")
            // 桌面端的 material3 停在 1.12.0-alpha03，其中不少 API 仍标着实验性，而 Android 端
            // 的 BOM 版本里已经转正；在模块级统一选择加入，免得同一行代码两端要求不同
            languageSettings.optIn("androidx.compose.material3.ExperimentalMaterial3Api")
            languageSettings.optIn("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        }
        commonMain.dependencies {
            api(project(":shared"))
            api(libs.cmp.runtime)
            api(libs.cmp.foundation)
            api(libs.cmp.ui)
            api(libs.cmp.material3)
            // 桌面端由 Esc 触发返回，Android 端接系统返回手势
            implementation(libs.cmp.ui.backhandler)
            implementation(libs.cmp.material3.adaptive.navigation.suite)
            implementation(libs.cmp.adaptive)
            implementation(libs.cmp.material.icons.extended)
            // 导出日志的文件名与抬头要本地时间
            implementation(libs.kotlinx.datetime)
            // Screen 继承 NavKey，Android 入口把播放器页交进来时要看得到它
            api(libs.androidx.navigation3.runtime)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.coil.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        // CMP 的 material3 在 Android 上重定向到 androidx 的构件，版本比 app 用的 BOM 旧；
        // 这里对齐到同一份 BOM，ui 模块编译时看到的就是 app 运行时的那套 API
        androidMain.dependencies {
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
        }
    }
}
