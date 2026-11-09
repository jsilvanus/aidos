plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "dev.aidos.mcp.binder.android"
    compileSdk = 35
    defaultConfig {
        // The Binder library needs 28 for PackageInfo.signingInfo (provider certificate verification).
        minSdk = 28
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    // Android-free McpTransport.Binder and the McpBinderConnector seam.
    api(project(":mcp-core"))
    // Local Maven until the library is published (publishToMavenLocal in kotlin-mcp-android-binder).
    api("fi.italeino.mcp:binder-android:0.1.0-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
