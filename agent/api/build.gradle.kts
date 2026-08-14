plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
}

kotlin {
    jvm()
    androidTarget()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            // ProjectRegistry (user-scope project_id -> path cache) is fully portable commonMain
            // code; RealRuntimeClient uses it directly rather than through a platform adapter.
            implementation(project(":identity"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            // SqlDriver type only -- RealRuntimeClient never opens a driver itself (that's
            // platform-specific, injected), it just holds and queries ones it's given.
            implementation("app.cash.sqldelight:runtime:2.0.2")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }

        val jvmMain by getting {
            dependencies {
                // JVM-specific adapters (JvmProjectStorage, JvmProjectLocker) wiring
                // RealRuntimeClient's injected SqlDriver/ProjectLocker seams to real
                // implementations. Android's equivalents are follow-up work (RFC-0050 Future
                // Work: SAF/scoped storage), same status as capability's SqliteDirHandle.
                implementation(project(":storage"))
                implementation(project(":lock"))
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
                implementation("org.xerial:sqlite-jdbc:3.45.2.0")
            }
        }
    }

    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

android {
    namespace = "dev.aidos.api"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
