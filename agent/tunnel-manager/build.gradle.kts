plugins {
    kotlin("jvm")
}

// RFC-0108 Phase 3: Engine adapter layer for the standalone sshtunnel library.
// Bridges the tunnel library with Engine services: vault KeyProvider, egress grants,
// audit logging, remote-access switch. Depends on Agent modules but not kernel directly
// (vault provides kernel bridge).
dependencies {
    api(project(":ssh-tunnel"))
    api(project(":vault"))
    
    implementation(project(":capability"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    
    testImplementation(kotlin("test-junit"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

tasks.test {
    useJUnitPlatform()
}
