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
            implementation("io.modelcontextprotocol:kotlin-sdk-client:0.15.0")
        }

        val jvmMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-core:3.5.1")
                implementation("io.ktor:ktor-client-cio:3.5.1")
                implementation("io.ktor:ktor-client-content-negotiation:3.5.1")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.1")
                implementation("io.ktor:ktor-sse:3.5.1")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
                implementation("io.ktor:ktor-client-mock:3.5.1")
            }
        }
    }

    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}
