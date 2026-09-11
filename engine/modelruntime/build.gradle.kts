plugins {
    kotlin("multiplatform")
}

kotlin {
    jvmToolchain(21)
    jvm()

    sourceSets {
        commonMain.dependencies {
            // TODO(RFC-0103): This dependency on :kernel violates RFC-0103's requirement.
            // Refactor during RFC-0103 implementation.
            implementation(project(":kernel"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }

        val jvmMain by getting {
            dependencies {
                implementation(project(":cookbook"))

                // For file hashing and path operations
                implementation("commons-codec:commons-codec:1.16.0")

                // llama.cpp Java binding for local inference (RFC-0022, M21). The coordinate this
                // pointed at (`de.kherud:llama-java:0.3.2`) never existed on any repository this
                // build can reach (confirmed against Maven Central's own index: the real artifact
                // is `de.kherud:llama`, versioned from 1.0.0, with no `0.3.2` release ever
                // published) -- `LlamaCppAdapter.kt` was written against a fictional API shape
                // (wrong package, wrong method names) that happened to resemble a real one.
                // Keep JVM and Android on the same current llama.cpp binding. This gives desktop
                // inference the same GGUF compatibility and request-oriented streaming API as
                // the Android engine, rather than freezing desktop on the older 2.x native core.
                implementation("de.kherud:llama:4.2.0")
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            }
        }
    }
}
