plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":kernel"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.xerial:sqlite-jdbc:3.45.2.0")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            }
        }
    }
}
