plugins {
    kotlin("multiplatform")
}

// RFC-0108 Phase 3: Engine adapter layer for the standalone sshtunnel library.
// Bridges the tunnel library with Engine services: vault KeyProvider, egress grants,
// audit logging, remote-access switch. Depends on Agent modules but not kernel directly
// (vault provides kernel bridge).
kotlin {
    jvm()

    sourceSets {
        val jvmMain by getting {
            dependencies {
                api(project(":ssh-tunnel"))
                api(project(":vault"))

                implementation(project(":capability"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            }
        }
    }

    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}
