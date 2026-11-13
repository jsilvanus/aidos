plugins {
    kotlin("multiplatform")
}

// RFC-0108 Phase 1: Route model and dialer fallback logic.
// Kernel-free, reusable for any discovery/dialing system.
kotlin {
    commonMain.dependencies {
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    }

    jvm {
        testFramework = org.jetbrains.kotlin.gradle.targets.jvm.JvmTestFramework.JUnit
    }

    commonTest.dependencies {
        implementation(kotlin("test"))
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    }

    jvmTest.dependencies {
        implementation(kotlin("test-junit"))
    }

    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}
