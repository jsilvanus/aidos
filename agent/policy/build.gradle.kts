plugins {
    kotlin("multiplatform")
}

// RFC-0108 Phase 2: Route policy and per-peer remote-access control.
// Kernel-free, reusable for any peer/policy system.
kotlin {
    commonMain.dependencies {
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        implementation(project(":routes"))  // Use Phase 1 route model
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
