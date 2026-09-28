import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
}

/**
 * Windows debug app for Aidos Engine (RFC-0103).
 *
 * Hosts the Engine in-process (JVM llama.cpp runtime, JDBC SQLite catalog, loopback HTTP server)
 * and renders the shared :engineui panes, so engine behaviour can be debugged without a phone.
 * This is a local debug tool, not RFC-0103's (unbuilt) desktop Engine host: no Unix-socket
 * transport and no Binder, so app approvals are exercised through a simulated handshake.
 */
kotlin {
    // :kernel is compiled for JVM 21, so the app that loads it at runtime must run on 21.
    jvmToolchain(21)
}

dependencies {
    implementation(project(":engineui"))
    implementation(project(":enginehost"))
    implementation(project(":modelruntime"))
    implementation(project(":models"))
    implementation(project(":downloads"))
    implementation(project(":huggingface"))
    implementation(project(":cookbook"))

    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("io.ktor:ktor-client-cio:3.5.2")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

compose.desktop {
    application {
        mainClass = "fi.italeino.aidos.engine.desktop.MainKt"
        // Compose's run/package tasks default to JAVA_HOME's JDK, not the toolchain; the classes
        // (and :kernel) are JVM 21 bytecode, so launch with the toolchain's JDK instead.
        javaHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }.get().metadata.installationPath.asFile.absolutePath
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "AidosEngineDebug"
            packageVersion = "0.1.0"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
