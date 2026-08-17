plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":mcp-core"))
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }

    // See :mcp-core — same reasoning, and it matters more here: this layer's whole purpose is to
    // be lifted out of Aidos, so it is the one that must stay clean on someone else's toolchain.
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}
