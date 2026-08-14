plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()
    
    sourceSets {
        commonMain.dependencies {
            // TODO(RFC-0103): These dependencies on :kernel and :settings violate RFC-0103's
            // requirement that agent/ and engine/ do not share a compile-time dependency graph.
            // Refactor during RFC-0103 implementation.
            implementation(project(":kernel"))
            implementation(project(":settings"))
            // TODO(RFC-0103): Clarify whether this refers to agent/androidapp or engine/androidapp.
            // If agent/androidapp, this violates RFC-0103 architectural boundaries.
            implementation(project(":androidapp"))
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
        }

        val jvmMain by getting {
            dependencies {
                implementation(project(":androidapp"))
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation(project(":androidapp"))
            }
        }
    }
}
