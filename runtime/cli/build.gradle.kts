plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    jvm()

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":api"))
                // SocketRuntimeClient implements DiffQueries, whose signatures reference
                // dev.aidos.kernel.FileDiff directly (M10) -- not exposed transitively through
                // :api's own `implementation(project(":kernel"))`.
                implementation(project(":kernel"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            }
        }
    }

    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

// See runtime/daemon/build.gradle.kts for why this is a hand-wired JavaExec task rather than
// the Gradle `application` plugin (M10, RFC-0052).
val jvmMainCompilation = kotlin.targets.getByName("jvm").compilations.getByName("main")

tasks.register<JavaExec>("run") {
    group = "application"
    description = "Run the Aidos CLI (dev.aidos.cli.MainKt) -- pass args with --args=\"...\""
    mainClass.set("dev.aidos.cli.MainKt")
    classpath = jvmMainCompilation.output.allOutputs + jvmMainCompilation.runtimeDependencyFiles!!
    standardInput = System.`in`
}
