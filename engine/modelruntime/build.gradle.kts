plugins {
    kotlin("multiplatform")
}

kotlin {
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
                // `2.3.5` is the closest real version to what the adapter already assumed: same
                // `LlamaModel(String, ModelParameters)` constructor shape, same setter names for
                // everything except two the real 2.3.x API spells differently (`setNBbatch`, a
                // real typo in the library itself, not this build's; `setUseMLock`).
                implementation("de.kherud:llama:2.3.5")
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
