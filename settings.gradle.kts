pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "piko"
// 本地默认纯桌面模式（跳过 :app 与各 KMP 模块的 Android target 注册）；
// 遇到 :app:* / Android / Debug / assembleRelease / bundleRelease 任务、Android Studio 同步、CI=true
// 或显式传 -Ppiko.enableAndroid=true 时自动开启 Android。
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
    include(":app")
}
include(":shared")
include(":ui-core")
include(":ui-drive")
include(":ui-player")
include(":ui-settings")
include(":ui-transfers")
include(":ui-reader")
include(":ui")
include(":desktopApp")
include(":cli")
include(":shots")

// 与 SDK 联调：local.properties 里写 pikpak.sdk.dir=../pikpak-kotlin，依赖里的 pikpak-kotlin 就换成那个目录的
// 源码构建，改了 SDK 下次编译即生效。local.properties 不进仓库，CI 与别的机器照常取 libs.versions.toml 里的
// 正式版本，所以联调不必发版，也不必临时指向 mavenLocal 再记得改回来
val localProperties = java.util.Properties().apply {
    val file = file("local.properties")
    if (file.isFile) file.inputStream().use(::load)
}
localProperties.getProperty("pikpak.sdk.dir")?.let { sdkDir ->
    includeBuild(sdkDir) {
        dependencySubstitution {
            substitute(module("io.github.nihildigit:pikpak-kotlin")).using(project(":"))
        }
    }
}
// 同理联调触摸桥：windowstouch.dir=../compose-windows-touch
localProperties.getProperty("windowstouch.dir")?.let { dir ->
    includeBuild(dir) {
        dependencySubstitution {
            substitute(module("dev.nihildigit:compose-windows-touch")).using(project(":"))
        }
    }
}
