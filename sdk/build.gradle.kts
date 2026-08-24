plugins {
    // Kotlin 2.4.10 matches agent/ and engine/. This is not cosmetic: `kernel` is source-included
    // by all three (settings.gradle.kts), and one shared module cannot be compiled by two
    // different Kotlin versions.
    kotlin("multiplatform") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    id("com.android.library") version "8.5.2"
}

kotlin {
    jvmToolchain(21)

    androidTarget()
    // RFC-0103 ships the SDK as an Android library, and that does not change. The jvm() target
    // exists so the parts of it that are ordinary Kotlin — wire-format mapping, capability and
    // version negotiation, and (from S2) SSE frame parsing — can be compiled and unit-tested
    // without an emulator or an Android SDK at all. Only the Binder handshake genuinely needs
    // Android, and that stays in androidMain.
    jvm()

    applyDefaultHierarchyTemplate()

    sourceSets {
        // Neither target's default source set is the right home for code that is pure JVM but not
        // pure Kotlin: `commonMain` cannot see java.net/java.util, and `androidMain` would hide it
        // from the jvm() target. This intermediate source set is the one both can share.
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())

            dependencies {
                // RFC-0103: the ModelAdapter implementations here realize kernel's contract types,
                // which must be the *same* types Aidos Agent deserializes into — hence the source
                // include rather than a vendored copy free to drift.
                implementation(project(":kernel"))

                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                // EngineModelAdapter parses Engine's OpenAI-shaped responses with
                // kotlinx-serialization.
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            }
        }

        androidMain.get().dependsOn(jvmAndAndroidMain)
        jvmMain.get().dependsOn(jvmAndAndroidMain)

        androidMain.dependencies {
            implementation("androidx.core:core-ktx:1.10.1")
            implementation("androidx.appcompat:appcompat:1.6.1")
            implementation("com.squareup.okhttp3:okhttp:4.11.0")
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
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

    // RFC-0103: the Binder handshake client needs its own compiled copy of Engine's
    // IEngineHandshake.aidl (engine/ and sdk/ are separate Gradle projects with no shared
    // module) to call across the Binder boundary — see src/androidMain/aidl.
    buildFeatures {
        aidl = true
    }
}
