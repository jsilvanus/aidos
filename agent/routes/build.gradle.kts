plugins {
    kotlin("multiplatform")
}

// RFC-0108 Phase 1: Route model and dialer fallback logic.
// Kernel-free, reusable for any discovery/dialing system.
kotlin {
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }

    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}
