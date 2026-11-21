plugins {
    id("piko.kmp.ui")
}

val enableIosTargets = providers.gradleProperty("piko.enableIos")
    .map { it.toBoolean() }
    .orElse(providers.systemProperty("os.name").map { it.startsWith("Mac", ignoreCase = true) })
    .get()

// 共享界面的聚合入口与工作台外壳；领域界面按职责拆分至 :ui-core 与五个并行叶模块。
// iOS 静态框架 PikoKit 仍由本模块导出，Xcode 工程配置保持不变。
kotlin {
    if (enableIosTargets) {
        listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
            target.binaries.framework {
                baseName = "PikoKit"
                isStatic = true
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":ui-core"))
            implementation(project(":ui-drive"))
            implementation(project(":ui-player"))
            implementation(project(":ui-settings"))
            implementation(project(":ui-transfers"))
            implementation(project(":ui-reader"))
            implementation(libs.cmp.material3.adaptive.navigation.suite)
            implementation(libs.cmp.navigation3.ui)
            implementation(libs.cmp.adaptive.navigation3)
            api(libs.androidx.navigation3.runtime)
        }
        if (enableIosTargets) {
            iosMain.dependencies {
                implementation(libs.ktor.client.core)
                implementation(libs.coil.network.ktor3)
            }
        }
        androidMain.dependencies {
            implementation(libs.androidx.navigation3.ui)
            implementation(libs.androidx.adaptive.navigation3)
        }
    }
}
