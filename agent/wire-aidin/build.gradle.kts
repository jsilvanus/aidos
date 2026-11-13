plugins {
    kotlin("multiplatform")
}

// RFC-0108 Phase 1: AIDIN wire format extension for route hints.
// Adapts routes to/from PairingInvite wire protocol.
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
