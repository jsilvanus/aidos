plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
}

kotlin {
    jvmToolchain(21)

    androidTarget()
    // The jvm() target exists so the parts of this module that are ordinary Kotlin — wire-format
    // mapping, SSE frame parsing, capability and version negotiation — can be compiled and
    // unit-tested without an emulator or an Android SDK at all. Only the Binder handshake
    // genuinely needs Android, and that stays in androidMain.
    jvm()

    applyDefaultHierarchyTemplate()

    sourceSets {
        // Neither target's default source set is the right home for code that is pure JVM but not
        // pure Kotlin: `commonMain` cannot see java.util Base64 etc, and `androidMain` would hide
        // it from the jvm() target. This intermediate source set is the one both can share.
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())

            dependencies {
                // Deliberately NOT :kernel (Dictator plan D-1) — a third-party consumer should be
                // able to ask for a chat completion without linking Aidos's frozen contract
                // types. The ModelAdapter bindings that do need :kernel live in :adapters.
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                // The loopback HTTP client, including SSE streaming (S2). Plain sockets to
                // 127.0.0.1 — nothing here needs Android, so it lives in the shared source set
                // and is exercised by MockWebServer under jvmTest, not only on-device.
                implementation("com.squareup.okhttp3:okhttp:4.11.0")
            }
        }

        androidMain.get().dependsOn(jvmAndAndroidMain)
        jvmMain.get().dependsOn(jvmAndAndroidMain)

        androidMain.dependencies {
            implementation("androidx.core:core-ktx:1.10.1")
            implementation("androidx.appcompat:appcompat:1.6.1")
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            implementation("com.squareup.okhttp3:mockwebserver:4.11.0")
        }
    }
}

android {
    namespace = "fi.italeino.aidos.sdk.client"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // RFC-0103: the Binder handshake client needs its own compiled copy of Engine's
    // IEngineHandshake.aidl (engine/ and sdk/ are separate Gradle projects with no shared
    // module) to call across the Binder boundary — see src/androidMain/aidl.
    buildFeatures {
        aidl = true
    }
}
