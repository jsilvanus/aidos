plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("com.android.application")
}

kotlin {
    jvm()
    androidTarget()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }

        val jvmMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                // jvmMain has no Compose UI of its own, but the Compose Compiler Gradle plugin
                // (kotlin.plugin.compose) runs its version check against every compilation in
                // this module, androidTarget included — it fails hard without the runtime on
                // the classpath even here (same workaround as agent/androidapp).
                implementation("androidx.compose.runtime:runtime:1.6.0")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            }
        }

        val androidMain by getting {
            dependencies {
                implementation(project(":modelruntime"))
                implementation(project(":cookbook"))
                implementation(project(":huggingface"))
                implementation(project(":downloads"))
                implementation(project(":models"))
                implementation(project(":voice"))

                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

                // Compose and Material Design 3
                implementation("androidx.compose.ui:ui:1.6.0")
                implementation("androidx.compose.material3:material3:1.1.0")
                implementation("androidx.compose.foundation:foundation:1.6.0")
                implementation("androidx.activity:activity-compose:1.8.0")
                implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.1")
                implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.1")

                // Navigation
                implementation("androidx.navigation:navigation-compose:2.7.0")

                // Android core
                implementation("androidx.core:core-ktx:1.10.1")
                implementation("androidx.appcompat:appcompat:1.6.1")
                implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")
                // EngineService : LifecycleService (RFC-0103) — the foreground service hosting
                // the Engine core.
                implementation("androidx.lifecycle:lifecycle-service:2.6.1")
            }
        }
    }
}

android {
    namespace = "fi.italeino.aidos.engine"
    compileSdk = 34

    defaultConfig {
        applicationId = "fi.italeino.aidos.engine"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}
