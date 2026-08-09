plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }

        val jvmMain by getting {
            dependencies {
                implementation(project(":cookbook"))

                // For file hashing and path operations
                implementation("commons-codec:commons-codec:1.16.0")

                // llama.cpp Java binding for local inference (RFC-0022, M21)
                implementation("de.kherud:llama-java:0.3.2")
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
