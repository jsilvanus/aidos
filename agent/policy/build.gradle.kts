plugins {
    kotlin("multiplatform")
}

// RFC-0108 Phase 2: Route policy and per-peer remote-access control.
// Kernel-free, reusable for any peer/policy system.
kotlin {
    // 17, not the agent default 21: the Engine's :aidin (Android-capable, JDK 17) depends on this module, and a
    // class file built for 21 cannot be loaded by its tests when Gradle itself runs on 21 (as in CI).
    jvmToolchain(17)
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation(project(":routes"))  // Use Phase 1 route model
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
