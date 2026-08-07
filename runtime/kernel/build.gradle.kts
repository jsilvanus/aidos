plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    // ---------------------------------------------------------------------------
    // androidTarget() is ready to wire now that gitsema-kotlin ships it.
    // The com.android.library plugin (AGP 8.5.2) must be added here, and
    // dl.google.com must be reachable from the build environment.
    // See build.gradle.kts at root; uncomment to activate.
    // ---------------------------------------------------------------------------
    // id("com.android.library")
}

kotlin {
    jvm()
    // androidTarget() — uncomment together with the plugin above.

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }
    }

    compilerOptions {
        // The kernel is a contract. Warnings here are design errors.
        allWarningsAsErrors.set(true)
    }
}

// android { ... } — uncomment when androidTarget() is wired above.
