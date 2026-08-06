plugins {
    kotlin("multiplatform")
    // id("com.android.library") — uncomment when AGP is resolvable (needs dl.google.com).
}

kotlin {
    jvm()
    // androidTarget() — uncomment together with the plugin above.

    sourceSets {
        // M28/M31 platform-neutral logic lives in commonMain so that when androidTarget()
        // is wired (needs AGP from dl.google.com), no file moves are required.
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation(project(":api"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
        }

        val jvmMain by getting {
            dependencies {
                implementation(project(":kernel"))
                implementation(project(":api"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(project(":api"))
                implementation(kotlin("test-junit"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            }
        }
    }
}

// android { ... } — uncomment when androidTarget() is wired.
