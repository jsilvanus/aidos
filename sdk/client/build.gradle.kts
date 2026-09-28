plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("maven-publish")
}

kotlin {
    jvmToolchain(21)

    androidTarget {
        // Required for the Android target to get a Maven publication at all (Dictator plan S3) —
        // without it, `publishing {}` below silently has nothing to publish for this target.
        publishLibraryVariants("release")
    }
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

group = "fi.italeino.aidos.sdk"
// Dictator plan D-4: the SDK is "versioned and distributed independently of both Aidos Agent and
// Aidos Engine" (RFC-0103) — that's what publishing to GitHub Packages, rather than only ever
// being source-included, is for. CI overrides this with -PaidosSdkVersion=<build-number+commit>;
// local/dev publishes fall back to the same base version the Agent/Engine apps currently carry.
version = (findProperty("aidosSdkVersion") as String?) ?: "0.1.0"

publishing {
    // Kotlin Multiplatform creates one publication per target (kotlinMultiplatform, jvm,
    // androidRelease) plus the root metadata publication, each defaulting to an artifactId built
    // from this project's own name ("client") — rename to the artifact name the plan actually
    // specifies (aidos-sdk-client), so a consumer's dependency coordinate reads the way
    // docs/dictator-sdk-integration-plan.md names it.
    publications.withType<MavenPublication>().configureEach {
        artifactId = artifactId.replace(project.name, "aidos-sdk-${project.name}")
    }

    repositories {
        // Same GitHub Packages registry agent/settings.gradle.kts and engine/settings.gradle.kts
        // already read gitsema-kotlin from — this repo (jsilvanus/aidos) is both the SDK's home
        // and where it publishes to, unlike gitsema-kotlin, which lives in its own repo.
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/jsilvanus/aidos")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: "token"
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}
