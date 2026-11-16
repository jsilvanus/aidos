plugins {
    kotlin("multiplatform")
}

kotlin {
    jvmToolchain(17)
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
                // ModelQuarantine (RFC-0022).
                implementation(project(":downloads"))

                // For file hashing and path operations
                implementation("commons-codec:commons-codec:1.16.0")

                // llama.cpp Java binding for local inference (RFC-0022, RFC-0128): the maintained
                // net.ladenthin fork of de.kherud's binding (no release since June 2025). Desktop and
                // Android share its API; Android takes it as the `llama-android` AAR instead.
                implementation("net.ladenthin:llama:6.0.0")
                // The binding's signatures carry checker-framework type annotations; Kotlin needs
                // their classes on the compile classpath to read them (its POM does not expose them).
                compileOnly("org.checkerframework:checker-qual:4.3.1")
                // Since 6.0.0 the binding jar carries no natives; each platform comes as a
                // classifier jar. CPU for every desktop target, Vulkan for Windows and Linux x64.
                // A GPU jar holds only its backend module and is used on top of the CPU one. It is
                // on the classpath unused unless a driver is present; pick or exclude backends at
                // run time with -Dnet.ladenthin.llama.backend=cpu|vulkan (RFC-0128).
                runtimeOnly("net.ladenthin:llama:6.0.0:cpu-windows-x86-64")
                runtimeOnly("net.ladenthin:llama:6.0.0:cpu-linux-x86-64")
                runtimeOnly("net.ladenthin:llama:6.0.0:cpu-linux-aarch64")
                runtimeOnly("net.ladenthin:llama:6.0.0:metal-macos-aarch64")
                runtimeOnly("net.ladenthin:llama:6.0.0:vulkan-windows-x86-64")
                runtimeOnly("net.ladenthin:llama:6.0.0:vulkan-linux-x86-64")
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
