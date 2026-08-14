plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            // TODO(RFC-0103): This dependency on :kernel violates RFC-0103's architectural
            // requirement that agent/ and engine/ do not share a compile-time dependency graph.
            // During RFC-0103 implementation, refactor cookbook to not depend on kernel types
            // directly, or move kernel into engine/ (the latter is not recommended).
            implementation(project(":kernel"))
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
