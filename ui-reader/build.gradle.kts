plugins {
    id("piko.kmp.ui")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(project(":ui-core"))
            implementation(libs.zoomimage.compose.coil3)
        }
    }
}
