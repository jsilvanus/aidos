plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

kotlin {
    jvmToolchain(21)
    jvm()
    androidTarget()

    sourceSets {
        commonMain.dependencies {
            api(project(":cookbook"))
            api(project(":huggingface"))
            // TODO(RFC-0103): This dependency on :kernel violates RFC-0103's requirement.
            // Refactor during RFC-0103 implementation.
            api(project(":kernel"))
            api(project(":downloads"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
            api("app.cash.sqldelight:runtime:2.0.2")
        }

        androidMain.dependencies {
            api("app.cash.sqldelight:android-driver:2.0.2")
        }

        jvmMain.dependencies {
            api("app.cash.sqldelight:sqlite-driver:2.0.2")
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "dev.aidos.models"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
}
