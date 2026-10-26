plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

/**
 * Agent runtime for the desktop Engine host (AGENT-MANAGEMENT.md, "First vertical slice").
 *
 * Runs the Aidos agent loop ([dev.aidos.amp.AmpAgentRunner]) behind the /v1/agent paths on the same
 * loopback server and bearer tokens as the model API. JVM only: Android hosts the agent in its own
 * service and reaches provider tools over Binder instead.
 */
kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":kernel"))
    api(project(":amp"))
    implementation(project(":enginehost"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("io.ktor:ktor-server-core:3.5.2")
    implementation("io.ktor:ktor-server-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:3.5.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test {
    useJUnitPlatform()
}
