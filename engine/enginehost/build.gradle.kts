plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
}

/**
 * Host-agnostic Aidos Engine pieces (RFC-0103): the loopback HTTP server, token issuance,
 * approval-store contract, request manager and UI data models. Shared by the Android app
 * (:androidapp, hosted in EngineService) and the Windows debug app (:desktopapp, hosted
 * in-process). Split out of :androidapp's jvmAndAndroidMain because a JVM app cannot depend on
 * an Android *application* module.
 */
kotlin {
    jvmToolchain(17)
    jvm()
    androidTarget()

    applyDefaultHierarchyTemplate()

    sourceSets {
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                api(project(":kernel"))
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                implementation("io.ktor:ktor-server-core:3.5.2")
                implementation("io.ktor:ktor-server-cio:3.5.2")
                implementation("io.ktor:ktor-server-content-negotiation:3.5.2")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
                implementation("io.ktor:ktor-server-auth:3.5.2")
                // KtorEffectBroker takes an HttpClient; each host supplies its own client engine.
                api("io.ktor:ktor-client-core:3.5.2")
                implementation("commons-codec:commons-codec:1.16.0")
            }
        }

        androidMain.get().dependsOn(jvmAndAndroidMain)
        jvmMain.get().dependsOn(jvmAndAndroidMain)

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
                implementation("io.ktor:ktor-server-test-host:3.5.2")
            }
        }
    }
}

android {
    namespace = "fi.italeino.aidos.engine.host"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
