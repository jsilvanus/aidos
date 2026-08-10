plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    jvm()

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":kernel"))
                implementation(project(":api"))
                implementation(project(":cli"))
                implementation(project(":storage"))
                implementation(project(":identity"))
                implementation(project(":executor"))
                implementation(project(":broker"))
                implementation(project(":capability"))
                implementation(project(":filesystem"))
                implementation(project(":git"))
                implementation(project(":prompt"))
                implementation(project(":routing"))
                implementation(project(":vault"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
                implementation("org.xerial:sqlite-jdbc:3.45.2.0")
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

// A `kotlin("multiplatform")` project has no Gradle `application` plugin support out of the
// box (that plugin expects a Gradle Java `main` sourceSet, which the jvm() target does not
// create) -- so the daemon's entry point is run via a hand-wired JavaExec task instead,
// against the jvm target's own compilation output and runtime classpath (M10, RFC-0052).
val jvmMainCompilation = kotlin.targets.getByName("jvm").compilations.getByName("main")

tasks.register<JavaExec>("run") {
    group = "application"
    description = "Run the Aidos daemon (dev.aidos.daemon.MainKt)"
    mainClass.set("dev.aidos.daemon.MainKt")
    classpath = jvmMainCompilation.output.allOutputs + jvmMainCompilation.runtimeDependencyFiles!!
    standardInput = System.`in`
}
