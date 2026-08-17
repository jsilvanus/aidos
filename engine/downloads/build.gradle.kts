import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

kotlin {
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }
    androidTarget()

    sourceSets {
        val commonMain by getting
        val commonTest by getting
        val jvmMain by getting
        val jvmTest by getting
        val androidMain by getting

        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
        }
        androidMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
    }
}

android {
    namespace = "dev.aidos.downloads"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }
}
