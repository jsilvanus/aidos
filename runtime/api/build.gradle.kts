plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    // id("com.android.library") — uncomment when AGP is resolvable (needs dl.google.com).
}

kotlin {
    jvm()
    // androidTarget() — uncomment together with the plugin above.

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

// android { ... } — uncomment when androidTarget() is wired.
