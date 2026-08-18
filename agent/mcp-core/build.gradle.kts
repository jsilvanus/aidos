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
            // Official MCP client implementation. mcp-core remains the reusable Aidos/Dictator
            // boundary; protocol, negotiation, request dispatch and MCP types belong to the SDK.
            implementation("io.modelcontextprotocol:kotlin-sdk-client:0.15.0")
        }

        val jvmMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-core:3.5.1")
                implementation("io.ktor:ktor-client-cio:3.5.1")
                implementation("io.ktor:ktor-client-content-negotiation:3.5.1")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.1")
                implementation("io.ktor:ktor-client-sse:3.5.1")
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

    // The module this was split out of did not set this, so the split inherited "off". Turned on
    // deliberately: RFC-0031's "Implementation Layering" exists so this module can be consumed
    // outside this repo, and a warning left standing here becomes a downstream consumer's problem
    // on a compiler version we do not control. Matches the 15 modules that already set it.
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}
