plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

kotlin {
    jvm()
    androidTarget()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            // D35: the driver only, not SQLDelight's .sq schema codegen. schema/ stays the one
            // canonical DDL (RFC-0040); this is the KMP SqlDriver interface gitsema-kotlin also
            // uses, so both libraries share one SQLite build per process on Android.
            implementation("app.cash.sqldelight:runtime:2.0.2")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        val jvmMain by getting {
            // schema/ is read directly from its one canonical location (RFC-0040) rather than
            // copied into this module, so there is exactly one file a change to the DDL touches.
            resources.srcDir(rootProject.projectDir.resolve("../schema"))
            resources.include("*.sql")
            dependencies {
                implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
                // sqlite-driver depends on this at runtime only; SQLiteConfig (used to bake WAL /
                // synchronous / foreign_keys / busy_timeout into every connection the driver
                // opens, since PRAGMA after the fact only reaches one connection) needs it at
                // compile time too.
                implementation("org.xerial:sqlite-jdbc:3.45.2.0")
            }
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

android {
    namespace = "dev.aidos.storage"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
