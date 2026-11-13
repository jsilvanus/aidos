plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    jvm()
    // android() target deferred to Phase 4: waiting for Google Maven access in CI environment
    // Android Keystore implementation is ready in src/androidMain, ready to activate when plugin available

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }

        val jvmMain by getting {
            resources.srcDir(rootProject.projectDir.resolve("../schema"))
            resources.include("vault.sql")
            dependencies {
                implementation("org.xerial:sqlite-jdbc:3.45.2.0")
                // Keep the daemon's Ktor graph on the same major/minor as the MCP SDK.
                implementation("io.ktor:ktor-client-core:3.5.1")
                implementation("io.ktor:ktor-client-cio:3.5.1")
                implementation("io.ktor:ktor-client-content-negotiation:3.5.1")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.1")
            }
        }

        // RFC-0108 Phase 4: Android target created but not activated (awaiting Google Maven access)
        // val androidMain by getting {
        //     dependencies {
        //         implementation("androidx.security:security-crypto:1.1.0-alpha06")
        //     }
        // }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            }
        }
    }
}
