plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    jvm()
    // androidTarget() is added with the Android app (RFC-0099 Phase 4).
    // Common code compiling is what this module exists to prove.

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
