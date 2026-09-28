plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("maven-publish")
}

kotlin {
    jvmToolchain(21)
    jvm()
    androidTarget {
        // Required for the Android target to get a Maven publication at all — see
        // sdk/client's and sdk/adapters' build.gradle.kts for the identical requirement.
        publishLibraryVariants("release")
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }

        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }
    }

    compilerOptions {
        // The kernel is a contract. Warnings here are design errors.
        allWarningsAsErrors.set(true)
    }
}

group = "fi.italeino.aidos"
// Dictator plan D-4 follow-up: :kernel is never published *as a source dependency* — every
// in-monorepo consumer (agent/, engine/, sdk/) still includes it by path, per RFC-0103's "depended
// on by everything, depends on none" design. This only gives it real Maven coordinates so
// aidos-sdk-adapters' published POM resolves instead of pointing at Gradle's
// groupId=aidos-sdk/version=unspecified placeholder. Same version property and default as
// sdk/client and sdk/adapters so a single `gradle publish -PaidosSdkVersion=...` run (kernel is
// included into the sdk/ build per sdk/settings.gradle.kts) publishes matching coordinates for all
// three.
version = (findProperty("aidosSdkVersion") as String?) ?: "0.1.0"

android {
    namespace = "dev.aidos.kernel"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

publishing {
    // Same GitHub Packages registry sdk/client and sdk/adapters publish to. No artifactId rename
    // here (unlike those two): the default "kernel"/"kernel-jvm" is already distinct enough
    // combined with this groupId, so fi.italeino.aidos:kernel-jvm is the coordinate as-is.
    repositories {
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
