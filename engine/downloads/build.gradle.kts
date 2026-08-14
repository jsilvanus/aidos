plugins {
    kotlin("multiplatform")
}

kotlin {
    jvmToolchain(21)
    jvm()

    sourceSets {
        commonMain.dependencies {
            // TODO(RFC-0103): This dependency on :kernel violates RFC-0103's requirement.
            // Refactor during RFC-0103 implementation.
            implementation(project(":kernel"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
