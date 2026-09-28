pluginManagement {
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
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
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
include(":app")
include(":shared")
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
