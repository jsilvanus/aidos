plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    kotlin("plugin.compose")
    id("com.android.library")
}

val composeMultiplatformVersion = "1.7.3"

kotlin {
    jvm()
    androidTarget()

    sourceSets {
        commonMain.dependencies {
            api(project(":kernel"))
            api(project(":intent"))
            api(project(":api"))
            api("dev.aidos.tcr:aidos-tcr:0.1.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

            api("org.jetbrains.compose.runtime:runtime:$composeMultiplatformVersion")
            api("org.jetbrains.compose.foundation:foundation:$composeMultiplatformVersion")
            api("org.jetbrains.compose.material3:material3:$composeMultiplatformVersion")
            api("org.jetbrains.compose.material:material-icons-extended:$composeMultiplatformVersion")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                // RFC-0124 UI-60: Compose UI smoke tests run headless on the JVM (runComposeUiTest).
                // Explicit coordinates: ui-common does not apply the Compose Gradle plugin, so the
                // `compose.uiTest` accessor aidos-tcr uses is not available here.
                implementation("org.jetbrains.compose.ui:ui-test-junit4:$composeMultiplatformVersion")
                implementation("org.jetbrains.compose.desktop:desktop-jvm-linux-x64:$composeMultiplatformVersion")
            }
        }
    }
}

android {
    namespace = "dev.aidos.ui"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
