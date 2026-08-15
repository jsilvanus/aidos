plugins {
    kotlin("multiplatform") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("com.android.library") version "8.5.2"
}

kotlin {
    androidTarget()

    sourceSets {
        androidMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.9.1")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
            implementation("androidx.core:core-ktx:1.10.1")
            implementation("androidx.appcompat:appcompat:1.6.1")
            implementation("com.squareup.okhttp3:okhttp:4.11.0")
            
            // Kernel types (frozen contract)
            implementation(project(":kernel"))
        }
    }
}

android {
    namespace = "fi.italeino.aidos.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
