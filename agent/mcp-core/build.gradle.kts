plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }

        val jvmMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-core:2.3.12")
                implementation("io.ktor:ktor-client-cio:2.3.12")
                implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
                implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
                // HTTP mock
                implementation("io.ktor:ktor-client-mock:2.3.12")
            }
        }
    }

    // The module this was split out of did not set this, so the split inherited "off". Turned on
    // deliberately: RFC-0031's "Implementation Layering" exists so this module can be consumed
    // outside this repo, and a warning left standing here becomes a downstream consumer's problem
    // on a compiler version we do not control. Matches the 15 modules that already set it.
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}
