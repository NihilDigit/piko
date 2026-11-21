plugins {
    id("piko.kmp.ui")
}

compose {
    resources {
        generateResClass = always
        publicResClass = true
        packageOfResClass = "dev.piko.ui.resources"
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.cmp.components.resources)
            api(project(":shared"))
            api(libs.cmp.runtime)
            api(libs.cmp.foundation)
            api(libs.cmp.ui)
            api(libs.cmp.material3)
            api(libs.androidx.navigation3.runtime)
        }
    }
}
