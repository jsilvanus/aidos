plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

/**
 * Model providers reached over HTTP (RFC-0021), starting with OpenAI-compatible servers such as a
 * local Ollama. A provider's models join the Engine's catalog beside the downloaded ones, and each
 * is classified for locality (RFC-0104): remote unless the developer toggle says otherwise and the
 * host is loopback. JVM only for now (the desktop host).
 */
kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":kernel"))
    implementation(project(":modelruntime"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation(kotlin("test"))
    testImplementation(project(":enginehost"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test {
    useJUnitPlatform()
}
