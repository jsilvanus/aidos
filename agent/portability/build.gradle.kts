plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

// RFC-0041 Export & Import. JVM only for now: the desktop CLI and the desktop engine drive it;
// the Android host adopts it when its project directories are reachable as java.io.File (they are).
kotlin {
    jvm()

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":kernel"))
                implementation(project(":storage"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                // OpenDatabase exposes a SqlDriver, so its types are needed at compile time.
                implementation("app.cash.sqldelight:runtime:2.0.2")
                // VACUUM INTO and the import-time rewrite go through plain JDBC on a file nobody
                // else has open; the runtime's SqlDriver is only used for the migration open.
                implementation("org.xerial:sqlite-jdbc:3.45.2.0")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
            }
        }
    }

    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}
