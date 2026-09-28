plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("com.android.library")
}

/**
 * Stateless Compose UI shared by the Android app and the Windows debug app (RFC-0103).
 *
 * Panes take UI state and callbacks only; each app keeps its own ViewModels and host wiring.
 *
 * Compose Multiplatform is pinned to 1.7.3 because its Android variants resolve to the androidx
 * versions :androidapp already uses (compose 1.7.x, material3 1.3.1), so sharing this module
 * does not silently upgrade the Android UI. Libraries are declared by coordinate rather than via
 * the org.jetbrains.compose Gradle plugin, which a library module does not need.
 */
val composeMultiplatformVersion = "1.7.3"

kotlin {
    jvmToolchain(17)
    jvm()
    androidTarget()

    applyDefaultHierarchyTemplate()

    sourceSets {
        // Not commonMain: the panes use java.time and String.format, and both targets are JVMs.
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                api(project(":enginehost"))
                api("org.jetbrains.compose.runtime:runtime:$composeMultiplatformVersion")
                api("org.jetbrains.compose.foundation:foundation:$composeMultiplatformVersion")
                api("org.jetbrains.compose.material3:material3:$composeMultiplatformVersion")
                api("org.jetbrains.compose.material:material-icons-extended:$composeMultiplatformVersion")
            }
        }

        androidMain.get().dependsOn(jvmAndAndroidMain)
        jvmMain.get().dependsOn(jvmAndAndroidMain)
    }
}

android {
    namespace = "fi.italeino.aidos.engine.ui"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
