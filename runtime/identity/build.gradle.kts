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
            implementation(project(":storage"))
            implementation(project(":settings"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            implementation("app.cash.sqldelight:runtime:2.0.2")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
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
        // expect/actual classes are Beta in Kotlin 2.x; suppress the warning so -Werror doesn't
        // trip on our UuidV7Generator expect class. Remove when the feature stabilises.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

android {
    namespace = "dev.aidos.identity"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
