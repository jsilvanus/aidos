plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    // Was missing: OpenAiSchema.kt's @Serializable classes had no serializer codegen without
    // this, which fails at runtime (SerializationException), not at compile time — nothing
    // exercised it because the jvmTest gap below meant nothing on the jvm() target ever called
    // Json.encodeToString on one of these types. Found while doing this split, fixed alongside it.
    kotlin("plugin.serialization")
    id("com.android.application")
}

kotlin {
    jvmToolchain(21)
    jvm()
    androidTarget()

    applyDefaultHierarchyTemplate()

    sourceSets {
        // Neither target's default source set is the right home for code that is pure JVM/Kotlin
        // but needs to be visible to both `jvm()` and `androidTarget()`: `commonMain` cannot see
        // java.security/java.time, and `androidMain` would hide it from the jvm() target — the
        // same problem sdk/client's jvmAndAndroidMain solves, for the same reason. This is where
        // EngineHttpServer, its wire types, and the other pieces that were previously stuck in
        // androidMain purely for lack of a better home now live, so jvmTest can actually exercise
        // them (docs/dictator-sdk-integration-plan.md's Risks section has the diagnosis: jvmTest
        // is a different KMP target than androidMain and can never see androidMain's classes
        // regardless of dependency wiring — moving the code is the only fix).
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())

            dependencies {
                // ModelRuntime/ModelAdapter (EngineHttpServer's constructor) and the kernel types
                // the OpenAI-shaped request conversion builds. Not :modelruntime — nothing here
                // needs the concrete llama.cpp-backed implementation, only the kernel contract.
                implementation(project(":kernel"))

                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

                // HTTP server for OpenAI-compatible endpoints (RFC-0103). Ktor's CIO server
                // engine is plain Kotlin/JVM — nothing here is Android-specific, unlike the HTTP
                // *client* engine (ktor-client-android, still androidMain-only below).
                implementation("io.ktor:ktor-server-core:3.5.2")
                implementation("io.ktor:ktor-server-cio:3.5.2")
                implementation("io.ktor:ktor-server-content-negotiation:3.5.2")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
                implementation("io.ktor:ktor-server-auth:3.5.2")

                // Token generation and management (TokenManager)
                implementation("commons-codec:commons-codec:1.16.0")
            }
        }

        androidMain.get().dependsOn(jvmAndAndroidMain)
        jvmMain.get().dependsOn(jvmAndAndroidMain)

        val jvmMain by getting {
            dependencies {
                // jvmMain has no Compose UI of its own, but the Compose Compiler Gradle plugin
                // (kotlin.plugin.compose) runs its version check against every compilation in
                // this module, androidTarget included — it fails hard without the runtime on
                // the classpath even here (same workaround as agent/androidapp).
                implementation("androidx.compose.runtime:runtime:1.7.5")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
                implementation("io.ktor:ktor-server-test-host:3.5.2")
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

                // Compose and Material Design 3
                implementation("androidx.compose.ui:ui:1.7.5")
                implementation("androidx.compose.material3:material3:1.3.1")
                implementation("androidx.compose.material:material-icons-extended:1.7.5")
                implementation("com.google.android.material:material:1.12.0")
                implementation("androidx.compose.foundation:foundation:1.7.5")
                implementation("androidx.activity:activity-compose:1.9.3")
                implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
                implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

                // Navigation
                implementation("androidx.navigation:navigation-compose:2.8.3")

                // Android core
                implementation("androidx.core:core-ktx:1.15.0")
                implementation("androidx.core:core-splashscreen:1.0.1")
                implementation("androidx.appcompat:appcompat:1.7.0")
                implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
                // EngineService : LifecycleService (RFC-0103) — the foreground service hosting
                // the Engine core.
                implementation("androidx.lifecycle:lifecycle-service:2.8.6")

                // Encrypted shared preferences for app approval storage (RFC-0103)
                implementation("androidx.security:security-crypto:1.1.0")

                // HTTP client for calling the Engine (RFC-0103, Phase E - E.1 HTTP client)
                implementation("io.ktor:ktor-client-android:3.5.2")
                implementation("io.ktor:ktor-client-serialization:3.5.2")
                implementation("io.ktor:ktor-client-content-negotiation:3.5.2")
            }
        }
    }
}

android {
    namespace = "fi.italeino.aidos.engine"
    compileSdk = 35

    defaultConfig {
        applicationId = "fi.italeino.aidos.engine"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // Enable AIDL compilation for Binder handshake interface (RFC-0103)
    buildFeatures {
        aidl = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}
